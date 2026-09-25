#!/bin/sh
# 自测 `tools/run_release_hygiene_guard.sh`：把它对着几份**打桩文件**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据几乎全是"结构关系"（谁在谁之前、谁调用谁、谁的取值来自谁），
# 这类判据**最容易恒真**：
#   · 正则写歪 → 抽不出函数体 → 变量落空 → 判据恒绿；
#   · 更隐蔽的一种：**判据读的是注释**（模块 I 的 PR-1、F-2 都踩过）——
#     注释里写一句旧写法，守卫报假故障；反过来注释里写一句新写法，守卫恒绿。
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真实文件、守卫必须变红并指出是哪一条，再还原。
#
# 桩落在 `tools/release_hygiene/stubs/*.py`（不是内联字符串）：
# 内联字符串要么被 heredoc 的引号层数困住，要么被 `sh` 的双引号展开吃掉反斜杠，
# 而桩里最需要的恰恰是**原样的源码字面量**；落在文件里还能把
# "这一根取的是哪一版旧写法"写进自己的 docstring（可读、可 diff）。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_release_hygiene_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_release_hygiene_guard.sh
REL=scripts/release.py
RULES=scripts/release-rules.json
README=README.md
STUBS=tools/release_hygiene/stubs

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$REL" "$TMP/rel.bak"
cp "$RULES" "$TMP/rules.bak"
cp "$README" "$TMP/readme.bak"
cp "$GUARD" "$TMP/guard.bak"
restore() {
    cp "$TMP/rel.bak" "$REL"
    cp "$TMP/rules.bak" "$RULES"
    cp "$TMP/readme.bak" "$README"
    cp "$TMP/guard.bak" "$GUARD"
    rm -rf "$TMP"
}
trap restore EXIT

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}
reset_src() {
    cp "$TMP/rel.bak" "$REL"; cp "$TMP/rules.bak" "$RULES"
    cp "$TMP/readme.bak" "$README"; cp "$TMP/guard.bak" "$GUARD"
    return 0
}

# 打桩器：stub <目标文件> <桩脚本>
# 桩脚本里 `s` 是目标文件的源码、写回 `s_out`。
stub() {
    "$PY" - "$1" "$2" <<'PY'
import sys
p, py_path = sys.argv[1], sys.argv[2]
g = {'s': open(p, encoding='utf-8').read(), 's_out': None, '__file__': py_path}
exec(compile(open(py_path, encoding='utf-8').read(), py_path, 'exec'), g)
if g['s_out'] is None:
    raise SystemExit('桩脚本 %s 没有给 s_out 赋值' % py_path)
open(p, 'w', encoding='utf-8').write(g['s_out'])
PY
}

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    # ⚠ `grep -F -e "$key"`：判据文案里含 `--xxx` 与 `**`，不加 `-e` 会把
    #   以 `--` 开头的那条当成 grep 自己的选项（第一版实测如此）。
    if grep -qF -e "$key" "$TMP/guard.out"; then
        line=$(grep -F -e "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}
must_green() {
    if run_guard; then ck "$1：守卫全绿" 1
    else ck "$1：守卫全绿（输出见下）" 0; cat "$TMP/guard.out"; fi
}

# ── 0) 基线：真源码上必须全绿 ──────────────────────────────────────────────
must_green "原始源码"

# ── 1~12) 逐根桩 ──────────────────────────────────────────────────────────
reset_src; stub "$REL"    "$STUBS/s01_scan_hollowed.py"
must_red "桩①（共享扫描器被掏空）" "共享扫描器里真的用了 HYGIENE_EXT"

reset_src; stub "$REL"    "$STUBS/s02_selfcheck_own_scan.py"
must_red "桩②（自检不调共享扫描器，自己另写一份近似）" "发布器自检**调用**了共享扫描器"

reset_src; stub "$REL"    "$STUBS/s03_main_inline_scan.py"
must_red "桩③（主流程卫生门禁回到内联循环）" "主流程的卫生门禁也走同一个扫描器"

reset_src; stub "$REL"    "$STUBS/s04_selfcheck_not_blocking.py"
must_red "桩④（自检只打印、不进 blocked）" "发布器自检的结论真的进了 blocked"

reset_src; stub "$REL"    "$STUBS/s05_moved_after_blocked.py"
must_red "桩⑤（自检整块挪到 blocked 之后）" "自检调用**排在**blocked 赋值之前"

reset_src; stub "$REL"    "$STUBS/s06_strict_ignored.py"
must_red "桩⑥（红线参数收下却不用）" "那条红线**真的**参与 blocked_selfcheck 计算"

reset_src; stub "$RULES"  "$STUBS/s07_readme_rules_removed.py"
must_red "桩⑦（README 版本号清洗规则被删 = J-3 的原始状态）" "README 按规则清洗后过得了卫生门禁"

reset_src; stub "$RULES"  "$STUBS/s07b_readme_rules_all_removed.py"
must_red "桩⑦b（README 规则全删）" "规则集里确实有 README 的条目"

reset_src; stub "$README" "$STUBS/s08_readme_no_cnb.py"
must_red "桩⑧（README 不再提到 .cnb.yml = J-4 的原始状态）" "README 提到了 .cnb.yml"

reset_src; stub "$README" "$STUBS/s09_cnb_after_gh.py"
must_red "桩⑨（README 先点名 build.yml、.cnb.yml 排到后面）" "README 先讲 .cnb.yml"

reset_src; stub "$README" "$STUBS/s10_no_gh_not_tools.py"
must_red "桩⑩（README 不再点明 build.yml 不跑 tools/run_*）" "README 点明 build.yml 那条**不跑**"

reset_src; stub "$REL"    "$STUBS/s11_arg_not_registered.py"
must_red "桩⑪（--skip-hygiene-selfcheck 没注册）" "--skip-hygiene-selfcheck 真的在 argparse 里注册了"

reset_src; stub "$GUARD"  "$STUBS/s12_truncated_regex.py"
must_red "桩⑫（守卫退回截断式正则取 blocked → 正确源码被报假故障）" "发布器自检的结论真的进了 blocked"

# ── 还原后必须回到全绿（否则"变红"可能是因为桩没还原掉）──────────────────
reset_src
echo ""
echo "还原后："
must_green "还原"

echo ""
if [ "$bad" = "0" ]; then echo "=== 发布器自洽守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 发布器自洽守卫自测：PASS $ok / FAIL $bad ==="; exit 1
