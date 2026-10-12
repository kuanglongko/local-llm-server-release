#!/bin/sh
# 自测 `tools/run_dual_model_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据分两类，各自都有自欺模式：
#
#   A. 存在性断言（`printf ... | grep -q '某段源码'`）：pattern 写成到处都有的
#      文本就恒真。桩①②③ 就是在测这一类。
#   B. **结构/归属**断言（"这个函数体里只拆 E"）：最容易的失效是"判据在别人身上跑"
#      —— 比如切函数体时切歪了（切到另一个函数），于是它测的是一段无辜的代码。
#      桩④⑤ 就在测这个：把 `unloadModelInternal(E)` 改成 `(S)` 必须变红。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真源码成故障版本，守卫必须变红并指出是哪一条；
# 再改回来，必须恢复全绿。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）。
#
# ⚠ 本轮真踩过一个坑：`strip_comments` 一度先跑块注释正则、再跑行注释正则，
#   于是 `// ... /*/README.md` 这种**行注释里的 `/*`** 被当成块注释起点，
#   一路吞掉中间的真实代码 —— 守卫因此把"两份 Session 都在"判成**假红**。
#   桩⑥ 专门钉这个：它往文件里塞一处"行注释里的 `/*`"，断言守卫仍全绿
#   （真代码没变）。守卫对注释敏感 = 它读的不是实现。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_dual_model_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_dual_model_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
STRIP=tools/dual_model/strip_comments.py

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$KT" "$TMP/kt.bak"; cp "$STRIP" "$TMP/strip.bak"
restore() {
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"; cp "$TMP/strip.bak" "$STRIP"
    rm -rf "$TMP"
}
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}
reset_src() {
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"
}

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}
must_green() {
    name=$1
    if run_guard; then ck "$name：守卫全绿" 1
    else ck "$name：守卫全绿（实际红了）" 0; sed -n '1,60p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 1. 桩①：加载嵌入模型时顺手拆了对话模型（会"聊到一半模型没了"）────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 只改 nativeLoadEmbedModel 体内那一行：找到它的函数体位置
i = s.index('Java_com_xiaowan_localinference_LlmEngine_nativeLoadEmbedModel(')
j = s.index('unloadModelInternal(E);', i)
s = s[:j] + 'unloadModelInternal(S);' + s[j + len('unloadModelInternal(E);'):]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩①（加载嵌入时拆了对话模型）" "nativeLoadEmbedModel 体内只拆 E"
reset_src

# ── 2. 桩②：卸载嵌入的导出改拆 S ─────────────────────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'nativeUnloadEmbedModel(JNIEnv *, jclass) { jp(">> unloadEmbed\\n"); unloadModelInternal(E); }'
assert s.count(old) == 1, "桩②：没找到 nativeUnloadEmbedModel 的定义"
s = s.replace(old, 'nativeUnloadEmbedModel(JNIEnv *, jclass) { jp(">> unloadEmbed\\n"); unloadModelInternal(S); }', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩②（卸载嵌入改拆 S）" "nativeUnloadEmbedModel 只拆 E"
reset_src

# ── 3. 桩③：卸载对话模型时把嵌入也一起拆了 ───────────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'nativeUnloadModel(JNIEnv *, jclass) { jp(">> unloadModel\\n"); unloadModelInternal(S); }'
assert s.count(old) == 1, "桩③：没找到 nativeUnloadModel 的定义"
s = s.replace(old, 'nativeUnloadModel(JNIEnv *, jclass) { jp(">> unloadModel\\n"); unloadModelInternal(S); unloadModelInternal(E); }', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩③（卸载对话时连嵌入一起拆）" "nativeUnloadModel 只拆 S"
reset_src

# ── 4. 桩④：去掉 llama_set_embeddings（检索全空而接口 200）───────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'llama_set_embeddings(E.ctx, true);'
assert s.count(old) == 1, "桩④：没找到 llama_set_embeddings 调用"
s = s.replace(old, '/* stub4: 去掉 */', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩④（不调 llama_set_embeddings）" "嵌入加载路径调用 llama_set_embeddings"
reset_src

# ── 5. 桩⑤：n_ctx 不再钳到上限（手机上直接加载失败）──────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚"把上限赋回去"那一行（变量名在合批之后是 `total`，不再是 `ctx`）——
# 锚旧的字面量会在正确的重构上失败，然后判据被放宽成恒真（本仓库吃过这个亏）。
import re as _re
m = _re.search(r'^[^\n]*=\s*kEmbedMaxCtx\s*;', s, _re.M)
assert m, "桩⑤：没找到 n_ctx 钳制"
s = s[:m.start()] + '/* stub5: 不钳制 */' + s[m.end():]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑤（n_ctx 不钳上限）" "n_ctx 超过上限时被钳制"
reset_src

# ── 6. 桩⑥：嵌入路径改用 genLock（检索要排在整轮生成后面 = 白做双常驻）────
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'fun loadEmbedModel(\n        path: String,'
assert s.count(old) == 1, "桩⑥：没找到 loadEmbedModel 签名"
s = s.replace('= synchronized(embedLock) {', '= synchronized(genLock) {', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑥（嵌入共用 genLock）" "loadEmbedModel 持 embedLock"
reset_src

# ── 6b. 桩⑥b：把 output 排位换成 n_tokens-1（多 token 文本取到别的行）──
# 0.9.160 起，"读哪一行"由 `plan.row` 分岔，读取点的实参是一个具名局部量
# （`slot`）。这一桩打的是"那个量被换成硬编码表达式"。
# ⚠ 桩必须**只在 `nativeEmbed` 的函数体里**动手：`llama_get_embeddings_ith(E.ctx, slot)`
# 这句在 `nativeEmbedBatch` 里也有一份（合批的 ith 那条路），全文件 `count == 1`
# 会被它顶掉 —— 那时 assert 失败、脚本带 `set -e` 直接退出，
# 后面的桩一根都不跑，而输出看起来像"某一根桩坏了"（其实是锚点过期了）。
# ⚠⚠ 桩必须**在原文上**定位（不是 `fn_body` 的返回值）：`_fnbody` 会剥掉注释与
# 字符串字面量，它的返回值**不是**原文的子串，拿它去 `s.replace(body, ...)`
# 是一次**静默的 no-op**（不报错、不生效）—— 于是桩看起来跑了、实际没改，
# 而 `must_red` 报的是"守卫没变红"，指向的是守卫（其实守卫是对的）。
# 定位改成"以 nativeEmbed 的函数签名为锚，向后找第一处 `ith(E.ctx, slot)`"，
# 并在写盘前 `assert` 文本真的变了。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
sig = 'Java_com_xiaowan_localinference_LlmEngine_nativeEmbed('
assert s.count(sig) == 1, f"桩⑥b：nativeEmbed 签名命中 {s.count(sig)} 次"
# 从签名往后**到下一个 JNIEXPORT 之前**这一段就是 nativeEmbed 的函数体范围
lo = s.index(sig)
hi = s.find('JNIEXPORT', lo + len(sig))
hi = len(s) if hi < 0 else hi
seg = s[lo:hi]
old = 'llama_get_embeddings_ith(E.ctx, slot)'
assert seg.count(old) == 1, f"桩⑥b：nativeEmbed 段内命中 {seg.count(old)} 次"
new_seg = seg.replace(old, 'llama_get_embeddings_ith(E.ctx, nTok - 1)', 1)
s2 = s[:lo] + new_seg + s[hi:]
assert s2 != s, "桩⑥b：替换没生效（静默 no-op）"
open(p, 'w', encoding='utf-8').write(s2)
EOF
must_red "桩⑥b（output 排位写成 n_tokens-1）" "硬编码表达式"
reset_src

# ── 6c. 桩⑥c：**去掉 batch_last_slot**（LAST 语义与 output 排位混成一个数）──
# 这一桩打的是 0.9.160 修掉的形态：`plan.output_slot` 对 LAST 恒为 0，
# 它在"我们只给最后一位置了 logits"的前提下**恰好**是最后一行。
# 库给的 output 一变多，output_ids[0] 就是第 0 个 token（BOS，范数 ~1e-30）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        const int slot = plan.row == embedtext::SeqRow::LAST_TOKEN
                         ? plan.batch_last_slot : plan.output_slot;"""
assert s.count(old) == 1, "桩⑥c：没找到 slot 的三元表达式"
s = s.replace(old, "        const int slot = plan.output_slot;", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑥c（LAST 语义与 output 排位混成一个数）" "没有用 plan.batch_last_slot"
reset_src

# ── 7. 桩⑦：把 E 那份 Session 声明删掉（判据不该恒真：删了就红）───────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'static Session E;   // 嵌入（embed），embedding=true'
assert s.count(old) == 1, "桩⑦：没找到 static Session E;"
s = s.replace(old, '/* stub7: E 没了 */', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑦（嵌入 Session 声明消失）" "native 侧存在嵌入 Session"
reset_src

# ── 8. 桩⑧：行注释里塞一处 `/*`，真代码不变 —— 守卫必须**仍全绿** ────────
# 这是本轮真踩过的坑：strip_comments 曾把行注释里的 `/*` 当块注释起点，
# 一路吞掉中间真实代码 → 假红。守卫对注释敏感 = 它读的不是实现。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
anchor = 'struct Session {'
assert s.count(anchor) == 1, "桩⑧：没找到 struct Session {"
s = s.replace(anchor, '// 一段注释里的斜杠星号：/* 这不是块注释起点 */\n' + anchor, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_green "桩⑧（行注释里的 /* 不得让守卫假红）"
reset_src

# ── 9. 收尾：还原后必须恢复全绿 ──────────────────────────────────────────
must_green "收尾：还原源码后"

echo ""
if [ "$bad" = "0" ]; then echo "=== 双常驻隔离守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 双常驻隔离守卫自测：PASS $ok / FAIL $bad ==="; exit 1
