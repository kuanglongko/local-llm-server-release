#!/usr/bin/env python3
"""HTTP 响应体的**合法性**守卫：每个手拼响应体都必须是合法、完整的 JSON。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一份（Issue #174 / 模块乙-3）
═══════════════════════════════════════════════════════════════════════════
本仓库的响应体是**手工拼字符串**（Kotlin 原始字符串 + 插值），而不是序列化器生成。
手拼 JSON 的坏法**全是静默的**：Kotlin 编得过、HTTP 200 照回、单测/守卫全绿，
只是发出去的字节里多一个引号、少一个逗号 —— 客户端 `JSON.parse` 直接抛。

`/health` 已经栽过一次真事故（Issue #77）：`healthJson()` 的行尾引号写多一个，
拼出来多一对空引号 `""`，整份响应非法；后果极隐蔽 —— 生成接口完全正常，只有依赖
`/health` 的探活把它判成「服务器不可达」。那份守卫因此存在（`run_health_json_guard.py`）。

但**其余端点从来没被这样量过**：API 从 3 个长到 7 个，`finish_reason` / `n` /
三个协议字段是上一轮才刚补上的。今天服务端**没有任何一处**核「响应体是不是合法 JSON」，
全靠 `JSONObject` 在别处逃过异常；`Content-Length` 也无人核。

这一份把 `/health` 那套词法**扩到全部响应体**：按 Kotlin 词法还原每一段拼接，
把插值换成类型正确的常量，再真解析。行尾引号多/少、逗号漏、括号错位，全部在提交前红。

纯 python3 标准库。运行：python3 tools/run_http_body_guard.py
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "http_body"))
from kotlin_json_lex import scan_raw_strings, restore_segment  # noqa: E402

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


def slice_fn(src, anchor, stop_at):
    """从 `anchor`（函数签名）之后到 `stop_at`（下一个函数的签名）之前的源码。

    不试图找花括号：本仓库这类工具函数有的是块体，有的是单表达式体（等号后直接跟
    原始字符串）。切到下一个函数为止即可 —— 段是按词法扫的，多带的注释不会影响
    [scan_raw_strings]（它只认引号配对）。
    """
    start = src.index(anchor)
    end = src.index(stop_at, start)
    return src[start:end]


def body_of(src, anchor, stop_at):
    """把一处响应体拼接表达式还原成字面量字符串（含插值原样保留）。"""
    seg = slice_fn(src, anchor, stop_at)
    # 拼接表达式里第一个 `"""` 之后到该语句结束（`，` / `)` / 换行 `)` 之前）
    i = seg.index('"""')
    # 取到该表达式收口：最后一个 `"""` 段（本仓库一律以 `"""` 结尾，后面可能跟逗号/括回）
    j = seg.rindex('"""') + 3
    expr = seg[i:j]
    return "".join(restore_segment(s) for s in scan_raw_strings(expr))


def as_json_body(restored):
    """把还原结果里的 Kotlin 插值换成**类型正确的 JSON 常量**后解析。

    关键是判断插值**落在 JSON 的引号里还是外**：
      · `"id":"$id"` —— `$id` 在引号内，插值本身是裸文本，填裸值；
      · `"content":$contentField` —— 在引号外，插值本身是带引号的 JSON 串，填 `"x"`；
      · `"created":$created` —— 在引号外、且是数字，填 `0`。
    搞反了就会把 "id" 填成 `""m""` —— 那会假红（本守卫第一次写完就当场撞上过），
    也会掩盖真正的引号结构错误。
    """
    out = []
    i = 0
    n = len(restored)
    while i < n:
        c = restored[i]
        if c != "$":
            out.append(c)
            i += 1
            continue
        # 取出插值文本
        if i + 1 < n and restored[i + 1] == "{":
            depth = 1
            j = i + 2
            while j < n and depth:
                if restored[j] == "{":
                    depth += 1
                elif restored[j] == "}":
                    depth -= 1
                j += 1
            expr = restored[i:j]
            i = j
        else:
            j = i + 1
            while j < n and (restored[j].isalnum() or restored[j] == "_"):
                j += 1
            expr = restored[i:j]
            i = j
        # 落在引号内？看左邻居是不是 `"`（简单但够用：本仓库的插值都被显式引号包着）
        in_quotes = len(out) > 0 and out[-1] == '"'
        out.append(subst(expr, in_quotes))
    return "".join(out)


def subst(expr, in_quotes):
    """把一个插值换成类型正确的 JSON 常量。"""
    name = re.sub(r"^\$\{?|\}?$", "", expr)
    # 引号内的：全是裸文本/数字形，直接给裸值
    if in_quotes:
        return "0" if name in ("created", "n", "promptTok", "completionTok") else "m"
    # 引号外的：必须是完整 JSON 值
    # 片段类（含前导逗号或空）——不出现是合法的一种
    if name in ("toolField", "lpField", "reasonField", "logprobsField", "ragField"):
        return ""
    if name == "fin":
        return '"stop"'
    if name == "usage":
        return '{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}'
    # 内容类：toJsonStr() 产物或已是带引号串
    if name in ("contentField", "deltaJson", "textJson"):
        return '"x"'
    if name == "desc":
        return '"m"'
    # 兜底：当成数字，最不容易引入引号结构错误
    return "0"


src = HTTP.read_text(encoding="utf-8")

check("HttpApi.kt 存在且非空", len(src) > 1000)

# ── 1) 非流式响应体：chat / completions ────────────────────────────────────
# 这两条是本模块的核心：它们就是 SDK 拿到的那份字节。手拼错了，客户端 `JSON.parse` 抛。
CHAT_ANCHOR = '"id":"$id","object":"chat.completion"'
COMP_ANCHOR = '"id":"$id","object":"text_completion"'

for label, anchor in (("/v1/chat/completions 非流式响应体", CHAT_ANCHOR),
                      ("/v1/completions 非流式响应体", COMP_ANCHOR)):
    seg = src.index(anchor)
    line_start = src.rindex("\n", 0, seg)
    line_end = src.index("corsOrigin", seg)
    expr = src[line_start:line_end]
    restored = "".join(restore_segment(s) for s in scan_raw_strings(expr))
    check(f"{label}：不含成对空引号 `\"\"`（行尾引号写多的症状）",
          '""' not in restored, f"片段：{restored[:80]}")
    sample = as_json_body(restored)
    try:
        json.loads(sample)
        check(f"{label}：是合法 JSON", True)
    except Exception as e:  # noqa: BLE001
        check(f"{label}：是合法 JSON", False, f"{e}；还原：{sample[:120]}")

# ── 2) SSE chunk 构造器：chat / completion ────────────────────────────────
# 流式每一帧都是同一份手拼模板。坏法与上面同族，但更隐蔽：单帧错只让**某一类**
# 事件非法，客户端往往在解析那一帧时才崩，现场看起来像"流到一半断了"。
for label, anchor, stop in (
    ("chat chunk 构造器", "private fun chatChunkJson", "private fun compChunkJson"),
    ("completion chunk 构造器", "private fun compChunkJson", "private fun logprobsFieldOf"),
):
    body = slice_fn(src, anchor, stop)
    segs = scan_raw_strings(body)
    restored = "".join(restore_segment(s) for s in segs)
    check(f"{label}：不含成对空引号 `\"\"`", '""' not in restored,
          f"片段：{restored[:80]}")
    sample = as_json_body(restored)
    try:
        json.loads(sample)
        check(f"{label}：是合法 JSON", True)
    except Exception as e:  # noqa: BLE001
        check(f"{label}：是合法 JSON", False, f"{e}；还原：{sample[:120]}")

# ── 3) 小响应体：usageJson / errJson / handleAbort ────────────────────────
for label, anchor, stop in (
    ("usageJson 响应体", "private fun usageJson", "private fun errJson"),
    ("errJson 响应体", "private fun errJson", "private fun String.toJsonStr"),
    ("handleAbort 响应体", "private fun handleAbort", "private fun chatChunkJson"),
):
    body = slice_fn(src, anchor, stop)
    segs = scan_raw_strings(body)
    if not segs:
        check(f"{label}：找得到拼接表达式", False, "该函数体里没有原始字符串")
        continue
    restored = "".join(restore_segment(s) for s in segs)
    sample = as_json_body(restored)
    try:
        json.loads(sample)
        check(f"{label}：是合法 JSON", True)
    except Exception as e:  # noqa: BLE001
        check(f"{label}：是合法 JSON", False, f"{e}；还原：{sample[:120]}")

# ── 4) 协议面：`n` 只支持 1，choices 必须是**恰好一条** ────────────────────
# `n: 2` 的失效形态是"HTTP 200、choices 少一条"，而很多 SDK 根本不看 length。
# 解析侧已由 run_protocol_fields_guard 钉住 400；这里钉**响应侧形状**：
# 每个 choice 里 index 必须存在且为 0（单候选），否则 SDK 会按 index 找错位。
for label, anchor, stop in (
    ("chat chunk 的 choices[0].index", "private fun chatChunkJson", "private fun compChunkJson"),
    ("completion chunk 的 choices[0].index", "private fun compChunkJson", "private fun logprobsFieldOf"),
):
    body = slice_fn(src, anchor, stop)
    restored = "".join(restore_segment(s) for s in scan_raw_strings(body))
    check(f"{label}：choices 里显式带 index:0（单候选定位）",
          '"index":0' in restored, f"还原：{restored[:120]}")

# 非流式两处同理
for label, anchor in (("非流式 chat 的 choices[0].index", CHAT_ANCHOR),
                      ("非流式 completions 的 choices[0].index", COMP_ANCHOR)):
    seg = src.index(anchor)
    line_start = src.rindex("\n", 0, seg)
    line_end = src.index("corsOrigin", seg)
    restored = "".join(restore_segment(s) for s in scan_raw_strings(src[line_start:line_end]))
    check(f"{label}：显式带 index:0", '"index":0' in restored, f"还原：{restored[:120]}")

# ── 5) Content-Length 与写出的字节数**同源** ──────────────────────────────
# 手写头时最容易出的错是"长度按字符串数、写出按 UTF-8 字节"——中文一进来就短。
# 判据：Content-Length 必须由 `b.size`（字节数）算，而不是 `body.length`（字符数）。
check("Content-Length 取自 UTF-8 字节数（b.size），不是字符数",
      "Content-Length: ${b.size}" in src,
      "长度按字符数算时，含中文的响应体会被客户端截断/挂起")

# ── 6) 拼装结果必须有自检（与 /health 同形的兜底）─────────────────────────
# /health 已有 `JSONObject(json)` 自检。其余响应体目前**没有任何一处**核过合法性。
# 这条不要求立刻把每个端点都加自检（那要动业务代码），而是钉住"至少要有一处
# 程序化兜底"，并在收口文档里登记盲区。
check("至少有一处程序化 JSON 自检兜底（/health 的 JSONObject 自检）",
      "JSONObject(json)" in src or "JSONObject(body)" in src)

print(f"\n{'=' * 60}\nPASS {ok} / FAIL {bad}")
sys.exit(1 if bad else 0)
