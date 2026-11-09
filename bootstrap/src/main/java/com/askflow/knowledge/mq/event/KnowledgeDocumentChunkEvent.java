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

package com.askflow.knowledge.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文档分块任务事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunkEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档处理任务 ID
     * <p>每次触发分块都会生成新的任务 ID，消费端必须凭它与文档当前有效的
     * {@code t_knowledge_document.current_chunk_task_id} 匹配后才能执行，
     * 从而让旧消息（上一个任务）天然失效。
     */
    private String taskId;

    /**
     * 文档 ID
     */
    private String docId;

    /**
     * 知识库 ID
     */
    private String kbId;

    /**
     * 操作人
     */
    private String operator;
}
