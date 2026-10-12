#!/bin/sh
# 自测 `tools/run_embed_entry_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据是**调用/取值关系**（"这个函数体里调了哪个方法""这个字段取自哪个表达式"），
# 它的自欺模式比一般存在性断言更隐蔽：
#
#   A. 取值表达式断言：`re.search(r'val X = ... LlmEngine.embedModelDesc')`——
#      写成不锚定变量名、或锚定到别处同名的 `val`，就变成"别的代码满足它"。
#      桩① 就在测这个：把取值换成对话那份的描述，必须变红。
#
#   B. 函数体切片断言：命中的函数体切歪（切到别的函数）时，判据会在无辜代码上跑。
#      桩②③④ 覆盖：把调用换成对话版、把按钮接线摘掉、把入口删掉。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真源码成故障版本，守卫必须变红并指出是哪一条；
# 再改回来，必须恢复全绿。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）。
#
# ⚠ 本轮真踩过并已修的坑（桩⑧ 钉住它）：`_fnbody` 的 `_strip` 会把**字符串
#   字面量也抹成 `""`**，而状态行的读数写在字符串模板里（`"n_embd=${LlmEngine.embedDim}"`）
#   —— 用函数体判据会把那条断言判成**假红**。判据因此必须取原文区间（raw span）。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_embed_entry_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_embed_entry_guard.sh
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
API=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
JNI=app/src/main/cpp/llama_jni.cpp
MS=app/src/main/java/com/xiaowan/localinference/ModelStore.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$EA" "$TMP/ea.bak"; cp "$API" "$TMP/api.bak"; cp "$JNI" "$TMP/jni.bak"; cp "$MS" "$TMP/ms.bak"
restore() {
    cp "$TMP/ea.bak" "$EA"; cp "$TMP/api.bak" "$API"
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/ms.bak" "$MS"
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
    cp "$TMP/ea.bak" "$EA"; cp "$TMP/api.bak" "$API"
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/ms.bak" "$MS"
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

must_green "基线：未改动源码树"

# ── 1. 桩①：/health 的 embed_model 改取对话那份的描述（字段名对、值错）────
"$PY" - "$API" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val embedDesc = if (embedLoaded) LlmEngine.embedModelDesc().toJsonStr() else "\\"\\""'
assert s.count(old) == 1, "桩①：没找到 embedDesc 取值行"
s = s.replace(old, 'val embedDesc = if (embedLoaded) LlmEngine.modelDesc().toJsonStr() else "\\"\\""', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩①（embed_model 取对话那份描述）" "embed_model 必须取"
reset_src

# ── 2. 桩②：doLoadEmbed 改调 loadModel（装进对话槽 = 用户踩的那个坑）──────
"$PY" - "$EA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚点要容忍"调用带上了别的实参"：ISSUE #269 起这里变成了
#   `loadEmbedModel(f.absolutePath, nThreads = parseThreads())` ——
#   写死整串的桩会因为锚点找不到而**直接报错退出**（本桩的 assert 设计如此，
#   报错比静默跳过好）。但那样这条桩就永久失效了，所以锚到"函数名 + 首个实参"这一层。
old = 'LlmEngine.loadEmbedModel('
# ⚠ 用**真调用**当锚（`LlmEngine.loadEmbedModel(`），不用裸 `loadEmbedModel(`
#   —— 后者会被 `LlmEngine.unloadEmbedModel(` 命中（子串），count 得 2。
n = s.count(old)
assert n >= 1, "桩②：没找到 doLoadEmbed 里的调用（锚点 'LlmEngine.loadEmbedModel('）"
s = s.replace(old, 'LlmEngine.loadModel(', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩②（嵌入加载走了对话那条路）" "doLoadEmbed"
reset_src

# ── 3. 桩③：加载按钮的点击没接到 doLoadEmbed（按钮变成死的）──────────────
"$PY" - "$EA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'embedLoadBtn.setOnClickListener { doLoadEmbed() }'
assert s.count(old) == 1, "桩③：没找到 embedLoadBtn 的接线"
s = s.replace(old, 'embedLoadBtn.setOnClickListener { /* stub3: 按钮是死的 */ }', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩③（加载按钮没接线）" "embedLoadBtn"
reset_src

# ── 4. 桩④：卸载入口改调对话的 unloadModel（会拆掉对话模型）──────────────
"$PY" - "$EA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'LlmEngine.unloadEmbedModel()'
assert s.count(old) == 1, "桩④：没找到 doUnloadEmbed 里的调用"
s = s.replace(old, 'LlmEngine.unload()', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩④（嵌入卸载拆了对话）" "找不到调用 LlmEngine.unloadEmbedModel"
reset_src

# ── 5. 桩⑤：状态行改读本页变量而不是引擎真值（读数是"我以为"）────────────
"$PY" - "$EA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val loaded = LlmEngine.hasEmbedModel'
assert s.count(old) == 1, "桩⑤：没找到 renderEmbedStatus 里的真值读取"
s = s.replace(old, 'val loaded = embedFile != null', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑤（状态行不读引擎真值）" "已加载判据取引擎真值"
reset_src

# ── 6. 桩⑥：native 的描述函数改读对话 Session S ─────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''Java_com_xiaowan_localinference_LlmEngine_nativeEmbedModelDesc(JNIEnv * env, jclass) {
    if (!E.model) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(E.model, desc, sizeof(desc));'''
assert s.count(old) == 1, "桩⑥：没找到 nativeEmbedModelDesc 的函数体"
new = old.replace('E.model', 'S.model')
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑥（native 描述读的是对话 Session）" "没有读 E.model"
reset_src

# ── 7. 桩⑦：嵌入与对话共用同一个选中键（两个角色互相顶掉）────────────────
"$PY" - "$MS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'private const val KEY_EMBED_MODEL = "embed_model"'
assert s.count(old) == 1, "桩⑦：没找到 KEY_EMBED_MODEL"
s = s.replace(old, 'private const val KEY_EMBED_MODEL = "selected_model"', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑦（两个角色共用选中键）" "prefs 键"
reset_src

# ── 8. 桩⑧：只在文件某处加一句提及 loadEmbedModel 的注释，真代码不变 ─────
# 判据若锚"文件里出现过 loadEmbedModel"，加一句注释就会变绿（恒真）；
# 锚"函数体里调用了它"则不受影响 —— 守卫必须**仍全绿**。
"$PY" - "$EA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
anchor = 'private fun doLoadEmbed() {'
assert s.count(anchor) == 1, "桩⑧：没找到 doLoadEmbed 定义"
s = s.replace(anchor, '// 这里调用 LlmEngine.loadEmbedModel 与 LlmEngine.loadModel\n' + anchor, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_green "桩⑧（注释里的函数名不得让判据恒真）"
reset_src

# ── 9. 桩⑨：把 doLoadEmbed 整个删掉（入口消失 = 缺口复现）───────────────
"$PY" - "$EA" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
i = s.index('private fun doLoadEmbed() {')
j = s.index('private fun doUnloadEmbed() {')
s = s[:i] + s[j:]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑨（加载入口被删掉）" "doLoadEmbed"
reset_src

# ── 10. 收尾：还原后必须恢复全绿 ─────────────────────────────────────────
must_green "收尾：还原源码后"

echo ""
if [ "$bad" = "0" ]; then echo "=== 嵌入入口守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 嵌入入口守卫自测：PASS $ok / FAIL $bad ==="; exit 1
