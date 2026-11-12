/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.askflow.admin.eval;

import cn.hutool.core.util.StrUtil;
import com.askflow.admin.config.EvalJudgeProperties;
import com.askflow.admin.config.EvalRunProperties;
import com.askflow.admin.dao.entity.EvalCaseResultDO;
import com.askflow.admin.dao.entity.EvalRunDO;
import com.askflow.admin.dao.entity.EvalSampleDO;
import com.askflow.admin.dao.mapper.EvalCaseResultMapper;
import com.askflow.admin.dao.mapper.EvalRunMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 评测运行引擎
 * <p>
 * 职责边界：只有编排——建运行上下文、按并发度跑样本、累加进度、汇总指标、收尾。
 * 单条样本怎么跑在 {@link EvalSampleRunner}，指标怎么算在 {@link EvalMetricCalculator}。
 * <p>
 * 不用 MQ：评测是低频任务，没有分片和互斥需求，线程池 + 落表轮询已经够用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalRunEngine {

    private final EvalSampleRunner sampleRunner;
    private final EvalMetricCalculator metricCalculator;
    private final EvalRunProperties runProperties;
    private final EvalJudgeProperties judgeProperties;
    private final EvalRunMapper runMapper;
    private final EvalCaseResultMapper resultMapper;

    @Resource(name = "evalRunExecutor")
    private Executor runExecutor;

    @Resource(name = "evalSampleExecutor")
    private Executor sampleExecutorPool;

    /**
     * 运行中的取消令牌，供 cancel 接口找到在途的流式句柄
     */
    private final Map<String, EvalRunCancellation> running = new ConcurrentHashMap<>();

    /**
     * 全局模型调用许可
     * <p>
     * 必须跨运行共享：模型 SSE 读取跑在全局 modelStreamExecutor 上，评测并发一高就会和线上对话抢线程池
     */
    private Semaphore modelPermits;

    @PostConstruct
    void init() {
        modelPermits = new Semaphore(Math.max(1, runProperties.getMaxConcurrentSamples()));
    }

    /**
     * 异步启动一次评测；池满时抛 RejectedExecutionException 由调用方转成友好提示
     */
    public void start(EvalRunDO run, List<EvalSampleDO> samples) {
        EvalRunCancellation cancellation = new EvalRunCancellation();
        running.put(run.getId(), cancellation);
        try {
            runExecutor.execute(() -> execute(run, samples, cancellation));
        } catch (RejectedExecutionException e) {
            running.remove(run.getId());
            markFailed(run.getId(), "评测任务繁忙，请稍后再试");
            throw e;
        }
    }

    /**
     * 取消一次运行
     *
     * @return 是否命中一个正在跑的运行
     */
    public boolean cancel(String runId) {
        EvalRunCancellation cancellation = running.get(runId);
        if (cancellation == null) {
            return false;
        }
        cancellation.cancel();
        return true;
    }

    // ==================== 主流程 ====================

    private void execute(EvalRunDO run, List<EvalSampleDO> samples, EvalRunCancellation cancellation) {
        String runId = run.getId();
        try {
            markRunning(runId);

            // 判分预算：按样本数上限分配，用完的样本只算确定性指标
            int judgeQuota = isJudgeEnabled(run) ? Math.max(0, judgeProperties.getMaxSamples()) : 0;
            AtomicInteger judged = new AtomicInteger();

            List<CompletableFuture<Void>> futures = new ArrayList<>(samples.size());
            for (EvalSampleDO sample : samples) {
                futures.add(CompletableFuture.runAsync(
                        () -> handleSample(run, sample, cancellation, judged, judgeQuota),
                        sampleExecutorPool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            if (cancellation.isCancelled()) {
                finish(runId, EvalConstants.RUN_CANCELLED, null, null);
                return;
            }
            List<EvalCaseResultDO> results = resultMapper.selectList(
                    Wrappers.lambdaQuery(EvalCaseResultDO.class).eq(EvalCaseResultDO::getRunId, runId));
            finish(runId, EvalConstants.RUN_SUCCESS, metricCalculator.calculate(results), null);
        } catch (Exception e) {
            log.error("[评测] 运行异常 runId={}", runId, e);
            markFailed(runId, StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), "评测运行异常"), 900));
        } finally {
            running.remove(runId);
        }
    }

    /**
     * 跑一条样本：限流 → 执行（失败重试一次）→ 落库 → 累加进度
     * <p>
     * 单条失败不能影响整批，也要保证 done + failed = total，否则前端进度永远到不了 100%
     */
    private void handleSample(EvalRunDO run, EvalSampleDO sample, EvalRunCancellation cancellation,
                              AtomicInteger judged, int judgeQuota) {
        if (cancellation.isCancelled()) {
            return;
        }
        boolean judgeAllowed = judgeQuota > 0 && judged.getAndIncrement() < judgeQuota;
        boolean acquired = false;
        try {
            modelPermits.acquire();
            acquired = true;

            EvalCaseResultDO result = null;
            int attempts = Math.max(1, runProperties.getMaxAttempts());
            for (int attempt = 1; attempt <= attempts; attempt++) {
                result = sampleRunner.execute(run, sample, cancellation, judgeAllowed);
                if (!EvalConstants.FINAL_ERROR.equals(result.getFinalStatus())) {
                    break;
                }
                log.warn("[评测] 样本失败，重试 {}/{} runId={} queryId={}",
                        attempt, attempts, run.getId(), sample.getQueryId());
            }
            result.setCreatedBy(run.getCreatedBy());
            resultMapper.insert(result);
            incrementProgress(run.getId(), EvalConstants.FINAL_ERROR.equals(result.getFinalStatus()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[评测] 样本落库失败 runId={} queryId={}", run.getId(), sample.getQueryId(), e);
            persistBrokenResult(run, sample, e);
            incrementProgress(run.getId(), true);
        } finally {
            if (acquired) {
                modelPermits.release();
            }
        }
    }

    /**
     * 兜底：执行器本身抛异常时也要留一条 error 结果，保证样本数与进度对得上
     */
    private void persistBrokenResult(EvalRunDO run, EvalSampleDO sample, Exception cause) {
        try {
            resultMapper.insert(EvalCaseResultDO.builder()
                    .runId(run.getId())
                    .queryId(sample.getQueryId())
                    .queryText(sample.getQueryText())
                    .groundTruth(sample.getGroundTruth())
                    .intentL1(sample.getIntentL1())
                    .intentL2(sample.getIntentL2())
                    .expectedRoute(sample.getExpectedRoute())
                    .requiresRag(sample.getRequiresRag())
                    .expectedDocIds(sample.getExpectedDocIds())
                    .answerBranch(EvalConstants.BRANCH_RAG)
                    .finalStatus(EvalConstants.FINAL_ERROR)
                    .errorMessage(StrUtil.maxLength(StrUtil.blankToDefault(cause.getMessage(), "样本执行异常"), 900))
                    .createdBy(run.getCreatedBy())
                    .build());
        } catch (Exception e) {
            log.error("[评测] 兜底结果写入失败 runId={} queryId={}", run.getId(), sample.getQueryId(), e);
        }
    }

    // ==================== 状态流转 ====================

    private void markRunning(String runId) {
        runMapper.update(null, Wrappers.lambdaUpdate(EvalRunDO.class)
                .set(EvalRunDO::getStatus, EvalConstants.RUN_RUNNING)
                .set(EvalRunDO::getStartedAt, new Date())
                .set(EvalRunDO::getUpdateTime, new Date())
                .eq(EvalRunDO::getId, runId));
    }

    /**
     * 进度用原子自增，避免并发样本各写各的快照值互相覆盖
     */
    private void incrementProgress(String runId, boolean failed) {
        LambdaUpdateWrapper<EvalRunDO> update = Wrappers.lambdaUpdate(EvalRunDO.class)
                .setSql("done_count = done_count + 1")
                .set(EvalRunDO::getUpdateTime, new Date())
                .eq(EvalRunDO::getId, runId);
        if (failed) {
            update.setSql("failed_count = failed_count + 1");
        }
        runMapper.update(null, update);
    }

    /**
     * 收尾写状态与指标
     * <p>
     * 用 updateById 而不是 UpdateWrapper：metrics_json 是 jsonb 列，
     * 实体字段上挂了 JsonbTypeHandler，走 Wrapper.set 会被当成 varchar 传参，PostgreSQL 直接报类型不匹配
     */
    private void finish(String runId, String status, String metricsJson, String errorMessage) {
        EvalRunDO patch = new EvalRunDO();
        patch.setId(runId);
        patch.setStatus(status);
        patch.setCompletedAt(new Date());
        patch.setMetricsJson(metricsJson);
        patch.setErrorMessage(errorMessage);
        runMapper.updateById(patch);
    }

    private void markFailed(String runId, String errorMessage) {
        finish(runId, EvalConstants.RUN_FAILED, null, errorMessage);
    }

    private boolean isJudgeEnabled(EvalRunDO run) {
        return run.getJudgeEnabled() != null && run.getJudgeEnabled() == 1 && judgeProperties.isEnabled();
    }
}
