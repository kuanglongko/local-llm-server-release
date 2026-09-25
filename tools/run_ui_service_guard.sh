#!/bin/sh
# 「UI / 服务 / 存储」的**接线**守卫（模块 I 之 PR-1：I-1 / I-6）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么这一轮不能靠"加个 grep"就完事
# ═══════════════════════════════════════════════════════════════════════════
# I-1 与 I-6 是**同一条纪律**的两个半面：`HttpApi.stop()` 必须先于"卸载模型 / 停服务"。
# 它俩的失效形态都是"读代码时每一句都成立"，所以判据必须锚定**顺序关系**与
# **配对关系**，而不是存在性（"文件里出现过 HttpApi.stop"）。
#
#   I-1 ACTION_STOP 先 `LlmEngine.unload()` 后 `HttpApi.stop()`，与文件里的
#       ⚠ 注释和 README 的声明**正好相反**。`LlmEngine.unload()` 只持 `loadLock`，
#       既不持 `genLock` 也不看 `hasModel`；而 `HttpApi.stop()` 不打断生成线程，
#       只 close() socket —— 卸载期间在途生成会打到已释放的 native 资源上。
#       同一个动作 UI 路径（EngineActivity.doUnload）**挡了**（生成中不许卸载），
#       Service 路径（ACTION_STOP）没挡 —— 两条路径判据不同，而失效方向是
#       "更危险的那条更宽松"。
#   I-6 两处 `stopSelf()` 没配对 `HttpApi.stop()`：`desired` 仍为 true，watchdog
#       每 30s 探活、连续 2 次失败就重启 listener，只靠 `onDestroy()` 兜（异步）。
#       当前不可达（onDestroy 远快于 30s），但"配对"没有任何守卫。
#
# 判据形态：**行号顺序**（stop 的行号必须小于 unload）+ **配对**（每处 stopSelf()
# 之前同一分支里必须先有 HttpApi.stop()）+ **对照**（UI 与 Service 两处"生成中
# 不许卸载"必须同时存在）。只钉其中一处会漏掉另一处 —— 而没有的那一处恰是更危险的。
#
# 全是源码级断言，不依赖任何工具链。运行：sh tools/run_ui_service_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
SVC=$SRC/InferenceService.kt
ACT=$SRC/EngineActivity.kt
ENG=$SRC/LlmEngine.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取函数体：从 `fun NAME(` 起，到第一个**顶格 4 空格**的 `}` 止。
# 与 tools/run_http_lifecycle_guard.sh 的 guard_fn 同一判据 ——
# 两处各写一份取函数体的近似，就是"判据漂移"的起点。
guard_fn() {
    awk -v name="$1" '
        $0 ~ ("fun " name "\\(") { infn=1 }
        infn { print; if ($0 == "    }") exit }
    ' app/src/main/java/com/xiaowan/localinference/HttpApi.kt
}
# 在 [起, 终] 行区间里找**代码行**（排除整行注释）的第一个匹配行号，无则 0。
# ⚠ 必须排注释：本轮两处锚点文本在 ⚠ 注释里也出现过，算进来会得到"假 PASS"
# （注释行号恰好排在真正实现之前，于是一份「先卸载后停服」的源码被判成顺序正确）。
firstline() { awk -v s="$1" -v e="$2" -v p="$3" 'NR>=s && NR<=e && $0 !~ /^[ \t]*\/\// && $0 ~ p { n=NR; exit } END { print (n==""?0:n) }' "$4"; }
lastline()  { awk -v s="$1" -v e="$2" -v p="$3" 'NR>=s && NR<=e && $0 !~ /^[ \t]*\/\// && $0 ~ p { n=NR } END { print (n==""?0:n) }' "$4"; }

# ── 1) I-1：ACTION_STOP 分支内，HttpApi.stop() 必须**先于** LlmEngine.unload() ──
# 锚的是"行号顺序"，不是"两行都在"。这正是 I-1 的本体：两行都在，顺序反了。
ASTOP_START=$(grep -n "ACTION_STOP ->" "$SVC" | head -1 | cut -d: -f1)
ASTOP_END=$(awk -v s="$ASTOP_START" 'NR>s && /return START_NOT_STICKY/ { print NR; exit }' "$SVC")
[ -n "$ASTOP_START" ] && [ -n "$ASTOP_END" ] || { echo "FAIL  找不到 ACTION_STOP 分支边界（锚点漂了，守卫需更新）"; exit 1; }

STOP_LN=$(firstline "$ASTOP_START" "$ASTOP_END" 'HttpApi\.stop\(\)' "$SVC")
UNLOAD_LN=$(firstline "$ASTOP_START" "$ASTOP_END" 'LlmEngine\.unload\(\)' "$SVC")
c "ACTION_STOP 分支里两处都在（stop=$STOP_LN unload=$UNLOAD_LN）" \
  "[ \"$STOP_LN\" -gt 0 ] && [ \"$UNLOAD_LN\" -gt 0 ]"
c "HttpApi.stop() 先于 LlmEngine.unload()（I-1 本体：顺序）" \
  "[ \"$STOP_LN\" -gt 0 ] && [ \"$UNLOAD_LN\" -gt 0 ] && [ \"$STOP_LN\" -lt \"$UNLOAD_LN\" ]"
# 旧写法（先卸载后停服）必须绝迹 —— 防有人"顺手"把它挪回去
# 只认**代码行**：注释里也会出现 `LlmEngine.unload()`，把它算进来会恒假触发
c "「先卸载后停服」旧写法已绝迹" \
  "! awk -v s=$ASTOP_START -v e=$ASTOP_END 'NR>=s&&NR<=e' $SVC | grep -v '^\s*//' | awk '/LlmEngine[.]unload[(][)]/{u=NR} /HttpApi[.]stop[(][)]/{if(u&&NR>u){print \"BAD\";exit}}' | grep -q BAD"
# unload 的前提判据仍在（不是把 hasModel 检查一起删掉）
c "卸载前仍以 hasModel 为闸门" \
  "awk -v s=\"$ASTOP_START\" -v e=\"$ASTOP_END\" 'NR>=s&&NR<=e&&/if \\(LlmEngine\\.hasModel\\)/{print \"OK\";exit}' \$SVC | grep -q OK"
c "卸载异常不外抛（try/catch 仍在，避免停服路径被一个 native 异常打断）" \
  "awk -v s=\"$ASTOP_START\" -v e=\"$ASTOP_END\" 'NR>=s&&NR<=e&&/catch \\(t: Throwable\\)/{print \"OK\";exit}' \$SVC | grep -q OK"

# ── 2) I-6：每一处 stopSelf() 之前，同一分支里必须先有 HttpApi.stop() ─────────
# 逐个数 stopSelf()，对每一处向上找"最近的一个分支开口"，断言区间内有 HttpApi.stop()。
# 这是"配对"判据 —— 只看"文件里有 stop()"挡不住 I-6（那一处就是漏的）。
PAIRS_OK=1
for ln in $(grep -n 'stopSelf()' "$SVC" | grep -v ':[[:space:]]*//' | cut -d: -f1); do
  # 向上找最近的分支起始（-> { 或 onDestroy），再确认其与 stopSelf 之间有 HttpApi.stop()
  seg=$(awk -v t="$ln" 'NR<=t && (/-> \{$/ || /override fun onDestroy/) { s=NR } s && NR==t { print s }' "$SVC")
  if [ -z "$seg" ]; then PAIRS_OK=0; continue; fi
  hit=$(firstline "$seg" "$ln" 'HttpApi\.stop\(\)' "$SVC")
  if [ "$hit" -eq 0 ]; then echo "      未配对：stopSelf() 在 $ln 行（所处分支自 $seg 行）"; PAIRS_OK=0; fi
done
c "每处 stopSelf() 在同一分支内都先配了 HttpApi.stop()（I-6）" "[ \"$PAIRS_OK\" = \"1\" ]"
STOPCOUNT=$(grep -c 'HttpApi[.]stop()' "$SVC")
c "至少存在 3 处 HttpApi.stop() 配对（ACTION_STOP / 两个 stopSelf 分支 / onDestroy）" \
  "[ $STOPCOUNT -ge 3 ]"
# onDestroy 兜底仍在（非用户意图的销毁也必须收敛 socket）
c "onDestroy() 仍收敛 HTTP（系统回收路径的兜底）" \
  "awk '/override fun onDestroy\(\)/,/^    \}/' \$SVC | grep -q 'HttpApi\.stop()'"

# ── 3) 对照：两处「生成中不许卸载」必须**同时**存在 ───────────────────────────
# I-1 之所以成立，是因为 UI 路径挡了、Service 路径没挡。只钉一处会漏另一处。
c "UI 路径（EngineActivity.doUnload）仍拦「生成中不许卸载」" \
  "awk '/private fun doUnload\(\)/,/^    \}/' \$ACT | grep -q 'if (generating || HttpApi\\.isGenerating)'"
c "Service 路径的守护来自顺序（stop 先于 unload），与 UI 路径语义对齐" \
  "awk -v s=\"$ASTOP_START\" -v e=\"$ASTOP_END\" 'NR>=s&&NR<=e&&/HttpApi\\.stop\\(\\)/{s2=NR} NR>=s&&NR<=e&&/LlmEngine\\.unload\\(\\)/{if(s2&&NR>s2){print \"OK\";exit}}' \$SVC | grep -q OK"
# 这条防的是"把 stop() 挪回去换 getExternalFilesDir 之类的方式绕过" —— 判据锚实现，不锚注释
c "注释里的顺序声明与实现一致（注释说的是 stop 先）" \
  "awk -v s=\"$ASTOP_START\" 'NR>=s && NR<=s+8' \$SVC | grep -q '必须先 \`HttpApi.stop()\` 再卸载模型'"

# ── 4) 机制事实：unload() 确实不持 genLock / 不看 hasModel（I-1 的前提）──────
# 这条是"为什么顺序重要"的机制锚点。如果哪天 unload() 自己开始持 genLock，
# 那么顺序就不再是唯一防线，这条断言会失败并提示重新评估。
c "LlmEngine.unload() 只持 loadLock（机制事实，I-1 的成立前提）" \
  "awk '/fun unload\(\)/,/^    \}/' \$ENG | grep -q 'synchronized(loadLock)'"
c "LlmEngine.unload() 不持 genLock（若将来持了，需重新评估本轮的顺序判据）" \
  "! awk '/fun unload\(\)/,/^    \}/' \$ENG | grep -q 'genLock'"
c "HttpApi.stop() 会收敛在途连接（顺序在前才拦得住已受理的请求）" \
  "guard_fn stop | grep -q 'for (c in activeConns.toList())'"
c "HttpApi.stop() 先置 desired=false（看门狗据此不自愈）" \
  "awk '/fun stop\(\)/,/^    \}/' \$SRC/HttpApi.kt | grep -q 'desired.set(false)'"

# ── 5) 注释里不许写嵌套块注释开头 ─────────────────────────────────────────────
c "InferenceService 注释里没有嵌套块注释开头" \
  "! grep -nE '^\s*\*(.*[^/]|^)/\*' \$SVC"

echo ""
if [ "$bad" = "0" ]; then echo "=== UI/服务接线守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== UI/服务接线守卫：PASS $ok / FAIL $bad ==="; exit 1
