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
import java.util.Set;

/**
 * 导入结果
 * <p>
 * unknownFields 和 warnings 必须回给前端：数据集里可能有 schema 之外的字段（如 eval_metrics），
 * 静默丢弃会让导出的内容与原文件不一致；业务码对不上则会让 hit@K 一直是 0 却查不出原因
 */
public record EvalImportResultVO(
        int imported,
        int updated,
        int skipped,
        int failed,
        Set<String> unknownFields,
        List<String> warnings
) {
}
