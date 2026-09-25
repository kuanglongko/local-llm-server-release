#!/bin/sh
# run_repack_channel_guard.sh 的自测：**每条判据都要能红**，且红在真的坏形态上。
#
# 为什么必须有这一条：本轮的失效形态是"装到手机上才发现、且装哪版都一样" ——
# 静态守卫全绿与"档位真的到了 native"同形。更糟的是这一族的假绿特别多：
#   · 「存在 extraBufts()」恒真 —— 它一直在，问题正是它读错了键；
#   · 「存在 nativeSetRepack」恒真 —— native 那一侧本来就没坏；
#   · 「文件里出现过 repack」恒真 —— 注释/日志/UI 文案里到处都是。
# 上一版（0.9.127）判据正是全绿而缺陷原样：**两处各自都能自证**，没人对过
# "这两个键是不是同一个"。所以桩全部取自**本轮真出现过的写法**与它最像的近亲：
#   · 退回"读另一个键"（`KEY_EXTRA_BUFTS`）—— 0.9.127 原样，就是真机上那条日志；
#   · 写端改回"另存一份"（`setExtraBufts`）—— 看起来"有写入端了"，其实又开了一条旁路；
#   · 只改读端、写端仍留第二个键（横向对账必须逮住）；
#   · 把读到的值丢掉（`setRepackMode(-1)`）—— "取了不用"；
#   · 把三态压成两态（`?: 0`）—— 未设过被当成"关"。
#
# 运行：sh tools/run_repack_channel_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
MS=app/src/main/java/com/xiaowan/localinference/ModelStore.kt
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
LL=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

ok=0; bad=0
chk() {
    name="$1"; want="$2"
    if sh tools/run_repack_channel_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=/tmp/repack_channel_stash
rm -rf "$STASH"; mkdir -p "$STASH"
cp "$MS" "$STASH/ModelStore.kt"; cp "$EA" "$STASH/EngineActivity.kt"; cp "$LL" "$STASH/LlmEngine.kt"
restore() { cp "$STASH/ModelStore.kt" "$MS"; cp "$STASH/EngineActivity.kt" "$EA"; cp "$STASH/LlmEngine.kt" "$LL"; }
trap restore EXIT

stub() {
    name="$1"; want="$2"; prog="$3"
    restore
    printf '%s\n' "$prog" | python3 - "$MS" "$EA" "$LL" || {
        # 桩的 assert 失败 = 这个桩根本没打上。**必须判红**，
        # 否则脚本退出非 0、文件还是原样，守卫自然绿 —— 桩静默变成"什么都没改"。
        echo "FAIL  $name：桩没打上（python 退出非 0，源码未被改动）"
        bad=$((bad+1))
        return 0
    }
    chk "$name" "$want"
}

READ_GOOD='        engineParams(ctx)["repack"]?.takeIf { it == "0" || it == "1" }?.toInt()'

# ① 0.9.127 原样回来：读端改回"另一个键" → 档位永远传不到 → 真机上那条日志
stub "退回读第二个键 KEY_EXTRA_BUFTS（0.9.127 原样，真机日志的来源）" red "
import sys, io
ms, ea, ll = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(ms, encoding='utf-8').read()
old = '''$READ_GOOD'''
assert old in s, '读端形状变了，本自测要一起更新'
new = '''        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EXTRA_BUFTS, null)
            ?.takeIf { it == \"0\" || it == \"1\" }
            ?.toInt()'''
s = s.replace(old, new, 1)
s = s.replace('    fun extraBufts(', '    private const val KEY_EXTRA_BUFTS = \"use_extra_bufts\"\n\n    fun extraBufts(', 1)
io.open(ms, 'w', encoding='utf-8').write(s)
"

# ② 读端修好了，但写端又另开一条旁路（两处各自自证 = 同一个病的复发）
stub "读端已修，却另外保留一个无人调用的写入端 setExtraBufts()" red "
import sys, io
ms = sys.argv[1]
s = io.open(ms, encoding='utf-8').read()
anchor = '    /** 文件名 -> 别名 映射'
assert anchor in s
add = '''    private const val KEY_EXTRA_BUFTS = \"use_extra_bufts\"

    fun setExtraBufts(ctx: Context, mode: Int?) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (mode == null) e.remove(KEY_EXTRA_BUFTS) else e.putString(KEY_EXTRA_BUFTS, mode.toString())
        e.apply()
    }

'''
s = s.replace(anchor, add + anchor, 1)
io.open(ms, 'w', encoding='utf-8').write(s)
"

# ③ 设置页改成另存一格（写端与读端不再同源）
stub "设置页改存 engine_params 之外的键（写读不再同一格）" red "
import sys, io
ea = sys.argv[2]
s = io.open(ea, encoding='utf-8').read()
old = '\"repack\" to (repackMode?.toString() ?: \"\")))'
assert old in s, '写端形状变了，本自测要一起更新'
s = s.replace(old, '\"repackx\" to (repackMode?.toString() ?: \"\")))', 1)
io.open(ea, 'w', encoding='utf-8').write(s)
"

# ④ 取到了值却不交给 native（存了不用）
stub "加载时没把 extraBufts() 交给 setRepackMode（取了不用）" red "
import sys, io
ll = sys.argv[3]
s = io.open(ll, encoding='utf-8').read()
old = 'setRepackMode(appCtx?.let { ModelStore.extraBufts(it) } ?: -1)'
assert old in s, '下发点形状变了，本自测要一起更新'
s = s.replace(old, 'setRepackMode(-1)', 1)
io.open(ll, 'w', encoding='utf-8').write(s)
"

# ⑤ 三态压成两态：未设过被当成"关"（默认开被静默改掉）
stub "未设过被压成显式 0（三态压两态，默认开被静默改掉）" red "
import sys, io
ms = sys.argv[1]
s = io.open(ms, encoding='utf-8').read()
old = '''$READ_GOOD'''
assert old in s, '读端形状变了，本自测要一起更新'
s = s.replace(old, '        engineParams(ctx)[\"repack\"]?.toInt() ?: 0', 1)
io.open(ms, 'w', encoding='utf-8').write(s)
"

# ⑥ 修好后的写法 → 绿
restore
chk "修复后的写法（单一真源 + 显式下发）" green

if [ "$bad" -eq 0 ]; then
    echo "=== repack 通道守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== repack 通道守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
