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

package com.askflow.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import com.askflow.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询向量生成服务
 * <p>
 * 检索阶段的 Query Embedding 必须与入库阶段使用同一个模型，否则两侧向量不在同一空间，
 * 相似度计算失去意义。本服务按 collection 反查知识库绑定的模型，统一收口 embedding 调用，
 * 使对话、评测等所有检索入口都不会遗漏模型信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryEmbeddingService {

    /**
     * 回退默认模型时的固定日志关键字，便于线上按关键字告警与检索
     */
    public static final String FALLBACK_LOG_KEYWORD = "EMBEDDING_MODEL_FALLBACK";

    private final EmbeddingService embeddingService;
    private final KbEmbeddingProfileProvider profileProvider;

    /**
     * 生成查询向量
     *
     * @param query          用户问题
     * @param collectionName 目标向量集合，用于反查知识库绑定的嵌入模型
     * @return 查询向量
     */
    public List<Float> embed(String query, String collectionName) {
        String modelId = profileProvider.modelOf(collectionName);
        if (StrUtil.isBlank(modelId)) {
            // 无知识库上下文或该 collection 未登记模型：沿用全局默认模型
            return embeddingService.embed(query);
        }
        try {
            return embeddingService.embed(query, modelId);
        } catch (Exception e) {
            // 指定模型不可用（已下线、被熔断、配置缺失）时不能让整条链路失败，但必须留痕：
            // 回退默认模型后两侧向量可能不同源，召回质量会下降
            log.warn("{} collection={} model={} reason={}，回退默认模型，召回质量可能下降",
                    FALLBACK_LOG_KEYWORD, collectionName, modelId, e.getMessage());
            return embeddingService.embed(query);
        }
    }
}
