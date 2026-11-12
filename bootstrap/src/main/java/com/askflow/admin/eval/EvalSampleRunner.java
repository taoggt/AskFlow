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

package com.askflow.admin.eval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.askflow.admin.config.EvalRunProperties;
import com.askflow.admin.dao.entity.EvalCaseResultDO;
import com.askflow.admin.dao.entity.EvalRunDO;
import com.askflow.admin.dao.entity.EvalSampleDO;
import com.askflow.framework.convention.ChatMessage;
import com.askflow.framework.convention.ChatRequest;
import com.askflow.infra.chat.LLMService;
import com.askflow.infra.chat.StreamCallback;
import com.askflow.infra.chat.StreamCancellationHandle;
import com.askflow.rag.constant.RAGConstant;
import com.askflow.rag.core.guidance.GuidanceDecision;
import com.askflow.rag.core.guidance.IntentGuidanceService;
import com.askflow.rag.core.intent.IntentResolver;
import com.askflow.rag.core.prompt.PromptContext;
import com.askflow.rag.core.prompt.PromptTemplateLoader;
import com.askflow.rag.core.prompt.RAGPromptService;
import com.askflow.rag.core.retrieve.RetrievalEngine;
import com.askflow.rag.core.rewrite.QueryRewriteService;
import com.askflow.rag.core.rewrite.RewriteResult;
import com.askflow.rag.dto.IntentGroup;
import com.askflow.rag.dto.RetrievalContext;
import com.askflow.rag.dto.SubQuestionIntent;
import com.askflow.rag.eval.EvalEvidenceAssembler;
import com.askflow.rag.eval.EvalEvidenceAssembler.EvalEvidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.askflow.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;

/**
 * 单条样本的评测执行器
 * <p>
 * 严格复刻 {@code StreamChatPipeline#execute} 的顺序：改写 → 意图 → 歧义引导短路 →
 * 纯系统意图短路 → 检索 → 生成。三个短路必须复现，否则行为类指标会失真：
 * 例如纯系统意图的问题本来就不该检索，不复现就会被算成「该拒未拒」或「过召回」。
 * <p>
 * 这里刻意不复用流式对话的 SSE 链路：评测只要文本和耗时，不需要 emitter、幂等锁和会话落库
 * <p>
 * 注意类名不要改回 {@code EvalSampleExecutor}：@Component 的默认 bean 名是类名首字母小写，
 * 会与 ThreadPoolExecutorConfig 里 {@code @Bean evalSampleExecutor}（评测样本线程池）重名，
 * 导致启动时 BeanDefinitionOverrideException。本项目里 *Executor 后缀约定留给线程池，
 * 承载业务逻辑的组件用 *Runner / *Service。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalSampleRunner {

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final LLMService llmService;
    private final EvalEvidenceAssembler evidenceAssembler;
    private final EvalJudgeService judgeService;
    private final EvalRunProperties runProperties;

    /**
     * 执行一条样本，返回可直接落库的结果（不写库，写库由引擎负责）
     *
     * @param judgeAllowed 是否还有判分预算（引擎按 maxSamples 控制）
     */
    public EvalCaseResultDO execute(EvalRunDO run, EvalSampleDO sample,
                                    EvalRunCancellation cancellation, boolean judgeAllowed) {
        long start = System.currentTimeMillis();
        EvalCaseResultDO result = EvalCaseResultDO.builder()
                .runId(run.getId())
                .queryId(sample.getQueryId())
                // 标注快照：样本后续被修改，历史运行仍可复算
                .queryText(sample.getQueryText())
                .groundTruth(sample.getGroundTruth())
                .intentL1(sample.getIntentL1())
                .intentL2(sample.getIntentL2())
                .expectedRoute(sample.getExpectedRoute())
                .requiresRag(sample.getRequiresRag())
                .expectedDocIds(sample.getExpectedDocIds())
                .build();

        try {
            // 1. 改写（固定无历史：评测要可复现，不能被上一轮对话影响）
            RewriteResult rewrite = queryRewriteService.rewriteWithSplit(sample.getQueryText(), List.of());
            // 2. 意图
            List<SubQuestionIntent> subIntents = intentResolver.resolve(rewrite);
            result.setRewrittenQuestion(rewrite.rewrittenQuestion());
            result.setIntentPred(firstIntentId(subIntents));

            // 3. 歧义引导短路
            GuidanceDecision guidance = guidanceService.detectAmbiguity(rewrite.rewrittenQuestion(), subIntents);
            if (guidance.isPrompt()) {
                result.setAnswerBranch(EvalConstants.BRANCH_GUIDANCE);
                result.setResponse(guidance.getPrompt());
                result.setFinalStatus(EvalConstants.FINAL_REFUSED);
            } else if (isSystemOnly(subIntents)) {
                // 4. 纯系统意图短路：不检索，直接让模型回答
                result.setAnswerBranch(EvalConstants.BRANCH_SYSTEM_ONLY);
                Generation generation = generate(buildSystemRequest(rewrite.rewrittenQuestion(), subIntents), cancellation);
                result.setResponse(generation.content());
                result.setFirstTokenMs(generation.firstTokenMs());
                result.setFinalStatus(EvalConstants.FINAL_SUCCESS);
            } else {
                // 5. 检索 + 证据采集
                RetrievalContext retrievalContext = retrievalEngine.retrieve(subIntents, resolveTopK(run));
                EvalEvidence evidence = evidenceAssembler.assemble(retrievalContext);
                result.setRetrievedDocIds(EvalJson.toJson(evidence.getDocIds()));
                if (runProperties.isStoreContexts()) {
                    result.setRetrievedContexts(EvalJson.toJson(evidence.getContexts()));
                    result.setRetrievedContextDocIds(EvalJson.toJson(evidence.getContextDocIds()));
                }
                if (retrievalContext.isEmpty()) {
                    result.setAnswerBranch(EvalConstants.BRANCH_EMPTY_RETRIEVAL);
                    result.setResponse(RAGConstant.NO_DOC_REPLY);
                    result.setFinalStatus(EvalConstants.FINAL_REFUSED);
                } else {
                    // 6. 生成
                    result.setAnswerBranch(EvalConstants.BRANCH_RAG);
                    Generation generation = generate(buildRagRequest(rewrite, retrievalContext, subIntents), cancellation);
                    result.setResponse(generation.content());
                    result.setFirstTokenMs(generation.firstTokenMs());
                    result.setFinalStatus(EvalConstants.FINAL_SUCCESS);
                }
            }
        } catch (Exception e) {
            log.warn("[评测] 样本执行失败 runId={} queryId={}", run.getId(), sample.getQueryId(), e);
            result.setAnswerBranch(StrUtil.blankToDefault(result.getAnswerBranch(), EvalConstants.BRANCH_RAG));
            result.setFinalStatus(EvalConstants.FINAL_ERROR);
            result.setErrorMessage(StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 900));
        }

        result.setLatencyMs(System.currentTimeMillis() - start);
        // 判分：失败样本、系统路由样本、空答案/空上下文样本都会被跳过，跳过原因写进 scores_json
        result.setScoresJson(judgeService.judge(result, run.getJudgeModel(), judgeAllowed));
        return result;
    }

    // ==================== 生成 ====================

    /**
     * 调用流式生成并等到真正结束
     * <p>
     * streamChat 是「首包到达即返回」，如果只等方法返回就记耗时，latency_ms 会严重低估
     */
    private Generation generate(ChatRequest request, EvalRunCancellation cancellation) throws InterruptedException {
        StringBuilder content = new StringBuilder();
        AtomicLong firstTokenMs = new AtomicLong(-1L);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        long start = System.currentTimeMillis();

        StreamCallback callback = new StreamCallback() {
            @Override
            public void onContent(String chunk) {
                if (StrUtil.isNotEmpty(chunk)) {
                    firstTokenMs.compareAndSet(-1L, System.currentTimeMillis() - start);
                    content.append(chunk);
                }
            }

            @Override
            public void onComplete() {
                finished.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.compareAndSet(null, throwable);
                finished.countDown();
            }
        };

        StreamCancellationHandle handle = llmService.streamChat(request, callback);
        cancellation.register(handle);
        try {
            if (!finished.await(runProperties.getSampleTimeoutMs(), TimeUnit.MILLISECONDS)) {
                if (handle != null) {
                    handle.cancel();
                }
                throw new IllegalStateException("生成超时：" + runProperties.getSampleTimeoutMs() + "ms");
            }
        } finally {
            cancellation.unregister(handle);
        }
        if (error.get() != null) {
            throw new IllegalStateException("生成失败：" + error.get().getMessage(), error.get());
        }
        return new Generation(content.toString(), firstTokenMs.get(), System.currentTimeMillis() - start);
    }

    /**
     * 复刻 {@code StreamChatPipeline#streamRagResponse} 的 Prompt 组装与生成参数，
     * 保证评测用的 Prompt 与线上完全一致；两者若漂移，判分结果就不再代表线上效果
     */
    private ChatRequest buildRagRequest(RewriteResult rewrite, RetrievalContext retrievalContext,
                                        List<SubQuestionIntent> subIntents) {
        IntentGroup intentGroup = intentResolver.mergeIntentGroup(subIntents);
        PromptContext promptContext = PromptContext.builder()
                .question(rewrite.rewrittenQuestion())
                .mcpContext(retrievalContext.getMcpContext())
                .kbContext(retrievalContext.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(retrievalContext.getIntentChunks())
                .build();
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                List.of(),
                rewrite.rewrittenQuestion(),
                rewrite.subQuestions()
        );
        boolean hasMcp = retrievalContext.hasMcp();
        return ChatRequest.builder()
                .messages(messages)
                .thinking(false)
                .temperature(hasMcp ? 0.3D : 0D)
                .topP(hasMcp ? 0.8D : 1D)
                .build();
    }

    /**
     * 复刻 {@code StreamChatPipeline#streamSystemResponse}：纯系统意图不注入知识库上下文
     */
    private ChatRequest buildSystemRequest(String question, List<SubQuestionIntent> subIntents) {
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores() == null ? java.util.stream.Stream.empty() : si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(question));
        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
    }

    // ==================== 小工具 ====================

    /**
     * 与生产 {@code StreamChatPipeline#handleSystemOnly} 保持一致：
     * 意图为空时 allMatch 恒为 true，同样走系统回答分支。评测若在这里「更严谨」，
     * 就会把生产上被短路掉的样本算成检索失败，指标反而失真
     */
    private boolean isSystemOnly(List<SubQuestionIntent> subIntents) {
        return CollUtil.isEmpty(subIntents)
                || subIntents.stream().allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
    }

    private String firstIntentId(List<SubQuestionIntent> subIntents) {
        if (CollUtil.isEmpty(subIntents)) {
            return null;
        }
        return subIntents.stream()
                .filter(si -> CollUtil.isNotEmpty(si.nodeScores()))
                .map(si -> si.nodeScores().get(0).getNode().getId())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    private int resolveTopK(EvalRunDO run) {
        return run.getTopK() == null ? 10 : run.getTopK();
    }

    /**
     * 一次生成的结果
     *
     * @param content      完整答案
     * @param firstTokenMs 首 token 耗时，-1 表示一个 token 都没吐出来
     * @param latencyMs    端到端耗时
     */
    private record Generation(String content, long firstTokenMs, long latencyMs) {
    }
}
