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

import com.askflow.infra.embedding.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryEmbeddingServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private KbEmbeddingProfileProvider profileProvider;

    @Test
    void shouldUseKnowledgeBaseModelWhenRegistered() {
        when(profileProvider.modelOf("collection_a")).thenReturn("model_a");
        when(embeddingService.embed("query", "model_a")).thenReturn(List.of(0.5f));
        QueryEmbeddingService service = new QueryEmbeddingService(embeddingService, profileProvider);

        assertEquals(List.of(0.5f), service.embed("query", "collection_a"));
        // 知识库绑定了模型时，不允许再走默认模型
        verify(embeddingService, never()).embed("query");
    }

    @Test
    void shouldUseDefaultModelWhenCollectionHasNoProfile() {
        when(profileProvider.modelOf("collection_a")).thenReturn(null);
        when(embeddingService.embed("query")).thenReturn(List.of(0.1f));
        QueryEmbeddingService service = new QueryEmbeddingService(embeddingService, profileProvider);

        assertEquals(List.of(0.1f), service.embed("query", "collection_a"));
        verify(embeddingService, never()).embed("query", "model_a");
    }

    @Test
    void shouldFallbackToDefaultModelWhenSpecifiedModelUnavailable() {
        when(profileProvider.modelOf("collection_a")).thenReturn("model_a");
        when(embeddingService.embed("query", "model_a")).thenThrow(new RuntimeException("model offline"));
        when(embeddingService.embed("query")).thenReturn(List.of(0.2f));
        QueryEmbeddingService service = new QueryEmbeddingService(embeddingService, profileProvider);

        // 模型不可用不能中断整条检索链，但必须回退默认模型并留痕
        assertEquals(List.of(0.2f), service.embed("query", "collection_a"));
        verify(embeddingService).embed("query");
    }
}
