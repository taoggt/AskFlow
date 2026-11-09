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
 * 评测运行实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_eval_run")
public class EvalRunDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String datasetId;

    /**
     * 数据集名称快照，避免数据集改名后历史运行显示不一致
     */
    private String datasetName;

    /**
     * pending / running / success / failed
     */
    private String status;

    /**
     * 取消标记，0/1
     */
    private Integer cancelFlag;

    private Integer totalCount;

    private Integer doneCount;

    private Integer failedCount;

    private Integer topK;

    /**
     * 是否启用 LLM 判分，0/1
     */
    private Integer judgeEnabled;

    private String judgeModel;

    /**
     * 指标聚合结果，JSON 对象字符串
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String metricsJson;

    private String errorMessage;

    private Date startedAt;

    private Date completedAt;

    private String createdBy;

    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
