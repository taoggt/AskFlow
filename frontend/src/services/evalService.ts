import { api } from "@/services/api";

export type EvalPage<T> = {
  records: T[];
  total: number;
  current: number;
  size: number;
  pages: number;
};

export type EvalDataset = {
  id: string;
  name: string;
  description?: string;
  sampleCount: number;
  createTime?: string;
};

export type EvalSample = {
  id: string;
  datasetId: string;
  queryId: string;
  queryText: string;
  intentL1?: string;
  intentL2?: string;
  requiresRag: number;
  expectedDocIds: string[];
  expectedDocIdsNice: string[];
  groundTruth?: string;
  expectedRoute: string;
};

export type EvalDocCode = {
  code: string;
  docName: string;
  kbId: string;
};

export type EvalImportResult = {
  imported: number;
  updated: number;
  skipped: number;
  failed: number;
  unknownFields: string[];
  warnings: string[];
};

export type EvalMetricValue = {
  name: string;
  value: number | null;
  sampleCount: number;
};

export type EvalMetrics = {
  metrics: EvalMetricValue[];
  judge: {
    status: string;
    scoredCount: number;
    totalCount: number;
  };
  sampleCount: number;
};

export type EvalRun = {
  id: string;
  datasetId: string;
  datasetName: string;
  status: string;
  totalCount: number;
  doneCount: number;
  failedCount: number;
  topK: number;
  judgeEnabled: number;
  judgeModel?: string;
  metrics?: EvalMetrics | null;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  createTime?: string;
};

export type EvalCaseResult = {
  id: string;
  queryId: string;
  queryText: string;
  intentL2?: string;
  intentPred?: string;
  expectedDocIds: string[];
  retrievedDocIds: string[];
  retrievedContexts: string[];
  retrievedContextDocIds: string[];
  rewrittenQuestion?: string;
  response?: string;
  answerBranch: string;
  finalStatus: string;
  latencyMs?: number;
  firstTokenMs?: number;
  scores?: Record<string, unknown> | null;
  errorMessage?: string;
};

export type EvalSamplePayload = {
  queryId: string;
  queryText: string;
  intentL1?: string;
  intentL2?: string;
  requiresRag: boolean;
  expectedDocIds: string[];
  expectedDocIdsNice: string[];
  groundTruth?: string;
  expectedRoute: string;
};

export async function getEvalDatasets(keyword?: string): Promise<EvalDataset[]> {
  return api.get<EvalDataset[], EvalDataset[]>("/admin/eval/datasets", {
    params: { keyword: keyword || undefined }
  });
}

export async function createEvalDataset(payload: { name: string; description?: string }): Promise<EvalDataset> {
  return api.post<EvalDataset, EvalDataset>("/admin/eval/datasets", payload);
}

export async function deleteEvalDataset(id: string): Promise<void> {
  return api.delete<void, void>(`/admin/eval/datasets/${id}`);
}

export async function getEvalSamples(
  datasetId: string,
  params: { pageNo: number; pageSize: number; keyword?: string }
): Promise<EvalPage<EvalSample>> {
  return api.get<EvalPage<EvalSample>, EvalPage<EvalSample>>(`/admin/eval/datasets/${datasetId}/samples`, {
    params: { ...params, keyword: params.keyword || undefined }
  });
}

export async function createEvalSample(datasetId: string, payload: EvalSamplePayload): Promise<EvalSample> {
  return api.post<EvalSample, EvalSample>(`/admin/eval/datasets/${datasetId}/samples`, payload);
}

export async function updateEvalSample(
  datasetId: string,
  sampleId: string,
  payload: EvalSamplePayload
): Promise<EvalSample> {
  return api.put<EvalSample, EvalSample>(`/admin/eval/datasets/${datasetId}/samples/${sampleId}`, payload);
}

export async function deleteEvalSample(datasetId: string, sampleId: string): Promise<void> {
  return api.delete<void, void>(`/admin/eval/datasets/${datasetId}/samples/${sampleId}`);
}

export async function importEvalDataset(file: File, name?: string, mode: "append" | "replace" = "append") {
  const formData = new FormData();
  formData.append("file", file);
  if (name) {
    formData.append("name", name);
  }
  formData.append("mode", mode);
  return api.post<EvalImportResult, EvalImportResult>("/admin/eval/datasets/import", formData, {
    headers: { "Content-Type": "multipart/form-data" },
    timeout: 120000
  });
}

export async function getEvalDocCodes(kbId?: string): Promise<EvalDocCode[]> {
  return api.get<EvalDocCode[], EvalDocCode[]>("/admin/eval/knowledge-doc-codes", {
    params: { kbId: kbId || undefined }
  });
}

export async function submitEvalRun(payload: {
  datasetId: string;
  topK?: number;
  judgeEnabled?: boolean;
  judgeModel?: string;
}): Promise<EvalRun> {
  return api.post<EvalRun, EvalRun>("/admin/eval/runs", payload);
}

export async function cancelEvalRun(id: string): Promise<void> {
  return api.post<void, void>(`/admin/eval/runs/${id}/cancel`);
}

export async function getEvalRuns(params: {
  datasetId?: string;
  pageNo: number;
  pageSize: number;
}): Promise<EvalPage<EvalRun>> {
  return api.get<EvalPage<EvalRun>, EvalPage<EvalRun>>("/admin/eval/runs", {
    params: { ...params, datasetId: params.datasetId || undefined }
  });
}

export async function getEvalRun(id: string): Promise<EvalRun> {
  return api.get<EvalRun, EvalRun>(`/admin/eval/runs/${id}`);
}

export async function getEvalRunResults(
  id: string,
  params: { pageNo: number; pageSize: number; failedOnly?: boolean }
): Promise<EvalPage<EvalCaseResult>> {
  return api.get<EvalPage<EvalCaseResult>, EvalPage<EvalCaseResult>>(`/admin/eval/runs/${id}/results`, {
    params: { ...params, failedOnly: params.failedOnly || undefined }
  });
}

export async function getEvalRunResultDetail(id: string, queryId: string): Promise<EvalCaseResult> {
  return api.get<EvalCaseResult, EvalCaseResult>(`/admin/eval/runs/${id}/results/${queryId}`);
}
