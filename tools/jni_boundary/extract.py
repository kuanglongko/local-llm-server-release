#!/usr/bin/env python3
"""抽出跨语言边界的两侧签名，供比对。

两侧：
  · Kotlin `external fun`（在 `LlmEngine.kt`，命名规范 `Java_com_xiaowan_localinference_LlmEngine_<名>`）；
  · C/C++ `Java_..._<名>(...)` 导出（在 `llama_jni.cpp` 与 `cpu_feat.c` 两处）。

为什么要抓**形参类型**而不只抓名字：名字对得上而形参漂移时，Kotlin 编译过、
App 装得上，只在**调到那个方法那一刻**抛 `UnsatisfiedLinkError`
（或缺参静默取默认值），现场看像"某个功能偶发崩溃"。本仓库合入过
`nativeNewSampler` 形参漂移那一次，但 29 个导出里**只有那一个**被核过。
"""

import re

# Kotlin 原始类型 -> JNI 类型。`Array<String>` 映射成 objectArray，
# 具体元素类型不参与 JNI 签名，故统一记 jobjectArray。
KT_TO_JNI = {
    "String": "jstring", "Boolean": "jboolean", "Byte": "jbyte", "Char": "jchar",
    "Short": "jshort", "Int": "jint", "Long": "jlong", "Float": "jfloat",
    "Double": "jdouble",
    "IntArray": "jintArray", "LongArray": "jlongArray", "FloatArray": "jfloatArray",
    "BooleanArray": "jbooleanArray", "ByteArray": "jbyteArray",
}
# JNI 侧写法 -> 归一化（`jboolean` 与 `jboolean` 一样；`jobject` 视作 object 数组元素）
# 我们按"位置一一对应"比对，不要求两边写法完全一致，而是比**槽位数与类别**。

# Kotlin 形参里可能出现的默认值/类型注解，剥掉
PARAM_ANNOT = re.compile(r"@\w+(\([^)]*\))?\s*")


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def kotlin_externals(path):
    """返回 {name: [ (kt_type, kt_nullable), ... ]}（按形参顺序）。"""
    src = strip_comments(open(path, encoding="utf-8").read())
    out = {}
    for m in re.finditer(r"external\s+fun\s+(\w+)\s*\(([^)]*)\)", src, re.S):
        name, raw = m.group(1), m.group(2)
        params = []
        for p in split_top_level(raw):
            p = p.strip()
            if not p:
                continue
            p = PARAM_ANNOT.sub("", p).strip()
            # `name: Type`（可能有 `= 默认值`）
            pm = re.match(r"^\w+\s*:\s*(.+?)(\s*=\s*.*)?$", p, re.S)
            if not pm:
                continue
            typ = pm.group(1).strip()
            nullable = typ.endswith("?")
            typ = typ.rstrip("?")
            params.append((typ, nullable))
        out[name] = params
    return out


def split_top_level(raw):
    """按顶层逗号切分形参（`Array<String>`、`Map<K,V>` 里的逗号不算）。"""
    out, depth, cur = [], 0, []
    for ch in raw:
        if ch in "<([":
            depth += 1
        elif ch in ">)]":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if cur:
        out.append("".join(cur))
    return out


def expected_jni_types(kt_params):
    """把 Kotlin 形参序列翻译成"期望的 JNI 槽位"序列（只判类别，判不了就 None）。"""
    out = []
    for typ, _nullable in kt_params:
        t = typ.strip()
        if t.startswith("Array<"):
            out.append("array")
        elif t in KT_TO_JNI:
            out.append(KT_TO_JNI[t])
        else:
            out.append(None)   # 认不得的类型，不硬判
    return out


def c_param_type(p):
    """从一个 C 形参里抽出**类型**（去掉形参名与指针星号）。

    `jstring jdir` -> jstring
    `JNIEnv * env`  -> JNIEnv
    `const char * p` -> char
    `jobjectArray roles` -> jobjectArray
    """
    p = p.strip()
    if not p or p == "void":
        return ""
    # 去掉 `*`（指针与形参名之间）
    p = p.replace("*", " ")
    p = re.sub(r"\s+", " ", p).strip()
    toks = p.split(" ")
    # 末位若是合法标识符且前面还有词 -> 是形参名，去掉
    if len(toks) >= 2 and re.match(r"^\w+$", toks[-1]) and toks[-1] not in (
        "int", "long", "short", "char", "float", "double", "void", "signed", "unsigned"):
        toks = toks[:-1]
    return " ".join(toks).strip()


def c_exports(paths):
    """返回 {name: [jni_param_types]}（剥掉前两个 JNIEnv*/jclass）。"""
    out = {}
    for path in paths:
        src = open(path, encoding="utf-8").read()
        # 函数名可能换行：`Java_..._name(\n  JNIEnv * env, jclass, ...)` 或 `JNIEXPORT x JNICALL Java_...`
        for m in re.finditer(r"(Java_com_xiaowan_localinference_\w+)\s*\(", src):
            full = m.group(1)
            name = full.rsplit("_", 1)[-1]
            # 取参数表：从 `(` 到匹配的 `)`
            start = m.end()
            depth, i = 1, start
            while i < len(src) and depth:
                if src[i] == "(":
                    depth += 1
                elif src[i] == ")":
                    depth -= 1
                i += 1
            raw = src[start:i - 1]
            params = [p.strip() for p in split_top_level(raw)]
            # 前两个是 JNIEnv / jclass（第二个可能是匿名 jclass）—— 剥掉
            types = []
            for p in params[2:]:
                types.append(c_param_type(p))
            out.setdefault(name, types)
    return out
