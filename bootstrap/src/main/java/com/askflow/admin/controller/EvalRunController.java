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
import com.askflow.admin.controller.request.EvalRunCreateRequest;
import com.askflow.admin.controller.vo.EvalCaseResultVO;
import com.askflow.admin.controller.vo.EvalRunVO;
import com.askflow.admin.service.EvalRunService;
import com.askflow.framework.convention.Result;
import com.askflow.framework.web.Results;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测运行接口
 * <p>
 * 提交是异步的：立刻返回 runId，前端轮询详情看进度
 */
@RestController
@RequiredArgsConstructor
public class EvalRunController {

    private final EvalRunService runService;

    @PostMapping("/admin/eval/runs")
    public Result<EvalRunVO> submit(@RequestBody EvalRunCreateRequest request) {
        StpUtil.checkRole("admin");
        return Results.success(runService.submit(request));
    }

    @PostMapping("/admin/eval/runs/{id}/cancel")
    public Result<Void> cancel(@PathVariable String id) {
        StpUtil.checkRole("admin");
        runService.cancel(id);
        return Results.success();
    }

    @GetMapping("/admin/eval/runs")
    public Result<IPage<EvalRunVO>> pageRuns(@RequestParam(required = false) String datasetId,
                                             @RequestParam(defaultValue = "1") long pageNo,
                                             @RequestParam(defaultValue = "20") long pageSize) {
        StpUtil.checkRole("admin");
        return Results.success(runService.pageRuns(datasetId, pageNo, pageSize));
    }

    @GetMapping("/admin/eval/runs/{id}")
    public Result<EvalRunVO> detail(@PathVariable String id) {
        StpUtil.checkRole("admin");
        return Results.success(runService.detail(id));
    }

    @GetMapping("/admin/eval/runs/{id}/results")
    public Result<IPage<EvalCaseResultVO>> pageResults(@PathVariable String id,
                                                       @RequestParam(defaultValue = "1") long pageNo,
                                                       @RequestParam(defaultValue = "20") long pageSize,
                                                       @RequestParam(defaultValue = "false") boolean failedOnly) {
        StpUtil.checkRole("admin");
        return Results.success(runService.pageResults(id, pageNo, pageSize, failedOnly));
    }

    @GetMapping("/admin/eval/runs/{id}/results/{queryId}")
    public Result<EvalCaseResultVO> resultDetail(@PathVariable String id, @PathVariable String queryId) {
        StpUtil.checkRole("admin");
        return Results.success(runService.resultDetail(id, queryId));
    }
}
