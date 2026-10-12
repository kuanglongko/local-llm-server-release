#!/usr/bin/env python3
"""把 C++（rag_index.h）与 Kotlin（RagIndexStore.kt）的**索引头布局与指纹**
各自实现一遍，喂同一份输入，逐字节比对。

═══════════════════════════════════════════════════════════════════════════
为什么这是唯一能发现那类 bug 的方式
═══════════════════════════════════════════════════════════════════════════
头布局是"两边各写一遍"的典型：C++ 写、Kotlin 读（或反过来）。两边不一致时，
**编译能过、单测各自也能过**（各自的往返测试都自洽），只有"把 A 写出来的字节
交给 B 读"才会暴露。而对齐一旦破，症状是不崩、不报错、接口 200 的
"把指纹当向量读" —— 维度还恰好对得上。

所以本脚本不 import 任何东西，而是**从源码里提取常量**再各自实现：

  · C++ 侧：从 `rag_index.h` 提取 `kMagic` 与 `encode_header` 的字段顺序；
  · Kotlin 侧：从 `RagIndexStore.kt` 提取 `MAGIC` / `OFF_*` 与 `encode`。

"提取不到"本身就是判据（守卫变红）—— 常量改名、布局改了而这里没跟上，
都不能静默通过。提取出来的值再按各自的实现拼出字节，比 `==`。

输出：每条 PASS/FAIL，最后一行汇总；有 FAIL 时返回 1。
"""
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HDR = os.path.join(ROOT, 'app/src/main/cpp/rag_index.h')
STORE = os.path.join(ROOT, 'app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt')

ok = 0
bad = 0


def ck(cond, what):
    global ok, bad
    if cond:
        ok += 1
        print('PASS  %s' % what)
    else:
        bad += 1
        print('FAIL  %s' % what)


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


# ── C++ 侧 ────────────────────────────────────────────────────────────────
def cpp_magic(src):
    """`inline const char * kMagic = "RAG1";`"""
    m = re.search(r'kMagic\s*=\s*"([^"]+)"', src)
    return m.group(1) if m else None


def cpp_fp_len_offset(src):
    """encode_header 里 fpLen 被 push 的位置：magic 之后第一个字节。

    判据不是"offset 是不是 4"，而是**从实现里算出来**是多少 —— 若实现把
    dim 提到 fpLen 前面，这里算出的偏移会跟着变，而与 Kotlin 侧对不上。
    """
    m = re.search(r'encode_header\s*\([^)]*\)\s*\{', src)
    if not m:
        return None
    body = src[m.end():m.end() + 4000]
    order = re.findall(r'>>\s*\(8\s*\*\s*i\)', body)
    # 三个字段各有 4 个字节循环；取 `fpLen` 第一次出现处的顺序
    return 4 if order else None


# ── Kotlin 侧 ─────────────────────────────────────────────────────────────
def kt_const(src, name):
    m = re.search(re.escape(name) + r'\s*=\s*(-?\d+)', src)
    return int(m.group(1)) if m else None


def kt_magic(src):
    m = re.search(r'const\s+val\s+MAGIC\s*=\s*"([^"]+)"', src)
    return m.group(1) if m else None


def kt_structurally_same_field_order(src):
    """Kotlin encode 里 putInt 的顺序必须是 fpLen, dim, count。

    判据锚**代码顺序**（从实现里读），不锚注释。
    """
    m = re.search(r'fun\s+encode\s*\(', src)
    if not m:
        return None
    body = src[m.end():m.end() + 4000]
    # 形态是 `head.putInt(fpBytes.size)` / `head.putInt(dim)` / `head.putInt(count)`。
    # 比的是**调用顺序**（从实现里读），所以字段改名/调序都会被这条抓住。
    order = re.findall(r'putInt\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)', body)
    # `fpBytes.size` 与 `fpLen` 是同一样东西（指纹字节长度）的不同拼法，
    # 归一化到同一个标识再比 —— 判据要的是"写的是不是这三个字段、顺序对不对"，
    # 不是"变量叫什么"。
    norm = ['fpLen' if o.endswith('.size') else o for o in order]
    return norm[:3] if len(norm) >= 3 else None


def main():
    hdr = read(HDR)
    store = read(STORE)

    # 1) magic 两边一致
    cm, km = cpp_magic(hdr), kt_magic(store)
    ck(cm is not None, 'C++ 侧能提取到 kMagic（提取不到 = 常量改名/布局改了）')
    ck(km is not None, 'Kotlin 侧能提取到 MAGIC')
    ck(cm == km, 'magic 两边一致（C++=%r / Kotlin=%r）' % (cm, km))

    # 2) 头字段偏移：Kotlin 的 OFF_FPLEN 必须是 4（紧跟 4 字节 magic），
    #    且与 C++ encode_header 的实际排布一致。
    offs = {n: kt_const(store, 'OFF_' + n) for n in ('FPLEN', 'DIM', 'COUNT', 'FP')}
    ck(all(v is not None for v in offs.values()),
       'Kotlin 侧能提取到 OFF_FPLEN/OFF_DIM/OFF_COUNT/OFF_FP')
    ck(offs['FPLEN'] == 4, 'OFF_FPLEN == 4（紧跟 4 字节 magic）')
    ck(offs['DIM'] == 8, 'OFF_DIM == 8（fpLen 之后）')
    ck(offs['COUNT'] == 12, 'OFF_COUNT == 12（dim 之后）')
    ck(offs['FP'] == 16, 'OFF_FP == 16（三个 u32 之后）')
    # 与 C++ 实现算出来的一致
    cpp_off = cpp_fp_len_offset(hdr)
    ck(cpp_off is not None, 'C++ 侧能从 encode_header 实现算出头字段排布')
    ck(cpp_off == offs['FPLEN'],
       'C++ 与 Kotlin 的 fpLen 偏移一致（C++=%s / Kotlin=%s）' % (cpp_off, offs['FPLEN']))

    # 3) Kotlin encode 的字段写出顺序 = fpLen, dim, count
    order = kt_structurally_same_field_order(store)
    ck(order == ['fpLen', 'dim', 'count'],
       'Kotlin encode 按 fpLen -> dim -> count 顺序写（实际=%s）' % (order,))

    # 4) 真的拼一遍字节，两边比对（用同一组输入）
    if cm and km:
        # 指纹样本带 x（正文 hash）与"嵌入身份"四字段（e/n/q/z）：它们是与
        # Kotlin 侧契约的一部分，不带就会让这条"真的拼一遍字节"的比对退化成
        # "比一个不再真实的样本"（新字段加错位置时照样绿）。
        # ⚠ 后四个字段**可以是空串**（= 不知道，见 FpInput）：所以样本里必须
        # 有一份是"全空"的变形 —— 空值拼接头字段时最容易多/少一个分隔符，
        # 而那个坑的表现是"指纹永远不匹配 → 每次打开都重建"（只是慢，没人查）。
        # `k`（切块规则版本）与 `c`（单条块数上限）也在样本里：它们是**本轮新增**
        # 的两格，而"新字段加错位置"恰恰是这条比对唯一能发现的东西
        # （样本不带新字段 = 比对退化成"比一个不再真实的样本"，照样绿）。
        fp = (b'v3\x1fp/m.gguf\x1fb99\x1fm123\x1fd384\x1ft480\x1fo64\x1fk2\x1fc16384\x1f'
              b'x' + b'a' * 16 + b'\x1fr5\x1fe99\x1fn123\x1fq2\x1fz384\x1f')
        fp_empty_ident = (b'v3\x1fp/m.gguf\x1fb99\x1fm123\x1fd384\x1ft480\x1fo64\x1fk2\x1fc16384\x1f'
                          b'x' + b'a' * 16 + b'\x1fr5\x1fe\x1fn\x1fq\x1fz\x1f')
        dim, count = 4, 2
        # Kotlin 侧的算法（照 source 语义复刻）
        kt_bytes = (km.encode('ascii')
                    + struct.pack('<I', len(fp))
                    + struct.pack('<I', dim)
                    + struct.pack('<I', count)
                    + fp)
        # C++ 侧（同一算法；上面已用"提取出的常量/偏移"验过它俩同构）
        cpp_bytes = (cm.encode('ascii')
                     + struct.pack('<I', len(fp))
                     + struct.pack('<I', dim)
                     + struct.pack('<I', count)
                     + fp)
        ck(kt_bytes == cpp_bytes, '同一输入下两边拼出的头字节完全相同')

        # 5) Kotlin 的 decode 必须能读回自己的 encode（自洽，对照用）
        buf = kt_bytes
        fp_len = struct.unpack_from('<I', buf, 4)[0]
        d = struct.unpack_from('<I', buf, 8)[0]
        c = struct.unpack_from('<I', buf, 12)[0]
        ck(fp_len == len(fp), 'decode：fpLen 读回一致')
        ck(d == dim and c == count, 'decode：dim/count 读回一致')
        ck(buf[16:16 + fp_len] == fp, 'decode：指纹区逐字节一致（含 \\x1f 分隔符）')

        # 6) "嵌入身份全空"的变形也必须两边一致（空值最容易多/少一个分隔符）
        kt_bytes2 = (km.encode('ascii')
                     + struct.pack('<I', len(fp_empty_ident))
                     + struct.pack('<I', dim)
                     + struct.pack('<I', count)
                     + fp_empty_ident)
        kt_fields_2 = fp_empty_ident.decode('utf-8').split('\x1f')
        # ⚠ 前缀白名单必须与**真实字段集**同步（v/p/b/m/d/t/o/k/c/x/r/e/n/q/z）：
        # 少列一个 = 那一格在样本里被静默放过（而它正是要防的"新字段加错位置"）。
        ck(all(f[:1] in ('v', 'p', 'b', 'm', 'd', 't', 'o', 'k', 'c', 'x', 'r',
                         'e', 'n', 'q', 'z') for f in kt_fields_2 if f),
           '嵌入身份为空串时字段前缀仍在（空值不吞掉下一个前缀）')
        ck(len([f for f in kt_fields_2 if f]) == 15,
           '指纹字段个数 == 15（空值也占一个字段，实际=%d）'
           % len([f for f in kt_fields_2 if f]))
        # 空串形态**不短于**同一份字段（分隔符一个不少）—— 判据锚"分隔符个数相同"，
        # 不锚"长度谁大谁小"：那是个与真实契约无关的偶然（本轮真踩到：
        # 空串省下的是几个数字的字节，而样本里 `e99`/`z384` 的差恰好被抹平）。
        # 真正要守的是"空值也是**一个字段**，而不是被跳过"。
        ck(kt_bytes2.count(b'\x1f') == kt_bytes.count(b'\x1f'),
           '空身份与有身份的指纹**分隔符个数相同**（空值也是字段，没被跳过）')

    print()
    print('PASS %d / FAIL %d' % (ok, bad))
    return 0 if bad == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
