#!/usr/bin/env python3
"""K-4 行为复刻：`probeGguf` 的内存缓存命中判据必须是**文件身份**，不是路径。

为什么不能只留源码守卫：判据"只按路径命中"和"按身份命中"在**文件没被替换**时
表现**逐字节相同** —— 稳态下跑多少遍都看不出来。必须构造"同一路径、内容已换"
（外部路径重新下载完成 / 续传落盘 / adb push 覆盖）这种输入才分得出来。

本脚本逐字复刻 `LlmEngine.kt` 里那两行判据的两份写法（旧/新），对同一组
文件变更事件跑一遍，比较"是否重新读 header"与"返回的是哪份判定"。
"""
import os
import sys
import tempfile

PASS = 0
FAIL = 0


def chk(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        print("PASS  %s%s" % (name, ("（%s）" % detail) if detail else ""))
        PASS += 1
    else:
        print("FAIL  %s%s" % (name, (" -> %s" % detail) if detail else ""))
        FAIL += 1


class Identity(object):
    """`LlmEngine.ProbeIdentity` 的两行判据（逐字照抄：path + length + mtime）。"""

    def __init__(self, path, length, mtime):
        self.path, self.length, self.mtime = path, length, mtime

    def __eq__(self, o):
        return (isinstance(o, Identity) and o.path == self.path
                and o.length == self.length and o.mtime == self.mtime)

    def __hash__(self):
        return hash((self.path, self.length, self.mtime))


class Engine(object):
    """复刻 probeGguf 的缓存逻辑。`new` 选择新旧两套判据。"""

    def __init__(self, new):
        self.new = new
        self.cached_path = None       # 旧写法的全部状态
        self.cached_id = None         # 新写法
        self.cached_val = None
        self.native_reads = 0         # 打了几次 native（= 预读几次 header）

    def _identity(self, path):
        st = os.stat(path)
        return Identity(path, st.st_size, int(st.st_mtime))

    def probe(self, path):
        if self.new:
            ident = self._identity(path)
            if self.cached_id is not None and self.cached_id == ident:
                return self.cached_val
        else:
            if self.cached_path == path:                 # 旧写法：只有路径
                return self.cached_val
        self.native_reads += 1
        val = "probe@%s" % self.tag_of(path)             # 模拟 native 读出的判定
        if self.new:
            self.cached_id = self._identity(path)
        else:
            self.cached_path = path
        self.cached_val = val
        return val

    def tag_of(self, path):
        # 判定替身必须反映"这份文件被读出来的那一刻"，所以带上 size + mtime ——
        # 只用 size 的话"续传落盘（size 恰好相同）"这一组里两份判定看起来一样，
        # 断言 `first != second` 就变成在测我的 tag_of，而不是在测缓存判据。
        # （第一版就是只用 size，② 那条当场变红 —— 是**测试**的错，不是实现的。）
        st = os.stat(path)
        return "%d@%d" % (st.st_size, int(st.st_mtime))


def main():
    tmp = tempfile.mkdtemp(prefix="k4-")
    path = os.path.join(tmp, "model.gguf")

    def write(nbytes, mtime):
        with open(path, "wb") as f:
            f.write(b"G" * nbytes)
        os.utime(path, (mtime, mtime))

    print("--- ① 同路径、内容被就地替换（size 变）---")
    for mode in ("new", "old"):
        # 复位
        write(1000, 1700000000)
        e = Engine(new=(mode == "new"))
        first = e.probe(path)
        write(2000, 1700000100)      # 重新下载完成：路径不变、内容全换
        second = e.probe(path)
        if mode == "new":
            chk("新写法：内容换了 -> 重新读 header 并给出新判定",
                e.native_reads == 2 and first != second,
                "reads=%d first=%s second=%s" % (e.native_reads, first, second))
        else:
            chk("旧写法：内容换了 -> 仍返回旧判定（这就是那个洞）",
                e.native_reads == 1 and first == second,
                "reads=%d first=%s second=%s" % (e.native_reads, first, second))

    print("--- ② 同路径、同 size、只有 mtime 变（续传落盘）---")
    for mode in ("new", "old"):
        write(1500, 1700000000)
        e = Engine(new=(mode == "new"))
        first = e.probe(path)
        write(1500, 1700000200)      # 续传完成：size 恰好相同，mtime 变了
        second = e.probe(path)
        if mode == "new":
            chk("新写法：mtime 变了 -> 重新读 header",
                e.native_reads == 2 and first != second,
                "reads=%d" % e.native_reads)
        else:
            chk("旧写法：mtime 变了却仍命中旧判定（同 size 更隐蔽）",
                e.native_reads == 1 and first == second,
                "reads=%d" % e.native_reads)

    print("--- ③ 文件一字未动 -> 必须命中缓存（别把缓存改没了）---")
    for mode in ("new", "old"):
        write(1200, 1700000000)
        e = Engine(new=(mode == "new"))
        first = e.probe(path)
        second = e.probe(path)
        chk("写(%s)：文件未变 -> 命中缓存，不重复打 native" % mode,
            e.native_reads == 1 and first == second,
            "reads=%d" % e.native_reads)

    print("--- ④ 另一个路径 -> 不复用前一个的判定 ---")
    other = os.path.join(tmp, "other.gguf")
    with open(other, "wb") as f:
        f.write(b"H" * 900)
    for mode in ("new", "old"):
        write(1000, 1700000000)
        e = Engine(new=(mode == "new"))
        e.probe(path)
        e.probe(other)
        chk("写(%s)：换路径 -> 重新读 header" % mode,
            e.native_reads == 2, "reads=%d" % e.native_reads)

    print("=== K-4 文件身份行为复刻：PASS %d / FAIL %d ===" % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
