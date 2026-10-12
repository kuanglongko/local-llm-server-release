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
    # 段是从 `\"\"\"` 开头切出来的，可能带行尾 `+`（排版产物）——去掉它
    line = line.rstrip()
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


def extract_health_json_segments(src):
    """从源码里切出 `healthJson()` 的**字符串段**（按 Kotlin 词法，含定界符原样返回）。

    为什么不能靠"行尾有没有 `+`"来分段：那是**排版**，而这段守卫要守的是**词法**。
    Kotlin 原始字符串的结束符是「连续引号里**最后 3 个**」，前面多出来的引号属于
    内容 —— 所以扫描必须按引号配对走。全仓库就 `healthJson()` 这一处手拼响应体，
    这里切错等于整份守卫失效，所以宁可多写几行也要按词法走。
    """
    start = src.index("private fun healthJson()")
    end = src.index("private fun modelsJson()", start)
    body = src[start:end]
    # 从拼接表达式的前缀 `val json = ` 之后开始扫：函数体上方那段注释是**散文**，
    # 里面为了讲清「结尾该写几个引号」自己就写了 `\"\"\"` 甚至 `\"\"\"\"` ——
    # 按词法扫时它们是"合法的段"，会把段落数与还原结果一起带歪。
    anchor = body.index('val json = ')
    segs = []
    i = anchor
    while True:
        i = body.find('"""', i)
        if i < 0:
            break
        k = body.find('"""', i + 3)
        if k < 0:
            raise AssertionError("原始字符串未闭合：%r" % body[i:i + 60])
        n = k
        while n < len(body) and body[n] == '"':
            n += 1
        content_quotes = n - k - 3      # 最后 3 个才是结束符，其余属于内容
        segs.append(body[i:k] + '"' * content_quotes + '"""')
        i = n
    assert segs, "没找到 healthJson() 的字符串拼接表达式"
    return segs


src = HTTP.read_text(encoding="utf-8")
seg_lines = extract_health_json_segments(src)

# ── 1) 结构：段数下限 4，不许并得更少 ───────────────────────────────────────
#
# 这条判据改过（`0.9.143`），原因值得留一句：它原来写「**恰好** 4 段」，
# 理由是"加字段时多拆一段会立刻变红，逼你把字段并进现有段"。方向是对的，
# 但它把判据挂在了**排版**上（几段是上一轮手工折行的结果），而不是 JSON 本身。
# 于是"想拆得更细给人读"这种无害改动会撞红，而真正要命的东西（拼出来是不是合法
# JSON、字段有没有少）反而在它下面。现在改成**段数 >= 4**，如实报出实际段数；
# 护栏全部压到第 2、3 步：**每段按 Kotlin 词法还原 → 拼出来必须是合法 JSON →
# 每个已知字段都在**。
check("healthJson() 的拼接表达式至少 4 段（并成更少才是退化）",
      len(seg_lines) >= 4, f"实际 {len(seg_lines)} 段：{seg_lines}")
check("末段不带 `+`（拼接必须收口）",
      not seg_lines[-1].rstrip().endswith("+"))

# ── 2) 逐段按 Kotlin 词法还原，再拼出真实 JSON ─────────────────────────────
# 段的切法见 extract_health_json_segments（按 `\"\"\"` 配对扫，不看行尾）。
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
                "kv_rounds",
                # Issue #159（后台保活）：冻结痕迹三个字段 + 欠重启标记。
                # 少了它们，「A2 到底有没有生效」就没有可读的证据 ——
                # 被冻期间探活不失败、连接不断，解冻后读数与"从没被冻过"完全一样。
                "frozen_ms", "frozen_count", "frozen_total_ms",
                "restart_pending",
                # RAG 阶段 2 补口：嵌入模型（双常驻）的可观测字段。
                # 少了它们，「嵌入到底装上没有」在探活里完全看不见 ——
                # 而"嵌入没加载（检索必然全空）"与"检索没命中（正常）"
                # 在客户端看来是完全一样的：都是"回答里没有知识库内容"。
                "embed_loaded", "embed_model", "embed_dim"):
        check(f"字段 {key} 存在且拼写正确", key in obj)
    check('status 恒为 "ok"', obj.get("status") == "ok")

# ── 4) 自检兜底：返回值必须过 JSONObject 解析（防同类问题再次静默上线）─────
check("healthJson() 对拼装结果做了 JSONObject 自检",
      "JSONObject(json)" in src)

print(f"\n{'=' * 60}\nPASS {ok} / FAIL {bad}")
sys.exit(1 if bad else 0)
