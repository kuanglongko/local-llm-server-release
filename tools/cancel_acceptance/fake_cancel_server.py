#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验收脚本的**打桩服务端**：用来证明 tools/acceptance_cancel.py 自己还认得出故障。

════════════════════════════════════════════════════════════════════════════
为什么需要它（与 tools/stop_acceptance 同一个理由）
════════════════════════════════════════════════════════════════════════════
`acceptance_cancel.py` 要装机跑，它的核心判据是**否定式**的：

  · 「客户端断开后输出没跑满 max_tokens」
  · 「`/v1/abort` 之后生成提前收尾」

这类断言有一个致命的自欺：**判据恒真也会全绿**。一个**完全不实现取消**的服务端，
只要模型短答（或 max_tokens 设得比实际输出小），"没跑满"就永远成立、脚本全绿。
第 47 行的对照组（不取消时必须跑满）就是为此设的，但它本身也需要被验证 ——
拿一个"连对照组都不满足"的桩跑一遍，断言脚本会**降级为 WARN 而不是报绿**。

第 2 种失效模式是**假红**：把正确实现判成坏的。取消这条链路上最容易假红的是
「客户端断开方式的差异」——正常 `close()`（FIN）与 `SO_LINGER=0`（RST）在
服务端表现为**两种不同的信号**（读得 EOF / 写抛异常），只处理一种的实现在
另一种上会被验收脚本判成"没发现断开"。所以两种都有独立的桩。

于是四种实现都做成可复现的桩，由 tools/run_cancel_acceptance_tests.sh 断言：

    good      —— 语义正确：写探测（写合法 SSE 注释帧，写失败即 GONE）、
                 /v1/abort 停当前轮次、收尾记日志
                 => 必须全绿
    noabort   —— `/v1/abort` 回 200 但**不停**任何东西（取消端点的"假成功"）
                 => 必须有 FAIL
    nogone    —— 探测**只写不判**：写得出去就永远当活着，漏掉"写失败"这条路
                 => disconnect-* 至少一组变红
    naked     —— 探测**裸写 0x00**（不按 chunked 分帧）：客户端读长度行拿到 0x0 断流
                 => 客户端侧必须看出流被破坏（这是 2026-09-20 真机事故的复现桩）
    shortans  —— 对照组不成立（模型短答）：取消判据全部降级为 WARN，**不许报绿**
                 => 不得出现 FAIL，但 WARN 必须 > 0

════════════════════════════════════════════════════════════════════════════
它按什么语义实现（不是随便编的）
════════════════════════════════════════════════════════════════════════════
逐字对照仓库里三份真实现：

  · 归属判定   —— `RequestCancel.enter/leave/cancelCurrent`：单实例、单并发，
                  取消永远打「当前轮次」；
  · 断连探测   —— `RequestCancel.probe`/`classify`：**只写不读**。探测载体是一帧
                  合法 SSE 注释（走 chunked 分帧），写出去了 -> ALIVE，写抛异常
                  -> GONE。**没有 UNKNOWN 档**（不读就没有"没结论"），也**不许裸写
                  单个字节**（那会破坏 chunked 分帧，客户端读长度行拿到 0x0 断流）；
  · 收尾与日志 —— `HttpApi` 的两行：`abort: 取消当前生成轮次（来源=http）` 与
                  `生成已被取消（客户端断连或 /v1/abort）：已生成 N tok`。

用法：python3 tools/cancel_acceptance/fake_cancel_server.py <good|noabort|nogone|naked|shortans> <port> [logfile]
"""
import http.server, json, os, socket, socketserver, sys, threading, time

MODE = sys.argv[1] if len(sys.argv) > 1 else "good"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 18081
LOGFILE = sys.argv[3] if len(sys.argv) > 3 else ""

_lock = threading.Lock()
_current = None           # 当前"轮次"：None = 没有在跑


def log(line):
    """把判据依赖的那几行写进日志文件（真机上由 LlmEngine 写进会话日志）。"""
    print(f"[mock] {line}", flush=True)
    if LOGFILE:
        with _lock:
            with open(LOGFILE, "a", encoding="utf-8") as f:
                f.write(f"[http] {line}\n")


class Round:
    def __init__(self):
        self.cancelled = False
        self.stop = threading.Event()


def begin():
    global _current
    with _lock:
        r = Round()
        _current = r
        return r


def end(r):
    global _current
    with _lock:
        if _current is r:
            _current = None


def cancel_current(source):
    """取消当前轮次；返回是否真的取消到了（= /v1/abort 的 aborted 字段）。"""
    with _lock:
        r = _current
    if r is None:
        log("abort: 当前没有生成轮次（no-op）")
        return False
    if MODE == "noabort":
        # 坏实现：回 200 但什么都不停 —— "假成功"的取消端点。真机上的对应形状是
        # 只把 busy 置位而没通知生成循环（客户端以为停了，服务端照跑）。
        log("abort: 取消当前生成轮次（来源=http）")
        return True
    r.cancelled = True
    r.stop.set()
    log("abort: 取消当前生成轮次（来源=http）")
    return True


def classify(write_ok):
    """与 RequestCancel.classify 逐字对齐（**独立写一份**，不共用代码）。

    只剩一条规则：写出去 = ALIVE，写失败 = GONE。**没有 UNKNOWN** —— 探测不读，
    就不存在"连接还在但没话说"这一档（旧的三态表正是误杀风险源）。
    """
    return "GONE" if not write_ok else "ALIVE"


def probe(wfile, sock):
    """一次**写探测**。返回 (两态, 依据)。

    与 RequestCancel.probe 同语义：探测的载体是一帧**合法 SSE 注释**，
    所以探测量本身不会污染 `Transfer-Encoding: chunked` 的分帧。

    第二返回值把"GONE 是怎么判出来的"标出来 —— 两次写之后才失败（FIN 后首次写
    仍可能成功）标 'write2'，第一次就抛异常（RST）标 'write'。naked / nogone
    两个坏实现靠它区分。
    """
    payload = b": ping\n\n"
    frame = b"%x\r\n" % len(payload) + payload + b"\r\n"

    if MODE == "nogone":
        # 坏实现：**探测整个没接上** —— 不写、不判，永远返回 ALIVE。
        # 真机上的对应形状是"只在 sseEvent 后面补了 sock.isClosed / 什么也没接，
        # 却没把写失败（Broken pipe / RST）当信号"：客户端走了服务端一无所知，
        # 白跑完剩下的 max_tokens。
        #
        # 注意不能做成"探一次、成功就当活着"：那样在 RST 到达之后*恰好*那一次
        # 探测写就会抛异常，桩反而能抓到断开（实测会假绿）。缺陷桩必须真的不写。
        return classify(True), "noprobe"

    def one_write():
        if MODE == "naked":
            # 坏实现：只往流里裸写一个字节，**不按 chunked 分帧**。
            # 客户端解析「下一个 chunk 的长度行」时读到 0x0 ->
            # `Expected leading [0-9a-fA-F] character but was 0x0` -> 断流。
            wfile.write(b"\x00")
        else:
            wfile.write(frame)
        wfile.flush()

    try:
        one_write()
    except Exception:  # noqa: BLE001
        return "GONE", "write"

    # 第二次写：FIN 之后首次写通常成功（数据进内核缓冲），第二次才拿到 RST。
    # 这正是"写探测只能保证最终发现、不保证立刻发现"的来源（见 RequestCancel 文件头）。
    try:
        one_write()
    except Exception:  # noqa: BLE001
        return "GONE", "write2"
    return classify(True), "wrote"


class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _j(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _chunk(self, payload: bytes):
        """真机那套 `Transfer-Encoding: chunked` 分帧：`<hex长度>\\r\\n<数据>\\r\\n`。

        用真分帧而不是 HTTP/1.0 的"靠 EOF 结束"，是因为**本次事故只有在 chunked 下
        才复现**：2026-09-20 客户端报的是
        `Expected leading [0-9a-fA-F] character but was 0x0` —— 它正在解析"下一个
        chunk 的长度行"，而心跳探测（naked 桩）往里裸写了一个 0x00。
        HTTP/1.0 没有长度行，裸字节只会被当普通字节吞掉，用例就永远绿。
        走 `sseFrame` 的那些帧必须这样发；naked 桩故意**不**走这里。
        """
        self.wfile.write(b"%x\r\n" % len(payload))
        self.wfile.write(payload)
        self.wfile.write(b"\r\n")
        self.wfile.flush()

    def do_GET(self):
        if self.path.startswith("/health"):
            with _lock:
                gen = _current is not None
            return self._j(200, {"status": "ok", "model_loaded": True,
                                 "busy": False, "generating": gen})
        self._j(404, {"error": {"message": "not found"}})

    def do_POST(self):
        if self.path.startswith("/v1/abort"):
            n = int(self.headers.get("Content-Length") or 0)
            if n:
                self.rfile.read(n)
            ok = cancel_current("http")
            return self._j(200, {"aborted": ok, "generating": ok})
        if not self.path.startswith("/v1/chat/completions"):
            return self._j(404, {"error": {"message": "not found"}})
        n = int(self.headers.get("Content-Length") or 0)
        try:
            j = json.loads(self.rfile.read(n).decode("utf-8", "replace"))
        except Exception:  # noqa: BLE001
            return self._j(400, {"error": {"message": "bad json"}})
        max_tok = int(j.get("max_tokens") or 64)
        stream = bool(j.get("stream"))
        if MODE == "shortans":
            # 对照组不成立：模型只吐 3 个 token 就 EOG。此时"没跑满 max_tokens"
            # 恒真 —— 验收脚本必须**降级为 WARN** 而不是报绿，这就是这个桩要钉的。
            max_tok = 3
        r = begin()
        try:
            if not stream:
                n_tok = 0
                while n_tok < max_tok:
                    if r.cancelled:
                        break
                    n_tok += 1
                if r.cancelled:
                    log(f"生成已被取消（客户端断连或 /v1/abort）：已生成 {n_tok} tok")
                fr = "cancelled" if r.cancelled else "length"
                return self._j(200, {"choices": [{"message": {"role": "assistant", "content": "x" * n_tok},
                                                  "finish_reason": fr}],
                                     "usage": {"completion_tokens": n_tok}})
            # 流式：逐块发，边发边探。用真 chunked（见 _chunk 的注释）。
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Connection", "close")
            self.end_headers()
            n_tok = 0
            while n_tok < max_tok:
                if r.cancelled:
                    break
                piece = f"{n_tok}、"
                try:
                    self._chunk(("data: " + json.dumps(
                        {"choices": [{"delta": {"content": piece}, "finish_reason": None}]},
                        ensure_ascii=False) + "\n\n").encode())
                except Exception:  # noqa: BLE001
                    break
                n_tok += 1
                # 每块的节奏：验收用例在"服务端已开始生成"之后约 1.5s 才调 abort，
                # 所以这段 sleep 必须让 64 个块**跑得比 1.5s 久**，否则轮次在 abort
                # 之前就结束了（aborted=false，用例红）。
                # 旧版写探测带一次 50ms 读超时，天然拖慢了循环；新版不读，全靠这里。
                time.sleep(0.05)          # 让它真的"跑得完"，取消才有意义
                # 每 5 步探一次（真机是 256 步；这里输出短，密度等同）
                if n_tok % 5 == 0:
                    # 每步都探（不是隔几步探一次）：FIN 之后本端**首次写**仍可能
                    # 成功，要第二次写才拿到 RST（Linux 实测）。真机的探测间隔是
                    # 256 步，密度远低于这里 —— 但两边都是"持续探直到判出来"。
                    st, why = probe(self.wfile, self.connection)
                    # good / noabort：写探测两态 —— 写失败（RST / FIN 后的第二次写）
                    # 即 GONE；写成功即 ALIVE。没有第三态可判。
                    #
                    # nogone 坏实现：只写不判，永远返回 ALIVE —— 真机上对应的形状是
                    # 「只在 sseEvent 后面补了 sock.isClosed，却没把写失败当信号」。
                    # naked 坏实现：探测量本身把分帧写坏了 —— 客户端会因此断流，
                    # 于是"发现断开"这件事看起来**成功**了，代价却是客户端拿不到
                    # 完整响应（这正是 2026-09-20 真机的形状）。
                    if st == "GONE":
                        log("客户端已断开（写探测判定离开）：取消本轮生成，不再空转")
                        r.cancelled = True
                        break
            if not r.cancelled and max_tok > 0:
                # 未取消：正常跑满后补 finish 帧与终止块（客户端才读得到流末尾）。
                try:
                    self._chunk(("data: " + json.dumps(
                        {"choices": [{"delta": {}, "finish_reason": "length"}]}) + "\n\n").encode())
                    self._chunk(b"data: [DONE]\n\n")
                    self.wfile.write(b"0\r\n\r\n")
                    self.wfile.flush()
                except Exception:  # noqa: BLE001
                    pass
            if r.cancelled:
                # 收尾日志**先写**，再试着把 finish 块发给客户端：
                # 客户端已经断开时后面那次写必然失败，把它写在前面才是"服务端
                # 确实收尾了"的证据（真机上这两行的顺序也是日志在前）。
                log(f"生成已被取消（客户端断连或 /v1/abort）：已生成 {n_tok} tok")
                try:
                    self._chunk(("data: " + json.dumps(
                        {"choices": [{"delta": {}, "finish_reason": "cancelled"}]}) + "\n\n").encode())
                    self._chunk(b"data: [DONE]\n\n")
                    self.wfile.write(b"0\r\n\r\n")   # 终止块
                    self.wfile.flush()
                except Exception:  # noqa: BLE001
                    pass
        finally:
            end(r)


if __name__ == "__main__":
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("127.0.0.1", PORT), H) as s:
        print(f"mock({MODE}) on {PORT} log={LOGFILE!r}", flush=True)
        s.serve_forever()
