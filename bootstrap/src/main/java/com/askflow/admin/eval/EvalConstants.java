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

package com.askflow.admin.eval;

/**
 * 评测相关常量
 * <p>
 * 这里集中放状态、分支、路由的字符串字面量，避免在实体、计算器、前端约定之间各写一份
 */
public final class EvalConstants {

    private EvalConstants() {
    }

    // ==================== 运行状态 ====================

    public static final String RUN_PENDING = "pending";
    public static final String RUN_RUNNING = "running";
    public static final String RUN_SUCCESS = "success";
    public static final String RUN_FAILED = "failed";
    public static final String RUN_CANCELLED = "cancelled";

    // ==================== 回答分支 ====================

    /**
     * 正常走完了「检索 + 生成」
     */
    public static final String BRANCH_RAG = "rag";

    /**
     * 纯系统意图，命中短路，不检索
     */
    public static final String BRANCH_SYSTEM_ONLY = "system_only";

    /**
     * 意图歧义，命中反问引导短路
     */
    public static final String BRANCH_GUIDANCE = "guidance";

    /**
     * 检索结果为空，直接返回兜底话术
     */
    public static final String BRANCH_EMPTY_RETRIEVAL = "empty_retrieval";

    // ==================== 样本终态 ====================

    public static final String FINAL_SUCCESS = "success";
    public static final String FINAL_REFUSED = "refused";
    public static final String FINAL_ERROR = "error";

    // ==================== 期望路由 ====================

    public static final String ROUTE_KB = "KB";
    public static final String ROUTE_SYSTEM = "SYSTEM";

    // ==================== 兜底话术标记 ====================

    /**
     * 兜底率指标的匹配子串（不含句号，与参考实现口径一致）
     */
    public static final String NO_DOC_MARKER = "未检索到与问题相关的文档内容";

    // ==================== 判分跳过原因 ====================

    public static final String SKIP_REASON_REFUSED = "not_success";
    public static final String SKIP_REASON_EMPTY_ANSWER = "empty_answer";
    public static final String SKIP_REASON_EMPTY_CONTEXT = "empty_context";
    public static final String SKIP_REASON_EMPTY_REFERENCE = "empty_reference";
    public static final String SKIP_REASON_SYSTEM_ROUTE = "system_route";
    public static final String SKIP_REASON_DISABLED = "judge_disabled";
    public static final String SKIP_REASON_BUDGET = "budget_exceeded";
}
