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

package com.askflow.admin.controller.vo;

import java.util.List;

/**
 * 评测样本结果视图
 *
 * @param retrievedContexts   命中的 chunk 文本，列表查询时为空以控制响应体积
 * @param scores              判分结果
 */
public record EvalCaseResultVO(
        String id,
        String queryId,
        String queryText,
        String intentL2,
        String intentPred,
        List<String> expectedDocIds,
        List<String> retrievedDocIds,
        List<String> retrievedContexts,
        List<String> retrievedContextDocIds,
        String rewrittenQuestion,
        String response,
        String answerBranch,
        String finalStatus,
        Long latencyMs,
        Long firstTokenMs,
        Object scores,
        String errorMessage
) {
}
