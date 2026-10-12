#!/bin/sh
# 「后台保活（无障碍 / 电池优化引导）」的**接线**守卫（Issue #159 的 B 方案）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么这一块必须守卫，而不是"加了就行"
# ═══════════════════════════════════════════════════════════════════════════
# 这一族有四种**零症状**的坏法，每一种都不会崩、不会报错、也不会让别的测试变红：
#
#   K-1 **声明了但用户在系统设置里看不到**：`<service>` 少了
#       `BIND_ACCESSIBILITY_SERVICE` 权限、少了 `accessibilityservice` 的 meta-data、
#       或 `android:exported` 不是 false —— 系统直接把这一项从无障碍列表里丢掉。
#       现象是"设置里找不到这个 App"，而界面只会显示"未开启"，用户会以为是自己没找到。
#   K-2 **开了等于没开**：`canRetrieveWindowContent="true"` 或 flags 里加了
#       retrieveInteractiveWindows / 事件类型声明成 typeAllMask —— 服务能读到用户
#       所有界面的文本，而本功能**一个字段都不需要**。更坏的是它**看起来完全正常**：
#       服务在跑、存活率可能真的提高了，没人会去看 xml 里多了一个 flag。
#   K-3 **代改权限**：直接写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`。
#       它要么拿不到（`WRITE_SECURE_SETTINGS` 是系统权限），要么在拿到时
#       等于绕过用户同意 —— 两条路都不该走。而"写了但没生效"与"没写"同形，
#       界面上完全看不出来。
#   K-4 **把"查不到"折成"没开"**：`Settings.Secure.getString` / `PowerManager` 查询
#       都可能抛。抛了就报 false 的话，一次系统接口抽风会变成一次误报，
#       用户会去反复开关那两项 —— 而这正是本轮要终结的处境（"以为开好了，其实没生效"
#       的反面："以为没开，其实开好了"）。
#
# 判据一律锚**结构关系**（谁声明了什么、谁读谁、三态有没有被折成两态），
# 不锚存在性（"文件里出现过 AccessibilityService"）—— 那种断言在 K-1~K-4 上全绿。
#
# 全是源码级静态断言，不依赖任何工具链。运行：sh tools/run_keepalive_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
KA=$SRC/KeepAlive.kt
SVC=$SRC/KeepAliveAccessibilityService.kt
ACT=$SRC/EngineActivity.kt
MAN=app/src/main/AndroidManifest.xml
XML=app/src/main/res/xml/keepalive_accessibility.xml
STR=app/src/main/res/values/strings.xml

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取 xml 的**代码行**：剥掉注释块（注释里含 flag 名字，算进来会恒假触发）
xml_code() { awk '{ if (in_c) { if ($0 ~ /-->/) in_c=0; next } if ($0 ~ /<!--/ && $0 !~ /-->/) { in_c=1; next } if ($0 ~ /<!--/) next; print }' "$XML"; }

# 取一个 Kotlin 函数的函数体：从签名行到下一个**同缩进**的 `}`（用大括号配平，防嵌套块）
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }

for f in "$KA" "$SVC" "$ACT" "$MAN" "$XML" "$STR"; do
    [ -f "$f" ] || { echo "FAIL  锚点文件不存在：$f（守卫需更新）"; exit 1; }
done

# ── 1) K-1：系统侧声明必须**齐备**，否则用户根本看不到这一项 ────────────────
# 四条缺一不可：permission / intent-filter action / meta-data / exported=false。
# 只看"有 <service>"是存在性断言 —— K-1 恰恰是"服务在、但三条里的某一条没了"。
c "清单里声明了该服务" \
  "grep -q 'android:name=\".KeepAliveAccessibilityService\"' \$MAN"
c "K-1：声明了 BIND_ACCESSIBILITY_SERVICE（缺了系统不认，设置里看不到）" \
  "grep -q 'android:permission=\"android.permission.BIND_ACCESSIBILITY_SERVICE\"' \$MAN"
c "K-1：intent-filter 里声明 AccessibilityService action" \
  "grep -q 'android.accessibilityservice.AccessibilityService' \$MAN"
c "K-1：meta-data 指向 res/xml 配置（缺了形态全按系统默认，K-2 的 flag 就管不住了）" \
  "grep -q 'android:name=\"android.accessibilityservice\"' \$MAN && grep -q '@xml/keepalive_accessibility' \$MAN"
c "K-1：服务 exported=false（无障碍服务不该被其它应用直接 bind）" \
  "awk '/KeepAliveAccessibilityService/{f=1} f&&/android:exported=\"false\"/{print \"OK\"; exit}' \$MAN | grep -q OK"
# A-2：**同一个坏法族的另一半** —— `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
#   K-1 详查了无障碍的 BIND 权限，却漏了电池优化这条 action 的配套权限：
#   用了 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 就必须声明同名
#   `uses-permission`。缺它不崩、不报错，只是**静默降级**（解析不到 -> 掉到
#   「优化列表」，或弹出没有本应用条目的总列表）—— 与 K-1 一样属于"用了某能力
#   却没把配套声明补齐"，症状是"一级入口恒不可用"，用户只会以为是自己没找到。
#
#   判据锚**蕴含关系**（发起该 action ⟹ 声明该权限），不锚存在性：
#   只断言"manifest 里有这一行"，把 action 那行删掉照样绿，而那时权限成了死声明。
BAT_ACTION='android.intent.action.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'
c "A-2：声明了 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（用了该 action 就必须声明）" \
  "grep -q 'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' \$MAN"
c "A-2：上面那条不是死声明 —— 源码里确实发起了同名 action（防"加了权限但没人用"）" \
  "grep -q 'Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' \$KA"
c "A-2：权限声明与 action 使用**成对**（删一侧必须变红，防恒真）" \
  "grep -q 'REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' \$MAN && grep -q 'ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' \$KA"
c "A-2：注释里点明"缺它会静默降级"（否则后人删它时不知道后果）" \
  "grep -q '缺了它' \$MAN"
# 权限声明只能由用户在系统设置里给：源码里不许出现写那两个键的代码
# 判据锚**写入动作**（`put*` / `Settings.Secure.put*`），不锚"出现过这个键名"：
# 本类**必须**读这个键（读是本功能存在的理由），只禁止写 ——
# 第一版写成"某个键名 + putString 同现"时实测**恒真**（桩⑥ 全绿），
# 因为它要求两段在同一**行**上，而真实写法必然跨行（键名在下一行）。
# 改成"同一个函数体里同时有写入调用与那个键"，桩⑥ 才被抓住。
SECURE_WRITE=$(grep -rnE 'Settings[.]Secure[.]put|contentResolver[.]put|Secure[.]putString' $SRC --include='*.kt' || true)
c "K-3：源码里没有任何一处**写**系统设置（代用户开启 = 绕过同意，且需系统级权限）" \
  "[ -z \"\$SECURE_WRITE\" ]"
c "K-3：写入判据确实能抓到"键名在下一行"的写法（防上一条恒真）" \
  "printf '%s\\n' 'Settings.Secure.putString(ctx.contentResolver,' 'Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, x)' | grep -qE 'Settings\.Secure\.put|Secure\.putString'"
# ⚠ 下面这两组必须**排注释**：xml 的注释里就在解释"禁止加这两项"，
#   Kotlin 注释里也会提到 WRITE_SECURE_SETTINGS。不排的话判据被自己的说明文字
#   命中，在**完全正确的源码**上报 3 条假故障 —— 本守卫第一版实测如此
#   （与本仓库"判据锚到注释上"那一族同形，只是这次是自己踩的）。
c "K-3：也没有申请 WRITE_SECURE_SETTINGS（普通应用拿不到，拿到也不该用）" \
  "! grep -vE '^[[:space:]]*<!--' \$MAN | grep -q 'WRITE_SECURE_SETTINGS'"
c "K-2：xml 里 canRetrieveWindowContent=\"false\"（不读窗口内容）" \
  "xml_code | grep -q 'android:canRetrieveWindowContent=\"false\"'"
c "K-2：xml 里绝不许出现 typeAllMask（那是"什么都收"）" \
  "! xml_code | grep -q 'typeAllMask'"
c "K-2：xml 里不许请求增强网页内容（flagRequestEnhancedWebAccessibility）" \
  "! xml_code | grep -q 'flagRequestEnhancedWebAccessibility'"
c "K-2：xml 里不许请求交互窗口（flagRetrieveInteractiveWindows）" \
  "! xml_code | grep -q 'flagRetrieveInteractiveWindows'"
c "K-2：xml 的 flags 只留 flagDefault" \
  "xml_code | grep -q 'android:accessibilityFlags=\"flagDefault\"'"
# 防上面那三条"不许"恒真：xml_code 必须真的取到了内容（注释剥过头就什么都没了）
c "xml_code 真的取到了属性行（防"不许"三条恒真）" \
  "xml_code | grep -q 'accessibility-service'"
c "K-2：运行时把 eventTypes 显式置 0（声明是最小集，置 0 才是"不收"）" \
  "body 'override fun onServiceConnected\(\)' \$SVC | grep -q 'eventTypes = 0'"
c "K-2：运行时把 flags 显式置 0" \
  "body 'override fun onServiceConnected\(\)' \$SVC | grep -q 'flags = 0'"
# 事件回调必须是空的：一旦有内容，就说明有人在读用户界面数据
c "K-2：onAccessibilityEvent 是空体（不许落日志、不许读 event 内容）" \
  "! body 'override fun onAccessibilityEvent\(event: AccessibilityEvent\?\)' \$SVC | grep -qvE '^\s*(//|override fun|\\}|/\\*|\\*)' "
c "K-2：onAccessibilityEvent 里不碰 event 的任何字段（不许 event\\.）" \
  "! body 'override fun onAccessibilityEvent\(event: AccessibilityEvent\?\)' \$SVC | grep -q 'event\\.'"

# ── 3) K-4：三态不许被折成两态（"查不到"必须如实报）────────────────────────
# 判据锚"状态是怎么算出来的"：读的是**系统真值**，且失败分支回 UNKNOWN 而不是 OFF。
c "状态是三态枚举（含 UNKNOWN）" \
  "grep -q 'enum class OnOff { ON, OFF, UNKNOWN }' \$KA"
K4_ACC=$(body 'fun accessibilityState\(ctx: Context\): OnOff' "$KA")
c "K-4：无障碍状态读的是系统真值（ENABLED_ACCESSIBILITY_SERVICES）" \
  "printf '%s' \"\$K4_ACC\" | grep -q 'Settings.Secure.getString' && printf '%s' \"\$K4_ACC\" | grep -q 'ENABLED_ACCESSIBILITY_SERVICES'"
c "K-4：无障碍状态的 catch 分支回 UNKNOWN（折成 OFF = 把查询失败报成一次误报）" \
  "printf '%s' \"\$K4_ACC\" | grep -q 'OnOff.UNKNOWN'"
c "K-4：无障碍状态不许用"可用服务列表"当开关判据（那会恒真）" \
  "! printf '%s' \"\$K4_ACC\" | grep -q 'getEnabledAccessibilityServiceList'"
c "K-4：无障碍状态做大小写不敏感比较（ROM 回写形式不统一，逐字节比会误报）" \
  "printf '%s' \"\$K4_ACC\" | grep -q 'equals(me, true)'"
K4_BAT=$(body 'fun batteryOptimizationState\(ctx: Context\): OnOff' "$KA")
c "K-4：电池优化读 isIgnoringBatteryOptimizations（不是自己记的开关）" \
  "printf '%s' \"\$K4_BAT\" | grep -q 'isIgnoringBatteryOptimizations'"
c "K-4：电池优化的 catch 分支回 UNKNOWN" \
  "printf '%s' \"\$K4_BAT\" | grep -q 'OnOff.UNKNOWN'"
# 文案层：未知必须与"未开"分开说
c "K-4：三态各自的文案分开（UNKNOWN 不许显示成"未开启"）" \
  "body 'fun onOffText\(' \$KA | grep -q 'OnOff.UNKNOWN' && body 'fun onOffText\(' \$KA | grep -q '查不到'"
c "K-4：组件名由包名现算（拼字符串常量会在 applicationId≠namespace 时永远读成未开启）" \
  "grep -q 'ComponentName(ctx.packageName, KeepAliveAccessibilityService::class.java.name)' \$KA"

# ── 4) 三态之外的第四件事：**声明在不在**，与"用户没开"分开报 ────────────────
c "有 serviceDeclared 判据（声明被吃掉时不能只显示"未开启"）" \
  "grep -q 'fun serviceDeclared(ctx: Context): Boolean' \$KA"
c "serviceDeclared 读不到时按"已声明"处理（它只是排障提示，不是判据本体）" \
  "body 'fun serviceDeclared\(ctx: Context\): Boolean' \$KA | grep -q 'catch (_: Throwable)'"
# A-3a：**注释必须描述实际实现** —— 否则后人照注释改会改错。
#   旧注释写"改用显式的组件查询 getServiceInfo(cn, 0)…NameNotFoundException 就是没声明"，
#   而代码实际走的是 queryIntentServices(q, 0)（行为更贴用途：系统设置也是按 action
#   找服务）。两个实现是不同的读法，注释说 A、代码做 B 属于"文档撒谎"，
#   而它还是本块唯一的排障提示，被人当依据的概率很高。
c "A-3a：实现确实走 queryIntentServices（带 setPackage 只看自己）" \
  "body 'fun serviceDeclared\\(ctx: Context\\): Boolean' \$KA | grep -q 'queryIntentServices'"
c "A-3a：注释描述的是**实际**那个实现（不再声称走 getServiceInfo）" \
  "! sed -n '/声明在不在/,/fun serviceDeclared/p' \$KA | grep -q 'getServiceInfo'"
c "A-3a：注释点明查不到返回空列表、不抛（旧注释靠 NameNotFoundException 解释，与实际不符）" \
  "sed -n '/声明在不在/,/fun serviceDeclared/p' \$KA | grep -q '空列表'"

# ── 5) 引导跳转：逐级降级，且失败时给**照抄得下去**的路径 ─────────────────────
c "电池优化跳转是逐级尝试（申请页 → 优化列表 → 应用详情）" \
  "body 'private fun openBatteryOptimizationSettings\(\)' \$ACT | grep -q 'requestIgnoreBatteryOptimizationIntent' \
   && body 'private fun openBatteryOptimizationSettings\(\)' \$ACT | grep -q 'batteryOptimizationListIntent' \
   && body 'private fun openBatteryOptimizationSettings\(\)' \$ACT | grep -q 'appDetailsIntent'"
c "三级全失败时贴出可照抄的路径（一句"跳转失败"等于没有信息）" \
  "body 'private fun openBatteryOptimizationSettings\(\)' \$ACT | grep -q 'batterySteps'"
c "无障碍页跳转失败时也给路径（各 ROM 差异是常态，不是错误）" \
  "body 'private fun openAccessibilitySettings\(\)' \$ACT | grep -q '设置 → 无障碍'"
c "路径文案是**分步**的（四步：无限制省电 / 自启动 / 省电策略 / 任务锁）" \
  "[ \$(grep -c '\"[0-9])' \$KA) -ge 4 ]"

# ── 6) 状态必须**看得见**：设置页有这一组，且回到前台会重读 ────────────────────
# ⚠ 这一组「留在设置页」是**有意为之**，不是还没搬（同族的「崩溃取证」已经搬到
#   日志/调试页，见 `tools/run_probe_tests.sh` 第 ⑦ 节）。所以判据从「文本里出现过
#   「── 后台保活」升级成**锚归属**：只有分组头在、内容被搬到别页时，下面那条
#   常驻状态行会连带消失，而用户「去系统设置改完回来看到改后结论」这个唯一反馈面
#   就没了 —— 那种坏法不崩不报错，与「本来就没做」同形。
c "设置页有「后台保活」分组，且状态行 / 三个引导入口**都在同一页**" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('── 后台保活')
# 分组体到「还原默认设置」之前（那是页尾 UNGROUPED 的全局操作区）
j = s.index('还原默认设置', i)
blk = s[i:j]
# ⚠ 判据必须锚**这个组自己的控件**的归属，不能只要整段里出现过 addFull/pageSet：
#   第一版写成后者时，桩⑬ 实测**没被抓住** —— 因为段内别的行（kaRow/应用详情按钮）
#   本来就带着 addFull，`keepAliveTv` 换页照样满足。所以逐控件反查它自己的那一行。
# 只扫**挂载行**（带 addView 的那些），不扫声明行：声明行里本来就没有归属信息，
# 拿它当判据等于恒真（第一版实测如此，桩⑬ 抓不住）。
mounts = [l for l in blk.splitlines() if 'addView' in l]
# 判据是负向的：这一段的任何挂载行都不许落到别的页。
bad = [l for l in mounts if 'pageLog' in l or 'pageRag' in l]
sys.exit(0 if mounts and not bad else 1)
PY"
c "「后台保活」不许被搬到日志/调试页（那一页的工具都是取样证据，而这一组是系统权限）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('pageLog.addView(logModeTv)')
j = s.index('pageLog.addView(logTv)')
blk = s[i:j]
bad = [k for k in ['keepAliveTv', 'KeepAlive', 'openAccessibilitySettings'] if k in blk]
sys.exit(1 if bad else 0)
PY"
c "状态行是常驻控件（不是只在日志里）" \
  "grep -q 'keepAliveTv.text = keepAliveText()' \$ACT"
c "onResume 会重读状态（用户去系统设置改完回来必须看到**改后**的结论）" \
  "body 'override fun onResume\(\)' \$ACT | grep -q 'refreshKeepAlive()'"
c "三个引导入口都在界面上（无障碍 / 免电池优化 / 应用详情）" \
  "grep -q 'openAccessibilitySettings()' \$ACT && grep -q 'openBatteryOptimizationSettings()' \$ACT && grep -q 'openAppDetails()' \$ACT"
c "状态行说明里点明"不承诺不被冻"（否则它会被读成一个能解决问题的开关）" \
  "grep -q '开了也不承诺不被冻' \$ACT"
c "状态行说明里点明"只能由你授予、本应用不代改"" \
  "grep -q '本应用不会代改' \$ACT"

# ── 7) 冻结痕迹那一行与「存活探测」**同源**，不许另发一次请求 ─────────────────
c "冻结痕迹取自缓存的那份探测结论（进设置页不该再打一次自己的服务）" \
  "grep -q 'KeepAlive.freezeLine(lastProbeResult)' \$ACT"
c "探测结果确实被缓存下来" \
  "grep -q 'lastProbeResult = r' \$ACT"
c "探测完成后同步刷新保活状态行（两份读数不许打架）" \
  "grep -q 'refreshKeepAlive()' \$ACT && awk '/lastProbeResult = r/{f=1} f&&/refreshKeepAlive\(\)/{print \"OK\"; exit}' \$ACT | grep -q OK"
c "freezeLine 对"没有结论"与"没读过字段"分开说（都是 null，含义不同）" \
  "body 'fun freezeLine\(r: HealthCheck.Result\?\): String' \$KA | grep -q '服务未在跑或未探测' \
   && body 'fun freezeLine\(r: HealthCheck.Result\?\): String' \$KA | grep -q '未报 frozen_'"
# ⚠ 上面这条是**存在性**断言：它要求"未报 frozen_ 字段"这句在函数体里出现。
#   它抓不到本族真正的坏法（A-1）：把"探测失败"与"缺字段"用 `||` 并进同一句 ——
#   那样两个分支输出同一句错方向的话，而"这句话存在"依旧为真（实测：修与不修都绿）。
#   下面四条改成锚**分支结构**，与 HealthCheck.format 的分法同源：
#   先按 transportError 判"这次探测成没成"，成了再看字段缺不缺。
c "A-1：freezeLine 不再把 transportError 与 frozenCount==null 用 || 并成同一句" \
  "! body 'fun freezeLine\\(r: HealthCheck.Result\\?\\): String' \$KA | grep -qE 'transportError != null *\\|\\| *r\\.frozenCount == null'"
c "A-1：探测失败那条分支自己出话（点名本次探测失败，而不是未报 frozen_ 字段）" \
  "body 'fun freezeLine\\(r: HealthCheck.Result\\?\\): String' \$KA | grep -q '本次探测失败'"
c "A-1：探测失败分支排在 frozenCount 判空**之前**（顺序反了等于没拆）" \
  "awk 'BEGIN{t=0;f=0} /本次探测失败/{t=NR} /服务端未报 frozen_/{if(!f)f=NR} END{exit !(t>0 && f>0 && t<f)}' \$KA"
c "A-1：旧错法（transportError 并进 frozenCount 判空）确实能被上面那条抓到（防恒真）" \
  "printf '%s\\n' 'if (r.transportError != null || r.frozenCount == null) {' | grep -qE 'transportError != null *\\|\\| *r\\.frozenCount == null'"

# ── 8) 系统列表里的说明必须**当场说清用途**（用户是在那儿决定开不开的）───────
c "无障碍服务有 description 字符串" \
  "grep -q 'keepalive_accessibility_desc' \$XML && grep -q 'name=\"keepalive_accessibility_desc\"' \$STR"
c "说明里点明"不读窗口内容"（这是这个权限最敏感的部分）" \
  "grep -q '不读取窗口内容' \$STR"
# 注释里不许写会被当成嵌套块注释开头的 /*（Kotlin 不支持嵌套，本项目已踩过两次）
c "KeepAlive 注释里没有嵌套块注释开头" \
  "! grep -nE '^\s*\*(.*[^/]|^)/\*' \$KA"
c "KeepAliveAccessibilityService 注释里没有嵌套块注释开头" \
  "! grep -nE '^\s*\*(.*[^/]|^)/\*' \$SVC"

echo ""
if [ "$bad" = "0" ]; then echo "=== 后台保活接线守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 后台保活接线守卫：PASS $ok / FAIL $bad ==="; exit 1
