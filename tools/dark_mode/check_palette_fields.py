#!/usr/bin/env python3
"""两列配色的**逐字段对账**（纯静态）。

═══════════════════════════════════════════════════════════════════════════
这一份守的是什么
═══════════════════════════════════════════════════════════════════════════
暗色与浅色两列是**手写的两份**（刻意不做"从一个推导另一个"，理由见
`ThemePalette.kt` 的对象头）。手写两份就意味着有"只有一份"的失败模式：

  · 浅色那一列多给了一个字段、暗色那一列漏了 —— Kotlin 的具名参数会
    编译不过，所以这一族其实被编译器挡住（**只要两列都是具名参数**）；
  · 真正危险的是另一族：**字段在两列里都给了，但暗色那列抄的是浅色原值**
    （复制粘贴时忘了改）。它编译得过、跑得动、也"看起来有暗色"，
    只有在暗底上那处字变成了同色 —— 也就是"某一小块看不见"。
  · 还有一族同样静默：`ThemePalette` 里定义了字段，但**没有任何取色点用它**
    （比如定义了 `onSurface` 却没人取）。那说明"这一处本来该用它"的地方
    还在写死某个值 —— 而那个值在暗色下就是错的。

所以本脚本做两件事，各自对应上面两族：
  · **默认模式**：两列给的字段集合必须完全相等，且与 data class 的字段集合相等
    （三处对账：`ThemePalette.kt` 的声明、`LIGHT_PALETTE`、`DARK_PALETTE`）；
  · **`--used` 模式**：每个字段都必须在 `EngineActivity.kt` 里被
    `pal.<字段>` 取用过（定义了没人用 = 那处还是写死的）。

`--same` 模式是第三条（同一字段在两列里取了同一个值），刻意**不做成判据**：
`onSurface` 这类字段在两列里取同一值未必是错（虽然本仓库两列都不重），
把它做成硬判据会逼着后来人给一个**假的**暗色值去凑绿 —— 那比漏一个字段更坏。
本仓库对"看起来量准了的假读数"判据一向是硬的，这条同理。

用法：
    python3 tools/dark_mode/check_palette_fields.py <EngineActivity.kt> <ThemePalette.kt>
    python3 tools/dark_mode/check_palette_fields.py --used <EngineActivity.kt> <ThemePalette.kt>
"""
import re
import sys


def strip_comments(s):
    """剥注释。

    ⚠ **必须认字符串字面量**：天真的 `re.sub(r"/\*.*?\*/", ...)` 会把
    `"/*"` 这种**字符串里的**注释起始符当成真的块注释起点，一路吞到很远处的
    下一个 `*/` —— 实测在 `EngineActivity.kt` 上它一口气吃掉 75 KB（从一句
    `sendText("/*")` 一直吞到几十个函数之后），于是"剥注释后的源码"里
    大段的 `pal.xxx` 凭空消失，判据恒红（而那条红看起来像源码的问题）。
    本仓库在 `run_dual_model_guard_tests.sh` 的桩⑧上踩过同一族（行注释里的
    `/*` 吃掉中间真实代码 → 守卫假红）。
    """
    out = []
    i = 0
    n = len(s)
    while i < n:
        ch = s[i]
        if ch == '"':
            # 字符串字面量：整段原样留下（含里面的 `//` 与 `/*`）
            j = i + 1
            while j < n:
                if s[j] == "\\":
                    j += 2
                    continue
                if s[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(s[i:j])
            i = j
            continue
        if ch == "/" and i + 1 < n and s[i + 1] == "/":
            j = s.find("\n", i)
            i = n if j < 0 else j
            continue
        if ch == "/" and i + 1 < n and s[i + 1] == "*":
            j = s.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def palette_block(src, name):
    """取 `private val NAME = ThemePalette( ... )` 的实参段。"""
    i = src.find(name)
    if i < 0:
        return None
    j = src.find("ThemePalette(", i)
    if j < 0:
        return None
    k = src.find("(", j)
    depth = 0
    end = k
    while end < len(src):
        if src[end] == "(":
            depth += 1
        elif src[end] == ")":
            depth -= 1
            if depth == 0:
                break
        end += 1
    return src[k + 1:end]


def named_args(block):
    """从具名参数段里取出 `字段名 -> 值`。"""
    out = {}
    for m in re.finditer(r"(\w+)\s*=\s*([^,]+?)(?:,|$)", block, flags=re.S):
        out[m.group(1)] = m.group(2).strip()
    return out


def declared_fields(src):
    """`ThemePalette` 的字段名（`val xxx: Int`，逐行）。"""
    body = src[src.index("data class ThemePalette("):]
    return set(re.findall(r"^\s*val (\w+): Int,?\s*$", body, flags=re.M))


def main():
    args = sys.argv[1:]
    used_mode = False
    if args and args[0] == "--used":
        used_mode = True
        args = args[1:]
    if len(args) != 2:
        print(__doc__)
        return 2
    act = read(args[0])
    pal_src = read(args[1])
    pal_src_nc = strip_comments(pal_src)
    act_nc = strip_comments(act)

    declared = declared_fields(pal_src_nc)
    if not declared:
        print("FAIL  取不到 ThemePalette 的字段声明（锚点漂了？）")
        return 1

    # ⚠ 两列定义在 **EngineActivity.kt** 里（它们要跟那一屏的取色点放在一起，
    #   而 `ThemePalette.kt` 只有类声明）——所以两列从 `act_nc` 取、字段声明从
    #   `pal_src_nc` 取。此前把两者都从 `pal_src_nc` 取，判据恒红（"取不到
    #   LIGHT_PALETTE"），而那条红看起来像源码的问题。
    light = named_args(palette_block(act_nc, "LIGHT_PALETTE") or "")
    dark = named_args(palette_block(act_nc, "DARK_PALETTE") or "")
    if not light or not dark:
        print("FAIL  取不到 LIGHT_PALETTE / DARK_PALETTE（锚点漂了？）")
        return 1

    bad = 0

    if used_mode:
        # 每个字段都要有 `pal.<字段>`（词边界，防 `pal.text` 命中 `pal.textMuted`）。
        # ⚠ 不剥注释会**恒真**：本文件通篇的注释里正引用着 `pal.xxx`（那是在
        #   讲"为什么这类取色点必须走它"）。带注释扫等于"只要有人在注释里提一句
        #   就算用过了"——本仓库对此的判据一向是"剥注释后再判"。
        unused = sorted(f for f in declared
                        if not re.search(r"\bpal\.%s\b" % re.escape(f), act_nc))
        if unused:
            print("FAIL  定义了却没被任何取色点用到：%s" % ", ".join(unused))
            print("      （定义了没人用 = 那一处还在写死某个值，而它在暗色下是错的）")
            bad += 1
        else:
            print("PASS  配色表的 %d 个语义字段**全部**被取用" % len(declared))
        return 1 if bad else 0

    for name, block in (("LIGHT_PALETTE", light), ("DARK_PALETTE", dark)):
        miss = sorted(declared - set(block))
        extra = sorted(set(block) - declared)
        if miss or extra:
            print("FAIL  %s 与字段声明对不上：缺 %s / 多 %s"
                  % (name, miss or "无", extra or "无"))
            bad += 1
        else:
            print("PASS  %s 逐字段给全了（%d 项）" % (name, len(block)))

    # 取色点里不许出现「浅色那列的名字」——写了 `LIGHT_PALETTE.xxx` 的取色点
    # 在暗色下必然错，而它**编译得过**（那两列是同一类型）。
    stray = re.findall(r"\b(?:LIGHT|DARK)_PALETTE\.\w+", act_nc)
    if stray:
        print("FAIL  取色点直接引用了某一列本身：%s"
              % ", ".join(sorted(set(stray))))
        print("      （应当经 `pal`，否则那一处在另一种主题下必然错）")
        bad += 1
    else:
        print("PASS  取色点没有绕开 `pal` 直接引用某一列")

    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
