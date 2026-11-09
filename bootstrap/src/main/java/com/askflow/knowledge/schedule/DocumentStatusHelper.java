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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.askflow.framework.exception.ClientException;
import com.askflow.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;
import com.askflow.knowledge.dao.entity.KnowledgeDocumentDO;
import com.askflow.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.askflow.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.askflow.knowledge.enums.DocumentStatus;
import com.askflow.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStatusHelper {

    private static final String SYSTEM_USER = "system";

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentChunkLogMapper chunkLogMapper;

    public boolean tryMarkRunning(String docId) {
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        // 抢占文档运行权的同时清空上一次任务残留的令牌，新的分块任务会重新登记
                        .set(KnowledgeDocumentDO::getCurrentChunkTaskId, null)
                        .set(KnowledgeDocumentDO::getProcessingToken, null)
                        .set(KnowledgeDocumentDO::getProcessingLeaseUntil, null)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        ) > 0;
    }

    public void markFailedIfRunning(String docId) {
        documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .set(KnowledgeDocumentDO::getProcessingToken, null)
                        .set(KnowledgeDocumentDO::getProcessingLeaseUntil, null)
                        .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );
    }

    public void applyRefreshedFileMetadata(String docId, StoredFileDTO stored) {
        KnowledgeDocumentDO update = KnowledgeDocumentDO.builder()
                .id(docId)
                .docName(stored.getOriginalFilename())
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .updatedBy(SYSTEM_USER)
                .build();
        int updated = documentMapper.updateById(update);
        if (updated == 0) {
            throw new ClientException("文档不存在");
        }
    }

    /**
     * 回收「没有任何执行者」的 RUNNING 文档，把它们重置为 FAILED，允许用户重新触发
     * <p>只处理以下三种情况，任何一个正在被消费者执行的任务都不会被误杀：
     * <ol>
     *   <li>持有执行令牌但租约已过期超过宽限期：消费者失联（进程崩溃、被 kill -9、
     *       宿主机宕机），不会再有人写回结果；</li>
     *   <li>没有执行令牌且超过重试窗口仍无人领取：消息丢失、消费组长时间无实例，
     *       或者可重试异常重试耗尽后进入死信队列；</li>
     *   <li>令牌存在但租约缺失（异常数据）且超过重试窗口：按空闲时长兜底。</li>
     * </ol>
     * <p>每个文档单独做一次带条件的 UPDATE（CAS）复核，多实例并发执行不会互相干扰，
     * 也不会覆盖刚刚被消费者领取的任务。
     *
     * @param leaseGraceMinutes   租约过期后的宽限时间（分钟）
     * @param retryWindowMinutes  无令牌时的空闲窗口（分钟），需大于 MQ 单次重试最大间隔（2 小时）
     * @param batchSize           单次最多处理的文档数
     * @return 实际回收的文档数
     */
    public int recoverStuckRunning(long leaseGraceMinutes, long retryWindowMinutes, int batchSize) {
        Date now = new Date();
        long safeLeaseGrace = Math.max(leaseGraceMinutes, 1);
        // 下限 120 分钟：RocketMQ 最后一次重试的间隔是 2 小时，
        // 配置过小会把"正在等待最后一次重试"的任务提前判死
        long safeRetryWindow = Math.max(retryWindowMinutes, 120);
        Date leaseExpiredBefore = new Date(now.getTime() - safeLeaseGrace * 60 * 1000);
        Date idleBefore = new Date(now.getTime() - safeRetryWindow * 60 * 1000);
        int limit = Math.max(batchSize, 1);

        List<KnowledgeDocumentDO> candidates = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentDO>()
                        .select(KnowledgeDocumentDO::getId,
                                KnowledgeDocumentDO::getCurrentChunkTaskId,
                                KnowledgeDocumentDO::getProcessingToken,
                                KnowledgeDocumentDO::getProcessingLeaseUntil,
                                KnowledgeDocumentDO::getUpdateTime)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .and(wrapper -> wrapper
                                .nested(w -> w.isNotNull(KnowledgeDocumentDO::getProcessingToken)
                                        .isNotNull(KnowledgeDocumentDO::getProcessingLeaseUntil)
                                        .lt(KnowledgeDocumentDO::getProcessingLeaseUntil, leaseExpiredBefore))
                                .or()
                                .nested(w -> w.isNull(KnowledgeDocumentDO::getProcessingToken)
                                        .lt(KnowledgeDocumentDO::getUpdateTime, idleBefore))
                                .or()
                                .nested(w -> w.isNotNull(KnowledgeDocumentDO::getProcessingToken)
                                        .isNull(KnowledgeDocumentDO::getProcessingLeaseUntil)
                                        .lt(KnowledgeDocumentDO::getUpdateTime, idleBefore)))
                        .orderByAsc(KnowledgeDocumentDO::getUpdateTime)
                        .last("LIMIT " + limit)
        );

        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int recovered = 0;
        for (KnowledgeDocumentDO candidate : candidates) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (!failStaleChunkTask(candidate, now)) {
                continue;
            }
            recovered++;
        }
        return recovered;
    }

    /**
     * 把单个「无执行者」的 RUNNING 文档置为 FAILED，并收尾悬空的分块日志
     *
     * @return 是否真正回收成功（false 表示期间被消费者领取或状态已变化）
     */
    private boolean failStaleChunkTask(KnowledgeDocumentDO candidate, Date now) {
        String docId = candidate.getId();
        String taskId = candidate.getCurrentChunkTaskId();

        LambdaUpdateWrapper<KnowledgeDocumentDO> update = Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                .set(KnowledgeDocumentDO::getProcessingToken, null)
                .set(KnowledgeDocumentDO::getProcessingLeaseUntil, null)
                .set(KnowledgeDocumentDO::getUpdateTime, now)
                .eq(KnowledgeDocumentDO::getId, docId)
                .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                // 复核：执行权此刻仍然无效，避免误杀刚刚被领取的任务
                .and(w -> w.isNull(KnowledgeDocumentDO::getProcessingToken)
                        .or()
                        .isNull(KnowledgeDocumentDO::getProcessingLeaseUntil)
                        .or()
                        .lt(KnowledgeDocumentDO::getProcessingLeaseUntil, now));
        // 任务 ID 未变才允许回收，避免覆盖期间新发起的任务
        if (taskId == null) {
            update.isNull(KnowledgeDocumentDO::getCurrentChunkTaskId);
        } else {
            update.eq(KnowledgeDocumentDO::getCurrentChunkTaskId, taskId);
        }

        int updated = documentMapper.update(update);
        if (updated == 0) {
            return false;
        }

        // 收尾悬空的分块日志：进程崩溃时那条 running 日志永远不会被写回
        chunkLogMapper.update(null,
                Wrappers.lambdaUpdate(KnowledgeDocumentChunkLogDO.class)
                        .set(KnowledgeDocumentChunkLogDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentChunkLogDO::getErrorMessage,
                                "执行超时或执行者失联，由补偿任务判定失败")
                        .set(KnowledgeDocumentChunkLogDO::getEndTime, now)
                        .set(KnowledgeDocumentChunkLogDO::getUpdateTime, now)
                        .eq(KnowledgeDocumentChunkLogDO::getDocId, docId)
                        .eq(KnowledgeDocumentChunkLogDO::getStatus, DocumentStatus.RUNNING.getCode())
        );

        log.warn("回收卡住的文档分块任务: docId={}, taskId={}, token={}, leaseUntil={}, updateTime={}",
                docId, taskId, candidate.getProcessingToken(),
                candidate.getProcessingLeaseUntil(), candidate.getUpdateTime());
        return true;
    }
}
