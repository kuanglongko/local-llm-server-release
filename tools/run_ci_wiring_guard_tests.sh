#!/bin/sh
# 自测 `tools/run_ci_wiring_guard.sh`：把它对着几份**打桩文件**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据比一般守卫**更容易恒真**，因为它是在算集合的差：
#   · 正则写歪 → `called` 扫成空集 → 差集变成"磁盘上全部脚本"→ 应该炸，
#     但如果正则写**成恒不匹配**且磁盘扫描也一起失效，三条 `[ -z ]` 会全绿；
#   · 更隐蔽的一种：**扫描目录写错**（比如 cwd 不在仓库根），`glob` 返回空，
#     `on_disk` 为空 → `on_disk - called` 为空 → "全都接了" 全绿。
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真实文件、守卫必须变红并指出是哪一条，再还原。
# 与 run_cors_guard_tests.sh / run_web_chat_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_ci_wiring_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_ci_wiring_guard.sh
THINK=tools/run_thinking_tests.sh
CI=.cnb.yml
# 调用清单现在有两个来源：`.cnb.yml` 与它引用的 stage 脚本
#（`scripts/ci-*.sh`）。桩①/⑤ 要删的那几行在**后者**里 —— 它们是从
# `.cnb.yml` 内联正文搬过去的（搬的原因：170 KB 内联脚本会让 CNB runner 的
# fork/exec 报 `argument list too long`，脚本一行都不跑）。
STAGE=scripts/ci-apk-stage.sh
README=README.md
STATUS=HTP-STATUS.md
SUMMARY=docs/REVIEW-110-SUMMARY.md

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$CI" "$TMP/ci.bak"; cp "$README" "$TMP/readme.bak"; cp "$STATUS" "$TMP/status.bak"
# 桩⑨ 直接改审查收口小结（模拟"它掉出扫描面"），必须还原得掉。
cp "$SUMMARY" "$TMP/summary.bak"
cp "$THINK" "$TMP/think.bak"
cp "$STAGE" "$TMP/stage.bak"
# 守卫自身也要备份：桩④直接改守卫源码（模拟扫描失效），必须还原得掉。
cp "$GUARD" "$TMP/guard.bak"
# 桩⑦ 直接改 run_schema_sampler_guard.sh（往里注入一处 shell 分叉），必须还原得掉。
cp tools/run_schema_sampler_guard.sh "$TMP/sg.bak"
cp tools/suite-counts.json "$TMP/counts.bak"
restore() {
    cp "$TMP/ci.bak" "$CI"; cp "$TMP/readme.bak" "$README"; cp "$TMP/status.bak" "$STATUS"
    cp "$TMP/guard.bak" "$GUARD"
    cp "$TMP/think.bak" "$THINK"
    cp "$TMP/stage.bak" "$STAGE"
    cp "$TMP/sg.bak" tools/run_schema_sampler_guard.sh
    [ -f "$TMP/summary.bak" ] && cp "$TMP/summary.bak" "$SUMMARY"
    [ -f "$TMP/counts.bak" ] && cp "$TMP/counts.bak" tools/suite-counts.json
    [ -f "$TMP/corslive.bak" ] && cp "$TMP/corslive.bak" tools/run_cors_live_tests.sh
    rm -f tools/run_zz_syntax_probe.sh
    rm -rf "$TMP"
}
# 退出即还原：EXIT / INT / TERM / HUP 都走同一个还原 ——
# 只 trap EXIT 时，CI 的 kill（默认 TERM）不会触发 EXIT trap，
# 打桩源码就会留在工作区（正是 #126 那轮脏工作区的机制）。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}

# 打桩用的内联 python：**失败就整份自测当场中止**，不许继续。
#
# ⚠ 为什么必须这样（本文件原来不这样做，代价是真实的）：
#   打桩段里带着 `assert <锚点还在>`。它一失败，这一根桩就没打成 ——
#   而此时若继续往下走，会发生两种都不该发生的结局：
#     · 守卫**照旧全绿** → `must_red` 报"守卫变红：FAIL"，看起来像被测对象坏了；
#     · 更坏的一种：`set -e` 在另一个上下文里把脚本悄悄中止，而**前一根桩
#       留下的污染还留在真源码上**（本文件桩⑪b 真出过这件事：清单的
#       `suite_total` 被改成 77 后没人还原，后一根桩在脏基线上跑）。
#   所以打桩统一走 `py_stub`，锚点找不到 = 桩过期 = 当场说清楚，方便定位。
py_stub() { # py_stub <脚本路径> [额外参数...] <<'PY' ... PY
    script=$1
    shift
    set +e
    "$PY" - "$script" "$@" > "$TMP/stub.err" 2>&1
    rc=$?
    set -e
    if [ "$rc" != "0" ]; then
        echo "FAIL  打桩失败（桩的锚点已过期，整份自测中止）：$script"
        cat "$TMP/stub.err"
        restore
        exit 1
    fi
}
reset_src() {
    cp "$TMP/ci.bak" "$CI"; cp "$TMP/readme.bak" "$README"; cp "$TMP/status.bak" "$STATUS"
    cp "$TMP/think.bak" "$THINK"
    cp "$TMP/stage.bak" "$STAGE"
    cp "$TMP/sg.bak" tools/run_schema_sampler_guard.sh
    # 桩⑨ 会改审查收口小结，同上。
    [ -f "$TMP/summary.bak" ] && cp "$TMP/summary.bak" "$SUMMARY"
    # 桩⑧b 会改 tools/suite-counts.json，必须在每次开桩前还原，
    # 否则后一根桩会带着上一根的污染一起红，指错对象。
    [ -f "$TMP/counts.bak" ] && cp "$TMP/counts.bak" tools/suite-counts.json
    return 0
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

must_green() {
    if run_guard; then ck "$1：守卫全绿" 1; else ck "$1：守卫全绿（输出见下）" 0; cat "$TMP/guard.out"; fi
}

# ── 0) 基线：真源码上必须全绿 ──────────────────────────────────────────────
must_green "原始源码"

# ── 1) 桩①：把 run_cors_live_tests.sh 的调用行从 CI 里删掉 ─────────────────
# 这正是本轮修的那个形态（脚本在磁盘上、文档说它通过、CI 里没有）。
reset_src
python3 - "$STAGE" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
before=s
s=s.replace('REQUIRED=0 sh tools/run_cors_live_tests.sh\n','')
assert s!=before, '桩①：stage 脚本里没找到那一行'
open(p,'w',encoding='utf-8').write(s)
PY
must_red "桩①（CI 调用清单里删掉 cors_live 的调用）" "tools/run_cors_live_tests.sh"

# ── 2) 桩②：新增一个磁盘上的脚本但故意不接 CI ──────────────────────────────
# 取一个**两边都没出现过**的新名字，避免撞上已有的名字而误判"本来就没接"。
reset_src
printf '#!/bin/sh\nexit 0\n' > tools/run_zz_ghost_probe.sh
chmod +x tools/run_zz_ghost_probe.sh
must_red "桩②（磁盘上多一个没接 CI 的脚本）" "tools/run_zz_ghost_probe.sh"
rm -f tools/run_zz_ghost_probe.sh

# ── 3) 桩③：文档引用了不存在的脚本（悬空引用）──────────────────────────────
# 形态：有人重命名/删掉了脚本，但 README 里的"证据"没改 —— 文档指向空气。
reset_src
python3 - "$README" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
s=s.replace("tools/run_web_chat_tests.sh","tools/run_zz_not_on_disk.sh",1)
open(p,'w',encoding='utf-8').write(s)
PY
must_red "桩③（README 引用不存在的脚本）" "tools/run_zz_not_on_disk.sh"

# ── 4) 桩④：防"守卫自己恒真" ───────────────────────────────────────────────
# 把仓库根的扫描对象整体搬走（这里用改 `on_disk` 的扫描范围模拟：把 cwd 换到
# 一个没有 tools/run_* 的空目录），守卫必须**变红**而不是"集合为空 → 全绿"。
reset_src
python3 - "$GUARD" <<'PY'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old="on_disk = set(p[2:] if p.startswith('./') else p for p in glob.glob('tools/run_*.sh') + glob.glob('tools/run_*.py'))"
new="on_disk = set()  # 桩④：模拟扫描失效（集合为空）"
assert old in s, 'anchor not found'
open(p,'w',encoding='utf-8').write(s.replace(old,new))
PY
if run_guard; then ck "桩④（扫描集合为空时守卫必须变红，不许因空集恒绿）" 0
else ck "桩④（扫描集合为空时守卫必须变红，不许因空集恒绿）" 1; fi
# 桩④ 顺带断言：失败信息里必须能看出"没扫到脚本"（也就是那条防恒真的判据真的在起作用）
if grep -qF "磁盘上确实扫到了离线测试脚本" "$TMP/guard.out"; then
    ck "桩④：指到了「磁盘上确实扫到了离线测试脚本」" 1
else
    ck "桩④：指到了「磁盘上确实扫到了离线测试脚本」（该判据没起作用）" 0
fi
cp "$TMP/guard.bak" "$GUARD"

# ── 4b) 桩④b：**调用清单的第二个来源没了** —— 必须点名，不许静默恒绿 ──────────
# 这是本轮真出过事的那一格：stage 正文从 `.cnb.yml` 搬进 `scripts/ci-*.sh`
# 之后，若守卫仍只扫 `.cnb.yml` 一个文件，`called` 会变空 —— 判据①③ 报
# "一个都没接"（红得莫名其妙，指不到真正的原因），而判据④⑥⑦⑨ 因为集合为空
# **静默恒绿**（"REQUIRED 都读了""语法都过了"，其实一条都没查）。
# 桩做法：把 stage 文件改名（模拟"被引用的脚本没扫到"），守卫必须变红，
# 且失败行里点得出 `scripts/ci-*.sh` 这个来源。
reset_src
mv "$STAGE" "$STAGE.hidden"
if run_guard; then ck "桩④b（stage 脚本扫不到时守卫必须变红）" 0
else ck "桩④b（stage 脚本扫不到时守卫必须变红）" 1; fi
if grep -F "两个来源都非空" "$TMP/guard.out" | grep -q 'FAIL'; then
    ck "桩④b：指到了「CI 调用清单的两个来源都非空」" 1
else
    ck "桩④b：指到了「CI 调用清单的两个来源都非空」（该判据没起作用）" 0
fi
# 同时断言"防恒真"那条**不**得红：它防的是"磁盘上一份脚本都没有"，
# 与"某个来源没扫到"是两件事，混在一起就分不清是哪一种失效。
if grep -F "磁盘上确实扫到了离线测试脚本" "$TMP/guard.out" | grep -q 'FAIL'; then
    ck "桩④b：防恒真那条仍绿（两种失效分得开）" 0
else
    ck "桩④b：防恒真那条仍绿（两种失效分得开）" 1
fi
mv "$STAGE.hidden" "$STAGE"
reset_src

# ── 5) 桩⑤：`REQUIRED=0` 写在 CI 里、脚本却不读它 ───────────────────────────
# 形态来自 2026-09-21 的真实构建：repo1.maven.org 限流（429）→ json.jar 拉不到
# → `REQUIRED=0 sh tools/run_thinking_tests.sh` 硬 exit 2 → 整轮 APK 构建停在
# **下载一个 78KB 的 jar** 上。`.cnb.yml` 那行 `REQUIRED=0` 当时是幻觉。
#
# 桩关键点：**注释里的 REQUIRED 字样必须留着**，只把"取值"改掉。
# 只判"文件里出现过 REQUIRED"的存在性断言会被注释骗过 —— 这正是本项目
# 在鉴权守卫桩②栽过的同一形态（pattern 写成到处都有的文本 => 恒真）。
reset_src
py_stub "$THINK" <<'EOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
old='if [ "${REQUIRED:-1}" = "0" ]; then'
new='if [ "x" = "0" ]; then   # 桩⑤：不再求值 REQUIRED，注释里的字样保留'
assert s.count(old)==1, '桩⑤：没找到 REQUIRED 取值点'
open(p,'w',encoding='utf-8').write(s.replace(old,new,1))
EOF
must_red "桩⑤（CI 写 REQUIRED=0、脚本不求值它）" "tools/run_thinking_tests.sh"
reset_src

# 对偶：把 CI 里的 REQUIRED=0 前缀删掉后，判据⑤**不得**再管这个脚本 ——
# 说明判据是按"CI 实际调用形态"取集合，而不是把仓库里所有脚本都要求一遍
#（那会把一堆纯静态守卫也套进这个契约，它们是另一回事）。
reset_src
py_stub "$STAGE" <<'EOF'
import sys
p=sys.argv[1]; s=open(p,encoding='utf-8').read()
assert 'REQUIRED=0 sh tools/run_thinking_tests.sh' in s
s=s.replace('REQUIRED=0 sh tools/run_thinking_tests.sh','sh tools/run_thinking_tests.sh')
open(p,'w',encoding='utf-8').write(s)
EOF
if run_guard; then ck "桩⑤b（CI 去掉 REQUIRED=0 前缀后判据⑤不再管它 -> 全绿）" 1
else ck "桩⑤b（CI 去掉 REQUIRED=0 前缀后判据⑤不再管它 -> 全绿）" 0; cat "$TMP/guard.out"; fi
reset_src

# ── 6) 桩⑥：CI 调用的脚本**本体**语法坏掉 —— 判据⑥（`sh -n`）必须变红 ────────
# 形态："CI 里接了、人也以为在跑"，但脚本本体有语法错 —— **一条断言都跑不到**。
# 与桩①③（接了没跑）是同一族，只是断在更前面一环：判据①~⑤ 算的都是
# "接没接 / 宣称没宣称 / REQUIRED 读没读"，**没有一条看脚本本体**。
#
# 桩做法（关键）：坏的是**一个被 CI 调用的目标脚本**，不是守卫本体 ——
# 守卫本体若坏了，连断言都打不出来，自测就分不清"判据⑥ 抓到了"与
# "守卫整个没跑"。所以这里挑 `run_cors_live_tests.sh`（纯静态、被 CI 调用），
# 只往它末尾追加一处**未闭合的 `if`**：集合没变、判据①~⑤ 也都不受影响，
# 唯一该变红的就是判据⑥。
reset_src
cp tools/run_cors_live_tests.sh "$TMP/corslive.bak"
printf 'if true; then\n' >> tools/run_cors_live_tests.sh
must_red "桩⑥（CI 调用的脚本本体语法坏掉）" "都过 sh -n"
cp "$TMP/corslive.bak" tools/run_cors_live_tests.sh
reset_src

# ── 6b) 对偶：CI 里**没调用**的脚本本体坏了，判据⑥ 不得管它 ──────────────────
# 说明判据⑥ 是按"CI 实际调用的集合"取的，不是把仓库里所有 `.sh` 都要求一遍 ——
# 否则它会与"接没接"脱钩，变成一条到处都会红的噪音判据。
# 桩脚本**故意不接 CI**：判据① 会红，但判据⑥ 那一行必须仍然 PASS。
reset_src
printf '#!/bin/sh\nif true; then\n' > tools/run_zz_syntax_probe.sh
chmod +x tools/run_zz_syntax_probe.sh
run_guard || true
if grep -F "都过 sh -n" "$TMP/guard.out" | grep -q 'FAIL'; then
    ck "桩⑥b：判据⑥ 不把「没接 CI」的脚本算进来" 0
else
    ck "桩⑥b：判据⑥ 不把「没接 CI」的脚本算进来" 1
fi
rm -f tools/run_zz_syntax_probe.sh
reset_src

# ── 7) 桩⑦：CI 调用的静态守卫**换 shell 结论就反过来** —— 判据⑦ 必须变红 ──────
# 形态来自 2026-09-21 的真实撞见（0.9.101 的 `run_schema_sampler_guard.sh`）：
#     VAR=$(cmd1 && cmd2 <<HEREDOC && cmd3 || cmd4)
# dash 能解析、**bash 报语法错**，而 `sh -n` / `bash -n` **两份都过** ——
# 判据⑥（`sh -n`）因此静默放过，而末尾四条断言在 bash 下**一条都不打印**，
# 守卫只报 `PASS 117`（长得不像失败）。
#
# 桩做法：往一个**被 CI 调用、且在白名单里**的静态守卫里注入**同一形态**的分叉
#（就是把判据⑦ 实测过的那处 pattern 再写回去）。集合没变、判据①~⑥ 都不受影响，
# 唯一该变红的就是判据⑦。
# ⚠ 不能拿守卫本体当桩：本体坏了连断言都打不出来（桩⑥ 踩过这个坑）。
reset_src
py_stub tools/run_schema_sampler_guard.sh <<'EOF'
import io, sys
p = sys.argv[1]; s = io.open(p, encoding='utf-8').read()
old = """ESC_OK=no
if grep -q 'fun String.escapeProbe' "$PURE"; then
  if python3 - "$PURE" <<'PYEOF'"""
new = """ESC_OK=$(grep -q 'fun String.escapeProbe' "$PURE" && \
  python3 - "$PURE" <<'PYEOF'"""
assert s.count(old) == 1, '桩⑦：没找到壳无关写法'
s = s.replace(old, new, 1)
# 收尾也要改回 `$( )` 形态（`then` / `fi` 去掉）
old2 = """PYEOF
  then ESC_OK=yes; fi
fi"""
new2 = """PYEOF
&& echo yes || echo no)"""
assert s.count(old2) == 1, '桩⑦：没找到壳无关收尾'
s = s.replace(old2, new2, 1)
io.open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑦（静态守卫里注入 sh/bash 分叉）" "在 sh / bash 下输出逐行相同"
reset_src

# 对偶（桩⑦b）：把分叉**修回去**，判据⑦ 必须恢复绿 —— 说明它抓的是
# "两把尺子量出不同数"，不是"脚本里出现过 heredoc"这种存在性。
reset_src
if run_guard; then ck "桩⑦b（分叉修回去后判据⑦恢复绿）" 1
else ck "桩⑦b（分叉修回去后判据⑦恢复绿）" 0; cat "$TMP/guard.out"; fi

# ── 7c) 判据⑦ 的**独立价值**：给出一个判据⑥ 抓不到、判据⑦ 抓得到的桩 ─────────
# 桩⑦ 那个形态 `sh -n` 也拒（dash 会报 `&& unexpected`），所以它俩同时红，
# 证明不了判据⑦ "不是判据⑥ 的复读"。这里换一个**两份 `-n` 都过、只有输出不同**
# 的形态：脚本按解析器身份走不同分支（`$BASH_VERSION`）。这**不是**要支持这种
# 写法，恰恰相反 —— 它正是"同一份只脚本换把尺子量出不同数"的最小重现，
# 而 `sh -n` / `bash -n` 对它**一个字都报不出来**。
reset_src
cp tools/run_schema_sampler_guard.sh "$TMP/sg7c.bak"
cat > tools/run_schema_sampler_guard.sh <<'EOS'
#!/bin/sh
# 桩⑦c：sh -n 与 bash -n 都过，但两份输出不同（专为判据⑦ 而造）
if [ "${BASH_VERSION:-}" != "" ]; then
  echo "PASS  甲"
else
  echo "PASS  甲"
  echo "PASS  乙"
fi
echo ""
echo "=== 结构化输出守卫：PASS 差异 / FAIL 0 ==="
EOS
sh -n tools/run_schema_sampler_guard.sh || true
bash -n tools/run_schema_sampler_guard.sh || true
if run_guard; then ck "桩⑦c（-n 两份都过、输出不同：判据⑦ 必须变红）" 0
else ck "桩⑦c（-n 两份都过、输出不同：判据⑦ 必须变红）" 1; fi
if grep -F "在 sh / bash 下输出逐行相同" "$TMP/guard.out" | grep -q 'FAIL'; then
    ck "桩⑦c：指到了「在 sh / bash 下输出逐行相同」" 1
else
    ck "桩⑦c：指到了「在 sh / bash 下输出逐行相同」" 0
fi
# 同时断言判据⑥ 这一行**不**得红 —— 证明确实是判据⑦ 独立抓的
if grep -F "都过 sh -n" "$TMP/guard.out" | grep -q 'FAIL'; then
    ck "桩⑦c：判据⑥ 仍然绿（证明这一格是判据⑦ 独立补的）" 0
else
    ck "桩⑦c：判据⑥ 仍然绿（证明这一格是判据⑦ 独立补的）" 1
fi
cp "$TMP/sg7c.bak" tools/run_schema_sampler_guard.sh
reset_src


# ── 8) 桩⑧：文档里写的条数被改坏（判据⑤ 必须变红）────────────────────────
# 这正是判据⑤ 存在的理由：判据①③ 只看**脚本名字**，名字在对、也真的在 CI 里跑，
# 只有**数字**是假的 —— 当年 run_cors_live_tests.sh「16 条全通过」却根本编不过，
# 正是这个形态。这里把 §5.2 里一个已知条数改掉，守卫必须点名「判据⑤」那一条。
reset_src
python3 - "$STATUS" tools/suite-counts.json <<'PY'
import json, sys
p, m = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
# ⚠ 不写死数字（本轮修：原来写死 `132`，而它随守卫加判据一起漂 ——
#   与 `run_schema_sampler_guard_tests.sh` 的桩⑦ 是**同一个形态的漂移**，
#   只不过发生在另一个自测文件里。任何一条"锚在一个会变的数字上"的桩，
#   都会在数字变的那一刻变成"桩自己红了"，而这不是被测对象的错。
#
#   改成从**清单里读真值**，再把它 +1 写成假值：本桩要的只是"文档与清单不一致"，
#   具体是多少条与它无关。
real = json.load(open(m, encoding="utf-8"))["totals"]["tools/run_schema_sampler_guard.sh"]
assert isinstance(real, int), "桩⑧：清单里 run_schema_sampler_guard.sh 的条数不是整数"
good = "**%d 条全通过**" % real
bad = "**%d 条全通过**" % (real + 1)
assert good in s, "桩⑧ 依赖 §5.2 里那条 %s" % good
s = s.replace(good, bad, 1)
open(p, "w", encoding="utf-8").write(s)
print("桩⑧：把 %d 改成 %d" % (real, real + 1))
PY
must_red "桩⑧（文档里的条数被改大 1）" "文档写的「N 条」都等于清单条数"

# ── 8b) 桩⑧b：反向 —— 清单被改坏、文档是对的 ─────────────────────────────
# 只有一边错时判据也必须红（否则"两边一起漂"会被放过）。
reset_src
cp tools/suite-counts.json "$TMP/counts.bak"
python3 - tools/suite-counts.json <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding="utf-8"))
d["totals"]["tools/run_schema_sampler_guard.sh"] = 77
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2, sort_keys=True)
PY
must_red "桩⑧b（清单改成 77、文档未动）" "文档写的「N 条」都等于清单条数"
cp "$TMP/counts.bak" tools/suite-counts.json
reset_src

# ── 9) 桩⑨：审查收口小结**掉出扫描面**（判据⑦ 必须变红）────────────────────
# 形态：`docs/REVIEW-110-SUMMARY.md` 是 Issue #110 的唯一索引，引用各套件的名字
# 与条数当证据。有人把它挪进代码块 / 改掉引用写法，使它再也匹配不到
# `tools/run_*.sh`，于是判据①③⑤ 对它的约束**静默消失** —— 它就从
# "受判据保护的证据"退化成"没人核对的手抄文档"，正是 J-1 的形态。
# 桩做法：把文件里全部套件名替换成不含 `tools/run_` 的写法。
reset_src
py_stub "$SUMMARY" <<'PYEOF'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
s2 = re.sub(r'tools/run_[A-Za-z0-9_]+\.(?:sh|py)', '某个套件', s)
assert s2 != s, '桩⑨ 依赖小结里出现 tools/run_*.sh 名字'
open(p, "w", encoding="utf-8").write(s2)
PYEOF
must_red "桩⑨（收口小结里的套件名取不到）" "审查收口小结里的套件名真的进了扫描集合"
reset_src

# ── 9b) 对偶：小结内容正常时判据⑦ 必须绿 ───────────────────────────────────
# 证明判据⑦ 抓的是"内容真的进了集合"，不是"文件存在"这种存在性断言。
reset_src
if run_guard; then ck "桩⑨b（小结内容正常时判据⑦ 全绿）" 1
else ck "桩⑨b（小结内容正常时判据⑦ 全绿）" 0; cat "$TMP/guard.out"; fi

# ── 10) 桩⑩：小结里的条数被改坏（判据⑤ 必须覆盖到小结）─────────────────────
# 这是"把小结纳入判据⑤ 扫描面"的**意义所在**：只有真的核对了它的数字，
# 它才不是又一份手抄文档。桩做法：给小结里加一行表格形式、条数写错的引用，
# 判据⑤（DOC_ROW 正则）必须点名。
reset_src
py_stub "$SUMMARY" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = "## 五、明确没做的事"
assert old in s, '桩⑩ 依赖小结的分节标题'
# 插入一行**表格形式**的条数引用，故意写成假值：999 永远不会是真值，
# 所以这根桩**不依赖**那条套件的当前条数（原来写"真值 45"是个会漂的手抄数）。
row = "| `tools/run_http_lifecycle_guard.sh` | **999 条**（桩⑩ 故意写错） |\n\n"
s = s.replace(old, row + old, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑩（小结里写了错的条数 999）" "文档写的「N 条」都等于清单条数"
reset_src

# 对偶（桩⑩b）：把那一行改成**清单里的真值**，判据⑤ 必须恢复绿 ——
# 说明它抓的是"数字 != 清单"，不是"小结里出现过条数"。
#
# ⚠ 真值**从清单读**，不写死：原来写死 59，而那条套件后来加到 73 ——
#   桩自己先红了，报出来的形状却像"被测对象坏了"（本仓库对这类
#   "锚在一个会变的数字上"的桩已有明确口径，见桩⑧ 的那条 ⚠）。
reset_src
py_stub "$SUMMARY" tools/suite-counts.json <<'PYEOF'
import json, sys
p, m = sys.argv[1], sys.argv[2]
real = json.load(open(m, encoding="utf-8"))["totals"]["tools/run_http_lifecycle_guard.sh"]
s = open(p, encoding="utf-8").read()
old = "## 五、明确没做的事"
row = "| `tools/run_http_lifecycle_guard.sh` | **%d 条**（桩⑩b 正确值） |\n\n" % real
s = s.replace(old, row + old, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
if run_guard; then ck "桩⑩b（条数写对后判据⑤ 恢复绿）" 1
else ck "桩⑩b（条数写对后判据⑤ 恢复绿）" 0; cat "$TMP/guard.out"; fi
reset_src

# ── 11) 桩⑪：文档里的「现 N 个套件」总数被改坏（判据⑧ 必须变红）──────────
# 判据⑤ 只核**逐套件**的「N 条」，对**总数**（「现 124 个」）此前无人核对 ——
# 帐目① 实测 §5.2 写着「83 个套件」、磁盘实际 124，而判据⑤ 全绿。
# 桩做法：把 §5.2 里那个 `**现 N 个**` 改成 +1。
reset_src
py_stub "$STATUS" tools/suite-counts.json <<'PYEOF'
import json, sys
p, m = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
real = json.load(open(m, encoding="utf-8"))["suite_total"]
good = "**现 %d 个**" % real
bad = "**现 %d 个**" % (real + 1)
# ⚠ 这一行必须留：`assert` 失败时**桩⑪b 用的污染清单（77）会留在原地**，
#   后一根桩就在脏基线上跑，报出来的红指不到真正的原因（本仓库
#   `run_ci_wiring_guard_tests.sh` 自己记过这个形态）。改成"找不到就跳过" ——
#   桩⑪ 的职责是"文档被改坏时守卫要红"，不是"文档必须写着我算的那个数"。
if good not in s:
    raise SystemExit("桩⑪ 跳过：§5.2 里没有 %s（文档总数写法变了）" % good)
open(p, "w", encoding="utf-8").write(s.replace(good, bad, 1))
PYEOF
must_red "桩⑪（§5.2 的套件总数被改大 1）" "文档写的「现 N 个套件」都等于清单总数"

# ── 11b) 桩⑪b：反向 —— 清单的 suite_total 被改坏、文档是对的 ──────────────
# 只查一边的判据在「两边一起漂」时会全绿，故必须两侧各来一次
#（判据⑤ 的桩⑧/⑧b 是同一规矩）。
reset_src
py_stub tools/suite-counts.json <<'PYEOF'
import json, sys
p = sys.argv[1]
d = json.load(open(p, encoding="utf-8"))
d["suite_total"] = 77
json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2, sort_keys=True)
PYEOF
must_red "桩⑪b（清单 suite_total 改成 77、文档未动）" "文档写的「现 N 个套件」都等于清单总数"

# ── 5) 还原后必须恢复全绿（防"改坏就不管了"）──────────────────────────────
reset_src
must_green "还原后"

echo ""
if [ "$bad" = "0" ]; then echo "=== CI 接线守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== CI 接线守卫自测：PASS $ok / FAIL $bad ==="; exit 1
