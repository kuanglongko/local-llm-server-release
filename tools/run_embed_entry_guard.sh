#!/bin/sh
# 「嵌入模型在 App 里真的有入口、且 /health 报得出它」的接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# RAG 阶段 0 把 native + Kotlin 的嵌入能力**全写好了**，`run_dual_model_guard.sh`
# 21 条全绿 —— 因为那一份守的是"两份 Session 彼此隔离"，而它也确实是隔离的。
#
# 缺的从来不是隔离，是**接线**：`loadEmbedModel` 的调用方为 0，`/health` 里
# 一个嵌入字段都没有。两条缺口的症状合起来是：
#
#   ① 用户按对话模型的步骤加载向量模型 → 两个文件装进**同一个槽**（不是顶掉，
#      是同一把椅子换了个人坐）→ "两个模型互相顶掉"。
#   ② 探活里看不出嵌入装上没有 → 无法区分"嵌入没加载（检索必然全空）"与
#      "检索没命中（正常）"—— 两者在客户端看来一模一样：回答里都没有知识库内容。
#
# 两条都**不崩、不报错**，且 native 守卫全绿。所以判据必须锚**接线关系**：
#   · UI 函数体内真的调用了哪个引擎方法（调用方存在性）；
#   · 按钮的点击真的接到了那个函数（有入口还不够）；
#   · /health 的字段取自**嵌入那一份**，不是对话那份的复制品。
#
# 锚"文件里出现过某关键字"是恒真的（定义处、注释里都会出现），本仓库已吃过
# 几次亏（run_schema_sampler_guard 的 awk 段、run_kv_cache_guard 的标识符断言）。
#
# 运行：sh tools/run_embed_entry_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
JNI=app/src/main/cpp/llama_jni.cpp
API=app/src/main/java/com/xiaowan/localinference/HttpApi.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "EngineActivity.kt 存在" "$PY -c \"import pathlib,sys; sys.exit(0 if pathlib.Path('\$EA').is_file() else 1)\""
c "llama_jni.cpp 存在"     "[ -f \$JNI ]"

# ── ① native：嵌入模型的描述有**独立**的取值口（不是复用对话那一份）────────
c "native 导出 nativeEmbedModelDesc" \
  "grep -q 'Java_com_xiaowan_localinference_LlmEngine_nativeEmbedModelDesc' \$JNI"
c "nativeEmbedModelDesc 读的是嵌入 Session E（不是 S）" \
  "$PY tools/embed_entry/check_native_desc_session.py \$JNI"
c "Kotlin 声明了 nativeEmbedModelDesc" \
  "grep -q 'external fun nativeEmbedModelDesc' \$KT"

# ── ② App：真的有一个"加载嵌入模型"的入口 ─────────────────────────────────
c "UI 存在 doLoadEmbed / doUnloadEmbed 且真的调用引擎方法" \
  "$PY tools/embed_entry/check_entry_wiring.py \$EA"

# ── ③ /health：三个字段取自嵌入那一份 ─────────────────────────────────────
c "healthJson 的 embed_* 三字段取自嵌入真值" \
  "$PY tools/embed_entry/check_health_embed_fields.py \$API"
c "health_json 守卫已把三个新字段列入必检" \
  "grep -q 'embed_loaded' tools/run_health_json_guard.py"

# ── ④ 加载嵌入不碰对话（双常驻契约的 Kotlin 侧入口）───────────────────────
c "doLoadEmbed 调用的是 loadEmbedModel（不是 loadModel）" \
  "$PY tools/embed_entry/check_embed_load_target.py \$EA"

echo
if [ "$bad" = 0 ]; then
    echo "=== 嵌入入口守卫 全部通过（$ok 条）==="
else
    echo "=== 嵌入入口守卫 $bad 条失败（共 $((ok+bad)) 条）==="
    exit 1
fi
