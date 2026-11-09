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

package com.askflow.admin.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.askflow.admin.controller.request.EvalDatasetSaveRequest;
import com.askflow.admin.controller.request.EvalSampleSaveRequest;
import com.askflow.admin.controller.vo.EvalDatasetVO;
import com.askflow.admin.controller.vo.EvalDocCodeVO;
import com.askflow.admin.controller.vo.EvalImportResultVO;
import com.askflow.admin.controller.vo.EvalSampleVO;
import com.askflow.admin.dao.entity.EvalDatasetDO;
import com.askflow.admin.dao.entity.EvalRunDO;
import com.askflow.admin.dao.entity.EvalSampleDO;
import com.askflow.admin.dao.mapper.EvalDatasetMapper;
import com.askflow.admin.dao.mapper.EvalRunMapper;
import com.askflow.admin.dao.mapper.EvalSampleMapper;
import com.askflow.admin.eval.EvalConstants;
import com.askflow.admin.eval.EvalJson;
import com.askflow.framework.context.UserContext;
import com.askflow.framework.exception.ClientException;
import com.askflow.knowledge.dao.entity.KnowledgeDocumentDO;
import com.askflow.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.askflow.rag.eval.EvalEvidenceAssembler;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测集与评测样本管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    /**
     * 导入时认识的字段；其余字段记入 unknownFields 回给前端，不静默丢弃
     */
    private static final Set<String> KNOWN_FIELDS = Set.of(
            "query_id", "query", "intent_l1", "intent_l2", "difficulty", "requires_rag",
            "expected_doc_ids", "expected_doc_ids_nice", "ground_truth", "expected_answer_type",
            "trap_type", "expected_route", "evaluation_scope", "scope_reason", "annotation_rationale",
            "requires_tool"
    );

    private static final int MAX_WARNINGS = 20;

    private final EvalDatasetMapper datasetMapper;
    private final EvalSampleMapper sampleMapper;
    private final EvalRunMapper runMapper;
    private final KnowledgeDocumentMapper documentMapper;

    // ==================== 评测集 ====================

    public List<EvalDatasetVO> list(String keyword) {
        return datasetMapper.selectList(Wrappers.lambdaQuery(EvalDatasetDO.class)
                        .like(StrUtil.isNotBlank(keyword), EvalDatasetDO::getName, keyword)
                        .orderByDesc(EvalDatasetDO::getCreateTime))
                .stream()
                .map(this::toDatasetVO)
                .toList();
    }

    public EvalDatasetVO create(EvalDatasetSaveRequest request) {
        String name = StrUtil.trim(request.name());
        if (StrUtil.isBlank(name)) {
            throw new ClientException("评测集名称不能为空");
        }
        if (existsByName(name)) {
            throw new ClientException("评测集名称已存在：" + name);
        }
        EvalDatasetDO dataset = EvalDatasetDO.builder()
                .name(name)
                .description(request.description())
                .sampleCount(0)
                .createdBy(currentUserId())
                .build();
        datasetMapper.insert(dataset);
        return toDatasetVO(dataset);
    }

    /**
     * 删除评测集：正在跑的运行未结束时拒绝，避免评测引擎读到空数据集
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String datasetId) {
        requireDataset(datasetId);
        Long running = runMapper.selectCount(Wrappers.lambdaQuery(EvalRunDO.class)
                .eq(EvalRunDO::getDatasetId, datasetId)
                .in(EvalRunDO::getStatus, List.of(EvalConstants.RUN_PENDING, EvalConstants.RUN_RUNNING)));
        if (running != null && running > 0) {
            throw new ClientException("该评测集还有运行中的评测任务，请先取消或等待完成");
        }
        sampleMapper.delete(Wrappers.lambdaQuery(EvalSampleDO.class).eq(EvalSampleDO::getDatasetId, datasetId));
        datasetMapper.deleteById(datasetId);
    }

    // ==================== 样本 ====================

    public IPage<EvalSampleVO> pageSamples(String datasetId, long pageNo, long pageSize, String keyword) {
        requireDataset(datasetId);
        Page<EvalSampleDO> page = new Page<>(pageNo, pageSize);
        IPage<EvalSampleDO> result = sampleMapper.selectPage(page, Wrappers.lambdaQuery(EvalSampleDO.class)
                .eq(EvalSampleDO::getDatasetId, datasetId)
                .like(StrUtil.isNotBlank(keyword), EvalSampleDO::getQueryText, keyword)
                .orderByAsc(EvalSampleDO::getSampleOrder));
        Page<EvalSampleVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toSampleVO).toList());
        return voPage;
    }

    @Transactional(rollbackFor = Exception.class)
    public EvalSampleVO createSample(String datasetId, EvalSampleSaveRequest request) {
        requireDataset(datasetId);
        validateSample(request);
        if (existsQueryId(datasetId, request.queryId(), null)) {
            throw new ClientException("样本 ID 已存在：" + request.queryId());
        }
        EvalSampleDO sample = buildSample(datasetId, request);
        sample.setSampleOrder((int) nextSampleOrder(datasetId));
        sample.setCreatedBy(currentUserId());
        sampleMapper.insert(sample);
        refreshSampleCount(datasetId);
        return toSampleVO(sample);
    }

    @Transactional(rollbackFor = Exception.class)
    public EvalSampleVO updateSample(String datasetId, String sampleId, EvalSampleSaveRequest request) {
        requireDataset(datasetId);
        validateSample(request);
        EvalSampleDO existing = sampleMapper.selectById(sampleId);
        if (existing == null || !datasetId.equals(existing.getDatasetId())) {
            throw new ClientException("样本不存在");
        }
        if (existsQueryId(datasetId, request.queryId(), sampleId)) {
            throw new ClientException("样本 ID 已存在：" + request.queryId());
        }
        EvalSampleDO sample = buildSample(datasetId, request);
        sample.setId(sampleId);
        sample.setSampleOrder(existing.getSampleOrder());
        sample.setUpdatedBy(currentUserId());
        sampleMapper.updateById(sample);
        return toSampleVO(sampleMapper.selectById(sampleId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSample(String datasetId, String sampleId) {
        sampleMapper.deleteById(sampleId);
        refreshSampleCount(datasetId);
    }

    // ==================== 导入 ====================

    /**
     * 导入 JSONL（每行一个 JSON 对象），字段对齐外部评测项目的 eval_set_*.jsonl
     *
     * @param mode append：按 query_id upsert；replace：先清空再写入
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalImportResultVO importJsonl(MultipartFile file, String name, String mode) {
        if (file == null || file.isEmpty()) {
            throw new ClientException("请上传 JSONL 文件");
        }
        String content = readFile(file);
        List<String> lines = content.lines().filter(StrUtil::isNotBlank).toList();
        if (lines.isEmpty()) {
            throw new ClientException("文件中没有有效数据行");
        }

        String datasetName = StrUtil.blankToDefault(StrUtil.trim(name), stripExtension(file.getOriginalFilename()));
        if (StrUtil.isBlank(datasetName)) {
            throw new ClientException("请指定评测集名称");
        }
        EvalDatasetDO dataset = findOrCreateDataset(datasetName);
        boolean replace = "replace".equalsIgnoreCase(StrUtil.trim(mode));
        if (replace) {
            sampleMapper.delete(Wrappers.lambdaQuery(EvalSampleDO.class).eq(EvalSampleDO::getDatasetId, dataset.getId()));
        }

        Map<String, String> existingIds = new HashMap<>();
        sampleMapper.selectList(Wrappers.lambdaQuery(EvalSampleDO.class)
                        .eq(EvalSampleDO::getDatasetId, dataset.getId()))
                .forEach(sample -> existingIds.put(sample.getQueryId(), sample.getId()));

        Set<String> knownDocCodes = loadKnownDocCodes();
        Set<String> unknownFields = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        int imported = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        int order = 0;

        for (String line : lines) {
            JSONObject json;
            try {
                json = JSONUtil.parseObj(line);
            } catch (Exception e) {
                failed++;
                addWarning(warnings, "第 " + (order + 1) + " 行不是合法 JSON，已跳过");
                order++;
                continue;
            }
            json.keySet().stream()
                    .filter(key -> !KNOWN_FIELDS.contains(key))
                    .forEach(unknownFields::add);

            String queryId = json.getStr("query_id");
            String queryText = json.getStr("query");
            if (StrUtil.isBlank(queryId) || StrUtil.isBlank(queryText)) {
                skipped++;
                addWarning(warnings, "第 " + (order + 1) + " 行缺少 query_id 或 query，已跳过");
                order++;
                continue;
            }

            EvalSampleSaveRequest request = new EvalSampleSaveRequest(
                    queryId,
                    queryText,
                    json.getStr("intent_l1"),
                    json.getStr("intent_l2"),
                    json.getBool("requires_rag", Boolean.TRUE),
                    toStrList(json.getJSONArray("expected_doc_ids")),
                    toStrList(json.getJSONArray("expected_doc_ids_nice")),
                    json.getStr("ground_truth"),
                    resolveRoute(json.getStr("expected_route"), json.getBool("requires_rag", Boolean.TRUE))
            );
            collectDocCodeWarnings(request, knownDocCodes, warnings);

            EvalSampleDO sample = buildSample(dataset.getId(), request);
            sample.setSampleOrder(order++);
            String existingId = existingIds.get(queryId);
            if (existingId == null) {
                sample.setCreatedBy(currentUserId());
                sampleMapper.insert(sample);
                existingIds.put(queryId, sample.getId());
                imported++;
            } else {
                sample.setId(existingId);
                sample.setUpdatedBy(currentUserId());
                sampleMapper.updateById(sample);
                updated++;
            }
        }

        refreshSampleCount(dataset.getId());
        log.info("[评测] 导入完成 dataset={} imported={} updated={} skipped={} failed={}",
                dataset.getName(), imported, updated, skipped, failed);
        return new EvalImportResultVO(imported, updated, skipped, failed, unknownFields, warnings);
    }

    // ==================== 文档业务码 ====================

    /**
     * 列出知识库文档的业务码（doc_name 剥后缀），评测集标注 expected_doc_ids 时对照用
     */
    public List<EvalDocCodeVO> listDocCodes(String kbId) {
        return documentMapper.selectList(Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(StrUtil.isNotBlank(kbId), KnowledgeDocumentDO::getKbId, kbId)
                        .orderByAsc(KnowledgeDocumentDO::getDocName))
                .stream()
                .filter(document -> StrUtil.isNotBlank(document.getDocName()))
                .map(document -> new EvalDocCodeVO(
                        EvalEvidenceAssembler.stripExtension(document.getDocName()),
                        document.getDocName(),
                        document.getKbId()))
                .toList();
    }

    // ==================== 内部方法 ====================

    private EvalDatasetDO requireDataset(String datasetId) {
        EvalDatasetDO dataset = datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new ClientException("评测集不存在");
        }
        return dataset;
    }

    private boolean existsByName(String name) {
        Long count = datasetMapper.selectCount(Wrappers.lambdaQuery(EvalDatasetDO.class)
                .eq(EvalDatasetDO::getName, name));
        return count != null && count > 0;
    }

    private EvalDatasetDO findOrCreateDataset(String name) {
        List<EvalDatasetDO> found = datasetMapper.selectList(Wrappers.lambdaQuery(EvalDatasetDO.class)
                .eq(EvalDatasetDO::getName, name));
        if (!found.isEmpty()) {
            return found.get(0);
        }
        EvalDatasetDO dataset = EvalDatasetDO.builder()
                .name(name)
                .description("由 JSONL 导入")
                .sampleCount(0)
                .createdBy(currentUserId())
                .build();
        datasetMapper.insert(dataset);
        return dataset;
    }

    private void validateSample(EvalSampleSaveRequest request) {
        if (StrUtil.isBlank(request.queryId())) {
            throw new ClientException("样本 ID 不能为空");
        }
        if (StrUtil.isBlank(request.queryText())) {
            throw new ClientException("问题不能为空");
        }
    }

    private EvalSampleDO buildSample(String datasetId, EvalSampleSaveRequest request) {
        boolean requiresRag = request.requiresRag() == null || request.requiresRag();
        return EvalSampleDO.builder()
                .datasetId(datasetId)
                .queryId(StrUtil.trim(request.queryId()))
                .queryText(request.queryText())
                .intentL1(request.intentL1())
                .intentL2(request.intentL2())
                .requiresRag(requiresRag ? 1 : 0)
                .expectedDocIds(EvalJson.toJson(request.expectedDocIds()))
                .expectedDocIdsNice(EvalJson.toJson(request.expectedDocIdsNice()))
                .groundTruth(request.groundTruth())
                .expectedRoute(resolveRoute(request.expectedRoute(), requiresRag))
                .build();
    }

    /**
     * 缺 expected_route 时按 requires_rag 兜底：需要检索就是 KB，否则是 SYSTEM
     */
    private String resolveRoute(String route, boolean requiresRag) {
        if (StrUtil.isNotBlank(route)) {
            return route.trim().toUpperCase();
        }
        return requiresRag ? EvalConstants.ROUTE_KB : EvalConstants.ROUTE_SYSTEM;
    }

    private boolean existsQueryId(String datasetId, String queryId, String excludeId) {
        Long count = sampleMapper.selectCount(Wrappers.lambdaQuery(EvalSampleDO.class)
                .eq(EvalSampleDO::getDatasetId, datasetId)
                .eq(EvalSampleDO::getQueryId, queryId)
                .ne(StrUtil.isNotBlank(excludeId), EvalSampleDO::getId, excludeId));
        return count != null && count > 0;
    }

    private long nextSampleOrder(String datasetId) {
        Long count = sampleMapper.selectCount(Wrappers.lambdaQuery(EvalSampleDO.class)
                .eq(EvalSampleDO::getDatasetId, datasetId));
        return count == null ? 0 : count;
    }

    /**
     * 样本数由样本表实际条数回写，避免增删改各算一遍导致对不上
     */
    private void refreshSampleCount(String datasetId) {
        Long count = sampleMapper.selectCount(Wrappers.lambdaQuery(EvalSampleDO.class)
                .eq(EvalSampleDO::getDatasetId, datasetId));
        EvalDatasetDO patch = new EvalDatasetDO();
        patch.setId(datasetId);
        patch.setSampleCount(count == null ? 0 : count.intValue());
        datasetMapper.updateById(patch);
    }

    private Set<String> loadKnownDocCodes() {
        Set<String> codes = new HashSet<>();
        documentMapper.selectList(Wrappers.lambdaQuery(KnowledgeDocumentDO.class))
                .stream()
                .filter(document -> StrUtil.isNotBlank(document.getDocName()))
                .forEach(document -> codes.add(EvalEvidenceAssembler.stripExtension(document.getDocName())));
        return codes;
    }

    /**
     * 标注的业务码在知识库里找不到时给一条警告：这种情况指标会一直是 0，但检索其实正常
     */
    private void collectDocCodeWarnings(EvalSampleSaveRequest request, Set<String> knownDocCodes, List<String> warnings) {
        if (knownDocCodes.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (request.expectedDocIds() != null) {
            request.expectedDocIds().stream().filter(code -> !knownDocCodes.contains(code)).forEach(missing::add);
        }
        if (request.expectedDocIdsNice() != null) {
            request.expectedDocIdsNice().stream().filter(code -> !knownDocCodes.contains(code)).forEach(missing::add);
        }
        if (!missing.isEmpty()) {
            addWarning(warnings, "样本 " + request.queryId() + " 的业务码在知识库中不存在：" + missing);
        }
    }

    private void addWarning(List<String> warnings, String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        }
    }

    private String readFile(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ClientException("读取上传文件失败");
        }
    }

    private List<String> toStrList(cn.hutool.json.JSONArray array) {
        if (array == null) {
            return List.of();
        }
        return array.toList(String.class);
    }

    private static String stripExtension(String fileName) {
        return EvalEvidenceAssembler.stripExtension(fileName);
    }

    private String currentUserId() {
        return StrUtil.nullToEmpty(UserContext.getUserId());
    }

    private EvalDatasetVO toDatasetVO(EvalDatasetDO dataset) {
        return new EvalDatasetVO(dataset.getId(), dataset.getName(), dataset.getDescription(),
                dataset.getSampleCount(), dataset.getCreateTime());
    }

    private EvalSampleVO toSampleVO(EvalSampleDO sample) {
        return new EvalSampleVO(
                sample.getId(),
                sample.getDatasetId(),
                sample.getQueryId(),
                sample.getQueryText(),
                sample.getIntentL1(),
                sample.getIntentL2(),
                sample.getRequiresRag(),
                EvalJson.toList(sample.getExpectedDocIds()),
                EvalJson.toList(sample.getExpectedDocIdsNice()),
                sample.getGroundTruth(),
                sample.getExpectedRoute());
    }
}
