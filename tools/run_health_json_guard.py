#!/usr/bin/env python3
"""`/health` 响应体必须是**合法 JSON** 的离线守卫。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一份
═══════════════════════════════════════════════════════════════════════════
`healthJson()` 是**手工拼字符串**出来的 JSON。手拼 JSON 最容易被忽略的坑是
Kotlin 原始字符串（`\"\"\"...\"\"\"`）的**行尾引号计数**：

    段尾写 `\"\"\"`  = 内容 0 个引号 + 结束符 `\"\"\"`     ✅
    段尾写 `\"\"\"\"` = 内容 1 个引号 + 结束符 `\"\"\"`     ❌ 多出一个 `"`

多写一个引号后，JSON 里会凭空出现一对 `\"\"`（如 `\"\"ctx_used\"`），整份响应
立刻非法。**这种错误的后果特别隐蔽**：服务本身照常回 200、生成接口完全正常，
只有依赖 `/health` 的探活/监控会把它判成「服务器不可达」—— 于是使用者看到的是
「服务明明是好的，探针却报不可达」，一个纯粹的误报，而病根在服务端。

真机就是这么发生的（Issue #77）：`/health` 从某次改动起一直是非法 JSON，
探测端把它判为不可达是**正确**行为，问题在服务端。

这份守卫把"拼出来的字符串到底是不是合法 JSON"钉死：它按 Kotlin 词法规则
**真的还原** `healthJson()` 的每一段拼接，再用真 `json` 模块解析。这样无论是
行尾引号少了还是多了，都会被拦在提交前。

纯 python3 标准库，不依赖任何工具链。运行：python3 tools/run_health_json_guard.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HTTP = ROOT / "app/src/main/java/com/xiaowan/localinference/HttpApi.kt"

ok = 0
bad = 0


def check(name, cond, detail=""):
    global ok, bad
    if cond:
        print(f"PASS  {name}")
        ok += 1
    else:
        print(f"FAIL  {name}" + (f" —— {detail}" if detail else ""))
        bad += 1


def kotlin_raw_segment(line):
    """把一个以 `\"\"\"` 开头、带 `+` 或行尾的原始字符串**片段**还原成字面量内容。

    Kotlin 词法：`\"\"\"` 开启原始字符串，扫描到下一个 `\"\"\"` 结束；
    若结束处引号多于 3 个，**最后 3 个**才是结束符，前面的都算内容。
    """
    # 去掉行尾的 `+`（拼接符）与其后空白
    line = re.sub(r"\s*\+\s*$", "", line)
    m = re.match(r'^\s*"""', line)
    assert m, f"不是以 \"\"\" 开头：{line!r}"
    rest = line[m.end():]
    run_m = re.search(r'("+)$', rest)
    assert run_m, f"缺少结束引号：{line!r}"
    run = run_m.group(1)
    body = rest[: rest.rfind(run)]
    content_quotes = len(run) - 3  # 最后 3 个是结束符，其余是内容
    assert content_quotes >= 0, f"未闭合的原始字符串：{line!r}"
    return body + '"' * content_quotes


def extract_health_json_expr(src):
    """从源码里切出 `healthJson()` 里那段 `return`/`val json =` 的字符串拼接表达式。"""
    start = src.index("private fun healthJson()")
    end = src.index("private fun modelsJson()", start)
    body = src[start:end]
    # 定位拼接表达式：从第一个 `"""` 起，到第一个行尾 `"`（非 `+` 结尾）为止
    lines = body.splitlines()
    segs = []
    collecting = False
    for ln in lines:
        if not collecting and '"""' in ln and ("val json" in ln or "return \"\"\"" in ln):
            collecting = True
        if collecting and '"""' in ln:
            # 只保留 `"""` 之后的表达式部分（丢掉 `val json = ` / `return ` 前缀）
            segs.append(ln[ln.index('"""'):])
            if not ln.rstrip().endswith("+"):
                break
    assert segs, "没找到 healthJson() 的字符串拼接表达式"
    return segs


src = HTTP.read_text(encoding="utf-8")
seg_lines = extract_health_json_expr(src)

# ── 1) 结构：必须是 4 段拼接（前 3 段带 +，末段不带）────────────────────────
check("healthJson() 的拼接表达式有 4 段", len(seg_lines) == 4,
      f"实际 {len(seg_lines)} 段：{seg_lines}")
check("前 3 段以 `+` 结尾、末段不带 `+`",
      all(l.rstrip().endswith("+") for l in seg_lines[:3])
      and not seg_lines[-1].rstrip().endswith("+"))

# ── 2) 逐段按 Kotlin 词法还原，再拼出真实 JSON ─────────────────────────────
restored = "".join(kotlin_raw_segment(l) for l in seg_lines)
# 把 Kotlin 插值 `${...}` / `$x` 换成**类型正确的常量**（只为让 JSON 能解析，
# 不改变它在 JSON 里的位置与引号结构）：
#   $desc 是 `toJsonStr()` 的产物，本身就是一段带引号的 JSON 串；
#   $loaded/$generating/... 是布尔；其余计数类是整数。
sample = restored.replace("$desc", '"m"')
sample = re.sub(r"\$\{[^}]*\}", "false", sample)
sample = re.sub(r"\$(loaded|generating|kvValid)",
                lambda m: "true" if m.group(1) != "generating" else "false", sample)
sample = re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*", "0", sample)

check("还原后的响应体不含成对空引号 `\"\"`（行尾引号写多的症状）",
      '""' not in sample,
      f"片段：{sample[:80]}")

try:
    obj = json.loads(sample)
    check("/health 响应体是合法 JSON", True)
except Exception as e:  # noqa: BLE001
    check("/health 响应体是合法 JSON", False, f"{e}；还原结果：{sample[:100]}")
    obj = None

# ── 3) 字段完整性：探活/监控依赖的字段一个都不能少 ────────────────────────
if isinstance(obj, dict):
    for key in ("status", "model_loaded", "ctx_used", "ctx_size",
                "busy", "port", "generating", "cancelled",
                "kv_cache_valid", "kv_reuse_tokens", "kv_prefill_tokens",
                "kv_rounds"):
        check(f"字段 {key} 存在且拼写正确", key in obj)
    check('status 恒为 "ok"', obj.get("status") == "ok")

# ── 4) 自检兜底：返回值必须过 JSONObject 解析（防同类问题再次静默上线）─────
check("healthJson() 对拼装结果做了 JSONObject 自检",
      "JSONObject(json)" in src)

print(f"\n{'=' * 60}\nPASS {ok} / FAIL {bad}")
sys.exit(1 if bad else 0)
