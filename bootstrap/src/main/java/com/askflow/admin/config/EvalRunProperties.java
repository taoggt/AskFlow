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

package com.askflow.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评测执行配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.eval.run")
public class EvalRunProperties {

    /**
     * 是否允许发起评测
     */
    private boolean enabled = true;

    /**
     * 单次运行最多评测的样本数，防止误提交全量数据集烧掉大量 token
     */
    private int maxSamples = 60;

    /**
     * 单个运行内部的样本并发度
     */
    private int concurrency = 2;

    /**
     * 全局同时进行的样本数上限（跨运行共享）
     * <p>
     * 必须压得比较低：streamChat 的 SSE 读取跑在全局 modelStreamExecutor 上，
     * core 只有 max(2, CPU/2) 且使用 AbortPolicy，评测并发一高就会和线上对话抢线程池
     */
    private int maxConcurrentSamples = 2;

    /**
     * 单条样本的总超时时间
     */
    private long sampleTimeoutMs = 180000L;

    /**
     * 单条样本的最大尝试次数（含首次）
     */
    private int maxAttempts = 2;

    /**
     * 是否把命中的 chunk 文本落库
     * <p>
     * 关掉可以显著减小 JSONB 行体积，但判分需要上下文，关掉后判分会被跳过
     */
    private boolean storeContexts = true;
}
