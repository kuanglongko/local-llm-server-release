#!/bin/sh
# `parallelN` 说明文案的存在性守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单独一条（它防的不是 bug，是"文案被顺手删掉"）
# ═══════════════════════════════════════════════════════════════════════════
# `parallelN` 只把 n_seq_max 传给库，**不产生并发能力**：服务端单并发，
# 第二个请求直接 503。以前只有代码知道这件事，UI 上是一个裸输入框，
# README 里也只混在"可调参数"一串名字里 —— 于是"填了没变快"会被归因成手机慢，
# 而真相是这个旋钮拧不动。
#
# 本轮把说明补到了输入框旁边与 README。但文案是**最容易在后续重构里被删掉**的东西：
# 它不影响编译、不影响任何测试、删了也没有任何现象，直到下一个人再次被误导。
# 所以把"这三处必须写着"钉成断言。纯静态，不需要工具链。
#
# 运行：sh tools/run_parallel_hint_tests.sh
set -e
cd "$(dirname "$0")/.."
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
JNI=app/src/main/cpp/llama_jni.cpp
README=README.md
STATUS=HTP-STATUS.md

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 0) 前提：这个参数确实只透传、不构成并发（若哪天真做了并发，本守卫必须一起改）
c "native 侧只把 parallelN 透传成 n_seq_max" "grep -q 'if (parallelN  > 0) cp.n_seq_max' \$JNI"
c "服务端仍是单并发（busy 闸门 + genLock 都在）" \
  "grep -q 'busy.compareAndSet(false, true)' \$ACT_DIR/HttpApi.kt 2>/dev/null || grep -q 'busy.compareAndSet(false, true)' app/src/main/java/com/xiaowan/localinference/HttpApi.kt"

# ── 1) 设置页：说明必须写在输入框旁边（只写进 README 等于没写）
c "设置页有 parallelN 说明控件"        "grep -q 'parallelNHintTv' \$ACT"
c "说明控件真的加进了参数面板"          "grep -q 'labRowG, gridRowG, parallelNHintTv' \$ACT"
c "标签写明「当前未生效」"              "grep -q 'parallelN 并行序列数（当前未生效）' \$ACT"
c "说明里点出「不产生并发」"            "grep -q '当前不产生并发' \$ACT"
c "说明里点出「503 / 不会排队」这个可观测现象" \
  "grep -q '503' \$ACT && grep -q '不会排队' \$ACT"
c "说明里点出「瓜分 n_ctx」这个代价"    "grep -q '瓜分 n_ctx' \$ACT"
c "说明里给出建议值（保持 1）"          "grep -q '建议保持 1' \$ACT"
c "说明里点出「已搁置」"                "grep -q '已搁置' \$ACT"

# ── 2) 输入框与持久化键刻意保留（删掉会让已保存的参数无处可改）
c "parallelN 输入框仍在参数面板里"      "grep -q 'gridRowG.addView(parallelNEt' \$ACT"
c "parallelN 仍参与持久化"              "grep -q '\"parallelN\" to parallelNEt' \$ACT"

# ── 3) README：并发形态要写在「HTTP 接口」这一节里
c "README 有「并发形态」段"             "grep -q '并发形态：单并发' \$README"
c "README 点出 parallelN 不改变并发"    "grep -q '不改变这一点' \$README"
c "README 给出瓜分 n_ctx 的具体例子"    "grep -q '每路实际可用约 2048' \$README"
c "README 点出搁置"                     "grep -q '已搁置' \$README"
c "README「已知限制」里有单并发一条"    "grep -q '\\*\\*单并发\\*\\*：同一时刻只跑一轮生成' \$README"
c "README 可调参数里 parallel 有标注"   "grep -q '当前不产生并发' \$README"

# ── 4) HTP-STATUS.md：看板必须能一眼看出"搁置"
c "看板里该项标为搁置"                  "grep -q '并发 slot 调度（真并发 / .n_seq_max > 1.）\\*\\* | \\*\\*搁置' \$STATUS"
c "有独立一节记录评估结论"              "grep -q '并发 slot 调度：评估结论是搁置' \$STATUS"
c "结论里写清平行的代价面（三处）"      "grep -q '每 slot 一本' \$STATUS && grep -q '停指定 slot' \$STATUS"

echo ""
if [ "$bad" = "0" ]; then echo "=== parallelN 说明文案守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== parallelN 说明文案守卫：PASS $ok / FAIL $bad ==="; exit 1
