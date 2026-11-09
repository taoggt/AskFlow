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

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.askflow.admin.dao.entity.EvalCaseResultDO;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 评测指标计算
 * <p>
 * 指标是纯函数：输入一批已落库的样本结果，输出指标清单。因此放在一个类里按族拆私有方法，
 * 不需要为每个指标建一个策略类——分母口径只有一套，集中在一起反而不容易写错
 * <p>
 * 每个指标都带 sampleCount：同一份数据在不同筛选条件下分母不同，只报分数不报分母会误导
 */
@Component
public class EvalMetricCalculator {

    /**
     * 检索指标取的 K 值
     */
    private static final int[] KS = {1, 3, 5, 10};

    /**
     * MRR 只看前 10 条
     */
    private static final int MRR_K = 10;

    /**
     * 汇总一批样本结果，返回可直接写入 t_eval_run.metrics_json 的 JSON
     */
    public String calculate(List<EvalCaseResultDO> results) {
        List<EvalRow> rows = results.stream().map(EvalRow::of).toList();
        List<MetricValue> metrics = new ArrayList<>();

        appendRetrieval(metrics, rows);
        appendIntent(metrics, rows);
        appendBehavior(metrics, rows);
        appendLatency(metrics, rows);
        JudgeSummary judge = appendJudge(metrics, rows);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metrics", metrics);
        payload.put("judge", judge);
        payload.put("sampleCount", rows.size());
        return JSONUtil.toJsonStr(payload);
    }

    // ==================== 检索类 ====================

    /**
     * Hit@K / Recall@K / MRR@10
     * <p>
     * 分母只算「期望检索、且标注了 must 文档」的样本：SYSTEM 路由或没标注的样本
     * 放进来会把指标稀释成一个没有意义的数字
     */
    private void appendRetrieval(List<MetricValue> metrics, List<EvalRow> rows) {
        Predicate<EvalRow> eligible = row -> EvalConstants.ROUTE_KB.equals(row.getExpectedRoute())
                && Boolean.TRUE.equals(row.getRequiresRag())
                && !row.getMust().isEmpty();
        for (int k : KS) {
            metrics.add(mean("hit@" + k, rows, row -> hitAtK(row, k), eligible));
        }
        for (int k : KS) {
            metrics.add(mean("recall@" + k, rows, row -> recallAtK(row, k), eligible));
        }
        metrics.add(mean("mrr@10", rows, row -> mrrAtK(row, MRR_K), eligible));
    }

    private double hitAtK(EvalRow row, int k) {
        return topK(row, k).stream().anyMatch(row.getMust()::contains) ? 1D : 0D;
    }

    private double recallAtK(EvalRow row, int k) {
        if (row.getMust().isEmpty()) {
            return 0D;
        }
        long hit = topK(row, k).stream().filter(row.getMust()::contains).count();
        return (double) hit / row.getMust().size();
    }

    /**
     * 第一个命中 must 的排位的倒数；前 K 条都没命中记 0
     */
    private double mrrAtK(EvalRow row, int k) {
        List<String> top = topK(row, k);
        for (int i = 0; i < top.size(); i++) {
            if (row.getMust().contains(top.get(i))) {
                return 1D / (i + 1);
            }
        }
        return 0D;
    }

    /**
     * 取前 K 条并按文档去重，保持检索顺序
     */
    private List<String> topK(EvalRow row, int k) {
        List<String> retrieved = row.getRetrieved();
        int limit = Math.min(k, retrieved.size());
        return new ArrayList<>(new LinkedHashSet<>(retrieved.subList(0, limit)));
    }

    // ==================== 意图类 ====================

    private void appendIntent(List<MetricValue> metrics, List<EvalRow> rows) {
        metrics.add(mean("intent_top1", rows,
                row -> row.getIntentL2().equals(row.getIntentPred()) ? 1D : 0D,
                row -> StrUtil.isNotBlank(row.getIntentL2())));
    }

    // ==================== 行为类 ====================

    /**
     * 误拒率：期望检索却一个文档都没召回
     * <p>
     * 兜底率：回答里出现固定兜底话术。两者分母都是 KB 路由样本，一个看检索侧、一个看答案侧
     */
    private void appendBehavior(List<MetricValue> metrics, List<EvalRow> rows) {
        Predicate<EvalRow> kbRoute = row -> EvalConstants.ROUTE_KB.equals(row.getExpectedRoute());
        metrics.add(mean("refusal_when_required", rows,
                row -> row.getRetrieved().isEmpty() ? 1D : 0D,
                kbRoute));
        metrics.add(mean("fallback_when_required", rows,
                row -> row.getResponse().contains(EvalConstants.NO_DOC_MARKER) ? 1D : 0D,
                kbRoute));
    }

    // ==================== 延迟类 ====================

    /**
     * 首 token 与端到端延迟
     * <p>
     * 分位数用 nearest-rank：排序后取 floor(n*q)，不做线性插值。
     * n 很小时 p95 会退化成最大值，所以样本量也一起返回
     */
    private void appendLatency(List<MetricValue> metrics, List<EvalRow> rows) {
        List<Long> ttfts = rows.stream()
                .map(row -> row.getFirstTokenMs() != null && row.getFirstTokenMs() > 0
                        ? row.getFirstTokenMs() : row.getLatencyMs())
                .filter(value -> value != null && value > 0)
                .toList();
        List<Long> latencies = rows.stream()
                .map(EvalRow::getLatencyMs)
                .filter(value -> value != null && value > 0)
                .toList();

        metrics.add(new MetricValue("ttft_p50_ms", percentile(ttfts, 0.50), ttfts.size()));
        metrics.add(new MetricValue("ttft_p95_ms", percentile(ttfts, 0.95), ttfts.size()));
        metrics.add(new MetricValue("total_mean_ms", average(latencies), latencies.size()));
    }

    // ==================== 判分类 ====================

    private JudgeSummary appendJudge(List<MetricValue> metrics, List<EvalRow> rows) {
        metrics.add(mean("faithfulness", rows, EvalRow::getFaithfulness,
                row -> row.getFaithfulness() != null));
        metrics.add(mean("answer_correctness", rows, EvalRow::getAnswerCorrectness,
                row -> row.getAnswerCorrectness() != null));

        long scored = rows.stream().filter(row -> row.getFaithfulness() != null).count();
        String status;
        if (scored > 0) {
            status = "enabled";
        } else if (rows.stream().allMatch(row -> EvalConstants.SKIP_REASON_DISABLED.equals(row.getJudgeSkipped()))) {
            status = "disabled";
        } else {
            status = "skipped";
        }
        return new JudgeSummary(status, (int) scored, rows.size());
    }

    // ==================== 聚合工具 ====================

    /**
     * 对满足资格、且取值非 null 的样本求算术平均
     */
    private MetricValue mean(String name, List<EvalRow> rows, Function<EvalRow, Double> valueFn,
                             Predicate<EvalRow> eligible) {
        double sum = 0D;
        int count = 0;
        for (EvalRow row : rows) {
            if (!eligible.test(row)) {
                continue;
            }
            Double value = valueFn.apply(row);
            if (value == null || value.isNaN()) {
                continue;
            }
            sum += value;
            count++;
        }
        return new MetricValue(name, count == 0 ? null : sum / count, count);
    }

    private Double average(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        long sum = 0L;
        for (Long value : values) {
            sum += value;
        }
        return (double) sum / values.size();
    }

    private Double percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.floor(sorted.size() * quantile);
        index = Math.min(sorted.size() - 1, Math.max(0, index));
        return sorted.get(index).doubleValue();
    }

    // ==================== 内部模型 ====================

    /**
     * 指标值；value 为 null 表示没有有效样本
     */
    @Data
    @AllArgsConstructor
    public static class MetricValue {

        private String name;

        private Double value;

        private int sampleCount;
    }

    /**
     * 判分覆盖情况，避免把「只判了 5 条」的分数当成整批结论
     */
    @Data
    @AllArgsConstructor
    public static class JudgeSummary {

        private String status;

        private int scoredCount;

        private int totalCount;
    }

    /**
     * 计算用的扁平视图：把 JSONB 字段解析成集合，避免计算过程中反复解析
     */
    @Data
    private static class EvalRow {

        private String queryId;

        private String intentL1;

        private String intentL2;

        private String intentPred;

        private String expectedRoute;

        private Boolean requiresRag;

        private List<String> must;

        private List<String> retrieved;

        private String response;

        private Long latencyMs;

        private Long firstTokenMs;

        private Double faithfulness;

        private Double answerCorrectness;

        private String judgeSkipped;

        static EvalRow of(EvalCaseResultDO result) {
            EvalRow row = new EvalRow();
            row.setQueryId(result.getQueryId());
            row.setIntentL1(result.getIntentL1());
            row.setIntentL2(StrUtil.nullToEmpty(result.getIntentL2()));
            row.setIntentPred(StrUtil.nullToEmpty(result.getIntentPred()));
            row.setExpectedRoute(StrUtil.nullToEmpty(result.getExpectedRoute()));
            row.setRequiresRag(result.getRequiresRag() != null && result.getRequiresRag() == 1);
            row.setMust(EvalJson.toList(result.getExpectedDocIds()));
            row.setRetrieved(EvalJson.toList(result.getRetrievedDocIds()));
            row.setResponse(StrUtil.nullToEmpty(result.getResponse()));
            row.setLatencyMs(result.getLatencyMs());
            row.setFirstTokenMs(result.getFirstTokenMs());
            readJudgeScores(result.getScoresJson(), row);
            return row;
        }

        private static void readJudgeScores(String scoresJson, EvalRow row) {
            if (StrUtil.isBlank(scoresJson)) {
                return;
            }
            try {
                JSONObject json = JSONUtil.parseObj(scoresJson);
                row.setFaithfulness(readDouble(json, "faithfulness"));
                row.setAnswerCorrectness(readDouble(json, "answer_correctness"));
                row.setJudgeSkipped(json.containsKey("skipped") && !json.isNull("skipped")
                        ? json.getStr("skipped") : null);
            } catch (Exception ignored) {
                // 判分结果损坏时按「未判分」处理，不影响确定性指标
            }
        }

        private static Double readDouble(JSONObject json, String key) {
            if (!json.containsKey(key) || json.isNull(key)) {
                return null;
            }
            try {
                return json.getDouble(key);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
