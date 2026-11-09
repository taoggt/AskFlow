import type { EvalMetricValue } from "@/services/evalService";

export const RUN_STATUS_LABEL: Record<string, string> = {
  pending: "排队中",
  running: "运行中",
  success: "已完成",
  failed: "失败",
  cancelled: "已取消"
};

export const RUN_STATUS_CLASS: Record<string, string> = {
  pending: "bg-slate-100 text-slate-600",
  running: "bg-amber-100 text-amber-700",
  success: "bg-emerald-100 text-emerald-700",
  failed: "bg-rose-100 text-rose-700",
  cancelled: "bg-slate-200 text-slate-600"
};

export const BRANCH_LABEL: Record<string, string> = {
  rag: "检索+生成",
  system_only: "纯系统意图",
  guidance: "歧义反问",
  empty_retrieval: "无命中兜底"
};

/**
 * 指标分组，顺序即展示顺序
 * 延迟类不是百分比，展示时要区别对待
 */
export const METRIC_GROUPS: { title: string; hint: string; metrics: EvalMetricValue["name"][] }[] = [
  {
    title: "检索效果",
    hint: "分母只算期望检索且标注了 must 文档的样本",
    metrics: [
      "hit@1",
      "hit@3",
      "hit@5",
      "hit@10",
      "recall@1",
      "recall@3",
      "recall@5",
      "recall@10",
      "mrr@10"
    ]
  },
  {
    title: "意图识别",
    hint: "top-1 是否命中标注的二级意图",
    metrics: ["intent_top1"]
  },
  {
    title: "兜底行为",
    hint: "分母是期望检索的样本：误拒看检索侧，兜底看答案侧",
    metrics: ["refusal_when_required", "fallback_when_required"]
  },
  {
    title: "延迟",
    hint: "nearest-rank 分位数，样本少于 20 条时 p95 等于最大值",
    metrics: ["ttft_p50_ms", "ttft_p95_ms", "total_mean_ms"]
  },
  {
    title: "答案质量（LLM 判分）",
    hint: "同源判分会系统性偏高，只用于同一系统纵向对比",
    metrics: ["faithfulness", "answer_correctness"]
  }
];

const LATENCY_METRICS = new Set(["ttft_p50_ms", "ttft_p95_ms", "total_mean_ms"]);

export function formatRatio(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}

export function formatMetricValue(name: string, value: number | null) {
  if (value === null || value === undefined) {
    return "-";
  }
  if (LATENCY_METRICS.has(name)) {
    return `${Math.round(value)} ms`;
  }
  return formatRatio(value);
}

export function formatTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

export function formatElapsed(start?: string, end?: string) {
  if (!start) return "-";
  const from = new Date(start).getTime();
  const to = end ? new Date(end).getTime() : Date.now();
  if (Number.isNaN(from) || Number.isNaN(to) || to < from) return "-";
  const seconds = Math.round((to - from) / 1000);
  if (seconds < 60) return `${seconds}s`;
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
