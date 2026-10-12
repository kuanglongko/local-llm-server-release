#!/bin/sh
# 「按需只编译出包」这条入口的守卫（ISSUE #303）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 它防的是什么
# ═══════════════════════════════════════════════════════════════════════════
# 用户在 ISSUE #303 里连问了几轮「直接编译拿包」。那几轮走的是**临时**流水线：
# 用 `POST /{repo}/-/build/start` 现传一份只编译的 config —— 不落盘、不改仓库,
# 包拿得到，但**没有任何东西记得它**，下次还得把那段 config 再拼一遍。
# 于是把那个入口落成 `.cnb.yml` 里一条正规事件：`api_trigger_apk_debug`。
#
# 这条线上每一格的失效**都不抛异常、也不变红**：
#
#   · **入口没落进 `.cnb.yml`** —— 临时 config 那条路照旧能跑，谁也不会发现
#     "其实没有正规入口"。与"根本没做这件事"完全同形。
#   · **入口落了、命令却漂成另一个东西**（改了 `sh scripts/xxx.sh`、或另写一份
#     内联 `gradle -x test ...`）—— 它长得像既有链路，实际编的不是同一份东西，
#     而两条流水线**都绿**。这正是本仓库反复吃的那一课：锚"两个东西相等"，
#     不锚"某某字符串出现过"。
#   · **加了入口、却把出包那段从既有 stage 里挪走** —— 240 份套件的质量门禁
#     连同产品一起没了，而新入口照旧绿。所以"目标套件仍接在既有 stage 里"
#     必须**同一条判据里**钉住，不能只钉新入口在不在。
#   · **把新入口接成一个"和 abicheck2 一样"的名字**（覆盖掉原有的）——
#     原有那条是唯一的质量门禁，被顶掉之后 push 侧就再没有全量套件了。
#     所以另有一条判据钉"两个事件名不同、且原有那个的命令不许动"。
#
# 判据一律锚**结构关系**（谁调用谁、两段文本是否逐字相等、A 在不在 B 里），
# 不锚存在性 —— "事件名出现过""容器名写对"这类断言在本仓库已被反复证明恒真。
#
# ⚠ 说明这件事的注释里**必然**出现 `api_trigger_apk_debug` / `apk_debug` /
#   `ci-apk-stage.sh` 这些字。所以下面每一处都**先剥注释**再判，否则整份守卫
#   对"把入口从配置里删掉、注释还留着"的桩恒真（自测的桩② 就是照这个造的）。
#
# 纯静态断言 + python3 标准库，不依赖任何工具链。运行：sh tools/run_apk_debug_entry_guard.sh
set -e
# 仓库根可被 `CNB_ROOT` 覆盖：自测（`run_apk_debug_entry_guard_tests.sh`）拿
# **副本目录**跑，于是它一处真源码都不用打开写 —— 本仓库多数自测走的是
# "就地打桩 + trap 还原"，那条路对并行很脆（见 tools/with_worktree_lock.sh），
# 能在副本上跑就不占那把锁。
cd "${CNB_ROOT:-$(dirname "$0")/..}"

CI=.cnb.yml
STAGE=scripts/ci-apk-stage.sh

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c ".cnb.yml 存在" "[ -f \$CI ]"
c "既有 stage 脚本存在" "[ -f \$STAGE ]"

# ── ① 入口落进了 `.cnb.yml`，且是 `api_trigger` 事件名 ────────────────────
# 事件名必须是 `api_trigger_` 前缀：这条入口的用法就是"从 Issue 里点一次"
# （那几轮用户体验到的就是这个形状），换成 `push:` / `crontab:` 都会变成
# 另一种东西（前者每次 push 都编、后者无人触发）。
ENTRY=$(python3 - "$CI" <<'PY'
import os, re, sys
sys.path.insert(0, os.path.join(os.environ.get("CNB_ROOT", "."), "tools", "apk_debug_entry"))
from parse_entries import block, script_of, strip_comments

# 剥注释：注释里**必然**提到事件名，不剥则下面全部判据对"删掉入口留注释"恒真。
s = strip_comments(open(sys.argv[1], encoding="utf-8").read())
print(" ".join(re.findall(r"^[ \t]+(api_trigger_\S+):[ \t]*$", s, re.M)))
PY
)
c "① .cnb.yml 里有 api_trigger 事件（当前：${ENTRY:-无}）" "[ -n \"\$ENTRY\" ]"
c "① 入口里有 apk_debug（按需出包那条真的落了盘）" \
  "printf '%s' \"\$ENTRY\" | grep -qw 'api_trigger_apk_debug'"

# ── ② 两个事件名必须**不同**，且原有的那条还在 ────────────────────────────
# 用力方向与直觉相反：这一条不是"新入口要在"，而是"**旧入口不许被顶掉**"。
# 把 `api_trigger_abicheck2:` 改名成 `api_trigger_apk_debug:` 就能让上面第①条
# 全绿一整天 —— 而仓库里唯一的质量门禁就此消失。
c "② 原有的 api_trigger_abicheck2 仍在（不许被顶掉）" \
  "printf '%s' \"\$ENTRY\" | grep -qw 'api_trigger_abicheck2'"
c "② 两个事件名不同（不是同一个键的两种写法）" \
  "[ \"\$(printf '%s\n' \$ENTRY | tr ' ' '\n' | grep -c .)\" -ge 2 ]"

# ── ②b 名单里**不许有第三份没接门的同族入口** ────────────────────────────
# 上面② 只钉"旧的还在、两个名字不同"。而"照着新入口再抄一份"是完全自然的
# 下一步（这条入口的存在本身就在鼓励照抄），抄出来的第二份会**静默地**
# 只受判据①②（名字在、不撞旧的），③④⑤（命令 / 脚本 / 镜像）一条都不管它。
# 所以名单必须**恰好**是这两个：多一个就现红，并点出是谁。
# 这不是"不许有第三条流水线"—— 真要加，就把本文件与自测一起扩成按名字表
# 遍历的形状；在那之前，"没接门"的入口不该被当成已经接过的。
EXTRA=$(python3 - "$CI" <<'PY'
import os, re, sys
sys.path.insert(0, os.path.join(os.environ.get("CNB_ROOT", "."), "tools", "apk_debug_entry"))
from parse_entries import strip_comments

s = strip_comments(open(sys.argv[1], encoding="utf-8").read())
known = {"api_trigger_abicheck2", "api_trigger_apk_debug"}
names = re.findall(r"^[ \t]+(api_trigger_\S+):[ \t]*$", s, re.M)
print(" ".join(n for n in names if n not in known))
PY
)
c "②b 没有未接门的 api_trigger 事件（多出来的：${EXTRA:-无}）" \
  "[ -z \"\$EXTRA\" ]"

# ── ③ 新入口跑的命令，与既有 stage **逐字同源** ──────────────────────────
# 这是本守卫的正中靶心。判"两段文本相等"，不判"出现过 ci-apk-stage.sh" ——
# 后者对"入口里写 `sh scripts/ci-apk-stage.sh --fast`"或"另写一份内联 gradle"
# 都恒真，而那两种都会让新入口编出**另一个东西**，且两条流水线都绿。
#
# 取法：剥注释后，按事件名切段（到下一个顶层键或下一个 `api_trigger_*:` 为止），
# 再抽这一段的 `script:` 正文。两段都按"去空行、去行首缩进"归一化后比。
CMD_EQ=$(python3 - "$CI" <<'PY'
import os, sys
exec(open(os.path.join(os.environ.get("CNB_ROOT", "."), "tools/apk_debug_entry/parse_entries.py"), encoding="utf-8").read())

s = strip_comments(open(sys.argv[1], encoding="utf-8").read())
a = script_of(s, "api_trigger_abicheck2")
b = script_of(s, "api_trigger_apk_debug")
print("SAME" if (a and b and a == b) else "DIFF")
PY
)
c "③ 新入口的命令与既有 stage 逐字相同（不一致 = 编的是另一个东西）" \
  "[ \"\$CMD_EQ\" = \"SAME\" ]"

# ── ④ 新入口没绕过既有 stage：命令确实调的是 `scripts/ci-apk-stage.sh` ────
# ③ 判"两段相等"，但相等的一段也可能两边**一起**漂成别的（比如都改成
# `sh scripts/other.sh`）。所以另钉一个锚：调用的名字必须是既有 stage 脚本，
# 且这个脚本在磁盘上真的存在（判据①那行 `[ -f $STAGE ]` 是它的另一半）。
CALLED=$(python3 - "$CI" <<'PY'
import os, re, sys
sys.path.insert(0, os.path.join(os.environ.get("CNB_ROOT", "."), "tools", "apk_debug_entry"))
from parse_entries import block, script_of, strip_comments

s = strip_comments(open(sys.argv[1], encoding="utf-8").read())
body = block(s, "api_trigger_apk_debug")
print(" ".join(re.findall(r"(?:sh|bash)\s+(scripts/[A-Za-z0-9_.-]+\.sh)", body)))
PY
)
c "④ 新入口调的是 scripts/ci-apk-stage.sh（当前：${CALLED:-无}）" \
  "[ \"\$CALLED\" = \"scripts/ci-apk-stage.sh\" ]"

# ── ⑤ 容器镜像与既有 stage 相同 ───────────────────────────────────────────
# 只编译也需要 NDK/CMake/Android SDK。换一个没这些的镜像，第一次真跑才发现 ——
# 而"入口存在"的所有静态判据照样全绿。
IMG_A=$(sed -nE '/^[ \t]*api_trigger_abicheck2:/,/^[ \t]*api_trigger_/{s/^[[:space:]]*image:[[:space:]]*//p;}' "$CI" | head -1)
IMG_B=$(sed -nE '/^[ \t]*api_trigger_apk_debug:/,/^[ \t]*[a-zA-Z$"'"'"']/{s/^[[:space:]]*image:[[:space:]]*//p;}' "$CI" | head -1)
c "⑤ 镜像非空且两边一致（既有：${IMG_A:-无} / 新入口：${IMG_B:-无}）" \
  "[ -n \"\$IMG_A\" ] && [ \"\$IMG_A\" = \"\$IMG_B\" ]"

# ── ⑥ 出包那段**仍在既有 stage 里**（宁可两条都编，不许拆东墙）───────────
# 这是"加了入口就把质量门禁拆掉"那一格的判据，也是本文件里唯一一条
# 把两个事件**一起**看的：既有 stage 的正文里必须仍有真正的出包动作。
# 锚"编 APK"那个标记行与 `gradle :app:assembleDebug` 的**组合**：
# 只判 `gradle` 出现过会被注释里的字样骗过（本文件的注释里就有 `gradle`）。
# ⚠ 锚必须是**那一行代码本身**，不能是 `grep -q 'gradle :app:assembleDebug'`。
#   本文件第一版正是后者：被审源码的**正文说明注释**里就写着
#   "流水线最后一步 `gradle :app:assembleDebug` 才是真编译…" ——
#   把真正那行删掉之后，判据照样绿（自测桩⑥b 当场抓到）。
#   这一课在本仓库已反复出现过：锚"某个字符串出现过"，注释就能把它喂饱。
#   所以取法是"逐行剥空行与注释行后，必须有一行**就是** compile 行"。
COMPILE=$(grep -nE '^[[:space:]]*gradle[[:space:]]+:app:assembleDebug([[:space:]]|$)' "$STAGE" | wc -l | tr -d ' ')
MARK=$(grep -cE '^[[:space:]]*echo[[:space:]]+"=== 编 APK ==="' "$STAGE" | tr -d ' ')
c "⑥ 既有 stage 里仍是整条守卫链 + 出包（'编 APK' 标记 ${MARK} 处 / assembleDebug 调用行 ${COMPILE} 处，都须 >= 1）" \
  "[ \"\$MARK\" -ge 1 ] && [ \"\$COMPILE\" -ge 1 ]"
c "⑥ 既有 stage 里没有 REQUIRE=0 式的短路（出包那步不许被条件化）" \
  "! grep -qE '\\\$\\{?SKIP_BUILD' \$STAGE"

echo
if [ "$bad" -eq 0 ]; then
  # 收尾报**条数**（`PASS n / FAIL 0` 这一形状）：`tools/count_suite_totals.py`
  # 拿这份读数去对账 `tools/suite-counts.json`，只认带数字的汇总行 ——
  # 写成"$ok 条全通过"会让清单记成 None，对账时报一条**假漂移**。
  echo "=== 按需出包入口守卫：PASS $ok / FAIL 0 ==="
else
  echo "=== 按需出包入口守卫：PASS $ok / FAIL $bad ==="
  exit 1
fi
