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

package com.askflow.admin.controller.request;

/**
 * 发起评测请求
 *
 * @param datasetId    评测集 ID
 * @param topK         检索返回条数，留空用默认 10
 * @param judgeEnabled 是否启用 LLM 判分，留空默认开启
 * @param judgeModel   判分模型，留空走默认路由
 */
public record EvalRunCreateRequest(
        String datasetId,
        Integer topK,
        Boolean judgeEnabled,
        String judgeModel
) {
}
