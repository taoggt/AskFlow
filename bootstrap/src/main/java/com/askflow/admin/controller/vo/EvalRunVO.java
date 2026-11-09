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

import java.util.Date;

/**
 * 评测运行视图
 *
 * @param metrics 指标聚合结果，直接透出 JSON 对象，前端不必再解析字符串
 */
public record EvalRunVO(
        String id,
        String datasetId,
        String datasetName,
        String status,
        Integer totalCount,
        Integer doneCount,
        Integer failedCount,
        Integer topK,
        Integer judgeEnabled,
        String judgeModel,
        Object metrics,
        String errorMessage,
        Date startedAt,
        Date completedAt,
        Date createTime
) {
}
