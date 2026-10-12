#!/bin/sh
# 「嵌入建库的线程数：从头到尾是通的」的源码级守卫（ISSUE #269）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠某个单测）
# ═══════════════════════════════════════════════════════════════════════════
# 用户报的现象：8e（骁龙 8 Elite，8 核）上建索引 **1 秒 1.3 条**，
# 7000 条约等于两小时。这不是"手机就这么慢"，是三处叠加：
#
#   ① `loadEmbedModel` 的 `nThreads` 默认值**写死 4**，与设备无关；
#   ② `doLoadEmbed` 调它时**不传线程数** -> 设置页那个 threads 输入框
#      对建库完全无效（用户从 4 调到 8，速度一点都不变，界面上看不出来）；
#   ③ `RagCost` 的秒/条口径比真机读数还小 2.6 倍，把 7000 条报成"约 35 分钟"。
#
# 这三处的共同形态是"参数在某处写死 / 在某处断掉，而界面上完全看不出来"。
# **单测钉不住它们**：`loadEmbedModel` 的单测喂什么线程数就跑什么线程数，
# "UI 到底传没传"它看不见。所以判据必须锚源码结构关系。
#
# 运行：sh tools/run_rag_index_speed_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ENG=$SRC/LlmEngine.kt
ACT=$SRC/EngineActivity.kt
COST=$SRC/RagCost.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "三个源文件都在" "[ -f \$ENG ] && [ -f \$ACT ] && [ -f \$COST ]"

# ① 线程数这一条链：默认值自适应 / 真读本机核数 / UI 真的传下去 / 兜底也自适应 /
#    没有残留的"按常数兜底"。五条判据收在一个 checker 里（它们是同一个契约的五个面），
#    失败时逐条点名。
c "嵌入线程数从默认值到 UI 实参全程接通" \
  "$PY tools/rag_index_speed/check_embed_threads_wiring.py \"\$ENG\" \"\$ACT\""

# ② 秒/条口径不能比真机读数小太多 —— 这是"提示报小了比不提示更坏"的判据。
#    锚的是**上界这个数本身**（它直接决定 7000 条被报成几分钟还是几小时），
#    不是"文件里出现过 1.00"（那种判据在常量被改名后照样绿）。
#    真机读数 0.77 秒/条（ISSUE #269），上界必须 ≥ 它。
c "RagCost 的上界覆盖真机读数（≥0.77 秒/条）" \
  "$PY -c \"
import re,sys
t=open('\$COST',encoding='utf-8').read()
m=re.search(r'SEC_PER_ENTRY_HI\s*=\s*([0-9.]+)',t)
sys.exit(0 if m and float(m.group(1))>=0.77 else 1)\""

# ③ 小时档必须存在：上界 1.0 秒/条时 7000 条算出 7000 秒 = 近两小时，
#    若 humanSeconds 的最高档还是"十几分钟"，提示仍然是在骗人。
c "humanSeconds 有『小时』这一档（不是止于十几分钟）" \
  "$PY -c \"
import re,sys
t=open('\$COST',encoding='utf-8').read()
i=t.find('fun humanSeconds')
b=t[i:i+1200]
sys.exit(0 if ('3600' in b and '小时' in b) else 1)\""

echo
echo "=== 嵌入建库速度守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
