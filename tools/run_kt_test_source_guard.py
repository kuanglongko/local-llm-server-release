#!/usr/bin/env python3
"""宿主单测的**源码**必须真的是 Kotlin（不是从页面里抄来的 JS）。

═══════════════════════════════════════════════════════════════════════════
为什么要有这一份（Issue #110 第二轮增量审查 · 模块 D / D-1）
═══════════════════════════════════════════════════════════════════════════
`tools/web_chat/WebChatPageTest.kt` 从 `89ad274` 起就**编不过**：

    tools/web_chat/WebChatPageTest.kt:278:5: error: variable expected.
        ck("有三个页签", (html.match(/class="tab/g) ?: emptyList()).size == 3)
                          ^^^^^

`html.match(/…/g)` 是 **JS** 的写法 —— Kotlin 的 `String` 上没有 `match(Regex)`。
这两行是从页面源码（`WebChatPage.kt` 里的内嵌 JS）**抄测试时一起抄过来的**。

它坏了一整天没人发现，因为三个条件叠起来等于谁也不红：

  · 编译报错确实让脚本 `exit 1`（`grep -qE ': error:'` 这条判据**是对的**）；
  · 但 CI 里写的是 `REQUIRED=0 sh tools/run_web_chat_tests.sh` ——
    **缺工具链**时按契约 SKIP（不是红）；
  · 而 CI 的 runner 恰好没有 kotlinc/JDK/android.jar → 永远走 SKIP。
  · `tools/suite-counts.json` 里它是 `null`（逐条打印、不汇总）→ 判据⑤ 也核不到。

于是「编译不过」与「环境缺件」在汇总里长得**一模一样**。这份脚本补的就是这一环：
**不依赖任何工具链**，纯源码级检查 —— 正因为工具链是那个会缺失的东西，
判据不能也建在它上面。

判据（结构，不是存在性）：

  ① `.kt` 单测的**去注释、去字符串**后的代码里，不得出现 JS 专有语法：
     `x.match(/…/)`、JS 正则字面量、箭头函数块、`document.`、`const/let`、`===`。
     字符串字面量里的 `"const why = …"` 是**被测数据**（页面源码片段），不算 ——
     所以判据必须先剥字符串，否则这条断言会天天误红，然后被 `|| true` 掉。
  ② 每个 `.kt` 源文件（含主源码）括号配平 —— 与 `scripts/kt_balance.py` 同一件事，
     这里只作为"没被工具链验过时的最低证据"。
  ③ 防恒真：判据① 用的 JS 惯用法清单本身必须能命中**已知该怎么写的**文件 ——
     拿 `WebChatPage.kt` 的内嵌 JS 段自证（那里 `const`/`=>` 遍地都是）。
     清单若被改空，这条会红。

纯 python3 标准库，不依赖工具链。运行：python3 tools/run_kt_test_source_guard.py
"""
import glob
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

ok = 0
bad = 0


def check(name, cond, detail=""):
    global ok, bad
    if cond:
        print(f"PASS  {name}")
        ok += 1
    else:
        print(f"FAIL  {name}" + (f"（{detail}）" if detail else ""))
        bad += 1


def strip_literals(src: str) -> str:
    """去掉注释与字符串/字符字面量，只留**代码**。

    必须剥字符串：单测里大量出现 `html.contains("const sysCount = …")` ——
    它们是被测**数据**（页面源码片段），含 JS 语法。不剥的话这条判据会恒红，
    然后被人 `|| true` 掉，彻底失效。

    ⚠️ 但"剥字符串"本身有一个**反过来**的失效模式，本守卫第一版就栽在它上面：
    被审的那行是 `html.match(/class="tab/g)` —— 里面那个 `"` **不是** Kotlin
    字符串的开头（它是 JS 正则里的字面量）。按 Kotlin 的规则去剥，会把这一行
    剩下的部分全当成字符串吃掉，于是**真正的 bug 恰好被剥掉**，判据报"命中 0 处"。

    所以这里的规则是：**字符串不跨行**。Kotlin 的三引号串是唯一跨行的形态，
    单独处理；普通的 `"…"` 若在本行内没有闭合，就当作"这一个引号不是字符串开头"
    （即它属于被审数据），原样保留。这条规则让上面那种行**留得下来**。
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "*":          # 块注释
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":          # 行注释
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith('"""', i):                              # Kotlin 三引号串
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c in ('"', "'"):                                       # 单行引号串
            eol = src.find("\n", i)
            eol = n if eol < 0 else eol
            j = i + 1
            closed = -1
            while j < eol:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == c:
                    closed = j
                    break
                j += 1
            if closed < 0:
                # 本行内没闭合 -> 这不是 Kotlin 字符串，是**被审数据里的**引号。
                # 原样保留（下一条判据才有机会看见它后面跟着的 JS 语法）。
                out.append(c)
                i += 1
                continue
            i = closed + 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


# JS 专有语法。只放**在 Kotlin 里一定不合法**的形态，不放"两边都合法"的东西
# （比如 `?.`、`?:`、`:` 都不算 —— 它们在 Kotlin 里各有含义）。
JS_IDIOMS = [
    # ⚠ 判据要**窄**：`x.match(...)` 在 Kotlin 里唯一"一定不合法"的形态是
    # **`String`/字面量上的 match**（Kotlin 的 String 没有 `match(Regex)`）。
    # 而 `Foo.match(a, b)`（**自定义的顶层/对象函数**，比如 `RoleCard.match`）
    # 是合法 Kotlin —— 只查 `\.match\s*\(` 会把它误判成 JS 语法。
    # 本轮加角色卡守卫时当场踩到：`RoleCard.match(usedId, snapshot, cards)` 六处被点红，
    # 而它们是**判据自己**要盯的东西（改窄 pattern 之前，这条判据在"守卫新增了
    # 一个叫 match 的纯函数"时就恒红，下一个人的第一反应会是 `|| true`）。
    # 收窄到"接收者是字符串字面量、或看起来是 String 变量（小写开头/带 s 后缀）"。
    ("JS 的 String.match(正则)", r"(?:\"[^\"]*\"|'[^']*'|\b(?:html|text|body|line|str|content|raw)\s*)\.match\s*\("),
    ("JS 正则字面量 /…/g", r"(?:^|[^\w/$\]\)%])/[^\s/*=][^\n]*?/[gimsuy]*(?=[\s,)\]])"),
    ("箭头函数 (x) => {…}", r"=>\s*\{"),
    ("document. 直取 DOM", r"\bdocument\s*\."),
    # `const val` 是**合法 Kotlin**（编译期常量），只有"const 后跟标识符再接 ="
    # 而没有 val 的才是 JS。判据不写成"出现过 const" —— 那样会天天误红。
    ("JS 的 const 声明", r"(?<![\w.])const\s+(?!val\b)\w+\s*="),
    # `let` 同理：Kotlin 的 `x.let { … }` 是标准库扩展，`let x = ` 才是 JS。
    ("JS 的 let 声明", r"(?<![\w.])let\s+\w+\s*="),
    # `===` 在 Kotlin 里是**引用相等**，合法。所以不能单单查 `===` 就报 ——
    # 真正是 JS 的是"用于**比较值**"的那种用法，Kotlin 里会写 `==`。
    # 判据收紧到"两侧是字符串字面量/数字字面量"这一形态（JS 里最常见、
    # Kotlin 里几乎不会写的那种）；对被测数据里的 JS 不误报，因为数据在字符串里。
    ("JS 严格相等 ===", r"(?:'[^']*'|\d+)\s*===\s*(?:'[^']*'|\d+)"),
    ("JS 的 new Error", r"(?<![\w.])throw\s+new\s+Error"),
]

# 被检查的对象：所有**宿主单测**（它们要在有工具链的机器上真编）。
# 不含主源码 —— 主源码的 JS 在字符串里（内嵌页面），剥掉后是空。
KT_TESTS = sorted(glob.glob("tools/**/*Test.kt", recursive=True))
check("仓库里有宿主单测 .kt（防下一条恒真）", len(KT_TESTS) >= 1, f"实际 {len(KT_TESTS)}")

hits = []
for f in KT_TESTS:
    code = strip_literals(io.open(f, encoding="utf-8").read())
    for name, pat in JS_IDIOMS:
        for m in re.finditer(pat, code):
            ln = code.count("\n", 0, m.start()) + 1
            hits.append((f, ln, name, code.split("\n")[ln - 1].strip()[:80]))

check(
    f"宿主单测的代码里没有 JS 专有语法（命中 {len(hits)} 处）",
    not hits,
    "; ".join(f"{os.path.relpath(f, ROOT)}:{ln} {name} → {snip}" for f, ln, name, snip in hits[:5]),
)

# 判据③（防恒真）：JS 惯用法清单必须**真的能命中 JS** —— 拿页面本体里的内嵌 JS 自证。
# 若有人把清单改空、或把正则写成永不命中，这条会红。
page = io.open("app/src/main/java/com/xiaowan/localinference/WebChatPage.kt", encoding="utf-8").read()
inner_js = re.search(r'<script>(.*?)</script>', page, re.S)
inner_js = inner_js.group(1) if inner_js else ""
self_check = []
for name, pat in JS_IDIOMS:
    if re.search(pat, inner_js):
        self_check.append(name)
# 至少要命中"const 声明"与"箭头函数"这两条（页面里遍地都是）——
# 其余几条（.match / document.）页面里未必用，不强制。
check(
    f"JS 惯用法清单真的能命中 JS（自证命中 {len(self_check)}/{len(JS_IDIOMS)} 条）",
    "JS 的 const 声明" in self_check and "箭头函数 (x) => {…}" in self_check,
    f"命中：{self_check}",
)

# 判据②：括号配平。与 scripts/kt_balance.py 同一件事，作为"没被编译器验过时的最低证据"。
def balanced(path):
    code = strip_literals(io.open(path, encoding="utf-8").read())
    return code.count("{") == code.count("}") and code.count("(") == code.count(")") and \
        code.count("[") == code.count("]")


unbalanced = []
for f in sorted(glob.glob("app/src/main/java/com/xiaowan/localinference/*.kt")) + KT_TESTS:
    if not balanced(f):
        unbalanced.append(os.path.relpath(f, ROOT))
check(
    f"Kotlin 源码括号配平（不平：{len(unbalanced)} 个）",
    not unbalanced,
    "; ".join(unbalanced[:5]),
)

print(f"\n{'=' * 60}\nPASS {ok} / FAIL {bad}")
sys.exit(1 if bad else 0)
