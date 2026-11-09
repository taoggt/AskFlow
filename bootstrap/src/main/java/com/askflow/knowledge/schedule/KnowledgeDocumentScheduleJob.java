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

package com.askflow.knowledge.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.askflow.knowledge.config.KnowledgeScheduleProperties;
import com.askflow.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.askflow.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识库文档定时刷新任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleJob {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final Executor knowledgeChunkExecutor;
    private final KnowledgeScheduleProperties scheduleProperties;
    private final ScheduleLockManager lockManager;
    private final ScheduleRefreshProcessor scheduleRefreshProcessor;
    private final DocumentStatusHelper documentStatusHelper;

    /**
     * 回收「没有任何执行者」的 RUNNING 文档（补偿任务 / watchdog）
     * <p>覆盖三种卡死来源：消费者失联（租约过期）、消息丢失、可重试异常重试耗尽进入死信队列。
     * 超过阈值仍无执行者的文档重置为 FAILED，允许用户重新触发。
     * <p>判定依赖 {@code processing_token / processing_lease_until / update_time}，
     * 正在执行、正在等待 MQ 重投的任务都不会被误判。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverStuckRunningDocuments() {
        long leaseGraceMinutes = scheduleProperties.getProcessingLeaseGraceMinutes();
        long retryWindowMinutes = scheduleProperties.getChunkRetryWindowMinutes();
        int recovered = documentStatusHelper.recoverStuckRunning(
                leaseGraceMinutes,
                retryWindowMinutes,
                scheduleProperties.getBatchSize());
        if (recovered > 0) {
            log.warn("回收了 {} 个没有执行者的 RUNNING 文档，已重置为 FAILED（租约宽限 {} 分钟，无令牌窗口 {} 分钟）",
                    recovered, leaseGraceMinutes, retryWindowMinutes);
        }
    }

    @Scheduled(fixedDelayString = "${rag.knowledge.schedule.scan-delay-ms:10000}")
    public void scan() {
        Date now = new Date();
        List<KnowledgeDocumentScheduleDO> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getEnabled, 1)
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getNextRunTime)
                                .or()
                                .le(KnowledgeDocumentScheduleDO::getNextRunTime, now))
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
                        .orderByAsc(KnowledgeDocumentScheduleDO::getNextRunTime)
                        .last("LIMIT " + Math.max(scheduleProperties.getBatchSize(), 1))
        );

        if (schedules == null || schedules.isEmpty()) {
            return;
        }

        for (KnowledgeDocumentScheduleDO schedule : schedules) {
            if (schedule == null || schedule.getId() == null) {
                continue;
            }
            ScheduleLockLease lease = lockManager.tryAcquire(schedule.getId(), now);
            if (lease == null) {
                continue;
            }
            try {
                knowledgeChunkExecutor.execute(() -> scheduleRefreshProcessor.process(lease));
            } catch (RejectedExecutionException e) {
                log.error("定时任务提交失败: scheduleId={}, docId={}, kbId={}",
                        schedule.getId(), schedule.getDocId(), schedule.getKbId(), e);
                lockManager.release(lease);
            }
        }
    }
}
