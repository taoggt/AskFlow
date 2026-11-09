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

package com.askflow.rag.eval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.askflow.framework.convention.RetrievedChunk;
import com.askflow.knowledge.dao.entity.KnowledgeChunkDO;
import com.askflow.knowledge.dao.entity.KnowledgeDocumentDO;
import com.askflow.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.askflow.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.askflow.rag.dto.RetrievalContext;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索证据装配器
 * <p>
 * 把 {@link RetrievalContext} 里的 chunk 摊平、去重，并解析出文档业务码。
 * 检索回放接口（{@link EvalController}）和离线评测引擎共用这一份逻辑：
 * 重复实现会导致「同一份检索结果在两个地方算出不同的文档集合」，指标也就失去意义。
 */
@Component
@RequiredArgsConstructor
public class EvalEvidenceAssembler {

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    /**
     * 装配一次检索的完整证据
     */
    public EvalEvidence assemble(RetrievalContext rc) {
        List<RetrievedChunk> uniqueChunks = flattenChunks(rc);
        List<String> chunkIds = uniqueChunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
        List<String> contexts = uniqueChunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.toList());

        // chunk 维度：与 contexts 一一对应、保留 null、不去重
        List<String> contextDocIds = resolveContextDocIds(uniqueChunks);
        // doc 维度：按 chunk 顺序首次出现并过滤空值，检索指标都基于它
        List<String> docIds = dedupNonBlank(contextDocIds);

        return EvalEvidence.builder()
                .chunkIds(chunkIds)
                .contexts(contexts)
                .contextDocIds(contextDocIds)
                .docIds(docIds)
                .build();
    }

    /**
     * 摊平 intentChunks（Map&lt;intentId, List&lt;RetrievedChunk&gt;&gt;），按 chunk id 去重并保留首次顺序
     */
    public List<RetrievedChunk> flattenChunks(RetrievalContext rc) {
        if (rc == null || CollUtil.isEmpty(rc.getIntentChunks())) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        return rc.getIntentChunks().values().stream()
                .filter(CollUtil::isNotEmpty)
                .flatMap(List::stream)
                .filter(c -> c != null && StrUtil.isNotBlank(c.getId()))
                .filter(c -> seen.add(c.getId()))
                .collect(Collectors.toList());
    }

    /**
     * 与 chunks 一一对应的业务 docId 列表（长度相同、保留 null、不去重）
     * <p>
     * 链路：chunkId → t_knowledge_chunk.doc_id（雪花）→ t_knowledge_document.doc_name → 剥文件后缀。
     * 评测集里的 expected_doc_ids 用业务码（如 {@code FAQ_VAC_001}），与此处对齐
     */
    public List<String> resolveContextDocIds(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return Collections.emptyList();
        }
        List<String> chunkIdsForLookup = chunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        if (chunkIdsForLookup.isEmpty()) {
            return new ArrayList<>(Collections.nCopies(chunks.size(), null));
        }
        // 第一跳：chunkId → 雪花 docId
        Map<String, String> chunkIdToInternalDocId = knowledgeChunkMapper.selectByIds(chunkIdsForLookup).stream()
                .filter(c -> StrUtil.isNotBlank(c.getId()) && StrUtil.isNotBlank(c.getDocId()))
                .collect(Collectors.toMap(
                        KnowledgeChunkDO::getId,
                        KnowledgeChunkDO::getDocId,
                        (a, b) -> a));
        // 第二跳：雪花 docId → 业务码（doc_name 剥后缀）
        List<String> internalDocIds = chunkIdToInternalDocId.values().stream().distinct().collect(Collectors.toList());
        Map<String, String> internalToBizDocId = internalDocIds.isEmpty()
                ? Map.of()
                : knowledgeDocumentMapper.selectByIds(internalDocIds).stream()
                        .filter(d -> StrUtil.isNotBlank(d.getId()) && StrUtil.isNotBlank(d.getDocName()))
                        .collect(Collectors.toMap(
                                KnowledgeDocumentDO::getId,
                                d -> stripExtension(d.getDocName()),
                                (a, b) -> a));
        // 按 chunks 原顺序展开（null 占位保留）
        return chunks.stream()
                .map(c -> {
                    if (StrUtil.isBlank(c.getId())) {
                        return null;
                    }
                    String internal = chunkIdToInternalDocId.get(c.getId());
                    if (StrUtil.isBlank(internal)) {
                        return null;
                    }
                    return internalToBizDocId.get(internal);
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 剥掉最后一个 `.` 之后的文件扩展名；无后缀则原样返回
     */
    public static String stripExtension(String docName) {
        if (docName == null) {
            return null;
        }
        int dot = docName.lastIndexOf('.');
        return (dot > 0 && dot < docName.length() - 1) ? docName.substring(0, dot) : docName;
    }

    /**
     * 按首次出现顺序去重并过滤空值
     */
    public static List<String> dedupNonBlank(List<String> in) {
        if (CollUtil.isEmpty(in)) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        return in.stream()
                .filter(StrUtil::isNotBlank)
                .filter(seen::add)
                .collect(Collectors.toList());
    }

    /**
     * 一次检索的可落库证据
     */
    @Data
    @Builder
    public static class EvalEvidence {

        /**
         * 命中的 chunk 主键，已去重
         */
        private List<String> chunkIds;

        /**
         * 命中的 chunk 文本，与 chunkIds 同序
         */
        private List<String> contexts;

        /**
         * 与 contexts 等长、保留 null 的文档业务码
         */
        private List<String> contextDocIds;

        /**
         * 文档级去重后的业务码，检索指标基于它计算
         */
        private List<String> docIds;
    }
}
