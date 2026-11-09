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

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.askflow.admin.dao.entity.EvalCaseResultDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标计算的单元测试
 * <p>
 * 指标出错最典型的表现不是报错，而是「分母不对但看起来正常」，所以这里固定一组小样本，
 * 人工把每个指标的期望值算一遍再断言
 */
class EvalMetricCalculatorTests {

    private final EvalMetricCalculator calculator = new EvalMetricCalculator();

    @Test
    @DisplayName("检索、意图、行为、延迟四类指标的分母与取值")
    void shouldCalculateDeterministicMetrics() {
        Map<String, JSONObject> metrics = calculate(List.of(
                // A：must 有两条，top1 命中 D1
                result("Q1", "KB", 1, List.of("D1", "D2"), List.of("D1", "D3"), "正常回答", 1000L, 100L)
                        .intentL2("X").intentPred("X")
                        .build(),
                // B：must 一条，第三位才命中
                result("Q2", "KB", 1, List.of("D3"), List.of("D1", "D2", "D3"), "正常回答", 2000L, 200L)
                        .intentL2("Y").intentPred("Z")
                        .build(),
                // C：SYSTEM 路由，不参与检索与行为指标
                result("Q3", "SYSTEM", 0, List.of(), List.of(), "系统回答", 3000L, 300L)
                        .intentL2("").intentPred("")
                        .build(),
                // D：该检索却什么都没召回
                result("Q4", "KB", 1, List.of("D9"), List.of(), "未检索到与问题相关的文档内容。", 4000L, 400L)
                        .intentL2("Y").intentPred("Y")
                        .build()
        ));

        // 检索类分母 = 3（A/B/D），C 是 SYSTEM 路由被排除
        assertEquals(3, metrics.get("hit@1").getInt("sampleCount"));
        assertClose(1D / 3, metrics.get("hit@1").getDouble("value"));
        assertClose(2D / 3, metrics.get("hit@3").getDouble("value"));
        assertClose(2D / 3, metrics.get("hit@10").getDouble("value"));

        // recall@1 = (0.5 + 0 + 0) / 3，recall@3 = (0.5 + 1 + 0) / 3
        assertClose(0.5 / 3, metrics.get("recall@1").getDouble("value"));
        assertClose(1.5 / 3, metrics.get("recall@3").getDouble("value"));

        // MRR@10 = (1 + 1/3 + 0) / 3
        assertClose((1D + 1D / 3) / 3, metrics.get("mrr@10").getDouble("value"));

        // 意图：A 命中、B 未命中、C 无标注被排除、D 命中
        assertEquals(3, metrics.get("intent_top1").getInt("sampleCount"));
        assertClose(2D / 3, metrics.get("intent_top1").getDouble("value"));

        // 行为：分母是 KB 路由的 3 条
        assertEquals(3, metrics.get("refusal_when_required").getInt("sampleCount"));
        assertClose(1D / 3, metrics.get("refusal_when_required").getDouble("value"));
        assertClose(1D / 3, metrics.get("fallback_when_required").getDouble("value"));

        // 延迟：4 条全算，nearest-rank 取 floor(n*q)
        assertEquals(4, metrics.get("ttft_p50_ms").getInt("sampleCount"));
        assertClose(300D, metrics.get("ttft_p50_ms").getDouble("value"));
        assertClose(400D, metrics.get("ttft_p95_ms").getDouble("value"));
        assertClose(2500D, metrics.get("total_mean_ms").getDouble("value"));
    }

    @Test
    @DisplayName("首 token 缺失时回退到端到端耗时")
    void shouldFallbackToLatencyWhenTtftMissing() {
        Map<String, JSONObject> metrics = calculate(List.of(
                result("Q1", "KB", 1, List.of("D1"), List.of("D1"), "回答", 1200L, -1L).build()
        ));
        assertClose(1200D, metrics.get("ttft_p50_ms").getDouble("value"));
    }

    @Test
    @DisplayName("没有 must 文档的样本不稀释检索指标")
    void shouldIgnoreSamplesWithoutMustDocIds() {
        Map<String, JSONObject> metrics = calculate(List.of(
                result("Q1", "KB", 1, List.of(), List.of("D1"), "回答", 1000L, 100L).build()
        ));
        assertEquals(0, metrics.get("hit@5").getInt("sampleCount"));
        assertTrue(metrics.get("hit@5").isNull("value"));
    }

    @Test
    @DisplayName("判分只统计有分数的样本，且记录覆盖情况")
    void shouldAggregateJudgeScores() {
        String metricsJson = calculator.calculate(List.of(
                result("Q1", "KB", 1, List.of("D1"), List.of("D1"), "回答", 1000L, 100L)
                        .scoresJson("{\"faithfulness\":0.6,\"answer_correctness\":0.4}")
                        .build(),
                result("Q2", "KB", 1, List.of("D1"), List.of("D1"), "回答", 1000L, 100L)
                        .scoresJson("{\"faithfulness\":1.0,\"answer_correctness\":0.8}")
                        .build(),
                result("Q3", "KB", 1, List.of("D1"), List.of("D1"), "回答", 1000L, 100L)
                        .scoresJson("{\"skipped\":\"empty_reference\"}")
                        .build()
        ));
        JSONObject payload = JSONUtil.parseObj(metricsJson);
        Map<String, JSONObject> metrics = toMap(payload.getJSONArray("metrics"));
        assertClose(0.8, metrics.get("faithfulness").getDouble("value"));
        assertClose(0.6, metrics.get("answer_correctness").getDouble("value"));
        assertEquals(2, metrics.get("faithfulness").getInt("sampleCount"));

        JSONObject judge = payload.getJSONObject("judge");
        assertEquals("enabled", judge.getStr("status"));
        assertEquals(2, judge.getInt("scoredCount"));
        assertEquals(3, judge.getInt("totalCount"));
    }

    private Map<String, JSONObject> calculate(List<EvalCaseResultDO> results) {
        return toMap(JSONUtil.parseObj(calculator.calculate(results)).getJSONArray("metrics"));
    }

    private Map<String, JSONObject> toMap(JSONArray array) {
        return array.stream()
                .map(item -> (JSONObject) item)
                .collect(Collectors.toMap(item -> item.getStr("name"), item -> item));
    }

    private EvalCaseResultDO.EvalCaseResultDOBuilder result(String queryId, String route, int requiresRag,
                                                            List<String> expectedDocIds, List<String> retrievedDocIds,
                                                            String response, Long latencyMs, Long firstTokenMs) {
        return EvalCaseResultDO.builder()
                .runId("RUN")
                .queryId(queryId)
                .queryText("问题 " + queryId)
                .expectedRoute(route)
                .requiresRag(requiresRag)
                .expectedDocIds(JSONUtil.toJsonStr(expectedDocIds))
                .retrievedDocIds(JSONUtil.toJsonStr(retrievedDocIds))
                .retrievedContexts("[]")
                .response(response)
                .finalStatus(EvalConstants.FINAL_SUCCESS)
                .latencyMs(latencyMs)
                .firstTokenMs(firstTokenMs);
    }

    private void assertClose(double expected, Double actual) {
        assertNotNull(actual);
        assertEquals(expected, actual, 1e-6);
    }
}
