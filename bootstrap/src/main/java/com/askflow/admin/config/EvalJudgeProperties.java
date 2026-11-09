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
 * LLM 判分配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.eval.judge")
public class EvalJudgeProperties {

    /**
     * 是否启用 LLM 判分
     */
    private boolean enabled = true;

    /**
     * 判分模型，留空则走默认路由
     * <p>
     * 建议指向与被评系统不同的模型：同源模型会系统性高估 faithfulness / answer_correctness
     */
    private String model = "";

    /**
     * 单次运行最多判分的样本数
     */
    private int maxSamples = 60;

    /**
     * 单次判分调用失败后的重试次数
     */
    private int maxRetries = 1;

    /**
     * 单次判分调用的超时时间
     */
    private long timeoutMs = 120000L;
}
