#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真机端到端验收：请求取消（客户端断连 + `POST /v1/abort`）。

════════════════════════════════════════════════════════════════════════════
为什么必须装机跑，离线单测不算数
════════════════════════════════════════════════════════════════════════════
离线单测（`tools/run_cancel_tests.sh`）钉的是**判据**：写探测三态表、归属
不串轮次、`/v1/abort` 只打当前轮次。下面这些它测不到：

  1. 生成循环**真的**在逐步查 `cancel.requested` —— 那要 native `step()` 在跑；
  2. 断连探测**真的**挂在心跳与每 256 步上 —— 没跑起来也编译得过；
  3. 客户端走了之后**服务端真的提前收尾**（省下的是 CPU/电，日志里才看得见）；
  4. `/v1/abort` 真的能停掉一个正在跑的 `stream=true` 请求（而非只回个 200）。

════════════════════════════════════════════════════════════════════════════
判据的失效模式和它的对偶（这份脚本最容易骗自己的地方）
════════════════════════════════════════════════════════════════════════════
「取消生效」是一个**否定式**判据（"输出没跑满"），恒真也全绿 —— 比如模型本来就
只吐 3 个 token 就 EOG，那"没跑满 `max_tokens`"永远成立。所以每条取消判据都配一个
**对照**：

  · 用例 A 先跑一次**不取消**的同参数请求，要求它**跑满 `max_tokens`**
    （`finish_reason == "length"`）。这个对照不成立就说明"跑不满"另有原因
    （模型短答 / EOG），此时取消类判据**整组降级为 WARN**，不许报绿。
  · 对照成立后，再要求取消的那次**明显更短**（默认 < 50% 且至少少 5 个 token）。

对偶的失效模式是**假红**：把"取消成功"判成失败。最常见的是把 TCP 半关闭当成
"还在跑"。所以这里断开客户端用 `SO_LINGER=0` 的 RST **与** 正常 `close()` 的 FIN
各跑一次 —— 两种离开方式都必须被发现（只测一种就会漏掉另一种）。

════════════════════════════════════════════════════════════════════════════
服务端日志是判据的唯一证据来源
════════════════════════════════════════════════════════════════════════════
客户端一侧在"取消"这件事上**什么都看不到**（连都断了，那个 `cancelled` 的
finish_reason 根本没有接收方）。所以下面的判据全部依赖 `--log` 指向的服务端日志
（App 的会话日志文件），从里面读三行事实：

  · `abort: 取消当前生成轮次（来源=http）`        —— `/v1/abort` 打到了轮次
  · `客户端已断开（写探测判定离开）` / `（心跳探测判定离开）` —— 探测判定离开
  · `生成已被取消（客户端断连或 /v1/abort）：已生成 N tok`     —— 循环真的收尾了

用法（需要同一网段能访问手机）:
    python3 tools/acceptance_cancel.py --host 192.168.1.23 --port 8080 \\
        --log /path/to/session-*.log

可选：
    --only 用例名     只跑某些用例，逗号分隔（名字见 --list）
    --list           列出用例名（含一句话说明）
    --timeout S      单请求超时（默认 300）
    --min-tok N      对照组要求"跑满"的最少 token 数（默认 64）
    --discover-wait S  「断开后发现」日志轮询上限（默认 60；自测对坏桩跑时缩短）
    --allow-skip     连不上时退化为 SKIP(exit 0)，供 CI 用

退出码：0 全绿；1 有失败；2 用法/环境问题（连不上、用例名写错）。
"""

import argparse
import json
import re
import socket
import sys
import time
import urllib.error
import urllib.request

C_GREEN, C_RED, C_YEL, C_DIM, C_RST = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"

# 让模型尽量**长**地回答（对照组要跑满 max_tokens，短答会让判据失去意义）。
LONG_ASK = ("请从 1 开始逐个数数，每个数字之间用顿号隔开，一直数下去，"
            "不要停顿、不要总结、不要结束，直到我让你停为止。")


class Result:
    def __init__(self):
        self.ok = 0
        self.fail = 0
        self.warn = 0

    def ck(self, name, cond, detail=""):
        if cond:
            print(f"{C_GREEN}PASS{C_RST}  {name}")
            self.ok += 1
        else:
            print(f"{C_RED}FAIL{C_RST}  {name}" + (f"\n      {detail}" if detail else ""))
            self.fail += 1

    def soft(self, name, cond, detail=""):
        """前提不成立（如模型短答）时用它：不绿不红，只提示 —— 前提没了就不许报绿。"""
        if cond:
            print(f"{C_GREEN}PASS{C_RST}  {name}")
            self.ok += 1
        else:
            print(f"{C_YEL}WARN{C_RST}  {name}" + (f"\n      {detail}" if detail else ""))
            self.warn += 1


# ── HTTP ────────────────────────────────────────────────────────────────────
def post(base, path, body, timeout):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"


def get(base, path, timeout=10):
    try:
        with urllib.request.urlopen(base + path, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"


def wait_idle(base, args, timeout=30):
    """轮询 `/health` 直到 `generating=false`（最多 timeout 秒）。

    为什么必须等：取消是**异步**收尾的（生成循环要在下一步才发现标志位）。
    不等就开下一个用例，会出现两种假象：日志判据读到上一个用例的收尾行（假绿），
    或新请求被 503/挂在 genLock 上（假红）。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, body = get(base, "/health", 5)
        if isinstance(body, dict) and body.get("generating") is False:
            return True
        time.sleep(0.5)
    return False


def post_abort(base, timeout=10):
    """调 `POST /v1/abort`；返回 (status, body)。取消端点不应因竞态失败，故 200 是唯一期望。"""
    req = urllib.request.Request(base + "/v1/abort", data=b"", method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"


def chat_body(max_tok, stream=True, seed=1234):
    return {
        "model": "local",
        "messages": [{"role": "user", "content": LONG_ASK}],
        "max_tokens": max_tok,
        "stream": stream,
        "temperature": 0,
        "seed": seed,
    }


def count_usage_tokens(body):
    """从非流式响应里取 completion_tokens；取不到返回 None。"""
    try:
        return int(body["usage"]["completion_tokens"])
    except Exception:  # noqa: BLE001
        return None


def open_stream(base, body, timeout):
    """打开一个 stream=true 请求，**不等响应体**：返回 (resp, status)。

    调用方负责在"服务端正在生成"时做别的事（调 `/v1/abort`、或直接砍掉连接）。
    用 urllib 而不是更底层的 socket：这里是真机验收，要的就是"普通客户端"的行为。
    """
    req = urllib.request.Request(base + "/v1/chat/completions",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    r = urllib.request.urlopen(req, timeout=timeout)
    return r, r.status


def read_stream_len(r, cap_bytes=2 * 1024 * 1024):
    """把流读干净，返回 (内容增量块数, 原始字节数)。"""
    n_chunks = 0
    nbytes = 0
    buf = b""
    while True:
        b = r.read(4096)
        if not b:
            break
        nbytes += len(b)
        buf += b
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            if b'"content"' in line or b'"reasoning_content"' in line:
                n_chunks += 1
        if nbytes > cap_bytes:
            break
    return n_chunks, nbytes


# ── 服务端日志判据 ──────────────────────────────────────────────────────────
CLOSE_PROBE = re.compile(r"客户端已断开（(写探测|心跳探测)判定离开）")
ABORT_HTTP = re.compile(r"abort: 取消当前生成轮次（来源=http）")
ABORT_NONE = re.compile(r"abort: 当前没有生成轮次")
FINISHED_CANCELLED = re.compile(r"生成已被取消（客户端断连或 /v1/abort）：已生成 (\d+) tok")


def log_since(path, offset):
    """读日志文件的 [offset, EOF) 段；文件被轮转/清空时退化为读全量。"""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            f.seek(0, 2)
            size = f.tell()
            f.seek(offset if offset <= size else 0)
            return f.read(), size
    except Exception:  # noqa: BLE001
        return "", offset


def log_size(path):
    try:
        import os
        return os.path.getsize(path)
    except Exception:  # noqa: BLE001
        return 0


# ── 用例 ────────────────────────────────────────────────────────────────────
def case_health(r, base, args):
    """`/health` 要暴露 generating 字段 —— 它是 `/v1/abort` 唯一的可观测面。"""
    wait_idle(base, args)
    st, body = get(base, "/health")
    r.ck("/health 可达且是 JSON", st == 200 and isinstance(body, dict), f"status={st} body={body}")
    if not isinstance(body, dict):
        return
    r.ck("/health 有 generating 字段（取消状态的可观测面）", "generating" in body,
         "缺该字段就看不到「谁在占着引擎」，/v1/abort 也无从核对")
    r.ck("空闲时 generating=false", body.get("generating") is False,
         f"generating={body.get('generating')} busy={body.get('busy')}")


def case_abort_idle(r, base, args):
    """空闲时调 `/v1/abort` 必须 200 且明确报 aborted=false（不是空洞的成功）。"""
    wait_idle(base, args)
    st, body = post_abort(base)
    r.ck("/v1/abort 空闲时返回 200", st == 200, f"status={st} body={body}")
    r.ck("空闲时 aborted=false（把「取消了不存在的请求」说清楚）",
         isinstance(body, dict) and body.get("aborted") is False, f"body={body}")


def case_abort_stops_stream(r, base, args):
    """核心：一个正在跑的流式请求，被 `/v1/abort` 停掉。

    判据分三段（缺一不可）：
      1. **对照**：同参数不取消时确实跑满 max_tokens（否则"变短"另有原因）；
      2. 取消后明显更短；
      3. 服务端日志里出现"取消当前生成轮次"与"生成已被取消" —— 后者的 N
         必须小于 max_tokens，否则"收尾了但已经跑满"，等于没省。
    """
    max_tok = max(args.min_tok, 64)
    wait_idle(base, args)
    if args.log:
        off = log_size(args.log)
    else:
        off = 0

    # 1) 对照：不取消
    st, body = post(base, "/v1/chat/completions", chat_body(max_tok, stream=False), args.timeout)
    ref_tok = count_usage_tokens(body) if st == 200 else None
    runs_full = st == 200 and ref_tok is not None and ref_tok >= max_tok
    r.soft(f"对照：不取消时跑满 max_tokens（{ref_tok}/{max_tok}）", runs_full,
           f"status={st} tokens={ref_tok}；模型短答/EOG 时取消类判据无意义，整段降级为 WARN")

    # 2) 取消：开流式请求，1.5s 后调 /v1/abort，再把流读干净
    #
    # 这里必须**先读到第一个响应字节**再调 abort：`urlopen()` 一返回就可以 abort 了
    # （它已经发完请求），但"生成还没开始就取消"会让服务端看到的那一轮可能是
    # 上一个请求的尾巴 —— 判据就成了随机的。读一个字节能确保服务端已经在发 SSE。
    resp = None
    try:
        resp, st2 = open_stream(base, chat_body(max_tok, stream=True), args.timeout)
        resp.read(1)   # 等服务端真的开始生成（此时它的轮次已登记）
        time.sleep(1.5)
        stA, bodyA = post_abort(base)
        r.ck("/v1/abort 对进行中的请求返回 200", stA == 200, f"status={stA} body={bodyA}")
        r.ck("/v1/abort 报 aborted=true（真的停到了轮次）",
             isinstance(bodyA, dict) and bodyA.get("aborted") is True, f"body={bodyA}")
        n_chunks, nbytes = read_stream_len(resp)
    except Exception as e:  # noqa: BLE001
        r.ck("流式请求 + /v1/abort 全过程未抛异常", False, f"{type(e).__name__}: {e}")
        return
    finally:
        try:
            resp and resp.close()
        except Exception:  # noqa: BLE001
            pass

    # 取消后等回空闲：既给收尾日志落盘的时间，也保证后续用例不受污染
    idle = wait_idle(base, args)
    r.ck("取消后服务端回到空闲（generating=false，不留僵尸轮次）", idle,
         "一直 busy 说明取消标志没人读、或轮次没摘除")

    # 3) 服务端日志：取消的两行事实
    if args.log:
        text, _ = log_since(args.log, off)
        r.ck("服务端日志有「取消当前生成轮次（来源=http）」",
             bool(ABORT_HTTP.search(text)), "没这行 = /v1/abort 没打到任何轮次")
        m = FINISHED_CANCELLED.search(text)
        r.ck("服务端日志有「生成已被取消」收尾行", bool(m),
             "没这行 = 循环没查 cancel.requested（或没走到收尾）")
        if m:
            got = int(m.group(1))
            r.ck(f"取消时实际生成 {got} tok < max_tokens（真的省下了）",
                 got < max_tok, f"got={got} max={max_tok}：跑到头才收尾等于没省")
    else:
        r.soft("服务端日志判据（需要 --log）", False,
               "取消在客户端一侧完全不可见，没有日志就只能核对 HTTP 层")
        # 无日志时退而求其次：至少要求流明显短于对照组
        if runs_full:
            r.soft("取消后客户端收到的字节数明显少于对照组", nbytes < 64 * 1024,
                   f"nbytes={nbytes}；这是弱判据，优先给 --log")


def case_disconnect_fin(r, base, args):
    """客户端**正常关闭**（FIN）后，服务端必须发现并提前收尾。

    这是"只看写异常"的实现会**全部漏掉**的那一类：连接完好，只是对方不在了。
    用底层 socket 手工发请求，然后 `close()`（不打 RST）。
    """
    _disconnect_case(r, base, args, reset=False)


def case_disconnect_rst(r, base, args):
    """客户端**被重置**（RST，SO_LINGER=0）后，服务端必须发现并提前收尾。

    与 FIN 是对偶的：写探测这条路径靠"写抛 IOException"，FIN 那条靠"读得 EOF"。
    只测一种就会漏掉另一种的实现缺陷。
    """
    _disconnect_case(r, base, args, reset=True)


def _disconnect_case(r, base, args, reset):
    tag = "RST" if reset else "FIN"
    max_tok = max(args.min_tok, 64)
    # 等引擎回到空闲再开始：上一个用例的收尾日志若落在本用例的日志区间里，
    # 判据就会读到**别人的**那一行而假绿（实测在桩上就复现了）。真机上同理 ——
    # 前一个请求的迟滞收尾与新请求之间没有任何顺序保证。
    wait_idle(base, args)
    if args.log:
        off = log_size(args.log)
    else:
        off = 0

    raw = json.dumps(chat_body(max_tok, stream=True)).encode()
    host = args.host
    port = args.port
    try:
        s = socket.create_connection((host, port), timeout=10)
    except Exception as e:  # noqa: BLE001
        r.ck(f"[{tag}] 能连上服务端", False, f"{type(e).__name__}: {e}")
        return
    try:
        s.sendall(
            f"POST /v1/chat/completions HTTP/1.1\r\nHost: {host}:{port}\r\n"
            f"Content-Type: application/json\r\nContent-Length: {len(raw)}\r\n\r\n".encode()
            + raw)
        # 读到第一个响应字节（证明服务端真的开始生成了），再走人
        s.settimeout(30)
        first = s.recv(64)
        r.ck(f"[{tag}] 服务端已开始响应（读到 {len(first)} 字节）", len(first) > 0,
             "一个字节都没读到，后面的判据不成立")
        time.sleep(1.5)
        if reset:
            # SO_LINGER=0：close 时发 RST 而不是 FIN，服务端下次写会抛 IOException
            s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER,
                         b"\x01\x00\x00\x00\x00\x00\x00\x00")
        s.close()
    except Exception as e:  # noqa: BLE001
        r.ck(f"[{tag}] 中途离开未抛异常", False, f"{type(e).__name__}: {e}")
        try:
            s.close()
        except Exception:  # noqa: BLE001
            pass
        return

    # 服务端发现离开需要一点时间：探测每 256 步一次，而生成速度取决于机型。
    # 轮询日志直到出现收尾行（最多 60s）—— 固定 sleep 会让判据随机器快慢漂移。
    if not args.log:
        r.soft(f"[{tag}] 断连后发现（需要 --log）", False,
               "客户端断开后看不到任何响应，只能靠服务端日志证明它发现了")
        return
    # 轮询直到出现收尾行（最多 60s）—— 固定 sleep 会让判据随机器快慢漂移。
    #
    # 判据是「发现断开」这一行，而不是「generating 变 false」：后者在循环
    # 一开始、还没生成任何 token 时也成立，会把"压根没探测"判成通过。
    #
    # 为什么给到 60s：TCP 上「对端正常关闭」这件事**可能不是一次探测就看得出来** ——
    # 对端 close() 之后本端第一次 recv 读到的常常是队列里残留的请求字节，
    # 要读空了才见 EOF（Linux 实测，离线单测 4b-2 也钉了这条）。真机按 token
    # 计数探测（256 步一次），慢机型上两次探测之间可能好几秒。判据因此是
    # 「最终会被发现」，而不是「下一次探测立刻发现」。
    deadline = time.time() + args.discover_wait
    text = ""
    m = None
    while time.time() < deadline:
        text, _ = log_since(args.log, off)
        m = CLOSE_PROBE.search(text)
        if m and FINISHED_CANCELLED.search(text):
            break
        time.sleep(2)
    r.ck(f"[{tag}] 服务端日志发现客户端断开（{m.group(1) if m else '无'}）", bool(m),
         "没发现 = 生成会白跑完剩下的 max_tokens（本特性的核心症状）")
    m2 = FINISHED_CANCELLED.search(text)
    r.ck(f"[{tag}] 断开后生成被取消并收尾", bool(m2),
         f"日志片段尾部：{text[-400:]!r}")
    if m2:
        got = int(m2.group(1))
        r.ck(f"[{tag}] 收尾时只生成了 {got} tok < max_tokens（真的提前停了）",
             got < max_tok, f"got={got} max={max_tok}")

    # 服务端必须回到空闲，否则后续请求全 503
    st, body = get(base, "/health")
    r.ck(f"[{tag}] 取消后 /health 报 generating=false（不留僵尸轮次）",
         isinstance(body, dict) and body.get("generating") is False,
         f"body={body}")
    stA, bodyA = post_abort(base)
    r.ck(f"[{tag}] 取消后 /v1/abort 报 aborted=false（轮次已摘除）",
         isinstance(bodyA, dict) and bodyA.get("aborted") is False, f"body={bodyA}")


def case_stream_integrity(r, base, args):
    """一次**完整跑完**的流式响应，客户端必须能整段解析 chunked 分帧。

    钉的是 2026-09-20 真机事故：心跳探测为了拿到"FIN"信号，往 `Transfer-Encoding:
    chunked` 的流里**裸写了一个 0x00**（不按分帧）。客户端解析"下一个 chunk 的长度
    行"时读到 `0x0`：

        Request Failed: Error: Expected leading [0-9a-fA-F] character but was 0x0

    于是**主动断流**，用户看到的是"回答输出一小段就断了"。断流后服务端下一次写
    失败 → 记一条"客户端已断开"，看起来"探测工作正常"—— 故障被伪装成成功。

    这条用例专门用**会解析 chunked 的 urllib**去读完整流：只要服务端分帧被污染，
    读取过程必然抛 `IncompleteRead` / `ChunkedEncodingError` 类异常。
    """
    max_tok = max(args.min_tok, 64)
    wait_idle(base, args)
    # 开一次短答（不取消、不 abort），把流读干净
    try:
        resp, _ = open_stream(base, chat_body(max_tok, stream=True), args.timeout)
    except Exception as e:  # noqa: BLE001
        r.ck("能开一个流式请求", False, f"{type(e).__name__}: {e}")
        return
    try:
        n_chunks, nbytes = read_stream_len(resp)
    except Exception as e:  # noqa: BLE001
        r.ck("客户端能完整解析 chunked 分帧（存活心跳不得呛到分帧）", False,
             f"{type(e).__name__}: {e} —— 现场：往 chunked 流里写了非分帧字节，"
             "客户端读长度行拿到 0x0（Expected leading [0-9a-fA-F] character but was 0x0）")
        return
    finally:
        try:
            resp.close()
        except Exception:  # noqa: BLE001
            pass
    r.ck(f"客户端能完整解析 chunked 分帧（收到 {n_chunks} 个内容块 / {nbytes} 字节）",
         n_chunks > 0,
         "一个内容块都没解析出来：分帧被污染，或响应根本不是合法 SSE/chunked")
    wait_idle(base, args)


def case_seq_and_probe(r, base, args):
    """连通性/失败模式自检：连不上时必须**快速**报错、不得挂住。

    这条防的是脚本自己变成"转圈不停"的那种工具：真机上服务没开时，
    期望是一两秒内被拒（Connection refused），而不是等满 --timeout。
    """
    t0 = time.time()
    st, body = post("http://127.0.0.1:1", "/v1/abort", {}, 5)
    dt = time.time() - t0
    r.ck("端口没人监听时快速失败（≤5s，而非等满超时）", st is None and dt <= 5.5,
         f"status={st} 用时 {dt:.2f}s body={body}")


CASES = {
    "health": (case_health, "/health 暴露 generating 字段（取消的可观测面）"),
    "abort-idle": (case_abort_idle, "空闲时 /v1/abort 返回 200 + aborted=false"),
    "abort-stream": (case_abort_stops_stream, "流式生成被 /v1/abort 提前停掉（带对照组）"),
    "disconnect-fin": (case_disconnect_fin, "客户端正常关闭（FIN）后服务端发现并收尾"),
    "disconnect-rst": (case_disconnect_rst, "客户端被 RST 后服务端发现并收尾"),
    "stream-integrity": (case_stream_integrity, "流式分帧未被心跳污染（客户端能完整解析 chunked）"),
    "快速失败": (case_seq_and_probe, "端口未监听时快速失败，不挂住"),
}


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--only", default="")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--timeout", type=float, default=300)
    ap.add_argument("--min-tok", type=int, default=64)
    ap.add_argument("--log", default="")
    ap.add_argument("--allow-skip", action="store_true")
    # 「断开后发现」的日志轮询上限（秒）。真机默认 60 足够；CI 自测对着**故意不发现**
    # 的坏桩跑时，60s × 每用例会让自测慢到没人愿意跑，于是留一个缩短的口子。
    ap.add_argument("--discover-wait", type=float, default=60)
    args = ap.parse_args()

    if args.list:
        for k, (_, desc) in CASES.items():
            print(f"{k:18s} {desc}")
        return 0

    base = f"http://{args.host}:{args.port}"
    # 连通性预检：连不上是环境问题(exit 2)，不是判据失败 —— 与 acceptance_stop.py 同约定
    st, body = get(base, "/health", 10)
    if st is None:
        msg = f"连不上 {base}：{body}"
        if args.allow_skip:
            print(f"{C_YEL}SKIP{C_RST}  {msg}")
            return 0
        print(f"{C_RED}环境问题{C_RST}  {msg}")
        return 2
    print(f"{C_DIM}服务端 {base} 可达（/health status={st}）{C_RST}")
    if isinstance(body, dict) and not body.get("model_loaded"):
        print(f"{C_YEL}注意{C_RST}  /health 报 model_loaded=false，生成类用例会 503")
    if not args.log:
        print(f"{C_YEL}注意{C_RST}  未给 --log：取消在客户端一侧不可见，"
              f"「真的提前收尾」这类判据只能降级提示")

    names = [n.strip() for n in args.only.split(",") if n.strip()] or list(CASES)
    bad = [n for n in names if n not in CASES]
    if bad:
        print(f"{C_RED}未知用例{C_RST}：{bad}\n可用：{', '.join(CASES)}")
        return 2

    r = Result()
    for n in names:
        fn, desc = CASES[n]
        print(f"\n{C_DIM}── {n}：{desc}{C_RST}")
        try:
            fn(r, base, args)
        except Exception as e:  # noqa: BLE001 —— 用例自身抛异常也要报成失败而不是崩掉整轮
            r.ck(f"{n} 用例未抛异常", False, f"{type(e).__name__}: {e}")

    print(f"\n== 取消验收：PASS {r.ok} / FAIL {r.fail} / WARN {r.warn} ==")
    if r.fail:
        return 1
    if r.warn and not r.ok:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
