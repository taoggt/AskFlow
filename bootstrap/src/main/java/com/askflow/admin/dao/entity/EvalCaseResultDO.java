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

package com.askflow.admin.dao.entity;

import com.askflow.knowledge.dao.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 评测样本结果实体
 * <p>
 * 检索证据、答案、耗时都在执行时当场采集：现有 trace 只记节点结构与耗时，
 * 查询 VO 不返回 extraData，AOP 也不记录出入参，事后无法还原改写结果与命中文档
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_case_result")
public class EvalCaseResultDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String runId;

    private String queryId;

    /**
     * 标注快照：查询文本
     */
    @TableField("query_text")
    private String queryText;

    @TableField("ground_truth")
    private String groundTruth;

    @TableField("intent_l1")
    private String intentL1;

    @TableField("intent_l2")
    private String intentL2;

    /**
     * 标注快照：期望路由 KB / SYSTEM
     */
    @TableField("expected_route")
    private String expectedRoute;

    /**
     * 标注快照：是否需要检索，0/1
     */
    @TableField("requires_rag")
    private Integer requiresRag;

    /**
     * 标注快照：must 文档业务码，JSON 数组字符串
     */
    @TableField(value = "expected_doc_ids", typeHandler = JsonbTypeHandler.class)
    private String expectedDocIds;

    /**
     * 实际召回的文档业务码（去重、保序），JSON 数组字符串
     */
    @TableField(value = "retrieved_doc_ids", typeHandler = JsonbTypeHandler.class)
    private String retrievedDocIds;

    /**
     * 与 retrievedContexts 等长、保留 null 的文档业务码，JSON 数组字符串
     */
    @TableField(value = "retrieved_context_doc_ids", typeHandler = JsonbTypeHandler.class)
    private String retrievedContextDocIds;

    /**
     * 命中的 chunk 文本，JSON 数组字符串
     */
    @TableField(value = "retrieved_contexts", typeHandler = JsonbTypeHandler.class)
    private String retrievedContexts;

    /**
     * 意图识别 top-1
     */
    @TableField("intent_pred")
    private String intentPred;

    @TableField("rewritten_question")
    private String rewrittenQuestion;

    private String response;

    /**
     * rag / system_only / guidance / empty_retrieval
     */
    @TableField("answer_branch")
    private String answerBranch;

    /**
     * success / refused / error
     */
    @TableField("final_status")
    private String finalStatus;

    @TableField("latency_ms")
    private Long latencyMs;

    @TableField("first_token_ms")
    private Long firstTokenMs;

    /**
     * 指标分与判分原始输出，JSON 对象字符串
     */
    @TableField(value = "scores_json", typeHandler = JsonbTypeHandler.class)
    private String scoresJson;

    @TableField("error_message")
    private String errorMessage;

    private String createdBy;

    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
