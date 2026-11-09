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

package com.askflow.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.askflow.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.askflow.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.askflow.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.askflow.knowledge.controller.vo.KnowledgeDocumentVO;
import com.askflow.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.askflow.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.askflow.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档服务接口
 */
public interface KnowledgeDocumentService {

    /**
     * 上传文档
     *
     * @param kbId         知识库 ID
     * @param requestParam 请求对象参数
     * @param file         待上传的文件
     * @return 知识库文档视图对象
     */
    KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest requestParam, MultipartFile file);

    /**
     * 开始文档分片处理（校验状态并发送 MQ 消息，立即返回）
     *
     * @param docId 文档 ID
     */
    void startChunk(String docId);

    /**
     * 原子领取文档分块任务的执行权
     * <p>仅当文档处于 running、当前任务 ID 匹配、且执行令牌为空或已过期时才能领取成功，
     * 由此保证同一时刻只有一个消费者能执行同一个任务。
     *
     * @param docId  文档 ID
     * @param taskId 任务 ID
     * @param token  本次执行令牌
     * @return 是否领取成功
     */
    boolean claimChunkTask(String docId, String taskId, String token);

    /**
     * 释放文档分块任务的执行权
     * <p>只有仍持有该令牌的消费者才能释放，避免误清其他消费者的令牌。
     *
     * @param docId  文档 ID
     * @param taskId 任务 ID
     * @param token  本次执行令牌
     */
    void releaseChunkTask(String docId, String taskId, String token);

    /**
     * 处理领取失败的消息
     * <p>领取失败不等于消息应当丢弃，需要按当前文档状态区分：
     * 旧任务/已结束任务直接跳过（ACK），同任务正在被其他消费者执行则抛出可重试异常交给 MQ 延迟重投。
     *
     * @param event 分块事件
     */
    void handleUnclaimedMessage(KnowledgeDocumentChunkEvent event);

    /**
     * 执行文档分块（由 MQ 消费者调用，调用前必须已成功领取任务）
     * 清理历史分块和向量 → 执行完整分块流程 → 按 taskId/token 做 fencing 写回
     *
     * @param docId  文档 ID
     * @param taskId 任务 ID
     * @param token  本次执行令牌
     */
    void executeChunk(String docId, String taskId, String token);

    /**
     * 删除文档
     *
     * @param docId 文档 ID
     */
    void delete(String docId);

    /**
     * 获取文档详情
     *
     * @param docId 文档 ID
     * @return 知识库文档视图对象
     */
    KnowledgeDocumentVO get(String docId);

    /**
     * 更新文档信息
     *
     * @param docId        文档 ID
     * @param requestParam 更新请求参数
     */
    void update(String docId, KnowledgeDocumentUpdateRequest requestParam);

    /**
     * 分页查询文档
     *
     * @param kbId         知识库 ID
     * @param requestParam 筛选参数
     * @return 文档分页结果
     */
    IPage<KnowledgeDocumentVO> page(String kbId, KnowledgeDocumentPageRequest requestParam);

    /**
     * 启用或禁用文档
     *
     * @param docId   文档 ID
     * @param enabled 是否启用
     */
    void enable(String docId, boolean enabled);

    /**
     * 搜索文档（用于全局检索建议）
     *
     * @param keyword 关键词
     * @param limit   最大返回数量
     * @return 文档列表
     */
    List<KnowledgeDocumentSearchVO> search(String keyword, int limit);

    /**
     * 查询文档分块日志
     *
     * @param docId 文档 ID
     * @param page  分页参数
     * @return 分块日志分页结果
     */
    IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page);

    /**
     * 预览 markdown 文档内容
     *
     * @param docId 文档 ID
     * @return markdown 原始文本内容
     */
    String preview(String docId);
}
