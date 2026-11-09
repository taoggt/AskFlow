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

package com.askflow.knowledge.mq;

import cn.hutool.core.util.StrUtil;
import com.askflow.framework.context.LoginUser;
import com.askflow.framework.context.UserContext;
import com.askflow.framework.mq.MessageWrapper;
import com.askflow.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.askflow.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文档分块任务 MQ 消费者
 * 负责异步执行耗时的文本提取、分块、向量嵌入及写库操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge-document-chunk_topic${unique-name:}",
        consumerGroup = "knowledge-document-chunk_cg${unique-name:}"
)
public class KnowledgeDocumentChunkConsumer implements RocketMQListener<MessageWrapper<KnowledgeDocumentChunkEvent>> {

    private final KnowledgeDocumentService documentService;

    @Override
    public void onMessage(MessageWrapper<KnowledgeDocumentChunkEvent> message) {
        KnowledgeDocumentChunkEvent event = message.getBody();

        // 升级前在途的历史消息没有 taskId，无法与文档当前任务匹配，直接丢弃
        if (StrUtil.isBlank(event.getTaskId())) {
            log.warn("[消费者] 消息缺少 taskId，跳过, docId={}, keys={}", event.getDocId(), message.getKeys());
            return;
        }

        String token = UUID.randomUUID().toString();
        log.info("[消费者] 开始消费文档分块任务，docId={}, taskId={}, token={}, keys={}",
                event.getDocId(), event.getTaskId(), token, message.getKeys());

        // 先原子领取执行权：领取成功才说明这条消息对应的任务仍然有效、且当前没有其他消费者在跑
        boolean claimed = documentService.claimChunkTask(event.getDocId(), event.getTaskId(), token);
        if (!claimed) {
            // 领取失败不一定是"该丢弃"：可能是同类任务正在被其他消费者执行，需要延迟重试
            documentService.handleUnclaimedMessage(event);
            return;
        }

        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            documentService.executeChunk(event.getDocId(), event.getTaskId(), token);
        } finally {
            UserContext.clear();
        }
    }
}
