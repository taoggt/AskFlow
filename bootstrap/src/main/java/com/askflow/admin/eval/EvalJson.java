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

package com.askflow.admin.eval;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 评测模块的 JSON 小工具
 * <p>
 * JSONB 字段在 Java 侧统一按 String 存取，这里收敛「列表 ↔ JSON 数组字符串」的转换与空值处理
 */
public final class EvalJson {

    private EvalJson() {
    }

    /**
     * JSON 数组字符串 → 字符串列表；空值或非法 JSON 返回空列表
     */
    public static List<String> toList(String json) {
        if (StrUtil.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return JSONUtil.parseArray(json).toList(String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 字符串列表 → JSON 数组字符串；null 元素会被保留为 JSON null
     */
    public static String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        return JSONUtil.toJsonStr(list);
    }

    /**
     * 保证列表长度与给定长度一致，不足补 null，超出截断
     */
    public static List<String> padTo(List<String> list, int size) {
        List<String> result = new ArrayList<>(list == null ? Collections.emptyList() : list);
        while (result.size() < size) {
            result.add(null);
        }
        return result.size() > size ? new ArrayList<>(result.subList(0, size)) : result;
    }
}
