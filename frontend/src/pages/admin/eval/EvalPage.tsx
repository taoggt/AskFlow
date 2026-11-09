import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { FlaskConical, Pencil, Play, Plus, RefreshCw, Trash2, Upload } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import {
  createEvalDataset,
  createEvalSample,
  deleteEvalDataset,
  deleteEvalSample,
  getEvalDatasets,
  getEvalRuns,
  getEvalSamples,
  importEvalDataset,
  submitEvalRun,
  updateEvalSample,
  type EvalDataset,
  type EvalPage as EvalPageResult,
  type EvalRun,
  type EvalSample,
  type EvalSamplePayload
} from "@/services/evalService";
import { getErrorMessage } from "@/utils/error";
import {
  formatRatio,
  formatTime,
  RUN_STATUS_CLASS as STATUS_CLASS,
  RUN_STATUS_LABEL as STATUS_LABEL
} from "@/pages/admin/eval/evalUtils";

const PAGE_SIZE = 20;

const emptySampleForm = {
  queryId: "",
  queryText: "",
  intentL1: "",
  intentL2: "",
  requiresRag: true,
  expectedRoute: "KB",
  expectedDocIds: "",
  expectedDocIdsNice: "",
  groundTruth: ""
};

type SampleForm = typeof emptySampleForm;

const splitCodes = (value: string) =>
  value
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean);

export function EvalPage() {
  const navigate = useNavigate();
  const sampleRequestRef = useRef(0);
  const runRequestRef = useRef(0);

  const [datasets, setDatasets] = useState<EvalDataset[]>([]);
  const [selectedId, setSelectedId] = useState<string>("");
  const [tab, setTab] = useState<"samples" | "runs">("samples");
  const [loading, setLoading] = useState(false);

  const [samplePageNo, setSamplePageNo] = useState(1);
  const [samplePage, setSamplePage] = useState<EvalPageResult<EvalSample> | null>(null);
  const [runPageNo, setRunPageNo] = useState(1);
  const [runPage, setRunPage] = useState<EvalPageResult<EvalRun> | null>(null);

  const [datasetDialogOpen, setDatasetDialogOpen] = useState(false);
  const [datasetForm, setDatasetForm] = useState({ name: "", description: "" });
  const [importDialogOpen, setImportDialogOpen] = useState(false);
  const [importName, setImportName] = useState("");
  const [importMode, setImportMode] = useState<"append" | "replace">("append");
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);

  const [runDialogOpen, setRunDialogOpen] = useState(false);
  const [runTopK, setRunTopK] = useState("10");
  const [runJudge, setRunJudge] = useState("true");
  const [submitting, setSubmitting] = useState(false);

  const [sampleDialogOpen, setSampleDialogOpen] = useState(false);
  const [editingSampleId, setEditingSampleId] = useState<string>("");
  const [sampleForm, setSampleForm] = useState<SampleForm>(emptySampleForm);

  const selectedDataset = datasets.find((item) => item.id === selectedId);

  const loadDatasets = useCallback(async () => {
    try {
      const data = await getEvalDatasets();
      setDatasets(data);
      setSelectedId((prev) => (prev && data.some((item) => item.id === prev) ? prev : data[0]?.id || ""));
    } catch (error) {
      toast.error(getErrorMessage(error, "加载评测集失败"));
    }
  }, []);

  const loadSamples = useCallback(async (datasetId: string, pageNo: number) => {
    if (!datasetId) {
      setSamplePage(null);
      return;
    }
    const requestId = ++sampleRequestRef.current;
    setLoading(true);
    try {
      const data = await getEvalSamples(datasetId, { pageNo, pageSize: PAGE_SIZE });
      if (sampleRequestRef.current !== requestId) return;
      setSamplePage(data);
    } catch (error) {
      if (sampleRequestRef.current !== requestId) return;
      toast.error(getErrorMessage(error, "加载样本失败"));
    } finally {
      if (sampleRequestRef.current === requestId) {
        setLoading(false);
      }
    }
  }, []);

  const loadRuns = useCallback(async (datasetId: string, pageNo: number) => {
    if (!datasetId) {
      setRunPage(null);
      return;
    }
    const requestId = ++runRequestRef.current;
    try {
      const data = await getEvalRuns({ datasetId, pageNo, pageSize: PAGE_SIZE });
      if (runRequestRef.current !== requestId) return;
      setRunPage(data);
    } catch (error) {
      if (runRequestRef.current !== requestId) return;
      toast.error(getErrorMessage(error, "加载运行记录失败"));
    }
  }, []);

  useEffect(() => {
    void loadDatasets();
  }, [loadDatasets]);

  useEffect(() => {
    if (tab === "samples") {
      void loadSamples(selectedId, samplePageNo);
    } else {
      void loadRuns(selectedId, runPageNo);
    }
  }, [tab, selectedId, samplePageNo, runPageNo, loadSamples, loadRuns]);

  // 有运行中的任务时轮询，进入终态后自动停
  useEffect(() => {
    if (tab !== "runs" || !selectedId) return;
    const hasActive = (runPage?.records || []).some(
      (item) => item.status === "pending" || item.status === "running"
    );
    if (!hasActive) return;
    const timer = window.setTimeout(() => {
      void loadRuns(selectedId, runPageNo);
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [tab, selectedId, runPage, runPageNo, loadRuns]);

  const handleCreateDataset = async () => {
    if (!datasetForm.name.trim()) {
      toast.error("请输入评测集名称");
      return;
    }
    try {
      const created = await createEvalDataset({
        name: datasetForm.name.trim(),
        description: datasetForm.description.trim() || undefined
      });
      toast.success("评测集已创建");
      setDatasetDialogOpen(false);
      setDatasetForm({ name: "", description: "" });
      await loadDatasets();
      setSelectedId(created.id);
    } catch (error) {
      toast.error(getErrorMessage(error, "创建失败"));
    }
  };

  const handleDeleteDataset = async () => {
    if (!selectedDataset) return;
    if (!window.confirm(`确认删除评测集「${selectedDataset.name}」及其所有样本？`)) return;
    try {
      await deleteEvalDataset(selectedDataset.id);
      toast.success("已删除");
      setSelectedId("");
      await loadDatasets();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  const handleImport = async () => {
    if (!importFile) {
      toast.error("请选择 JSONL 文件");
      return;
    }
    try {
      setImporting(true);
      const result = await importEvalDataset(importFile, importName.trim() || undefined, importMode);
      const summary = `导入 ${result.imported} 条，更新 ${result.updated} 条，跳过 ${result.skipped} 条，失败 ${result.failed} 条`;
      toast.success(summary);
      if (result.unknownFields?.length) {
        toast.warning(`以下字段未识别：${result.unknownFields.join("、")}`);
      }
      if (result.warnings?.length) {
        toast.warning(result.warnings.slice(0, 3).join("；"));
      }
      setImportDialogOpen(false);
      setImportFile(null);
      setImportName("");
      await loadDatasets();
      setTab("samples");
      setSamplePageNo(1);
    } catch (error) {
      toast.error(getErrorMessage(error, "导入失败"));
    } finally {
      setImporting(false);
    }
  };

  const handleSubmitRun = async () => {
    if (!selectedId) return;
    try {
      setSubmitting(true);
      const run = await submitEvalRun({
        datasetId: selectedId,
        topK: Number(runTopK) || 10,
        judgeEnabled: runJudge === "true"
      });
      toast.success("评测已提交");
      setRunDialogOpen(false);
      setTab("runs");
      setRunPageNo(1);
      navigate(`/admin/eval/runs/${run.id}`);
    } catch (error) {
      toast.error(getErrorMessage(error, "提交失败"));
    } finally {
      setSubmitting(false);
    }
  };

  const openCreateSample = () => {
    setEditingSampleId("");
    setSampleForm(emptySampleForm);
    setSampleDialogOpen(true);
  };

  const openEditSample = (sample: EvalSample) => {
    setEditingSampleId(sample.id);
    setSampleForm({
      queryId: sample.queryId,
      queryText: sample.queryText,
      intentL1: sample.intentL1 || "",
      intentL2: sample.intentL2 || "",
      requiresRag: sample.requiresRag === 1,
      expectedRoute: sample.expectedRoute || "KB",
      expectedDocIds: (sample.expectedDocIds || []).join(", "),
      expectedDocIdsNice: (sample.expectedDocIdsNice || []).join(", "),
      groundTruth: sample.groundTruth || ""
    });
    setSampleDialogOpen(true);
  };

  const handleSaveSample = async () => {
    if (!selectedId) return;
    const payload: EvalSamplePayload = {
      queryId: sampleForm.queryId.trim(),
      queryText: sampleForm.queryText.trim(),
      intentL1: sampleForm.intentL1.trim() || undefined,
      intentL2: sampleForm.intentL2.trim() || undefined,
      requiresRag: sampleForm.requiresRag,
      expectedRoute: sampleForm.expectedRoute,
      expectedDocIds: splitCodes(sampleForm.expectedDocIds),
      expectedDocIdsNice: splitCodes(sampleForm.expectedDocIdsNice),
      groundTruth: sampleForm.groundTruth.trim() || undefined
    };
    if (!payload.queryId || !payload.queryText) {
      toast.error("样本 ID 与问题不能为空");
      return;
    }
    try {
      if (editingSampleId) {
        await updateEvalSample(selectedId, editingSampleId, payload);
      } else {
        await createEvalSample(selectedId, payload);
      }
      toast.success("已保存");
      setSampleDialogOpen(false);
      await loadSamples(selectedId, samplePageNo);
      await loadDatasets();
    } catch (error) {
      toast.error(getErrorMessage(error, "保存失败"));
    }
  };

  const handleDeleteSample = async (sample: EvalSample) => {
    if (!selectedId) return;
    if (!window.confirm(`确认删除样本 ${sample.queryId}？`)) return;
    try {
      await deleteEvalSample(selectedId, sample.id);
      toast.success("已删除");
      await loadSamples(selectedId, samplePageNo);
      await loadDatasets();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
    }
  };

  const samples = samplePage?.records || [];
  const runs = runPage?.records || [];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-semibold text-slate-900">
            <FlaskConical className="h-5 w-5 text-indigo-500" />
            效果评测
          </h2>
          <p className="mt-1 text-sm text-slate-500">
            评测集 → 复用线上链路跑批 → 落证据 → 算指标，用于回答质量和检索效果的回归对比
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => setDatasetDialogOpen(true)}>
            <Plus className="h-4 w-4" />
            新建评测集
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={!selectedId}
            onClick={() => {
              setImportName(selectedDataset?.name || "");
              setImportDialogOpen(true);
            }}
          >
            <Upload className="h-4 w-4" />
            导入 JSONL
          </Button>
          <Button size="sm" disabled={!selectedId} onClick={() => setRunDialogOpen(true)}>
            <Play className="h-4 w-4" />
            发起评测
          </Button>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[260px_1fr]">
        <Card className="h-fit">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">评测集</CardTitle>
            <CardDescription className="text-xs">共 {datasets.length} 个</CardDescription>
          </CardHeader>
          <CardContent className="space-y-1 pt-0">
            {datasets.length === 0 ? (
              <p className="px-1 py-6 text-center text-xs text-slate-400">还没有评测集</p>
            ) : (
              datasets.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => {
                    setSelectedId(item.id);
                    setSamplePageNo(1);
                    setRunPageNo(1);
                  }}
                  className={`w-full rounded-xl px-3 py-2 text-left text-sm transition ${
                    item.id === selectedId
                      ? "bg-indigo-50 text-indigo-700"
                      : "text-slate-600 hover:bg-slate-50"
                  }`}
                >
                  <div className="truncate font-medium">{item.name}</div>
                  <div className="text-xs text-slate-400">{item.sampleCount} 条样本</div>
                </button>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-start justify-between gap-3 pb-3">
            <div>
              <CardTitle className="text-base">{selectedDataset?.name || "未选择评测集"}</CardTitle>
              <CardDescription className="text-xs">
                {selectedDataset?.description || "选择左侧评测集查看样本与运行记录"}
              </CardDescription>
            </div>
            <div className="flex items-center gap-2">
              <Button variant="ghost" size="sm" onClick={() => void loadDatasets()}>
                <RefreshCw className="h-4 w-4" />
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={!selectedId}
                className="text-rose-600 hover:text-rose-700"
                onClick={handleDeleteDataset}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          </CardHeader>
          <CardContent className="space-y-3 pt-0">
            <div className="flex items-center gap-1 rounded-full bg-slate-100 p-1 text-sm">
              <button
                type="button"
                onClick={() => setTab("samples")}
                className={`rounded-full px-4 py-1.5 transition ${
                  tab === "samples" ? "bg-white font-medium text-slate-900 shadow-sm" : "text-slate-500"
                }`}
              >
                样本 {selectedDataset ? `(${selectedDataset.sampleCount})` : ""}
              </button>
              <button
                type="button"
                onClick={() => setTab("runs")}
                className={`rounded-full px-4 py-1.5 transition ${
                  tab === "runs" ? "bg-white font-medium text-slate-900 shadow-sm" : "text-slate-500"
                }`}
              >
                运行记录 {runPage ? `(${runPage.total})` : ""}
              </button>
              {tab === "samples" && selectedId ? (
                <Button variant="ghost" size="sm" className="ml-auto" onClick={openCreateSample}>
                  <Plus className="h-4 w-4" />
                  新增样本
                </Button>
              ) : null}
            </div>

            {tab === "samples" ? (
              <>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-24">样本 ID</TableHead>
                      <TableHead>问题</TableHead>
                      <TableHead className="w-28">意图</TableHead>
                      <TableHead className="w-20">路由</TableHead>
                      <TableHead className="w-28">期望文档</TableHead>
                      <TableHead className="w-20 text-right">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {samples.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={6} className="py-10 text-center text-sm text-slate-400">
                          {loading ? "加载中..." : "暂无样本，可导入 JSONL 或手动新增"}
                        </TableCell>
                      </TableRow>
                    ) : (
                      samples.map((sample) => (
                        <TableRow key={sample.id}>
                          <TableCell className="font-mono text-xs">{sample.queryId}</TableCell>
                          <TableCell className="max-w-[320px] truncate" title={sample.queryText}>
                            {sample.queryText}
                          </TableCell>
                          <TableCell className="text-xs text-slate-500">{sample.intentL2 || "-"}</TableCell>
                          <TableCell>
                            <Badge variant="outline" className="text-[11px]">
                              {sample.expectedRoute}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-xs text-slate-500">
                            {sample.expectedDocIds?.length ? sample.expectedDocIds.join(", ") : "-"}
                          </TableCell>
                          <TableCell className="text-right">
                            <Button variant="ghost" size="sm" onClick={() => openEditSample(sample)}>
                              <Pencil className="h-3.5 w-3.5" />
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              className="text-rose-600 hover:text-rose-700"
                              onClick={() => void handleDeleteSample(sample)}
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
                <Pager
                  current={samplePage?.current || 1}
                  pages={samplePage?.pages || 1}
                  total={samplePage?.total || 0}
                  onChange={setSamplePageNo}
                />
              </>
            ) : (
              <>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-40">提交时间</TableHead>
                      <TableHead className="w-24">状态</TableHead>
                      <TableHead className="w-32">进度</TableHead>
                      <TableHead className="w-24">TopK</TableHead>
                      <TableHead className="w-24">判分</TableHead>
                      <TableHead>关键指标</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {runs.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={6} className="py-10 text-center text-sm text-slate-400">
                          还没有运行记录
                        </TableCell>
                      </TableRow>
                    ) : (
                      runs.map((run) => (
                        <TableRow
                          key={run.id}
                          className="cursor-pointer"
                          onClick={() => navigate(`/admin/eval/runs/${run.id}`)}
                        >
                          <TableCell className="text-xs text-slate-500">
                            {formatTime(run.createTime)}
                          </TableCell>
                          <TableCell>
                            <span
                              className={`rounded-full px-2 py-0.5 text-[11px] ${
                                STATUS_CLASS[run.status] || "bg-slate-100 text-slate-600"
                              }`}
                            >
                              {STATUS_LABEL[run.status] || run.status}
                            </span>
                          </TableCell>
                          <TableCell className="text-xs text-slate-500">
                            {run.doneCount}/{run.totalCount}
                            {run.failedCount ? `（失败 ${run.failedCount}）` : ""}
                          </TableCell>
                          <TableCell className="text-xs text-slate-500">{run.topK}</TableCell>
                          <TableCell className="text-xs text-slate-500">
                            {run.judgeEnabled === 1 ? "开" : "关"}
                          </TableCell>
                          <TableCell className="text-xs text-slate-500">
                            {metricSummary(run)}
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
                <Pager
                  current={runPage?.current || 1}
                  pages={runPage?.pages || 1}
                  total={runPage?.total || 0}
                  onChange={setRunPageNo}
                />
              </>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 新建评测集 */}
      <Dialog open={datasetDialogOpen} onOpenChange={setDatasetDialogOpen}>
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>新建评测集</DialogTitle>
            <DialogDescription>一个评测集对应一组标注好的问题与期望文档</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">名称</label>
              <Input
                value={datasetForm.name}
                onChange={(event) => setDatasetForm((prev) => ({ ...prev, name: event.target.value }))}
                placeholder="例如 售后场景回归集"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">描述</label>
              <Textarea
                value={datasetForm.description}
                onChange={(event) => setDatasetForm((prev) => ({ ...prev, description: event.target.value }))}
                placeholder="可选"
                rows={3}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDatasetDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleCreateDataset}>创建</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 导入 */}
      <Dialog open={importDialogOpen} onOpenChange={setImportDialogOpen}>
        <DialogContent className="sm:max-w-[480px]">
          <DialogHeader>
            <DialogTitle>导入评测集</DialogTitle>
            <DialogDescription>
              每行一个 JSON 对象，字段与外部评测项目的 eval_set_*.jsonl 对齐
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">评测集名称</label>
              <Input
                value={importName}
                onChange={(event) => setImportName(event.target.value)}
                placeholder="留空则用文件名"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">写入方式</label>
              <Select value={importMode} onValueChange={(value) => setImportMode(value as "append" | "replace")}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="append">追加（同 query_id 覆盖）</SelectItem>
                  <SelectItem value="replace">清空后导入</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">JSONL 文件</label>
              <Input
                type="file"
                accept=".jsonl,.json,.txt"
                onChange={(event) => setImportFile(event.target.files?.[0] || null)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setImportDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleImport} disabled={importing}>
              {importing ? "导入中..." : "开始导入"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 发起评测 */}
      <Dialog open={runDialogOpen} onOpenChange={setRunDialogOpen}>
        <DialogContent className="sm:max-w-[420px]">
          <DialogHeader>
            <DialogTitle>发起评测</DialogTitle>
            <DialogDescription>
              每条样本都会真实走一遍改写、意图、检索和生成，开启判分会额外产生模型调用
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">检索 TopK</label>
              <Input
                type="number"
                min={1}
                max={50}
                value={runTopK}
                onChange={(event) => setRunTopK(event.target.value)}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">LLM 判分</label>
              <Select value={runJudge} onValueChange={setRunJudge}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">开启（多算 faithfulness / answer_correctness）</SelectItem>
                  <SelectItem value="false">关闭（只算确定性指标）</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <p className="rounded-xl bg-slate-50 px-3 py-2 text-xs text-slate-500">
              单次最多评测 {selectedDataset?.sampleCount || 0} 条样本，实际条数受服务端 maxSamples 限制
            </p>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRunDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleSubmitRun} disabled={submitting}>
              {submitting ? "提交中..." : "开始评测"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 样本编辑 */}
      <Dialog open={sampleDialogOpen} onOpenChange={setSampleDialogOpen}>
        <DialogContent className="sm:max-w-[560px]">
          <DialogHeader>
            <DialogTitle>{editingSampleId ? "编辑样本" : "新增样本"}</DialogTitle>
            <DialogDescription>期望文档写业务码（文档名去掉扩展名），多个用逗号分隔</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">样本 ID</label>
              <Input
                value={sampleForm.queryId}
                onChange={(event) => setSampleForm((prev) => ({ ...prev, queryId: event.target.value }))}
                placeholder="如 Q001"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">期望路由</label>
              <Select
                value={sampleForm.expectedRoute}
                onValueChange={(value) => setSampleForm((prev) => ({ ...prev, expectedRoute: value }))}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="KB">KB（需要检索）</SelectItem>
                  <SelectItem value="SYSTEM">SYSTEM（不需要检索）</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2 sm:col-span-2">
              <label className="text-sm font-medium text-slate-700">问题</label>
              <Textarea
                value={sampleForm.queryText}
                onChange={(event) => setSampleForm((prev) => ({ ...prev, queryText: event.target.value }))}
                rows={2}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">一级意图</label>
              <Input
                value={sampleForm.intentL1}
                onChange={(event) => setSampleForm((prev) => ({ ...prev, intentL1: event.target.value }))}
                placeholder="用于指标分桶"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">二级意图</label>
              <Input
                value={sampleForm.intentL2}
                onChange={(event) => setSampleForm((prev) => ({ ...prev, intentL2: event.target.value }))}
                placeholder="与意图识别结果比对"
              />
            </div>
            <div className="space-y-2 sm:col-span-2">
              <label className="text-sm font-medium text-slate-700">期望文档（must）</label>
              <Input
                value={sampleForm.expectedDocIds}
                onChange={(event) => setSampleForm((prev) => ({ ...prev, expectedDocIds: event.target.value }))}
                placeholder="FAQ_VAC_001, FAQ_VAC_002"
              />
            </div>
            <div className="space-y-2 sm:col-span-2">
              <label className="text-sm font-medium text-slate-700">加分文档（nice）</label>
              <Input
                value={sampleForm.expectedDocIdsNice}
                onChange={(event) =>
                  setSampleForm((prev) => ({ ...prev, expectedDocIdsNice: event.target.value }))
                }
                placeholder="可选"
              />
            </div>
            <div className="space-y-2 sm:col-span-2">
              <label className="text-sm font-medium text-slate-700">参考答案</label>
              <Textarea
                value={sampleForm.groundTruth}
                onChange={(event) => setSampleForm((prev) => ({ ...prev, groundTruth: event.target.value }))}
                rows={3}
                placeholder="判分用，留空则跳过 LLM 判分"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setSampleDialogOpen(false)}>
              取消
            </Button>
            <Button onClick={handleSaveSample}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Pager(props: { current: number; pages: number; total: number; onChange: (page: number) => void }) {
  const { current, pages, total, onChange } = props;
  return (
    <div className="flex items-center justify-between pt-2 text-xs text-slate-500">
      <span>共 {total} 条</span>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" disabled={current <= 1} onClick={() => onChange(current - 1)}>
          上一页
        </Button>
        <span>
          {current} / {pages}
        </span>
        <Button variant="outline" size="sm" disabled={current >= pages} onClick={() => onChange(current + 1)}>
          下一页
        </Button>
      </div>
    </div>
  );
}

function metricSummary(run: EvalRun) {
  const metrics = run.metrics?.metrics;
  if (!metrics?.length) return "-";
  const pick = (name: string) => metrics.find((item) => item.name === name)?.value;
  const hit = pick("hit@5");
  const recall = pick("recall@5");
  const intent = pick("intent_top1");
  const parts = [
    hit === null || hit === undefined ? null : `hit@5 ${formatRatio(hit)}`,
    recall === null || recall === undefined ? null : `recall@5 ${formatRatio(recall)}`,
    intent === null || intent === undefined ? null : `意图 ${formatRatio(intent)}`
  ].filter(Boolean);
  return parts.length ? parts.join(" · ") : "-";
}
