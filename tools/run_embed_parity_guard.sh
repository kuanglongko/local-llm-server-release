#!/bin/sh
# 「向量逐位比对真的接上了」的源码级守卫（ISSUE #269 第 5 步）。
#
# ═══════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠 EmbedParity 的单测）
# ═══════════════════════════════════════════════════════════════════════
# 0.9.218 把批大小 `g` 单独定级，但**明确不授权沿用**，理由是那条断言
# 「不同批大小算出的向量逐位相同」**尚未验证**（第 5 步）。而"尚未验证"
# 这件事本身也会**静默地一直挂着**：
#
#   · `EmbedParity.compare` 算得对，但**没有入口**跑它 -> 用户永远量不出来；
#   · 有入口，但只跑合批那一侧 -> 比的是"合批自己和自己"，结论恒为"相同"
#     （一个**假的**断言成立，比没有结论更坏：它会让人据此沿用）；
#   · 跑了两侧，但结论只打日志不落 `/health` -> 跨版本要对的那条结论只能翻日志；
#   · `/health` 报了，但 `HealthCheck.parse` 没解析 -> 报告里读不到（与没报等价）；
#   · 结论报成布尔 -> "没测过"与"不是逐位"混成一个 false（行动方向相反）；
#   · 换嵌入模型不清结论 -> 看到的是上一个模型的结论（数字看着正常）；
#   · 入口只跑跨路、不跑"同路径重复" -> 「批布局改语义」与「编码本身不确定」
#     混在一个读数里（首轮真机那条 firstDiffDim=0 正是分不开的两件事）；
#   · 只重复**逐条**那条路 -> 「**合批自身**不确定」与「批布局改语义」混在一起
#     （而前者根本不该谈 `g`：先查合批那条路）；
#   · 只有一组长短悬殊样本 -> 「批布局」与「不等长的 padding/切分」分不开；
#   · 样本条数与批大小不对齐 -> 跨版本比结论时多出"图不一样"这个变量；
#   · 界面那行说明的条数**手抄**（不是从常量取）-> 常量改条数时就地漂，
#     而那行字正是让人按对的样本数解释结论的手段；
#   · 落 `/health` 的是**等长**那组（构造上必然稳）-> 报出来的是"必然成立"
#     那句，而它答不了"长短悬殊那批换批大小会不会改答案"（真机 PR #301 现场）；
#   · 报读数时**不说取自哪一组** -> 同一句"逐位相同 4/4"在两组下同形，
#     读者把等长组的结论当成长短悬殊组也成立。
#
# **单测钉不住它们**：`EmbedParity` 的单测喂什么向量就比什么向量，
# "界面上有没有那个入口"、"结论有没有落进 /health"它看不见。
# 所以判据必须锚源码结构关系。
#
# 运行：sh tools/run_embed_parity_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
HEALTH=app/src/main/java/com/xiaowan/localinference/HealthCheck.kt
PARITY=app/src/main/java/com/xiaowan/localinference/EmbedParity.kt
PROBE=app/src/main/java/com/xiaowan/localinference/EmbedParityProbe.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "六个源文件都在" \
  "[ -f \$ENGINE ] && [ -f \$ACT ] && [ -f \$HTTP ] && [ -f \$HEALTH ] && [ -f \$PARITY ] && [ -f \$PROBE ]"

# ① 全链判据收在一个 checker 里（它们是同一个契约的几个面），失败时逐条点名。
c "逐位比对（真机入口->两侧都跑->结论->/health 读数->探活解析）全程接通" \
  "$PY tools/embed_parity/check_embed_parity_wiring.py \"\$ENGINE\" \"\$ACT\" \"\$HTTP\" \"\$HEALTH\" \"\$PARITY\" \"\$PROBE\""

# ② 判定层与决策层**必须分开**：`EmbedParity` 只给结论，不许出现"沿用 / 重建"这类
#    决策符号 —— 让它去决定沿用就是把"观测"与"决策"焊在一起，而它们的正确方向相反
#    （观测要忠实，决策要保守：断言没验出来时**不能**沿用）。
c "判定层不做决策（EmbedParity 里不出现沿用/重建这类决策符号）" \
  "$PY -c \"
import re, sys
s = open('$PARITY', encoding='utf-8').read()
sys.exit(0 if not re.search(r'Load\.(none|ok)|savePartial|rebuild\s*\(', s) else 1)
\""

# ③ 结论必须是**分档**的（不是布尔）："没测过"与"不是逐位"必须分得开。
c "结论分档（EmbedParity.Last.Verdict 枚举），不是布尔" \
  "grep -q 'enum class Verdict' \$PARITY"

# ④ 样本集必须**写死**成常量（不是运行时从库里取）：跨版本结论要能对上。
#    且必须是**两组**（长短悬殊 + 等长）：只有一组长短悬殊时，「批布局」与
#    「不等长的 padding/切分」两个来源混在同一个读数里 —— 而"不等长是不是
#    元凶"正是首轮真机逼出来的那个问题。
c "固定样本集是常量（EmbedParityProbe.TEXTS），不是运行时从库里取" \
  "grep -q 'val TEXTS' \$PROBE && grep -q 'LONG_REPEAT' \$PROBE"

c "等长对照样本集是**另一个**常量（EmbedParityProbe.SAME_LEN），不是并进 TEXTS" \
  "grep -q 'val SAME_LEN' \$PROBE"

# ⑤ 判定层必须把「两条路各自的重现性」分成两格。只重复逐条那条路时，
#    「**合批自己**复现不了自己」这一格不存在 —— 而它与"批布局改语义"是两件事
#    （前者先查合批，后者才谈 `g` 收不收窄），处置相反。
c "两条路各自的重现性分开报（repeatRows + repeatBatchedRows）" \
  "grep -q 'repeatBatchedRows' \$PARITY && grep -q 'repeatRows' \$PARITY"

# ⑥ 界面那行说明的条数必须与**常量同源**（读 `EmbedParityProbe.*.size`），不手抄：
#    0.9.222 把两组各 6 条改成各 4 条时，手抄的那份就地漂了（说明里还是"等长 5 条"，
#    而真跑的是 4 条）—— 那行字正是让人对读数的手段。判据读的是**两句 `size`**，
#    不是"文件里出现过 4 这个数字"（后者在改错方向时照样绿）。
c "界面说明里的样本条数与常量同源（不是手抄）" \
  "grep -q 'EmbedParityProbe.TEXTS.size' \$ACT && grep -q 'EmbedParityProbe.SAME_LEN.size' \$ACT"

# ⑦ 落 `/health` 必须**只落长短悬殊那组**（等长组按构造必然稳，落它等于用
#    必然成立的结论回答"换批大小会不会改答案"）。与检索那格同源：放行 `g`
#    取决于**最坏**的那组。判据锚的是那句 `record` 的**闸门**，不是"文件里
#    出现过 LONG_SHORT_LABEL"（后者在改成落等长组时照样绿）。
c "落 /health 只落长短悬殊那组（等长组不许覆盖它）" \
  "grep -q 'label == LONG_SHORT_LABEL) EmbedParity.Last.record(report, label)' \$ENGINE && grep -q 'const val LONG_SHORT_LABEL' \$ENGINE"

# ⑧ 报告必须说得出**这份读数取自哪一组**（否则"逐位相同 4/4"在两组下同形，
#    等长组的结论会被当成长短悬殊组也成立）。三个面都要有：落盘时带组名、
#    `/health` 有字段、探活解析它并按组换措辞。
c "读数带来源组（落盘带 label + /health 报 embed_parity_group + 探活按组换措辞）" \
  "grep -q 'val group: String' \$PARITY && grep -q '\"embed_parity_group\":' \$HTTP && grep -q 'embed_parity_group' \$HEALTH && grep -q 'embedParityGroup' \$HEALTH"

echo
echo "=== 嵌入逐位比对守卫：PASS $ok / FAIL $bad ==="
[ "$bad" -eq 0 ] || exit 1
