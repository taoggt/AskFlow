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

import com.askflow.infra.chat.StreamCancellationHandle;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个评测运行的取消令牌
 * <p>
 * 取消有两个层面：已提交但还没开始的样本看 {@link #isCancelled()} 直接跳过；
 * 正在生成中的样本需要把底层的流式句柄 cancel 掉，否则要等模型把话说完
 */
@Slf4j
public class EvalRunCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private final Set<StreamCancellationHandle> handles = ConcurrentHashMap.newKeySet();

    public void cancel() {
        cancelled.set(true);
        handles.forEach(handle -> {
            try {
                handle.cancel();
            } catch (Exception e) {
                log.warn("[评测] 取消流式生成失败", e);
            }
        });
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void register(StreamCancellationHandle handle) {
        if (handle == null) {
            return;
        }
        handles.add(handle);
        // 注册前就已经取消：立即把刚拿到的句柄也停掉
        if (cancelled.get()) {
            handle.cancel();
        }
    }

    public void unregister(StreamCancellationHandle handle) {
        if (handle != null) {
            handles.remove(handle);
        }
    }
}
