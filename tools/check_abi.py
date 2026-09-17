#!/usr/bin/env python3
"""解释 ELF64 arm64 的 dynsym，比对"我们引用的符号"与"库导出的符号"。

用途：在**不装 NDK** 的环境里，判定 libllmjni_*.so 与 librnllama*.so 的
C++ ABI 是否一致。这是 PR #13 那次真机闪退的根因所在 ——
两侧 STL 不同（libstdc++ 的 St7__cxx11 vs libc++ 的 NSt6__ndk1）时，
mangled name 不同、结构体字段偏移也不同，链接期就缺失符号，
侥幸链上则运行时 SIGSEGV。

不带参数时自检本脚本的 ELF 解析。
"""
import struct
import sys


def read_dynsyms(path):
    d = open(path, "rb").read()
    if d[:4] != b"\x7fELF" or d[4] != 2 or d[5] != 1:
        raise ValueError("%s 不是 ELF64 LE" % path)
    e_shoff = struct.unpack_from("<Q", d, 0x28)[0]
    e_es = struct.unpack_from("<H", d, 0x3A)[0]
    e_n = struct.unpack_from("<H", d, 0x3C)[0]
    e_si = struct.unpack_from("<H", d, 0x3E)[0]
    secs = []
    for i in range(e_n):
        name, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from(
            "<IIQQQQIIQQ", d, e_shoff + i * e_es)
        secs.append(dict(name=name, off=off, size=size, entsize=entsize))
    shstr = secs[e_si]

    def shname(o):
        end = d.index(b"\0", shstr["off"] + o)
        return d[shstr["off"] + o:end].decode()

    for s in secs:
        s["nm"] = shname(s["name"])
    dynsym = [s for s in secs if s["nm"] == ".dynsym"][0]
    dynstr = [s for s in secs if s["nm"] == ".dynstr"][0]

    def dstr(o):
        end = d.index(b"\0", dynstr["off"] + o)
        return d[dynstr["off"] + o:end].decode()

    out = {}
    for i in range(dynsym["size"] // 24):
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from(
            "<IBBHQQ", d, dynsym["off"] + i * 24)
        nm = dstr(st_name)
        if not nm:
            continue
        out[nm] = dict(bind=st_info >> 4, typ=st_info & 0xF, defined=st_shndx != 0,
                       size=st_size, value=st_value)
    return out


def stl_flavour(syms):
    """统计 __ndk1 / __cxx11 出现次数。两者并存说明有 ABI 混用。"""
    ndk = sum(1 for s in syms if "__ndk1" in s)
    cxx = sum(1 for s in syms if "__cxx11" in s)
    return ndk, cxx


def main():
    if len(sys.argv) == 1:
        print("用法: check_abi.py <lib-x.so> [ref.so]")
        print("  ref.so 给出时，列出 lib 引用但 ref 未导出的 STL 符号（即真 ABI 缺符号）")
        return 0
    lib = read_dynsyms(sys.argv[1])
    ndk, cxx = stl_flavour(lib)
    print("%s: 符号总数 %d；__ndk1=%d  __cxx11=%d" % (sys.argv[1], len(lib), ndk, cxx))
    if cxx and ndk:
        print("  !! 同一文件内混用 libc++ 与 libstdc++ ABI，几乎必然崩")
    if len(sys.argv) < 3:
        return 0
    ref = read_dynsyms(sys.argv[2])
    missing = [s for s in lib
               if not lib[s]["defined"] and s.startswith("_Z")
               and ("__ndk1" in s or "__cxx11" in s) and s not in ref]
    print("%s: ABI 缺失符号 %d 个" % (sys.argv[2], len(missing)))
    for s in sorted(missing)[:20]:
        print("  缺:", s)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
