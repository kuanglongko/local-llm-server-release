#!/usr/bin/env python3
"""存根面积判据：真源码用到的 `JNIEnv` 成员 / JNI 数组类型，桩里必须都有。

═══════════════════════════════════════════════════════════════════════════
为什么必须有这一条
═══════════════════════════════════════════════════════════════════════════
`tools/stub_jni/jni.h` 自己第 8 行立的规矩是「面积必须**刚好**是真源码用到的
那些成员……**少写一个则真源码编不过**（这时把成员补上，而不是把检查关掉）」。

但"少写一个"这件事**此前没有任何判据**：

  · `run_static_order_guard.sh` 判据① 真的编 `llama_jni.cpp` 本体 —— 它**能**
    逮住"桩缺成员"，可它依赖宿主有能过 libc++ 的编译器；没有编译器时它按契约
    **SKIP**（`REQUIRED=0`）。于是"桩缺成员"在无编译器的机器上**谁也不红**。
  · 同一脚本判据④ 名叫「存根面积本身就是契约」，**实际只锚了两条**
    `typedef _jobject * jstring/jobject` —— "齐不齐、够不够编过"没有任何断言。

两个条件叠在一起就等于谁也不红（B-3 同族：症状指向与真因相反）。C-1 就是这么
一路绿到有人装上 clang 才暴露的：`befc564` 给 `nativeNewSampler` 新增了
`jintArray`/`jfloatArray` 两个形参与两次数组读取调用，而存根**一个字都没动**。

═══════════════════════════════════════════════════════════════════════════
锚「真源码用到了什么」，不锚「桩里写没写某个字符串」
═══════════════════════════════════════════════════════════════════════════
本判据**先扫真源码**取出用到的成员与数组类型，再逐项要求桩里有 —— 所以它
**不需要编译器**，也就补上了判据① 在无编译器宿主上 SKIP 的那个缺口。反过来
（只锚"桩里有没有 getXXX"这种硬编码字面量）就是存在性断言：桩里写着、源码
不用，照样绿；源码新用了、桩没补，也照样绿。

口径与 `run_static_order_guard.sh` 一致：**先剥注释、再剥字符串字面量**。
不剥的话，日志文案里提到 `GetFloatArrayRegion` 会被算成"用到了"，把真缺口盖住
（该守卫桩⑥ 打的就是这一形态）。

退出码：0 = 全部覆盖；1 = 有缺口（逐条列出）。用法：
  python3 tools/stub_jni/check_stub_surface.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JNI = os.path.join(ROOT, 'app/src/main/cpp/llama_jni.cpp')
STUB = os.path.join(ROOT, 'tools/stub_jni/jni.h')

# 真正的 JNI 数组类型（`jXXXArray`）。这里**不写死全集**：从源码里扫出来，
# 逐个去桩里找 typedef。写死全集就成了"名单对名单"，新类型照样漏。
ARRAY_TYPE_RE = re.compile(r'\b(j[A-Za-z][A-Za-z0-9_]*Array)\b')
ENV_CALL_RE = re.compile(r'\benv\s*->\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(')


def strip_noise(text):
    """剥注释、再剥字符串字面量 —— 两处都会出现标识符，不剥会算成「用到」。

    与 run_static_order_guard.sh 的 strip_noise 同口径（那边注释里写明了理由）。
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
        elif ch == '/' and i + 1 < n and text[i + 1] == '*':
            i += 2
            while i + 1 < n and not (text[i] == '*' and text[i + 1] == '/'):
                i += 1
            i += 2
        elif ch == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == '\\' else 1
            i += 1
            out.append('""')
        elif ch == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == '\\' else 1
            i += 1
            out.append("''")
        else:
            out.append(ch)
            i += 1
    return ''.join(out)


def main():
    for p in (JNI, STUB):
        if not os.path.isfile(p):
            print('缺少 %s' % p)
            return 2

    src = strip_noise(io.open(JNI, encoding='utf-8').read())
    stub = io.open(STUB, encoding='utf-8').read()

    members = sorted(set(ENV_CALL_RE.findall(src)))
    types = sorted(set(ARRAY_TYPE_RE.findall(src)))

    if not members:
        print('FAIL  在 %s 里一个 `env->成员(` 都没扫到（正则写歪？）' % JNI)
        return 1
    if not types:
        print('FAIL  在 %s 里一个 `jXXXArray` 都没扫到（正则写歪？）' % JNI)
        return 1

    missing_m = [m for m in members
                 if not re.search(r'\b%s\s*\(' % re.escape(m), stub)]
    missing_t = [t for t in types
                 if not re.search(r'\btypedef\b[^;]*\b%s\b\s*;' % re.escape(t), stub)]

    print('真源码用到 JNIEnv 成员 %d 个 / JNI 数组类型 %d 个' % (len(members), len(types)))
    print('  成员：%s' % ' '.join(members))
    print('  类型：%s' % ' '.join(types))

    if missing_m or missing_t:
        for m in missing_m:
            print('FAIL  JNIEnv 成员 `%s` 被真源码使用，但 %s 里没有声明'
                  % (m, os.path.relpath(STUB, ROOT)))
        for t in missing_t:
            print('FAIL  JNI 数组类型 `%s` 被真源码使用，但 %s 里没有 typedef'
                  % (t, os.path.relpath(STUB, ROOT)))
        return 1

    print('PASS  真源码用到的每个 JNIEnv 成员 / JNI 数组类型，存根里都有')
    return 0


if __name__ == '__main__':
    sys.exit(main())
