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

package com.askflow.knowledge.dao.entity;

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
 * 知识库文档实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_document")
public class KnowledgeDocumentDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 所属知识库 ID
     */
    private String kbId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 来源类型：file / url
     */
    private String sourceType;

    /**
     * 来源位置（URL）
     */
    private String sourceLocation;

    /**
     * 是否开启定时拉取：1-启用，0-禁用
     */
    private Integer scheduleEnabled;

    /**
     * 定时表达式（cron）
     */
    private String scheduleCron;

    /**
     * 是否启用：1-启用，0-禁用
     */
    private Integer enabled;

    /**
     * 分块数（chunk 数量）
     */
    private Integer chunkCount;

    /**
     * 文件地址（存 OSS / NFS 等路径）
     */
    private String fileUrl;

    /**
     * 文件类型：pdf / markdown / docx 等
     */
    private String fileType;

    /**
     * 文件大小（单位字节）
     */
    private Long fileSize;

    /**
     * 文件内容 SHA-256 十六进制
     * <p>用于同一知识库内的文件去重；远程 URL 文档与历史数据为空。
     */
    private String contentHash;

    /**
     * 处理模式：chunk / pipeline
     * - chunk: 使用分块策略直接分块
     * - pipeline: 使用数据通道进行清洗处理
     */
    private String processMode;

    /**
     * 分块策略
     * 仅在 processMode=chunk 时有效
     */
    private String chunkStrategy;

    /**
     * 分块参数配置（JSON）
     * 仅在 processMode=chunk 时有效
     */
    @TableField(typeHandler = com.askflow.knowledge.dao.handler.JsonbTypeHandler.class)
    private String chunkConfig;

    /**
     * 数据通道（Pipeline）ID
     * 仅在 processMode=pipeline 时有效
     */
    private String pipelineId;

    /**
     * 状态：
     * - pending：待向量化
     * - running：向量化中
     * - failed：向量化失败
     * - success：向量化完成
     */
    private String status;

    /**
     * 当前有效的文档处理任务 ID
     * <p>每次触发分块都会覆盖为新的任务 ID，旧任务的回写将被乐观条件拦截。
     */
    private String currentChunkTaskId;

    /**
     * 当前消费者持有的执行令牌
     * <p>为空表示没有消费者在执行；与 {@link #currentChunkTaskId} 一起构成
     * 执行权的 fencing token，避免租约过期后的旧消费者覆盖新消费者的结果。
     */
    private String processingToken;

    /**
     * 当前执行令牌的有效期
     * <p>超过该时间视为消费者已失联，允许其他消费者抢占。
     */
    private Date processingLeaseUntil;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 修改人
     */
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除：0-正常，1-删除
     */
    @TableLogic
    private Integer deleted;
}
