#!/bin/sh
# 「远端知识库（RAG 阶段 3）+ /v1/embeddings」的源码级接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 阶段 3 把知识库从「App 内自用」推到「远端 HTTP 也能用」，同时补上
# `/v1/embeddings`。这一批的失效形态**全是静默的**，而且有两处会**主动伤害用户**：
#
#   ① 远端开关的默认值读成 true
#      —— 升级后所有没动过设置的人**突然开始**被注入知识库内容（接口照 200、
#      结构合法），而他们从没要求过。这是本阶段唯一会主动伤害用户的错误。
#   ② 远端库名与 App 内那个共用键
#      —— App 里点一下别的库，远端某个正在轮询的客户端就**静默**换库。
#      回答变差，而没有任何现象指向"库换了"。
#
# 其余几条的共同形态是「接线没接上，而一切正常」：
#   ③ 注入没接到 HTTP 路径上（只在 App 内那条）-> 用户开了开关，远端照旧不注入；
#   ④ 协议层字段漏在某一条响应路径上 -> 那条路径上的调用方永远看不到"被注入了"；
#   ⑤ 开关判据写了两处 -> 漂移方向是"多注入一个字节"；
#   ⑥ `/v1/embeddings` 被"顺手"绑上 RAG 开关 -> 两件不相干的事互相绑架。
#
# 判据锚**结构关系**，不锚"某个字符串出现过"（那是恒真的：写错的那一支里
# 关键字往往还在）。纯静态断言，不依赖任何工具链。
#
# 运行：sh tools/run_rag_remote_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "RagProtocol.kt 存在" "[ -f \$SRC/RagProtocol.kt ]"
c "EmbeddingsApi.kt 存在" "[ -f \$SRC/EmbeddingsApi.kt ]"

# ── 接线（见 tools/rag_remote/check_wiring.py 的文件头）─────────────────────
c "远端知识库的接线守卫" "$PY tools/rag_remote/check_wiring.py \$SRC"

# ── 解耦（见 tools/rag_remote/check_embeddings_decoupled.py 的文件头）──────
c "/v1/embeddings 与知识库开关完全解耦" \
  "$PY tools/rag_remote/check_embeddings_decoupled.py \$SRC/HttpApi.kt"

# ── 默认值必须**正面**判：读键的那一行实参是 false ────────────────────────
# check_wiring 已经判过，这里再单独一条是为了让"默认值"这件事在守卫输出里
# 有自己的一行 —— 它是本阶段唯一会主动伤害用户的错误，值得单独可见。
c "远端开关默认 false（读键的实参）" \
  "grep -q 'getBoolean(KEY_REMOTE_ENABLED, false)' \$SRC/RagInject.kt"
c "App 内开关默认仍 true（没被带偏）" \
  "grep -q 'getBoolean(KEY_ENABLED, true)' \$SRC/RagInject.kt"

# ── 两个开关 / 两个库名：四个键两两不同 ───────────────────────────────────
c "四个 prefs 键两两不同（撞名即互相覆盖）" \
  "$PY - <<'PYEOF'
import re, sys
s = open('app/src/main/java/com/xiaowan/localinference/RagInject.kt', encoding='utf-8').read()
keys = re.findall(r'const val (KEY_ENABLED|KEY_CURRENT|KEY_REMOTE_ENABLED|KEY_REMOTE_KB)\\s*=\\s*\"([^\"]+)\"', s)
vals = [v for _, v in keys]
sys.exit(0 if len(vals) == 4 and len(set(vals)) == 4 else 1)
PYEOF"

# ── 远端库名不得"跟随当前库"（那等于把会变的量当输入）────────────────────
# 判据：remoteKb 的读取里**不许**出现 currentKb（跟随 = 静默切换又回来了）。
c "remoteKb 不跟随 currentKb（不读 App 内那个键）" \
  "\$PY tools/rag_remote/check_remote_kb_independent.py \$SRC/RagInject.kt"

# ── 远端库名保存要有可感知的反馈（失焦即存的静默性）──────────────────────
# 失焦落盘本身是对的（与 system/库名同款习惯），但**只有落盘、没有信号**时，
# 用户"敲完名字、点了建库/别的库"之后没有任何东西告诉他这次改动生效了没有 ——
# 表现就是"我改了远端库名，好像没反应"。判据：
#   · 保存函数在成功路径上刷新状态行（那才是常驻反馈面）；
#   · 主动点「确定」时补一句 Toast（失焦路不弹，免得每点一次空白都吵一次）；
#   · 界面上真的有一个可点的「确定」与一行常驻说明。
c "远端库名保存会刷新状态行" \
  "\$PY - <<'PYEOF'
import re
s = open('app/src/main/java/com/xiaowan/localinference/EngineActivity.kt', encoding='utf-8').read()
i = s.index('private fun saveRagRemoteKbName')
j = s.index('private fun doBuildIndex', i)
blk = s[i:j]
ok = blk.count('renderRagStatus()') >= 2 and 'announce' in blk
raise SystemExit(0 if ok else 1)
PYEOF"
c "远端库名有可点的「确定」按钮" \
  "grep -q 'saveRagRemoteKbName(announce = true)' \$SRC/EngineActivity.kt"
c "界面有一行说明生效时机（不是只靠 Toast）" \
  "grep -q '改完点「确定」或点空白处即保存' \$SRC/EngineActivity.kt"

# ── 知识库设置独立成页（设置在「设置」与「日志」之间）──────────────────────
# 知识库那一段太长，和设置页混在一起把高频项挤远。判据锚**四页**这个事实，
# 而不是"有没有 pageRag 这个词"：
#   · 滑动范围是 4、页下标数组长度是 4；
#   · 知识库页挂进 root，且在「日志」页之前；
#   · 日志页下标从 2 挪到 3（贴底/跟随那几处判据必须同步，漂了就是日志页失联）。
c "翻页范围是 4 页" "grep -q 'attachSwipePager(rootScroll, 4)' \$SRC/EngineActivity.kt"
c "页位置数组长度是 4" "grep -q 'private val pageScrollY = IntArray(4)' \$SRC/EngineActivity.kt"
c "知识库页挂进 root（在日志页之前）" \
  "\$PY - <<'PYEOF'
s = open('app/src/main/java/com/xiaowan/localinference/EngineActivity.kt', encoding='utf-8').read()
a = s.index('root.addView(pageRag')
b = s.index('root.addView(pageLog')
raise SystemExit(0 if 0 <= a < b else 1)
PYEOF"
c "日志页下标已同步为 3（贴底 + 跟随判据）" \
  "[ \$(grep -c 'curTab == 3\|curTab != 3' \$SRC/EngineActivity.kt) -ge 2 ] && \
   grep -q 'else if (i == 3) { rootScroll.post { followLogBottom() } }' \$SRC/EngineActivity.kt"

# ── `local_rag` 的 {kb, injected} 必须**同源取一次** ────────────────────
# ⚠ 这一条是本轮补的。原先 `ragFieldOf(count)` 到响应时才 `remoteKb(prefs)`
#    现读库名，而条数是**注入那一刻**写的 —— 两者可能来自两个不同的时刻：
#    用户在长生成期间把「远端库名」从 A 改成 B，响应里就成了 `kb:"B", injected:3`，
#    一对**从未同时成立过**的事实。字段合法、JSON 合法、只是内容不真，
#    而调用方据此判断"哪个库在工作"会得出错的结论。
#    判据：响应取值处传的是注入当时存下的那个库名，且 `ragFieldOf` 体内
#    **不再读** remoteKb（读一次就是又开了那个窗口）。
c "响应里的 local_rag 用注入当时存下的库名（不是响应时现读）" \
  "grep -q 'ragFieldOf(lastRagKb, lastRagInjected.get())' \$SRC/HttpApi.kt"
# ⚠⚠ 这条判据**不能**用 `_fnbody.kotlin_fn_body('ragFieldOf')`：
#    `ragFieldOf` 是**表达式体**（`= RagProtocol.responseField(...)`），后面没有
#    `{`，于是那个 helper 会一路找下去、把**下一个函数**（`compChunkJson`）的函数体
#    当成它的 —— 判据在别人身上跑，`remoteKb(` 永远找不到，**恒绿**。
#    （本轮真踩到：桩⑬ 打上去守卫不红，才发现判的是隔壁函数。）
#    所以这里取的是**整段声明**（签名 + 表达式体到下一个函数/空行为止）。
c "ragFieldOf 体内不再现读 remoteKb（同源取一次的判据）" \
  "\$PY tools/rag_remote/check_ragfield_src.py app/src/main/java/com/xiaowan/localinference/HttpApi.kt"
c "注入成功那一刻把库名与条数一起写下（两个值同一个判据点）" \
  "\$PY - <<'PYEOF'
s = open('app/src/main/java/com/xiaowan/localinference/HttpApi.kt', encoding='utf-8').read()
i = s.index('lastRagInjected.set(rag.count)')
seg = s[i:i + 400]
raise SystemExit(0 if 'lastRagKb = rag.kb' in seg else 1)
PYEOF"

echo
echo "=== 远端知识库守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
