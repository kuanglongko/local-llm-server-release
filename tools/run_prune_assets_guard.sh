#!/bin/sh
# 「到期清扫」的**接线**守卫：定时任务真的在跑吗？跑的是不是这条命令？
#
# ═══════════════════════════════════════════════════════════════════════════
# 现象与根因
# ═══════════════════════════════════════════════════════════════════════════
# 用户的诉求（ISSUE #310）是"附件别一直占地方，给我个自动的寿命"。
# 平台在 Issue/PR 附件上没有 ttl 字段（OpenAPI 里那张表单只有
# name / size / content_type），所以能做的是**每天扫一遍、把到期的删掉**
# —— 一个 `crontab:` 任务 + 一个脚本。这套东西全在 `.cnb.yml` 里接线。
#
# 而这条线上**每一步的失效都是静默的**，且都不抛异常：
#
#   · **任务没接进 `.cnb.yml`** —— 脚本写得再对也一天都不跑。用户看到的
#     是"空间还是在涨"，与"根本没做这件事"完全同形。
#   · **接了但没带 `--apply`** —— 每天跑一次、每天列一遍清单、每天什么
#     都不删，而**日志绿油油**。这一条最像"已经在工作"。
#   · **生命周期没写或写反** —— 默认 3 天是用户点名的值（"设3天就可以"）。
#     漏掉 `DAYS` 会退回脚本默认……**恰好也是 3**，所以这一条靠"跑了没报错"
#     看不出来；要靠"配置里写着 3"才算钉住（不许靠两处默认值撞上）。
#   · **定时任务改成以非 Owner 身份触发** —— 定时任务的 `CNB_TOKEN` 以
#     **最后修改该配置的人**签发。若后来有人（或某个 NPC）顺手改了 `.cnb.yml`，
#     那个人就成了负责人；他若没有 `repo-manage:rw`，清扫每天 403、
#     每天红。这一条脚本自己会报（有测试钉），这里只需保证**任务归属
#     可辨认**：任务名与脚本必须指得出彼此。
#   · **`list-assets` / `delete-asset` 的权限点写错** —— 少一个 scope 就是
#     403，而 403 在第一条守卫里长得和"没到期的东西"很像。
#
# 上面全锚**结构关系**（谁调谁、带什么参数），不锚"某某文件存在"：
# 一个从不被任何 `crontab` 引用的脚本，与没有它等价。
#
# 纯静态断言，不依赖任何工具链。运行：sh tools/run_prune_assets_guard.sh
set -e
cd "$(dirname "$0")/.."

CI=.cnb.yml
SCRIPT=scripts/prune-issue-assets.sh

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "清理脚本存在" "[ -f \$SCRIPT ]"
c ".cnb.yml 存在" "[ -f \$CI ]"

# 剥注释：说明这件事的注释里**必然**出现 `crontab:` / `--apply` / `DAYS` 这些字。
# 不剥则下面全部判据恒真 —— 那正是本轮要防的"守卫被静音"。
code() { sed -e 's/[[:space:]]*#.*//' "$CI" | grep -vE '^[[:space:]]*$'; }

# ── ① 定时任务真的存在，且是按 `crontab:` 触发的 ──────────────────────────
CRON=$(code | grep -n 'crontab:' || true)
c "① .cnb.yml 里有 crontab 触发（不是只有 push / api_trigger）" "[ -n \"\$CRON\" ]"

# ② cron 表达式要合法 POSIX 五段（分支名那种 `crontab: xxx` 之外的写法挡掉）
EXPR=$(printf '%s' "$CRON" | head -1 | sed -E 's/.*crontab:[[:space:]]*//; s/["'"'"'].*//')
c "② cron 表达式是五段（当前：${EXPR}）" \
  "[ \"\$(printf '%s' \"\$EXPR\" | wc -w | tr -d ' ')\" = 5 ]"

# ── ③ 这条 crontab 的 stage 真的调了清理脚本 ──────────────────────────────
# 取 `crontab:` 之后到下一个顶层键之前的那一段（这条任务自己的正文）。
#
# ⚠ 这一段**必须先剥注释**再判。为什么：本文件里解释这条任务的注释
# （包括守卫自己的说明）**必然**出现 `--apply` / `DAYS=3` 这些字 ——
# 拿含注释的正文去 grep，判据对"把 `--apply` 从命令里删掉、注释里还留着"
# 的桩**恒真**。判据②（桩⑨）专门打这一条：它在**另一个任务**里也写了
# `--apply`，若判据锚的是"整个文件里出现过"而不是"这一段里"，桩⑨ 会假绿。
#
# 取段的方式：从含 `crontab:` 的那一行起，到下一个**顶格**的键（列 0 的
# `xxx:`）止 —— 缩进的同级键（另一个 `crontab:`）也停，因为它属于下一个任务。
BLOCK=$(awk '
  /^[[:space:]]*"crontab:/ || /^[[:space:]]*crontab:/ {
    if (!f) { f = 1; print; next }
    else { exit }
  }
  f {
    if (/^[^[:space:]]/) exit
    print
  }' "$CI" | sed -e 's/[[:space:]]*#.*//' -e '/^[[:space:]]*$/d')
c "③ crontab 那一段里调了清理脚本" "printf '%s' \"\$BLOCK\" | grep -q 'prune-issue-assets.sh'"

# ── ④ 必须带 --apply ──────────────────────────────────────────────────────
# 不带 `--apply` = 每天只打清单、什么都不删，而日志全绿。这条是本守卫的
# **主要理由**：它不可能从"任务跑了且成功"里看出来。
c "④ 定时任务带 --apply（不带就只是每天列一遍清单，永远不删）" \
  "printf '%s' \"\$BLOCK\" | grep -q -- '--apply'"

# ── ⑤ 寿命 = 3 天，且写在配置里（不靠脚本默认值撞上）────────────────────
# `DAYS` 与 `--days` 两种写法都接受；关键是要有**显式的 3**。
c "⑤ 定时任务显式指定寿命 3 天（DAYS=3 或 --days 3）" \
  "printf '%s' \"\$BLOCK\" | grep -qE 'DAYS=3([^0-9]|\$)|--days[[:space:]]+3([^0-9]|\$)'"

# ── ⑥ 脚本自己那边的默认值也必须是 3 ─────────────────────────────────────
# 两处同源：配置漏了（比如新加一个入口）也不该悄悄变成别的天数。
c "⑥ 脚本默认 DAYS=3（与配置同源，不靠撞上）" \
  "grep -qE '^DAYS=\\\$\\{DAYS:-3\\}' \$SCRIPT"

# ── ⑦ 权限点写在脚本里，且与平台要求一致 ─────────────────────────────────
# `list-assets` 要 repo-manage:r、`delete-asset` 要 repo-manage:rw。
# 少写一个 scope 的后果是 403，而 403 与"没有到期的东西"在日志里长得像。
c "⑦ 脚本里写明了 list-assets 的权限点 repo-manage:r" \
  "grep -q 'repo-manage:r' \$SCRIPT"
c "⑦ 脚本里写明了 delete-asset 的权限点 repo-manage:rw" \
  "grep -q 'repo-manage:rw' \$SCRIPT"

# ── ⑧ 两个接口都在，且 delete 用的是记录 ID（不是文件名）────────────────
c "⑧ 用 list-assets 列记录" "grep -q 'list-assets' \$SCRIPT"
c "⑧ 用 /-/assets/{id} 删（按记录 ID，不是按文件名）" \
  "grep -qE '/-/assets/' \$SCRIPT"

# ── ⑨ 只放行可删的类型：release / commit 必须**不在**放行集里 ─────────────
# 平台对这两类回 422。把它们塞进去的后果是"整批 422"，而如果失败被吞，
# 看起来就是"没什么可清的"。
# ⚠ 放行集写在**两行**上（Python 的隐式续行），只取 `DELETABLE = ` 那一行
# 会把第二行的 `pull_file`/`pull_img` 漏掉 —— 判据⑨ 于是对"把
# `repo_release` 挪到第二行"的桩恒真（桩⑥ 就是照这个写法造的）。
DEL_ROW=$(awk '/^DELETABLE = /{f=1} f{print; if (/\}/) exit}' "$SCRIPT" || true)
c "⑨ 放行集存在" "[ -n \"\$DEL_ROW\" ]"
c "⑨ 放行集含 slug_file（Issue/PR 附件）" "grep -q 'slug_file' \$SCRIPT"
c "⑨ 放行集**不含** repo_release（平台会回 422）" \
  "! printf '%s' \"\$DEL_ROW\" | grep -q 'repo_release'"
c "⑨ 放行集**不含** repo_commit（平台会回 422）" \
  "! printf '%s' \"\$DEL_ROW\" | grep -q 'repo_commit'"

# ── ⑩ 默认 dry-run：`APPLY=0` 起步，`--apply` 是唯一的开启路径 ────────────
c "⑩ 默认 APPLY=0（不加 --apply 不删）" "grep -qE '^APPLY=0' \$SCRIPT"
c "⑩ 只有一处把 APPLY 置 1（且挂在 --apply 上）" \
  "[ \"\$(grep -cE 'APPLY=1' \$SCRIPT)\" = 1 ] && grep -q -- '--apply) APPLY=1' \$SCRIPT"

# ── ⑪ 脚本可被 `sh` 解析（本体语法错会让全部断言一行都不执行）───────────
c "⑪ 脚本本体过 sh -n（否则上面一切在 CI 里都是空话）" "sh -n \$SCRIPT"

# ── ⑪b 「上传」那一半还在（少了它，清扫就没有对象）────────────────────────
# 这两件事是一对：`scripts/upload-apk-to-issue.sh` 负责把 APK 放到 Issue 上，
# `prune-issue-assets.sh` 负责到点收走。上传那半截在 `4f20160`
#（"stage 脚本挪出 .cnb.yml"）时**丢过一次** —— 那次挪动照的是更早的一份正文，
# 于是整段连同脚本一起消失，而构建照旧全绿。
# 所以这里钉：脚本在、且**构建 stage 真的调它**。
STAGE=scripts/ci-apk-stage.sh
UPLOAD=scripts/upload-apk-to-issue.sh
c "⑪b 上传脚本存在" "[ -f \$UPLOAD ]"
c "⑪b 构建 stage 真的调了上传脚本（丢了它 = 没有对象可清）" \
  "grep -q 'upload-apk-to-issue.sh' \$STAGE"
# ⚠ 必须锚**那一行调用**，不能锚"文件里出现过这个词" —— 文件头的说明注释里
# 也有它，于是"把调用改走 commit 附件"的桩会假绿（注释还留着这个词）。
c "⑪b 上传走的是 Issue 评论附件那条路（与清扫的过滤集同源）" \
  "grep -q -- '-/issues/\\\$ISSUE/comment-file-asset-upload-url' \$UPLOAD"

# ── ⑫ 跑它的镜像里**有 python3** ──────────────────────────────────────────
# 脚本不用 curl / jq / common utils（HTTP 全走 urllib），只要一个 python3。
# 这条判据防的是"任务红在一个与被测对象无关的地方"：`ubuntu:latest`
# **不带 python3**，于是任务在 `python3: not found` 上红 —— 它长得像
# "清扫脚本写了 bug"，而排查方向全错。这一条要靠**镜像名**判，
# 因为"装没装 python3"没法静态看出来。
IMG=$(printf '%s' "$BLOCK" | grep -oE 'image:[[:space:]]*[^[:space:]]+' | head -1 | sed 's/image:[[:space:]]*//')
c "⑫ 清扫任务的镜像带 python3（当前：${IMG}）" \
  "printf '%s' \"\$IMG\" | grep -qE '^(python|.*python:)'"
c "⑫ 脚本确实不需要 curl（HTTP 走 python3，镜像才敢用 slim）" \
  "! grep -qE '(^|[^A-Za-z_])curl([^A-Za-z_]|\$)' \$SCRIPT"

echo
if [ "$bad" -eq 0 ]; then
  # 收尾报**条数**（`PASS n / FAIL 0` 这一形状）：`tools/count_suite_totals.py`
  # 拿这份读数去对账 `suite-counts.json`，只认带数字的汇总行 ——
  # 写成"$ok 条全通过"会让清单记成 None，对账时报一条**假漂移**。
  echo "=== 到期清扫接线守卫：PASS $ok / FAIL 0 ==="
else
  echo "=== 到期清扫接线守卫：PASS $ok / FAIL $bad ==="
  exit 1
fi
