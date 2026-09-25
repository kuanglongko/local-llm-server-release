#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
原始 SSE 取证：回答"客户端到底收到了什么字节"这三个问题。

════════════════════════════════════════════════════════════════════════════
它只回答三个问题（不做断言式验收，不猜测服务端该怎样）
════════════════════════════════════════════════════════════════════════════
  ① `content` / `reasoning_content` 字段里**有没有裸标签**（`<think>` / `</think>`
     等）。有 = 状态机漏了，客户端会自己再识别一次 → 折叠里再折叠。
  ② **有没有同一段文本出现两次**。这是"分块发两次"的判据，但要分清两种形状：
       · 相邻两帧内容**完全相等**            → 服务端把同一段发了两次；
       · 每一帧都是**到目前为止的累计文本**   → 客户端每帧从头拼一遍（或服务端发了累计）；
       · 文本里出现 `我我` / `MiniMini` 这种短串连排 → 上面两种留下的痕迹。
     三者成因完全不同，混在一起会把锅扣错层，所以分开报。
  ③ **输出尾部那几个字符的原始字节**：乱码是不是 U+FFFD（`EF BF BD`）？
     还要区分"服务端真的发了 EF BF BD"与"服务端发了**非法 UTF-8**、被解码层
     换成 U+FFFD"—— 后者才是 stop 暂扣切进字符内部那个 bug 的形状。

════════════════════════════════════════════════════════════════════════════
为什么要自己开 socket，而不是用 urllib
════════════════════════════════════════════════════════════════════════════
`urllib` 会把 HTTP/chunked 框架、逐次到达的字节边界、非法 UTF-8 全部抹平，
只给你一个已经解好码的 `str`。而这三问里有两问（②的形状、③的字节）恰恰只在
**框架与字节层**才看得见。所以这里手写最小 HTTP/1.1 客户端：

  · 记录**每次 `recv` 到达的字节**（含到达顺序与时间）—— 这是"分块"的原始证据；
  · 保留**chunked 分帧**（`sseEvent` 每条事件是 4 次 write：长度行 / payload / CRLF，
    客户端看到的"分块"就发生在这里）；
  · 全程按 `bytes` 处理，不解码。解码只发生在最后一层，并且**两种解码各跑一遍**：
    `strict`（拿 offset，证明服务端发了非法 UTF-8）与 `replace`（拿文本）。

════════════════════════════════════════════════════════════════════════════
它把东西写到**脚本自己所在的目录**（你要的"好操作"）
════════════════════════════════════════════════════════════════════════════
跑完在脚本旁边落三个文件（时间戳命名，不会互相覆盖）：

  sse-<ts>.raw          服务端回来的**原始字节**（含 HTTP 头与 chunked 框架）
  sse-<ts>.report.txt   人读报告（与终端打印的内容一致，可直接发给我）
  sse-<ts>.json         结构化解析结果（字段级，便于再分析）

同时把报告打到 stdout（Termux 里直接可见、可长按复制）。

用法（Termux，手机本地跑）:
    python acceptance_sse.py                      # 默认 127.0.0.1:8083，自动探测模型
    python acceptance_sse.py --think              # 这次带"开思考"
    python acceptance_sse.py --stop '，'          # 复现"带 stop 时乱码"
    python acceptance_sse.py --print-curl         # 只打一条等价 curl -N，自己手动抓
    python acceptance_sse.py --from-file cap.bin  # 分析已经抓下来的原始流

可选：
    --host H          默认 127.0.0.1
    --port P          默认 8083
    --path P          默认 /v1/chat/completions
    --model M         默认自动探测（GET /v1/models 取第一个 id）
    --prompt TEXT     默认一句能逼出长输出的中文；**不要塞 ` <think> ` 之类裸标签**
                      （模板对 user 消息原样拼接，字面标签会被模型当真标签读 →
                       报告变成"脚本自造的形状"，查不出服务端问题；脚本会拒跑）
    --think           请求里显式开思考（enable_thinking=true）
    --no-think        请求里显式关思考（enable_thinking=false）
    --stop S          可重复；给 stop 用于复现"暂扣切碎 UTF-8"
    --max-tokens N    默认 512
    --timeout S       默认 180
    --out-dir DIR     报告落盘目录，默认=脚本所在目录
    --label TAG       给文件名加个标记，例如 --label think-on
    --tail N          尾部原始字节打印多少字节，默认 96

退出码：0 三问都干净；1 三问里**有发现**（详情在报告里，这是取证不是判分）；2 环境问题。
"""

import argparse
import json
import os
import socket
import sys
import time

C_GREEN, C_RED, C_YEL, C_DIM, C_RST = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"

# 裸标签：这些形状**不应该**出现在 content / reasoning_content 的**值**里。
# 客户端拿到它们会自己再识别一次 → 折叠里再折叠（正是要查的症状）。
BARE_TAGS = [
    "<think>", "</think>",
    "<thinking>", "</thinking>",
    "<|im_start|>", "<|im_end|>",
    "<|endoftext|>", "</s>",
    "<tool_call>", "</tool_call>",
]

# 尾部长这样就是"被替换过的非法字节"。要按**字节**找，不能按字符找：字符串层
# 找 "\ufffd" 只能告诉你"有个替换符"，告诉不了你是服务端发的还是解码层造的。
FFFD = b"\xef\xbf\xbd"


def script_dir():
    """脚本所在目录 —— pyz 场景下 sys.argv[0] 就是 .pyz 自己，正是你要的"脚本旁边"。"""
    a = sys.argv[0] or ""
    if a.endswith(".pyz") or a.endswith(".py") or os.path.exists(a):
        return os.path.dirname(os.path.abspath(a))
    return os.getcwd()


# ────────────────────────────── 原始 HTTP：保留一切 ──────────────────────────────

class RawResp(object):
    def __init__(self):
        self.raw = b""          # 服务端回来的全部字节（头 + 体）
        self.head = b""         # HTTP 响应头
        self.body = b""         # 响应体（chunked 已解出）
        self.arrivals = []      # [(t, 起始偏移, 本次字节)] —— "分块"的原始证据
        self.chunks = []        # [(大小, 数据)] chunked 分帧，非 chunked 时为空
        self.status = None
        self.headers = {}
        self.decode_error_at = None   # body 里第一处非法 UTF-8 的字节偏移
        self.frame_bytes = []         # [(t, bytes)] 分帧边界上的片段，见 q3


def http_post_raw(host, port, path, body_bytes, timeout):
    """最小 HTTP/1.1 客户端：逐次 recv 记账，不做任何解码。"""
    r = RawResp()
    s = socket.create_connection((host, port), timeout=timeout)
    try:
        head = (
            "POST %s HTTP/1.1\r\n"
            "Host: %s:%d\r\n"
            "Content-Type: application/json\r\n"
            "Accept: text/event-stream\r\n"
            "Content-Length: %d\r\n"
            "Connection: close\r\n\r\n" % (path, host, port, len(body_bytes))
        )
        s.sendall(head.encode("ascii") + body_bytes)
        s.settimeout(timeout)
        buf = b""
        while True:
            try:
                b = s.recv(65536)
            except socket.timeout:
                break            # 超时按"流到此为止"处理，已收到的字节照样分析
            if not b:
                break
            r.arrivals.append((time.monotonic(), len(buf), b))
            buf += b
        r.raw = buf
    finally:
        try:
            s.close()
        except Exception:        # noqa: BLE001
            pass
    return r


def http_get(host, port, path, timeout):
    s = socket.create_connection((host, port), timeout=timeout)
    try:
        s.sendall(("GET %s HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\n\r\n"
                   % (path, host, port)).encode("ascii"))
        s.settimeout(timeout)
        buf = b""
        while True:
            b = s.recv(65536)
            if not b:
                break
            buf += b
        return buf
    finally:
        try:
            s.close()
        except Exception:        # noqa: BLE001
            pass


def split_http(r):
    """把头 / 体分开，并解出 chunked 分帧。全程 bytes。"""
    i = r.raw.find(b"\r\n\r\n")
    if i < 0:
        r.head, r.body = b"", r.raw
        return
    r.head, rest = r.raw[:i], r.raw[i + 4:]
    first = r.head.split(b"\r\n", 1)[0].decode("latin-1")
    parts = first.split()
    if len(parts) >= 2 and parts[1].isdigit():
        r.status = int(parts[1])
    for line in r.head.split(b"\r\n")[1:]:
        if b":" in line:
            k, v = line.split(b":", 1)
            r.headers[k.strip().lower().decode("latin-1")] = v.strip().decode("latin-1")

    te = (r.headers.get("transfer-encoding") or "").lower()
    if "chunked" not in te:
        r.body = rest
        return

    # chunked：一边切帧一边记大小。**不**只看内容 —— 帧的切法本身就是证据：
    # 服务端每条事件 4 次 write，客户端"分块"就发生在这一层。
    pos = 0
    out = []
    while True:
        j = rest.find(b"\r\n", pos)
        if j < 0:
            break
        size_line = rest[pos:j].split(b";", 1)[0].strip()
        try:
            n = int(size_line, 16)
        except ValueError:
            break
        if n == 0:
            break
        data = rest[j + 2:j + 2 + n]
        r.chunks.append((n, data))
        out.append(data)
        pos = j + 2 + n + 2
    r.body = b"".join(out)
    # chunked 帧就是"分帧边界" —— 比 TCP 到达片段可信得多：
    # 一次 recv 可能把十几个 chunk 一起收回来（真机上几乎总是如此），
    # 拿 TCP 到达片段当边界会**把该报的切点漏掉**（本函数第一版就踩了这条）。
    if r.chunks:
        r.frame_bytes = [(0.0, d) for _n, d in r.chunks]


def analyze_bytes(r):
    """补三样字节层事实：非法 UTF-8 的偏移、U+FFFD 字节是否出现过、尾部字节。"""
    try:
        r.body.decode("utf-8", "strict")
    except UnicodeDecodeError as e:
        r.decode_error_at = e.start
    r.fffd_count = r.body.count(FFFD)
    return r


# ────────────────────────────── SSE 解析（字段级） ──────────────────────────────

def parse_sse(body_bytes):
    """把响应体切成事件，再取每个事件的 delta 字段。

    返回 (events, warn)。events 每项含：
      idx / raw_data(原文) / ok(能否 json) / content / reasoning / finish
      / content_present / reasoning_present

    注意：`_present` 与"值非空"是两件事。`"content":""` 与**没有 content 键**
    在协议上不同，诊断时不能混。
    """
    events = []
    warn = []
    text = body_bytes.decode("utf-8", "replace")
    for block in text.split("\n\n"):
        data = []
        for line in block.split("\n"):
            line = line.rstrip("\r")
            if line.startswith("data:"):
                data.append(line[5:].lstrip())
        if not data:
            continue
        payload = "\n".join(data)
        if payload.strip() == "[DONE]":
            events.append({"idx": len(events), "kind": "done", "raw_data": payload})
            continue
        ev = {"idx": len(events), "kind": "data", "raw_data": payload,
              "content": "", "reasoning": "", "finish": None,
              "content_present": False, "reasoning_present": False, "ok": False}
        try:
            j = json.loads(payload)
            ev["ok"] = True
            ch = j.get("choices") or []
            if ch:
                d = ch[0].get("delta")
                if d is None:
                    d = ch[0]          # 有些实现非流式形状混在流里
                if isinstance(d, dict):
                    if "content" in d:
                        ev["content_present"] = True
                        ev["content"] = d.get("content") or ""
                    if "reasoning_content" in d:
                        ev["reasoning_present"] = True
                        ev["reasoning"] = d.get("reasoning_content") or ""
                ev["finish"] = ch[0].get("finish_reason")
        except Exception as e:                       # noqa: BLE001
            warn.append("第 %d 条 data 不是合法 JSON：%s；原文=%r"
                        % (len(events), e, payload[:120]))
        events.append(ev)
    return events, warn


# ────────────────────────────── 自污染防线 ──────────────────────────────

def self_pollution_hits(prompt):
    """prompt 里出现**字面标签**就是自污染：脚本自己在造待查的形状。

    判据只针对"标签会不会被模型当标签读"这一件事，所以只看 prompt 文本本身，
    不看它被放在哪个 role —— 模板对 user 是原样拼接，任何 role 都危险。
    返回命中的标签列表（空 = 干净）。
    """
    return [t for t in BARE_TAGS if t in (prompt or "")]


def q1_bare_tags(events):
    """① 字段值里有没有裸标签。"""
    hits = []
    for e in events:
        if e.get("kind") != "data":
            continue
        for field in ("content", "reasoning"):
            v = e.get(field) or ""
            for tag in BARE_TAGS:
                start = 0
                while True:
                    k = v.find(tag, start)
                    if k < 0:
                        break
                    hits.append({
                        "event": e["idx"], "field": field, "tag": tag, "at": k,
                        "context": v[max(0, k - 24):k + len(tag) + 24],
                    })
                    start = k + 1
    return hits


def q2_duplication(events):
    """② 同一段文本是不是出现了两次。三种形状分开判，别混。"""
    res = {"adjacent_exact": [], "cumulative": [], "short_dup": [], "whole_twice": [],
           "n_frames": 0}
    for field in ("content", "reasoning"):
        seq = [(e["idx"], e.get(field) or "") for e in events
               if e.get("kind") == "data" and (e.get(field) or "")]
        if not seq:
            continue
        res["n_frames"] = max(res["n_frames"], len(seq))

        # 形状 A：相邻两帧**完全相等** —— 服务端把同一段发了两次。
        # 只报"非空且相等"的相邻对；`""` 相等不算（空帧很常见、无意义）。
        run = 1
        for i in range(1, len(seq)):
            if seq[i][1] == seq[i - 1][1]:
                run += 1
            else:
                if run > 1:
                    res["adjacent_exact"].append({
                        "field": field, "from_event": seq[i - run][0], "to_event": seq[i - 1][0],
                        "times": run, "text": seq[i - 1][1][:80]})
                run = 1
        if run > 1:
            res["adjacent_exact"].append({
                "field": field, "from_event": seq[len(seq) - run][0],
                "to_event": seq[-1][0], "times": run, "text": seq[-1][1][:80]})

        # 形状 B：每一帧都是"到目前为止的累计文本"（后一帧以整个前一帧为前缀开始）。
        # 这是"客户端每帧从头拼一遍"的痕迹；服务端若真发了累计也是同一形状。
        if len(seq) >= 3:
            grow = all(seq[i][1].startswith(seq[i - 1][1]) and len(seq[i][1]) > len(seq[i - 1][1])
                       for i in range(1, len(seq)))
            if grow:
                res["cumulative"].append({
                    "field": field,
                    "events": [seq[0][0], seq[-1][0]],
                    "first_frame": seq[0][1][:60],
                    "last_frame_len": len(seq[-1][1]),
                })

        # 形状 C：文本里短串**连排**（`我我` / `MiniMini` / `你好你好`）。
        # 这是上面两种形状在最终文本上留下的印记，肉眼最容易认。
        joined = "".join(t for _, t in seq)
        seen = set()
        for L in range(1, 7):
            for i in range(0, max(0, len(joined) - 2 * L + 1)):
                a, b = joined[i:i + L], joined[i + L:i + 2 * L]
                if a and a == b:
                    if a in seen:
                        continue
                    seen.add(a)
                    res["short_dup"].append({"field": field, "unit": a,
                                             "context": joined[max(0, i - 12):i + 2 * L + 12]})

    # 整个 data 载荷重复（最粗的一层）。
    #
    # 这里**故意**判得很保守：单字符 delta（`"1"` `"+"` 这种）在正常流里天然就会
    # 重复出现，拿它当"发两次"是**假红** —— 本脚本第一版就踩了这条，被 self-test
    # 的 `good` 桩抓出来。所以只报"内容尺寸足够大、且这份载荷的**内容**本身在别处
    # 也出现过"的情形，并且把"内容"而非整条 JSON 拿来比（id/时间戳不同不影响）。
    payloads = []
    for e in events:
        if e.get("kind") != "data":
            continue
        txt = (e.get("content") or "") + (e.get("reasoning") or "")
        if len(txt) >= 12:          # 太短的天然重复，不报
            payloads.append((e["idx"], txt))
    seen = {}
    for idx, txt in payloads:
        if txt in seen:
            res["whole_twice"].append({
                "first": seen[txt], "again": idx,
                "payload": txt[:100]})
        else:
            seen[txt] = idx
    return res


def q3_tail(raw_body, text, tail_n, frame_bytes=None):
    """③ 尾部那几个字符的原始字节 + U+FFFD 判定。"""
    tail_text = text[-tail_n:]
    out = {
        "tail_text": tail_text,
        "tail_text_repr": repr(tail_text),
        "tail_bytes": tail_text.encode("utf-8", "replace"),
        "body_tail_raw": raw_body[-tail_n:],
        "fffd_in_body": raw_body.count(FFFD),
        "invalid_utf8_at": None,   # 由 analyze_bytes 填
        "boundary_cuts": [],       # 见 replacement_evidence
    }
    out["boundary_cuts"] = replacement_evidence(raw_body, frame_bytes or [])
    return out


def replacement_evidence(raw_body, frame_bytes):
    """找"字符在**帧边界**被切开、残缺字节又被替换成 U+FFFD"的痕迹。

    ════════════════════════════════════════════════════════════════════════
    为什么必须补这一条：整包解码通过**并不**等于没坏
    ════════════════════════════════════════════════════════════════════════
    只看"整个响应体是不是合法 UTF-8"会漏掉一整类真实故障：

        `，`(U+FF0C) = EF BC 8C
        暂扣区在 EF BC 处放行 -> 孤立续字节
        下游 `new_string_utf8_safe` 把它换成 U+FFFD -> 发出去的是 EF BF BD

    发出去的是 **EF BF BD，一个三字节的合法字符** —— 整包解码当然通过，U+FFFD
    计数也是 0（那是在数服务端发的 EF BF BD；这里服务端发的是替换符自身）。
    于是脚本报"没有乱码证据"，而客户端看到的就是 `�`。这正是 stop 暂扣切碎
    UTF-8 那个 bug（#57）修完之后的形状：从"非法字节"变成了"合法的替换符"。

    还有更隐蔽的一种：`，` 的前两字节 EF BC 与后面某个 0x8D 拼成了 `－`(U+FF0D)
    —— 字符被**静默改写**，连替换符都不会出现。那种只能靠"帧边界落在多字节
    字符内部"这一条来发现：

        EF BC | 8C ...
        ^^^^^ 这一帧以"一个多字节序列的非末尾字节"收尾

    ════════════════════════════════════════════════════════════════════════
    判据（故意保守，只报"形状对得上"的，不做推断）
    ════════════════════════════════════════════════════════════════════════
    对每个帧边界，看它前面那 1~3 个字节能不能构成"某个字符的前缀"：

        · 某字符的**真前缀**（如 EF BC）—— 切点落在字符内部；
        · **孤立续字节**（如 8C）—— 切点落在字符内部且前面那截已经丢了；
          或"替换符紧跟一个非字符边界"—— 前一片的残骸被替换过。

    两种都只说明"这里可疑"，**不能**单独证明服务端有罪：
      · 一个"恰好按 UTF-8 字符边界分帧"的服务端不会命中；
      · HTTP chunked 是应用层写的，正常实现按事件写，不按字节滑窗；
      · 但客户端渲染是一层层 buffer 的，所以真机上"帧边界"未必等于服务端切点。
    因此这里只在**报告里判黄并说明成因**，不直接判红 —— 要定性还得看第 3 问
    报出来的原始字节。
    """
    hits = []
    if len(frame_bytes) < 2:
        return hits

    # 逐帧累积；每到一个帧边界，就检查"这条边界是否落在某个 UTF-8 字符内部"。
    #
    # 判据只有一句：**把已收字节切在帧边界上，前半段能不能解码**。
    #   · 能解码 -> 这一刀落在字符边界上，正常；
    #   · 不能解码 -> 这一刀切进了字符内部（后面那一帧是来补它的）。
    #
    # 为什么不看"帧尾那几字节"：acc 是完整字节流（含 chunked 框架与 JSON 结构），
    # 帧尾常常是 `"}}]}` 这种 ASCII，拿它当"残片"永远看不出问题 —— 本函数第一版
    # 就是这么写的，`good` 桩全绿而真故障也全绿，等于没写。
    acc = b""
    n_frames = len(frame_bytes)
    for idx, (_t, piece) in enumerate(frame_bytes):
        acc += piece
        if idx == n_frames - 1:
            # 最后一片的"边界"是流末尾，没有下一片来补它，不算切点
            continue
        # 帧边界就是 `end = len(acc)` —— **不要**剥掉尾部的 \r\n / 引号之类，
        # 那些字节本身就是被切的那个字符之后的内容。切点要落在"字节流"上，
        # 而不是"看起来像内容的那部分"上（第一版在这里 rstrip 了一把，
        # 结果切点被拉回到 ASCII 引号上，判据就永远不触发了）。
        end = len(acc)
        if end <= 0:
            continue
        try:
            acc[:end].decode("utf-8", "strict")
            continue                       # 停在字符边界上，这一刀没问题
        except UnicodeDecodeError as err:
            bad_at = err.start           # 第一个解不出来的字节，就是断点
        # 被切开的字符从 `prev_ok` 开始：往前找最近的、能让 acc[:k] 解码通过的位置。
        # `bad_at` 只是"第一个解不出来的字节"，多字节字符的起始字节在它更前面
        # （EF BC 8C 被切在 EF BC 时，bad_at 指到 EF，起始字节也是 EF；但若切点
        # 落在续字节上，bad_at 会指到那个续字节，起始字节还要往前）。
        k = bad_at
        while k > 0 and end - k <= 4:
            k -= 1
            try:
                acc[:k].decode("utf-8", "strict")
                break
            except UnicodeDecodeError:
                continue
        frag = acc[k:end]
        if not frag:
            continue
        # 从残片里挑出**真正的字符起始字节**：第一个 ≥0x80 的字节。
        # 它前面可能挂着 `"` 之类的 ASCII（JSON 结构），那不是被切开的东西。
        head = None
        for byte in frag:
            if byte >= 0x80:
                head = byte
                break
        if head is None:
            continue
        if 0x80 <= head <= 0xBF:
            kind = "孤立续字节（被切开字符的前缀已在更早的帧里丢掉）"
        elif 0xC0 <= head <= 0xEF:
            kind = "多字节字符被切成两帧（字符内部断开）"
        elif 0xF0 <= head <= 0xF7:
            kind = "四字节字符被切成两帧（字符内部断开）"
        else:
            continue
        hits.append({"frame": idx, "at": k, "bytes": frag.hex(" "), "kind": kind})

    # 去重：同一位移只留一条
    seen, out = set(), []
    for h in hits:
        if h["at"] in seen:
            continue
        seen.add(h["at"])
        out.append(h)
    return out[:20]


# ────────────────────────────── 输出 ──────────────────────────────

def hexdump(b, base=0, width=16):
    lines = []
    for i in range(0, len(b), width):
        part = b[i:i + width]
        hexs = " ".join("%02x" % c for c in part)
        asc = "".join(chr(c) if 32 <= c < 127 else "." for c in part)
        lines.append("  %08x  %-*s  |%s|" % (base + i, width * 3, hexs, asc))
    return "\n".join(lines)


def fmt_bytes(b):
    return " ".join("%02x" % c for c in b) if b else "(空)"


def render(res, args):
    L = []
    a = L.append
    a("╔══════════════════════════════════════════════════════════════════════╗")
    a("║  原始 SSE 取证（三问）：裸标签 / 重复文本 / 尾部原始字节            ║")
    a("╚══════════════════════════════════════════════════════════════════════╝")
    a("")
    a("【0. 这次的请求与响应】")
    a("  URL            : http://%s:%d%s" % (args.host, args.port, args.path))
    a("  model          : %s" % res["model"])
    a("  think 开关      : %s" % res["think_flag"])
    a("  stop           : %s" % (json.dumps(res["stops"], ensure_ascii=False) if res["stops"] else "(未带 stop)"))
    a("  HTTP 状态       : %s" % res["status"])
    a("  Transfer-Encoding: %s" % (res["transfer_encoding"] or "(无)"))
    a("  原始字节数      : %d（HTTP 头 %d + 体 %d）" % (len(res["raw"]), len(res["head"]), len(res["body"])))
    a("  TCP 到达次数    : %d（每行是一次 recv：偏移 / 字节数）" % len(res["arrivals"]))
    for t, off, b in res["arrivals"][:40]:
        a("      off=%-8d len=%d" % (off, len(b)))
    if len(res["arrivals"]) > 40:
        a("      ...（还有 %d 次）" % (len(res["arrivals"]) - 40))
    a("  chunked 分帧数  : %d" % len(res["chunks"]))
    if res["chunks"]:
        sizes = [n for n, _ in res["chunks"]]
        a("      前 24 帧大小: %s" % sizes[:24])
    a("  SSE 事件数      : %d（其中 data %d 条）" % (len(res["events"]), res["n_data"]))
    a("  请求体          : %s" % json.dumps(res["request"], ensure_ascii=False)[:400])
    a("")

    if res["warns"]:
        a("  ⚠ 解析告警：")
        for w in res["warns"]:
            a("      " + w)
        a("")

    # ── 一问 ──
    a("【1. content / reasoning_content 里有没有裸标签】")
    a("    content 拼接值 (%d 字符):" % len(res["content"]))
    a("      %s" % (res["content"][:400] or "(空)"))
    if len(res["content"]) > 400:
        a("      ...（总长 %d，见 .raw / .json）" % len(res["content"]))
    a("    reasoning_content 拼接值 (%d 字符):" % len(res["reasoning"]))
    a("      %s" % (res["reasoning"][:400] or "(空)"))
    if res["bare"]:
        a("    %s发现 %d 处裸标签%s" % (C_RED, len(res["bare"]), C_RST))
        for h in res["bare"][:30]:
            a("      event#%d %s 命中 %r @%d  上下文=%r"
              % (h["event"], h["field"], h["tag"], h["at"], h["context"]))
    else:
        a("    %s两个字段的值里都没有裸标签%s（→ 客户端不该自己再识别出标签）" % (C_GREEN, C_RST))
    a("")

    # ── 二问 ──
    a("【2. 同一段文本有没有出现两次】")
    d = res["dup"]
    a("    逐帧内容（前 30 帧，空帧省略）：")
    shown = 0
    for e in res["events"]:
        if e.get("kind") != "data":
            continue
        if not (e.get("content") or e.get("reasoning")):
            continue
        tag = "reasoning" if e.get("reasoning") else "content"
        v = e.get("reasoning") or e.get("content")
        a("      #%-3d %-9s %r" % (e["idx"], tag, v[:70]))
        shown += 1
        if shown >= 30:
            a("      ...（更多见 .json）")
            break
    if not shown:
        a("      (没有任何非空内容帧)")

    if d["adjacent_exact"] or d["cumulative"] or d["short_dup"] or d["whole_twice"]:
        a("    %s有重复迹象%s" % (C_RED, C_RST))
        for x in d["adjacent_exact"]:
            a("      · [形状A 服务端重发] %s：event #%d..#%d 连续 %d 帧**完全相等**，内容=%r"
              % (x["field"], x["from_event"], x["to_event"], x["times"], x["text"]))
        for x in d["cumulative"]:
            a("      · [形状B 累计式拼接] %s：event #%d..#%d，每帧都以上一帧整体为前缀"
              % (x["field"], x["events"][0], x["events"][1]))
            a("        首帧=%r，末帧长=%d" % (x["first_frame"], x["last_frame_len"]))
        for x in d["short_dup"][:20]:
            a("      · [形状C 短串连排] %s：%r 连排，上下文=%r"
              % (x["field"], x["unit"], x["context"]))
        for x in d["whole_twice"][:10]:
            a("      · [整条 data 载荷重复] event #%d 与 #%d 原文相同：%r"
              % (x["first"], x["again"], x["payload"]))
    else:
        a("    %s没有重复迹象%s（相邻帧不等、无累计式重发、无短串连排）" % (C_GREEN, C_RST))
    a("")

    # ── 三问 ──
    a("【3. 输出尾部的原始字节（乱码是不是 U+FFFD）】")
    t = res["tail"]
    a("    尾部 %d 个字符: %s" % (args.tail, t["tail_text_repr"]))
    a("    它们的 UTF-8 字节: %s" % fmt_bytes(t["tail_bytes"]))
    a("    响应体尾部 %d 字节（含框架）hexdump:" % min(len(t["body_tail_raw"]), args.tail * 4))
    a(hexdump(t["body_tail_raw"][-args.tail * 4:], base=max(0, len(res["body"]) - args.tail * 4)))
    a("    响应体里 U+FFFD 字节(EF BF BD)出现次数: %d" % t["fffd_in_body"])
    if t.get("boundary_cuts"):
        a("    %s发现 %d 处「帧边界落在多字节字符内部」%s —— 典型的"
          % (C_YEL, len(t["boundary_cuts"]), C_RST))
        a("      暂扣/缓冲边界切碎 UTF-8 的形状（整包**可能仍然合法**，所以只报黄）：")
        for h in t["boundary_cuts"][:8]:
            a("        帧 #%d 偏移 %d：%s —— %s"
              % (h["frame"], h["at"], h["bytes"], h["kind"]))
        a("      注意：客户端是一层层 buffer 的，帧边界未必等于服务端切点；")
        a("      要定性请看上面报出来的原始字节。")
    if t["invalid_utf8_at"] is not None:
        a("    %s响应体在偏移 %d 处**不是合法 UTF-8**%s；该处字节: %s"
          % (C_RED, t["invalid_utf8_at"], C_RST,
             fmt_bytes(res["body"][t["invalid_utf8_at"]:t["invalid_utf8_at"] + 12])))
        a("      → 这是【服务端把字符切碎了发】的形状（客户端才把它变成 �）；")
        a("        若 U+FFFD 计数也为 0，说明乱码是**解码层造的**，不是服务端发的。")
    elif t["fffd_in_body"]:
        a("    %s服务端真的发了 %d 个 EF BF BD%s —— 即服务端自己已经替换过，"
          % (C_RED, t["fffd_in_body"], C_RST))
        a("      不是客户端解码造的。")
    else:
        a("    %s响应体是合法 UTF-8，且不含 U+FFFD%s —— 这份抓包里没有乱码证据。"
          % (C_GREEN, C_RST))
    a("")
    a("【结论一句话】")
    cut = t.get("boundary_cuts") or []
    if t["fffd_in_body"] or t["invalid_utf8_at"] is not None:
        mojibake = "有"
    elif cut:
        mojibake = "可疑"          # 整包合法，但帧边界切进了字符内部
    else:
        mojibake = "无"
    a("  裸标签: %s   重复: %s   乱码: %s"
      % ("有" if res["bare"] else "无",
         "有" if (d["adjacent_exact"] or d["cumulative"] or d["short_dup"] or d["whole_twice"]) else "无",
         mojibake))
    if cut and mojibake == "可疑":
        a("  ↑「可疑」= 整包是合法 UTF-8，但帧边界切进了多字节字符内部；")
        a("    真机上这常常就是 U+FFFD 的来源（残骸在下游被替换掉了）。")
    a("")
    a("（原始字节与完整字段在 %s / %s）" % (res["path_raw"], res["path_json"]))
    return "\n".join(L)


# ────────────────────────────── 主流程 ──────────────────────────────

# 默认 prompt 的**唯一**要求：能让模型说满一段话，并且**绝不包含 `<think>` 本身**。
#
# ⚠ 这里踩过一个坑，别再改回去：第一版默认 prompt 是
#     "…然后在结尾单独写一行 <think>你好</think> 这两个标签本身，不要省略。"
#   想法是"逼模型把标签吐出来，好查裸标签"。**这是自污染**：
#
#   · 模板对 user 消息是**原样拼接**的（`minicpm5_fixture/chat_template.jinja:41-43`，
#     `'<|im_start|>user\n' + content + '<|im_end|>'`），不做任何转义；
#   · 于是字面量 `<think>` / `</think>` 进了 **prompt 的 user 段**，被模型当成
#     **真标签**而不是"要复述的文本"；
#   · 再叠上生成后缀自带的 `<think>\n`，prompt 里就有**两个 `<think>`**
#     —— 正好是工单里要查的那个形状，但它是**脚本自己造出来的**，查不出服务端的问题。
#
# 取证脚本一旦自己制造待查形状，报告就不可信（这一版就是这样：报告里 `[Unfinished]`
# 那处占位符就是模型被污染后的产物）。所以默认 prompt 必须**中立**。
DEFAULT_PROMPT = ("请用一个段落简单介绍你自己，并说明你跑在哪里；"
                  "如果你在推理，请把推理过程也一并展开写全，不要省略。")


def build_request(args, model):
    body = {
        "model": model,
        "messages": [{"role": "user", "content": args.prompt}],
        "stream": True,
        "max_tokens": args.max_tokens,
        "temperature": args.temperature,
    }
    if args.think:
        body["enable_thinking"] = True
        body["chat_template_kwargs"] = {"enable_thinking": True}
    elif args.no_think:
        body["enable_thinking"] = False
        body["chat_template_kwargs"] = {"enable_thinking": False}
    if args.stop:
        body["stop"] = args.stop
    return body


def detect_model(host, port, timeout):
    try:
        raw = http_get(host, port, "/v1/models", timeout)
        i = raw.find(b"\r\n\r\n")
        j = json.loads(raw[i + 4:].decode("utf-8", "replace"))
        data = j.get("data") or []
        if data:
            return data[0].get("id") or "unknown"
    except Exception:        # noqa: BLE001
        pass
    return "unknown"


def main():
    ap = argparse.ArgumentParser(add_help=True, description="原始 SSE 取证三问")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8083)
    ap.add_argument("--path", default="/v1/chat/completions")
    ap.add_argument("--model", default="")
    ap.add_argument("--prompt", default=DEFAULT_PROMPT,
                    help="自定义 prompt。**不能含 <think> 等裸标签**：模板对 user 消息"
                         "原样拼接，字面标签会被模型当真标签读，报告会变成脚本自造的形状")
    ap.add_argument("--think", action="store_true")
    ap.add_argument("--no-think", action="store_true", dest="no_think")
    ap.add_argument("--stop", action="append", default=[])
    ap.add_argument("--max-tokens", type=int, default=512, dest="max_tokens")
    ap.add_argument("--temperature", type=float, default=0.0)
    ap.add_argument("--timeout", type=float, default=180.0)
    ap.add_argument("--out-dir", default="", dest="out_dir")
    ap.add_argument("--label", default="")
    ap.add_argument("--tail", type=int, default=96)
    ap.add_argument("--from-file", default="", dest="from_file")
    ap.add_argument("--print-curl", action="store_true", dest="print_curl")
    args = ap.parse_args()

    if args.think and args.no_think:
        print("--think 与 --no-think 不能同时给", file=sys.stderr)
        return 2

    # 自污染拦截：prompt 里带字面标签 → 报告不可信，宁可拒跑也不出假结论。
    # 这不是"用户用错参数"，而是**脚本会自己造出待查的形状**（见 DEFAULT_PROMPT 上方的注释）。
    poll = self_pollution_hits(args.prompt)
    if poll:
        print("!! prompt 里出现字面标签 %s —— 这会让模型把它当**真标签**读，"
              % ", ".join(repr(t) for t in sorted(set(poll))), file=sys.stderr)
        print("   而不是要它复述的文本；报告会变成「脚本自己造的形状」，查不出服务端问题。",
              file=sys.stderr)
        print("   请去掉 prompt 里的 %s，或直接用默认 prompt（不带 --prompt）。"
              % "/".join(repr(t) for t in sorted(set(poll))), file=sys.stderr)
        return 2

    model = args.model or detect_model(args.host, args.port, min(args.timeout, 10))
    if args.label:
        model = model      # label 只影响文件名，不动 model

    out = args.out_dir or script_dir()
    try:
        os.makedirs(out, exist_ok=True)
    except Exception as e:       # noqa: BLE001
        print("输出目录不可写：%s（%s）" % (out, e), file=sys.stderr)
        return 2

    if args.print_curl:
        body = build_request(args, model)
        print("curl -N -s http://%s:%d%s \\" % (args.host, args.port, args.path))
        print("  -H 'Content-Type: application/json' \\")
        print("  -d '%s'" % json.dumps(body, ensure_ascii=False))
        return 0

    res = {"model": model, "stops": args.stop, "think_flag":
           ("on" if args.think else ("off" if args.no_think else "(未指定)")),
           "path_raw": "", "path_json": ""}

    if args.from_file:
        try:
            with open(args.from_file, "rb") as f:
                blob = f.read()
        except Exception as e:   # noqa: BLE001
            print("读不了 %s：%s" % (args.from_file, e), file=sys.stderr)
            return 2
        r = RawResp()
        r.raw = blob
        if blob.startswith(b"HTTP/"):
            split_http(r)
        else:
            r.body = blob

        res["request"] = {"note": "来自 %s，不是本次发的" % args.from_file}
    else:
        body = build_request(args, model)
        body_bytes = json.dumps(body, ensure_ascii=False).encode("utf-8")
        res["request"] = json.loads(body_bytes.decode("utf-8"))
        try:
            r = http_post_raw(args.host, args.port, args.path, body_bytes, args.timeout)
        except Exception as e:   # noqa: BLE001
            print("连不上 http://%s:%d：%s" % (args.host, args.port, e), file=sys.stderr)
            print("  · 手机端要在 App 里「启动服务」；", file=sys.stderr)
            print("  · 若从别的设备连，需打开「局域网访问」（否则只绑 127.0.0.1）；", file=sys.stderr)
            print("  · 端口以 App 设置与通知为准。", file=sys.stderr)
            return 2
        if r.raw and not r.head:
            split_http(r)

    analyze_bytes(r)
    events, warns = parse_sse(r.body)
    n_data = sum(1 for e in events if e.get("kind") == "data")

    content = "".join(e.get("content") or "" for e in events if e.get("kind") == "data")
    reasoning = "".join(e.get("reasoning") or "" for e in events if e.get("kind") == "data")

    res.update({
        "status": r.status, "headers": r.headers,
        "transfer_encoding": r.headers.get("transfer-encoding"),
        "raw": r.raw, "head": r.head, "body": r.body,
        "arrivals": r.arrivals, "chunks": r.chunks,
        "events": events, "n_data": n_data, "warns": warns,
        "content": content, "reasoning": reasoning,
        "bare": q1_bare_tags(events), "dup": q2_duplication(events),
    })
    tail = q3_tail(r.body, content + "", args.tail, r.frame_bytes)
    tail["invalid_utf8_at"] = r.decode_error_at
    res["tail"] = tail

    ts = time.strftime("%Y%m%d-%H%M%S")
    tag = ("-" + args.label) if args.label else ""
    base = os.path.join(out, "sse-%s%s" % (ts, tag))
    res["path_raw"], res["path_json"] = base + ".raw", base + ".json"
    with open(base + ".raw", "wb") as f:
        f.write(r.raw if r.raw else r.body)

    report = render(res, args)
    with open(base + ".report.txt", "w") as f:
        f.write(report + "\n")
    slim = dict(res)
    for k in ("raw", "head", "body"):
        slim[k] = "...（见 .raw）"
    slim["arrivals"] = [(round(t, 3), off, len(b)) for t, off, b in res["arrivals"]]
    slim["chunks"] = [n for n, _ in res["chunks"]]
    slim["tail"] = dict(res["tail"])
    slim["tail"]["body_tail_raw"] = res["tail"]["body_tail_raw"].hex()
    slim["tail"]["tail_bytes"] = res["tail"]["tail_bytes"].hex()
    with open(base + ".json", "w") as f:
        json.dump(slim, f, ensure_ascii=False, indent=2)

    print(report)
    print("\n已写入：\n  %s\n  %s\n  %s" % (base + ".raw", base + ".report.txt", base + ".json"))

    bad = bool(res["bare"] or res["dup"]["adjacent_exact"] or res["dup"]["cumulative"]
               or res["dup"]["short_dup"] or res["dup"]["whole_twice"]
               or tail["fffd_in_body"] or tail["invalid_utf8_at"] is not None)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
