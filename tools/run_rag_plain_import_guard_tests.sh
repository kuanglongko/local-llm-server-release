#!/bin/sh
# 自测 `tools/run_rag_plain_import_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是 grep/awk/python 的**存在性、结构性与次序性**断言，三个自欺模式：
#
#   1. **恒真**：pattern 写成到处都有的文本。本族最容易恒真的有两条 ——
#      "decodePlainText 不碰 Uri / Context"（解释"为什么不能按扩展名判"的注释里
#      必然出现这些词）与"判据在按行收之前"（把 `startsWith(` 换成到处都有的
#      `if (` 就恒真了）。前者靠剥注释、后者靠**真打桩验红**才不是自欺。
#   2. **锚错位置**：钉"文件里出现过 setRagDocText"而不是"**回填这条路径里**
#      用的是它" —— 前者在别处（保存正文）也成立。所以判据一律锚函数体内。
#   3. **恒假后被静音**：pattern 与源码差一个字符 → 守卫永远 FAIL，
#      很快就会有人把 `exit 1` 改成 `|| true`，那时它彻底没用了。
#
# 桩全部来自"真正会写错的写法"（这一族真正的失败方向）：
#   · 纯文本导入**直接写库**（文档里该并行的短行在用户没看清时落盘，且绕开归属）
#   · 格式判据挪到按行收之后（等于没拒绝，JSON 数组被收成一行行 JSON 文本）
#   · 二进制 / 非 UTF-8 不检查（乱码建库成功、检索能命中，没有任何一步报错）
#   · 回填不置脏（切库时长文静默消失）
#   · 请求码与「导入库」共用（选完文本文件被当库文件解析）
#   · 按钮名改回去（"导入正文"重新出现）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_plain_import_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_plain_import_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
RA=$SRC/RagArchive.kt
ACT=$SRC/EngineActivity.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$RA" "$TMP/ra.bak"; cp "$ACT" "$TMP/act.bak"
restore() { cp "$TMP/ra.bak" "$RA"; cp "$TMP/act.bak" "$ACT"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ra.bak" "$RA"; cp "$TMP/act.bak" "$ACT"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

# ⚠ 桩的 py 段里 `assert` 失败时脚本会在 `set -e` 下直接退出；
#   退出走 trap restore，所以**源码一定被还原** —— 但"没找到锚点"这件事必须
#   报成 FAIL 而不是静默中止（静默中止会让它后面所有桩都不再跑，
#   而输出看着像"跑了一半就完了"）。所以桩的 py 段一律包在子 shell 里取返回值。
stub() { # stub <py脚本文件> [参数...]
    if "$PY" "$@"; then return 0; else ck "打桩失败：$*" 0; return 1; fi
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

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：形状判据挪到按行收**之后**（等于没拒绝）───────────────────────
# 症状：一份 JSON 数组被逐行收成 N 条"看着像 JSON 的文本条目"，检索照样能用，
# 而用户以为导进来的是一份库文件。次序判据是唯一能钉住它的东西。
stub_py() { "$PY" - "$@" || echo "⚠ 打桩脚本失败（锚点漂了？）—— 该桩将被跳过" >&2; }
stub_py "$RA" <<'EOF'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (t.startsWith("[")) {
            return fail("这是 JSON 数组（一行一条的库文件），请用「导入库」；" +
                "这条按钮读的是 .txt / .md 那种一行一条的纯文本")
        }
"""
assert s.count(old) == 1, "桩①：没找到 JSON 数组判据"
s = s.replace(old, "", 1)
# ⚠ 这一行的字面量本轮跟着源码改了：切段之后 `skipped` 这个计数在纯文本那条路上
#   不再有来源（超长是**切**，不是**截**），于是失败文案里只剩条数上限那一句。
old2 = """        if (out.isEmpty()) return fail("文件里没有可用的条目（没有非空行）")"""
new2 = """        if (t.startsWith("[")) { return fail("JSON 数组请走导入库") }
        if (out.isEmpty()) return fail("文件里没有可用的条目（没有非空行）")"""
assert s.count(old2) == 1, "桩①：没找到收尾那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old2, new2, 1))
EOF
must_red "桩①（形状判据挪到按行收之后）" "形状判据（JSON 数组）写在按行收"
reset_src

# ── 2. 桩②：不检查二进制（NUL 直接放行）─────────────────────────────────
stub_py "$RA" <<'EOF'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (text.contains('\\u0000')) {
            return fail("这看起来不是文本文件（内容里有 NUL 字节）—— " +
                "导入库支持 .json / .jsonl，导入 .txt / .md 走的是这个按钮")
        }
"""
assert s.count(old) == 1, "桩②：没找到 NUL 判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩②（不挡二进制 -> 乱码收成条目）" "二进制"
reset_src

# ── 3. 桩③：非 UTF-8 不检查（GBK 中文笔记变成一库问号）───────────────────
stub_py "$RA" <<'EOF'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (text.contains('\\uFFFD')) {
            throw NotText("这份文件不是 UTF-8 编码（中文变成了一串乱码字符）—— " +
                "用记事本另存为 UTF-8 后再导入")
        }
"""
assert s.count(old) == 1, "桩③：没找到 U+FFFD 判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩③（非 UTF-8 不拒 -> 一库乱码）" "非 UTF-8 的提示"
reset_src

# ── 4. 桩④：解析完**直接落盘**（绕过确认框）─────────────────────────────
# ⚠⚠ 这一桩本轮**换过目标**。上一版这条路是"读进正文框"，所以桩是"直接写库"；
#   这一版它**本来就该写库**，危险只剩一处：**绕过确认框**。
#   导入是追加合并、不可撤销 —— 确认框是用户唯一一次能反悔的机会，
#   而且它报的条数正是"整篇一段会变成 1 条"唯一能被看见的地方。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                confirmRagImport(parsed, fromTextFile = true)"
new = "                doImportRagEntries(RagIndexStore.sanitizeName(parsed.library) ?: \"导入的库\", parsed)"
assert s.count(old) == 1, "桩④：没找到确认框调用那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（解析完直接落盘、绕开确认框）" "解析完**先进确认框**"
reset_src

# ── 5. 桩⑤：确认框标题**不分流**（.txt 那一版还写着"只是读进框里"）──────
# 症状：`.txt` 现在会写盘，而标题若还用「导入库（追加合并）」那一句，
# 用户读不出"这一步会改磁盘上的库" —— 而导入不可撤销。
# 这是本仓库最忌讳的"提示与动作不符"：他以为可逆的那一步其实不可逆，就没细看条数。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        val title = if (fromTextFile) "导入文档（追加合并，会写盘）"'
new = '        val title = if (false) "导入文档（追加合并，会写盘）"'
assert s.count(old) == 1, "桩⑤：没找到标题分流那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑤（.txt 导入的确认框不说会写盘）" "标题按来源分流"
reset_src

# ── 6. 桩⑥：请求码与「导入库」共用（选完文本文件被当库文件解析）──────────
stub_py "$ACT" <<'EOF'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        private const val REQ_IMPORT_RAG_TEXT = 45"
new = "        private const val REQ_IMPORT_RAG_TEXT = 44"
assert s.count(old) == 1, "桩⑥：没找到请求码定义"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（两个知识库入口共用一个请求码）" "独立**请求码"
reset_src

# ── 7. 桩⑦：按钮名改回去（"导入正文"重新出现）───────────────────────────
stub_py "$ACT" <<'EOF'
import re
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'ragImportBtn = btn("导入库")'
new = 'ragImportBtn = btn("导入正文")'
assert s.count(old) == 1, "桩⑦：没找到按钮文案"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（按钮名退回"导入正文"）" "这个名字在位"
reset_src

# ── 8. 桩⑧：这条路自己又写一遍"落盘之后做什么"（两处各写一份）──────────
# 症状：切库 / 回读正文 / 刷列表各写一份，改一处另一处不会红 ——
# 而漂移的形态是"库切过去了、框里还是上一个库的正文"（两个信号打架，
# 用户看到的那个说"成了"）。这一段只有 [doImportRagEntries] 一处该写。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                confirmRagImport(parsed, fromTextFile = true)"
new = "                RagInject.setCurrentKb(this, parsed.library)\n" + old
assert s.count(old) == 1, "桩⑧：没找到确认框调用那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（这条路自己写落盘后的动作）" "自己写落盘后的动作"
reset_src

# ── 9. 桩⑨：把"文件名当库名"这个旧行为**退回去**（ISSUE #259 的用户拍板）──
# 症状：这条路又和「导入库」分家了 —— 想往现有库里追加一篇文档只能先把文件改名，
# 而更坏的形态是静默建出第二个库（他以为在往 A 里追加，磁盘上多了个"文件名"库，
# A 里一条没多，而屏幕上两句提示都说"成了"）。
stub_py "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'return Parsed(out, "", 0, null, splitNoPunct = nosplit)'
new = 'return Parsed(out, name.substringBeforeLast(\'.\').take(64), 0, null, splitNoPunct = nosplit)'
assert s.count(old) == 1, "桩⑨：没找到纯文本解码的返回"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑨（目标库退回「取文件名」）" "不从文件名派生"
reset_src

# ── 10. 桩⑩：确认框又凭空兜底一个目标库名（"导入的库"）──────────────────
# 症状：用户明明选了库，App 却给他编了第三个名字 —— 而"已有同名库 = 追加"
# 这条语义会让他**静默建出一个新库**，屏幕上两句提示（"已导入"、"库名 导入的库"）
# 都在说"成了"，而他以为在往选中的那个库里追加。
stub_py "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val suggested = fromEnvelope ?: fromCurrent ?: ""'
new = 'val suggested = fromEnvelope ?: fromCurrent ?: "导入的库"'
assert s.count(old) == 1, "桩⑩：没找到目标库预填那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑩（目标库又凭空兜底一个名字）" "凭空兜底一个"
reset_src

# ── 11. 桩⑪：解码里混进 Context（纯逻辑判据失去意义）─────────────────────
stub_py "$RA" <<'EOF'
import re
import sys
import re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
m = re.search(r"(?m)^(    fun decodePlainText\([^)]*\)): Parsed \{", s)
assert m, "桩⑪：没找到签名"
# ⚠ 不能拿 group(1) 去拼：那一组里**不含** `): Parsed {`，拼出来是
#   `...String), ctx: ...` —— 一个括号数都不对的签名（本轮实测踩到，
#   而它报出来的是"纯逻辑判据恒绿"，看着像判据写错了）。
old = m.group(0)
new = old.replace("): Parsed {", ", ctx: Context? = null): Parsed {", 1)
assert new != old, "桩⑪：替换没生效"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑪（解码被塞进 Context -> 宿主不可单测）" "解码是纯逻辑"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 纯文本导入守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 纯文本导入守卫自测：PASS $ok / FAIL $bad ==="; exit 1
