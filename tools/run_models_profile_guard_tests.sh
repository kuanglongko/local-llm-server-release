#!/bin/sh
# 自测 `tools/run_models_profile_guard.sh`：把它对着几份**就地改坏的真源码**跑，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据几乎全是 `grep` / `python3 找函数体` 形式的**结构性**断言，
# 它有三个自欺模式（本仓库各栽过一次，所以三个都钉）：
#
#   1. **恒真**：pattern 写成到处都有的文本。本套件最容易恒真的一条是
#      「档案只挂在当前加载那一条上」—— 写成 `grep -q attachProfile` 会在
#      `attachProfile` 的定义处就恒真（定义本身就在 `modelsJson()` 里）。
#      所以它锚的是**调用点那一行的条件**（`alias == cur`）。
#   2. **恒假后被静音**：pattern 与源码差一个字符 -> 守卫永远 FAIL ->
#      有人把 `exit 1` 改成 `|| true`，那时它彻底没用了。
#   3. **锚错位置**：钉"文件里出现过某字段"而不是"**这两处**的字段集合相同"。
#      于是"单查多一个只在这里出现的字段"照样绿，而契约已经分叉。
#
# 桩全部是**真正会写错的那几种写法**（不是编出来的），每一条对应一个坏法：
#   · 去掉 `>= sizeof(buf)` 的截断判据 -> 静默回半截值，与真值同形
#   · 两个 uint64 的下标写反           -> "权重 4 GB / 参数 0.3 个"，编译照过
#   · 拿掉 INT64_MAX 夹取              -> 溢出变成**负数**读数
#   · 取不到时回 0 而不是 null         -> "0 层"混进客户端
#   · 单查路由排在列表之后             -> 前缀先命中，回一份列表当模型对象
#   · 单查多一个只在它出现的字段       -> 两个接口的契约分叉（都是合法 JSON）
#   · 档案挂到**别的**模型上           -> 把这个模型的读数说成那个模型的
#   · 加载失败时也报 [模型档案]        -> 空行被读成"这个模型没有档案"
#   · 字段名分叉成另一套               -> 列表有、单查没有
#   · JNI 数组初值写成 nullptr          -> Kotlin 侧 Array<String> 解包处 NPE
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_log_filter_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。运行：sh tools/run_models_profile_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_models_profile_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
PROF=app/src/main/java/com/xiaowan/localinference/ModelProfile.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$ENG" "$TMP/eng.bak"
cp "$HTTP" "$TMP/http.bak"; cp "$PROF" "$TMP/prof.bak"
restore() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/eng.bak" "$ENG"
            cp "$TMP/http.bak" "$HTTP"; cp "$TMP/prof.bak" "$PROF"; rm -rf "$TMP"; }
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/eng.bak" "$ENG"
              cp "$TMP/http.bak" "$HTTP"; cp "$TMP/prof.bak" "$PROF"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

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

patch() { # 文件 python-替换脚本
    "$PY" - "$1" <<PYEOF
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
$2
open(p, 'w', encoding='utf-8').write(s)
PYEOF
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：去掉截断判据（buffer 小了静默回半截值，与真值同形）─────────────
patch "$JNI" '
old = """    if (n >= (int) sizeof(buf)) return nullptr;"""
new = """    (void) sizeof(buf);"""
assert s.count(old) == 1, "桩①：没找到截断判据"
s = s.replace(old, new, 1)'
must_red "桩①（截断判据被删，半截值当成功）" "buffer 大小 = 截断"
reset_src

# ── 2. 桩②：两个 uint64 的下标写反（编译照过，只出鬼读数）──────────────────
patch "$JNI" '
old = """    put_u64(0, (uint64_t) llama_model_size(S.model));    // 权重张量字节（≠ 文件字节）
    put_u64(1, (uint64_t) llama_model_n_params(S.model));// 参数个数"""
new = """    put_u64(0, (uint64_t) llama_model_n_params(S.model));
    put_u64(1, (uint64_t) llama_model_size(S.model));"""
assert s.count(old) == 1, "桩②：没找到两个 uint64 的写入"
s = s.replace(old, new, 1)'
must_red "桩②（权重与参数的下标写反）" "下标 0 是权重字节"
reset_src

# ── 3. 桩③：拿掉 INT64_MAX 夹取（溢出变成负数读数）────────────────────────
patch "$JNI" '
old = """        if (v > (uint64_t) INT64_MAX) v = (uint64_t) INT64_MAX;"""
new = """        // 桩：不夹了"""
assert s.count(old) == 1, "桩③：没找到夹取"
s = s.replace(old, new, 1)'
must_red "桩③（uint64 溢出会变成负数）" "夹到 INT64_MAX"
reset_src

# ── 4. 桩④：取不到当成空串之外的哨兵（"0 层"混进客户端）───────────────────
# 真正的错法是把空串解析成 0：`toIntOrNull() ?: 0`。
patch "$PROF" '
old = """                ctxTrain = n(3),"""
new = """                ctxTrain = a?.getOrNull(3)?.toIntOrNull() ?: 0,"""
assert s.count(old) == 1, "桩④：没找到 ctxTrain 解析"
s = s.replace(old, new, 1)'
must_red "桩④（取不到被折成 0，客户端分不出「读不到」）" "取不到就是 null，不是 0"
reset_src

# ── 5. 桩⑤：单查路由排在列表**之后**（前缀先命中，回一份列表当模型对象）──
patch "$HTTP" '
old = """                req.method == \"GET\" && pathUnder(req.path, \"/v1/models/\") ->
                    writeJson(out, 200, modelEntryJson(idFromModelsPath(req.path)), corsOrigin)
                req.method == \"GET\" && pathUnder(req.path, \"/v1/models\") ->
                    writeJson(out, 200, modelsJson(), corsOrigin)"""
new = """                req.method == \"GET\" && pathUnder(req.path, \"/v1/models\") ->
                    writeJson(out, 200, modelsJson(), corsOrigin)
                req.method == \"GET\" && pathUnder(req.path, \"/v1/models/\") ->
                    writeJson(out, 200, modelEntryJson(idFromModelsPath(req.path)), corsOrigin)"""
assert s.count(old) == 1, "桩⑤：没找到两条路由"
s = s.replace(old, new, 1)'
must_red "桩⑤（单查路由在列表之后，被前缀吞掉）" "路由排在 /v1/models 列表之前"
reset_src

# ── 6. 桩⑥：单查多一个只在它出现的字段（两个接口的契约分叉）───────────────
# 这根桩是"绕开共用拼装口、自己在函数体里 put 一个字段"的写法 ——
# 它既让两个接口的字段集合不等，又**不**经过 `profile.<字段>?.let`，
# 所以只认那一种形状的收集器会漏掉它（第一版正是这么漏的）。
patch "$HTTP" '
old = """            if (profile.available && forId == cur) {
                o.put(\"profile\", JSONObject().apply {
                    profile.name?.let { put(\"name\", it) }"""
new = """            if (profile.available && forId == cur) {
                o.put(\"extra_only_here\", 1)
                o.put(\"profile\", JSONObject().apply {
                    profile.name?.let { put(\"name\", it) }"""
assert s.count(old) == 1, "桩⑥：没找到单查的 profile 拼装"
s = s.replace(old, new, 1)'
must_red "桩⑥（单查多一个只在那出现的字段）" "两处的 profile 字段名集合"
reset_src

# ── 7. 桩⑦：档案挂到**别的**条目上（把这个模型的读数说成那个模型的）──────
patch "$HTTP" '
old = """                if (cur != null && alias == cur) attachProfile(d)"""
new = """                attachProfile(d)"""
assert s.count(old) == 1, "桩⑦：没找到挂档案的条件"
s = s.replace(old, new, 1)'
must_red "桩⑦（给列表里每条都挂上当前模型的档案）" "只有当前加载那一条挂档案"
reset_src

# ── 8. 桩⑧：加载失败时也报 [模型档案]（空行被读成"这个模型没有档案"）──────
patch "$ENG" '
old = """        if (err == null) {
            val prof = modelProfile()
            if (prof.available) uiLog(\"${LogFileStore.TAG_PROFILE} ${prof.describe()}\")
        }"""
new = """        {
            val prof = modelProfile()
            if (prof.available) uiLog(\"${LogFileStore.TAG_PROFILE} ${prof.describe()}\")
        }"""
assert s.count(old) == 1, "桩⑧：没找到档案日志"
s = s.replace(old, new, 1)'
must_red "桩⑧（加载失败也报档案）" "日志只在加载成功"
reset_src

# ── 9. 桩⑨：档案读不到也报一行（空的 [模型档案]）──────────────────────────
patch "$ENG" '
old = """            if (prof.available) uiLog(\"${LogFileStore.TAG_PROFILE} ${prof.describe()}\")"""
new = """            uiLog(\"${LogFileStore.TAG_PROFILE} ${prof.describe()}\")"""
assert s.count(old) == 1, "桩⑨：没找到 available 判据"
s = s.replace(old, new, 1)'
must_red "桩⑨（档案不可用也报一行空的）" "档案读不到就不报"
reset_src

# ── 10. 桩⑩：把 modelProfile 改成缓存（换模型后挂上一个模型的读数）───────
patch "$ENG" '
old = """    fun modelProfile(): ModelProfile = ModelProfile.fromNative(
        runCatching { nativeModelMeta() }.getOrNull())"""
new = """    fun modelProfile(): ModelProfile = memo ?: ModelProfile.fromNative(
        runCatching { nativeModelMeta() }.getOrNull()).also { memo = it }
    private var memo: ModelProfile? = null"""
assert s.count(old) == 1, "桩⑩：没找到 modelProfile"
s = s.replace(old, new, 1)'
must_red "桩⑩（档案被缓存起来，换模型不失效）" "每次直读 native"
reset_src

# ── 11. 桩⑪：短数组不兜底（新 Kotlin 撞旧 .so 直接崩）────────────────────
patch "$PROF" '
old = """            fun s(i: Int): String? = a?.getOrNull(i)?.takeIf { it.isNotEmpty() }"""
new = """            fun s(i: Int): String? = a!![i].takeIf { it.isNotEmpty() }"""
assert s.count(old) == 1, "桩⑪：没找到 s() 的兜底"
s = s.replace(old, new, 1)'
must_red "桩⑪（短数组不兜底，跨版本直接崩）" "解析口做短数组兜底"
reset_src

# ── 12. 桩⑫：数组初值改成 nullptr（Kotlin 侧 Array<String> 解包处 NPE）────
patch "$JNI" '
old = """    jobjectArray out = env->NewObjectArray(kFields, strCls, empty);"""
new = """    jobjectArray out = env->NewObjectArray(kFields, nullptr, nullptr);"""
assert s.count(old) == 1, "桩⑫：没找到 NewObjectArray"
s = s.replace(old, new, 1)'
must_red "桩⑫（数组初值是 null，Kotlin 侧解包 NPE）" "数组初值不是 nullptr"
reset_src

# ── 12. 收尾：还原后必须恢复全绿 ────────────────────────────────────────────
if run_guard; then ck "收尾：还原源码后守卫恢复全绿" 1
else ck "收尾：还原源码后守卫恢复全绿（实际红了 -> 有桩没还原干净）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 模型档案守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 模型档案守卫自测：PASS $ok / FAIL $bad ==="; exit 1
