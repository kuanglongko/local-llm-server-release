#!/usr/bin/env python3
"""生成「分块规则」两边比对的语料。

═══════════════════════════════════════════════════════════════════════════
为什么语料要**确定性**（固定种子、不落随机）
═══════════════════════════════════════════════════════════════════════════
这条测试比的是"两侧在同一份输入上逐字节相同"。语料一旦随每次运行变化，
红的时候无法复现 —— 而"偶发红"最后总是被当成 flaky 跳过，等于没有这条测试。
所以用固定种子生成，红时必须能拿同一份语料原样复跑。

语料只用 **ASCII**：C++ 的 `targetChars` 按**字节**算，Kotlin 按**字符**
（UTF-16 code unit）算 —— 这是既有的单位差（两边各自都自洽），
所以"逐字节相同"这条断言只在 ASCII 上成立（那里字节 == 字符）。
CJK 上的不变式（码点对齐 / 不空块）由各自的单测与覆盖守卫钉住，
不放在这条差分测试里 —— 混进来会让这条测试时红时绿，反而被跳过。

用法：gen_corpus.py [条数]   输出到 stdout（"target \t overlap \t len \t 正文"）
"""
import random
import sys

# ASCII-only 字母表：覆盖段落/换行/半角句读/硬截四种降级路径
ALPHA = "ab \n,;!?.-x0"


# CJK 语料：看的是"块边界落在第几个字符"（单位无关），因此能用同一套探针比。
# 它专测 ASCII 语料测不到的那几条分隔符（。！？；，、）—— 而"、漏了"这类
# 差异在 ASCII 语料上**完全不可见**（本轮真踩到：拿掉 Kotlin 的顿号，ASCII
# 差分照样全绿）。
# ⚠ CJK 语料必须**每个字符都是 3 字节**（含分隔符），否则两边窗口覆盖的字符数不同 ——
#    C++ 的 target 按**字节**算、Kotlin 按**字符**算，这是既有单位差（不是规则差）。
#    在"所有字符等长"的语料上，C++ 给 target=3N、Kotlin 给 target=N，两边窗口
#    就覆盖**同样多的字符**，于是"块边界落在第几个字符"是可比的。
#    换行在这里是**唯一的 1 字节字符** —— 对它单独按行处理（见 main 的 cjk 分支：
#    语料里不放换行，换行由定死的边界样例单独覆盖，且那几条也用 3N/N 换算）。
CJK3_ALPHA = ["中", "文", "天", "。", "，", "、", "！", "？", "；"]
# emoji 是 4 字节，所以这一档的语料**只由 4 字节码点**组成，target = 4N / N。
EMOJI4_ALPHA = ["😀", "🙂", "🚀", "🔥"]


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "ascii"
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 1200
    rng = random.Random(20241005)          # 固定种子：红时可原样复跑
    out = []
    # 先放几条**定死**的边界样例（随机很难撞到的形态）
    fixed = [
        ("", 20, 0),
        ("x", 20, 0),
        ("   ", 20, 0),
        ("a,b;c!d?e.f,g;h!i?j.", 6, 0),
        ("a,b;c!d?e.f,g;h!i?j.", 6, 2),
        ("para1\n\npara2\n\npara3", 8, 0),
        ("line1\nline2\nline3\nline4", 8, 0),
        ("AAAAAAAAA\nBBBBBBBBBBBBBBBBBBBBBBBBBB", 10, 0),
        ("AAAAAAAAA\nBBBBBBBBBBBBBBBBBBBBBBBBBB", 20, 0),
        ("no-sep-at-all-here-just-plain-text", 7, 0),
        ("abcdefghijklmnopqrstuvwxyz", 12, 4),
        ("a" * 1000, 4, 0),
        ("z" * 60, 6, 3),
        # 末尾有换行 / 只有换行（trim 的边界）
        ("a\n", 3, 0),
        ("\n\n\n", 3, 0),
        ("a\n\n\nb", 3, 0),
    ]
    for t, tg, ov in fixed:
        out.append((t, tg, ov))
    if mode in ("cjk", "emoji"):
        emoji = (mode == "emoji")
        alpha = EMOJI4_ALPHA if emoji else CJK3_ALPHA
        unit = 4 if emoji else 3          # 每个字符的字节数（本档语料全部等长）
        out = []
        # 定死的边界样例（同样是等长字符）
        fixed = [
            ("中文。中文！中文？中文；中文，中文、中文", 8, 0),
            ("甲" * 50 + "、", 12, 0),
            ("天地玄黄宇宙洪荒" * 40, 24, 0),
        ]
        if emoji:
            fixed = [("😀" * 20, 5, 0), ("😀🙂🚀🔥" * 20, 7, 0), ("😀" * 60, 12, 0)]
        for t, tg, ov in fixed:
            out.append((t, tg, ov))
        while len(out) < n:
            ln = rng.randint(1, 60)
            text = "".join(rng.choice(alpha) for _ in range(ln))
            tg = rng.choice([1, 2, 3, 4, 5, 6, 7, 8, 12, 20, 64])
            # ⚠ ov 必须**严格小于** tg：`ov >= tg` 时两边各自把 overlap 归一成
            #   `target/2`（C++ 按字节、Kotlin 按字符）—— 那个半值本身是"单位相关"的，
            #   于是归一后的窗口覆盖的字符数不同，差分必红。那不是规则分家，
            #   是"两个单位下的 target/2 不是同一个东西"。这一档由单测覆盖
            #   （两边都不会死循环），不放进取分语料。
            ov = rng.choice([0, 0, 0, 1, 2, 3, 5])
            if ov >= tg:
                ov = 0
            out.append((text, tg, ov))
        # ⚠ 输出的 target 是**字节数**（tg * unit），Kotlin 侧拿到的是字符数（tg）——
        #    这正是"两边窗口覆盖同样多字符"的换算。同时多输出一列字符数 target，
        #    由 run_diff.sh 转交给 Kotlin 探针（见 CHUNK_CHAR_TARGET 环境变量）。
        for text, tg, ov in out:
            esc = text.replace('\\', '\\\\').replace('\n', '\\n')
            print("%d\t%d\t%d\t%d\t%s" % (tg * unit, ov * unit, tg, ov, esc))
        return 0
    while len(out) < n:
        ln = rng.randint(0, 170)
        text = "".join(rng.choice(ALPHA) for _ in range(ln))
        tg = rng.choice([1, 2, 3, 4, 5, 6, 7, 8, 12, 20, 64])
        # 同上：ov < tg（ASCII 上 byte/char 相同，这条只是保持两边语料口径一致）
        ov = rng.choice([0, 0, 0, 1, 2, 3, 5, 8, 64])
        if ov >= tg:
            ov = 0
        out.append((text, tg, ov))
    # 5 列：byteTarget 	 byteOverlap 	 charTarget 	 charOverlap 	 <转义正文>
    # ASCII 上 byte == char，两列相同（探针各自取自己那一列）。
    for text, tg, ov in out:
        esc = text.replace('\\', '\\\\').replace('\n', '\\n')
        print("%d\t%d\t%d\t%d\t%s" % (tg, ov, tg, ov, esc))
    return 0


if __name__ == '__main__':
    sys.exit(main())
