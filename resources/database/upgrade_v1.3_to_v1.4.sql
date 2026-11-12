-- askflow v1.3 -> v1.4 升级脚本
-- 新增 RAG 离线评测能力：评测集、评测样本、评测运行、样本结果 共 4 张表
--
-- 适用场景（满足其一即可执行）：
-- 1. 库是用 resources/database/schema_pg.sql 建出来的：全量 schema 已经把 v1.1 / v1.2 / v1.3 的
--    字段和索引都包含进去了，此时这 4 张评测表已存在，本脚本会被全部跳过、等价于无操作（正常现象）
-- 2. 库已经执行过 upgrade_v1.2_to_v1.3.sql：本脚本会真正建表
--
-- 不要和 upgrade_v1.2_to_v1.3.sql 混着跑：那个脚本改的是 t_knowledge_document，
-- 而由 schema_pg.sql 建出来的库已经有这些列，再跑会报 column already exists。
--
-- 本脚本可重复执行：所有 CREATE TABLE / CREATE INDEX 均带 IF NOT EXISTS，重复跑不报错。
-- 注意 IF NOT EXISTS 只做"存在则跳过"，不校验结构差异；若表已存在但字段与下面定义不一致，
-- 需要手工 ALTER 补齐，不能指望重跑本脚本修复。
--
-- 设计说明：
-- 1. 指标聚合结果不单独建表，整份存 t_eval_run.metrics_json（一次写、一次读，无按指标查询需求）
-- 2. 判分原始输出不单独建表，存 t_eval_case_result.scores_json，够追溯即可
-- 3. 样本结果里冗余存一份标注快照（query_text / intent_l2 / expected_doc_ids / ground_truth），
--    保证样本后续被修改后历史 run 仍可复算指标

-- ============================================
-- 评测集
-- ============================================
CREATE TABLE IF NOT EXISTS t_eval_dataset (
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_dataset_name ON t_eval_dataset (name) WHERE deleted = 0;
COMMENT ON TABLE t_eval_dataset IS '评测集';

-- ============================================
-- 评测样本（字段对齐外部评测项目的 eval_set_*.jsonl schema）
-- ============================================
CREATE TABLE IF NOT EXISTS t_eval_sample (
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_sample_query ON t_eval_sample (dataset_id, query_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_eval_sample_dataset ON t_eval_sample (dataset_id, sample_order);
COMMENT ON TABLE t_eval_sample IS '评测样本';
COMMENT ON COLUMN t_eval_sample.expected_doc_ids IS '期望召回的文档业务码(must)，必须命中';
COMMENT ON COLUMN t_eval_sample.expected_doc_ids_nice IS '期望召回的文档业务码(nice)，命中加分';
COMMENT ON COLUMN t_eval_sample.expected_route IS '期望路由：KB(需要检索) / SYSTEM(不需要检索)';

-- ============================================
-- 评测运行
-- ============================================
CREATE TABLE IF NOT EXISTS t_eval_run (
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
CREATE INDEX IF NOT EXISTS idx_eval_run_dataset ON t_eval_run (dataset_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_eval_run_status ON t_eval_run (status);
COMMENT ON TABLE t_eval_run IS '评测运行';
COMMENT ON COLUMN t_eval_run.status IS '运行状态：pending / running / success / failed / cancelled';
COMMENT ON COLUMN t_eval_run.metrics_json IS '指标聚合结果，运行结束后一次性写入';

-- ============================================
-- 评测样本结果（执行时当场采集，事后无法从 trace 还原）
-- ============================================
CREATE TABLE IF NOT EXISTS t_eval_case_result (
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_case_result ON t_eval_case_result (run_id, query_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_eval_case_result_run ON t_eval_case_result (run_id);
COMMENT ON TABLE t_eval_case_result IS '评测样本结果';
COMMENT ON COLUMN t_eval_case_result.retrieved_doc_ids IS '去重后的文档业务码，保留检索顺序，用于 Hit@K/Recall@K';
COMMENT ON COLUMN t_eval_case_result.retrieved_context_doc_ids IS '与 retrieved_contexts 等长、保留 null，用于 chunk 级归因';
COMMENT ON COLUMN t_eval_case_result.answer_branch IS '回答分支：rag / system_only / guidance / empty_retrieval';
COMMENT ON COLUMN t_eval_case_result.scores_json IS '该样本的指标分与判分原始输出';
