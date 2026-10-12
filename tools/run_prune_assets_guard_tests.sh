#!/bin/sh
# 自测 `tools/run_prune_assets_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 那个守卫的判据全是 grep/awk 的结构性断言，而结构性断言有三个自欺模式
# （本仓库各栽过一次，所以三个都钉）：
#
#   1. **恒真**：pattern 写成到处都有的文本。这里最容易恒真的是
#      「.cnb.yml 里有 crontab:」—— 守卫**自己的注释**里就写着这个词，
#      而文件末尾的说明里也反复出现。不剥注释就是恒真。
#   2. **判据锚错位置**：钉"文件里出现过 `--apply`"，而不是"**那条 crontab
#      的 stage 里**有 `--apply`"。前者对"另一个任务里带 `--apply`、
#      清扫这条没带"的桩恒真 —— 而清扫这条没带 = 每天只列清单不删，
#      恰恰是这件事最像"已经在工作"的坏法。
#   3. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快有人把 `exit 1` 改成 `|| true`。所以每条判据都要有一条桩
#      证明它**真的会在该红时红**。
#
# 桩全部取自这套接线**真正会写错的写法**（不是编出来的）：
#   · 定时任务压根没接（脚本在磁盘上，没人调它 -> 与没有它等价）
#   · 接了但没带 `--apply`（每天列一遍清单、日志绿油油）
#   · 寿命漏写（退回脚本默认……恰好也是 3，所以靠"没报错"看不出来）
#   · 寿命写成 7 天（用户点名的是 3）
#   · cron 表达式写成四段（平台不接受，任务永不触发）
#   · 放行集里混进 `repo_release`（平台回 422，整批静默失败）
#   · 默认从 dry-run 改成真删（`APPLY=1` 起步 —— `--apply` 就形同虚设）
#   · `--apply` 之外又开了一个把 APPLY 置 1 的入口（绕过闸）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本 —— 与
# run_reset_default_guard_tests.sh 同一套路。
# 运行：sh tools/run_prune_assets_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_prune_assets_guard.sh
CI=.cnb.yml
SCRIPT=scripts/prune-issue-assets.sh
STAGE=scripts/ci-apk-stage.sh
UPLOAD=scripts/upload-apk-to-issue.sh

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$CI" "$TMP/ci.bak"
cp "$SCRIPT" "$TMP/script.bak"
cp "$STAGE" "$TMP/stage.bak"
cp "$UPLOAD" "$TMP/upload.bak"
worktree_lock_acquire "$0"
restore() { cp "$TMP/ci.bak" "$CI"; cp "$TMP/script.bak" "$SCRIPT"
            cp "$TMP/stage.bak" "$STAGE"; cp "$TMP/upload.bak" "$UPLOAD"; rm -rf "$TMP"; }
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ci.bak" "$CI"; cp "$TMP/script.bak" "$SCRIPT"
              cp "$TMP/stage.bak" "$STAGE"; cp "$TMP/upload.bak" "$UPLOAD"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    # ⚠ 要在**打红的那一行**上找，不能只看"关键词出现在输出里"：
    # 判据名本身会出现在守卫的 PASS 行上（同一句话既是被判的对象、也是判据名），
    # 只看"出现过"就会拿 PASS 行当命中 —— 那是判据网自己假绿，最难发现的一类。
    line=$(grep -F "$key" "$TMP/guard.out" | grep '^FAIL' | head -1)
    if [ -n "$line" ]; then
        ck "$name：指到了「$key」" 1
    else
        ck "$name：指到了「$key」（守卫里没这条判据，或它没在这条桩上红）" 0
    fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：定时任务压根没接 ───────────────────────────────────────────────
# 脚本在磁盘上、也测得出对错，但**没有任何东西调它** —— 用户看到的是
# "空间还是在涨"，与"根本没做这件事"完全同形。
"$PY" - "$CI" <<'PY_EOF'
import re, sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = re.sub(r'\nmain:\n.*$', '\n', s, flags=re.S)
open(p, 'w', encoding='utf-8').write(s)
PY_EOF
must_red "桩①（crontab 没接）" "① .cnb.yml 里有 crontab 触发"
reset_src

# ── 2. 桩②：接了但没带 --apply ─────────────────────────────────────────────
# 最像"已经在工作"的坏法：每天跑一次、每天列一遍清单、一条都不删，
# 而日志绿油油、退出码 0。
"$PY" - "$CI" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'DAYS=3 sh scripts/prune-issue-assets.sh --apply'
new = 'DAYS=3 sh scripts/prune-issue-assets.sh'
assert s.count(old) == 1, "桩②：没找到清扫那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩②（没带 --apply：每天只列清单）" "④ 定时任务带 --apply"
reset_src

# ── 3. 桩③：寿命漏写（退回脚本默认，恰好也是 3）───────────────────────────
# 这一桩专打"靠两处默认值撞上"：脚本默认就是 3，所以漏写**看不出任何差别**，
# 而它意味着"以后改脚本默认值，配置这边不会跟着改"。
"$PY" - "$CI" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'DAYS=3 sh scripts/prune-issue-assets.sh --apply'
new = 'sh scripts/prune-issue-assets.sh --apply'
assert s.count(old) == 1, "桩③：没找到清扫那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩③（寿命漏写，靠默认撞上）" "⑤ 定时任务显式指定寿命 3 天"
reset_src

# ── 4. 桩④：寿命写成 7 天（用户点名的是 3）────────────────────────────────
"$PY" - "$CI" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'DAYS=3 sh scripts/prune-issue-assets.sh --apply'
new = 'DAYS=7 sh scripts/prune-issue-assets.sh --apply'
assert s.count(old) == 1, "桩④：没找到清扫那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩④（寿命 7 天，不是用户点的 3）" "⑤ 定时任务显式指定寿命 3 天"
reset_src

# ── 5. 桩⑤：cron 表达式写成四段 ────────────────────────────────────────────
# 平台要求 POSIX 五段；少一段的表达式任务永不触发，而 YAML 照旧能解析。
"$PY" - "$CI" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '"crontab: 30 4 * * *"'
new = '"crontab: 30 4 * *"'
assert s.count(old) == 1, "桩⑤：没找到 cron 表达式"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑤（cron 只有四段）" "② cron 表达式是五段"
reset_src

# ── 6. 桩⑥：放行集混进 repo_release ───────────────────────────────────────
# 平台对 release / commit 附件回 422。塞进去 = 整批 422，而失败若被处理成
# "这批都不行"，看起来就是"没什么可清的"。
"$PY" - "$SCRIPT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "DELETABLE = {'slug_file', 'slug_img', 'issue_file', 'issue_img',\n             'pull_file', 'pull_img'}"
new = "DELETABLE = {'slug_file', 'slug_img', 'issue_file', 'issue_img',\n             'pull_file', 'pull_img', 'repo_release'}"
assert s.count(old) == 1, "桩⑥：没找到放行集"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑥（放行集混进 repo_release）" "⑨ 放行集**不含** repo_release"
reset_src

# ── 7. 桩⑦：默认从"只看"改成"真删" ────────────────────────────────────────
# `APPLY=1` 起步 -> 任何一次不带参数的调用（含人工试跑）都会真删，
# 而 `--apply` 这道闸形同虚设。输出文本与清单**完全看不出来**。
"$PY" - "$SCRIPT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "APPLY=0\nNOW="
new = "APPLY=1\nNOW="
assert s.count(old) == 1, "桩⑦：没找到 APPLY 的初值"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑦（默认改成真删）" "⑩ 默认 APPLY=0"
reset_src

# ── 8. 桩⑧：另开一个把 APPLY 置 1 的入口（绕过 --apply 那道闸）───────────
"$PY" - "$SCRIPT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    --apply) APPLY=1 ;;"
new = "    --apply) APPLY=1 ;;\n    --force) APPLY=1 ;;"
assert s.count(old) == 1, "桩⑧：没找到 --apply 分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑧（多开一个绕过闸的入口）" "⑩ 只有一处把 APPLY 置 1"
reset_src

# ── 9. 桩⑨：判据锚错位置 —— 把 --apply 挪到**另一个**任务里 ───────────────
# 这一桩专打"只看整个文件里有没有 --apply"：清扫那条没带，但文件里
# **确实**出现过 `--apply`。锚"文件里出现过"的写法对这条桩恒真。
"$PY" - "$CI" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'DAYS=3 sh scripts/prune-issue-assets.sh --apply'
new = ('DAYS=3 sh scripts/prune-issue-assets.sh\n'
       '            # 别处用得上 --apply（这一段是桩，为了骗"文件里出现过"）')
assert s.count(old) == 1, "桩⑨：没找到清扫那一行"
s = s.replace(old, new, 1)
# 另起一个任务，里面带 --apply，让"整个文件里出现过 --apply"为真。
s += ('\n    # 桩：另一个任务里带 --apply\n'
      '  "crontab: 0 5 * * *":\n'
      '    - name: 桩任务\n'
      '      stages:\n'
      '        - script: |\n'
      '            sh scripts/prune-issue-assets.sh --apply\n')
open(p, 'w', encoding='utf-8').write(s)
PY_EOF
must_red "桩⑨（--apply 在别的任务里，清扫这条没带）" "④ 定时任务带 --apply"
reset_src

# ── 10. 桩⑩：镜像退回 `ubuntu:latest`（不带 python3）──────────────────────
# 任务会红在 `python3: not found` 上 —— 它长得像"清扫脚本有 bug"，
# 而排查方向全错。这条判据只能靠镜像名守，"装没装 python3"静态看不出来。
"$PY" - "$CI" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'image: python:3.12-slim'
new = 'image: ubuntu:latest'
assert s.count(old) == 1, "桩⑩：没找到清扫任务的镜像"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑩（镜像退回 ubuntu，不带 python3）" "⑫ 清扫任务的镜像带 python3"
reset_src

# ── 11. 桩⑪：脚本改用 curl（镜像就必须带 curl，slim 不再够）───────────────
"$PY" - "$SCRIPT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'def get(url):'
new = 'def get(url):  # curl -sS "$url"'
assert s.count(old) == 1, "桩⑪：没找到 get()"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑪（脚本改用 curl，镜像得跟着换）" "⑫ 脚本确实不需要 curl"
reset_src

# ── 12. 桩⑫：上传那半截从 stage 里掉出去（4f20160 的真实回归）───────────
# 这是**原样复刻**本仓库真栽过的那一次：挪 stage 脚本时照了更早的正文，
# 上传那几行没了，而构建全绿。少了上传，清扫就没有对象。
"$PY" - "$STAGE" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
i = s.index('if [ -n "$CNB_ISSUE_IID" ]; then')
s = s[:i]  # 从「上传那一段」起整段砍掉
open(p, 'w', encoding='utf-8').write(s)
PY_EOF
must_red "桩⑫（上传那半截从 stage 里掉了）" "⑪b 构建 stage 真的调了上传脚本"
reset_src

# ── 13. 桩⑬：上传脚本退回 commit/release 附件那条路 ───────────────────────
# 那条路**没有** delete-asset 的适用性（平台回 422 不可删），于是放上去的东西
# 再也清不掉 —— 上传与清扫必须同源。
# 只改**那一行调用**（文件头的说明注释里也有这个词，全替换会连带把注释改了 ——
# 那样桩就"只剩注释里还有这个词"，而判据②打的是**代码那一行**）。
"$PY" - "$UPLOAD" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '-/issues/$ISSUE/comment-file-asset-upload-url'
new = '-/git/commit-assets/$ISSUE'
assert s.count(old) == 1, "桩⑬：没找到上传端点那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑬（上传改走 commit 附件，就再也清不掉）" "⑪b 上传走的是 Issue 评论附件"
reset_src

echo
echo "=== 到期清扫接线守卫自测：PASS $ok / FAIL $bad ==="
[ "$bad" = 0 ]
