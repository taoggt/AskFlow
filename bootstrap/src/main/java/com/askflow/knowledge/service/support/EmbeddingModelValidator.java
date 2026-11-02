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

package com.askflow.knowledge.service.support;

import cn.hutool.core.util.StrUtil;
import com.askflow.framework.exception.ClientException;
import com.askflow.infra.config.AIModelProperties;
import com.askflow.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 知识库嵌入模型校验器
 * <p>
 * 知识库绑定的模型会同时用于入库与检索，一旦模型不存在、被禁用或维度与向量库不一致，
 * 会导致向量写不进库或检索时静默走错模型。这里在建库/改库时就拦下，避免问题延迟到运行期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingModelValidator {

    private final AIModelProperties aiModelProperties;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 校验知识库选择的嵌入模型是否可用
     *
     * @param embeddingModel 模型标识
     */
    public void validate(String embeddingModel) {
        if (StrUtil.isBlank(embeddingModel)) {
            throw new ClientException("嵌入模型不能为空");
        }

        AIModelProperties.ModelCandidate candidate = findCandidate(embeddingModel);
        if (candidate == null) {
            throw new ClientException("嵌入模型不存在：" + embeddingModel);
        }
        if (Boolean.FALSE.equals(candidate.getEnabled())) {
            throw new ClientException("嵌入模型已被禁用：" + embeddingModel);
        }

        Integer modelDimension = candidate.getDimension();
        Integer vectorDimension = ragDefaultProperties.getDimension();
        if (modelDimension == null) {
            log.warn("嵌入模型 {} 未配置 dimension，跳过维度校验，请确认其输出维度与向量库一致", embeddingModel);
            return;
        }
        if (vectorDimension != null && !Objects.equals(modelDimension, vectorDimension)) {
            throw new ClientException(StrUtil.format(
                    "嵌入模型 {} 的维度为 {}，与向量库维度 {} 不一致，写入向量会失败",
                    embeddingModel, modelDimension, vectorDimension));
        }
    }

    private AIModelProperties.ModelCandidate findCandidate(String embeddingModel) {
        List<AIModelProperties.ModelCandidate> candidates = aiModelProperties.getEmbedding().getCandidates();
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> embeddingModel.equals(candidate.getId()))
                .findFirst()
                .orElse(null);
    }
}
