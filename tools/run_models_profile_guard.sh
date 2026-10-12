#!/bin/sh
# 「模型档案（GGUF 元数据）」+ `GET /v1/models/{id}` 的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 这个特性**不参与生成**，坏了也不会崩、不会报错、不会变慢。它最危险的坏法是
# **读数错了却看不出来** —— 而它存在的全部意义就是"给一个可信的数"：
#
#   ① `llama_model_meta_val_str` 往你给的 buffer 里写、返回**实际长度**，
#      按头文件约定这个长度**可以 ≥ buffer_size**（-1 才表示失败）。buffer 小
#      就**静默截断**：不报错、不抛异常，给出一个"看起来正常"的短串 ——
#      而"这个数被截断了"与"这模型就是这个值"完全同形。
#   ② `llama_model_size` / `llama_model_n_params` 声明是 `uint64_t`，
#      而 JNI 的 `jlong` 是**有符号** —— 溢出会变成一个**负数**读数。
#   ③ 两个都是 `uint64_t` 返回，**写反不报错**：类型都对、编译都过，
#      只是 `/v1/models` 上会出现"权重 4 GB / 参数 0.3 个"这种鬼读数。
#   ④ 取不到的字段回 `0`（而不是空）—— 客户端分不清"读不到"与"它真是 0"，
#      而"0 层"是个不存在的东西。
#   ⑤ `/v1/models/{id}` 的路由排在 `/v1/models` **之后** —— 前缀先命中，
#      客户端拿到的是一份**列表**而不是它要的那个对象；列表本身是合法 JSON，
#      SDK 只会解成"字段缺失"，报出来的错与"模型不存在"同形。
#   ⑥ 列表与单查的**字段名分叉** —— 两边都是合法 JSON、谁都不报错，
#      客户端看到的是"列表里有 profile、单查没有"。
#   ⑦ 给**别的**模型挂上当前模型的档案 —— 库同一时刻只加载一份权重，
#      挂错等于把一个模型的读数说成另一个模型的。
#   ⑧ 日志行在**加载失败**时也报 —— S.model 已清空，报出来的空档案会被读成
#      "这个模型没有档案"，而实际是"这次没加载成"。
#
# 所以钉的是**结构与同源**，不是"文件里出现过 profile 这个词"：
#   ① 每个数各自判"取不到"，且判据取 `>=`（等长也可能是截断）；
#   ② uint64 → jlong 就地夹到 INT64_MAX（宁可要看得见的错）；
#   ③ 两个 uint64 字段的**下标顺序**，以及 Kotlin 侧解包的同一个顺序；
#   ④ 路由的**行号关系**：单查必须排在列表之前；
#   ⑤ 列表与单查共用同一批字段名（逐字段列表由两处共享的字符串集合比对）；
#   ⑥ 档案只挂在当前加载模型那一条上；
#   ⑦ 档案与 `[模型档案]` 日志只在加载成功时报。
#
# 运行：sh tools/run_models_profile_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
PROF=app/src/main/java/com/xiaowan/localinference/ModelProfile.kt
LFS=app/src/main/java/com/xiaowan/localinference/LogFileStore.kt
AUTH=app/src/main/java/com/xiaowan/localinference/ApiAuth.kt
HDR=app/src/main/cpp/include/llama.h

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"
c "HttpApi.kt 存在"    "[ -f \$HTTP ]"
c "ModelProfile.kt 存在" "[ -f \$PROF ]"

# ── 1) 头文件侧的接口确实存在（判据的**前提**，不是"符号名在文件里出现"）────
# 少了 `llama_model_meta_count` 就等于"条数"这个字段根本取不到，而那种情况
# 在 Kotlin 侧只会表现为一个 null —— 与"这个模型没有元数据"同形。
c "llama.h 里有 llama_model_meta_val_str（写入式取值）" \
  "grep -q 'int32_t llama_model_meta_val_str(const struct llama_model' \$HDR"
c "llama.h 里有 llama_model_meta_count" \
  "grep -q 'int32_t llama_model_meta_count(const struct llama_model' \$HDR"
c "llama.h 里有 llama_model_size（声明为 uint64_t）" \
  "grep -q 'uint64_t llama_model_size(const struct llama_model' \$HDR"
c "llama.h 里有 llama_model_n_params（声明为 uint64_t）" \
  "grep -q 'uint64_t llama_model_n_params(const struct llama_model' \$HDR"

# ── 2) 取数口只有一个，且逐字段判"取不到" ──────────────────────────────────
# 头文件自己写着「returns the length of the string on success, or -1 on failure」，
# 但**没写**"返回值可以 ≥ buf_size"。所以判据必须取 `>=`：
#   · 只判 `< 0` 的话，截断（返回值 == buf_size）会被当成成功，回一个半截值；
#   · 判 `> 0` 更是恒真（正常取值永远 > 0）。
c "取值判据是「负 = 失败」与「>= buffer 大小 = 截断」两半" \
  "grep -q 'if (n < 0) return nullptr' \$JNI && grep -q 'if (n >= (int) sizeof(buf)) return nullptr' \$JNI"
c "截断按「读不到」处理（不给半截值）" \
  "grep -q '截断了 —— 当\"读不到\"，不回半截值' \$JNI"
c "空值也按「读不到」处理（不回空串去污染档案行）" \
  "grep -q 'return buf\[0\] ? buf : nullptr' \$JNI"
c "value_str 的缓冲是一处定义（不散落多个尺寸各异的栈数组）" \
  "grep -q 'static char buf\[8192\]' \$JNI"

c "数组初值是真实空串、且**只造一个** local ref（不是 8 个、不是 null）" \
  "python3 - \$JNI <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('Java_com_xiaowan_localinference_LlmEngine_nativeModelMeta')
j = s.index('auto put = ', i)
b = s[i:j]
code = [l.strip() for l in b.splitlines()
        if l.strip() and not l.strip().startswith('//')]
flat = ' '.join(code)
# ① 只造一个空串；② 用它当 NewObjectArray 的初值；③ 用完删掉；④ DeleteLocalRef(strCls)
sys.exit(0 if flat.count('NewStringUTF(\"\")') == 1
         and 'NewObjectArray(kFields, strCls, empty)' in flat
         and 'DeleteLocalRef(empty)' in flat
         and 'DeleteLocalRef(strCls)' in flat else 1)
PY"
c "数组初值不是 nullptr（null 元素会让 Kotlin 侧 Array<String> 解包处 NPE）" \
  "! grep -q 'NewObjectArray(kFields, nullptr' \$JNI"

# ── 3) uint64 -> jlong 必须就地夹住（溢出会变成**负数**读数）───────────────
# 这根判据是"负数是看得见的错、截断是看不见的错"那条取舍的落点：
# 允许夹（数不准），但不允许溢出成一个合法类型的负值。
c "uint64 落进 jlong 前夹到 INT64_MAX" \
  "grep -q 'if (v > (uint64_t) INT64_MAX) v = (uint64_t) INT64_MAX' \$JNI"
c "逐个字段判「取不到」的 put 口存在（put / put_u64 两个）" \
  "grep -q 'auto put = \[&\]' \$JNI && grep -q 'auto put_u64 = \[&\]' \$JNI"
c "未加载模型时返回 8 个空串（不是 null、不是 0）" \
  "grep -q 'if (!S.model) return out;' \$JNI"
c "字段数是 8，且注释点名它是接口" \
  "grep -q 'const int kFields = 8;' \$JNI && grep -q '取值顺序\*\*是接口' \$JNI"

# ── 4) 两个 uint64 返回值的**下标顺序**（写反不报错，只会给出鬼读数）────────
# 这条锚的是"哪个写在前面"，不锚"出现过 llama_model_size"——
# 后者在函数里恒真，改成反过来也一样绿。
c "下标 0 是权重字节（llama_model_size），下标 1 是参数个数（n_params）" \
  "python3 - \$JNI <<'PY'
import sys, re
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('Java_com_xiaowan_localinference_LlmEngine_nativeModelMeta')
j = s.index('\n}\n', i)
body = s[i:j]
code = [l.strip() for l in body.splitlines()
        if l.strip() and not l.strip().startswith('//')]
p0 = next(k for k, l in enumerate(code) if l.startswith('put_u64(0,'))
p1 = next(k for k, l in enumerate(code) if l.startswith('put_u64(1,'))
sys.exit(0 if ('llama_model_size' in code[p0] and 'llama_model_n_params' in code[p1]) else 1)
PY"
c "Kotlin 侧的解析顺序与 native 同一个 8" \
  "grep -q 'tensorBytes = l(0)' \$PROF && grep -q 'paramCount = l(1)' \$PROF && grep -q 'metaCount = n(7)' \$PROF"
c "两边各写了一次「8」（跨版本兜底的前提：顺序是接口）" \
  "[ \$(grep -c 'kFields = 8' \$JNI) -eq 1 ] && grep -q 'String\[8\]' \$PROF"

# ── 5) 取不到 = null，不是 0 ───────────────────────────────────────────────
c "ModelProfile 的每个字段都是可空的（取不到就是 null）" \
  "python3 - \$PROF <<'PY'
import sys, re
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('data class ModelProfile(')
j = s.index('\n)', i)
b = s[i:j]
for f in ('tensorBytes', 'paramCount', 'name', 'ctxTrain',
          'architecture', 'vocabSize', 'fileType', 'metaCount'):
    if not re.search(r'val %s: [^\\n]*\\?' % f, b):
        sys.exit(1)
sys.exit(0)
PY"
c "取不到就是 null，不是 0（8 个字段**每个**都过共用解析口）" \
  "python3 tools/models_profile/check_null_not_zero.py \$PROF"
c "解析口做短数组兜底 + 把空串当「取不到」（跨版本：新 Kotlin 撞旧 .so 不许崩）" \
  "grep -q 'a?.getOrNull(i)?.takeIf { it.isNotEmpty() }' \$PROF"

# ── 6) 路由顺序：单查必须排在列表**之前**（行号关系，不靠肉眼）─────────────
c "/v1/models/{id} 的路由排在 /v1/models 列表之前" \
  "python3 - \$HTTP <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('req.method == \"GET\" && pathUnder(req.path, \"/v1/models/\")')
j = s.index('pathUnder(req.path, \"/v1/models\") ->')
sys.exit(0 if i < j else 1)
PY"
c "单查分支用 pathUnder(路径, \"/v1/models/\")（带分隔符，不是裸前缀）" \
  "grep -q 'pathUnder(req.path, \"/v1/models/\") ->' \$HTTP"
c "id 取口做了百分号解码（中文别名/空格文件名会被客户端编码）" \
  "grep -q 'java.net.URLDecoder.decode(raw, \"UTF-8\")' \$HTTP"
c "解码失败按原样使用（不抛 -> 不把 404 变成 500）" \
  "python3 - \$HTTP <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun idFromModelsPath(')
j = s.index('\n    }', i)
b = s[i:j]
sys.exit(0 if 'catch (_: Exception)' in b and 'raw' in b else 1)
PY"
c "id 取口切掉 query/fragment（pathUnder 会命中带 ? 的路径）" \
  "grep -q \"substringBefore('?').substringBefore('#')\" \$HTTP"

# ── 7) 列表与单查共用同一批字段名（分叉了就是「列表有、单查没有」）─────────
# 判据不是"两处都出现过 tensor_bytes"——那种写法在改名时只改一处照样绿。
# 这里把两处出现的 profile 字段名集合**取出来比**，并要求就是这 8 个。
c "两处的 profile 字段名集合**完全相同**，且就是那 8 个" \
  "python3 tools/models_profile/check_field_names.py \$HTTP"
c "单查复用同一个「命中判据」（拿别名逐个比，不另写一套 stat 判据）" \
  "grep -q 'if (alias != id) continue' \$HTTP"
c "单查的兜底与列表同一口径（列表里出现过的 id 一定查得到）" \
  "grep -q 'if (currentModel == id) return entry(id, null, null)' \$HTTP"

# ── 8) 档案只挂在**当前加载**的那一条上 ────────────────────────────────────
# 库同一时刻只加载一份权重（native 侧 `Session S` 只有一个）。挂到别的条目上
# 等于把"这一个模型"的读数说成"那个模型"的，而两边都是合法 JSON。
c "列表里只有当前加载那一条挂档案（判据是别名与 currentModel 相等）" \
  "grep -q 'if (cur != null && alias == cur) attachProfile(d)' \$HTTP"
# 判据锚的是**与 `currentModel` 同源**这件事，而不是某个形参名：
# 形参名会被 `run_http_scope_order.py` 的盲区逼着改（`alias` 与下面 for 里的
# 局部变量同名会误报），所以这里取两侧（函数体内取 `val x = currentModel`
# 与 profile 分支里比的那个名字），要求它们相等且标签不是写死的。
c "单查也要求「档案只挂在当前加载那一个」上" \
  "python3 tools/models_profile/check_single_is_current.py \$HTTP"
c "降级分支（拿不到模型库）也挂了档案" \
  "grep -q 'attachProfile(d)' \$HTTP"
c "别名解析走 ModelStore.aliasOf（不另用 ModelStore.Row.alias 那批缓存值）" \
  "grep -q 'val alias = ModelStore.aliasOf(dbg, f.name)' \$HTTP"

# ── 9) 档案不可用时不产出空的 profile 对象 ────────────────────────────────
# 空的 `"profile": {}` 与"没有 profile"在客户端是两个意思：前者会让 SDK 认为
# 字段存在但全是 null，后者才是"这台服务不提供档案"。
c "档案不可用时**不写** profile 字段（不是写一个空对象）" \
  "grep -q 'if (!profile.available) return' \$HTTP"

# ── 10) `[模型档案]` 日志：只在加载成功时报，且不是错误 ────────────────────
c "日志只在加载成功（err == null）时报" \
  "python3 - \$ENG <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('if (err == null) {')
j = s.index('TAG_PROFILE', i)
sys.exit(0 if j - i < 400 else 1)
PY"
c "档案读不到就不报（不是报一行空的 [模型档案]）" \
  "grep -q 'if (prof.available) uiLog' \$ENG"
c "标签常量在 LogFileStore（与其它日志标签同一处定义）" \
  "grep -q 'const val TAG_PROFILE = \"\[模型档案\]\"' \$LFS"
c "日志行走 LogFileStore.TAG_PROFILE 常量（不另抄字面量）" \
  "grep -q 'uiLog(\"\${LogFileStore.TAG_PROFILE} \${prof.describe()}\")' \$ENG"
c "档案行只说读到的字段（缺的字段不出现，不写占位符）" \
  "python3 - \$PROF <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('fun describe(): String {')
j = s.index('\n    }', i)
b = s[i:j]
# 每个字段都必须经 ?.let 才进 parts（写死 \"?\" / 0 的占位符会漏这条）
sys.exit(0 if b.count('?.let { parts +=') == 8 else 1)
PY"
c "describe 里没有问号占位符（\`[模型档案]\` 要能与 [mmap释放] 对账）" \
  "! grep -q 'parts += \"?\"' \$PROF"

# ── 11) 档案**不缓存**（缓存 = 要回答"换模型时怎么失效"，那是静默 bug 的温床）─
c "modelProfile() 每次直读 native，不带 memo 字段" \
  "python3 tools/models_profile/check_no_cache.py \$ENG"
c "nativeModelMeta 是 @JvmStatic external（不是 Kotlin 侧仿造一份）" \
  "grep -q 'external fun nativeModelMeta(): Array<String>' \$ENG"

# ── 12) 免鉴权面没有被这次新增端点悄悄放大 ────────────────────────────────
# 新增的是 `/v1/models/...`，落在 `/v1/models` 之下 → 按现有白名单**仍免鉴权**
#（与模型下拉框同一理由：只读、无生成能力）。但**前提**是判据是路径级而不是
# 裸前缀 —— `/v1/models-evil` 必须仍要 token（它落在 /v1/ 命名空间里）。
c "ApiAuth 的 under 是路径级判定（裸前缀会把免鉴权集合放大）" \
  "grep -q 'path.startsWith(\"\$base/\") || path.startsWith(\"\$base?\")' \$AUTH"
c "免鉴权的判据里没有为本次新增端点写第二处特例" \
  "[ \$(grep -c 'under(path, \"/v1/models\")' \$AUTH) -eq 1 ]"

if [ "$bad" -eq 0 ]; then
    echo "=== 模型档案守卫全部通过（$ok 条）==="
    exit 0
else
    echo "=== 模型档案守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
