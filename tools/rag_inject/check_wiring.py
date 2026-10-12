#!/usr/bin/env python3
"""断言「RAG 注入」在 App 里**真的接上了聊天链路**，且**开关关着时零注入**。

═══ 这一条守的是什么 ═══
RAG 阶段 1 把索引建好、阶段 0 把嵌入模型装上，但**没有一处把"检索"接到"拼 prompt"上**。
这类缺口的形态与阶段 0 那次一模一样（`loadEmbedModel` 写好了、调用方为 0）：
native / 存储层全绿，守卫全绿，而用户点下去**什么都不会发生**。

判据锚**结构关系**，不锚"文件里出现过某关键字"（后者恒真：定义处、注释里都有）：

  ① preparedMessages 体内真的调用了检索注入（否则注入永不发生）；
  ② 注入落在**最后一条 user 之前**（第 4 步定的位置）：拼接由 [attachBeforeLastUser] 做，
     且 preparedMessages 里**不得**再出现对 system 段的注入 —— 回到了 system 段
     就等于把分叉点又拉回 pos 0，KV 复用归零（这正是第 4 步要修的那个现象）；
  ③ 注入由 RagInject 的纯函数拼接（不是自己拼字符串 —— 那会绕开
     "注入为空时一个字节都不动"这条硬契约）；
  ④ 关掉的路径上只有一处开关判据（多一套必然漂移，漂移方向是多注入）；
  ⑤ 生成路径（App 内 + HTTP）没有各写一份注入逻辑。

用法：check_wiring.py <EngineActivity.kt> <LlmEngine.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body, kotlin_fn_span  # noqa: E402


def raw_fn(src, name):
    code, sline = kotlin_fn_span(src, name)
    if code is None:
        return None
    d = 0
    started = False
    end_line = sline
    for idx, ch in enumerate(code):
        if ch == '{':
            d += 1
            started = True
        elif ch == '}':
            d -= 1
            if started and d == 0:
                end_line = sline + code.count(chr(10), 0, idx)
                break
    # 表达式体 `= try { ... } catch (...) { ... }`：括号配对在 try 的 `}` 处就结束了，
    # catch 落在后面 —— 向后吸到那段 `catch ... { ... }` 结束，否则"兜住异常"这条
    # 判据对它恒假（本轮真踩到）。
    text = src.split(chr(10))
    end = min(end_line, len(text))
    tail = chr(10).join(text[sline - 1:end])
    idx = text[min(end, len(text)) - 1:min(end, len(text)) + 4] if end < len(text) else []
    after = ' '.join(text[end:end + 8])
    if 'catch' in after:
        # 把 catch 块也吸进来（按花括号配对，从 `catch` 所在处起）
        j = end
        while j < len(text) and 'catch' not in text[j]:
            j += 1
        if j < len(text):
            # 从 catch 行开始配对花括号
            k = j
            depth = 0
            started = False
            while k < len(text):
                depth += text[k].count('{') - text[k].count('}')
                if '{' in text[k]:
                    started = True
                if started and depth <= 0:
                    break
                k += 1
            tail = chr(10).join(text[sline - 1:k + 1])
    return tail


def main():
    ea = Path(sys.argv[1]).read_text(encoding='utf-8')
    eng = Path(sys.argv[2]).read_text(encoding='utf-8')
    bad = 0

    # ① preparedMessages 体内真的走了 ragBlockFor
    body, line = kotlin_fn_body(ea, 'preparedMessages')
    if body is None:
        print("FAIL  找不到 preparedMessages（App 内生成的唯一拼 prompt 出口）")
        bad += 1
    else:
        has_call = 'ragBlockFor(' in body
        has_attach = 'RagInject.attachBeforeLastUser' in body
        if not has_call:
            print("FAIL  preparedMessages 没有调用 ragBlockFor —— 检索结果永远进不了 prompt")
            bad += 1
        else:
            print("PASS  preparedMessages 调用了 ragBlockFor（第 %d 行起）" % line)
        if not has_attach:
            print("FAIL  preparedMessages 没有用 RagInject.attachBeforeLastUser 拼接"
                  "（自己拼会绕开 注入为空即零字节 的契约）")
            bad += 1
        else:
            print("PASS  preparedMessages 用 RagInject.attachBeforeLastUser 拼接")
        # [!] _fnbody 会抹掉字符串字面量，所以 role == "system" 在体内看起来是
        #     role == "" —— 这条判据必须读**原文**区间（raw_fn）。
        #
        # 判据方向（第 4 步起）：**不许**再出现"往 system 段注入"的写法。
        # 只判"调了 attachBeforeLastUser"是不够的 —— 两处都调（既注 system 又注 user）
        # 照样能绿，而那样分叉点仍在 pos 0，本步的收益全没了（就是第 4 步要修的现象）。
        #
        # 锚"system 段被改写"这个**语义**，不锚某一个函数名：换个名字重写注入
        # （`attachSys` / 手写 `system to content + block`）同样要把分叉点拉回 pos 0，
        # 而锚名字的判据一改名就恒绿。这里判的是原文里"取 system 那一格"的两种写法。
        raw = raw_fn(ea, 'preparedMessages') or ''
        sys_writes = (
            re.search(r'\"system\"\s*to\s', raw) is not None          # system to <新内容>
            or re.search(r'role\s*==\s*\"system\"\s*\)', raw) is not None  # if (role == "system") …
        )
        if sys_writes:
            print("FAIL  preparedMessages 仍往 system 段注入 —— "
                  "分叉点会被拉回 pos 0、KV 复用归零；注入只许落在最后一条 user 之前")
            bad += 1
        else:
            print("PASS  注入不再落在 system 段（分叉点在本轮提问处）")

    # ④ ragBlockFor 必须把开关与库名一起交给引擎
    rb, rline = kotlin_fn_body(ea, 'ragBlockFor')
    if rb is None:
        print("FAIL  找不到 ragBlockFor（App 侧注入的唯一入口）")
        bad += 1
    else:
        for needle, desc in (
            ('RagInject.currentKb', '当前库名取自持久化'),
            ('RagInject.enabled', '开关状态取自持久化'),
            ('LlmEngine.ragContext', '检索交给引擎的 ragContext'),
        ):
            if needle not in rb:
                print("FAIL  ragBlockFor 缺少 %s（%s）" % (needle, desc))
                bad += 1
            else:
                print("PASS  ragBlockFor 的%s（%s）" % (desc, needle))

    # ⑤ 生成路径不许各写一份注入：attachBeforeLastUser 的调用点必须是**已知的两个**
    # [!] 按**函数**计，不按文件计：一处逻辑只应有一个调用点。真正要守的是
    #     "别的函数里不许再拼一份"——而不是"只能有一个"。
    #
    # 为什么从"恰好一个"放开成"恰好这两个"（阶段 3）：远端路径要把同一份注入块
    # 拼进 HTTP 请求的 messages，而它与 App 路径**天然是两个入口**（两条消息来源
    # 不同、开关与库名各取一对键）。硬要求仍然成立且更严格：**除这两个之外**
    # 任何函数再拼一份都要红 —— 那才是"注入多一份"的真实来源。
    # 放开成"恰好这两个"而不是"至少一个"：漏掉某个入口（注入在一条路径上
    # 静默消失）同样要红，否则远端路径没接上时这里会是绿的。
    # 判据锚**函数**（`文件名.函数名`），不锚行号与顺序：换实现位置不该红。
    expected_callsites = {'EngineActivity.kt.preparedMessages', 'HttpApi.kt.handleChat'}
    src_dir = Path(sys.argv[1]).resolve().parent
    offenders = []
    for f in src_dir.glob('*.kt'):
        text = f.read_text(encoding='utf-8')
        for fn in re.findall(r'\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]', text):
            if fn == 'attachBeforeLastUser':
                continue          # RagInject.kt 里的定义本身
            b, _ = kotlin_fn_body(text, fn)
            if b and 'RagInject.attachBeforeLastUser' in b:
                offenders.append('%s.%s' % (f.name, fn))
    if set(offenders) != expected_callsites:
        print("FAIL  attachBeforeLastUser 的调用点是 %s（应恰好是 %s）"
              % (sorted(set(offenders)) or '无', sorted(expected_callsites)))
        bad += 1
    else:
        print("PASS  attachBeforeLastUser 只在已知两个入口被调用（注入不会在别处多一份）")

    # ⑥ 引擎侧：ragContext 必须真的走 shouldSearch，且兜住异常
    # [!] kotlin_fn_body 返回 (body, line) 元组 —— 直接拿元组做 `in` 判断会恒假。
    rc, _rline = kotlin_fn_body(eng, 'ragContext')
    if rc is None:
        print("FAIL  LlmEngine 里找不到 ragContext（App 侧检索无出口）")
        bad += 1
    else:
        if 'RagInject.shouldSearch' not in rc:
            print("FAIL  ragContext 没有走 RagInject.shouldSearch —— 开关判据多了一套，必然漂移")
            bad += 1
        else:
            print("PASS  ragContext 走 RagInject.shouldSearch（开关判据只有一处）")
        if 'search(' not in rc:
            print("FAIL  ragContext 没有调用 search（检索永不发生）")
            bad += 1
        else:
            print("PASS  ragContext 调用了 search")
        if 'RagInject.select' not in rc or 'RagInject.render' not in rc:
            print("FAIL  ragContext 没有走 RagInject.select / render（各写一份会漂移）")
            bad += 1
        else:
            print("PASS  ragContext 走 RagInject.select + render")
        # [!] ragContext 是表达式体 `= try { ... } catch (...) { ... }`，catch 落在
        #     try 的 `{}` **之外** —— 只看函数体的括号配对会拿不到 catch（假红）。
        #     改读 raw_fn（含签名到函数结束的整段原文）。
        full = raw_fn(eng, 'ragContext') or ''
        if 'catch' not in full:
            print("FAIL  ragContext 没有兜住异常（检索失败会连带整轮生成失败）")
            bad += 1
        else:
            print("PASS  ragContext 兜住了异常（检索问题不让请求失败）")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
