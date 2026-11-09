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
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.askflow.admin.config.EvalJudgeProperties;
import com.askflow.admin.dao.entity.EvalCaseResultDO;
import com.askflow.framework.convention.ChatMessage;
import com.askflow.framework.convention.ChatRequest;
import com.askflow.infra.chat.LLMService;
import com.askflow.infra.embedding.EmbeddingService;
import com.askflow.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * LLM 判分服务（Faithfulness + AnswerCorrectness）
 * <p>
 * 只做两个指标：一个衡量「答案有没有编」，一个衡量「答案对不对」。
 * 参考答案与命中上下文都来自单条评测结果，不需要额外请求。
 * <p>
 * 已知局限（写在返回的 meta 里，避免被当成绝对分数）：
 * 判分模型与被评模型可能同源，会系统性高估；与 ragas 原始实现口径不同，分数不可跨系统比较
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalJudgeService {

    private static final String SPLIT_PROMPT_PATH = "prompt/eval/statement-split.st";
    private static final String FAITHFULNESS_PROMPT_PATH = "prompt/eval/faithfulness.st";
    private static final String CORRECTNESS_PROMPT_PATH = "prompt/eval/answer-correctness.st";

    private static final String JUDGE_SYSTEM_PROMPT = "你是严谨的评测助手，只输出合法 JSON，不输出任何多余文字。";
    private static final int MAX_STATEMENTS = 20;

    private final LLMService llmService;
    private final EmbeddingService embeddingService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final EvalJudgeProperties properties;

    /**
     * 对一条评测结果判分，返回可直接落库的 JSON
     *
     * @param judgeAllowed 是否还有判分预算（由运行引擎按 maxSamples 控制）
     */
    public String judge(EvalCaseResultDO result, String judgeModel, boolean judgeAllowed) {
        Map<String, Object> scores = new LinkedHashMap<>();
        String skipReason = resolveSkipReason(result, judgeAllowed);
        if (skipReason != null) {
            scores.put("skipped", skipReason);
            return JSONUtil.toJsonStr(scores);
        }

        List<String> contexts = EvalJson.toList(result.getRetrievedContexts());
        String model = resolveJudgeModel(judgeModel);
        List<String> answerStatements = splitStatements(result.getResponse(), model);
        if (answerStatements.isEmpty()) {
            scores.put("skipped", EvalConstants.SKIP_REASON_EMPTY_ANSWER);
            return JSONUtil.toJsonStr(scores);
        }

        scores.put("faithfulness", faithfulness(answerStatements, contexts, model));

        List<String> referenceStatements = splitStatements(result.getGroundTruth(), model);
        scores.put("answer_correctness", answerCorrectness(
                answerStatements, referenceStatements, result.getResponse(), result.getGroundTruth(), model));
        scores.put("judge_model", model);
        return JSONUtil.toJsonStr(scores);
    }

    // ==================== 两个指标 ====================

    /**
     * 逐条陈述判断能否由上下文推出，取支持比例
     */
    private Double faithfulness(List<String> statements, List<String> contexts, String model) {
        String context = String.join("\n---\n", contexts);
        String prompt = promptTemplateLoader.render(FAITHFULNESS_PROMPT_PATH, Map.of(
                "context", context,
                "count", String.valueOf(statements.size()),
                "statements", numbered(statements)
        ));
        JSONObject json = callJson(prompt, model);
        List<Integer> verdicts = toIntegerList(json.getJSONArray("verdicts"));
        if (verdicts.size() != statements.size()) {
            log.warn("[判分] faithfulness 返回数量不一致，expected={}, actual={}", statements.size(), verdicts.size());
            return null;
        }
        return verdicts.stream()
                .mapToInt(v -> v != null && v == 1 ? 1 : 0)
                .average()
                .orElse(0D);
    }

    /**
     * 事实点 F1 与语义相似度加权
     * <p>
     * 0.75 * F1 + 0.25 * cosine：以事实点为主，余弦只用来吸收同义表述带来的差异
     */
    private Double answerCorrectness(List<String> answerStatements, List<String> referenceStatements,
                                     String answer, String reference, String model) {
        if (referenceStatements.isEmpty()) {
            return null;
        }
        String prompt = promptTemplateLoader.render(CORRECTNESS_PROMPT_PATH, Map.of(
                "reference", numbered(referenceStatements),
                "answer", numbered(answerStatements)
        ));
        JSONObject json = callJson(prompt, model);
        int tp = json.getInt("tp", 0);
        int fp = json.getInt("fp", 0);
        int fn = json.getInt("fn", 0);

        double precision = (tp + fp) == 0 ? 0D : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0D : (double) tp / (tp + fn);
        double f1 = (precision + recall) == 0 ? 0D : 2 * precision * recall / (precision + recall);
        return 0.75 * f1 + 0.25 * cosine(answer, reference);
    }

    // ==================== 拆句 ====================

    /**
     * 把一段文本拆成可独立判断真假的陈述句；失败返回空列表（该指标记 null）
     */
    private List<String> splitStatements(String text, String model) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        try {
            String prompt = promptTemplateLoader.render(SPLIT_PROMPT_PATH, Map.of("text", text));
            JSONObject json = callJson(prompt, model);
            JSONArray array = json.getJSONArray("statements");
            if (array == null) {
                return List.of();
            }
            return array.toList(String.class).stream()
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .limit(MAX_STATEMENTS)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[判分] 陈述句拆分失败：{}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 模型调用与解析 ====================

    /**
     * 调判分模型并把返回解析成 JSON 对象
     * <p>
     * 解析失败会带一句「只输出合法 JSON」的提示重试一次，仍失败就抛异常，
     * 由上层把该样本该指标记为 null，不中断整批评测
     */
    private JSONObject callJson(String prompt, String model) {
        int maxRetries = Math.max(0, properties.getMaxRetries());
        Exception last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            String current = attempt == 0
                    ? prompt
                    : prompt + "\n\n上一次输出无法解析，请只输出一个合法 JSON 对象，不要任何其他文字。";
            try {
                String raw = llmService.chat(buildRequest(current), model);
                return JSONUtil.parseObj(sanitize(raw));
            } catch (Exception e) {
                last = e;
                log.warn("[判分] 第 {} 次调用失败：{}", attempt + 1, e.getMessage());
            }
        }
        throw new IllegalStateException("判分调用失败", last);
    }

    private ChatRequest buildRequest(String userPrompt) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.system(JUDGE_SYSTEM_PROMPT), ChatMessage.user(userPrompt)))
                .temperature(0D)
                .thinking(false)
                .maxTokens(2048)
                .build();
    }

    /**
     * 去掉 ```json 围栏与前后多余文字，只保留最外层 JSON 对象
     */
    private String sanitize(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw new IllegalStateException("判分返回为空");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text.trim();
    }

    // ==================== 小工具 ====================

    private String resolveSkipReason(EvalCaseResultDO result, boolean judgeAllowed) {
        if (!properties.isEnabled()) {
            return EvalConstants.SKIP_REASON_DISABLED;
        }
        if (!judgeAllowed) {
            return EvalConstants.SKIP_REASON_BUDGET;
        }
        if (!EvalConstants.FINAL_SUCCESS.equals(result.getFinalStatus())) {
            return EvalConstants.SKIP_REASON_REFUSED;
        }
        if (StrUtil.isBlank(result.getResponse())) {
            return EvalConstants.SKIP_REASON_EMPTY_ANSWER;
        }
        if (CollUtil.isEmpty(EvalJson.toList(result.getRetrievedContexts()))) {
            return EvalConstants.SKIP_REASON_EMPTY_CONTEXT;
        }
        if (StrUtil.isBlank(result.getGroundTruth())) {
            return EvalConstants.SKIP_REASON_EMPTY_REFERENCE;
        }
        return null;
    }

    private String resolveJudgeModel(String runJudgeModel) {
        if (StrUtil.isNotBlank(properties.getModel())) {
            return properties.getModel();
        }
        return StrUtil.nullToEmpty(runJudgeModel);
    }

    private String numbered(List<String> statements) {
        return IntStream.range(0, statements.size())
                .mapToObj(i -> (i + 1) + ". " + statements.get(i))
                .collect(Collectors.joining("\n"));
    }

    private List<Integer> toIntegerList(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        try {
            return array.toList(Integer.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 余弦相似度；embedding 不可用时记 0，不让整条判分失败
     */
    private double cosine(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return 0D;
        }
        try {
            List<Float> a = embeddingService.embed(left);
            List<Float> b = embeddingService.embed(right);
            if (CollUtil.isEmpty(a) || CollUtil.isEmpty(b) || a.size() != b.size()) {
                return 0D;
            }
            double dot = 0D;
            double normA = 0D;
            double normB = 0D;
            for (int i = 0; i < a.size(); i++) {
                dot += a.get(i) * b.get(i);
                normA += a.get(i) * a.get(i);
                normB += b.get(i) * b.get(i);
            }
            if (normA == 0D || normB == 0D) {
                return 0D;
            }
            return dot / (Math.sqrt(normA) * Math.sqrt(normB));
        } catch (Exception e) {
            log.warn("[判分] embedding 计算失败：{}", e.getMessage());
            return 0D;
        }
    }
}
