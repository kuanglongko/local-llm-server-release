"""桩②（C-1 裁剪不留痕）：把 `capChars` 里**两级**的留痕都删掉。

S1（`0.9.176`）起留痕有两条语句（一级"已丢弃最早 N 条历史"、二级"末条一字未动"，
后者还带续行），所以这里按行**扫描**删除，而不是写一条正则 —— 正则要穿过
shell heredoc、python 字符串、括号三层层层转义，本仓库在 release_hygiene 那段
已经栽过"引号层数把最需要原样的东西吃掉"。独立 `.py` 文件是本仓库对这类桩的
标准做法（见 `stub_cap_single.py` 顶部）。

用法：python3 tools/pure_logic/stub_cap_nolog.py <SessionStore.kt>
"""
import io
import sys

HEAD = 'Log.w(TAG, "会话 '


def main():
    p = sys.argv[1]
    lines = io.open(p, encoding="utf-8").read().split("\n")
    out, removed, i = [], 0, 0
    while i < len(lines):
        if HEAD in lines[i]:
            # 这条语句可能带续行：一直吃到"该行以 ) 收尾"为止。
            removed += 1
            while i < len(lines):
                done = lines[i].rstrip().endswith(")")
                i += 1
                if done:
                    break
            continue
        out.append(lines[i])
        i += 1
    assert removed == 2, "期望删掉两级各一条留痕，实际 %d 条" % removed
    io.open(p, "w", encoding="utf-8").write("\n".join(out))
    print("removed %d log statements" % removed)


main()
