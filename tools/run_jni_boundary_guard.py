#!/usr/bin/env python3
"""跨语言边界守卫：`external fun` ↔ JNI 导出 ↔ 结构体字段，三处必须一一对上。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一份（Issue #174 / 模块乙-1）
═══════════════════════════════════════════════════════════════════════════
这是**唯一"两端各自都合法、合起来静默错"**的地方：

  · Kotlin 侧 `external fun` 与 C++ 侧 `Java_..._<名>` 导出，**名对不上**时
    Kotlin 编译过、App 装得上，只在**调到那个方法那一刻**抛 `UnsatisfiedLinkError`；
  · **形参漂移**（个数/类型）时更隐蔽：少一个参数、类型写错，JNI 按位置取值，
    静默读到垃圾或默认值 —— 现场看像"某个功能偶发崩溃"。
  · 结构体 `offsetof` 断言若只判**数量**（`>= 14`），头与 `.so` 不同源时挡不住：
    删 2 个字段加 2 个，数量不变、断言照绿。

本仓库合入过 `nativeNewSampler` 形参漂移那一次，但 29 个导出里**只有那一个**被核过
（且是逐字锚一段字符串）；`offsetof` 的判据是 `>= 14` 的**数量**判据。
本守卫把这三件事都变成**逐项**判据。

纯 python3 标准库。运行：python3 tools/run_jni_boundary_guard.py
"""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "jni_boundary"))
from extract import kotlin_externals, c_exports, expected_jni_types, split_top_level  # noqa: E402
from struct_fields import struct_fields  # noqa: E402

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
        print(f"FAIL  {name}" + (f" —— {detail}" if detail else ""))
        bad += 1


def c_type(raw):
    """归一化 C 形参类型到"末位类型名"（`const std::string & s` -> `std::string`）。"""
    t = raw.replace("*", " ").replace("&", " ")
    t = re.sub(r"\s+", " ", t).strip()
    return t.split(" ")[-1] if t else t


def type_matches(exp, got):
    if exp == got:
        return True
    if exp == "array":
        return got.endswith("Array") or got == "jobject"
    return False


KT = "app/src/main/java/com/xiaowan/localinference/LlmEngine.kt"
CPP = ["app/src/main/cpp/llama_jni.cpp", "app/src/main/cpp/cpu_feat.c"]
HEADER = "app/src/main/cpp/chat/include/chat.h"
ABI = "app/src/main/cpp/chat_abi.h"
STRUCT = "common_chat_templates_inputs"

kt = kotlin_externals(KT)
ce = c_exports(CPP)

check("Kotlin external fun 数量与 JNI 导出数量都对得上（防两侧任一侧解析失败）",
      len(kt) >= 25 and len(ce) >= 25,
      f"kt={len(kt)} c={len(ce)}")

# ── 判据①：每个 `external fun` 都有导出，每个导出都有声明 ──────────────────
missing_export = sorted(set(kt) - set(ce))
missing_decl = sorted(set(ce) - set(kt))
check(
    f"每个 external fun 都有 JNI 导出（缺：{len(missing_export)} 个）",
    not missing_export,
    "、".join(missing_export[:6]),
)
check(
    f"每个 JNI 导出都有 Kotlin 声明（多：{len(missing_decl)} 个）",
    not missing_decl,
    "、".join(missing_decl[:6]),
)

# ── 判据②：形参**逐个**对上（数量 + 类型类别）────────────────────────────
COUNT_OK = 0
TYPE_OK = 0
count_bad = []
type_bad = []

for name in sorted(set(kt) & set(ce)):
    exp = expected_jni_types(kt[name])
    got = [c_type(x) for x in ce[name]]
    if len(exp) != len(got):
        count_bad.append(f"{name}: Kotlin {len(exp)} 个 vs JNI {len(got)} 个")
        continue
    COUNT_OK += 1
    for i, (e, g) in enumerate(zip(exp, got)):
        if e is None:
            continue          # 认不得的 Kotlin 类型不硬判
        if not type_matches(e, g):
            type_bad.append(f"{name}[{i}]: 期望 {e} 实际 {g}")
            continue
        TYPE_OK += 1

check("核到了形参数量（防下一条恒真）", COUNT_OK >= 25, f"实际 {COUNT_OK}")
check(
    f"每个导出的形参**个数**与 Kotlin 声明一致（不一致：{len(count_bad)} 个）",
    not count_bad,
    "；".join(count_bad[:4]),
)
check("核到了形参类型槽位（防下一条恒真）", TYPE_OK >= 40, f"实际 {TYPE_OK}")
check(
    f"每个形参**类型**与 Kotlin 声明一致（不一致：{len(type_bad)} 个）",
    not type_bad,
    "；".join(type_bad[:4]),
)

# ── 判据③：结构体字段**逐个**有 offsetof（不是数量）──────────────────────
fields = [n for n, _t in struct_fields(HEADER, STRUCT)]
abi_src = open(ABI, encoding="utf-8").read()
claimed = re.findall(r"offsetof\(\s*" + re.escape(STRUCT) + r"\s*,\s*(\w+)\s*\)", abi_src)

check("读到了结构体字段清单（防下面两条恒真）", len(fields) >= 10, f"实际 {len(fields)}")
check("读到了 offsetof 断言（防下面两条恒真）", len(claimed) >= 10, f"实际 {len(claimed)}")

no_assert = [f for f in fields if f not in claimed]
ghost = [c for c in claimed if c not in fields]
check(
    f"结构体每个字段都有 offsetof 断言（缺：{len(no_assert)} 个）",
    not no_assert,
    "、".join(no_assert[:6]),
)
check(
    f"offsetof 断言没有指向不存在的字段（幻影：{len(ghost)} 个）",
    not ghost,
    "、".join(ghost[:6]),
)

print(f"\n{'=' * 60}\nPASS {ok} / FAIL {bad}")
sys.exit(1 if bad else 0)
