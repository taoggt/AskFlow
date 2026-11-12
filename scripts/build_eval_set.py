#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""评测集 JSONL 生成 / 校验 / 规范化工具

字段契约来自：
  bootstrap/src/main/java/com/askflow/admin/service/EvalDatasetService.java#importJsonl
  bootstrap/src/main/java/com/askflow/admin/controller/request/EvalSampleSaveRequest.java

三个子命令：
  check      校验一份待导入 JSONL（重复 query_id、缺字段、非法 route、业务码对不上）
  normalize  把外部评测项目的 eval_set_*.jsonl 洗成 Ragent 可直接导入的格式
  scaffold   从知识库业务码生成待标注骨架，保证 query_id 唯一、业务码零拼写错误

示例：
  python scripts/build_eval_set.py check     --src eval_set_v1_all.jsonl --doc-codes doccodes.json
  python scripts/build_eval_set.py normalize --src eval_set_v1_all.jsonl --out rag_hr.jsonl --drop-non-kb
  python scripts/build_eval_set.py scaffold  --doc-codes doccodes.json --out skeleton.jsonl --per-doc 2
"""

import argparse
import json
import os
import sys
from collections import Counter, OrderedDict

# 真正会落库的 9 个字段，输出只保留这些，避免 unknownFields 噪音
PERSISTED = [
    "query_id", "query", "intent_l1", "intent_l2", "requires_rag",
    "expected_doc_ids", "expected_doc_ids_nice", "ground_truth", "expected_route",
]

# 后端认得但不会落库的字段，遇到时提示一句
RECOGNIZED_BUT_DROPPED = [
    "difficulty", "expected_answer_type", "trap_type",
    "evaluation_scope", "scope_reason", "annotation_rationale", "requires_tool",
]

VALID_ROUTES = {"KB", "SYSTEM"}


def read_jsonl(path):
    rows, bad_lines = [], []
    with open(path, "r", encoding="utf-8-sig") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append((lineno, json.loads(line)))
            except json.JSONDecodeError as exc:
                bad_lines.append((lineno, str(exc)))
    return rows, bad_lines


def load_doc_codes(path):
    """支持两种格式：[{"code": "...", ...}] 或 ["...", "..."]"""
    if not path:
        return set()
    with open(path, "r", encoding="utf-8-sig") as fh:
        data = json.load(fh)
    if isinstance(data, dict):
        data = list(data.keys())
    codes = set()
    for item in data:
        if isinstance(item, str):
            codes.add(item.strip())
        elif isinstance(item, dict):
            code = item.get("code") or item.get("docCode") or item.get("name")
            if code:
                codes.add(str(code).strip())
    return codes


def normalize_route(raw_route, requires_rag):
    route = (raw_route or "").strip().upper()
    if route:
        return route
    return "KB" if requires_rag else "SYSTEM"


def to_str_list(value):
    if value is None:
        return []
    if isinstance(value, str):
        value = [value]
    out, seen = [], set()
    for item in value:
        if item is None:
            continue
        text = str(item).strip()
        if text and text not in seen:
            seen.add(text)
            out.append(text)
    return out


def cmd_check(args):
    rows, bad_lines = read_jsonl(args.src)
    known = load_doc_codes(args.doc_codes)

    errors, warnings = [], []
    ids = Counter()
    missing_docs = Counter()
    route_dist = Counter()
    dropped_fields = Counter()

    for lineno, row in rows:
        qid = (row.get("query_id") or "").strip()
        query = (row.get("query") or "").strip()
        if not qid:
            errors.append("第 %d 行：缺 query_id" % lineno)
        else:
            ids[qid] += 1
        if not query:
            errors.append("第 %d 行：缺 query" % lineno)

        requires_rag = row.get("requires_rag")
        requires_rag = True if requires_rag is None else bool(requires_rag)
        route = normalize_route(row.get("expected_route"), requires_rag)
        route_dist[route] += 1
        if route not in VALID_ROUTES:
            warnings.append("%s：expected_route=%s 不是 KB/SYSTEM，检索与行为指标不会计入该样本"
                            % (qid or lineno, route))

        docs = to_str_list(row.get("expected_doc_ids"))
        if route == "KB" and not docs:
            warnings.append("%s：KB 路由但没标 expected_doc_ids，检索指标分母会漏掉它" % (qid or lineno))
        if route == "SYSTEM" and docs:
            warnings.append("%s：SYSTEM 路由却标了 expected_doc_ids，大概率是标注矛盾" % (qid or lineno))

        if known:
            for code in docs:
                if code not in known:
                    missing_docs[code] += 1
        for key in row:
            if key in RECOGNIZED_BUT_DROPPED:
                dropped_fields[key] += 1

    dup = [qid for qid, cnt in ids.items() if cnt > 1]

    print("=" * 60)
    print("文件：%s" % args.src)
    print("有效行 %d / 非法 JSON 行 %d / 唯一 query_id %d" % (len(rows), len(bad_lines), len(ids)))
    print("路由分布：%s" % dict(route_dist))
    for lineno, msg in bad_lines:
        print("  [JSON 错误] 第 %d 行：%s" % (lineno, msg))
    if dup:
        print("  [重复 query_id] %s" % ", ".join(dup[:20]))
    if missing_docs:
        print("  [业务码不在知识库] %d 个：" % len(missing_docs))
        for code, cnt in missing_docs.most_common(20):
            print("    %s（被引用 %d 次）" % (code, cnt))
        print("  → 这些样本的 Hit@K 恒为 0，不是检索坏了，是标注的码跟 doc_name 对不上")
    if dropped_fields:
        print("  [提示] 以下字段后端认得但不落库：%s" % dict(dropped_fields))

    for msg in errors[:20]:
        print("  [错误] %s" % msg)
    for msg in warnings[:20]:
        print("  [警告] %s" % msg)
    if len(errors) > 20 or len(warnings) > 20:
        print("  ……（其余省略）")
    print("=" * 60)

    ok = not errors and not dup and not bad_lines
    print("结论：%s" % ("可以导入" if ok else "先修掉上面的错误再导入"))
    return 0 if ok else 1


def cmd_normalize(args):
    rows, bad_lines = read_jsonl(args.src)
    kept, dropped_route, dropped_invalid = [], 0, 0

    for lineno, row in rows:
        qid = (row.get("query_id") or "").strip()
        query = (row.get("query") or "").strip()
        if not qid or not query:
            dropped_invalid += 1
            continue

        requires_rag = row.get("requires_rag")
        requires_rag = True if requires_rag is None else bool(requires_rag)
        route = normalize_route(row.get("expected_route"), requires_rag)
        if route not in VALID_ROUTES and args.drop_non_kb:
            dropped_route += 1
            continue

        item = OrderedDict()
        item["query_id"] = qid
        item["query"] = query
        if row.get("intent_l1"):
            item["intent_l1"] = str(row["intent_l1"]).strip()
        if row.get("intent_l2"):
            item["intent_l2"] = str(row["intent_l2"]).strip()
        item["requires_rag"] = requires_rag
        docs = to_str_list(row.get("expected_doc_ids"))
        if docs:
            item["expected_doc_ids"] = docs
        nice = to_str_list(row.get("expected_doc_ids_nice"))
        if nice:
            item["expected_doc_ids_nice"] = nice
        if row.get("ground_truth"):
            item["ground_truth"] = str(row["ground_truth"]).strip()
        item["expected_route"] = route
        kept.append(item)

    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir and not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        for item in kept:
            fh.write(json.dumps(item, ensure_ascii=False) + "\n")

    print("输出：%s" % args.out)
    print("保留 %d 条 / 丢弃 %d 条（非法 JSON 或空行 %d）"
          % (len(kept), dropped_route + dropped_invalid, len(bad_lines)))
    if dropped_route:
        print("  其中 %d 条因 expected_route 非 KB/SYSTEM 被丢弃（--drop-non-kb）" % dropped_route)
    print("字段已裁剪为落库所需的 9 个，不会再有 unknownFields 提示")
    return 0


def cmd_scaffold(args):
    codes = load_doc_codes(args.doc_codes)
    if not codes:
        print("错误：--doc-codes 为空，先请求 /admin/eval/knowledge-doc-codes 导出业务码")
        return 1

    out = []
    for code in sorted(codes):
        for idx in range(1, args.per_doc + 1):
            out.append(OrderedDict([
                ("query_id", "%s-Q%02d" % (code, idx)),
                ("query", "TODO 写一个用户会问、答案就在 %s 里的问题" % code),
                ("intent_l1", ""),
                ("intent_l2", "TODO_意图"),
                ("requires_rag", True),
                ("expected_doc_ids", [code]),
                ("expected_doc_ids_nice", []),
                ("ground_truth", "TODO 用 1-3 个可独立判断真假的事实句作答"),
                ("expected_route", "KB"),
            ]))

    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        for item in out:
            fh.write(json.dumps(item, ensure_ascii=False) + "\n")

    print("输出：%s" % args.out)
    print("生成 %d 条骨架（%d 份文档 × %d 条），business code 已对齐知识库" % (len(out), len(codes), args.per_doc))
    print("下一步：把 query / ground_truth / intent_l2 的 TODO 换成真实内容，再跑 check")
    return 0


def main():
    parser = argparse.ArgumentParser(description="Ragent 评测集 JSONL 工具")
    sub = parser.add_subparsers(dest="cmd")

    p_check = sub.add_parser("check", help="校验待导入的 JSONL")
    p_check.add_argument("--src", required=True)
    p_check.add_argument("--doc-codes", help="业务码清单 JSON，用于校验 expected_doc_ids")
    p_check.set_defaults(func=cmd_check)

    p_norm = sub.add_parser("normalize", help="外部评测集转 Ragent 格式")
    p_norm.add_argument("--src", required=True)
    p_norm.add_argument("--out", required=True)
    p_norm.add_argument("--drop-non-kb", action="store_true",
                        help="丢弃 expected_route 不是 KB/SYSTEM 的样本")
    p_norm.set_defaults(func=cmd_normalize)

    p_sca = sub.add_parser("scaffold", help="从业务码生成待标注骨架")
    p_sca.add_argument("--doc-codes", required=True)
    p_sca.add_argument("--out", required=True)
    p_sca.add_argument("--per-doc", type=int, default=2)
    p_sca.set_defaults(func=cmd_scaffold)

    args = parser.parse_args()
    if not getattr(args, "func", None):
        parser.print_help()
        return 1
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
