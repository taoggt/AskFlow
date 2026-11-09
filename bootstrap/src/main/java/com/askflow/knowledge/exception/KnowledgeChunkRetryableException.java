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

package com.askflow.knowledge.exception;

import java.io.Serial;

/**
 * 文档分块可重试异常
 * <p>表示本次执行失败属于临时性故障（网络抖动、下游限流、数据库连接异常等），
 * 由消费者主动抛出，让 RocketMQ 重新投递该消息，而不是把任务置为终态失败。
 * <p>抛出前必须已经释放执行令牌，否则重投的消息无法重新领取任务。
 */
public class KnowledgeChunkRetryableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public KnowledgeChunkRetryableException(String message) {
        super(message);
    }

    public KnowledgeChunkRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
