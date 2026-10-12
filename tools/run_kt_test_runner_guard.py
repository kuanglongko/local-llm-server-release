#!/usr/bin/env python3
"""宿主 Kotlin 单测的**运行器**必须真的跑得起来 —— 不许"永远 SKIP"。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一份（这一条是真出过事的，且出了两轮没被发现）
═══════════════════════════════════════════════════════════════════════════
`tools/run_*_tests.sh` 里那一批 Kotlin 单测的契约是：

    缺工具链 -> 打印 `SKIP`（`REQUIRED=0` 时）或 `exit 2`（否则）→ 环境问题；
    有工具链 -> **真的编、真的跑**，断言不过就红。

而这两份套件把**第三种**结果藏在了前两种后面：

  · `run_embed_retrieve_tests.sh`：`java` 那一行写的主类是
    `com.xiaowan.localinference.EmbedRetrieveParityTest`，而那份测试是
    **顶层 `fun main()`**（文件 `EmbedRetrieveParityTest.kt`）—— Kotlin 生成的
    类名是 `...EmbedRetrieveParityTestKt`（多一个 `Kt`）。于是：
        无工具链 -> SKIP（看着像环境问题）
        有工具链 -> `ClassNotFoundException`（**一条断言都没跑过**）
    两轮（#308 / #309）都写着"本机 SKIP（无 kotlinc/JDK）"，而它**不是** SKIP
    能解释的 —— 它从来没跑成过。

  · `run_embed_parity_tests.sh`：只编 `EmbedParity.kt` + `EmbedParityProbe.kt`
    + 测试本体三个纯逻辑文件，而测试里引用了 `LlmEngine.kEmbedBatchDefault` /
    `LlmEngine.kRetrieveParityTopK` —— `LlmEngine.kt` 依赖 Android，编不进来。
    `kotlinc` 报 `unresolved reference 'LlmEngine'`，脚本 `exit 1`。
    这一份是 `b8bbdb2` 引入的（那之前编得过），同样被"本机 SKIP"的措辞盖住。

被盖住的是**几十条从没执行过的断言**：修好运行器后，两份套件当场多报出
**13 条真失败**（`EmbedRetrieveParity` 2 条：断言主语取错行 / 测错方向；
`EmbedParity` 11 条：输入写的是 `1.0f + 1e-9f`，而 `1e-9` 在 f32 里**低于 1.0 的
ulp**（约 1.19e-7），加完还是 `1.0f` —— 输入产不出"接近但非逐位"这个现象，
10 条 CLOSE 断言全部朝 IDENTICAL 倒；另 1 条把措辞「同路径重复」写成「同路重复」，
恒假）。这一族与 `run_kt_test_source_guard.py` 记的那次（JS 语法抄进 Kotlin 单测、
坏了整天没人发现）**同一个成因**：失效形态是"看起来只是环境缺件"。

判据不能建在工具链上（它正是会缺失的那个东西），所以这一份**纯源码级**：

  ① 每个 `tools/run_*_tests.sh` 里 `java ... "com.xiaowan.<pkg>.<Main>"` 的主类名，
     必须与它编的那份 `*Test.kt` **实际会生成的类名**一致：
       · 文件里是 `object Foo {` -> `Foo`；
       · 文件里是顶层 `fun main` -> `<文件名>Kt`；
       · `@file:JvmName("X")` -> `X`。
     （锚**结构**：从源码声明推出类名，不锚"文件里出现过某个字符串"。）

  ② 运行器把测试**编进来**的那些文件，不得引用**没被编进来**的 `com.xiaowan`
     类型。这一条抄的是编译器会做的事（`unresolved reference`），但因为
     `kotlinc` 会缺，必须有一份不依赖它的判据 —— 否则"编不过"永远只在
     有工具链时红，而那时人已经在看 SKIP 了。

  ③ 防恒真：判据①要能命中**已知写错**的形态（拿本仓库记录过的那个
     `EmbedRetrieveParityTest`（缺 `Kt`）自证），判据②同理。

用法：python3 tools/run_kt_test_runner_guard.py
"""
import glob
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


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def strip_literals(src: str) -> str:
    """去注释 + 去字符串/字符字面量，只留代码（与 run_kt_test_source_guard 同规）。

    必须剥：单测里大量出现 `check("... Kt ...")` 这类**被审数据**，
    不剥就会把断言文字当成代码，判据恒红 -> 被 `|| true` 掉。
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c in ('"', "'"):
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
                out.append(c)
                i += 1
                continue
            i = closed + 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def class_names(test_path: str):
    """从一份 `*Test.kt` 的**源码声明**推出它会生成的**全部** JVM 类名（简单名）。

    ⚠ 返回的是**集合**，不是单个：同一份测试里可以**既有** `object Foo { … }`
      **又有**顶层 `fun main()`（本仓库好几份都是这个写法，如
      `tools/rag_cost/RagCostTest.kt`）—— 那样 `Foo` 与 `FooKt` 都会生成，
      运行器挑哪个都跑得起来。第一版只取"第一个匹配"，于是把这些**对的**
      运行器误报成不符（判据自己恒红，下一手就是 `|| true`）。
    """
    src = strip_literals(read(test_path))
    base = os.path.basename(test_path)[:-3]  # 去掉 .kt
    out = set()

    m = re.search(r'@file\s*:\s*JvmName\s*\(\s*"([^"]+)"\s*\)', read(test_path))
    if m:
        out.add(m.group(1))

    for m in re.finditer(r'^\s*(?:public\s+|internal\s+|abstract\s+|open\s+)*'
                         r'object\s+([A-Za-z_]\w*)', src, re.M):
        out.add(m.group(1))

    # 顶层 `fun main`（不在任何 object/class 花括号里）—— 生成 `<文件名>Kt`。
    for m in re.finditer(r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
                         r'(?:public\s+|internal\s+)?fun\s+main\s*\(', src, re.M):
        out.add(base + "Kt")
        break

    return out


RUNNERS = sorted(glob.glob("tools/run_*_tests.sh"))


def runner_main_class(text: str):
    """取运行器里 `java … -cp "$CP" "com.xiaowan.…"` 那一行的主类全名。"""
    for m in re.finditer(r'"com\.xiaowan\.[A-Za-z_][\w.]*"', text):
        return m.group(0).strip('"')
    return None


def runner_kotlin_sources(text: str):
    """取运行器 `kotlinc … -d "$OUT" f1 f2 f3 > "$LOG"` 里编进去的 .kt 列表。

    只在**同一条命令**里取（`kotlinc` 那行可能跨行续行）—— 锚结构，
    不锚"文件里出现过某个 .kt"（同一份脚本里别的命令也引用 .kt）。
    """
    m = re.search(r'"\$KOTLINC"[^\n]*(?:\n[^\n]*)*?> *"\$LOG"', text)
    if not m:
        m = re.search(r'\$KOTLINC[^\n]*(?:\n[^\n]*)*?> *"\$LOG"', text)
    if not m:
        return None
    seg = m.group(0)
    out = []
    for v in re.findall(r'"([^"\n]*\.kt)"', seg):
        out.append(v.replace("$SRC/", "app/src/main/java/com/xiaowan/localinference/"))
    return out


# ═══════════════════════════════════════════════════════════════════════════
# 判据① 主类名 == 测试源码实际生成的类名
# ═══════════════════════════════════════════════════════════════════════════
mismatch = []
checked = 0
for f in RUNNERS:
    text = read(f)
    main = runner_main_class(text)
    if not main:
        continue
    kts = runner_kotlin_sources(text)
    if not kts:
        continue
    tests = [k for k in kts if os.path.basename(k).endswith("Test.kt")]
    if not tests:
        continue
    # 取包名：与主类同包的那份测试
    want = main.rsplit(".", 1)[-1]
    checked += 1
    candidates = {}
    for t in tests:
        if not os.path.exists(t):
            mismatch.append(f"{f}: 编的文件不存在 {t}")
            continue
        candidates[t] = class_names(t)
    if want not in set().union(*candidates.values()) if candidates else True:
        mismatch.append(
            f"{f}: 主类 {want}，而它编的测试只会生成 "
            + "、".join(sorted(set().union(*candidates.values()))) if candidates else "")

check(
    f"每个运行器的主类名 == 测试源码生成的类名（核了 {checked} 份，不符 {len(mismatch)}）",
    not mismatch,
    "; ".join(mismatch[:4]),
)

# ═══════════════════════════════════════════════════════════════════════════
# 判据② 编进来的文件不得引用没编进来的 com.xiaowan 类型
# ═══════════════════════════════════════════════════════════════════════════
# 这是把编译器那句 `unresolved reference 'X'` 抄成源码级判据 —— 因为
# `kotlinc` 正是会缺的那个东西，判据不能建在它上面（否则"编不过"只在有工具链时红，
# 而那时人已经在读 SKIP 了）。
#
# 做法：
#   1. 扫主源码目录，收集**所有**顶层 `object` / `class` / `interface` / 顶层
#      `const val` 所在的文件（一个"类型清单 + 住在哪个文件"）；
#   2. 对每个运行器：`declared` = 它编进来的那些文件里声明的类型；
#      `visible`  = 主源码里与 declared **同文件**的类型全都可见（同一个文件
#                   编进来，里面的类型当然都在）。所以判据只看**跨文件**引用：
#      引用的类型住在**别的**文件里，而那个文件没被编进来。
#   3. 只看"类型当值用"的形态（`Type.member` / `Type(` ），不看注释（已剥字符串）。
#
# ⚠ 第一版把"主源码里存在、但没编进来"的类型一律算越界，于是把
#   `HealthCheck.kt` 自己引用 `LlmEngine`（**真越界**，已修）与一堆
#   `empty`（它其实是 `emptyList()` 被正则切出来的假名）混在一起报 ——
#   判据恒红就会被人 `|| true` 掉。所以这里：
#     · 类型名只认**首字母大写**且**确实是该文件的顶层声明**；
#     · 只报"跨文件 + 那个文件没编进来"。
MAIN_GLOB = "app/src/main/java/com/xiaowan/localinference/*.kt"

TYPE_DECL = (r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
             r'(?:public\s+|internal\s+|private\s+|protected\s+|abstract\s+|'
             r'open\s+|sealed\s+|data\s+|value\s+|enum\s+|annotation\s+)*'
             r'(?:object|class|interface)\s+([A-Z]\w*)')


def declared_types(path):
    """这份文件里**顶层**声明了哪些类型（简单名）。

    ⚠ 必须能认出**同名不同文件**的情形：`HealthCheck.kt` 自己声明了
      `enum class Verdict`，而 `EmbedParity.kt` 里也有一个 `Verdict` ——
      "类型名 -> 唯一文件"的映射会把前者误判成"引用了未编译的 EmbedParity.Verdict"，
      判据当场恒红。所以按**文件**收集声明集合，再逐文件比对。
    """
    src = strip_literals(read(path))
    return {m.group(1) for m in re.finditer(TYPE_DECL, src, re.M)}


type_home = {}          # 类型名 -> 声明它的**全部**文件
for p_ in sorted(glob.glob(MAIN_GLOB)):
    for t_ in declared_types(p_):
        type_home.setdefault(t_, set()).add(os.path.normpath(p_))

unresolved = []
scanned = 0
for f_ in RUNNERS:
    text_ = read(f_)
    kts = runner_kotlin_sources(text_)
    if not kts:
        continue
    compiled = {os.path.normpath(k) for k in kts if os.path.exists(k)}
    for k in sorted(compiled):
        scanned += 1
        src_ = strip_literals(read(k))
        own = declared_types(k)          # 本文件自己声明的类型（同文件必然可见）
        for t_, homes in type_home.items():
            if t_ in own:
                continue
            if homes & compiled:
                continue                 # 有**任一份**同名声明编进来了
            if re.search(r'(?<![\w.])' + re.escape(t_) + r'\s*\.\s*[A-Za-z_]', src_):
                unresolved.append(
                    f"{os.path.basename(f_)} 编的 {os.path.basename(k)} 引用了 "
                    f"{t_}（只住在未编译的 "
                    + "、".join(os.path.basename(h) for h in sorted(homes)) + "）")

check(
    f"编进来的文件不引用「住在未编译文件里」的类型（扫了 {scanned} 份，越界 {len(unresolved)}）",
    not unresolved,
    "; ".join(sorted(set(unresolved))[:4]),
)

# ═══════════════════════════════════════════════════════════════════════════
# 判据③ 防恒真 / 防恒假：拿本仓库记录过的那两个错法自证
# ═══════════════════════════════════════════════════════════════════════════
# 自证 ①：顶层 fun main 的文件，类名必须是 <文件名>Kt —— 把"缺 Kt"那种写法
# 判成不符（这正是 run_embed_retrieve_tests.sh 出过的那个）。
probe_dir = "tools/embed_retrieve"
probe = os.path.join(probe_dir, "EmbedRetrieveParityTest.kt")
if os.path.exists(probe):
    names = class_names(probe)
    check(
        "自证：顶层 fun main 的类名带 Kt（缺 Kt 的写法会被判为不符）",
        "EmbedRetrieveParityTestKt" in names
        and os.path.basename(probe)[:-3] not in names,
        f"推出 {sorted(names)}",
    )
    pn = class_names("tools/embed_parity/EmbedParityTest.kt")
    check(
        "自证：object 型测试给的是简单名（不带 Kt）",
        "EmbedParityTest" in pn,
        f"推出 {sorted(pn)}",
    )
    both = class_names("tools/rag_cost/RagCostTest.kt") if os.path.exists(
        "tools/rag_cost/RagCostTest.kt") else set()
    check(
        "自证：object + 顶层 main 并存时两个类名都给（不误报）",
        "RagCostTest" in both and "RagCostTestKt" in both,
        f"推出 {sorted(both)}",
    )
else:
    check("自证：找不到探针文件", False, probe)

# ═══════════════════════════════════════════════════════════════════════════
# 判据④ 纯逻辑层的常量文件必须真的「纯」（这正是把它搬过去的意义）
# ═══════════════════════════════════════════════════════════════════════════
# `EmbedLimits.kt` 之所以能救活那三份套件，全部前提是它**不依赖 Android**
# （`Context` / `android.*` / `org.json` / native 符号）。一旦有人往它里面加一句
# `import android.…`，它就编不到宿主上 —— 而那时症状又是那三份套件的
# `unresolved reference`（"编不过"），正是这一份守卫要防的那族。
LIMITS = "app/src/main/java/com/xiaowan/localinference/EmbedLimits.kt"
if os.path.exists(LIMITS):
    lsrc = strip_literals(read(LIMITS))
    forbidden = []
    for pat, why in ((r'\bimport\s+android\.', "import android.*"),
                     (r'\bimport\s+org\.json', "import org.json"),
                     (r'\bContext\b', "Context"),
                     (r'\bexternal\s+fun\b', "native 声明（external fun）")):
        if re.search(pat, lsrc):
            forbidden.append(why)
    check(
        f"{os.path.basename(LIMITS)} 不碰 Android / org.json / native（宿主上编得到）",
        not forbidden,
        "、".join(forbidden),
    )
    # 三个数必须都在它里面（判据①②要读的就是它们）。
    missing_consts = [c for c in ("kEmbedBatchDefault", "kEmbedBatchRows", "kRetrieveParityTopK")
                      if not re.search(re.escape(c) + r'\s*=\s*\d+', lsrc)]
    check(
        "三个数都在 EmbedLimits 里、且都是字面量（不是转发）",
        not missing_consts,
        "缺：" + "、".join(missing_consts),
    )
    # ⚠ 反向：同一个数不许在 LlmEngine 里**又写一遍字面量** ——
    # 两处各写一个字面量时，"改了一处"就是那一族静默失效（守卫各自只看得见一处）。
    eng = read("app/src/main/java/com/xiaowan/localinference/LlmEngine.kt")
    dup = [c for c in ("kEmbedBatchDefault", "kEmbedBatchRows", "kRetrieveParityTopK")
           if re.search(re.escape(c) + r'\s*=\s*\d+', eng)]
    check(
        "LlmEngine 里这三个数**只有引用**（没有第二份字面量）",
        not dup,
        "又写了一遍字面量：" + "、".join(dup),
    )
else:
    check(f"找不到 {LIMITS}", False, LIMITS)

print(f"\n{'=' * 60}\nPASS {ok} / FAIL {bad}")
sys.exit(1 if bad else 0)
