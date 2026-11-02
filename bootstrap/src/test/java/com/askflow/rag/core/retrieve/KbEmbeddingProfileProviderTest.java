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

import com.askflow.knowledge.dao.entity.KnowledgeBaseDO;
import com.askflow.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KbEmbeddingProfileProviderTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    /**
     * Lambda 条件构造器依赖 MyBatis-Plus 的实体元数据缓存，真实运行由框架启动时写入，
     * 纯单元测试需要手动初始化，否则 {@code Wrappers.lambdaQuery} 会抛异常
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
    }

    @Test
    void shouldCacheProfilesWithinTtl() {
        when(knowledgeBaseMapper.selectList(any()))
                .thenReturn(List.of(kb("collection_a", "model_a")));
        KbEmbeddingProfileProvider provider = new KbEmbeddingProfileProvider(knowledgeBaseMapper, 30_000L);

        assertEquals("model_a", provider.modelOf("collection_a"));
        assertEquals("model_a", provider.modelOf("collection_a"));
        assertEquals(1, provider.collections().size());

        // TTL 内只查一次库，避免检索扇出时反复打库
        verify(knowledgeBaseMapper, times(1)).selectList(any());
    }

    @Test
    void shouldReloadAfterInvalidate() {
        when(knowledgeBaseMapper.selectList(any()))
                .thenReturn(List.of(kb("collection_a", "model_a")))
                .thenReturn(List.of(kb("collection_a", "model_a"), kb("collection_b", "model_b")));
        KbEmbeddingProfileProvider provider = new KbEmbeddingProfileProvider(knowledgeBaseMapper, 30_000L);

        assertEquals(1, provider.collections().size());

        // 新建知识库后会主动失效，下一次访问必须能看到新 collection
        provider.invalidate();

        assertEquals("model_b", provider.modelOf("collection_b"));
        verify(knowledgeBaseMapper, times(2)).selectList(any());
    }

    @Test
    void shouldKeepStaleSnapshotWhenReloadFails() {
        when(knowledgeBaseMapper.selectList(any()))
                .thenReturn(List.of(kb("collection_a", "model_a")))
                .thenThrow(new RuntimeException("db down"));
        // ttl <= 0 表示不缓存，第二次访问会触发重新加载
        KbEmbeddingProfileProvider provider = new KbEmbeddingProfileProvider(knowledgeBaseMapper, 0L);

        assertEquals("model_a", provider.modelOf("collection_a"));
        // 加载失败时沿用旧快照，不能把 DB 抖动放大成整轮空召回
        assertEquals("model_a", provider.modelOf("collection_a"));
        assertEquals(1, provider.collections().size());
    }

    @Test
    void shouldReturnNullForUnknownCollection() {
        when(knowledgeBaseMapper.selectList(any()))
                .thenReturn(List.of(kb("collection_a", "model_a")));
        KbEmbeddingProfileProvider provider = new KbEmbeddingProfileProvider(knowledgeBaseMapper, 30_000L);

        assertNull(provider.modelOf("collection_not_exists"));
        assertNull(provider.modelOf(null));
        assertTrue(provider.collections().contains("collection_a"));
    }

    private KnowledgeBaseDO kb(String collectionName, String embeddingModel) {
        KnowledgeBaseDO kb = new KnowledgeBaseDO();
        kb.setCollectionName(collectionName);
        kb.setEmbeddingModel(embeddingModel);
        return kb;
    }
}
