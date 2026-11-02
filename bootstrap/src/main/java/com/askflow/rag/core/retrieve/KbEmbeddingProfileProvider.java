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
import com.askflow.knowledge.dao.entity.KnowledgeBaseDO;
import com.askflow.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库嵌入模型档案提供者
 * <p>
 * 维护 {@code collectionName -> embeddingModel} 映射，供检索阶段按知识库选择向量化模型。
 * 背景：入库时按知识库绑定的模型生成向量，若检索时改用全局默认模型，两侧向量不在同一空间，
 * 相似度会失真且不会报错，因此必须使用同一模型。
 * <p>
 * 缓存策略：
 * <ul>
 *   <li>快照缓存，默认 30 秒过期（{@code rag.embedding-profile.cache-ttl-ms}，&lt;=0 表示不缓存）</li>
 *   <li>加载单飞：并发检索时只允许一个线程查库，避免全局检索扇出导致惊群</li>
 *   <li>加载失败保留上一份快照并退避重试，避免 DB 抖动放大成整轮空召回</li>
 * </ul>
 * 知识库增删改后应调用 {@link #invalidate()}，否则新库在 TTL 窗口内不会进入全局检索。
 */
@Slf4j
@Component
public class KbEmbeddingProfileProvider {

    /**
     * 加载失败后的退避时间，避免数据库抖动期间每个请求都打库
     */
    private static final long FAILURE_BACKOFF_MS = 5_000L;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /**
     * 快照有效期（毫秒），小于等于 0 时每次都重新加载
     */
    private final long cacheTtlMs;

    private final Object loadLock = new Object();

    private volatile Snapshot snapshot = Snapshot.empty();

    /**
     * 下次允许重试加载的时间戳，仅在持有 {@link #loadLock} 时读写
     */
    private long nextRetryAt;

    public KbEmbeddingProfileProvider(KnowledgeBaseMapper knowledgeBaseMapper,
                                      @Value("${rag.embedding-profile.cache-ttl-ms:30000}") long cacheTtlMs) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.cacheTtlMs = cacheTtlMs;
    }

    /**
     * 查询指定 collection 绑定的嵌入模型
     *
     * @param collectionName 向量集合名称
     * @return 模型标识；collection 为空或未登记时返回 null，由调用方回退默认模型
     */
    public String modelOf(String collectionName) {
        if (StrUtil.isBlank(collectionName)) {
            return null;
        }
        Snapshot current = current();
        String model = current.model(collectionName);
        if (model == null && current.warnedCollections.add(collectionName)) {
            // 每个快照代次只提示一次：知识库可能已删除，或意图节点的 collectionName 与知识库漂移
            log.warn("collection {} 未登记嵌入模型，检索将回退默认模型，请检查知识库是否被删除或意图节点配置是否漂移",
                    collectionName);
        }
        return model;
    }

    /**
     * 全部已登记的知识库 collection（按知识库创建顺序）
     */
    public Set<String> collections() {
        return current().collections();
    }

    /**
     * 预热快照，供调用方在并行检索前主动触发一次加载
     */
    public void warmUp() {
        current();
    }

    /**
     * 使快照失效，下一次访问重新加载
     */
    public void invalidate() {
        synchronized (loadLock) {
            snapshot = Snapshot.empty();
            nextRetryAt = 0L;
        }
        log.info("知识库嵌入模型映射缓存已失效，下次检索时重新加载");
    }

    private Snapshot current() {
        Snapshot current = snapshot;
        if (!current.isExpired(cacheTtlMs)) {
            return current;
        }
        synchronized (loadLock) {
            current = snapshot;
            if (!current.isExpired(cacheTtlMs)) {
                return current;
            }
            long now = System.currentTimeMillis();
            if (now < nextRetryAt) {
                return current;
            }
            try {
                Snapshot fresh = Snapshot.of(loadProfiles(), now);
                snapshot = fresh;
                nextRetryAt = 0L;
                log.info("知识库嵌入模型映射加载完成，共 {} 个 collection", fresh.collections().size());
                return fresh;
            } catch (Exception e) {
                nextRetryAt = now + FAILURE_BACKOFF_MS;
                log.warn("加载知识库嵌入模型映射失败，沿用上一份快照（{} 个 collection），{}ms 后重试",
                        current.collections().size(), FAILURE_BACKOFF_MS, e);
                return current;
            }
        }
    }

    private Map<String, String> loadProfiles() {
        // KnowledgeBaseDO 带 @TableLogic，MyBatis-Plus 会自动追加 deleted = 0，此处显式保留便于阅读
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName, KnowledgeBaseDO::getEmbeddingModel)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        Map<String, String> modelByCollection = new LinkedHashMap<>();
        for (KnowledgeBaseDO kb : kbList) {
            String collectionName = kb.getCollectionName();
            if (StrUtil.isBlank(collectionName)) {
                continue;
            }
            modelByCollection.put(collectionName, StrUtil.trimToNull(kb.getEmbeddingModel()));
        }
        return Collections.unmodifiableMap(modelByCollection);
    }

    /**
     * 不可变快照，保证并发读取期间不会看到半加载状态
     */
    private static final class Snapshot {

        private final Map<String, String> modelByCollection;

        /**
         * 本代次已提示过的 collection，避免同一份数据反复刷日志
         */
        private final Set<String> warnedCollections = ConcurrentHashMap.newKeySet();

        private volatile long loadedAt;

        private Snapshot(Map<String, String> modelByCollection, long loadedAt) {
            this.modelByCollection = modelByCollection;
            this.loadedAt = loadedAt;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyMap(), 0L);
        }

        static Snapshot of(Map<String, String> modelByCollection, long loadedAt) {
            return new Snapshot(modelByCollection, loadedAt);
        }

        String model(String collectionName) {
            return modelByCollection.get(collectionName);
        }

        Set<String> collections() {
            return modelByCollection.keySet();
        }

        boolean isExpired(long ttlMs) {
            if (loadedAt <= 0L || ttlMs <= 0L) {
                return true;
            }
            return System.currentTimeMillis() - loadedAt > ttlMs;
        }
    }
}
