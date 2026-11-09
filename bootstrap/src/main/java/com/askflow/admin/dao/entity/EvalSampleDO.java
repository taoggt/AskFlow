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
 * 评测样本实体
 * <p>
 * 字段对齐外部评测项目的 eval_set_*.jsonl schema，便于直接导入
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_sample")
public class EvalSampleDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String datasetId;

    /**
     * 样本业务 ID，数据集内唯一
     */
    private String queryId;

    @TableField("query_text")
    private String queryText;

    /**
     * 一级意图，用于指标分桶
     */
    private String intentL1;

    /**
     * 二级意图，与意图识别结果直接比对
     */
    private String intentL2;

    /**
     * 是否需要检索，0/1
     */
    private Integer requiresRag;

    /**
     * 期望召回的文档业务码（must），JSON 数组字符串
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String expectedDocIds;

    /**
     * 期望召回的文档业务码（nice），JSON 数组字符串
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String expectedDocIdsNice;

    /**
     * 参考答案，判分用
     */
    private String groundTruth;

    /**
     * 期望路由：KB（需要检索）/ SYSTEM（不需要检索）
     */
    private String expectedRoute;

    private Integer sampleOrder;

    private String createdBy;

    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
