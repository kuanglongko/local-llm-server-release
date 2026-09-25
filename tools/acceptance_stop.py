#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真机端到端验收：OpenAI `stop` / `stop_sequences` 停止序列（PR #41）。

════════════════════════════════════════════════════════════════════════════
为什么需要一个「真机」脚本，而离线单测不算数
════════════════════════════════════════════════════════════════════════════
本 PR 的拦截在 native 侧（llama_jni.cpp 的 `local-stop` 采样器，逐 token 判）。
离线单测跑的是**同一份** stop_sequences.h 的匹配语义，测不到下面这些：

  1. 采样器到底有没有挂到 vendor 预编译库的采样链上 —— 依赖
     `llama_sampler_chain_*` / `llama_sampler_init` 这些符号，CI 的宿主侧没有 libllama；
  2. 「暂扣 → 放行」这条路径真的跑起来了没有 —— 它只在**词表拼不出 continuation**
     时才触发，跟具体模型/词表绑定，宿主侧没有词表；
  3. 流式下 stop 之后的内容到底有没有被发出去 —— 这是本 PR 的**协议级**承诺，
     只能在 HTTP 层看；
  4. 「采样器有没有挂在生成链上」这件事本身（`llama_jni.cpp` 的
     `[stop] 已挂载 stop 采样器：N 条` 只在服务端日志里），所以给了 `--log` 才核这条。

════════════════════════════════════════════════════════════════════════════
关键判据：客户端**不能**自己按 stop 切字符串
════════════════════════════════════════════════════════════════════════════
如果脚本把 SSE 拼完再按 stop 切一刀，那服务端「流式下漏拦」这个最严重的失效模式
就被脚本自己掩盖了：内容发出来了，脚本切掉，看起来全绿。
所以流式路径一律**逐块累加**，任何一块（哪怕是子串）里出现 stop 串就算失败。

为此这里不解析 SSE 的 JSON（那会引入 JSON 转义的坑），而是直接扫**去掉 SSE 信封
之后的原始文本流**。stop 串用「不可能出现在协议开销里」的记号（如 @@END@@），
所以原文里出现它就是真出现。这样做的**误报只可能出现在服务端真的把 stop 串
写到了流里**——正是要被抓住的情况。

════════════════════════════════════════════════════════════════════════════
每条判据都配了一次「不带 stop」的对照
════════════════════════════════════════════════════════════════════════════
这份脚本的判据几乎都是否定式的：**客户端没有看到某个串**。这种断言有一个致命的
自欺 —— 判据恒真也会全绿。真机上最容易骗过自己的两种情况：

  1. 模型压根没按提示词写记号 —— 那么"记号没出现"就永远成立（而提示词恰恰是让
     模型把记号写在文末的，于是"没出现"和"没拦住"完全无法区分）；
  2. 脚本自己写错了：比如只查"记号没出现"，漏掉"输出**尾部真的被切掉了**"。
     一个完全不认 stop 的服务端，只要回答以记号结尾就能让第 1 条成立。

所以每组用例先跑一次**不带 stop** 的输出当基准（preflight），再要求带 stop 的那次
是「基准在记号起点处的真前缀」。这样"记号没出现"才有意义。

而上面第 2 种（脚本自己写错）**只能靠对着坏实现跑来发现** —— 装机跑一万次也发现
不了，因为真机的实现是好的。为此 `tools/stop_acceptance/fake_stop_server.py` 提供
三份打桩实现（正确 / 静默忽略 stop / 暂扣后不放行），由
`tools/run_acceptance_stop_tests.sh` 在 CI 里断言"该绿的绿、该红的红"。
本文件的第一版判据就踩了第 2 条，是**对着 nosampler 桩跑出来的**。

用法（需要同一网段能访问手机；模型建议带上固定 `seed` 以便复现）:
    python3 tools/acceptance_stop.py --host 192.168.1.23 --port 8080

可选：
    --tries N        随机判据的重试次数（默认 3）
    --only 用例名     只跑某些用例，逗号分隔（名字见 --list）
    --list           列出用例名（含一句话说明）
    --timeout S      单请求超时（默认 120）
    --log FILE       服务端日志文件（App 崩溃取证目录里的 session-*.log），
                     给了才核对「已挂载 stop 采样器」这类只存在于日志里的判据
    --no-validate    跳过 400 校验组（模型没加载时也能跑）
    --strict-soft    把 WARN（前提不成立，如模型没照做）也判失败，默认只提醒
    --allow-skip     连不上时退化为 SKIP(exit 0)，供 CI 用
    --temp T         采样温度（默认 0）；>0 时第 3 组的逐字比对自动降级
    --need-never     第 3 组加强版：要求模型在文末写 @@NEVER@@，覆盖命中路径

关于「记号在输出里出现两次」：
    真机上很常见 —— 模型把提示词里的记号抄进输出，于是输出里记号出现两次
    （`@@END@@`（7 字）-> `@@@@ENDEND`（10 字）就是这种形状）。这不是服务端
    故障，**服务端的正确输出是"停在最靠前的那个命中点"**（stopseq::relevant
    的语义），不是"停在第一次出现的位置"。命中类判据因此一律用
    hit_point(基准) 取最靠前命中点；用 find/rfind 都会把正确输出判成假红。

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

# 每个记号都要求模型**在文末写出它**（见 ASK_* 提示词）。挑选原则：
#   · 不会自然出现在正常正文里（都是 `@@` 包裹的记号或 HTML 式闭合标签），
#     所以"原文里出现了它"就等于"服务端把它发出去了"，判据不需要解析器；
#   · 不含引号 / 反斜杠 / 换行 —— 这样拿 SSE 原文扫它不会被 JSON 转义层干扰；
#   · `</end>` 这种形状会被 BPE 切成多段，用来验"跨 token 命中"。
MARK = "@@END@@"          # 普通命中（单 token 级）
NEVER = "@@NEVER@@"        # 模型**不会**自然输出 -> 验"不吞正文"
XMARK = "</end>"           # 跨 token（`</e` + `nd>` 两次输出仍要命中）
# 多条同时可命中：输出以 AB 开头时，AB 与 ABC 都命中，语义上必须按 **最短/最靠前**
# 处理（kMP 里的"最短匹配"），不能因为数组里 ABC 排在前面就多吐一个 token。
AMONG = ["@@END@@" + "Z" * 40, "@@END@@"]

C_GREEN, C_RED, C_YEL, C_DIM, C_RST = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


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
        """判据依赖模型行为，无法硬断言时用它：不绿不红，只提示。"""
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
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001 —— 网络类异常一律按可报告错误处理
        return None, f"{type(e).__name__}: {e}"


def get(base, path, timeout):
    try:
        with urllib.request.urlopen(base + path, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"


def stream_raw(base, body, timeout, path="/v1/chat/completions"):
    """发一次 stream=true 的请求，**逐块**返回去掉 SSE 信封后的原始文本。

    返回 (status, chunks:list[str], raw:str)。chunks 是"一次 read 拿到的东西"，
    粒度不保证与 SSE event 对齐 —— 这正是我们要的：**字节级**地看有没有东西
    在 stop 之后被吐出来（不解析 JSON，避免转义层把判据搞模糊）。
    """
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    chunks, raw = [], []
    with urllib.request.urlopen(req, timeout=timeout) as r:
        status = r.status
        buf = b""
        while True:
            # 固定长度读且必须读到 EOF：read(256) 是"最多 256 字节"，不吃满不返回，
            # 用 read(1) 会让每个字节一次系统调用（本 PR 的测试要跑几十次请求，会很慢）。
            b = r.read(8192)
            if not b:
                break
            buf += b
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.decode("utf-8", "replace").rstrip("\r")
                raw.append(line)
                feed_sse_line(line, chunks)
        if buf:
            line = buf.decode("utf-8", "replace").rstrip("\r")
            raw.append(line)
            feed_sse_line(line, chunks)
    return status, chunks, "\n".join(raw)


def feed_sse_line(line, chunks):
    """把一行 SSE 归入 chunks：只有「内容增量」进 chunks。

    协议开销（`[DONE]`、只有 finish_reason 的收尾块、`: ping` 心跳）一律不进 —— 否则
    脚本会拿协议字符串去扫 stop 串（既可能误报），"输出长度"这类判据也会失去意义。
    """
    if not line.startswith("data:"):
        return
    body = line[5:].strip()
    if not body or body == "[DONE]":
        return
    if '"content"' in body or '"reasoning_content"' in body:
        chunks.append(body)


def sse_text(chunks):
    """把逐块内容拼成「服务端实际发出去的内容文本」（**不**切 stop）。

    为什么不解析 JSON 取值：这里要的是**客户端视角的原始流**。解析成 content
    字段后再拼，会把「跨块被切开」的细节抹平（错误实现常常恰好跨块漏出来），
    而 JSON 转义层对本用例的判据没有影响 —— stop 记号里不含引号/反斜杠/换行，
    出现在 JSON 里就是原样出现。所以直接扫原文，报错时连位置一起打出来。
    """
    return "".join(decode_sse_json(c) for c in chunks)


_STR = re.compile(r'"(?:[^"\\]|\\.)*"')


def decode_sse_json(chunk):
    """从一块 SSE data 原文里取出 content 字段的字符串值；取不到就返回原文。

    实现故意很土（找 content 键后取第一个字符串字面量再反转义）：目标是**看内容**，
    不是做 JSON 解析器，遇到不认识的结构宁可退回原文也不能抛异常把用例打红。
    """
    out = []
    for key in ('"reasoning_content"', '"content"'):
        i = chunk.find(key)
        if i < 0:
            continue
        j = chunk.find(":", i + len(key))
        if j < 0:
            continue
        m = _STR.search(chunk, j + 1)
        if not m:
            continue
        try:
            out.append(json.loads(m.group(0)))
        except Exception:  # noqa: BLE001
            pass
    return "".join(out) if out else ""


def stream_has(chunks, needle):
    """任一时刻（含跨块）出现过 needle 就返回它出现的位置信息，否则 None。

    跨块会漏判，所以这里做**增量扫描**：逐块累加后再判，等价于"在时间轴上任何
    一个瞬间，客户端手里是否已经拿到了这个串"。少读到一次事件也要报出来。
    """
    acc = ""
    for i, c in enumerate(chunks):
        acc += c
        if needle in acc:
            return f"第 {i + 1} 块（累计 {len(acc)} 字符）时已出现 {needle!r}"
    return None


def hit_point(gen, stops):
    """`gen` 里**最靠前**的那个命中点：从该处起，`gen` 的尾部正好是某条 stop 的前缀。

    这是「服务端应当切在哪里」的**唯一**定义，取自 stopseq::relevant 的语义：

      relevant() 对每条 stop 枚举"它能从输出的哪个位置开始"，只有末尾恰好是
      该 stop 前缀的位置才谈得上匹配；`gen` 是**逐 token 累积**出来的，所以
      在某个时刻它与 stop 前缀重叠的那些位置中，**最靠前**的那个就是命中的起点。

    为什么必须按这个定义、而不能用 `gen.rfind(mark)`：
      模型完全可能把提示词里的记号**抄进输出**，于是记号在基准里出现两次
      （真机日志里就是这样：`基准 '@@END@@'（7 字）-> 本次 '@@@@ENDEND'（10 字）`）。
      `rfind` 会取到**最后一处**，把服务端的正确输出（停在第一处）判成
      「多吐了字」—— 假红。这里改成"任何一条 stop 的最靠前重叠位置"，
      两处出现时取第一处，与真实现一致。

    返回起点下标；没有任何重叠返回 None（= 根本没有命中，不该被切）。
    注意：**只要读出 stop 串本身**就说明模型把它写出来了，此时按「该切」判定；
    不依据"输出长度是否超过重叠长度"。
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


def trimmed_at_hit(cur, ref, stops):
    """判据：`cur` 是基准 `ref` 在**命中点**处的真前缀（= 尾部确实被切了）。

    返回 (ok, detail)。三种都算通过：
      · `cur` 正好停在命中点之前 —— 主路径；
      · `cur` 是命中点之前那段文本的真前缀（逐块下发粒度差异）—— 只要没越界；
      · 输出为空而命中点在最前面（模型一开口就写记号）—— 有断言的"空"，
        判「没有任何 stop 之前的字符被发出去」，由 `hit_point == 0` 兜住。
    `cur` 长过命中点、或与基准逐字不符 → 说明服务端没拦（或拦晚了），判失败。
    """
    at = hit_point(ref, stops)
    if at is None:
        return False, "基准里没有可命中的位置，本用例前提不成立（见上一条对照判据）"
    if len(cur) > at:
        return False, (f"基准的命中点在 {at} 字处，本次却下发了 {len(cur)} 字："
                       f"越界 {len(cur) - at} 字，服务端没在命中处停下")
    # 允许 rstrip 差异：流式下每块自带换行，逐块累积与基准的收尾空白可能不同
    if cur.rstrip() == ref[:at].rstrip() or ref[:len(cur)] == cur:
        return True, ""
    return False, (f"本次输出 {cur!r} 既不是基准在命中点处的真前缀，"
                   f"也与基准前 {len(cur)} 字逐字不符（基准 {ref!r}）")


def no_marker_stops(txt, stops):
    """输出 `txt` 是否**停在**某条 stop 的尾部匹配上（命中残留 / 暂扣没放行）。

    对齐 stop_sequences.h 的语义：只有「输出尾部是某条 stop 的真前缀」才算
    "被暂扣卡在这儿"。用它把"未命中残留的半截记号"和"正常的收尾标点"区分开，
    也用来判断模型有没有照提示词做（见 preflight）。
    """
    t = txt.rstrip()
    for s in stops:
        if not s:
            continue
        for k in range(min(len(t), len(s) - 1), 0, -1):
            if t.endswith(s[:k]):
                return f"输出停在 {s[:k]!r}（是 {s!r} 的真前缀）"
    return None


# ── 请求构造 ────────────────────────────────────────────────────────────────
def chat(prompt, stops=None, stream=False, extra=None, key="stop", temperature=0):
    # temperature=0 的用意：本文件几乎所有判据都是"拿带 stop 的输出去比不带 stop
    # 的基准"，采样噪声会直接变成假红。`--temp` 允许调高它 —— 但那会让
    # 「逐字比对」类判据（第 3 组）自动退化成"只看有没有停在 stop 真前缀上"，
    # 因为两次采样的输出本来就不同，逐字比对不再成立。
    b = {"messages": [{"role": "user", "content": prompt}], "stream": stream,
         "temperature": temperature, "max_tokens": 160}
    if stops is not None:
        b[key] = stops
    if extra:
        b.update(extra)
    return b


def content_of(body):
    """非流式响应正文。"""
    try:
        return json.loads(body)["choices"][0]["message"].get("content") or ""
    except Exception:  # noqa: BLE001
        return None


def finish_of(body):
    try:
        return json.loads(body)["choices"][0].get("finish_reason")
    except Exception:  # noqa: BLE001
        return None


def err_msg(body):
    try:
        return json.loads(body)["error"]["message"]
    except Exception:  # noqa: BLE001
        return body[:200]


# ── 用例 ────────────────────────────────────────────────────────────────────
ASK_PLAIN = "请只输出这一行，原样输出、前后不要加任何别的内容：@@END@@"
# ⚠️ ASK_NEVER 曾经是「请写一段 30 字左右的自我介绍，结尾写上：@@NEVER@@」，
# 这条提示词**自身自相矛盾**：它一边要求"另起一行/原文照抄"，一边又和
# ASK_PLAIN 一样带前缀。模型在长上下文下会把整条提示词抄进输出，于是"开头就
# 命中" —— 输出变成空串。第 3 组（不吞正文）要的是"采样器**放行**了正文"，
# 开头就命中时放行路径压根没被走到，判据（"没有停在 stop 真前缀上"）恒真、
# 全绿，什么都没证明。所以这里改成一句**不会诱导回显提示词**的正文要求，
# 并让 preflight 的 never 基准不再自带 `@@NEVER@@`：
#   · 记号只在 `--need-never` 时要求模型写出来（想覆盖"放行路径"时打开）；
#   · 默认情况下第 3 组的判据退化为「逐字比对基准」（见 case_no_swallow），
#     这是真模型下**唯一**能证明"没吞正文"的判据，不再依赖模型照抄记号。
ASK_NEVER = "请写一段 30 字左右的自我介绍，介绍你这台手机上的本地模型自己。"
ASK_NEVER_MARK = (ASK_NEVER + "，最后再另起一行写上：@@NEVER@@")
ASK_X = "请只输出这一行，原样输出、前后不要加任何别的内容：</end>"
PLAIN = "请写一段 30 字左右的自我介绍"


def case_health(base, timeout, r):
    st, body = get(base, "/health", timeout)
    r.ck("前置：/health 可达且返回 JSON", st == 200 and body.strip().startswith("{"),
         f"status={st} body={body[:200]!r}")
    loaded = False
    try:
        loaded = bool(json.loads(body).get("model_loaded", json.loads(body).get("ok", False)))
    except Exception:  # noqa: BLE001
        pass
    return loaded


def case_mounted(base, args, r):
    """第 1 步：请求带 stop 后，日志里必须出现「已挂载 stop 采样器」。

    这一步的判据全在服务端日志里，所以必须给 --log。没给就跳过而不是假绿：
    采样器没挂上时，后面所有"命中"类用例都会红，而根因是这一条。
    """
    if not args.log:
        print(f"{C_YEL}SKIP{C_RST}  [1] 采样器挂载判据（未给 --log，无法看服务端日志）")
        return
    before = _tail_log(args.log)
    st, body = post(base, "/v1/chat/completions", chat(ASK_PLAIN, [MARK], stream=False), args.timeout)
    after = _tail_log(args.log)
    new = after[len(before):] if after.startswith(before) else after
    r.ck("[1] 带 stop 的请求后，日志出现「已挂载 stop 采样器」",
         "已挂载 stop 采样器" in new,
         f"本次请求新增日志尾部：{new[-400:]!r}\n"
         "      根因排查：LlmEngine.kt 里 stops 为空时会传 null（native 侧据此不挂采样器）；\n"
         "      若传的是空数组而非 null，也会走到不挂载那条路。")


def _tail_log(path):
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            n = min(f.tell(), 2_000_000)
            f.seek(-n, 2)
            return f.read().decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return ""


def case_hit_stream(base, args, r, ref=None):
    """第 2 步：流式命中，且 stop 串**不得**出现在任何一块里（协议级要求）。

    关键点：带 stop 跑一次、**再不带 stop 跑一次**做对照。后者的输出证明"模型本来
    会把记号写出来"，这样"客户端一个字都没看到"才是服务端拦住的证据，而不是
    模型根本没照做的巧合 —— 这一步是整份脚本里唯一能证明协议的判据。
    """
    st, chunks, raw = stream_raw(base, chat(ASK_PLAIN, [MARK], stream=True), args.timeout)
    if st != 200:
        r.ck("[2] 流式命中：HTTP 200", False, f"status={st} {raw[:300]!r}")
        return
    txt = sse_text(chunks)
    where = stream_has(chunks, MARK)

    # 对照：不带 stop 时，记号必须原样出现（否则模型没照做，本用例不成立）
    ref_ok = False
    if ref is None:
        r2, c2, _ = stream_raw(base, chat(ASK_PLAIN, None, stream=True), args.timeout)
        ref_ok = r2 == 200 and MARK in sse_text(c2)
    else:
        ref_ok = MARK in ref
    r.ck("[2] 流式命中：对照（不带 stop）时记号确实被输出 —— 否则本用例不成立",
         ref_ok, "模型没照 ASK_PLAIN 写 @@END@@。换模型/提示词再验；"
                 "这一条 FAIL 说明用例前提不成立，不是服务端的问题")

    # ⚠️ 这里必须查**尾部被切掉**，不能只查"记号没出现"：
    # 一个完全不认 stop 的服务端，只要模型把记号恰好写在文末，"记号没出现"
    # 也可能成立 —— 判据恒真。只有"输出正好停在命中处"才证明服务端真的切了。
    #
    # 命中点用 hit_point(基准) 算，**不是** ref.find(MARK)：
    # 模型会把提示词里的记号抄进输出，于是记号在基准里出现两次（真机日志里
    # `@@END@@`（7 字）-> `@@@@ENDEND`（10 字）就是这种形状）。find/rfind 都会
    # 取错位置，把服务端的正确输出判成"多吐了字" —— 假红。
    ok, why = trimmed_at_hit(txt, ref, [MARK])
    at = hit_point(ref, [MARK])
    # 前提：基准里确实有可命中的位置，否则判据无从谈起。判定为 WARN 而非 FAIL ——
    # 前提不成立是"换个提示词再验"，不是服务端的问题。
    r.soft("[2] 流式命中：基准里记号在正文之后（本用例判据需要这一点）",
           at is not None,
           f"基准输出 {ref!r}，没有可命中的位置，无法判定是否真被切掉；"
           "换个提示词（让记号出现在文末）再验")
    r.ck("[2] 流式命中：输出被切在记号起点（证明服务端真拦了，不是记巧合）",
         ok, why or f"基准 {ref!r}（{len(ref)} 字）-> 本次 {txt!r}（{len(txt)} 字）")
    r.ck("[2] 流式命中：stop 串绝不出现在任何增量块里（协议级）",
         where is None, f"越界内容已经发给客户端：{where}")
    r.ck("[2] 流式命中：SSE 以 [DONE] 正常收尾", "[DONE]" in raw, f"尾部：{raw[-200:]!r}")
    return {"end": MARK in txt, "x": XMARK in txt}


def case_no_swallow(base, args, r, ref=None):
    """第 3 步：**不吞正文** —— 本 PR 最花心思的一处（暂扣 + can_continue_fast）。

    ═══════════════════════════════════════════════════════════════════════════
    这一组**改过一次判据**，原因值得写下来
    ═══════════════════════════════════════════════════════════════════════════
    旧判据是「带 stop 的那次输出不许停在 `@@NEVER@@` 的真前缀上」，前提是
    「不带 stop 的基准里模型写出了完整记号」。两个问题，真机上都暴露了：

      1. **前提永远不成立**：旧 ASK_NEVER 是「写一段自我介绍，结尾写上 @@NEVER@@」，
         模型并不照做（那一步的 WARN 就是这么来的）。前提不成立时旧判据是个
         **恒真式** —— 输出里没有记号，当然不会"停在记号真前缀上"，全绿，
         什么都没证明。
      2. **旧的失败模式与命中混乱**：模型容易把带记号的提示词抄进输出，于是
         输出**开头就命中** `@@NEVER@@` -> 正文为空。这是采样器的**正确**行为
         （没有正文可放行），不是"吞正文"，但会撞上 `输出为空（正文全被吞）`
         这条判据 —— 同样是假红。

    新判据同时覆盖两条路，且都不依赖模型照抄记号：

      · **放行路径（主判据，硬断言）**：`stop` 设成模型不会输出的串，采样器
        应当**一个字节都不拦**。所以带 stop 的输出必须与不带 stop 的基准
        **逐字相同**。这正是「吞正文」最容易伤到的地方，且"少一个字节"必然红。
        —— 与更省事的"不许停在真前缀上"相比，这条是**正向**的：它要求输出
        真的在，而不只是"看起来没被截"。
      · **命中路径（`--need-never` 时硬断言）**：需要真正覆盖"暂扣后命中"时，
        用 ASK_NEVER_MARK 让模型在**文末**写记号，断言输出是基准在命中点的真前缀。

    代价说明（为什么默认不带 `--need-never`）：逐字比对要求两次采样一致，
    所以这一组的判据在 `--temp > 0` 时自动降级为"只查有没有停在真前缀上"，
    并 WARN 提示。temperature=0 也不是逐字保证（数值上不保证），所以这里把
    "不一致"的细节原样打出来 —— 是真吞字还是采样抖动，一眼能分清：
    吞字只会短、且短在 stop 前缀处；抖动是任意位置的改写。
    """
    if args.need_never:
        return case_no_swallow_marked(base, args, r)

    # 主判据要"带 stop == 不带 stop"，所以基准必须用**同一条**提示词现抓一次，
    # 不能复用 preflight 的（--temp 一致才谈得上逐字比对）。
    if ref is None:
        r2, c2, _ = stream_raw(base, chat(ASK_NEVER, None, stream=True,
                                          temperature=args.temp), args.timeout)
        ref = sse_text(c2) if r2 == 200 else ""
    if not (ref or "").strip():
        r.ck("[3] 不吞正文：可以先拿到不带 stop 的基准输出", False,
             "不带 stop 的对照输出为空，本组判据无从比对；调大 --timeout 再试")
        return

    problems = []
    lens = []
    for i in range(args.tries):
        st, chunks, raw = stream_raw(base, chat(ASK_NEVER, [NEVER], stream=True,
                                                temperature=args.temp), args.timeout)
        if st != 200:
            problems.append(f"第 {i + 1} 次 status={st}（{raw[:120]!r}）")
            continue
        txt = sse_text(chunks).rstrip()
        lens.append(len(txt))
        if not txt:
            problems.append(f"第 {i + 1} 次输出为空（正文全被吞）")
            continue
        stuck = no_marker_stops(txt, [NEVER])
        if stuck:
            problems.append(f"第 {i + 1} 次 {stuck}（停在被暂扣的真前缀上 = 少一截字）")
            continue
        if args.temp == 0 and txt != ref.rstrip():
            # 主判据：带 stop 与不带 stop 必须逐字相同（采样器不该拦任何东西）
            problems.append(f"第 {i + 1} 次输出与基准逐字不同 —— 少了字说明"
                            f"正文被吞（基准 {len(ref.rstrip())} 字 / 本次 {len(txt)} 字）：\n"
                            f"        基准 {ref.rstrip()!r}\n        本次 {txt!r}")
    if args.temp == 0:
        r.ck(f"[3] 不吞正文：{args.tries} 次带 stop 的输出与不带 stop 的基准逐字相同"
             f"（少一个字必红）", not problems, "\n      ".join(problems))
    else:
        r.ck(f"[3] 不吞正文：{args.tries} 次都没有停在 stop 真前缀上"
             f"（--temp={args.temp}：逐字比对不成立，已降级；少了字仍必红）",
             not problems, "\n      ".join(problems))
        r.soft("[3] 不吞正文：temperature=0 下才有逐字比对（当前 --temp>0，判据已降级）",
               False, "要恢复最强的判据，去掉 --temp（默认 0）再跑一次")
    if lens:
        print(f"{C_DIM}      各次输出长度：{lens}（基准 {len(ref.rstrip())}）{C_RST}")
    return


def case_no_swallow_marked(base, args, r, ref=None):
    """第 3 组的加强版（`--need-never`）：让记号出现在**文末**，硬断言"被放行"。

    与命中类用例同一套判据（hit_point + trimmed_at_hit），但语义相反：
    这里要的是"命中点之前的正文**一个字不少**地发出来了"，所以断言
    `输出 == 基准在命中点处的真前缀` 且**长度不短于**正文部分。
    旧的 `len(txt) == 0 -> 问题` 在"开头就命中"时是假红，这里按命中点算，
    开头命中（at == 0）空输出是**正确**行为。
    """
    if ref is None:
        r2, c2, _ = stream_raw(base, chat(ASK_NEVER_MARK, None, stream=True,
                                          temperature=args.temp), args.timeout)
        ref = sse_text(c2) if r2 == 200 else ""
    ref_has = NEVER in ref
    r.soft("[3] 不吞正文：基准里模型写出了完整记号，本组真正覆盖了「命中 + 放行」路径",
           ref_has, "基准（不带 stop）那次模型没写出 @@NEVER@@，本组只验了放行路径；"
                    "换个模型/提示词再跑一次能补上")
    problems = []
    lens = []
    at = hit_point(ref, [NEVER])
    for i in range(args.tries):
        st, chunks, raw = stream_raw(base, chat(ASK_NEVER_MARK, [NEVER], stream=True,
                                                temperature=args.temp), args.timeout)
        if st != 200:
            problems.append(f"第 {i + 1} 次 status={st}（{raw[:120]!r}）")
            continue
        txt = sse_text(chunks).rstrip()
        lens.append(len(txt))
        stuck = no_marker_stops(txt, [NEVER])
        if stuck:
            problems.append(f"第 {i + 1} 次 {stuck}")
            continue
        if at is not None and at > 0:
            ok, why = trimmed_at_hit(txt, ref, [NEVER])
            if not ok:
                problems.append(f"第 {i + 1} 次 {why}")
            elif len(txt) < at * 0.5:
                # 停在命中点之前是对的，但如果在正文很早的地方就断了，说明是"吞"
                problems.append(f"第 {i + 1} 次只下发了 {len(txt)} 字，正文有 {at} 字："
                                f"疑似少了一截正文（{txt!r}）")
        elif not txt:
            problems.append(f"第 {i + 1} 次输出为空，且基准里命中点不在开头")
    r.ck(f"[3] 不吞正文：{args.tries} 次都没有停在 stop 真前缀上（少了字必红）",
         not problems, "\n      ".join(problems))
    if lens:
        print(f"{C_DIM}      各次输出长度：{lens}（基准 {len(ref.rstrip())}）{C_RST}")
    return


def case_cross_token(base, args, r, ref=None):
    """第 4 步：跨 token 命中（`</end>` 会被 BPE 切成多段，单测里那条 `</e` + `nd>`）。

    模板：区分「命中」与「停在真前缀上」。后者是这类实现最常见的失效：
    标记被切开、暂扣住了、却始终没补全也没放行 —— 输出就少一截。
    """
    st, chunks, raw = stream_raw(base, chat(ASK_X, [XMARK], stream=True), args.timeout)
    if st != 200:
        r.ck("[4] 跨 token 命中：HTTP 200", False, f"status={st} {raw[:300]!r}")
        return
    txt = sse_text(chunks)
    where = stream_has(chunks, XMARK)

    ref_ok = XMARK in (ref or "")
    if ref is None:
        r2, c2, _ = stream_raw(base, chat(ASK_X, None, stream=True), args.timeout)
        ref_ok = r2 == 200 and XMARK in sse_text(c2)
    r.ck("[4] 跨 token 命中：对照（不带 stop）时 </end> 确实被输出 —— 否则本用例不成立",
         ref_ok, "模型没照 ASK_X 写 </end>。换模型/提示词再验；"
                 "这一条 FAIL 说明用例前提不成立，不是服务端的问题")

    # 同 [2]：命中点按 stopseq::relevant 的语义取"最靠前重叠位置"，不用 find
    ok, why = trimmed_at_hit(txt, ref or "", [XMARK])
    r.ck("[4] 跨 token 命中：输出被切在记号起点（证明真拦了）",
         ok, why or f"基准 {ref!r}（{len(ref or '')} 字）-> 本次 {txt!r}（{len(txt)} 字）")
    r.ck("[4] 跨 token 命中：</end> 不出现在任何增量块里", where is None, str(where))
    r.ck("[4] 跨 token 命中：没有停在半截前缀上（暂扣后没补全就放行 = 少一截字）",
         no_marker_stops(txt, [XMARK]) is None, str(no_marker_stops(txt, [XMARK])))
    r.ck("[4] 跨 token 命中：没有孤立的 `nd>`（说明只拦到了开口）",
         "nd>" not in txt or "</e" in txt, f"输出尾部：{txt[-80:]!r}")
    return {"x": XMARK in txt}


def case_validate(base, args, r):
    """第 5 步：400 校验（不用模型也能跑，但服务端要先有模型才会走到校验之前的 503）。"""
    cases = [
        ("空串", {"stop": [""]}),
        ("字符串空串", {"stop": ""}),
        ("类型错（数字）", {"stop": 3}),
        ("类型错（对象）", {"stop": {"a": 1}}),
        ("数组含非字符串", {"stop": ["X", 5]}),
        ("单条 257 字节", {"stop": ["x" * 257]}),
        # 注意：**条数不再是 400**。曾把上限设成 8、超出报 400，结果
        # "stop 与 stop_sequences 各塞一批、并集 9 条"的正常客户端每个请求都失败。
        # 现在超额只截断 + 落日志，请求照常生成 —— 见下面单独那条断言。
    ]
    for name, extra in cases:
        st, body = post(base, "/v1/chat/completions",
                        chat("hi", None, stream=False, extra=extra), args.timeout)
        if st == 503:
            r.ck(f"[5] {name} -> 400", False,
                 "服务端返回 503（模型未加载）。校验发生在加载检查之后，"
                 "先在 App 里加载模型再跑这一组")
            return
        r.ck(f"[5] {name} -> 400 且给出原因", st == 400 and bool(err_msg(body)),
             f"status={st} msg={err_msg(body)!r}")
    # 8 条整好合法：不能被上限判错
    st, body = post(base, "/v1/chat/completions",
                    chat("只回复两个字：好的", ["a", "b", "c", "d", "e", "f", "g", "h"],
                         stream=False, extra={"max_tokens": 16}), args.timeout)
    r.ck("[5] 8 条整好合法（不得 400）", st == 200, f"status={st} msg={err_msg(body)!r}")
    # 条数多**不是**错误：这是"客户端完全无法生成"事故的回归点，必须 200。
    st, body = post(base, "/v1/chat/completions",
                    chat("只回复两个字：好的", ["a", "b", "c", "d", "e", "f", "g", "h", "i"],
                         stream=False, extra={"max_tokens": 16}), args.timeout)
    r.ck("[5] 9 条（旧实现会 400）不得失败请求", st == 200, f"status={st} msg={err_msg(body)!r}")


def case_alias_union(base, args, r):
    """第 6 步：`stop` / `stop_sequences` / 两个都给 —— 三种写法行为一致。"""
    variants = [
        ("stop 单串", {"stop": MARK}),
        ("stop 数组", {"stop": [MARK]}),
        ("stop_sequences 别名", {"stop_sequences": [MARK]}),
        ("两个都给（取并集）", {"stop": [MARK], "stop_sequences": ["@@OTHER@@"]}),
    ]
    for name, extra in variants:
        st, body = post(base, "/v1/chat/completions",
                        chat(ASK_PLAIN, None, stream=False, extra=extra), args.timeout)
        if st != 200:
            r.ck(f"[6] {name}：200", False, f"status={st} msg={err_msg(body)!r}")
            continue
        c = content_of(body) or ""
        r.ck(f"[6] {name}：stop 串不落在结果里", MARK not in c,
             f"结果里出现了 {MARK}：{c[-60:]!r}")


def case_nonstream_fallback(base, args, r):
    """第 7 步：非流式兜底截断。

    注意判据的方向与 PR 描述一致：**兜底不该被触发**（native 正常拦下时
    根本轮不到它）。若日志里出现「stop 兜底截断」，说明 native 侧漏拦了 ——
    输出照样是对的（兜底生效），但这属于要单独查的信号，所以这里判 WARN。
    """
    st, body = post(base, "/v1/chat/completions", chat(ASK_PLAIN, [MARK], stream=False), args.timeout)
    if st != 200:
        r.ck("[7] 非流式：200", False, f"status={st} msg={err_msg(body)!r}")
        return
    c = content_of(body) or ""
    r.ck("[7] 非流式：stop 串不出现在结果里", MARK not in c, f"结果尾部：{c[-60:]!r}")
    r.ck("[7] 非流式：finish_reason=stop", finish_of(body) == "stop", f"finish_reason={finish_of(body)!r}")
    r.soft("[7] 非流式：没走到兜底路径（出现「兜底截断」说明 native 漏拦）",
           not (args.log and "stop 兜底截断" in _tail_log(args.log)),
           "日志里出现 stop 兜底截断：结果仍然正确，但 native 采样器可能漏拦，值得单独查")


def case_no_stop_unchanged(base, args, r):
    """回归：不带 stop 时行为不变（采样器不该被挂上）。"""
    st, body = post(base, "/v1/chat/completions", chat(PLAIN, None, stream=False), args.timeout)
    r.ck("[8] 不带 stop：200 且正文非空", st == 200 and (content_of(body) or "").strip() != "",
         f"status={st} body={body[:200]!r}")


def case_among_multi(base, args, r, ref=None):
    """第 9 步：多条同时可命中时，必须按「最靠前/最短」处理。

    输出 `...@@END@@ZZZ...`（40 个 Z）时，`@@END@@` 与 `@@END@@ZZZ...` 两条都能命中。
    若实现按数组顺序挑、而长的那条排在前面，就会多吐一个 token（把 `Z` 发出去）。
    判据：结果里不得出现 `@@END@@` 之后的任何字符。
    """
    st, chunks, raw = stream_raw(base, chat(ASK_PLAIN, AMONG, stream=True), args.timeout)
    if st != 200:
        r.ck("[9] 多 stop 同时可命中：HTTP 200", False, f"status={st}")
        return
    txt = sse_text(chunks)
    if ref is None:
        r2, c2, _ = stream_raw(base, chat(ASK_PLAIN, None, stream=True), args.timeout)
        ref = sse_text(c2) if r2 == 200 else ""
    ref_ok = MARK in ref
    r.ck("[9] 多 stop 同时可命中：对照（不带 stop）时记号确实被输出 —— 否则本用例不成立",
         ref_ok, "模型没照 ASK_PLAIN 写 @@END@@；换提示词/模型再验，不是服务端的问题")
    r.ck("[9] 多 stop 同时可命中：结果里没有 @@END@@ 之后的字符",
         "Z" not in txt, f"尾部：{txt[-40:]!r}（多吐了 Z，说明按数组顺序而非最靠前命中）")
    return


def case_completions(base, args, r):
    """第 10 步：`/v1/completions` 也要认 stop（README 承诺两个端点都认）。"""
    body = {"prompt": "补全这一行，原样输出：@@END@@", "stop": [MARK],
            "stream": False, "temperature": 0, "max_tokens": 80}
    st, resp = post(base, "/v1/completions", body, args.timeout)
    if st != 200:
        r.ck("[10] /v1/completions：200", False, f"status={st} msg={err_msg(resp)!r}")
        return
    try:
        txt = json.loads(resp)["choices"][0].get("text") or ""
    except Exception:  # noqa: BLE001
        txt = ""
    r.ck("[10] /v1/completions：stop 串不出现在 text 里", MARK not in txt,
         f"text 尾部：{txt[-60:]!r}")


def case_tail_overlap(base, args, r, ref=None):
    """第 11 步：长文本里 stop 出现在**尾部** —— 首版实现栽的就是这条。

    `relevant()` 若拿「整段输出」去和 stop 的前缀比（而不是比"输出尾部"），
    输出一旦积累超过 stop 长度，判定就恒为假 —— 表现为 **stop 完全不生效**
    且不报错。让模型先写一大段、最后才写记号，就能把它钉住。
    """
    prompt = ("请先写一段 80 字左右的短文介绍手机本地推理的好处，"
              "然后另起一行，原样输出：@@END@@")
    st, chunks, raw = stream_raw(base, chat(prompt, [MARK], stream=True), args.timeout)
    if st != 200:
        r.ck("[11] 长文本尾部命中：HTTP 200", False, f"status={st}")
        return
    txt = sse_text(chunks)
    if ref is None:
        r2, c2, _ = stream_raw(base, chat(prompt, None, stream=True), args.timeout)
        ref = sse_text(c2) if r2 == 200 else ""
    ref_ok = MARK in ref
    r.ck("[11] 长文本尾部命中：对照（不带 stop）时记号确实被输出 —— 否则本用例不成立",
         ref_ok, "模型没在文末写记号；换提示词/模型再验，不是服务端的问题")
    r.ck("[11] 长文本尾部命中：正文长度大于 20 字（确保不是空输出蒙过去的）",
         len(txt) > 20, f"输出长度 {len(txt)}，内容 {txt!r}")
    r.ck("[11] 长文本尾部命中：记号不出现在正文里", MARK not in txt,
         f"尾部：{txt[-60:]!r}")
    # 关键：只有"输出停在命中处"才证明**尾部也生效**。
    # 漏了这条，一个完全不认 stop 的服务端也会让上面那条成立（记号在文末）。
    #
    # 同样不用 ref.find(MARK)：长提示词的用例里模型爱把提示词抄进输出，
    # 记号会因此出现两次（真机日志 `基准 54 字 -> 本次 104 字；基准第 47 字起是记号`
    # 就是 find 取到第一处、而服务端正确地切在**第二处**）。按 hit_point 算，
    # 两处出现时取第一处，与真实现（最靠前命中点）一致。
    ok, why = trimmed_at_hit(txt, ref, [MARK])
    at = hit_point(ref, [MARK])
    if at is not None:
        r.ck("[11] 长文本尾部命中：输出是基准在记号起点处的真前缀（尾部确实被切）",
             ok, why or f"基准 {len(ref)} 字 -> 本次 {len(txt)} 字；"
                        f"命中点在基准第 {at} 字处（记号出现 {ref.count(MARK)} 次）")
    else:
        r.soft("[11] 长文本尾部命中：基准里记号在正文之后", False,
               f"基准没在文末写记号（{ref[-40:]!r}），无法判定是否真被切掉")
    r.ck("[11] 长文本尾部命中：也没有停在记号的半截前缀上",
         no_marker_stops(txt, [MARK]) is None, str(no_marker_stops(txt, [MARK])))
    return


CASES = {
    "mounted": case_mounted,
    "among-multi": case_among_multi,
    "completions": case_completions,
    "tail-overlap": case_tail_overlap,
    "hit-stream": case_hit_stream,
    "no-swallow": case_no_swallow,
    "cross-token": case_cross_token,
    "validate": case_validate,
    "alias-union": case_alias_union,
    "nonstream": case_nonstream_fallback,
    "no-stop": case_no_stop_unchanged,
}


def preflight(base, args, r):
    """开跑之前先确认「模型会照提示词做」这件前提，并准备好对照输出。

    为什么值得单独做：这份脚本的每条判据都是「客户端**没有**看到某个串」。
    如果模型压根没写那个串，判据恒真 —— 脚本会全绿，但它什么都没证明。
    真机上这就是最容易骗过自己的地方。所以每组用例先跑一次**不带 stop** 的对照，
    把它当基准，再去比带 stop 的那次。
    """
    print(f"{C_DIM}-- preflight（不带 stop 的对照输出）--{C_RST}")
    ref = {}
    # `never` 用哪个提示词，取决于这一组要不要模型把记号写出来（--need-never）：
    # 默认的 ASK_NEVER **故意不提记号** —— 它测的是"采样器没拦"（放行路径），
    # 前提是输出里根本不出现记号。旧版在这里沿用"要求写 @@NEVER@@"的提示词，
    # 而模型不照做，于是第 3 组的判据变成恒真式（见 case_no_swallow 的注释）。
    never_prompt = ASK_NEVER_MARK if args.need_never else ASK_NEVER
    shots = [("plain", ASK_PLAIN, MARK), ("never", never_prompt, NEVER), ("x", ASK_X, XMARK)]
    for name, prompt, mark in shots:
        st, chunks, raw = stream_raw(base, chat(prompt, None, stream=True), args.timeout)
        txt = sse_text(chunks) if st == 200 else ""
        ref[name] = txt
        n = txt.count(mark)
        # 记号出现次数要打出来：出现两次是**常见且合法**的（模型把提示词抄进
        # 输出，两边各写一遍）。命中类判据按 hit_point 取最靠前命中点，不受影响；
        # 但"一次都没出现"时上面那些判据恒真，必须让人一眼看见前提不成立。
        how = "记号已出现" if n == 1 else f"记号出现 {n} 次" if n > 1 else "记号**没有**出现"
        print(f"{C_DIM}   {name}: {how} （{len(txt)} 字）{C_RST}")
    # plain 一次性超时（真机首字可能要几十秒）时给一句明确提示，不要让人以为是脚本坏了
    if all(not t for t in ref.values()):
        print(f"{C_YEL}   对照全部为空：请求可能超时或模型没吐字。"
              f"调大 --timeout 再试；也可以先用 --no-validate 只跑校验组。{C_RST}")
    return ref


def main():
    ap = argparse.ArgumentParser(description="真机端到端验收 stop / stop_sequences")
    ap.add_argument("--host", help="手机 IP（--list 时可不给）")
    ap.add_argument("--port", type=int, help="服务端口（--list 时可不给）")
    ap.add_argument("--tries", type=int, default=3,
                    help="随机判据（不吞正文）的重试次数，默认 3")
    ap.add_argument("--timeout", type=float, default=120.0,
                    help="单请求超时秒数，默认 120；真机首字慢时调大")
    ap.add_argument("--only", default="", help="只跑这些用例，逗号分隔（名字见 --list）")
    ap.add_argument("--list", action="store_true", help="列出用例名")
    ap.add_argument("--log", default="",
                    help="服务端日志文件（App 崩溃取证目录里的 session-*.log）；"
                         "给了才能核对「已挂载 stop 采样器」「命中 stop 序列」这类判据")
    ap.add_argument("--no-validate", action="store_true", help="跳过 400 校验组")
    ap.add_argument("--temp", type=float, default=0.0,
                    help="采样温度，默认 0。调大后「逐字比对」类判据（第 3 组）"
                         "自动降级为「不许停在 stop 真前缀上」，且不再需要两次采样一致")
    ap.add_argument("--need-never", action="store_true",
                    help="第 3 组加强版：要求模型在文末写 @@NEVER@@，"
                         "从而同时覆盖「命中 + 放行」两条路（默认只覆盖放行）")
    ap.add_argument("--strict-soft", action="store_true",
                    help="把 WARN（前提不成立/模型没照做）也判失败")
    ap.add_argument("--allow-skip", action="store_true",
                    help="连不上时退化为 SKIP(exit 0)，供 CI 用")
    args = ap.parse_args()

    if args.list:
        for k, fn in CASES.items():
            print(f"{k:14s} {(fn.__doc__ or '').strip().splitlines()[0]}")
        return 0
    if not args.host or not args.port:
        ap.error("--host 与 --port 必填（或先用 --list 看用例名）")

    base = f"http://{args.host}:{args.port}"
    # 先探一次 TCP：连不上属于环境问题（手机没开服务 / 不在同网段），
    # 不该报成"用例失败"，否则 CI 上会被误判成代码坏了。
    try:
        with socket.create_connection((args.host, args.port), timeout=5):
            pass
    except Exception as e:  # noqa: BLE001
        print(f"{C_RED}连不上 {args.host}:{args.port}：{type(e).__name__}: {e}{C_RST}")
        print("检查：App 里已「启动服务」、已打开「局域网访问」、手机与执行机同网段、端口一致。")
        return 0 if args.allow_skip else 2

    r = Result()
    print(f"== 目标 {base} ==")
    case_health(base, args.timeout, r)

    names = [n for n in CASES if n != "validate" or not args.no_validate]
    if args.only:
        want = {x.strip() for x in args.only.split(",") if x.strip()}
        unknown = want - set(CASES)
        if unknown:
            # 打错名字就静默什么都不跑、最后"全绿" —— 这正是本文件一直在防的
            # 那类自欺，所以直接报错退出。
            print(f"{C_RED}--only 里有不认识的用例名：{sorted(unknown)}{C_RST}")
            print("可用：" + " ".join(CASES))
            return 2
        names = [n for n in names if n in want]

    # 只跑校验组时不必做对照（省时间，也不需要模型加载好）
    ref = {}
    if any(n != "validate" for n in names):
        ref = preflight(base, args, r)

    for n in names:
        print(f"{C_DIM}-- {n} --{C_RST}")
        t0 = time.time()
        try:
            fn = CASES[n]
            if n in ("hit-stream",):
                fn(base, args, r, ref.get("plain"))
            elif n in ("cross-token",):
                fn(base, args, r, ref.get("x"))
            elif n in ("no-swallow",):
                # 默认（非 --need-never）不带基准：判据是"带 stop == 不带 stop"，
                # 由函数内部自己抓一次基准（也避免 preflight 的提示词与这里不一致）
                fn(base, args, r, None if not args.need_never else ref.get("never"))
            elif n in ("among-multi", "tail-overlap"):
                # 这两组的对照提示词与 ASK_PLAIN 同源（tail 的提示词不同，交给函数内部处理）
                fn(base, args, r, ref.get("plain") if n == "among-multi" else None)
            else:
                fn(base, args, r)
        except Exception as e:  # noqa: BLE001
            r.ck(f"[{n}] 用例本身抛异常", False, f"{type(e).__name__}: {e}")
        print(f"{C_DIM}   {time.time() - t0:.1f}s{C_RST}")

    print(f"\n== 结果：PASS {r.ok} / FAIL {r.fail} / WARN {r.warn} ==")
    if r.fail:
        return 1
    if args.strict_soft and r.warn:
        return 1
    print(f"{C_GREEN}全绿{C_RST}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
