#!/usr/bin/env python3
"""断言「嵌入模型」在 App 里**真的有入口**（不是写好了没人调）。

═══ 这一条守的是什么 ═══
RAG 阶段 0 把 `LlmEngine.loadEmbedModel` / `unloadEmbedModel` 写好了，但**调用方为 0**
（全仓 `.kt` 里只出现在 `LlmEngine.kt` 自己文件内）。于是用户按对话模型的步骤去加载
向量模型时，实际走的永远是 `loadModel` → 两个文件先后装进**同一个槽**。
症状是「对话模型和向量模型互相顶掉」，而它不是任何一处代码写错 —— 是缺少接线。

这类缺口用单测测不出来（native 侧双常驻是真做完了的，守卫全绿），
只能用「**调用方存在性**」这条结构判据钉住：入口一旦被删/被改回只调 loadModel，
必须变红。

判据锚「某个 UI 函数体内调用了哪个引擎方法」，不锚"文件里出现过 loadEmbedModel"
—— 后者恒真（定义处、注释里都会出现），前者才是「有入口」本身。
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dual_model"))
from _fnbody import kotlin_fn_body, kotlin_fn_span  # noqa: E402


def raw_span(src: str, name: str):
    """按 `{` 配对切出函数在**原文**里的区间（保留字符串字面量）。

    `kotlin_fn_body` 会把字符串字面量抹成 `""`，而状态行的读数写在字符串模板里
    （`"n_embd=${LlmEngine.embedDim}"`）—— 用函数体会把那条判据判成**假红**。
    """
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
                end_line = sline + code.count('\n', 0, idx)
                break
    lines = src.split('\n')
    if end_line > len(lines):
        return None
    return '\n'.join(lines[sline - 1:end_line])


def main():
    src_path = sys.argv[1]
    src = Path(src_path).read_text(encoding='utf-8')
    bad = 0

    # ① 存在一个 UI 函数，体内真的调用 LlmEngine.loadEmbedModel
    body, line = kotlin_fn_body(src, 'doLoadEmbed')
    if body is None:
        print("FAIL  找不到 doLoadEmbed —— 嵌入模型没有加载入口（这正是本轮要修的缺口）")
        bad += 1
    elif 'LlmEngine.loadEmbedModel' not in body:
        print("FAIL  doLoadEmbed 体内没有调用 LlmEngine.loadEmbedModel（按钮点下去不会加载）")
        bad += 1
    else:
        print(f"PASS  doLoadEmbed 调用了 LlmEngine.loadEmbedModel（第 {line} 行起）")

    # ② 卸载入口
    body2, line2 = kotlin_fn_body(src, 'doUnloadEmbed')
    if body2 is None or 'LlmEngine.unloadEmbedModel' not in body2:
        print("FAIL  找不到调用 LlmEngine.unloadEmbedModel 的卸载入口")
        bad += 1
    else:
        print(f"PASS  doUnloadEmbed 调用了 LlmEngine.unloadEmbedModel（第 {line2} 行起）")

    # ③ 两个入口都接到了按钮上（有入口还不够：按钮没接等于没有）
    for fn, btn in (('doLoadEmbed', 'embedLoadBtn'), ('doUnloadEmbed', 'embedUnloadBtn')):
        if not re.search(rf'{btn}\.setOnClickListener\s*\{{\s*{fn}\(', src):
            print(f"FAIL  {btn} 的点击没有接到 {fn}（按钮是死的）")
            bad += 1
        else:
            print(f"PASS  {btn} 的点击接到 {fn}")

    # ④ 状态行渲染函数存在且被列表刷新调用（否则 UI 上读不到"装上没有"）
    if 'fun renderEmbedStatus' not in src:
        print("FAIL  没有 renderEmbedStatus（用户看不到嵌入模型加载状态）")
        bad += 1
    else:
        rb, _ = kotlin_fn_body(src, 'renderEmbedList')
        if rb is None or 'renderEmbedStatus()' not in rb:
            print("FAIL  renderEmbedList 没有调用 renderEmbedStatus（状态行不会刷新）")
            bad += 1
        else:
            print("PASS  renderEmbedList 调用了 renderEmbedStatus")

    # ⑤ 状态行的两个读数必须来自**引擎真值**，不能是本页自己的变量
    sb = raw_span(src, 'renderEmbedStatus')
    if sb is None:
        print("FAIL  找不到 renderEmbedStatus 函数体")
        bad += 1
    else:
        for needle, desc in (('LlmEngine.hasEmbedModel', '已加载判据取引擎真值'),
                             ('LlmEngine.embedDim', '维度取引擎真值')):
            if needle not in sb:
                print(f"FAIL  renderEmbedStatus 的{desc} —— 少了 {needle}")
                bad += 1
            else:
                print(f"PASS  renderEmbedStatus 的{desc}（{needle}）")

    # ⑥ 选中的嵌入模型必须与对话模型**分开存**（合并一个键会让两个角色互相改）
    ms = Path(src_path).parent / 'ModelStore.kt'
    msrc = ms.read_text(encoding='utf-8')
    if 'KEY_EMBED_MODEL' not in msrc:
        print("FAIL  ModelStore 没有独立的嵌入模型键（KEY_EMBED_MODEL）")
        bad += 1
    else:
        m = re.search(r'const val KEY_EMBED_MODEL\s*=\s*"([^"]+)"', msrc)
        m2 = re.search(r'const val KEY_SELECTED\s*=\s*"([^"]+)"', msrc)
        if not m or not m2:
            print("FAIL  取不到两个选中键的字面量（判据锚点失效）")
            bad += 1
        elif m.group(1) == m2.group(1):
            print(f"FAIL  嵌入与对话共用同一个 prefs 键「{m.group(1)}」—— 两个角色会互相顶掉")
            bad += 1
        else:
            print(f"PASS  嵌入选中键「{m.group(1)}」与对话「{m2.group(1)}」分开")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
