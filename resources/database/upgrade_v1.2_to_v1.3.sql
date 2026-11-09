-- askflow v1.2 -> v1.3 升级脚本
-- t_knowledge_document 表：新增文档处理任务与执行令牌字段，用于 MQ 分块任务的并发互斥与结果 fencing

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
