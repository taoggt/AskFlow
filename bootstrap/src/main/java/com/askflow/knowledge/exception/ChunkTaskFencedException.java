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
 * 分块任务执行权已失效异常
 * <p>消费者在写回结果时发现文档的 {@code current_chunk_task_id} / {@code processing_token}
 * 已经不再是自己持有的值，说明任务被更晚的任务或更晚的消费者接管，
 * 本次结果必须整体丢弃（事务回滚），避免旧数据覆盖新数据。
 * <p>该异常属于终态异常，不应触发 MQ 重投。
 */
public class ChunkTaskFencedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ChunkTaskFencedException(String message) {
        super(message);
    }
}
