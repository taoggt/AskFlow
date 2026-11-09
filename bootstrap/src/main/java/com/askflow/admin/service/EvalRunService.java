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

package com.askflow.admin.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.askflow.admin.config.EvalJudgeProperties;
import com.askflow.admin.config.EvalRunProperties;
import com.askflow.admin.controller.request.EvalRunCreateRequest;
import com.askflow.admin.controller.vo.EvalCaseResultVO;
import com.askflow.admin.controller.vo.EvalRunVO;
import com.askflow.admin.dao.entity.EvalCaseResultDO;
import com.askflow.admin.dao.entity.EvalDatasetDO;
import com.askflow.admin.dao.entity.EvalRunDO;
import com.askflow.admin.dao.entity.EvalSampleDO;
import com.askflow.admin.dao.mapper.EvalCaseResultMapper;
import com.askflow.admin.dao.mapper.EvalDatasetMapper;
import com.askflow.admin.dao.mapper.EvalRunMapper;
import com.askflow.admin.dao.mapper.EvalSampleMapper;
import com.askflow.admin.eval.EvalConstants;
import com.askflow.admin.eval.EvalJson;
import com.askflow.admin.eval.EvalRunEngine;
import com.askflow.framework.context.UserContext;
import com.askflow.framework.exception.ClientException;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * 评测运行管理：提交、取消、查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalRunService {

    private static final int DEFAULT_TOP_K = 10;

    private final EvalDatasetMapper datasetMapper;
    private final EvalSampleMapper sampleMapper;
    private final EvalRunMapper runMapper;
    private final EvalCaseResultMapper resultMapper;
    private final EvalRunEngine runEngine;
    private final EvalRunProperties runProperties;
    private final EvalJudgeProperties judgeProperties;

    /**
     * 提交一次评测：落运行记录后立刻返回，执行交给引擎
     * <p>
     * 样本数会被 maxSamples 截断：评测一次就可能几百次模型调用，不做上限很容易误操作烧掉额度
     */
    public EvalRunVO submit(EvalRunCreateRequest request) {
        if (!runProperties.isEnabled()) {
            throw new ClientException("评测功能未开启");
        }
        if (StrUtil.isBlank(request.datasetId())) {
            throw new ClientException("请选择评测集");
        }
        EvalDatasetDO dataset = datasetMapper.selectById(request.datasetId());
        if (dataset == null) {
            throw new ClientException("评测集不存在");
        }

        int limit = Math.max(1, runProperties.getMaxSamples());
        IPage<EvalSampleDO> samplePage = sampleMapper.selectPage(
                new Page<>(1, limit),
                Wrappers.lambdaQuery(EvalSampleDO.class)
                        .eq(EvalSampleDO::getDatasetId, dataset.getId())
                        .orderByAsc(EvalSampleDO::getSampleOrder));
        List<EvalSampleDO> samples = samplePage.getRecords();
        if (samples.isEmpty()) {
            throw new ClientException("该评测集还没有样本");
        }

        boolean judgeEnabled = request.judgeEnabled() == null || request.judgeEnabled();
        EvalRunDO run = EvalRunDO.builder()
                .datasetId(dataset.getId())
                .datasetName(dataset.getName())
                .status(EvalConstants.RUN_PENDING)
                .cancelFlag(0)
                .totalCount(samples.size())
                .doneCount(0)
                .failedCount(0)
                .topK(request.topK() == null ? DEFAULT_TOP_K : Math.max(1, request.topK()))
                .judgeEnabled(judgeEnabled && judgeProperties.isEnabled() ? 1 : 0)
                .judgeModel(StrUtil.blankToDefault(request.judgeModel(), judgeProperties.getModel()))
                .createdBy(StrUtil.nullToEmpty(UserContext.getUserId()))
                .build();
        runMapper.insert(run);

        try {
            runEngine.start(run, samples);
        } catch (RejectedExecutionException e) {
            throw new ClientException("评测任务繁忙，请稍后再试");
        }
        return toRunVO(runMapper.selectById(run.getId()));
    }

    public void cancel(String runId) {
        EvalRunDO run = requireRun(runId);
        if (!EvalConstants.RUN_PENDING.equals(run.getStatus())
                && !EvalConstants.RUN_RUNNING.equals(run.getStatus())) {
            return;
        }
        // 先让在途的流式生成停下来，再落取消标记，避免队列里的运行继续往下跑
        runEngine.cancel(runId);
        EvalRunDO patch = new EvalRunDO();
        patch.setId(runId);
        patch.setCancelFlag(1);
        runMapper.updateById(patch);
    }

    public IPage<EvalRunVO> pageRuns(String datasetId, long pageNo, long pageSize) {
        IPage<EvalRunDO> result = runMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.lambdaQuery(EvalRunDO.class)
                        .eq(StrUtil.isNotBlank(datasetId), EvalRunDO::getDatasetId, datasetId)
                        .orderByDesc(EvalRunDO::getCreateTime));
        Page<EvalRunVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toRunVO).toList());
        return voPage;
    }

    /**
     * 运行详情，指标一次性带出，前端不必再发一次请求
     */
    public EvalRunVO detail(String runId) {
        return toRunVO(requireRun(runId));
    }

    /**
     * 样本结果分页
     *
     * @param failedOnly 只看失败样本
     */
    public IPage<EvalCaseResultVO> pageResults(String runId, long pageNo, long pageSize, boolean failedOnly) {
        IPage<EvalCaseResultDO> result = resultMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.lambdaQuery(EvalCaseResultDO.class)
                        .eq(EvalCaseResultDO::getRunId, runId)
                        .eq(failedOnly, EvalCaseResultDO::getFinalStatus, EvalConstants.FINAL_ERROR)
                        .orderByAsc(EvalCaseResultDO::getQueryId));
        Page<EvalCaseResultVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        // 列表不返回 chunk 文本：一条样本可能有十条上下文，翻页时体积会失控
        voPage.setRecords(result.getRecords().stream()
                .map(item -> toResultVO(item, false))
                .toList());
        return voPage;
    }

    /**
     * 单条样本详情，带上命中的 chunk 文本与判分结果，用于人工核查
     */
    public EvalCaseResultVO resultDetail(String runId, String queryId) {
        EvalCaseResultDO item = resultMapper.selectOne(Wrappers.lambdaQuery(EvalCaseResultDO.class)
                .eq(EvalCaseResultDO::getRunId, runId)
                .eq(EvalCaseResultDO::getQueryId, queryId));
        if (item == null) {
            throw new ClientException("样本结果不存在");
        }
        return toResultVO(item, true);
    }

    private EvalRunDO requireRun(String runId) {
        EvalRunDO run = runMapper.selectById(runId);
        if (run == null) {
            throw new ClientException("评测运行不存在");
        }
        return run;
    }

    private EvalRunVO toRunVO(EvalRunDO run) {
        Object metrics = StrUtil.isBlank(run.getMetricsJson()) ? null : JSONUtil.parseObj(run.getMetricsJson());
        return new EvalRunVO(
                run.getId(),
                run.getDatasetId(),
                run.getDatasetName(),
                run.getStatus(),
                run.getTotalCount(),
                run.getDoneCount(),
                run.getFailedCount(),
                run.getTopK(),
                run.getJudgeEnabled(),
                run.getJudgeModel(),
                metrics,
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getCreateTime());
    }

    private EvalCaseResultVO toResultVO(EvalCaseResultDO item, boolean withContexts) {
        return new EvalCaseResultVO(
                item.getId(),
                item.getQueryId(),
                item.getQueryText(),
                item.getIntentL2(),
                item.getIntentPred(),
                EvalJson.toList(item.getExpectedDocIds()),
                EvalJson.toList(item.getRetrievedDocIds()),
                withContexts ? EvalJson.toList(item.getRetrievedContexts()) : List.of(),
                withContexts ? EvalJson.toList(item.getRetrievedContextDocIds()) : List.of(),
                withContexts ? item.getRewrittenQuestion() : null,
                withContexts ? item.getResponse() : null,
                item.getAnswerBranch(),
                item.getFinalStatus(),
                item.getLatencyMs(),
                item.getFirstTokenMs(),
                StrUtil.isBlank(item.getScoresJson()) ? null : JSONUtil.parseObj(item.getScoresJson()),
                item.getErrorMessage());
    }
}
