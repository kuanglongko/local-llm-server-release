#!/bin/sh
# 「常驻推理服务的 FGS 类型声明」守卫（Issue #159 的 A4）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么这一块要守卫：它有三种**零症状**的坏法
# ═══════════════════════════════════════════════════════════════════════════
#   F-1 **声明了 `specialUse` 但没写用途说明**。API 34+ 要求
#       `<service>` 内带 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`。
#       少了它，manifest 照样合、照样装，但在系统/审核侧这是一个"用途不明的特殊类型" ——
#       与"没声明"在下游同形，且**没人会看 manifest**。
#   F-2 **少了 `FOREGROUND_SERVICE_SPECIAL_USE` 权限**。该类型要求这条权限
#       （`normal|appop`，自动授予），缺了它 startForeground 时该类型不被认。
#       这一条**只在 API 34+ 的机器上**才发作，本仓库的开发路径（`targetSdk=28`）
#       永远不会撞到 —— 正是"没人会发现"的那一类。
#   F-3 **加了 `specialUse` 却把 `dataSync` 抹掉**。"两者一起声明"是本轮的**前提**：
#       只留 `specialUse` 会让"进程在做本地文件/数据传输"这个**事实成立**的描述消失，
#       换成一个"其它类型都不适用"的兜底 —— 逻辑上更弱，而且是对上一次声明的静默改写。
#
# 还有一条**方向性**的坏法（比上面三条更坏，因为它会让人以为问题解决了）：
#   F-4 **把类型声明说成"保活开关"**。FGS 的类型位**不参与** oom_score_adj
#       （那个由"进程是不是 FGS"决定，与类型位无关），也**换不到**内核冻结豁免
#       （冻结判据只看 MemFree/SwapFree，见 README）。若注释/文档把它写成
#       "开了就不被冻"，下一个人会拿这份声明去解释一个它根本没碰的现象。
#       所以判据要求**同一段说明里必须点出"它不做什么"**，而不是只写"加了 specialUse"。
#
# 判据一律锚**结构关系**（谁与谁必须同现、少了哪个会被谁拒绝），不锚存在性
# （"manifest 里出现过 specialUse"）—— 那种断言在 F-1/F-2/F-3 上全绿。
# 全是源码级静态断言，不依赖任何工具链。运行：sh tools/run_fgs_type_guard.sh
set -e
cd "$(dirname "$0")/.."
MAN=app/src/main/AndroidManifest.xml
TMP_COMMENT=$(mktemp)
trap 'rm -f "$TMP_COMMENT"' EXIT
README=README.md

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

[ -f "$MAN" ] || { echo "FAIL  锚点文件不存在：$MAN（守卫需更新）"; exit 1; }

# 取 manifest 的**代码行**：剥掉注释块（注释里就在解释这些字段/权限名，算进来会
# 让"不许/必须"两条都失真 —— 本仓库在这一族上已栽过一次，见 run_keepalive_guard.sh）。
man_code() { awk '{ if (in_c) { if ($0 ~ /-->/) in_c=0; next } if ($0 ~ /<!--/ && $0 !~ /-->/) { in_c=1; next } if ($0 ~ /<!--/) next; print }' "$MAN"; }
# 取 InferenceService 那个 <service> 元素的代码行（到它的 `</service>` 为止）
svc_block() {
  man_code | awk '/<service/{f=1} f{print} f&&/<\/service>/{exit}' \
    | awk '/InferenceService/{f=1} f{print}'
}
# 取 manifest 里那段"常驻推理服务"的**注释**（F-4 的说明就在注释里）
svc_comment() {
  awk '/<!-- 常驻推理服务/{f=1} f{print} f&&/-->/{exit}' "$MAN"
}

# ── 0) 防恒真：取行工具必须真的取到了内容 ─────────────────────────────────
c "man_code 取到了 <manifest> 行（防下面\"必须/不许\"恒真）" \
  "man_code | grep -q '<manifest'"
c "svc_block 取到了 InferenceService 的 <service> 元素（防\"同现\"判据恒真）" \
  "svc_block | grep -q 'InferenceService'"
c "svc_comment 取到了那段说明（防 F-4 恒真）" \
  "[ -n \"\$(svc_comment)\" ]"

# ── 1) F-3：两个类型**一起**声明（前提，不是可选项）────────────────────────
c "F-3：foregroundServiceType 同时含 dataSync 与 specialUse（不能只剩 specialUse）" \
  "svc_block | grep -q 'android:foregroundServiceType=\"dataSync|specialUse\"'"
c "F-3：dataSync 仍在（local file processing 这个事实成立的描述不许被静默改写）" \
  "svc_block | grep -q 'dataSync'"
c "F-3：specialUse 仍在（将来抬 targetSdk 时不吃 dataSync 的时间额度）" \
  "svc_block | grep -q 'specialUse'"

# ── 2) F-1：specialUse 必须带用途说明 property，且说明**有内容** ────────────
c "F-1：声明了 PROPERTY_SPECIAL_USE_FGS_SUBTYPE（specialUse 的用途说明）" \
  "svc_block | grep -q 'android:name=\"android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE\"'"
# 判据锚"有实质内容"，不锚"出现过这个属性名"：一句话的 value 与空串在下游同形。
# 要求 value 里出现"服务/推理/生成/传输"这类**具体用途**词，而不是 \"keep alive\" 那种空话。
c "F-1：用途说明是**具体用途**（不是 \"keep alive\" 之类的空话）" \
  "svc_block | grep -qE 'android:value=\"[^\"]*(推理|生成|服务|传输|文件)[^\"]*\"'"
c "F-1：用途说明里**不出现** \"保活\"（这个类型不是保活开关，写成保活等于误导审核）" \
  "! svc_block | grep -q '保活'"
c "F-1：用途说明**非空且够长**（≥ 20 字，防占位串）" \
  "python3 -c \"
import re,sys
s=open('$MAN',encoding='utf-8').read()
m=re.search(r'PROPERTY_SPECIAL_USE_FGS_SUBTYPE\\\".*?android:value=\\\"([^\\\"]*)\\\"', s, re.S)
sys.exit(0 if m and len(m.group(1))>=20 else 1)
\""

# ── 3) F-2：specialUse 对应的权限必须声明 ────────────────────────────────
c "F-2：声明了 FOREGROUND_SERVICE_SPECIAL_USE（缺了它 API 34+ 不认该类型）" \
  "man_code | grep -q 'android:name=\"android.permission.FOREGROUND_SERVICE_SPECIAL_USE\"'"
c "F-2：FOREGROUND_SERVICE 基础权限仍在" \
  "man_code | grep -q 'android:name=\"android.permission.FOREGROUND_SERVICE\"'"
c "F-2：dataSync 对应的权限仍在（不能为了加新类型而删旧类型）" \
  "man_code | grep -q 'android:name=\"android.permission.FOREGROUND_SERVICE_DATA_SYNC\"'"
# 剥注释不能是"多此一举"：那段 `<service>` 的说明里**确实**写着 oom_score_adj /
# MemFree / targetSdk 这些字样（F-4 就靠它们）。若 man_code 没把注释剥掉，
# 则 F-4 那三条会在**注释里**命中 —— 也就是判据锚到了自己的说明文字上
# （本仓库 run_keepalive_guard.sh 栽过的同一形态）。这里把这个差别钉死。
c "剥注释真的生效：注释里的 oom_score_adj 没被 man_code 取到" \
  "! man_code | grep -q 'oom_score_adj'"
c "对照：同一串在整文件里**确实存在**（防上一条因"哪都没有"而恒绿）" \
  "grep -q 'oom_score_adj' \"\$MAN\""
c "剥注释真的生效：注释里的 MemFree 没被 man_code 取到" \
  "! man_code | grep -q 'MemFree'"

svc_comment > "$TMP_COMMENT"

# ── 4) F-4：说明必须点出"它**不**做什么"（防被读成保活开关）────────────────
c "F-4：说明里点明不改 oom_score_adj" \
  "svc_comment | grep -q 'oom_score_adj'"
c "F-4：说明里点明换不到冻结豁免（冻结判据只看内存）" \
  "svc_comment | grep -qE '冻结豁免|MemFree'"
c "F-4：说明里点明收益是语义完备 / 将来抬 targetSdk（而不是\"不再被冻\"）" \
  "svc_comment | grep -qE '语义完备|targetSdk'"
# 这一条用"承诺词必须被**就近否定**"来判，而不是"全文里有没有出现过'不是'"：
# 说明里本来就写着"它**不是**一个 time-bounded 的任务"（在描述 dataSync），
# 拿"出现过'不是'"当豁免，会让任何一句"保证存活"都变得合法（自测桩⑤ 第一版全绿）。
# 判据：每一处 不再被冻/保证存活/永不被冻 的前 8 个字符里必须有"不"，
# 否则就是一句**正面承诺**，判红。
c "F-4：每一处承诺词都被**就近否定**（'不是不再被冻' 合法，'保证存活' 非法）" \
  "python3 -c \"
import re,sys
t=open('$TMP_COMMENT',encoding='utf-8').read()
bad=[m for m in re.finditer(r'不再被冻|保证存活|永不被冻', t) if '不' not in t[max(0,m.start()-8):m.start()]]
sys.exit(0 if not bad else 1)
\""
c "F-4：承诺词确实在说明里出现过（防上一条因'哪都没有'恒绿）" \
  "svc_comment | grep -qE '不再被冻|保证存活'"

# ── 5) 那条被点名放弃的路**确实没被偷偷加回来** ───────────────────────────
c "没加 PARTIAL_WAKE_LOCK（只挡 CPU 休眠、不挡 cgroup 冻结，纯耗电）" \
  "! man_code | grep -q 'WAKE_LOCK'"
# 判据必须排注释：InferenceService 里**特意**用注释解释了"有意不用 IMPORTANCE_HIGH"，
# 不排的话这条断言在**完全正确的源码**上会红（与上一条同族，先钉住）。
isvc_code() { awk '{ if (in_c) { if ($0 ~ /\*\//) in_c=0; next } if ($0 ~ /\/\*/ && $0 !~ /\*\//) { in_c=1; next } if ($0 ~ /\/\*/) next; sub(/\/\/.*$/,""); print }' app/src/main/java/com/xiaowan/localinference/InferenceService.kt; }
c "没把通知提到 IMPORTANCE_HIGH（换不到豁免还每次弹横幅）" \
  "! isvc_code | grep -q 'IMPORTANCE_HIGH'"
c "对照：IMPORTANCE_HIGH 在注释里确实出现过（防上一条恒绿）" \
  "grep -q 'IMPORTANCE_HIGH' app/src/main/java/com/xiaowan/localinference/InferenceService.kt"
c "通知渠道仍是 IMPORTANCE_DEFAULT（不是 LOW，也不是 HIGH）" \
  "isvc_code | grep -q 'IMPORTANCE_DEFAULT'"
c "targetSdk 仍是 28（本轮不抬；抬它需要单独回归 FGS 类型与 POST_NOTIFICATIONS）" \
  "grep -q 'targetSdk = 28' app/build.gradle.kts"

# ── 6) 文档侧：README 必须把 A4 也写成"不承诺不被冻" ─────────────────────
c "README 的保活一节提到 specialUse（用户要能从文档对上 manifest）" \
  "grep -q 'specialUse' \$README"
# 判据锚**关系**（"由什么决定"），不锚"这一节里出现过 oom_score_adj 这个词"：
# 提到症状/字段名很容易，而这一条要防的恰恰是"只提名字、不说它由谁决定"。
# 范围取**A4 那一段**（从含 specialUse 的粗体入口到下一个 `### `），
# 不取"从「后台保活」到文件末尾" —— 后者会把 KV 缓存等章节也算进来，判据变松。
a4_section() { awk '/\*\*服务自身的前台服务类型/{f=1} f&&/^### /{exit} f{print}' "$README"; }
c "a4_section 真的取到了 A4 那一段（防下面三条恒真）" \
  "a4_section | grep -q 'specialUse'"
c "README 的 A4 段点明 oom_score_adj 由'进程是不是 FGS'决定" \
  "a4_section | grep -qE 'oom_score_adj.*由.*决定'"
c "README 的 A4 段点明它换不到冻结豁免（判据只看内存）" \
  "a4_section | grep -qE '换不到.*冻结豁免|冻结豁免.*只看|冻结判据只看'"
c "README 的 A4 段点明收益是语义完备 / 不吃将来的时间额度（不是不再被冻）" \
  "a4_section | grep -qE '语义完备|时间额度'"

echo ""
if [ "$bad" = "0" ]; then echo "=== FGS 类型声明守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== FGS 类型声明守卫：PASS $ok / FAIL $bad ==="; exit 1
