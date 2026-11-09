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
import com.askflow.framework.convention.Result;
import com.askflow.framework.web.Results;
import com.askflow.rag.config.SearchChannelProperties;
import com.askflow.rag.core.intent.IntentResolver;
import com.askflow.rag.core.retrieve.RetrievalEngine;
import com.askflow.rag.core.rewrite.QueryRewriteService;
import com.askflow.rag.core.rewrite.RewriteResult;
import com.askflow.rag.dto.RetrievalContext;
import com.askflow.rag.dto.SubQuestionIntent;
import com.askflow.rag.eval.EvalEvidenceAssembler.EvalEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 效果评测接口
 * <p>
 * 单条问题的检索证据回放，供外部脚本调用；批量的数据集评测在 admin 后台
 * （{@code /admin/eval/**}）里做，两者共用 {@link EvalEvidenceAssembler} 的证据口径
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.eval", name = "enabled", havingValue = "true")
public class EvalController {

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final EvalEvidenceAssembler evidenceAssembler;

    @GetMapping("/rag/eval")
    public Result<EvalResponse> chat(@RequestParam String question) {
        long start = System.currentTimeMillis();

        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, List.of());
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);
        RetrievalContext rc = retrievalEngine.retrieve(subIntents, searchProperties.getDefaultTopK());

        return Results.success(buildResponse(rc, subIntents, System.currentTimeMillis() - start));
    }

    private EvalResponse buildResponse(RetrievalContext rc, List<SubQuestionIntent> subIntents, long latencyMs) {
        EvalEvidence evidence = evidenceAssembler.assemble(rc);

        return EvalResponse.builder()
                .retrievedDocIds(evidence.getDocIds())
                .retrievedChunkIds(evidence.getChunkIds())
                .retrievedContexts(evidence.getContexts())
                .retrievedContextDocIds(evidence.getContextDocIds())
                .mcpContext(rc == null ? null : rc.getMcpContext())
                .hasMcp(rc != null && rc.hasMcp())
                .hasKb(rc != null && rc.hasKb())
                .subIntents(extractSubIntents(subIntents))
                .intentLeafIds(extractTopLeafIds(subIntents))
                .latencyMs(latencyMs)
                .build();
    }

    private List<String> extractSubIntents(List<SubQuestionIntent> intents) {
        if (CollUtil.isEmpty(intents)) {
            return Collections.emptyList();
        }
        return intents.stream()
                .map(SubQuestionIntent::subQuestion)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    private List<String> extractTopLeafIds(List<SubQuestionIntent> intents) {
        if (CollUtil.isEmpty(intents)) {
            return Collections.emptyList();
        }
        return intents.stream()
                .map(si -> {
                    if (CollUtil.isEmpty(si.nodeScores())) {
                        return null;
                    }
                    return si.nodeScores().get(0).getNode().getId();
                })
                .collect(Collectors.toList());
    }
}
