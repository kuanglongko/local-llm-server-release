#!/bin/sh
# 日志页的静态断言。
#
# 为什么要有这一套：0.9.70 装机后用户报「开了探针，模型加载完了但日志页不显示
# 加载层数和内存信息」。日志其实**全都写进去了**（native 探针文件 + 会话文件 +
# ring buffer 都有），是**页面默认停在顶部**，而 [HTP实测]/[HTP参与] 那几行在
# 2000 行 ring 的**尾部** → 看不等于没有。
#
# 这类「数据在、界面没显示」的故障最容易反复出现（改 UI 时顺手把贴底去掉就能复现），
# 所以把两条硬约束钉住：日志页必须跟随最新行、且不允许出现「一屏渲染全部 ring 行」。
set -e
cd "$(dirname "$0")/.."
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
PROBE=app/src/main/java/com/xiaowan/localinference/HtpProbe.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 1) 渲染窗口固定，不许把整条 ring 塞进 TextView
c "日志页只渲染最近 N 行（有上限常量）"  "grep -q 'LOG_VIEW_LINES' \$ACT"
c "上限是有限值而不是 ring 全量"        "! grep -q 'takeLast(PROBE_RING)' \$ACT"
c "取尾部而非头部"                      "grep -q 'lines.takeLast(shown)' \$ACT"

# ── 2) 日志页必须贴底跟随，否则新行（探针摘要）永远看不见
c "有日志页贴底函数"                    "grep -q 'private fun followLogBottom()' \$ACT"
c "渲染日志后调用贴底"                  "sed -n '/private fun renderLog()/,/^    }/p' \$ACT | grep -q 'followLogBottom'"
c "切到日志页时也贴底一次"              "grep -q 'else if (i == 2) { rootScroll.post { followLogBottom() } }' \$ACT"

# ── 3) 贴底不得把用户上滑看历史的动作抢回去（否则比不跟随更难用）
c "上滑后停止跟随（有跟随标志）"        "grep -q 'private var logFollowBottom' \$ACT"
c "跟随标志在滚动回调里更新"            "grep -q 'if (curTab == 2) logFollowBottom = isLogAtBottom()' \$ACT"
c "跟随标志是贴底判据而非位移方向"      "grep -q 'private fun isLogAtBottom(): Boolean = chatAtBottom()' \$ACT"

# ── 4) 截断不静默：显示了多少、被截掉多少，必须在页面上说出来
c "有截断说明控件"                      "grep -q 'private lateinit var logTrimTv' \$ACT"
c "说明里有「仅显示最近」字样"          "grep -q '仅显示最近' \$ACT"
c "说明里给出完整日志的去处"            "grep -q '要完整日志请用上方「导出」' \$ACT"

# ── 5) 探针摘要的行确实来自引擎侧（说明「不显示」不是引擎没打）
c "引擎在加载后打 [HTP参与] 摘要"       "grep -q 'uiLog(HtpProbe.summary())' \$ENG"
c "引擎在加载后打 [HTP实测] 层分布"     "grep -q 'TAG_HTP_MEASURED' \$ENG"

# 层分布文本由 HtpProbe.measured() 产出（"N/M层在NPU… 权重 HTP=… OpenCL=… MiB"），
# 这是用户要看的「层数和内存信息」，断言它真的带层数与显存两个量。
c "层分布文本含层数"                    'grep -q "层在NPU" $PROBE'
c "层分布文本含显存占用"                'grep -q "MiB" $PROBE && grep -q "OpenCL=" $PROBE'

if [ "$bad" -eq 0 ]; then
    echo "=== 日志页显示单测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 日志页显示单测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
