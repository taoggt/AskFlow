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

package com.askflow.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.askflow.admin.controller.request.EvalDatasetSaveRequest;
import com.askflow.admin.controller.request.EvalSampleSaveRequest;
import com.askflow.admin.controller.vo.EvalDatasetVO;
import com.askflow.admin.controller.vo.EvalDocCodeVO;
import com.askflow.admin.controller.vo.EvalImportResultVO;
import com.askflow.admin.controller.vo.EvalSampleVO;
import com.askflow.admin.service.EvalDatasetService;
import com.askflow.framework.convention.Result;
import com.askflow.framework.web.Results;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 评测集管理接口
 * <p>
 * 全局拦截器只保证「已登录」，管理员隔离要在每个方法里显式 checkRole
 */
@RestController
@RequiredArgsConstructor
public class EvalDatasetController {

    private final EvalDatasetService datasetService;

    @GetMapping("/admin/eval/datasets")
    public Result<List<EvalDatasetVO>> list(@RequestParam(required = false) String keyword) {
        StpUtil.checkRole("admin");
        return Results.success(datasetService.list(keyword));
    }

    @PostMapping("/admin/eval/datasets")
    public Result<EvalDatasetVO> create(@RequestBody EvalDatasetSaveRequest request) {
        StpUtil.checkRole("admin");
        return Results.success(datasetService.create(request));
    }

    @DeleteMapping("/admin/eval/datasets/{id}")
    public Result<Void> delete(@PathVariable String id) {
        StpUtil.checkRole("admin");
        datasetService.delete(id);
        return Results.success();
    }

    @GetMapping("/admin/eval/datasets/{id}/samples")
    public Result<IPage<EvalSampleVO>> pageSamples(@PathVariable String id,
                                                  @RequestParam(defaultValue = "1") long pageNo,
                                                  @RequestParam(defaultValue = "20") long pageSize,
                                                  @RequestParam(required = false) String keyword) {
        StpUtil.checkRole("admin");
        return Results.success(datasetService.pageSamples(id, pageNo, pageSize, keyword));
    }

    @PostMapping("/admin/eval/datasets/{id}/samples")
    public Result<EvalSampleVO> createSample(@PathVariable String id,
                                             @RequestBody EvalSampleSaveRequest request) {
        StpUtil.checkRole("admin");
        return Results.success(datasetService.createSample(id, request));
    }

    @PutMapping("/admin/eval/datasets/{id}/samples/{sampleId}")
    public Result<EvalSampleVO> updateSample(@PathVariable String id,
                                             @PathVariable String sampleId,
                                             @RequestBody EvalSampleSaveRequest request) {
        StpUtil.checkRole("admin");
        return Results.success(datasetService.updateSample(id, sampleId, request));
    }

    @DeleteMapping("/admin/eval/datasets/{id}/samples/{sampleId}")
    public Result<Void> deleteSample(@PathVariable String id, @PathVariable String sampleId) {
        StpUtil.checkRole("admin");
        datasetService.deleteSample(id, sampleId);
        return Results.success();
    }

    /**
     * 导入 JSONL 评测集
     *
     * @param mode append：按 query_id 覆盖或新增；replace：清空后写入
     */
    @PostMapping("/admin/eval/datasets/import")
    public Result<EvalImportResultVO> importJsonl(@RequestParam("file") MultipartFile file,
                                                  @RequestParam(required = false) String name,
                                                  @RequestParam(defaultValue = "append") String mode) {
        StpUtil.checkRole("admin");
        return Results.success(datasetService.importJsonl(file, name, mode));
    }

    /**
     * 知识库文档业务码，标注 expectedDocIds 时对照用
     */
    @GetMapping("/admin/eval/knowledge-doc-codes")
    public Result<List<EvalDocCodeVO>> docCodes(@RequestParam(required = false) String kbId) {
        StpUtil.checkRole("admin");
        return Results.success(datasetService.listDocCodes(kbId));
    }
}
