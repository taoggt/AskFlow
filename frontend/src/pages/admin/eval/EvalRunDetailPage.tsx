import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, RefreshCw, Square } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  cancelEvalRun,
  getEvalRun,
  getEvalRunResultDetail,
  getEvalRunResults,
  type EvalCaseResult,
  type EvalPage as EvalPageResult,
  type EvalRun
} from "@/services/evalService";
import { getErrorMessage } from "@/utils/error";
import {
  BRANCH_LABEL,
  formatElapsed,
  formatMetricValue,
  formatTime,
  METRIC_GROUPS,
  RUN_STATUS_CLASS,
  RUN_STATUS_LABEL
} from "@/pages/admin/eval/evalUtils";

const PAGE_SIZE = 20;

export function EvalRunDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();

  const [run, setRun] = useState<EvalRun | null>(null);
  const [results, setResults] = useState<EvalPageResult<EvalCaseResult> | null>(null);
  const [pageNo, setPageNo] = useState(1);
  const [failedOnly, setFailedOnly] = useState(false);
  const [detail, setDetail] = useState<EvalCaseResult | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const loadRun = useCallback(async () => {
    if (!id) return;
    try {
      setRun(await getEvalRun(id));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载运行详情失败"));
    }
  }, [id]);

  const loadResults = useCallback(async () => {
    if (!id) return;
    try {
      setResults(await getEvalRunResults(id, { pageNo, pageSize: PAGE_SIZE, failedOnly }));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载样本结果失败"));
    }
  }, [id, pageNo, failedOnly]);

  useEffect(() => {
    void loadRun();
  }, [loadRun]);

  useEffect(() => {
    void loadResults();
  }, [loadResults]);

  // 运行中才轮询，进入终态自动停止
  useEffect(() => {
    if (!run) return;
    const active = run.status === "pending" || run.status === "running";
    if (!active) return;
    const timer = window.setTimeout(() => {
      void loadRun();
      void loadResults();
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [run, loadRun, loadResults]);

  const handleCancel = async () => {
    if (!run) return;
    if (!window.confirm("确认取消该次评测？已完成的样本结果会保留")) return;
    try {
      await cancelEvalRun(run.id);
      toast.success("已请求取消");
      await loadRun();
    } catch (error) {
      toast.error(getErrorMessage(error, "取消失败"));
    }
  };

  const openDetail = async (item: EvalCaseResult) => {
    try {
      const full = await getEvalRunResultDetail(id, item.queryId);
      setDetail(full);
      setDetailOpen(true);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载样本详情失败"));
    }
  };

  const records = results?.records || [];
  const active = run?.status === "pending" || run?.status === "running";
  const percent =
    run && run.totalCount > 0 ? Math.min(100, Math.round((run.doneCount / run.totalCount) * 100)) : 0;
  const metricMap = new Map((run?.metrics?.metrics || []).map((item) => [item.name, item]));

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Button variant="ghost" size="sm" onClick={() => navigate("/admin/eval")}>
            <ArrowLeft className="h-4 w-4" />
            返回
          </Button>
          <div>
            <h2 className="text-lg font-semibold text-slate-900">{run?.datasetName || "评测详情"}</h2>
            <p className="text-xs text-slate-500">
              TopK {run?.topK ?? "-"} · 判分 {run?.judgeEnabled === 1 ? "开" : "关"} · 提交于{" "}
              {formatTime(run?.createTime)}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => void loadRun()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {active ? (
            <Button variant="outline" size="sm" className="text-rose-600" onClick={handleCancel}>
              <Square className="h-4 w-4" />
              取消
            </Button>
          ) : null}
        </div>
      </div>

      <Card>
        <CardContent className="space-y-3 pt-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <span
                className={`rounded-full px-2.5 py-1 text-xs ${
                  RUN_STATUS_CLASS[run?.status || ""] || "bg-slate-100 text-slate-600"
                }`}
              >
                {RUN_STATUS_LABEL[run?.status || ""] || run?.status || "-"}
              </span>
              <span className="text-sm text-slate-600">
                {run?.doneCount ?? 0} / {run?.totalCount ?? 0} 条
                {run?.failedCount ? `（失败 ${run.failedCount}）` : ""}
              </span>
              <span className="text-xs text-slate-400">
                耗时 {formatElapsed(run?.startedAt, run?.completedAt)}
              </span>
            </div>
            <span className="text-sm font-medium text-slate-700">{percent}%</span>
          </div>
          <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100">
            <div
              className={`h-full rounded-full transition-all ${
                run?.status === "failed" ? "bg-rose-400" : "bg-indigo-500"
              }`}
              style={{ width: `${percent}%` }}
            />
          </div>
          {run?.errorMessage ? (
            <p className="rounded-xl bg-rose-50 px-3 py-2 text-xs text-rose-600">{run.errorMessage}</p>
          ) : null}
          {run?.metrics?.judge ? (
            <p className="text-xs text-slate-500">
              判分覆盖 {run.metrics.judge.scoredCount} / {run.metrics.judge.totalCount} 条（状态：
              {run.metrics.judge.status}）
            </p>
          ) : null}
        </CardContent>
      </Card>

      {run?.metrics ? (
        <div className="grid gap-4 lg:grid-cols-2">
          {METRIC_GROUPS.map((group) => {
            const rows = group.metrics
              .map((name) => metricMap.get(name))
              .filter((item): item is NonNullable<typeof item> => Boolean(item));
            if (rows.length === 0) return null;
            return (
              <Card key={group.title}>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm">{group.title}</CardTitle>
                  <CardDescription className="text-xs">{group.hint}</CardDescription>
                </CardHeader>
                <CardContent className="pt-0">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-32">指标</TableHead>
                        <TableHead className="w-28 text-right">值</TableHead>
                        <TableHead className="w-24 text-right">有效样本</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {rows.map((metric) => (
                        <TableRow key={metric.name}>
                          <TableCell className="font-mono text-xs">{metric.name}</TableCell>
                          <TableCell className="text-right font-medium">
                            {formatMetricValue(metric.name, metric.value)}
                          </TableCell>
                          <TableCell className="text-right text-xs text-slate-400">
                            {metric.sampleCount}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </CardContent>
              </Card>
            );
          })}
        </div>
      ) : null}

      <Card>
        <CardHeader className="flex flex-row items-center justify-between pb-3">
          <div>
            <CardTitle className="text-sm">样本结果</CardTitle>
            <CardDescription className="text-xs">点行查看答案、召回文档与判分结果</CardDescription>
          </div>
          <Button
            variant={failedOnly ? "default" : "outline"}
            size="sm"
            onClick={() => {
              setFailedOnly((prev) => !prev);
              setPageNo(1);
            }}
          >
            只看失败
          </Button>
        </CardHeader>
        <CardContent className="pt-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-24">样本 ID</TableHead>
                <TableHead>问题</TableHead>
                <TableHead className="w-36">意图（预测/标注）</TableHead>
                <TableHead className="w-20 text-right">召回</TableHead>
                <TableHead className="w-20 text-right">TTFT</TableHead>
                <TableHead className="w-24">分支</TableHead>
                <TableHead className="w-20">状态</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {records.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="py-10 text-center text-sm text-slate-400">
                    {active ? "评测进行中..." : "暂无结果"}
                  </TableCell>
                </TableRow>
              ) : (
                records.map((item) => (
                  <TableRow
                    key={item.id}
                    className="cursor-pointer"
                    onClick={() => void openDetail(item)}
                  >
                    <TableCell className="font-mono text-xs">{item.queryId}</TableCell>
                    <TableCell className="max-w-[280px] truncate" title={item.queryText}>
                      {item.queryText}
                    </TableCell>
                    <TableCell className="text-xs text-slate-500">
                      <span
                        className={
                          item.intentPred && item.intentPred === item.intentL2
                            ? "text-emerald-600"
                            : "text-rose-600"
                        }
                      >
                        {item.intentPred || "-"}
                      </span>
                      <span className="text-slate-300"> / </span>
                      {item.intentL2 || "-"}
                    </TableCell>
                    <TableCell className="text-right text-xs text-slate-500">
                      {item.retrievedDocIds?.length || 0}
                    </TableCell>
                    <TableCell className="text-right text-xs text-slate-500">
                      {item.firstTokenMs && item.firstTokenMs > 0 ? `${item.firstTokenMs}ms` : "-"}
                    </TableCell>
                    <TableCell className="text-xs text-slate-500">
                      {BRANCH_LABEL[item.answerBranch] || item.answerBranch}
                    </TableCell>
                    <TableCell>
                      <span
                        className={`text-xs ${
                          item.finalStatus === "success"
                            ? "text-emerald-600"
                            : item.finalStatus === "refused"
                              ? "text-amber-600"
                              : "text-rose-600"
                        }`}
                      >
                        {item.finalStatus}
                      </span>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
          <div className="flex items-center justify-between pt-3 text-xs text-slate-500">
            <span>共 {results?.total ?? 0} 条</span>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={pageNo <= 1}
                onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}
              >
                上一页
              </Button>
              <span>
                {results?.current || pageNo} / {results?.pages || 1}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={pageNo >= (results?.pages || 1)}
                onClick={() => setPageNo((prev) => prev + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>

      <Dialog open={detailOpen} onOpenChange={setDetailOpen}>
        <DialogContent className="max-h-[85vh] overflow-auto sm:max-w-[760px]">
          <DialogHeader>
            <DialogTitle>样本 {detail?.queryId}</DialogTitle>
            <DialogDescription>{detail?.queryText}</DialogDescription>
          </DialogHeader>
          {detail ? (
            <div className="space-y-4 text-sm">
              <div className="grid grid-cols-2 gap-3 text-xs text-slate-500 sm:grid-cols-4">
                <div>
                  <div className="text-slate-400">命中文档</div>
                  <div className="font-medium text-slate-700">{detail.retrievedDocIds?.length || 0} 个</div>
                </div>
                <div>
                  <div className="text-slate-400">总耗时</div>
                  <div className="font-medium text-slate-700">{detail.latencyMs ?? "-"} ms</div>
                </div>
                <div>
                  <div className="text-slate-400">首 token</div>
                  <div className="font-medium text-slate-700">{detail.firstTokenMs ?? "-"} ms</div>
                </div>
                <div>
                  <div className="text-slate-400">分支</div>
                  <div className="font-medium text-slate-700">
                    {BRANCH_LABEL[detail.answerBranch] || detail.answerBranch}
                  </div>
                </div>
              </div>

              <section className="space-y-1">
                <h4 className="text-xs font-semibold text-slate-500">改写后的问题</h4>
                <p className="rounded-xl bg-slate-50 px-3 py-2 text-xs text-slate-700">
                  {detail.rewrittenQuestion || "-"}
                </p>
              </section>

              <section className="space-y-1">
                <h4 className="text-xs font-semibold text-slate-500">模型答案</h4>
                <pre className="max-h-56 overflow-auto whitespace-pre-wrap rounded-xl bg-slate-50 px-3 py-2 text-xs text-slate-700">
                  {detail.response || "-"}
                </pre>
              </section>

              <section className="space-y-1">
                <h4 className="text-xs font-semibold text-slate-500">
                  期望文档 vs 召回文档
                </h4>
                <div className="grid gap-2 sm:grid-cols-2">
                  <div className="rounded-xl bg-slate-50 px-3 py-2 text-xs">
                    <div className="mb-1 text-slate-400">期望（must）</div>
                    {(detail.expectedDocIds || []).length ? (
                      detail.expectedDocIds.map((code) => (
                        <div key={code} className="font-mono text-slate-600">
                          {code}
                        </div>
                      ))
                    ) : (
                      <span className="text-slate-400">-</span>
                    )}
                  </div>
                  <div className="rounded-xl bg-slate-50 px-3 py-2 text-xs">
                    <div className="mb-1 text-slate-400">实际召回</div>
                    {(detail.retrievedDocIds || []).length ? (
                      detail.retrievedDocIds.map((code, index) => (
                        <div key={`${code}-${index}`} className="font-mono text-slate-600">
                          {index + 1}. {code || "(未知文档)"}
                        </div>
                      ))
                    ) : (
                      <span className="text-slate-400">-</span>
                    )}
                  </div>
                </div>
              </section>

              {(detail.retrievedContexts || []).length ? (
                <section className="space-y-1">
                  <h4 className="text-xs font-semibold text-slate-500">命中的 chunk</h4>
                  <div className="max-h-56 space-y-2 overflow-auto">
                    {detail.retrievedContexts.map((context, index) => (
                      <pre
                        key={index}
                        className="whitespace-pre-wrap rounded-xl bg-slate-50 px-3 py-2 text-xs text-slate-600"
                      >
                        [{index + 1}] {detail.retrievedContextDocIds?.[index] || "未知文档"}
                        {"\n"}
                        {context}
                      </pre>
                    ))}
                  </div>
                </section>
              ) : null}

              {detail.scores ? (
                <section className="space-y-1">
                  <h4 className="text-xs font-semibold text-slate-500">判分</h4>
                  <pre className="rounded-xl bg-slate-50 px-3 py-2 text-xs text-slate-600">
                    {JSON.stringify(detail.scores, null, 2)}
                  </pre>
                </section>
              ) : null}

              {detail.errorMessage ? (
                <p className="rounded-xl bg-rose-50 px-3 py-2 text-xs text-rose-600">{detail.errorMessage}</p>
              ) : null}
            </div>
          ) : (
            <p className="text-sm text-slate-400">加载中...</p>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
