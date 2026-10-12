#!/usr/bin/env python3
"""断言「远端知识库」（RAG 阶段 3）**真的接上了 HTTP 链路**，且默认**关**。

═══ 这一条守的是什么 ═══
远端注入的失效形态**全是不崩不报错的**，而且比前两阶段更隐蔽：

  ① 开关读成默认 **true** —— 升级后所有没动过设置的人**突然开始**被注入知识库内容
     （接口照 200、结构合法），而他们从没要求过。这是本阶段唯一"会主动伤害
     用户"的错误，所以它是第一条判据。
  ② 远端库名与 App 内那个**共用** —— App 里点一下别的库，远端某个正在轮询的
     客户端就**静默**换库。回答变差，而没有任何现象指向"库换了"。
  ③ 注入没接到 HTTP 路径上（只接到 App 内那条）—— 用户在设置页开了开关、
     远端客户端照旧不注入，而**一切正常**：这就是阶段 0 那个"调用方为 0"
     的复现形态（native / 存储层全绿，用户点下去什么都不会发生）。
  ④ 协议层字段漏在**某一条**响应路径上（流式 / 非流式）—— 那条路径上的调用方
     永远看不到"被注入了"，正是这个字段要消除的盲区。
  ⑤ 开关判据写了两处 —— 漂移的方向是"多注入一个字节"，零症状。

判据锚**结构关系**，不锚"文件里出现过某关键字"（后者恒真：定义处、注释里都有）。

用法：check_wiring.py <源码目录>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402
from strip_comments import strip_kt  # noqa: E402


def main():
    if len(sys.argv) < 2:
        print("用法：check_wiring.py <源码目录>")
        return 2
    src = Path(sys.argv[1])
    inj = (src / "RagInject.kt").read_text(encoding="utf-8")
    proto = (src / "RagProtocol.kt").read_text(encoding="utf-8")
    api = (src / "HttpApi.kt").read_text(encoding="utf-8")
    ea = (src / "EngineActivity.kt").read_text(encoding="utf-8")

    bad = 0

    def ck(name, ok, detail=""):
        nonlocal bad
        if ok:
            print("PASS  " + name)
        else:
            print("FAIL  " + name + (" —— " + detail if detail else ""))
            bad += 1

    # ① 远端开关的默认值**必须是 false**
    #    只判"有 remoteEnabled 函数"是恒真的：把默认值改成 true 它照样在。
    #    锚的是**取值调用的实参**（getBoolean(键, false)），不是"出现过 false"。
    ck("远端开关读出默认 false（远端默认不注入）",
       re.search(r'getBoolean\(\s*KEY_REMOTE_ENABLED\s*,\s*false\s*\)', inj) is not None,
       "remoteEnabled 的默认值不是 false —— 升级后所有人会被静默注入")
    # 反向：App 内那个开关的默认值仍必须是 true（不许"顺手统一"）
    ck("App 内开关默认仍是 true（不被远端那个带偏）",
       re.search(r'getBoolean\(\s*KEY_ENABLED\s*,\s*true\s*\)', inj) is not None,
       "App 内开关的默认值被改了 —— 配好了却永远用不上")

    # ② 远端库名与 App 内库名用**不同的 prefs 键**
    def const_of(key):
        m = re.search(r'const val ' + key + r'\s*=\s*"([^"]+)"', inj)
        return m.group(1) if m else None

    kk = {k: const_of(k) for k in
          ("KEY_ENABLED", "KEY_CURRENT", "KEY_REMOTE_ENABLED", "KEY_REMOTE_KB")}
    ck("四个 prefs 键全部定义（缺一个就是某个值无处落）",
       all(v for v in kk.values()), f"实际 {kk}")
    vals = list(kk.values())
    ck("四个键两两不同（撞名 = 互相覆盖）",
       len(vals) == len(set(vals)), f"实际 {vals}")
    ck("远端库名的键与 App 内当前库**不是同一个**",
       kk["KEY_REMOTE_KB"] != kk["KEY_CURRENT"],
       "两个库名共用键 —— 在 App 里切库会把远端正在用的库静默换掉")
    ck("远端开关的键与 App 内开关**不是同一个**",
       kk["KEY_REMOTE_ENABLED"] != kk["KEY_ENABLED"])

    # ③ 远端判据走的是**同一个** shouldSearch（不是另写一遍三条件）
    #
    # ⚠ 这里用**正则取表达式体**而不是 kotlin_fn_body：`shouldSearchRemote` 是
    #   表达式体 `= shouldSearch(...)`，没有自己的 `{` —— 按括号配对会取到**下一个
    #   函数**（setEnabled）的函数体，判据就跑到别人身上去了（本轮真踩到，
    #   报出来的"函数体"是 setEnabled 的内容）。
    m = re.search(r'fun\s+shouldSearchRemote\s*\([^)]*\)[^=]*=\s*([^\n]*)', inj)
    rb = m.group(1) if m else None
    ck("shouldSearchRemote 复用 RagInject.shouldSearch（判据只有一处）",
       rb is not None and 'shouldSearch(' in rb
       and 'remoteEnabled(ctx)' in rb and 'remoteKb(ctx)' in rb,
       f"函数体：{(rb or '')[:160]}")

    # ④ HTTP 路径真的注入了
    hc, _ = kotlin_fn_body(api, 'handleChat')
    # ⚠ 判据必须锚"**作为 ragContext 的实参**"，不能只锚"函数体里出现过 remoteEnabled"：
    #    那个标识符在紧跟其后的 `else if (ragActive())` 分支里也出现，于是把 ragContext
    #    那一次的取值换成字面量 `false`（= 远端开关根本没被读）时，只出现即命中的写法
    #    照样绿（自测的桩⑤ 就是这个形态，本轮真踩到）。
    #
    # ⚠ 首参的**实参必须走 `HttpApi.ragPrefs()`**（＝ `appContext`），不许是裸 `this`，
    #    也不许是 `appContext!!` / `appContext ?: ...` 的内联写法：
    #      · 裸 `this` —— `HttpApi` 是 `object`，`this` 不是 `Context`，**编译不过**
    #        （0.9.168 的 CI 就是红在这里：六处 `RagInject.*(this)` 类型不匹配）；
    #      · 各个调用点自己判空/自己回退 —— 漏判一处就是"静默按未配置处理"，
    #        接口照 200、内容不注入，与"用户没开"完全同形。取值口只留一个才能防住。
    #    钉在位次上（`ragContext(` 之后第一个参数），而不是"函数体里出现过 ragPrefs"。
    ck("handleChat 把远端开关作为 ragContext 的实参传入（真的被读）",
       hc is not None and re.search(
           r'ragContext\(\s*ragPrefs\(\)\s*,\s*remoteKb\s*,\s*lastUserText\s*,\s*'
           r'RagInject\.remoteEnabled\(ragPrefs\(\)\)\s*\)', hc) is not None,
       "HTTP 路径没把远端开关传给 ragContext —— 开了也不会注入（阶段 0 那个『调用方为 0』的复现）")
    ck("handleChat 的库名取自 RagInject.remoteKb（不是 App 内那个）",
       hc is not None and 'val remoteKb = RagInject.remoteKb(ragPrefs())' in hc)
    # ③b `Context` 只从 ragPrefs() 一个口出，且 RAG 相关调用**一处都不许写裸 `this`**。
    #     这两条正面钉住 0.9.168 那个编译阻断：写 `this` 会在 CI 上红，
    #     但红的是一条类型错误，而"为什么当时会写成 this"（object 单例 vs Context）
    #     在这里才有答案。锚的是**调用实参**，不是"文件里没有 this"（那恒红）。
    #     ⚠ 判据前**必须先去注释**再匹配：`RagInject.remoteKb(this)` 这个**错法**
    #       本身就写在上面那段注释里（作为"为什么不许这么写"的说明），
    #       不剥注释的写法会抓到自己的说明文字 —— **恒红**，而且看起来像真有 bug。
    #       这是本轮第二次踩"判据抓到注释"（第一次是桩⑤的标识符形态），
    #       所以复用 `strip_comments.strip_kt`（本目录已有的一份），不另写第二份。
    ck("RAG 调用一律走 ragPrefs()，没有裸 this 当 Context",
       re.search(r'RagInject\.(remoteEnabled|remoteKb)\(\s*this\s*\)', strip_kt(api)) is None
       and re.search(r'ragContext\(\s*this\s*,', strip_kt(api)) is None,
       "有调用点把 HttpApi 这个 object 当 Context 传了 —— 那是编译不过的（类型不匹配）")
    ck("远端取值口只判一次「开关 + 库名都满足」（ragActive）",
       hc is not None and 'else if (ragActive())' in hc,
       "『开关开着但没注入』那一支又自己拼了一遍条件 —— 两处判据必然漂移")
    ck("ragContext 的首参走 HttpApi 自己的取值口（不是各调用点自己判空）",
       re.search(r'ragContext\(\s*ragPrefs\(\)', api) is not None
       and 'fun ragPrefs()' in api,
       "没有 ragPrefs()（或首参不是它）—— 各点自己写 appContext!! / ?: 会在漏判处静默降级")
    ck("handleChat 调 LlmEngine.ragContext（检索真的发生）",
       hc is not None and 'ragContext' in hc)
    ck("handleChat 用 RagInject.attachBeforeLastUser 拼接（不是自己拼字符串）",
       hc is not None and 'attachBeforeLastUser' in hc)
    ck("注入落在渲染之前（工具/思考判定看到的是最终 messages）",
       hc is not None and hc.index('ragContext') < hc.index('applyChatTemplate'))

    # ⑤ 协议层字段：三条响应路径都取同一处
    ck("非流式响应带 local_rag（usage 之后）",
       re.search(r'"usage":\$usage\$ragField\}', api) is not None
       or '"usage":$usage$ragField' in api,
       "非流式响应没带 local_rag —— 那条路径上的调用方看不到被注入了")
    ck("流式收尾帧带 local_rag",
       re.search(r'chatChunkJson\(id, modelName, created, "\{\}", fin, lpField, ragField\)', api)
       is not None,
       "流式收尾帧没带 local_rag")
    # 判据锚"**取值**调用"的次数，不锚"`val ragField = ` 这个写法"：
    # 后者会被 `val ragField2 = ragFieldOf(...)` 这种"又拼一份"的写法绕过去
    # （自测的桩⑩ 就是这个形态，本轮真踩到）。
    # 1 次 = 三条响应路径共用同一个值；>1 次 = 有路径在自己拼，漂移方向是"某条路径上没了"。
    # ⚠ 实参是 `(lastRagKb, lastRagInjected.get())` —— 库名与条数**同源取一次**。
    # 判据只锚"取值调用"这一件事：把库名也带上之后，正则要跟着走，
    # 否则这条断言会以"0 处"的形式**恒假**（本轮真踩到：改了签名没改判据）。
    n_field = len(re.findall(r'ragFieldOf\(lastRagKb, lastRagInjected\.get\(\)\)', api))
    ck("ragField 只算一次（三条路径共用，不各拼一份）",
       n_field == 1, f"实际 {n_field} 处 ragFieldOf(lastRagKb, lastRagInjected.get())")
    ck("注入条数经 lastRagInjected 传出（不是从文本里数）",
       hc is not None and 'lastRagInjected.set(rag.count)' in hc)
    ck("每轮请求开始时先把 lastRagInjected 清零（否则读到的是上一轮）",
       hc is not None and hc.index('lastRagInjected.set(0)') < hc.index('ragContext'))
    fb, _ = kotlin_fn_body(proto, 'responseField')
    # 判据：`inj == null` 那一支返回**空的原生串**。写法上 `""` 在三引号串里是
    # 两个字符——但 `_fnbody` 会把字符串字面量抹成 `""`，所以这里读**原文**
    # （含引号），锚的是 `if (inj == null) ""` 这个形状，而不是"出现过空串"。
    fraw = re.search(r'fun\s+responseField[^\n]*\n(?:[^\n]*\n)*?[^\n]*if \(inj == null\) ""',
                     proto)
    ck("没注入时 responseField 返回空串（响应里一个字节都不多）",
       fraw is not None or (fb is not None and 'inj == null' in fb and
                            re.search(r'if\s*\(inj == null\)\s*""', proto) is not None),
       "responseField 的空值分支不是空串 —— 恒出现字段会让『没开』与『开了没命中』不可分")

    # ⑥ /health 三个字段进的是同一次拼接
    ck("/health 出 rag_remote_enabled", 'rag_remote_enabled' in api)
    ck("/health 出 rag_remote_kb", 'rag_remote_kb' in api)
    ck("/health 出 rag_remote_injected", 'rag_remote_injected' in api)
    ck("/health 的库名走 RagProtocol.kbJson（转义收在一处）",
       'RagProtocol.kbJson' in api)

    # ⑦ App 内两个开关是两个控件、两个控件写两个键
    ck("App 内远端开关写的是 setRemoteEnabled",
       'RagInject.setRemoteEnabled' in ea and 'RagInject.setEnabled' in ea)

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
