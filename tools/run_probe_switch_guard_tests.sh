#!/bin/sh
# 自测 `tools/run_probe_switch_guard.sh`：对着**打桩源码树**跑，
# 断言"该红的红、且失败时点得出是哪一条"，再还原后全绿。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据分两类，各有各的自欺模式：
#   1. `grep -q` 形式的**存在性**断言 —— pattern 写成到处都有的文本就恒真，
#      把调用删掉照样 PASS（D-1/D-4 恰恰活在"存在但无效"里）；
#   2. `! grep -q` 形式的**禁令**断言 —— pattern 与源码差一个字符就恒真（永远绿），
#      而它本该拦住的旧写法可以大摇大摆回来。
# 两类都在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是
# 对**已知该红的桩**跑。**7 个桩全部来自本轮真正的旧写法**（git show 取出修复前的
# 那段代码，不是自己另写一份近似）。
#
# 不需要工具链，只要 sh + python3。
# 运行：sh tools/run_probe_switch_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_probe_switch_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
README=README.md
HTP=HTP-STATUS.md

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$KT" "$TMP/kt.bak"
cp "$README" "$TMP/readme.bak"; cp "$HTP" "$TMP/htp.bak"
restore() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"
            cp "$TMP/readme.bak" "$README"; cp "$TMP/htp.bak" "$HTP"; rm -rf "$TMP"; }
trap restore EXIT
reset_src() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"
              cp "$TMP/readme.bak" "$README"; cp "$TMP/htp.bak" "$HTP"; }

must_red() { # 名字 期望被点到的断言关键词
    name=$1; key=$2
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    if [ "$rc" = "0" ]; then
        echo "FAIL  $name：守卫没变红（该断言失效了）"; bad=$((bad+1)); return
    fi
    if grep -F "$key" "$TMP/guard.out" | grep -q 'FAIL'; then
        echo "PASS  $name：守卫变红，且指到了「$key」"; ok=$((ok+1))
    else
        echo "FAIL  $name：守卫红了，但没指到「$key」"; bad=$((bad+1))
    fi
}

# 先确认基线全绿（否则下面每条都会"变红"，测不出任何东西）
set +e
sh "$GUARD" > "$TMP/guard.out" 2>&1
base=$?
set -e
if [ "$base" = "0" ]; then ck "基线：对着当前源码全绿" 1
else echo "--- 基线红了，先修实现或守卫 ---"; cat "$TMP/guard.out"; ck "基线：对着当前源码全绿" 0; fi

# ── 桩①：kProbeFlagProps 退回死代码（D-2 的原始形态）──────────────────
# 判据① 该红。注意桩要打在**函数体里**：把读取循环删掉，但常量定义留着 ——
# 这正是"存在但无效"，也是上一版守卫唯一测得出来的东西。
"$PY" - "$JNI" <<'PYEOF'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r"(static bool probe_bootstrap\(\) \{.*?\n\})", s, re.S)
body = m.group(1)
# 整块删掉"读开关属性值并判定"的循环（常量定义仍留着 —— 这正是桩①要复现的
# "存在但无效"：kProbeFlagProps 这个名字还在文件里，但函数体里 0 处读取）
stub = re.sub(r"    \{\n        const char \* vals\[kProbeFlagPropCount\];.*?\n    \}\n", "", body, flags=re.S)
assert stub != body, "桩① 未生效"
open(p, "w", encoding="utf-8").write(s.replace(body, stub))
PYEOF
must_red "桩① kProbeFlagProps 取值不再被读取/判定（D-2 复现）" "kProbeFlagProps 在自举里被逐个 getprop 读取"
reset_src

# ── 桩②：自举重新无条件打开文件并置 g_probe_on（D-1 的原始形态）────────
"$PY" - "$JNI" <<'PYEOF'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r"(static bool probe_bootstrap\(\) \{.*?\n\})", s, re.S)
body = m.group(1)
stub = body.replace('    return true;\n}',
                    '    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0644);\n'
                    '    g_probe_fd = fd;\n'
                    '    g_probe_on = true;\n'
                    '    probe_raw(g_boot_log, g_boot_log_len);\n'
                    '    g_boot_log_len = 0;\n'
                    '    return true;\n}')
assert stub != body, "桩② 未生效"
open(p, "w", encoding="utf-8").write(s.replace(body, stub))
PYEOF
must_red "桩② 自举无条件开文件 + 置 g_probe_on（D-1 复现）" "自举函数体里不得 open 文件"
must_red "桩② 自举落盘 + 清零缓冲（D-4 复现）" "自举里不再清零 g_boot_log_len"
reset_src

# ── 桩③：probe_bootstrap_flush 退回"无条件下发"（D-4 的另一半）─────────
# 旧实现是 `if (g_boot_log_len > 0) probe_raw(...)` —— 不走唯一口径。
"$PY" - "$JNI" <<'PYEOF'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r"(static void probe_bootstrap_flush\(\) \{.*?\n\})", s, re.S)
body = m.group(1)
stub = '''static void probe_bootstrap_flush() {
    if (!g_probe_attempted) return;
    if (g_boot_log_len > 0) probe_raw(g_boot_log, g_boot_log_len);
}'''
open(p, "w", encoding="utf-8").write(s.replace(body, stub))
PYEOF
must_red "桩③ flush 退回『无条件下发』（不走唯一口径）" "自举缓冲交付走 probe_bootstrap_write"
reset_src

# ── 桩④：Kotlin 退回"关着直接 return"（D-1 得以成立的 Kotlin 侧成因）───
"$PY" - "$KT" <<'PYEOF'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r"(    fun startProbe\(ctx: android\.content\.Context\): String\? \{.*?\n    \})", s, re.S)
body = m.group(1)
stub = body.replace('        return try {', '        if (!probeEnabled) return null\n        return try {')
stub = stub.replace('nativeProbeInit(dir.absolutePath, probeEnabled)', 'nativeProbeInit(dir.absolutePath, true)')
assert stub != body, "桩④ 未生效"
open(p, "w", encoding="utf-8").write(s.replace(body, stub))
PYEOF
must_red "桩④ Kotlin 关着直接 return + 传死 true（D-1 的 Kotlin 侧成因）" "Kotlin 在调 nativeProbeInit 之前不得因 !probeEnabled 提前返回"
reset_src

# ── 桩⑤：probe_prop_has 退回"只看属性在不在"（开关判据退化）────────────
"$PY" - "$JNI" <<'PYEOF'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r"(static bool probe_bootstrap\(\) \{.*?\n\})", s, re.S)
body = m.group(1)
stub = body.replace("if (nv > 0 && probe_flag_off(vals, nv, kProbeOffValues,",
                    "if (probe_prop_has(kProbeFlagProps, kProbeFlagPropCount) && probe_flag_off(vals, nv, kProbeOffValues,")
assert stub != body, "桩⑤ 未生效"
open(p, "w", encoding="utf-8").write(s.replace(body, stub))
PYEOF
must_red "桩⑤ 开关判据退化成『属性在不在』（probe_prop_has）" "开关判据不得退化成 probe_prop_has"
reset_src

# ── 桩⑥：g_probe_on 在 native 侧别处也被置真（开关权威被分叉）──────────
# 用 probe_raw 的函数体当落点：在那里插一行 `g_probe_on = true;`
"$PY" - "$JNI" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "static void probe_raw(const char * s, size_t n) {\n"
assert old in s
s = s.replace(old, old + "    g_probe_on = true;\n", 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑥ native 侧别处也置 g_probe_on（开关权威被分叉）" "g_probe_on 的置真只出现在 nativeProbeInit 里"
reset_src

# ── 桩⑥b：Kotlin 把 on 参数写死 true（用户关了也照开 —— D-1 的另一半）──
"$PY" - "$KT" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = "nativeProbeInit(dir.absolutePath, probeEnabled)"
assert old in s
s = s.replace(old, "nativeProbeInit(dir.absolutePath, true)", 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑥b Kotlin 把 on 写死 true（用户关不掉探针）" "Kotlin 把 probeEnabled 作为 on 参数传给 nativeProbeInit"
reset_src

# ── 桩⑦：文档退回旧承诺（自举落盘 / 开关是死代码）──────────────────────
"$PY" - "$README" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
s = s.replace("自举阶段**只记不写**", "自举阶段会把目录信息落盘")
s = s.replace("**不建文件、不落盘**", "会先建好文件再交给 Kotlin")
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑦ README 退回『自举会落盘』的旧承诺" "README 说明自举只记不写"
reset_src

# ── 还原后必须全绿（确认桩清理干净、也没把源码改坏）──────────────────
set +e
sh "$GUARD" > "$TMP/guard.out" 2>&1
rc=$?
set -e
if [ "$rc" = "0" ]; then ck "还原后守卫全绿（桩清理干净）" 1
else echo "--- 还原后仍红 ---"; cat "$TMP/guard.out"; ck "还原后守卫全绿（桩清理干净）" 0; fi

# ── 探针单测也要跟着跑（守卫与单测判据同源，别只修一边）────────────────
set +e
sh tools/run_probe_tests.sh > "$TMP/pt.out" 2>&1
rcp=$?
set -e
if [ "$rcp" = "0" ]; then ck "还原后探针单测全绿" 1
else cat "$TMP/pt.out"; ck "还原后探针单测全绿" 0; fi

if [ "$bad" -eq 0 ]; then
    echo "=== 探针开关/自举守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 探针开关/自举守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
