#!/bin/sh
# 自测 `tools/run_llama_jni_abort_guard.sh`：对着**打桩源码树**跑，
# 断言"该红的红、且失败时点得出是哪一条"，再还原后全绿。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据大多是 `grep -q` 形式的**存在性/结构**断言，它有两个自欺模式：
#   1. **恒真**：pattern 写成到处都有的文本（例如只 grep `kv_invalidate`
#      而不断言**nativeAbort 的函数体里**有它），于是把作废逻辑删掉照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快就有人把 `exit 1` 改成 `|| true` —— 那时它彻底没用了。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真源码、守卫必须变红并指出是哪一条，再还原。
# 与 run_kv_cache_guard_tests.sh / run_http_lifecycle_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_llama_jni_abort_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_llama_jni_abort_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
RC=app/src/main/java/com/xiaowan/localinference/RequestCancel.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$ENG" "$TMP/eng.bak"
cp "$HTTP" "$TMP/http.bak"; cp "$RC" "$TMP/rc.bak"
restore() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/eng.bak" "$ENG"
            cp "$TMP/http.bak" "$HTTP"; cp "$TMP/rc.bak" "$RC"; rm -rf "$TMP"; }
trap restore EXIT
reset_src() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/eng.bak" "$ENG"
              cp "$TMP/http.bak" "$HTTP"; cp "$TMP/rc.bak" "$RC"; }

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

# ── 桩①（S.abort 退回裸 bool —— E-1 的数据竞争）────────────────────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='    std::atomic<bool> abort{false};'
new='    bool  abort     = false;'
assert s.count(old)==1, "stub1: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PYEOF
must_red "桩①（abort 退回裸 bool）" "声明为 std::atomic<bool>"
must_red "桩①（abort 退回裸 bool）" "裸 bool abort 声明必须绝迹"
reset_src

# ── 桩②（abort 写入退回裸赋值 —— 绕过原子）─────────────────────────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='    S.abort.store(false);'
new='    S.abort = false;'
assert s.count(old)==1, "stub2: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PYEOF
must_red "桩②（abort 退回裸赋值）" "裸赋值 S.abort = ... 已绝迹"
reset_src

# ── 桩③（取消丢掉归属 —— 停错对象）───────────────────────────────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='    if (roundEpoch == 0 || (long long) roundEpoch != cur) {'
new='    if (false) {'
assert s.count(old)==1, "stub3: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PYEOF
must_red "桩③（取消丢掉归属判定）" "先比归属再置位"
reset_src

# ── 桩④（nativeAbort 不作废账本 —— E-2 的脏账本被复用）────────────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='    kv_invalidate("本轮被 abort（残留不作复用候选）");'
assert s.count(old)==1, "stub4: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,'',1))
PYEOF
must_red "桩④（nativeAbort 不作废账本）" "nativeAbort 在函数体内作废账本"
reset_src

# ── 桩⑤（prefill 中断又写死 n_used=0 —— E-3）─────────────────────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='''            kv_invalidate("prefill 被中断");
            return JNI_FALSE;'''
new='''            kv_invalidate("prefill 被中断");
            S.n_used = 0;
            return JNI_FALSE;'''
assert s.count(old)==1, "stub5: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PYEOF
must_red "桩⑤（prefill 中断写死 n_used=0）" "prefill 中断分支不再写死 S.n_used = 0"
reset_src

# ── 桩⑥（kv_invalidate 顺手清零 kv_rounds —— E-5 失去分辨率）─────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='''    S.last_reuse = 0;
    S.last_prefill_tokens = 0;
    // 注意：**不动** S.kv_rounds'''
new='''    S.last_reuse = 0;
    S.last_prefill_tokens = 0;
    S.kv_rounds = 0;
    // 注意：**不动** S.kv_rounds'''
assert s.count(old)==1, "stub6: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PYEOF
must_red "桩⑥（作废账本顺手清零轮次计数）" "kv_invalidate 不得清零 kv_rounds"
reset_src

# ── 桩⑦（stats 退回两元 —— E-5 字段消失）─────────────────────────────
"$PY" - "$JNI" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='    jintArray a = env->NewIntArray(3);'
new='    jintArray a = env->NewIntArray(2);'
assert s.count(old)==1, "stub7: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PYEOF
must_red "桩⑦（stats 退回两元）" "stats 出口带上第三个读数"
reset_src

# ── 桩⑧（Kotlin 侧绑定归属被删 —— 归属止步于 JNI 门口）───────────────
"$PY" - "$ENG" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='        t.bindNativeEpoch(runCatching { nativeCurrentEpoch() }.getOrDefault(0L))'
assert s.count(old)==1, "stub8: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,'        // 绑定被删',1))
PYEOF
must_red "桩⑧（Kotlin 不再绑定归属）" "beginCancelable 把编号绑进 token"
reset_src

# ── 桩⑨（心跳/探测不再把取消送到 native —— prefill 停不下来）──────────
"$PY" - "$HTTP" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='                    LlmEngine.abortRound(cancel)'
assert s.count(old)==2, "stub9: expected 2 anchors, got %d" % s.count(old)
open(p,'w',encoding='utf-8').write(s.replace(old,'                    // 送 native 那一跳被删'))
PYEOF
must_red "桩⑨（探测不再送 native 取消）" "断连探测/心跳把取消送到 native"
reset_src

# ── 桩⑩（/health 少报 kv_rounds）──────────────────────────────────────
"$PY" - "$HTTP" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='"kv_prefill_tokens":$kvPrefill,"kv_rounds":$kvRounds}'
new='"kv_prefill_tokens":$kvPrefill}'
assert s.count(old)==1, "stub10: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
PYEOF
must_red "桩⑩（/health 少报 kv_rounds）" "HttpApi /health 报 kv_rounds"
reset_src

# ── 桩⑪（RequestCancel.Token 丢掉 nativeEpoch）────────────────────────
"$PY" - "$RC" <<'PYEOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='        @Volatile var nativeEpoch: Long = 0L'
assert s.count(old)==1, "stub11: anchor not found"
open(p,'w',encoding='utf-8').write(s.replace(old,'        @Volatile var epochX: Long = 0L',1))
PYEOF
must_red "桩⑪（token 丢掉 nativeEpoch）" "RequestCancel.Token 携带 nativeEpoch"
reset_src

# ── 还原后必须恢复全绿 ────────────────────────────────────────────────
set +e
sh "$GUARD" > "$TMP/guard.out" 2>&1
rc=$?
set -e
if [ "$rc" = "0" ]; then ck "还原后守卫恢复全绿（说明桩都还原干净了）" 1
else echo "--- 还原后仍红 ---"; cat "$TMP/guard.out"; ck "还原后守卫恢复全绿" 0; fi

echo
if [ "$bad" -eq 0 ]; then
    echo "=== abort 守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== abort 守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
