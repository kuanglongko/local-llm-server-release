#!/bin/sh
# 自测 `tools/run_chat_archive_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是 grep/awk 的存在性与结构性断言，它有三个自欺模式（本仓库各栽过一次）：
#
#   1. **恒真**：pattern 写成到处都有的文本，于是真 bug 改回去照样 PASS。
#      本轮最容易恒真的就是"导出不引用 capChars"—— 注释里为了讲清
#      "为什么不裁剪"必然出现这个词，不剥注释的判据会**恒假**（一直红）。
#   2. **恒假后被静音**：pattern 与源码差一个字符 → 守卫永远 FAIL → 有人
#      把 exit 1 改成 `|| true`，那时它彻底没用了。
#   3. **锚错位置**：钉"文件里出现过某函数"而不是"**在这条路径里**按这个
#      顺序做了这件事" —— 前者在定义处就恒真（"格式名判据在读 sessions 之前"
#      就是为此存在的）。
#
# 桩全部来自⑧条"真正会写错的写法"，不是编出来的：
#   · 导入按原 id 覆盖现有会话（用户几天后才发现记录被换掉了）
#   · 格式名对不上就"尽力解析"（选错文件被读成"记录是空的"）
#   · role 不过滤（非 user/assistant 混进历史，被模型当指令执行）
#   · 导出套上 MAX_CHARS 裁剪（记录"看起来完整"其实被截了头）
#   · 导出 MIME 退回 text/plain（.json 被当纯文本，全程无报错）
#   · 会话文件布局在 UI 里自己拼（改命名规则后静默失效）
#   · 导入复用选模型的请求码 42（选完文件什么都没发生）
#   · 上限静默截断（用户以为文件里就这么多）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_chat_archive_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_chat_archive_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
ARC=$SRC/ChatArchive.kt
SS=$SRC/SessionStore.kt
ACT=$SRC/EngineActivity.kt
LEX=$SRC/LogExport.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ARC" "$TMP/arc.bak"; cp "$SS" "$TMP/ss.bak"
cp "$ACT" "$TMP/act.bak"; cp "$LEX" "$TMP/lex.bak"
restore() { cp "$TMP/arc.bak" "$ARC"; cp "$TMP/ss.bak" "$SS";
            cp "$TMP/act.bak" "$ACT"; cp "$TMP/lex.bak" "$LEX"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/arc.bak" "$ARC"; cp "$TMP/ss.bak" "$SS";
              cp "$TMP/act.bak" "$ACT"; cp "$TMP/lex.bak" "$LEX"; }

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

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：导入按**原 id** 覆盖现有会话（本特性最坏的失败方向）──────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 桩的锚点取「函数签名 + 造新 id 那一行」两行（ISSUE #326 之后 id 行与
#   `val arr` 之间多了一段裁剪逻辑，原先的三行锚点会漂）。同一句造 id 在
#   SessionStore 里有**三处**（create/createNoMigrate/writeImported），带上签名
#   才唯一 —— 桩的意图不变：把 writeImported 的新 id 换成原 id。
old = """    private fun writeImported(ctx: Context, s: ChatArchive.Session, title: String): String? {
        val id = System.currentTimeMillis().toString(36) + ((Math.random() * 1e4).toInt().toString(36))"""
new = """    private fun writeImported(ctx: Context, s: ChatArchive.Session, title: String): String? {
        val id = s.id"""
assert s.count(old) == 1, "桩①：没找到 writeImported 里造 id 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（导入按原 id 覆盖）" "导入的每条都拿**新 id**"
reset_src

# ── 2. 桩②：格式名对不上就"尽力解析"（选错文件读成"记录是空的"）───────────
"$PY" - "$ARC" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val fmt = root.optString("format", "")
        if (fmt != FORMAT) {"""
new = """        val fmt = root.optString("format", "")
        if (false) {"""
assert s.count(old) == 1, "桩②：没找到格式名判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（格式名对不上也尽力解析）" "格式名对不上就返回 error"
reset_src

# ── 3. 桩③：role 不过滤（非 user/assistant 混进历史，被模型当指令）─────────
"$PY" - "$ARC" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                if (role != "user" && role != "assistant") { skipped++; continue }"""
new = """                // 不过滤了"""
assert s.count(old) == 1, "桩③：没找到 role 过滤那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（role 不过滤）" "只收 user/assistant 两种 role"
reset_src

# ── 4. 桩④：导出套上 MAX_CHARS 裁剪（记录"看起来完整"其实被截了头）────────
"$PY" - "$ARC" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            for (m in s.messages) {
                // 导出**不做裁剪**：导出的目的就是"把库里的东西原样带走"。
                // 若在这里套 MAX_CHARS 保险丝，用户会拿到一份"看起来完整、
                // 实际被截掉开头"的记录，而无从判断是导出坏了还是会话本来就短。
                msgs.put(JSONObject().put("role", m.role).put("content", m.content))
            }"""
new = """            for (m in s.messages) {
                val content = if (m.content.length > MAX_CHARS) m.content.take(MAX_CHARS) else m.content
                msgs.put(JSONObject().put("role", m.role).put("content", content))
            }"""
assert s.count(old) == 1, "桩④：没找到 encode 的正文写入"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（导出套上字符数裁剪）" "导出不做任何字符数裁剪"
reset_src

# ── 5. 桩⑤：导出 MIME 退回 text/plain（.json 被当纯文本，零报错）──────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            val where = LogExport.saveText(this, name, json, mime = "application/json")"""
new = """            val where = LogExport.saveText(this, name, json)"""
assert s.count(old) == 1, "桩⑤：没找到导出落盘那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（导出 MIME 退回 text/plain）" "聊天记录导出显式声明 application/json"
reset_src

# ── 6. 桩⑥：会话文件布局在 UI 里自己拼（改命名规则后静默失效）─────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    private fun showSessionDialogDismiss() {"""
new = """    private fun chatFileOf(id: String) = java.io.File(filesDir, "s_$id.json")

    private fun showSessionDialogDismiss() {"""
assert s.count(old) == 1, "桩⑥：没找到 showSessionDialogDismiss"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（UI 里自己拼会话文件名）" "会话文件名的拼法只在 SessionStore 里"
reset_src

# ── 7. 桩⑦：导入复用选模型的请求码 42（选完文件什么都没发生）─────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        private const val REQ_IMPORT_CHAT = 43"""
new = """        private const val REQ_IMPORT_CHAT = 42"""
assert s.count(old) == 1, "桩⑦：没找到导入请求码"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（导入与选模型共用请求码）" "导入用**独立**请求码"
reset_src

# ── 8. 桩⑧：预算静默截断 / 不按预算收（用户以为文件里就这么多）──────────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (written >= quota) break"""
new = """            if (false) break"""
assert s.count(old) == 1, "桩⑧：没找到上限判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（按预算收的判据没了）" "按预算收"
reset_src

# ── 9. 桩⑨：格式名判据挪到读 sessions **之后**（顺序反了等于没拒绝）───────
"$PY" - "$ARC" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val fmt = root.optString("format", "")
        if (fmt != FORMAT) {"""
new = """        val fmt = root.optString("format", "")
        root.optJSONArray("sessions")
        if (fmt != FORMAT) {"""
assert s.count(old) == 1, "桩⑨：没找到格式名判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑨（格式名判据挪到读 sessions 之后）" "格式名判据在**读 sessions 之前**"
reset_src

# ── 10. 桩⑩：读文件回到主线程（大文件会 ANR）─────────────────────────────
# ⚠ 桩必须**按函数名定位**，不能靠大段文本 replace(...,1)：知识库导入
# （`importRagArchiveFile`）的读文件段与本段**逐字同形**，靠文本匹配会打到
# 那一份上去，于是"聊天导入回主线程"的桩假绿、而真正变红的是知识库那条判据
# —— 测试以为验的是聊天那份。这是本轮新增知识库导入时暴露的一处既有脆弱点。
"$PY" - "$ACT" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
sig = "private fun importArchiveFile(uri: Uri)"
i = s.index(sig)
j = s.index("\n    /**", i)          # 本函数体之后紧跟下一个成员的开头
seg = s[i:j]
old = "ioExecutor.execute {"
assert seg.count(old) >= 1, "桩⑩：聊天导入里没找到 ioExecutor.execute"
seg2 = seg.replace(old, "run {", 1)
open(p, 'w', encoding='utf-8').write(s[:i] + seg2 + s[j:])
EOF
must_red "桩⑩（读文件回到主线程）" "读文件 + 解析在后台线程"
reset_src

# ── 11. 桩⑪：导入路径去动现有会话（rename / clearMessages）───────────────
"$PY" - "$SS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        firstNew?.let { setCurrent(ctx, it) }"""
new = """        firstNew?.let { setCurrent(ctx, it) }
        sessions.firstOrNull()?.let { rename(ctx, it.id, "导入的") }"""
assert s.count(old) == 1, "桩⑪：没找到 setCurrent 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑪（导入路径去改现有会话）" "导入路径**不碰**已存在的会话"
reset_src

# ── 12. 桩⑫：模型导入那条分支被挤掉（新请求码吃掉了旧的）─────────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            promptImportMode(uri)
        }"""
new = """        if (requestCode == REQ_IMPORT_CHAT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            promptImportMode(uri)
        }"""
assert s.count(old) == 1, "桩⑫：没找到 REQ_PICK 分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑫（新请求码挤掉模型导入分支）" "模型导入那条分支**仍在**"
reset_src

# ── 13. 桩⑬：decode 不再带 skipped（脏数据静默丢失）──────────────────────
"$PY" - "$ARC" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                if (role != "user" && role != "assistant") { skipped++; continue }"""
new = """                if (role != "user" && role != "assistant") { continue }"""
assert s.count(old) == 1, "桩⑬：没找到 role 过滤那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑬（脏数据静默丢弃、不计数）" "被跳过的脏数据**计数**"
reset_src

# ── 14. 桩⑭：顶层 system 被丢但**不计数**（人设静默消失 —— 本轮修的那条）──
"$PY" - "$ARC" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            val sys = o.optString("system", "")
            if (sys.isNotEmpty()) systemDropped++"""
new = """            val sys = o.optString("system", "")"""
assert s.count(old) == 1, "桩⑭：没找到 system 判空计数那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑭（丢人设不计数）" "decode 读顶层 system 之后**真的去判空并计数**"
reset_src

# ── 15. 桩⑮：计数**进 skipped**（两者处置相反，混一起用户没法判断该不该在意）──
"$PY" - "$ARC" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (sys.isNotEmpty()) systemDropped++"""
new = """            if (sys.isNotEmpty()) { skipped++; systemDropped++ }"""
assert s.count(old) == 1, "桩⑮：没找到 system 计数那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑮（丢人设混进 skipped）" "人设丢失**不进** skipped"
reset_src

# ── 16. 桩⑯：算了但**没人报**（确认框根本不读它 —— 只算不报与没算等价）──
#
# ⚠ 这个桩**不能只把 `if (parsed.systemDropped > 0)` 改成 `if (false)`**：
#    守卫里那条判据的锚是"确认框这个**函数体**里出现了 parsed.systemDropped"，
#    改成 `if (false)` 之后那一串还在函数体里，判据照旧命中 → 桩假绿
#    （第一版就是这么写的，实测桩不变红）。所以这里整段替换函数体，
#    与"把这段提示删掉"这个真实改法同形。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
sig = "    private fun confirmImport(parsed: ChatArchive.Parsed) {"
i = s.index(sig)
# 大括号配平找函数体末尾
k = i; depth = 0
while k < len(s):
    if s[k] == "{": depth += 1
    elif s[k] == "}":
        depth -= 1
        if depth == 0:
            break
    k += 1
body = sig + "\n        AlertDialog.Builder(this).setMessage(\"导入\").show()\n    }"
assert "systemDropped" in s[i:k + 1], "stub16: 原函数体里本该有 systemDropped"
open(p, 'w', encoding='utf-8').write(s[:i] + body + s[k + 1:])
EOF
must_red "桩⑯（算了但没人报）" "⑨ 落盘前的确认框里读 systemDropped"
reset_src

# ── 16b. 桩⑯b：逐条说明退化成"只报个数"（用户点不出是哪一条会话）─────────
#
# 与桩⑯ 同理：判据锚的是 `systemDroppedDetail` 这个**函数体**里的 `s.title`，
# 只把筛选行改成空列表**不会**让判据变红（`s.title` 还在函数体里）——
# 那种改法是"逐条说明永远输出空"，用户看到的确实是"只报个数"，
# 但它不是判据能看见的形状。桩改成把标题那部分去掉，与"改成只报个数"同形。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '"　「${oneLine(s.title)}」：$head"'
assert s.count(old) == 1, "stub16b: no title line"
open(p, 'w', encoding='utf-8').write(s.replace(old, '"　$head"', 1))
EOF
must_red "桩⑯b（逐条说明退化成只报个数）" "⑨ 逐条说明按会话标题点名"
reset_src


# ── 17. 桩⑰：结果提示里不说（确认框关掉后无处对证）───────────────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                val sysDrop = if (parsed.systemDropped > 0)"
assert s.count(old) == 1, "stub17: no sysDrop head"
s = s.replace(old, '                val sysDrop = ""', 1)
old2 = "                    \"\\n\u26a0 ${parsed.systemDropped} 个会话的 system 人设未导入（本机只收对话正文）\" else \"\""
assert s.count(old2) == 1, "stub17: no sysDrop text"
s = s.replace(old2, "                ", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑰（结果提示里不说）" "导入**结果**那一句里也带上"
reset_src


# ── 19. 桩⑲：预览超长时静默截（用户以为"本来就这么长"）───────────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            val head = if (body.length > SYSTEM_DROPPED_PREVIEW)
                body.take(SYSTEM_DROPPED_PREVIEW) + "…" else body"""
new = """            val head = body.take(SYSTEM_DROPPED_PREVIEW)"""
assert s.count(old) == 1, "桩⑲：没找到预览截断那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑲（预览静默截）" "预览超长时**显式标出省略**"
reset_src

# ── 20. 桩⑳：systemDropped 被写进信封（内部状态泄漏给下一个版本）──────────
"$PY" - "$ARC" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                    .put("system", s.system)"""
new = """                    .put("system", s.system)
                    .put("systemDropped", s.systemDropped)"""
assert s.count(old) == 1, "桩⑳：没找到 encode 写 system 那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑳（内部字段写进信封）" "systemDropped **不进信封**"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 聊天记录导入/导出守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 聊天记录导入/导出守卫自测：PASS $ok / FAIL $bad ==="; exit 1
