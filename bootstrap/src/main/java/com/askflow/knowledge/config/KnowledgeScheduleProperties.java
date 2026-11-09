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

package com.askflow.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 知识库定时任务配置
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "rag.knowledge.schedule")
public class KnowledgeScheduleProperties {

    /**
     * 定时扫描间隔（毫秒）
     */
    private Long scanDelayMs = 10000L;

    /**
     * 分布式锁持有时长（秒）
     */
    private Long lockSeconds = 900L;

    /**
     * 每次扫描批量大小
     */
    private Integer batchSize = 20;

    /**
     * 定时拉取最小间隔（秒）
     */
    private Long minIntervalSeconds = 60L;

    /**
     * 执行令牌租约过期后的宽限时间（分钟）
     * <p>租约过期说明消费者可能已经失联（进程崩溃、被 kill），但为了避免把
     * "刚好在写回"的消费者误判为失败，需要再等一段时间才回收。
     */
    private Long processingLeaseGraceMinutes = 5L;

    /**
     * 无执行令牌时的空闲窗口（分钟）
     * <p>文档处于 RUNNING 但没有任何消费者持有令牌，说明消息丢失或重试已耗尽进入死信队列。
     * 该值以"最后一次尝试结束（释放令牌）"为起点计时，因此只需大于 RocketMQ 单次重试的
     * 最大间隔（默认 2 小时，即第 16 次重试）并留出余量，否则会在重试等待期间把任务误判为失败。
     */
    private Long chunkRetryWindowMinutes = 180L;
}
