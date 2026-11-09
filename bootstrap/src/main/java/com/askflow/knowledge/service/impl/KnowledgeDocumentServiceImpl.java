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

package com.askflow.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.askflow.core.chunk.ChunkEmbeddingService;
import com.askflow.core.chunk.ChunkingMode;
import com.askflow.core.chunk.ChunkingOptions;
import com.askflow.core.chunk.ChunkingStrategy;
import com.askflow.core.chunk.ChunkingStrategyFactory;
import com.askflow.core.chunk.VectorChunk;
import com.askflow.core.parser.DocumentParserSelector;
import com.askflow.core.parser.ParserType;
import com.askflow.framework.context.UserContext;
import com.askflow.framework.exception.ClientException;
import com.askflow.framework.exception.RemoteException;
import com.askflow.framework.mq.producer.MessageQueueProducer;
import com.askflow.ingestion.dao.entity.IngestionPipelineDO;
import com.askflow.ingestion.dao.mapper.IngestionPipelineMapper;
import com.askflow.ingestion.domain.context.IngestionContext;
import com.askflow.ingestion.domain.pipeline.PipelineDefinition;
import com.askflow.ingestion.engine.IngestionEngine;
import com.askflow.ingestion.service.IngestionPipelineService;
import com.askflow.knowledge.config.KnowledgeScheduleProperties;
import com.askflow.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.askflow.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.askflow.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.askflow.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.askflow.knowledge.controller.vo.KnowledgeChunkVO;
import com.askflow.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.askflow.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.askflow.knowledge.controller.vo.KnowledgeDocumentVO;
import com.askflow.knowledge.dao.entity.KnowledgeBaseDO;
import com.askflow.knowledge.dao.entity.KnowledgeChunkDO;
import com.askflow.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;
import com.askflow.knowledge.dao.entity.KnowledgeDocumentDO;
import com.askflow.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.askflow.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.askflow.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.askflow.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.askflow.knowledge.enums.DocumentStatus;
import com.askflow.knowledge.enums.ProcessMode;
import com.askflow.knowledge.enums.SourceType;
import com.askflow.knowledge.exception.ChunkTaskFencedException;
import com.askflow.knowledge.exception.KnowledgeChunkRetryableException;
import com.askflow.knowledge.handler.RemoteFileFetcher;
import com.askflow.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.askflow.knowledge.schedule.CronScheduleHelper;
import com.askflow.knowledge.service.KnowledgeChunkService;
import com.askflow.knowledge.service.KnowledgeDocumentScheduleService;
import com.askflow.knowledge.service.KnowledgeDocumentService;
import com.askflow.rag.core.vector.VectorSpaceId;
import com.askflow.rag.core.vector.VectorStoreService;
import com.askflow.rag.dto.StoredFileDTO;
import com.askflow.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentParserSelector parserSelector;
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    private final FileStorageService fileStorageService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentScheduleService scheduleService;
    private final IngestionPipelineService ingestionPipelineService;
    private final IngestionPipelineMapper ingestionPipelineMapper;
    private final IngestionEngine ingestionEngine;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final KnowledgeDocumentChunkLogMapper chunkLogMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final TransactionOperations transactionOperations;
    private final MessageQueueProducer messageQueueProducer;
    private final KnowledgeScheduleProperties scheduleProperties;
    private final RemoteFileFetcher remoteFileFetcher;

    @Value("knowledge-document-chunk_topic${unique-name:}")
    private String chunkTopic;

    /**
     * 执行令牌的租约时长（分钟）
     * <p>超过该时长未释放，视为消费者已失联，允许其他消费者抢占该任务。
     * <p>注意：该值需要大于 RocketMQ 的消费超时（默认 15 分钟），
     * 否则正常执行中的任务会被重投的消息抢走。
     */
    private static final int CHUNK_TASK_LEASE_MINUTES = 30;

    /**
     * 文档内容哈希算法，与定时刷新链路的 SHA-256 口径保持一致
     */
    private static final String CONTENT_HASH_ALGORITHM = "SHA-256";

    @Override
    public KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest requestParam, MultipartFile file) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        SourceType sourceType = SourceType.normalize(requestParam.getSourceType());
        validateSourceAndSchedule(sourceType, requestParam);
        // 先算内容哈希并做同库去重，重复文件不必再走一遍对象存储
        String contentHash = SourceType.FILE == sourceType ? resolveFileContentHash(file) : null;
        assertContentNotDuplicated(kbId, contentHash);
        StoredFileDTO stored = resolveStoredFile(kbDO.getCollectionName(), sourceType, requestParam.getSourceLocation(), file);
        ProcessModeConfig modeConfig = resolveProcessModeConfig(requestParam);

        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .kbId(kbId)
                .docName(stored.getOriginalFilename())
                .enabled(1)
                .chunkCount(0)
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .contentHash(contentHash)
                .status(DocumentStatus.PENDING.getCode())
                .sourceType(sourceType.getValue())
                .sourceLocation(SourceType.URL == sourceType ? StrUtil.trimToNull(requestParam.getSourceLocation()) : null)
                .scheduleEnabled(isScheduleEnabled(sourceType, requestParam) ? 1 : 0)
                .scheduleCron(isScheduleEnabled(sourceType, requestParam) ? StrUtil.trimToNull(requestParam.getScheduleCron()) : null)
                .processMode(modeConfig.processMode().getValue())
                .chunkStrategy(modeConfig.chunkingMode() != null ? modeConfig.chunkingMode().getValue() : null)
                .chunkConfig(modeConfig.chunkConfig())
                .pipelineId(modeConfig.pipelineId())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        try {
            documentMapper.insert(documentDO);
        } catch (DuplicateKeyException e) {
            // 并发上传同一文件时由唯一索引兜底：本次已落盘的对象要回收，避免留下孤儿文件
            deleteStoredFileQuietly(documentDO);
            throw new ClientException("该文件已存在于当前知识库，请勿重复上传");
        }

        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    public void startChunk(String docId) {
        KnowledgeDocumentDO document = documentMapper.selectById(docId);
        Assert.notNull(document, () -> new ClientException("文档不存在"));

        // 每次触发都生成新的任务 ID：消费端凭它判断消息是否仍然有效，
        // 上一个任务遗留的消息（未消费、重投、积压）会因为任务 ID 不匹配而被跳过
        String taskId = UUID.randomUUID().toString();
        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .taskId(taskId)
                .docId(docId)
                .kbId(document.getKbId())
                .operator(UserContext.getUsername())
                .build();

        messageQueueProducer.sendInTransaction(
                chunkTopic,
                taskId,
                "文档分块",
                event,
                arg -> {
                    int updated = documentMapper.update(
                            new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                                    .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                                    .set(KnowledgeDocumentDO::getCurrentChunkTaskId, taskId)
                                    .set(KnowledgeDocumentDO::getUpdatedBy, event.getOperator())
                                    // 新任务开始即作废旧任务持有的执行令牌
                                    .set(KnowledgeDocumentDO::getProcessingToken, null)
                                    .set(KnowledgeDocumentDO::getProcessingLeaseUntil, null)
                                    // update_time 是补偿任务判断"多久没人动过"的依据，必须显式维护
                                    .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                                    .eq(KnowledgeDocumentDO::getId, docId)
                                    .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                    );
                    if (updated == 0) {
                        throw new ClientException("文档分块操作正在进行中，请稍后再试");
                    }
                    scheduleService.upsertSchedule(document);
                }
        );
    }

    @Override
    public boolean claimChunkTask(String docId, String taskId, String token) {
        Date now = new Date();
        Date leaseUntil = DateUtil.offsetMinute(now, CHUNK_TASK_LEASE_MINUTES);
        // 一条 UPDATE 完成"校验 + 抢占"，由数据库保证并发安全：
        // 任务 ID、状态、令牌归属任一不满足都不会命中，因此不会出现两个消费者同时领取成功
        int updated = documentMapper.update(
                new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                        .set(KnowledgeDocumentDO::getProcessingToken, token)
                        .set(KnowledgeDocumentDO::getProcessingLeaseUntil, leaseUntil)
                        .set(KnowledgeDocumentDO::getUpdateTime, now)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getCurrentChunkTaskId, taskId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        // 无人持有，或原持有者租约已过期（失联）时才允许领取
                        .and(wrapper -> wrapper
                                .isNull(KnowledgeDocumentDO::getProcessingToken)
                                .or()
                                .isNull(KnowledgeDocumentDO::getProcessingLeaseUntil)
                                .or()
                                .le(KnowledgeDocumentDO::getProcessingLeaseUntil, now))
        );
        if (updated > 0) {
            log.info("[分块任务] 领取执行权成功, docId={}, taskId={}, token={}, leaseUntil={}",
                    docId, taskId, token, leaseUntil);
        }
        return updated > 0;
    }

    @Override
    public void releaseChunkTask(String docId, String taskId, String token) {
        transactionOperations.executeWithoutResult(status -> {
            int updated = documentMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                            .set(KnowledgeDocumentDO::getProcessingToken, null)
                            .set(KnowledgeDocumentDO::getProcessingLeaseUntil, null)
                            .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                            .eq(KnowledgeDocumentDO::getId, docId)
                            .eq(KnowledgeDocumentDO::getCurrentChunkTaskId, taskId)
                            // 只允许令牌持有者释放，避免误清新消费者的令牌
                            .eq(KnowledgeDocumentDO::getProcessingToken, token)
                            .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
            );
            if (updated > 0) {
                log.info("[分块任务] 释放执行权, docId={}, taskId={}, token={}", docId, taskId, token);
            } else {
                log.warn("[分块任务] 释放执行权未命中，令牌可能已被接管, docId={}, taskId={}, token={}",
                        docId, taskId, token);
            }
        });
    }

    @Override
    public void handleUnclaimedMessage(KnowledgeDocumentChunkEvent event) {
        String docId = event.getDocId();
        String taskId = event.getTaskId();
        KnowledgeDocumentDO document = documentMapper.selectById(docId);

        // 1. 文档不存在（已删除）：消息没有任何意义，直接跳过（ACK）
        if (document == null) {
            log.warn("[分块任务] 文档不存在，丢弃消息, docId={}, taskId={}", docId, taskId);
            return;
        }

        // 2. 不是当前有效任务：旧任务遗留消息，跳过（ACK）
        if (!Objects.equals(taskId, document.getCurrentChunkTaskId())) {
            log.info("[分块任务] 非当前有效任务，跳过消息, docId={}, taskId={}, currentTaskId={}",
                    docId, taskId, document.getCurrentChunkTaskId());
            return;
        }

        // 3. 任务已结束（成功/失败）：重复投递的消息，跳过（ACK）
        if (!DocumentStatus.RUNNING.getCode().equals(document.getStatus())) {
            log.info("[分块任务] 任务已结束，跳过消息, docId={}, taskId={}, status={}",
                    docId, taskId, document.getStatus());
            return;
        }

        // 4. 任务仍是当前任务且处于运行中，说明另一个消费者正持有有效令牌；
        //    这里不能 ACK，必须抛异常让 MQ 延迟重投，否则任务会永远停在 RUNNING
        Date leaseUntil = document.getProcessingLeaseUntil();
        if (StrUtil.isNotBlank(document.getProcessingToken())
                && leaseUntil != null && leaseUntil.after(new Date())) {
            log.info("[分块任务] 任务正在被其他消费者执行，延迟重试, docId={}, taskId={}, leaseUntil={}",
                    docId, taskId, leaseUntil);
            throw new KnowledgeChunkRetryableException(
                    "文档分块任务正在被其他消费者执行，稍后重试, docId=" + docId + ", taskId=" + taskId);
        }

        // 5. 令牌已释放或租约已过期但状态仍是 RUNNING：交给 MQ 重投，下一次投递即可重新领取执行
        log.info("[分块任务] 执行令牌已失效但任务未完成，延迟重试, docId={}, taskId={}, token={}, leaseUntil={}",
                docId, taskId, document.getProcessingToken(), leaseUntil);
        throw new KnowledgeChunkRetryableException(
                "文档分块任务令牌已失效，等待重新领取, docId=" + docId + ", taskId=" + taskId);
    }

    @Override
    public void executeChunk(String docId, String taskId, String token) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        if (documentDO == null) {
            log.warn("文档不存在，跳过分块任务, docId={}, taskId={}", docId, taskId);
            return;
        }
        if (!taskId.equals(documentDO.getCurrentChunkTaskId())) {
            log.warn("任务已过期，跳过：{}", taskId);
            return;
        }
        runChunkTask(documentDO, taskId, token, true);
    }

    /**
     * 执行一次分块任务
     * <p>失败按「可重试 / 终态」两类处理：
     * <ul>
     *   <li>可重试（网络抖动、下游限流、数据库连接异常等）：写失败日志 → 释放执行令牌 →
     *       抛出 {@link KnowledgeChunkRetryableException}，交给 MQ 重新投递；</li>
     *   <li>终态（文件解析失败、Pipeline 配置缺失、参数非法等）：写失败日志 → 按
     *       taskId + token 做 fencing 后把文档置为 failed。</li>
     * </ul>
     * <p>另外，写回成功状态时同样带 taskId + token 条件，租约过期后被接管执行的旧消费者
     * 会命中 0 行并整体回滚，不会覆盖新任务的结果。
     *
     * @param throwOnRetryable MQ 消费链路传 true（抛出让 MQ 重投）；
     *                         定时刷新链路传 false（直接置为失败，由下一次 cron 触发重试）
     */
    private void runChunkTask(KnowledgeDocumentDO documentDO, String taskId, String token, boolean throwOnRetryable) {
        String docId = documentDO.getId();
        ProcessMode processMode = ProcessMode.normalize(documentDO.getProcessMode());

        KnowledgeDocumentChunkLogDO chunkLog;
        try {
            chunkLog = KnowledgeDocumentChunkLogDO.builder()
                    .docId(docId)
                    .status(DocumentStatus.RUNNING.getCode())
                    .processMode(processMode.getValue())
                    .chunkStrategy(documentDO.getChunkStrategy())
                    .pipelineId(documentDO.getPipelineId())
                    .startTime(new Date())
                    .build();
            chunkLogMapper.insert(chunkLog);
        } catch (Exception e) {
            // 写日志都失败，基本可以判定是数据库不可用，属于可重试失败
            log.error("写入分块日志失败：docId={}, taskId={}", docId, taskId, e);
            if (throwOnRetryable) {
                releaseChunkTask(docId, taskId, token);
                throw new KnowledgeChunkRetryableException("写入分块日志失败, docId=" + docId, e);
            }
            markChunkFailed(docId, taskId, token);
            return;
        }

        long totalStartTime = System.currentTimeMillis();
        long extractDuration = 0;
        long chunkDuration = 0;
        long embedDuration = 0;
        long persistDuration = 0;

        try {
            List<VectorChunk> chunkResults;
            if (ProcessMode.PIPELINE == processMode) {
                long start = System.currentTimeMillis();
                chunkResults = runPipelineProcess(documentDO);
                chunkDuration = System.currentTimeMillis() - start;
            } else {
                ChunkProcessResult result = runChunkProcess(documentDO);
                extractDuration = result.extractDuration();
                chunkDuration = result.chunkDuration();
                embedDuration = result.embedDuration();
                chunkResults = result.chunks();
            }

            String collectionName = resolveCollectionName(documentDO.getKbId());
            // 提取 + 分块 + 嵌入可能耗时很久，进入持久化前续租一次，
            // 避免补偿任务把"仍在执行"的任务误判成"执行者失联"
            renewChunkTaskLease(docId, taskId, token);
            long persistStart = System.currentTimeMillis();
            int savedCount = persistChunksAndVectorsAtomically(collectionName, docId, taskId, token, chunkResults);
            persistDuration = System.currentTimeMillis() - persistStart;

            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.SUCCESS.getCode(), savedCount,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, null);
        } catch (ChunkTaskFencedException e) {
            // 执行权已被更晚的消费者/任务接管：不写文档状态，直接丢弃本次结果（ACK）
            log.warn("分块结果写回时发现执行权已失效，丢弃本次结果：docId={}, taskId={}, token={}",
                    docId, taskId, token);
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.FAILED.getCode(), 0,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration,
                    "执行权已失效（任务被接管），本次结果作废");
        } catch (Exception e) {
            boolean retryable = isRetryable(e);
            log.error("文档分块任务执行失败：docId={}, taskId={}, retryable={}", docId, taskId, retryable, e);

            long totalDuration = System.currentTimeMillis() - totalStartTime;

            if (retryable && throwOnRetryable) {
                // 先释放令牌，保证重投的消息能重新领取；文档保持 running，重试可以继续
                releaseChunkTask(docId, taskId, token);
                updateChunkLog(chunkLog.getId(), DocumentStatus.FAILED.getCode(), 0,
                        extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration,
                        "可重试失败，等待 MQ 重投：" + e.getMessage());
                throw new KnowledgeChunkRetryableException(
                        "文档分块任务执行失败，等待重试, docId=" + docId, e);
            }

            // 终态失败：文档置为 failed 并清理令牌
            updateChunkLog(chunkLog.getId(), DocumentStatus.FAILED.getCode(), 0,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration,
                    e.getMessage());
            markChunkFailed(docId, taskId, token);
        }
    }

    /**
     * 续租执行令牌
     * <p>仅在阶段边界调用（当前是"进入持久化之前"），用于覆盖耗时的提取/嵌入阶段。
     * 续租失败说明执行权已被更晚的消费者接管，直接抛 {@link ChunkTaskFencedException}
     * 放弃本次结果，避免白做一次持久化。
     */
    private void renewChunkTaskLease(String docId, String taskId, String token) {
        Date now = new Date();
        int updated = documentMapper.update(
                new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                        .set(KnowledgeDocumentDO::getProcessingLeaseUntil,
                                DateUtil.offsetMinute(now, CHUNK_TASK_LEASE_MINUTES))
                        .set(KnowledgeDocumentDO::getUpdateTime, now)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getCurrentChunkTaskId, taskId)
                        .eq(KnowledgeDocumentDO::getProcessingToken, token)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );
        if (updated == 0) {
            throw new ChunkTaskFencedException(
                    "续租失败，执行权已被接管, docId=" + docId + ", taskId=" + taskId);
        }
    }

    private int persistChunksAndVectorsAtomically(String collectionName, String docId, String taskId,
                                                  String token, List<VectorChunk> chunkResults) {
        List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
                .map(vc -> {
                    KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                    req.setChunkId(vc.getChunkId());
                    req.setIndex(vc.getIndex());
                    req.setContent(vc.getContent());
                    return req;
                })
                .toList();
        transactionOperations.executeWithoutResult(status -> {
            knowledgeChunkService.deleteByDocId(docId);
            knowledgeChunkService.batchCreate(docId, chunks);
            vectorStoreService.deleteDocumentVectors(collectionName, docId);
            vectorStoreService.indexDocumentChunks(collectionName, docId, chunkResults);

            // fencing 写回：只有仍持有当前任务令牌的消费者才能把文档置为成功。
            // 租约过期后被接管执行的旧消费者会命中 0 行，从而整体回滚，不覆盖新结果
            int updated = documentMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                            .set(KnowledgeDocumentDO::getChunkCount, chunks.size())
                            .set(KnowledgeDocumentDO::getStatus, DocumentStatus.SUCCESS.getCode())
                            .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername())
                            .set(KnowledgeDocumentDO::getProcessingToken, null)
                            .set(KnowledgeDocumentDO::getProcessingLeaseUntil, null)
                            .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                            .eq(KnowledgeDocumentDO::getId, docId)
                            .eq(KnowledgeDocumentDO::getCurrentChunkTaskId, taskId)
                            .eq(KnowledgeDocumentDO::getProcessingToken, token)
                            .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
            );
            if (updated == 0) {
                throw new ChunkTaskFencedException(
                        "任务执行权已失效, docId=" + docId + ", taskId=" + taskId);
            }
        });
        return chunks.size();
    }

    /**
     * 判断异常是否属于「可重试」的临时性故障
     * <p>会沿 cause 链逐层判断：命中 ClientException / IllegalArgumentException 等参数类
     * 异常直接判定为不可重试；命中 IO、超时、连接、数据库访问、远程调用等异常判定为可重试。
     */
    private boolean isRetryable(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ChunkTaskFencedException) {
                return false;
            }
            if (current instanceof ClientException || current instanceof IllegalArgumentException) {
                return false;
            }
            if (current instanceof DataAccessException
                    || current instanceof RemoteException
                    || current instanceof IOException
                    || current instanceof TimeoutException
                    || current instanceof ConnectException
                    || current instanceof SocketException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private void updateChunkLog(String logId, String status, int chunkCount, long extractDuration,
                                long chunkDuration, long embedDuration, long persistDuration,
                                long totalDuration, String errorMessage) {
        KnowledgeDocumentChunkLogDO update = KnowledgeDocumentChunkLogDO.builder()
                .id(logId)
                .status(status)
                .chunkCount(chunkCount)
                .extractDuration(extractDuration)
                .chunkDuration(chunkDuration)
                .embedDuration(embedDuration)
                .persistDuration(persistDuration)
                .totalDuration(totalDuration)
                .errorMessage(errorMessage)
                .endTime(new Date())
                .build();
        chunkLogMapper.updateById(update);
    }

    /**
     * 使用分块策略处理文档，失败直接抛异常，由 runChunkTask 统一处理错误状态
     * 4 阶段中的前 3 阶段：Extract → Chunk → Embed
     */
    private ChunkProcessResult runChunkProcess(KnowledgeDocumentDO documentDO) {
        ChunkingMode chunkingMode = ChunkingMode.fromValue(documentDO.getChunkStrategy());
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        ChunkingOptions config = buildChunkingOptions(chunkingMode, documentDO);

        long extractStart = System.currentTimeMillis();
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            String text = parserSelector.select(ParserType.TIKA.getType()).extractText(is, documentDO.getDocName());
            long extractDuration = System.currentTimeMillis() - extractStart;

            ChunkingStrategy chunkingStrategy = chunkingStrategyFactory.requireStrategy(chunkingMode);
            long chunkStart = System.currentTimeMillis();
            List<VectorChunk> chunks = chunkingStrategy.chunk(text, config);
            long chunkDuration = System.currentTimeMillis() - chunkStart;

            long embedStart = System.currentTimeMillis();
            chunkEmbeddingService.embed(chunks, embeddingModel);
            long embedDuration = System.currentTimeMillis() - embedStart;

            return new ChunkProcessResult(chunks, extractDuration, chunkDuration, embedDuration);
        } catch (Exception e) {
            throw new RuntimeException("文档内容提取或分块失败", e);
        }
    }

    private record ChunkProcessResult(List<VectorChunk> chunks, long extractDuration, long chunkDuration,
                                      long embedDuration) {
    }

    private record ProcessModeConfig(ProcessMode processMode, ChunkingMode chunkingMode, String chunkConfig,
                                     String pipelineId) {
    }

    /**
     * 使用 Pipeline 处理文档，失败直接抛异常，由 runChunkTask 统一处理错误状态
     */
    private List<VectorChunk> runPipelineProcess(KnowledgeDocumentDO documentDO) {
        String docId = String.valueOf(documentDO.getId());
        String pipelineId = documentDO.getPipelineId();

        if (pipelineId == null) {
            throw new IllegalStateException("Pipeline模式下Pipeline ID为空：docId=" + docId);
        }

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());

        PipelineDefinition pipelineDef = ingestionPipelineService.getDefinition(pipelineId);

        byte[] fileBytes;
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            fileBytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取文件内容失败：docId=" + docId, e);
        }

        IngestionContext context = IngestionContext.builder()
                .taskId(docId)
                .pipelineId(pipelineId)
                .rawBytes(fileBytes)
                .mimeType(documentDO.getFileType())
                .vectorSpaceId(VectorSpaceId.builder()
                        .logicalName(kbDO.getCollectionName())
                        .build())
                // 与 chunk 模式保持一致：向量必须用知识库绑定的模型生成，否则同一集合内向量不同源
                .embeddingModel(kbDO.getEmbeddingModel())
                .skipIndexerWrite(true)
                .build();

        IngestionContext result = ingestionEngine.execute(pipelineDef, context);

        if (result.getError() != null) {
            throw new RuntimeException("Pipeline执行失败：" + result.getError().getMessage(), result.getError());
        }

        List<VectorChunk> chunks = result.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            log.warn("Pipeline执行完成但未产生分块：docId={}", docId);
            return List.of();
        }

        return chunks;
    }

    /**
     * 定时刷新链路直接执行分块
     * <p>该链路已经通过调度表租约（lock_owner / lock_until）保证同一时刻只有一个调度在跑，
     * 但分块任务本身也要登记 taskId 并抢占执行令牌，否则同一份文档被"手动触发 + 定时刷新"
     * 同时命中时，两边都会写分块与向量。抢占失败说明已有消费者在执行，本轮直接跳过。
     */
    public void chunkDocument(KnowledgeDocumentDO documentDO) {
        if (documentDO == null) {
            return;
        }
        String docId = documentDO.getId();
        String taskId = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        Date now = new Date();
        Date leaseUntil = DateUtil.offsetMinute(now, CHUNK_TASK_LEASE_MINUTES);

        int claimed = documentMapper.update(
                new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                        .set(KnowledgeDocumentDO::getCurrentChunkTaskId, taskId)
                        .set(KnowledgeDocumentDO::getProcessingToken, token)
                        .set(KnowledgeDocumentDO::getProcessingLeaseUntil, leaseUntil)
                        .set(KnowledgeDocumentDO::getUpdateTime, now)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .and(wrapper -> wrapper
                                .isNull(KnowledgeDocumentDO::getProcessingToken)
                                .or()
                                .isNull(KnowledgeDocumentDO::getProcessingLeaseUntil)
                                .or()
                                .le(KnowledgeDocumentDO::getProcessingLeaseUntil, now))
        );
        if (claimed == 0) {
            log.warn("定时刷新分块任务已有消费者持有执行令牌，跳过本轮, docId={}", docId);
            return;
        }

        // 定时刷新链路不抛可重试异常：失败直接置为 failed，由下一次 cron 再触发
        runChunkTask(documentDO, taskId, token, false);
    }

    /**
     * 把文档置为终态失败
     * <p>带 taskId + token 的 fencing 条件：若执行权已被更晚的任务或消费者接管，
     * 旧消费者无法把状态改成 failed，避免覆盖新任务。
     */
    private void markChunkFailed(String docId, String taskId, String token) {
        transactionOperations.executeWithoutResult(status -> {
            int updated = documentMapper.update(
                    new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                            .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                            .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername())
                            .set(KnowledgeDocumentDO::getProcessingToken, null)
                            .set(KnowledgeDocumentDO::getProcessingLeaseUntil, null)
                            .set(KnowledgeDocumentDO::getUpdateTime, new Date())
                            .eq(KnowledgeDocumentDO::getId, docId)
                            .eq(KnowledgeDocumentDO::getCurrentChunkTaskId, taskId)
                            .eq(KnowledgeDocumentDO::getProcessingToken, token)
                            .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
            );
            if (updated == 0) {
                log.warn("标记文档分块失败时未命中，执行权可能已被接管, docId={}, taskId={}, token={}",
                        docId, taskId, token);
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时删除
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法删除");
        }

        knowledgeChunkService.deleteByDocId(docId);
        scheduleService.deleteByDocId(docId);
        chunkLogMapper.delete(Wrappers.lambdaQuery(KnowledgeDocumentChunkLogDO.class)
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId));

        documentDO.setDeleted(1);
        documentDO.setUpdatedBy(UserContext.getUsername());
        documentMapper.deleteById(documentDO);

        String collectionName = resolveCollectionName(documentDO.getKbId());
        vectorStoreService.deleteDocumentVectors(collectionName, docId);
        deleteStoredFileQuietly(documentDO);
    }

    @Override
    public KnowledgeDocumentVO get(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, KnowledgeDocumentUpdateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        String docName = requestParam == null ? null : requestParam.getDocName();
        if (!StringUtils.hasText(docName)) {
            throw new ClientException("文档名称不能为空");
        }

        LambdaUpdateWrapper<KnowledgeDocumentDO> updateWrapper = Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, documentDO.getId())
                .set(KnowledgeDocumentDO::getDocName, docName.trim())
                .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername());

        // 如果传了 processMode，校验并更新处理配置
        if (StringUtils.hasText(requestParam.getProcessMode())) {
            ProcessMode processMode = ProcessMode.normalize(requestParam.getProcessMode());
            updateWrapper.set(KnowledgeDocumentDO::getProcessMode, processMode.getValue());

            if (ProcessMode.CHUNK == processMode) {
                ChunkingMode chunkingMode = ChunkingMode.fromValue(requestParam.getChunkStrategy());
                String chunkConfig = validateAndNormalizeChunkConfig(chunkingMode, requestParam.getChunkConfig());
                updateWrapper.set(KnowledgeDocumentDO::getChunkStrategy, chunkingMode.getValue());
                updateWrapper.setSql("chunk_config = CAST({0} AS jsonb)", chunkConfig);
                updateWrapper.set(KnowledgeDocumentDO::getPipelineId, null);
            } else {
                if (!StringUtils.hasText(requestParam.getPipelineId())) {
                    throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
                }
                try {
                    ingestionPipelineService.get(requestParam.getPipelineId());
                } catch (Exception e) {
                    throw new ClientException("指定的Pipeline不存在: " + requestParam.getPipelineId());
                }
                updateWrapper.set(KnowledgeDocumentDO::getPipelineId, requestParam.getPipelineId());
                updateWrapper.set(KnowledgeDocumentDO::getChunkStrategy, null);
                updateWrapper.set(KnowledgeDocumentDO::getChunkConfig, null);
            }
        }

        // 处理定时调度相关字段（仅 URL 类型文档支持）
        boolean scheduleChanged = false;
        if (SourceType.URL.getValue().equalsIgnoreCase(documentDO.getSourceType())) {
            String newSourceLocation = requestParam.getSourceLocation();
            Integer newScheduleEnabled = requestParam.getScheduleEnabled();
            String newScheduleCron = requestParam.getScheduleCron();

            if (StringUtils.hasText(newSourceLocation)) {
                updateWrapper.set(KnowledgeDocumentDO::getSourceLocation, newSourceLocation.trim());
                scheduleChanged = true;
            }
            if (newScheduleEnabled != null) {
                updateWrapper.set(KnowledgeDocumentDO::getScheduleEnabled, newScheduleEnabled);
                scheduleChanged = true;
            }
            if (StringUtils.hasText(newScheduleCron)) {
                try {
                    CronScheduleHelper.nextRunTime(newScheduleCron, new Date());
                    // 验证 cron 周期不能太短（与 upsertSchedule 保持一致）
                    if (CronScheduleHelper.isIntervalLessThan(newScheduleCron, new Date(), 60)) {
                        throw new ClientException("定时周期不能小于 60 秒");
                    }
                } catch (IllegalArgumentException e) {
                    throw new ClientException("定时表达式不合法: " + e.getMessage());
                }
                updateWrapper.set(KnowledgeDocumentDO::getScheduleCron, newScheduleCron.trim());
                scheduleChanged = true;
            }

            // 验证：启用定时拉取时必须有 cron 和 sourceLocation
            if (scheduleChanged) {
                KnowledgeDocumentDO willBe = documentMapper.selectById(docId);
                Integer finalEnabled = newScheduleEnabled != null ? newScheduleEnabled : willBe.getScheduleEnabled();
                String finalCron = StringUtils.hasText(newScheduleCron) ? newScheduleCron.trim() : willBe.getScheduleCron();
                String finalLocation = StringUtils.hasText(newSourceLocation) ? newSourceLocation.trim() : willBe.getSourceLocation();

                if (finalEnabled != null && finalEnabled == 1) {
                    if (!StringUtils.hasText(finalCron)) {
                        throw new ClientException("启用定时拉取时必须设置定时表达式");
                    }
                    if (!StringUtils.hasText(finalLocation)) {
                        throw new ClientException("启用定时拉取时必须设置来源地址");
                    }
                }
            }
        }

        documentMapper.update(updateWrapper);

        if (scheduleChanged) {
            KnowledgeDocumentDO updated = documentMapper.selectById(docId);
            scheduleService.upsertSchedule(updated);
        }
    }

    @Override
    public IPage<KnowledgeDocumentVO> page(String kbId, KnowledgeDocumentPageRequest requestParam) {
        Page<KnowledgeDocumentDO> pageParam = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        LambdaQueryWrapper<KnowledgeDocumentDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getKbId, kbId)
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(requestParam.getKeyword() != null && !requestParam.getKeyword().isBlank(), KnowledgeDocumentDO::getDocName, requestParam.getKeyword())
                .eq(requestParam.getStatus() != null && !requestParam.getStatus().isBlank(), KnowledgeDocumentDO::getStatus, requestParam.getStatus())
                .orderByDesc(KnowledgeDocumentDO::getCreateTime);

        IPage<KnowledgeDocumentVO> result = documentMapper.selectPage(pageParam, queryWrapper)
                .convert(each -> BeanUtil.toBean(each, KnowledgeDocumentVO.class));

        List<String> docIds = result.getRecords().stream()
                .map(KnowledgeDocumentVO::getId)
                .collect(Collectors.toList());
        Set<String> editedDocIds = findEditedDocIds(docIds);
        result.getRecords().forEach(vo -> vo.setChunksEdited(editedDocIds.contains(vo.getId())));

        return result;
    }

    private Set<String> findEditedDocIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Collections.emptySet();
        }
        QueryWrapper<KnowledgeChunkDO> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT doc_id")
                .in("doc_id", docIds)
                .apply("update_time > create_time + INTERVAL '1 second'");
        return chunkMapper.selectObjs(wrapper).stream()
                .map(String::valueOf)
                .collect(Collectors.toSet());
    }

    @Override
    public List<KnowledgeDocumentSearchVO> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        int size = Math.min(Math.max(limit, 1), 20);
        Page<KnowledgeDocumentDO> mpPage = new Page<>(1, size);
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(KnowledgeDocumentDO::getDocName, keyword)
                .orderByDesc(KnowledgeDocumentDO::getUpdateTime);

        IPage<KnowledgeDocumentDO> result = documentMapper.selectPage(mpPage, qw);
        List<KnowledgeDocumentSearchVO> records = result.getRecords().stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeDocumentSearchVO.class))
                .toList();
        if (records.isEmpty()) {
            return records;
        }

        Set<String> kbIds = new HashSet<>();
        for (KnowledgeDocumentSearchVO record : records) {
            if (record.getKbId() != null) {
                kbIds.add(record.getKbId());
            }
        }
        if (kbIds.isEmpty()) {
            return records;
        }

        List<KnowledgeBaseDO> bases = knowledgeBaseMapper.selectByIds(kbIds);
        Map<String, String> nameMap = new HashMap<>();
        if (bases != null) {
            for (KnowledgeBaseDO base : bases) {
                nameMap.put(base.getId(), base.getName());
            }
        }
        for (KnowledgeDocumentSearchVO record : records) {
            record.setKbName(nameMap.get(record.getKbId()));
        }
        return records;
    }

    @Override
    public void enable(String docId, boolean enabled) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        // 如果已经是目标状态，直接返回
        int targetEnabled = enabled ? 1 : 0;
        if (documentDO.getEnabled() != null && documentDO.getEnabled() == targetEnabled) {
            return;
        }

        // 提前查知识库，两个分支都需要，避免重复查询
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();

        // 启用时：embed 耗时较长，在事务外提前执行，避免长事务占用连接
        List<VectorChunk> vectorChunks = null;
        if (enabled) {
            List<KnowledgeChunkVO> chunks = knowledgeChunkService.listByDocId(docId);
            vectorChunks = chunks.stream().map(each ->
                    VectorChunk.builder()
                            .chunkId(each.getId())
                            .content(each.getContent())
                            .index(each.getChunkIndex())
                            .build()
            ).toList();
            if (CollUtil.isEmpty(vectorChunks)) {
                log.warn("启用文档时未找到任何 Chunk，跳过向量重建，docId={}", docId);
                return;
            }
            chunkEmbeddingService.embed(vectorChunks, kbDO.getEmbeddingModel());
        }

        final List<VectorChunk> finalVectorChunks = vectorChunks;
        transactionOperations.executeWithoutResult(status -> {
            documentDO.setEnabled(targetEnabled);
            documentDO.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(documentDO);
            scheduleService.syncScheduleIfExists(documentDO);
            knowledgeChunkService.updateEnabledByDocId(docId, String.valueOf(kbDO.getId()), enabled);

            if (!enabled) {
                vectorStoreService.deleteDocumentVectors(collectionName, docId);
            } else {
                vectorStoreService.indexDocumentChunks(collectionName, docId, finalVectorChunks);
            }
        });
    }

    @Override
    public IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page) {
        Page<KnowledgeDocumentChunkLogDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentChunkLogDO> qw = new LambdaQueryWrapper<KnowledgeDocumentChunkLogDO>()
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId)
                .orderByDesc(KnowledgeDocumentChunkLogDO::getCreateTime);

        IPage<KnowledgeDocumentChunkLogDO> result = chunkLogMapper.selectPage(mpPage, qw);

        List<KnowledgeDocumentChunkLogDO> records = result.getRecords();
        Map<String, String> pipelineNameMap = new HashMap<>();
        if (CollUtil.isNotEmpty(records)) {
            Set<String> pipelineIds = new HashSet<>();
            for (KnowledgeDocumentChunkLogDO record : records) {
                if (record.getPipelineId() != null) {
                    pipelineIds.add(record.getPipelineId());
                }
            }
            if (!pipelineIds.isEmpty()) {
                List<IngestionPipelineDO> pipelines = ingestionPipelineMapper.selectByIds(pipelineIds);
                if (CollUtil.isNotEmpty(pipelines)) {
                    for (IngestionPipelineDO pipeline : pipelines) {
                        pipelineNameMap.put(pipeline.getId(), pipeline.getName());
                    }
                }
            }
        }

        Page<KnowledgeDocumentChunkLogVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(records.stream().map(each -> {
            KnowledgeDocumentChunkLogVO vo = BeanUtil.toBean(each, KnowledgeDocumentChunkLogVO.class);
            if (each.getPipelineId() != null) {
                vo.setPipelineName(pipelineNameMap.get(each.getPipelineId()));
            }
            Long totalDuration = each.getTotalDuration();
            if (totalDuration != null) {
                long other = getOther(each, totalDuration);
                vo.setOtherDuration(Math.max(0, other));
            }
            return vo;
        }).toList());
        return voPage;
    }

    private static long getOther(KnowledgeDocumentChunkLogDO each, Long totalDuration) {
        String mode = each.getProcessMode();
        boolean pipelineMode = ProcessMode.PIPELINE.getValue().equalsIgnoreCase(mode);
        long extract = each.getExtractDuration() == null ? 0 : each.getExtractDuration();
        long chunk = each.getChunkDuration() == null ? 0 : each.getChunkDuration();
        long embed = each.getEmbedDuration() == null ? 0 : each.getEmbedDuration();
        long persist = each.getPersistDuration() == null ? 0 : each.getPersistDuration();
        return pipelineMode
                ? totalDuration - chunk - persist
                : totalDuration - extract - chunk - embed - persist;
    }

    private String resolveCollectionName(String kbId) {
        return knowledgeBaseMapper.selectById(kbId).getCollectionName();
    }

    private boolean isScheduleEnabled(SourceType sourceType, KnowledgeDocumentUploadRequest request) {
        return SourceType.URL == sourceType && Boolean.TRUE.equals(request.getScheduleEnabled());
    }

    private void validateSourceAndSchedule(SourceType sourceType, KnowledgeDocumentUploadRequest request) {
        String sourceLocation = StrUtil.trimToNull(request.getSourceLocation());
        if (SourceType.URL == sourceType && !StringUtils.hasText(sourceLocation)) {
            throw new ClientException("来源地址不能为空");
        }
        if (!isScheduleEnabled(sourceType, request)) {
            return;
        }
        String scheduleCron = StrUtil.trimToNull(request.getScheduleCron());
        if (!StringUtils.hasText(scheduleCron)) {
            throw new ClientException("定时表达式不能为空");
        }
        try {
            if (CronScheduleHelper.isIntervalLessThan(scheduleCron, new java.util.Date(), scheduleProperties.getMinIntervalSeconds())) {
                throw new ClientException("定时周期不能小于 " + scheduleProperties.getMinIntervalSeconds() + " 秒");
            }
        } catch (IllegalArgumentException e) {
            throw new ClientException("定时表达式不合法");
        }
    }

    private ProcessModeConfig resolveProcessModeConfig(KnowledgeDocumentUploadRequest request) {
        ProcessMode processMode = ProcessMode.normalize(request.getProcessMode());
        if (ProcessMode.CHUNK == processMode) {
            ChunkingMode chunkingMode = ChunkingMode.fromValue(request.getChunkStrategy());
            String chunkConfig = validateAndNormalizeChunkConfig(chunkingMode, request.getChunkConfig());
            return new ProcessModeConfig(processMode, chunkingMode, chunkConfig, null);
        } else {
            if (!StringUtils.hasText(request.getPipelineId())) {
                throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
            }
            try {
                ingestionPipelineService.get(request.getPipelineId());
            } catch (Exception e) {
                throw new ClientException("指定的Pipeline不存在: " + request.getPipelineId());
            }
            return new ProcessModeConfig(processMode, null, null, request.getPipelineId());
        }
    }

    private StoredFileDTO resolveStoredFile(String bucketName, SourceType sourceType, String sourceLocation, MultipartFile file) {
        if (SourceType.FILE == sourceType) {
            Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
            return fileStorageService.upload(bucketName, file);
        }
        return remoteFileFetcher.fetchAndStore(bucketName, sourceLocation);
    }

    /**
     * 计算上传文件的内容哈希（SHA-256 十六进制）
     * <p>MultipartFile#getInputStream 每次调用都会从底层临时文件重新打开流，
     * 因此这里先完整读一遍算哈希、再由 {@link #resolveStoredFile} 另开流上传，两者互不影响。
     * <p>读取失败时不做兜底：宁可让上传失败，也不能让文档以空哈希入库而跳过后续去重。
     */
    private String resolveFileContentHash(MultipartFile file) {
        Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
        Assert.isFalse(file.isEmpty(), () -> new ClientException("上传文件不能为空"));
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance(CONTENT_HASH_ALGORITHM);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new ClientException("读取上传文件失败: " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 " + CONTENT_HASH_ALGORITHM, e);
        }
    }

    /**
     * 同知识库内按内容哈希去重
     * <p>这里只做一次预检，用来给出明确提示并省掉一次对象存储写入；
     * 真正的并发保护依赖 uk_kb_doc_content_hash 唯一索引。
     * <p>去重范围限定在 kb_id 内：同一份文件进入不同知识库是正常用法。
     */
    private void assertContentNotDuplicated(String kbId, String contentHash) {
        if (!StringUtils.hasText(contentHash)) {
            return;
        }
        KnowledgeDocumentDO existing = documentMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
                        .eq(KnowledgeDocumentDO::getContentHash, contentHash)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            throw new ClientException(String.format(
                    "该文件已存在于当前知识库：%s（状态 %s），请勿重复上传",
                    existing.getDocName(), existing.getStatus()));
        }
    }

    private ChunkingOptions buildChunkingOptions(ChunkingMode mode, KnowledgeDocumentDO documentDO) {
        Map<String, Object> config = parseChunkConfig(documentDO.getChunkConfig());
        return mode.createOptions(config);
    }

    private String validateAndNormalizeChunkConfig(ChunkingMode mode, String chunkConfigJson) {
        if (!StringUtils.hasText(chunkConfigJson)) {
            return null;
        }
        if (mode == null) {
            mode = ChunkingMode.STRUCTURE_AWARE;
        }
        String json = chunkConfigJson.trim();
        Map<String, Object> config;
        try {
            config = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ClientException("分块参数JSON格式不合法");
        }
        for (String key : mode.getDefaultConfig().keySet()) {
            if (!config.containsKey(key)) {
                throw new ClientException("分块参数缺少必要字段: " + key);
            }
        }
        return json;
    }

    private Map<String, Object> parseChunkConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("分块参数解析失败: {}", json, e);
            return Map.of();
        }
    }

    @Override
    public String preview(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (!"markdown".equalsIgnoreCase(documentDO.getFileType())) {
            throw new ClientException("仅支持预览 markdown 格式文档");
        }
        try (InputStream in = fileStorageService.openStream(documentDO.getFileUrl())) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (ClientException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("读取文档内容失败: " + e.getMessage());
        }
    }

    private void deleteStoredFileQuietly(KnowledgeDocumentDO documentDO) {
        if (documentDO == null || !StringUtils.hasText(documentDO.getFileUrl())) {
            return;
        }
        try {
            fileStorageService.deleteByUrl(documentDO.getFileUrl());
        } catch (Exception e) {
            log.warn("删除文档存储文件失败, docId={}, fileUrl={}", documentDO.getId(), documentDO.getFileUrl(), e);
        }
    }
}
