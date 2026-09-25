#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验收脚本的**打桩服务端**：用来证明 tools/acceptance_stop.py 自己还认得出故障。

════════════════════════════════════════════════════════════════════════════
为什么需要它（这是本目录存在的唯一理由）
════════════════════════════════════════════════════════════════════════════
`acceptance_stop.py` 要装机跑，它的每条判据都是「客户端**没有**看到某个串」。
这类"否定式断言"有一个致命的自欺：**判据恒真也会全绿**。真机上最典型的两种骗过
自己的方式是：

  1. 模型压根没按提示词写记号 —— 那么"记号没出现"就永远成立，脚本全绿，什么都
     没证明（所以脚本里有 preflight 对照，这里也把"模型没照做"做成一个变体）；
  2. 脚本自己写错了 —— 比如只查"记号没出现"而漏掉"输出尾部真的被切掉了"。
     一个**完全不认 stop** 的服务端，只要模型的回答恰好把记号写在最后，就能让
     这条判据成立（本文件 nosampler 变体就是这个行为）。

第 2 种只能靠"对着坏实现跑"来发现，装机跑一万次也发现不了（真机的实现是好的）。
所以把三种实现都做成可复现的桩，由 tools/run_acceptance_stop_tests.sh 断言：

    good      —— 语义正确（对照 llama_jni.cpp + StopSequences.kt）=> 必须全绿
    nosampler —— 带 stop 时静默忽略（本 PR 修掉的那个 bug）    => 必须有 FAIL
    swallow   —— 暂扣后永不放行（吞正文）                      => 必须有 FAIL
    echoraw   —— 语义**也正确**，但模型的输出会把提示词抄一遍，于是 stop 串在
                基准里**出现两次**（真机日志里 `@@END@@`（7 字）-> `@@@@ENDEND`
                （10 字）就是这种形状）。=> 也必须全绿。

                这个变体是补上的：原来自测只有上面三种，它们生成的都是"记号只
                出现一次"的干净输出，于是脚本里用 `ref.find(mark)` 取命中点的
                写法**测不出来**。真机上模型抄提示词，`find` 取到第一处，而
                服务端正确地切在第二处 -> 三条命中判据全假红（用户日志里的 4 条
                FAIL 有 3 条是这么来的）。凡属"模型行为形状"的差异都应该在这里
                打桩，而不是指望装机时撞上。

════════════════════════════════════════════════════════════════════════════
它按什么语义实现（不是随便编的）
════════════════════════════════════════════════════════════════════════════
逐字对照仓库里两份真实现：

  · 请求侧解析/校验 —— `StopSequences.fromRequest`（空串/类型/256 字节/别名并集；条数只截断不 400）；
  · 逐 token 拦截   —— `llama_jni.cpp` 的 stop 采样器 accept：
      命中 -> 结束本轮；是某条 stop 的真前缀 -> 暂扣（不下发、不进 KV）；
      其余 -> 把暂扣的连同本 token 一起放行（对应 `can_continue_fast` 为 false）。
    匹配用「已放行 + 已暂扣」的**尾部**去比 stop（`stopseq::relevant`），
    两趟扫描先命中后前缀（stop=["abc","ab"] 要按 ab 命中）。

这里按**字符**粒度模拟 token 粒度：语雀上验收的是"客户端看到什么"，
与切分粒度无关；真机的切分粒度由 tools/run_stop_sampler_tests.sh 用真 C++ 逻辑测。

用法：python3 tools/stop_acceptance/fake_stop_server.py <good|nosampler|swallow|echoraw> <port>
"""
import json, re, socketserver, http.server, sys, threading

MODE = sys.argv[1] if len(sys.argv) > 1 else "good"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 18080

# 「不吞正文」这一组的提示词有两种（见 acceptance_stop.py 的 ASK_NEVER /
# ASK_NEVER_MARK）：默认**不提** @@NEVER@@（只验"采样器没拦"= 放行路径），
# 加了 --need-never 才要求模型在文末写出来（同时验"命中 + 放行"）。
# 桩必须跟着提示词走，否则"基准里有记号、带 stop 的那次没有"会被逐字比对
# 判成吞字 —— 那是提示词不同导致的，不是服务端行为。
REPLY = "你好，我是一个完全跑在手机上的本地大模型，不联网也不上传任何数据。"
REPLY_NEVER = REPLY + "@@NEVER@@"
REPLY_END = "这一行就是你要的内容：@@END@@"
REPLY_X = "标记在这里：</end>"

def validate_stop(j):
    out = []
    for key in ("stop", "stop_sequences"):
        if key not in j or j[key] is None:
            continue
        v = j[key]
        if isinstance(v, str):
            out.append(v)
        elif isinstance(v, list):
            for e in v:
                if not isinstance(e, str):
                    return None, "stop 数组元素必须都是字符串"
                out.append(e)
        else:
            return None, "stop 必须是字符串或字符串数组"
    for s in out:
        if s == "": return None, "stop 不能包含空串（空串会匹配每个位置，等于输出恒为空）"
        if len(s.encode()) > 256: return None, "stop 单条长度不能超过 256"
    # 条数**不是**合法性边界：超内部上限只截断、不失败请求（与真实现
    # StopSequences.capped 对齐）。此前这里按 8 条 400，正是把客户端弄挂的行为。
    return out[:64], None

def gen(prompt, mode):
    if mode == "echoraw":
        # 真机形状：模型把提示词**抄进输出**。于是 stop 串在输出里出现两次
        # （提示词里那处 + 正文那处），且两处之间还有一小段文字。
        # 服务端的正确行为是"停在**最靠前**那个命中点"，命中之后一个字都不补。
        if "</end>" in prompt: return prompt + "\n好的，原样输出：" + "</end>", "</end>"
        if "@@END@@" in prompt: return prompt + "\n好的，原样输出：" + "@@END@@", "@@END@@"
        return prompt, "@@NEVER@@"
    if "@@END@@" in prompt:
        # 长文本尾部命中：先写一大段再写记号
        if "短文" in prompt:
            return ("手机本地推理的最大好处是数据不出设备：对话、语音、文档都留在本机，"
                    "没有账号、没有联网、没有遥测；离线也能用，弱网环境下不必等云端往返；"
                    "模型与参数的取舍权在自己手里，换一个 GGUF 就换一套能力。" + REPLY_END), "@@END@@"
        return REPLY_END, "@@END@@"
    if "</end>" in prompt: return REPLY_X, "</end>"
    # 只有提示词里明确要求了 @@NEVER@@，模型才写它（与真模型的行为一致）
    if "@@NEVER@@" in prompt: return REPLY_NEVER, "@@NEVER@@"
    return REPLY, "@@NEVER@@"

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _j(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code); self.send_header("Content-Type","application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def _completions(self):
        n = int(self.headers.get("Content-Length") or 0)
        j = json.loads(self.rfile.read(n).decode("utf-8", "replace"))
        stops, err = validate_stop(j)
        if stops is None:
            return self._j(400, {"error": {"message": err}})
        text = "手机本地推理的好处：数据不出设备。" + "@@END@@"
        out = text
        if MODE != "nosampler":
            for st in stops:
                i = text.find(st)
                if i >= 0:
                    out = text[:i]
                    break
        self._j(200, {"choices": [{"text": out, "finish_reason": "stop"}]})

    def do_GET(self):
        if self.path == "/health":
            self._j(200, {"status":"ok","model_loaded":True})
        else:
            self._j(404, {"error":{"message":"not found"}})
    def do_POST(self):
        if self.path == "/v1/completions":
            return self._completions()
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode("utf-8","replace")
        try: j = json.loads(raw)
        except Exception: return self._j(400, {"error":{"message":"bad json"}})
        stops, err = validate_stop(j)
        if stops is None: return self._j(400, {"error":{"message":err}})
        prompt = "".join(m.get("content","") for m in j.get("messages",[]))
        text, mark = gen(prompt, MODE)
        stream = bool(j.get("stream"))
        print(f"[mock] mode={MODE} stops={stops} stream={stream}", flush=True)
        if stops and MODE != "nosampler":
            # 对齐 llama_jni.cpp:934 —— 真机日志里那行「已挂载 stop 采样器」
            print(f"[stop] 已挂载 stop 采样器：{len(stops)} 条", flush=True)
        if not stream:
            out = text
            if mode_should_stop(MODE) and any(s in text for s in stops):
                i = hit_index(text, stops)
                if i >= 0: out = text[:i]
            if stops and MODE != "nosampler" and out != text:
                print(f"[stop] 命中 stop 序列，结束本轮生成", flush=True)
            print(f"[mock] 非流式 -> {out!r}", flush=True)
            return self._j(200, {"choices":[{"message":{"role":"assistant","content":out},
                                              "finish_reason":"stop"}]})
        self.send_response(200); self.send_header("Content-Type","text/event-stream")
        self.send_header("Connection","close"); self.end_headers()
        def w(s):
            self.wfile.write(s.encode()); self.wfile.flush()
        acc, hit = "", False
        # 对齐 llama_jni.cpp 的 stop 采样器（**以字符为"token"粒度**）：
        #   accept 拿到的 piece：把它接到"已下发"之后，拿 stop 去比**这个字符的
        #   边界**是否正好落在某条 stop 的末尾（stopseq::relevant 的语义）。
        #   命中 -> 结束本轮，**不再下发这个字符之后的东西**；
        #   是真前缀 -> 暂扣（不下发，等下一个字符）；
        #   其余 -> 连同暂扣一起放行（can_continue_fast 为 false 的那条路）。
        #
        # 这里必须是"整个已下发文本"而不是"暂扣 + 本字符"：真机的 acc 是逐
        # token 累积的完整输出，命中判定看的是输出**尾部**。早期版本只看
        # `acc + pending` 的尾部，对"提示词回显"这类形状会算错命中点
        # （echoraw 变体就是这么把这个漏洞钉出来的）。
        pending = ""
        for ch in text:
            piece = ch
            if not (mode_should_stop(MODE) and stops):
                acc += piece
                w("data: " + json.dumps({"choices": [{"delta": {"content": piece}, "finish_reason": None}]},
                                        ensure_ascii=False) + "\n\n")
                continue
            if MODE == "swallow":
                # 坏实现：只看真前缀就吞，且**永不放行** —— 对应"暂扣后没走
                # can_continue_fast 那条放行路"，正文会少一截（第 3 组必红）。
                if classify(acc + pending, piece, stops) == "hit":
                    hit = True
                    break
                if classify(acc + pending, piece, stops) == "prefix":
                    pending += piece
                continue
            have = acc + pending
            # 先判"命中"：本字符补完后，某条 stop 的**起点**（最靠前的那个）
            # 是否落在已下发文本里 —— 等价于真机里 accept 返回 hit。
            if hit_point_index(have + piece, stops) is not None:
                hit = True
                break
            if classify(have, piece, stops) == "prefix":
                pending += piece
                continue
            for c in pending + piece:
                w("data: " + json.dumps({"choices": [{"delta": {"content": c}, "finish_reason": None}]},
                                        ensure_ascii=False) + "\n\n")
            acc += pending + piece
            pending = ""
        if not hit and pending and MODE != "swallow":
            for c in pending:
                w("data: " + json.dumps({"choices": [{"delta": {"content": c}, "finish_reason": None}]},
                                        ensure_ascii=False) + "\n\n")
            acc += pending
        w('data: ' + json.dumps({"choices":[{"delta":{},"finish_reason":"stop"}]}) + "\n\n")
        w("data: [DONE]\n\n")
        print(f"[mock] 流式完成，下发 {acc!r}", flush=True)


def classify(have, piece, stops):
    """返回 'hit' / 'prefix' / 'no'，语义对齐 stop_sequences.h 的 relevant()。

    两趟扫描、顺序不能反：**先找完全命中，再找真前缀**（stop=["abc","ab"] 时要按
    ab 命中，否则会多吐 token）。
    """
    if not piece:
        return "no"
    for want_hit in (True, False):
        for s in stops:
            if not s:
                continue
            for k in range(min(len(have), len(s)), -1, -1):
                pre = have[len(have) - k:]
                if not s.startswith(pre):
                    continue
                rem = s[k:]
                if len(piece) >= len(rem):
                    if piece[:len(rem)] == rem and want_hit:
                        return "hit"
                elif not want_hit and rem.startswith(piece):
                    return "prefix"
    return "no"

def hit_point_index(gen, stops):
    """`gen` 里**最靠前**的命中点（对齐 stopseq::relevant：枚举 stop 能起于何处）。

    独立于 tools/acceptance_stop.py 的 hit_point 写一份是刻意的 —— 验收脚本与
    被打桩的实现不能共用同一段代码，否则双方一起错、测试全绿。
    """
    # 「最靠前命中点」= 对每个起点 i 枚举"从这里起的 k 个字符是否等于某条 stop 的
    # 前缀"，取最小的 i。注意一个 stop 在同一段输出里可能有**多个**起点都构成
    # 重叠（例：输出尾部 `...@@END@@\n好的，原样输出：`、stop=`@@END@@` 时，
    # i=25（整条命中）与 i=30（`@@` 是前缀）都算），所以是"先收集全部候选再取
    # min"，**不是**"每条 stop 只取最长的那段后缀" —— 后者会把 i=30 也算进去、
    # 把命中点推后 5 个字，服务端的正确输出被判成"多吐了 5 个字"（假红）。
    best = None
    n = len(gen)
    for s in stops or []:
        if not s:
            continue
        for i in range(n):
            hi = len(s) if len(s) < n - i else n - i
            for k in range(hi, 0, -1):
                if gen[i:i + k] == s[:k]:
                    if best is None or i < best:
                        best = i
                    break
    return best


def hit_index(text, stops):
    """`text` 里**最靠前**的命中点（非流式截断用）。"""
    if MODE == "nosampler":
        return -1
    # 「最靠前命中点」= 对每个起点 i 枚举"从这里起的 k 个字符是否等于某条 stop 的
    # 前缀"，取最小的 i。注意一个 stop 在同一段输出里可能有**多个**起点都构成
    # 重叠（例：输出尾部 `...@@END@@\n好的，原样输出：`、stop=`@@END@@` 时，
    # i=25（整条命中）与 i=30（`@@` 是前缀）都算），所以是"先收集全部候选再取
    # min"，**不是**"每条 stop 只取最长的那段后缀" —— 后者会把 i=30 也算进去、
    # 把命中点推后 5 个字，服务端的正确输出被判成"多吐了 5 个字"（假红）。
    best = None
    n = len(text)
    for s in stops or []:
        if not s:
            continue
        for i in range(n):
            hi = len(s) if len(s) < n - i else n - i
            for k in range(hi, 0, -1):
                if text[i:i + k] == s[:k]:
                    if best is None or i < best:
                        best = i
                    break
    return best

def mode_should_stop(m): return m != "nosampler"

if __name__ == "__main__":
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("127.0.0.1", PORT), H) as s:
        print(f"mock({MODE}) on {PORT}", flush=True)
        s.serve_forever()
