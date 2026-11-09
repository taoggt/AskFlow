-- askflow v1.2 -> v1.3 升级脚本
-- 1) t_knowledge_document 表：新增文档处理任务与执行令牌字段，用于 MQ 分块任务的并发互斥与结果 fencing
-- 2) RAG 离线评测：评测集、评测样本、评测运行、样本结果 共 4 张表
--
-- 说明：评测相关建表原本放在单独的 upgrade_v1.3_to_v1.4.sql 中；由于本脚本尚未在任何环境执行过，
-- 两个脚本分开跑没有意义，因此合并到本文件（原 v1.3->v1.4 文件已删除），执行本脚本即可完成全部升级。

ALTER TABLE t_knowledge_document ADD COLUMN current_chunk_task_id VARCHAR(64);
ALTER TABLE t_knowledge_document ADD COLUMN processing_token VARCHAR(64);
ALTER TABLE t_knowledge_document ADD COLUMN processing_lease_until TIMESTAMP;

COMMENT ON COLUMN t_knowledge_document.current_chunk_task_id IS '当前有效的文档处理任务ID';
COMMENT ON COLUMN t_knowledge_document.processing_token IS '当前消费者持有的执行令牌';
COMMENT ON COLUMN t_knowledge_document.processing_lease_until IS '当前执行令牌的有效期';

-- 补偿任务扫描 running 文档时使用，部分索引避免全表扫描
CREATE INDEX idx_doc_running_lease ON t_knowledge_document (processing_lease_until) WHERE status = 'running';

-- 历史数据兜底：把仍处于 running 的文档的执行令牌清空，避免升级后长时间无法重新触发
UPDATE t_knowledge_document
SET processing_token       = NULL,
    processing_lease_until = NULL
WHERE status = 'running';

-- ============================================
-- 文档内容哈希：上传时按内容去重
-- ============================================

ALTER TABLE t_knowledge_document ADD COLUMN content_hash VARCHAR(64);

COMMENT ON COLUMN t_knowledge_document.content_hash IS '文件内容 SHA-256 十六进制，用于同知识库内文件去重；远程 URL 文档与历史数据为空';

-- 必须是部分唯一索引，不能是普通唯一索引：
-- t_knowledge_document 走的是逻辑删除（deleted=0/1），普通唯一索引会把已删除的行也纳入约束，
-- 导致文档删除后重新上传同一文件时报唯一键冲突。
-- 同时唯一键限定在 (kb_id, content_hash) 而不是仅 content_hash：
-- 同一份文件进入不同知识库是正常用法（不同 embedding_model / collection），全局唯一会错误拦截。
-- 历史数据 content_hash 为 NULL，PostgreSQL 唯一索引中 NULL 互不相等，因此存量文档不会冲突，
-- 也无需回填——去重只对升级后新上传的文件生效。
CREATE UNIQUE INDEX uk_kb_doc_content_hash ON t_knowledge_document (kb_id, content_hash) WHERE deleted = 0 AND content_hash IS NOT NULL;

-- ============================================
-- RAG 离线评测：4 张表
--
-- 设计说明：
-- 1. 指标聚合结果不单独建表，整份存 t_eval_run.metrics_json（一次写、一次读，无按指标查询需求）
-- 2. 判分原始输出不单独建表，存 t_eval_case_result.scores_json，够追溯即可
-- 3. 样本结果里冗余存一份标注快照（query_text / intent_l2 / expected_doc_ids / ground_truth），
--    保证样本后续被修改后历史 run 仍可复算指标
-- ============================================

-- 评测集
CREATE TABLE t_eval_dataset (
    id           VARCHAR(20)  NOT NULL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512),
    sample_count INTEGER      NOT NULL DEFAULT 0,
    created_by   VARCHAR(20)  DEFAULT '',
    updated_by   VARCHAR(20)  DEFAULT '',
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_eval_dataset_name ON t_eval_dataset (name) WHERE deleted = 0;
COMMENT ON TABLE t_eval_dataset IS '评测集';

-- 评测样本（字段对齐外部评测项目的 eval_set_*.jsonl schema）
CREATE TABLE t_eval_sample (
    id                    VARCHAR(20) NOT NULL PRIMARY KEY,
    dataset_id            VARCHAR(20) NOT NULL,
    query_id              VARCHAR(64) NOT NULL,
    query_text            TEXT        NOT NULL,
    intent_l1             VARCHAR(64),
    intent_l2             VARCHAR(128),
    requires_rag          SMALLINT    NOT NULL DEFAULT 1,
    expected_doc_ids      JSONB,
    expected_doc_ids_nice JSONB,
    ground_truth          TEXT,
    expected_route        VARCHAR(16) NOT NULL DEFAULT 'KB',
    sample_order          INTEGER,
    created_by            VARCHAR(20) DEFAULT '',
    updated_by            VARCHAR(20) DEFAULT '',
    create_time           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               SMALLINT    NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_eval_sample_query ON t_eval_sample (dataset_id, query_id) WHERE deleted = 0;
CREATE INDEX idx_eval_sample_dataset ON t_eval_sample (dataset_id, sample_order);
COMMENT ON TABLE t_eval_sample IS '评测样本';
COMMENT ON COLUMN t_eval_sample.expected_doc_ids IS '期望召回的文档业务码(must)，必须命中';
COMMENT ON COLUMN t_eval_sample.expected_doc_ids_nice IS '期望召回的文档业务码(nice)，命中加分';
COMMENT ON COLUMN t_eval_sample.expected_route IS '期望路由：KB(需要检索) / SYSTEM(不需要检索)';

-- 评测运行
CREATE TABLE t_eval_run (
    id            VARCHAR(20) NOT NULL PRIMARY KEY,
    dataset_id    VARCHAR(20) NOT NULL,
    dataset_name  VARCHAR(128),
    status        VARCHAR(16) NOT NULL DEFAULT 'pending',
    cancel_flag   SMALLINT    NOT NULL DEFAULT 0,
    total_count   INTEGER     NOT NULL DEFAULT 0,
    done_count    INTEGER     NOT NULL DEFAULT 0,
    failed_count  INTEGER     NOT NULL DEFAULT 0,
    top_k         INTEGER     NOT NULL DEFAULT 10,
    judge_enabled SMALLINT    NOT NULL DEFAULT 1,
    judge_model   VARCHAR(64),
    metrics_json  JSONB,
    error_message TEXT,
    started_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    created_by    VARCHAR(20) DEFAULT '',
    updated_by    VARCHAR(20) DEFAULT '',
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_eval_run_dataset ON t_eval_run (dataset_id, create_time DESC);
CREATE INDEX idx_eval_run_status ON t_eval_run (status);
COMMENT ON TABLE t_eval_run IS '评测运行';
COMMENT ON COLUMN t_eval_run.status IS '运行状态：pending / running / success / failed / cancelled';
COMMENT ON COLUMN t_eval_run.metrics_json IS '指标聚合结果，运行结束后一次性写入';

-- 评测样本结果（执行时当场采集，事后无法从 trace 还原）
CREATE TABLE t_eval_case_result (
    id                        VARCHAR(20) NOT NULL PRIMARY KEY,
    run_id                    VARCHAR(20) NOT NULL,
    query_id                  VARCHAR(64) NOT NULL,
    query_text                TEXT,
    ground_truth              TEXT,
    intent_l1                 VARCHAR(64),
    intent_l2                 VARCHAR(128),
    expected_route            VARCHAR(16),
    requires_rag              SMALLINT,
    expected_doc_ids          JSONB,
    retrieved_doc_ids         JSONB,
    retrieved_context_doc_ids JSONB,
    retrieved_contexts        JSONB,
    intent_pred               VARCHAR(128),
    rewritten_question        TEXT,
    response                  TEXT,
    answer_branch             VARCHAR(24),
    final_status              VARCHAR(16),
    latency_ms                BIGINT,
    first_token_ms            BIGINT,
    scores_json               JSONB,
    error_message             TEXT,
    created_by                VARCHAR(20) DEFAULT '',
    updated_by                VARCHAR(20) DEFAULT '',
    create_time               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                   SMALLINT    NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_eval_case_result ON t_eval_case_result (run_id, query_id) WHERE deleted = 0;
CREATE INDEX idx_eval_case_result_run ON t_eval_case_result (run_id);
COMMENT ON TABLE t_eval_case_result IS '评测样本结果';
COMMENT ON COLUMN t_eval_case_result.retrieved_doc_ids IS '去重后的文档业务码，保留检索顺序，用于 Hit@K/Recall@K';
COMMENT ON COLUMN t_eval_case_result.retrieved_context_doc_ids IS '与 retrieved_contexts 等长、保留 null，用于 chunk 级归因';
COMMENT ON COLUMN t_eval_case_result.answer_branch IS '回答分支：rag / system_only / guidance / empty_retrieval';
COMMENT ON COLUMN t_eval_case_result.scores_json IS '该样本的指标分与判分原始输出';
