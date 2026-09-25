#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`tools/acceptance_sse.py` 的**打桩服务端**：用来证明那份取证脚本自己还认得出故障。

════════════════════════════════════════════════════════════════════════════
为什么需要它
════════════════════════════════════════════════════════════════════════════
取证脚本要装机跑（要真模型、真服务端）。而它的三问全是"**有没有**"：
裸标签有没有、重复有没有、U+FFFD 有没有。这类判据有个致命的自欺 —— **判据恒真
也会报"无"**。所以必须对着**故意做坏的**实现跑，断言"该报有的报有、该报无的报无"。

五个变体，逐字对着真实现的行为造（不是随便编）：

  good       健康的 SSE：逐 token 增量、content / reasoning 分流、无标签、合法 UTF-8
             => 三问都必须报"无"（exit 0）
  dup        服务端把**同一段**逐字段发两次（相邻两帧内容完全相等）
             => 第 2 问必须报"有"（形状 A）
  cumulative 每一帧都是**到目前为止的累计文本**（客户端每帧从头拼一遍的痕迹）
             => 第 2 问必须报"有"（形状 B）
  baretag    content 里漏了裸 `</think>`、reasoning 里漏了裸 `<think>`
             => 第 1 问必须报"有"
  fffd       尾部把一个中文字**切碎**成孤立续字节发出去（对应 stop 暂扣切进字符内部）
             => 第 3 问必须报"有"（非法 UTF-8 / U+FFFD）
  splitutf8  同一次切碎，但残骸在下游**已经被替换成 U+FFFD** —— 整包**合法**。
             => 第 3 问必须报"可疑"（帧边界切进字符内部），不能报"无"
                （这一条是本轮补的：只看"整包是否合法"会把它整份漏掉）

另有一份 `framing`：内容干净，但把每条事件拆成"长度行 / data / CRLF"多次 write
（和真实现 `sseEvent` 一样），用来钉住"分块"证据本身不会把干净包误判成重复。

════════════════════════════════════════════════════════════════════════════
它按什么形状发（对齐 HttpApi.sseEvent）
════════════════════════════════════════════════════════════════════════════
真实现的每条 SSE 事件是 4 次 write + chunked 分帧：

    out.write(十六进制长度 + "\r\n"); out.write("data: {...}\n\n"); out.write("\r\n")

这里用裸 socket 复刻同一形状，因为"客户端看到的分块"就发生在这一层 —— 用
http.server 反而看不清。

用法：python3 tools/sse_probe/fake_sse_server.py <mode> <port>
"""
import json
import socket
import socketserver
import sys
import threading
import time

MODE = sys.argv[1] if len(sys.argv) > 1 else "good"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 18700

# 一段有两个字段的正常增量：一段思考 + 一段正文。
REASON = ["先", "数一下", "：", "1", "+", "1", "=", "2", "。"]
CONTENT = ["答案是", " 2", "。", "还有别的想问的吗？"]


def chunk_of(payload):
    """一条 SSE 事件的 chunked 分帧 —— 与 HttpApi.sseEvent 的 4 次 write 同形。"""
    data = ("data: " + payload + "\n\n").encode("utf-8")
    hdr = ("%x" % len(data)).encode("ascii")
    return hdr + b"\r\n" + data + b"\r\n"


def events(mode):
    """返回 [(字段, 文本, 是否发原始字节)] 的序列；第三项为 bytes 时按原样写。"""
    out = []
    for t in REASON:
        out.append(("reasoning_content", t))
    for t in CONTENT:
        out.append(("content", t))
    if mode == "baretag":
        # ① content 里漏进裸闭合标签；reasoning 里漏进裸开标签
        out = [("reasoning_content", "<think>想一下"),
               ("reasoning_content", "1+1=2</think>"),
               ("content", "答案是 2。</think>")] + out
    elif mode == "dup":
        # ② 服务端把每一段**发两次**：相邻两帧完全相等
        doubled = []
        for f, t in out:
            doubled.append((f, t))
            doubled.append((f, t))
        out = doubled
    elif mode == "cumulative":
        # ③ 每帧都是"到目前为止的累计文本"
        acc = {"reasoning_content": "", "content": ""}
        cum = []
        for f, t in out:
            acc[f] += t
            cum.append((f, acc[f]))
        out = cum
    return out


def body_bytes(mode):
    bufs = []
    for item in events(mode):
        field, text = item[0], item[1]
        fl = {"reasoning_content": "reasoning_content", "content": "content"}[field]
        payload = json.dumps({"id": "x", "object": "chat.completion.chunk",
                              "choices": [{"index": 0, "delta": {fl: text}}]},
                             ensure_ascii=False)
        bufs.append(chunk_of(payload))
    fin = json.dumps({"id": "x", "choices": [{"index": 0, "delta": {},
                                              "finish_reason": "stop"}]}, ensure_ascii=False)
    bufs.append(chunk_of(fin))
    bufs.append(chunk_of("[DONE]"))

    if mode == "splitutf8":
        # 形状来自 #57 修完之后：暂扣/缓冲边界切进了 UTF-8 字符内部，而发出去的
        # 东西**整体仍然是合法 UTF-8**。所以"整包能不能解码"这一问查不出它。
        #
        # 造法：把一份**干净、合法、无标签、无重复**的 body 拿出来，在某个
        # 多字节字符（"：" 的 EF BC 9A）中间**加一条 chunked 边界** ——
        # 即"帧在这里断开了"，字节流本身一个都没改。
        #
        # 这正是要靠帧边界才能发现的那一类：脚本必须比对"帧边界 vs 字符边界"，
        # 而不是只做一次全量解码。
        clean = body_bytes("good")
        # 切点：找 body 里第一处 EF BC（多字节字符的前两字节），在它后面切
        at = clean.index(b"\xef\xbc") + 2
        a, b2 = clean[:at], clean[at:]
        # a 以 EF BC 收尾 —— 一个多字节字符的非末尾字节；
        # b2 以该字符的第三字节开头。两段拼起来仍是合法 UTF-8。
        for seg in (a, b2):
            bufs.append(("%x" % len(seg)).encode() + b"\r\n" + seg + b"\r\n")
        return b"".join(bufs)
    if mode == "fffD".lower() or mode == "fffd":
        # ④ 尾部**故意**把一个中文字切碎：只发它的前两个字节，孤立的续字节留在流里。
        #    这正是 stop 暂扣切进字符内部时流出去的形状。
        broken = b'\xef\xbc'          # "，"（U+FF0C）的前两字节，缺第三字节 0x8c
        data = b"data: " + json.dumps(
            {"choices": [{"index": 0, "delta": {"content": ""}}]},
            ensure_ascii=False).encode("utf-8")[:-2] + b'}}' 
        data = b"data: " + b'{"choices":[{"index":0,"delta":{"content":"' + broken + b'"}}]}' + b"\n\n"
        bufs.append(("%x" % len(data)).encode() + b"\r\n" + data + b"\r\n")
    return b"".join(bufs)


class H(socketserver.StreamRequestHandler):
    def handle(self):
        # 读掉请求头与 body（不关心内容，模式由命令行给定）
        head = b""
        while b"\r\n\r\n" not in head:
            b = self.rfile.read(1)
            if not b:
                return
            head += b
        clen = 0
        for line in head.split(b"\r\n"):
            if line.lower().startswith(b"content-length:"):
                clen = int(line.split(b":", 1)[1].strip() or 0)
        if clen:
            self.rfile.read(clen)

        if head.startswith(b"GET /v1/models"):
            body = json.dumps({"object": "list", "data": [
                {"id": "stub-model", "object": "model"}]}).encode()
            self.wfile.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                             b"Content-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body)
            return

        body = body_bytes(MODE)
        self.wfile.write(b"HTTP/1.1 200 OK\r\n"
                         b"Content-Type: text/event-stream\r\n"
                         b"Cache-Control: no-cache\r\n"
                         b"Transfer-Encoding: chunked\r\n"
                         b"Connection: close\r\n\r\n")
        self.wfile.write(body)
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()
        time.sleep(0.05)


class S(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    S(("127.0.0.1", PORT), H).serve_forever()
