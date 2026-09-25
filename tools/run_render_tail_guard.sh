#!/bin/sh
# 「渲染出口的长度段与发出串同源」＋「尾窗口判据本体唯一、两侧同单位」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 模块 F 的两条都是**静默错位**：不崩、不报错、HTTP 200，只是切点偏了。
#
#   F-1 `new_rendered_prompt` 用 `utf16_len(genSuffix)` 写长度段，却把同一串交给
#       `new_string_utf8_safe` 发出。两个函数**各自实现**的解码器对非法序列算出
#       不同的 code unit 数（overlong `C0 80`：1 vs 2；CESU-8：1 vs 3）。
#       合法 UTF-8 下恒等 —— 所以真机常见模型（后缀纯 ASCII）全无症状，
#       只有第三方 GGUF 模型自带的模板里含非法字节时才错位。
#       宿主侧 50 万例随机字节实测 35.7 万处不一致。
#
#   F-2 尾窗口判据原先有**三份**各自实现（`llama_jni.cpp` / `RenderedPrompt` /
#       `ThinkingControl`）。三处都叫"256 尾窗口"，但 C++ 取 256 **字节**、
#       另两处取 256 个 **UTF-16 code unit**（256 字节 ≈ 85 个汉字 → 覆盖范围差 3 倍）。
#       现有用例全是 ASCII 后缀 → 两种窗口覆盖同一段 → 分叉了却全绿，
#       镜像给出的正是"判据没漂"的**假信心**。
#
# 所以钉的是**结构与同源性**，不是"某个常量等于 256"：
#   ① 长度段的来源必须与发出串的来源是**同一个解码器**（不许两份各自实现）；
#   ② 尾窗口判据**本体只允许一处**（`probe_util.h`，宿主可编 → 宿主测能测真实现），
#      且两侧窗口**同值同单位**（字节）；
#   ③ 上述两条在 `new_rendered_prompt` 里必须同时成立（不能只钉文件里出现过）。
#
# 运行：bash tools/run_render_tail_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
UTF8=app/src/main/cpp/utf8_safe.h
PU=app/src/main/cpp/probe_util.h
SRC=app/src/main/java/com/xiaowan/localinference
TEST=tools/render_tail/render_tail_test.cpp

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "llama_jni.cpp 存在"      "[ -f \$JNI ]"
c "utf8_safe.h 存在"        "[ -f \$UTF8 ]"
c "probe_util.h 存在"       "[ -f \$PU ]"
c "RenderedPrompt.kt 存在"  "[ -f \$SRC/RenderedPrompt.kt ]"
c "ThinkStream.kt 存在"     "[ -f \$SRC/ThinkStream.kt ]"
c "ThinkingControl.kt 存在" "[ -f \$SRC/ThinkingControl.kt ]"

# ── 1) F-1：长度段与发出串**同源** ─────────────────────────────────────────
# 1a. 解码器本体只允许出现在 utf8_safe.h 一处，且是可复用的具名函数。
c "utf8_safe.h 提供可复用的解码器 utf8_decode_each" \
  "grep -q 'Utf8Decode utf8_decode_each' \$UTF8"
c "utf8_safe.h 的发出函数走 utf8_decode_each（不是自己那段 while）" \
  "grep -q 'utf8_decode_each(str, len' \$UTF8"
# 1b. llama_jni.cpp 的 utf16_len 必须**调用**那个解码器，不得自带解码循环。
c "llama_jni.cpp 的 utf16_len 调用共享解码器" \
  "grep -q 'utf8_decode_each(s.data(), s.size()' \$JNI"
c "llama_jni.cpp 不再自带第二份解码循环（'i += 1; n += 1;' 已绝迹）" \
  "! grep -q 'i += 1; n += 1;' \$JNI"
c "llama_jni.cpp 不再自带第二份 overlong 判据（解码循环只在 utf8_safe.h）" \
  "! grep -q 'need == 1 && cp < 0x80u' \$JNI"
# 1c. 长度段与发出串必须在**同一个函数体**里并存（只钉"文件里都有"是恒真的）。
c "new_rendered_prompt 里同时有长度段与发出串" \
  "python3 - \$JNI <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('static jstring new_rendered_prompt(')
j = s.index('\n}\n', i)
body = s[i:j]
ok = ('utf16_len(genSuffix)' in body) and ('new_string_utf8_safe(env, out.c_str())' in body)
sys.exit(0 if ok else 1)
PY"
c "旧的双计数器写法已绝迹（utf16_len 体内不许再有 for 循环）" \
  "python3 - \$JNI <<'PY'
import sys, re
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('static size_t utf16_len(const std::string & s) {')
j = s.index('\n}\n', i)
sys.exit(1 if re.search(r'for \(', s[i:j]) else 0)
PY"

# ── 2) F-2：判据本体唯一、两侧同值同单位 ──────────────────────────────────
c "C++ 判据本体在 probe_util.h（宿主可编，与真机同一份）" \
  "grep -q 'static inline ThinkTailShape classify_think_tail' \$PU"
c "C++ 判据本体窗口常量在位" \
  "grep -q 'kThinkTailWindowBytes = 256;' \$PU"
c "C++ 判据本体写明窗口单位是字节" \
  "grep -q '单位是 UTF-8 字节' \$PU"
c "C++ 判据本体写明「原先有三份各自实现」（防止后人再拆一份出去）" \
  "grep -q '这条判据原先有三份各自实现' \$PU"
c "llama_jni.cpp 只**调用**共享判据，不自己实现" \
  "grep -q 'return classify_think_tail(prompt);' \$JNI"
c "llama_jni.cpp 不再自带第二份判据（tail.rfind(OPEN) 已绝迹）" \
  "! grep -q 'tail.rfind(OPEN)' \$JNI"
c "Kotlin 侧尾窗口常量存在" \
  "grep -q 'TAIL_WINDOW_BYTES = 256' \$SRC/ThinkStream.kt"
c "Kotlin 判据本体存在（ThinkStream.classifyTail）" \
  "grep -q 'fun classifyTail(renderedPrompt: String): TailShape' \$SRC/ThinkStream.kt"
c "Kotlin 窗口按**字节**取（toByteArray，不是 substring(length - N)）" \
  "grep -q 'toByteArray(Charsets.UTF_8)' \$SRC/ThinkStream.kt"
c "Kotlin 窗口落在字符边界上（续字节回退）" \
  "grep -q 'and 0xC0) == 0x80' \$SRC/ThinkStream.kt"
c "Kotlin 判据覆盖三档（none / openOnly / closed）" \
  "grep -q 'enum class TailShape { none, openOnly, closed }' \$SRC/ThinkStream.kt"
c "RenderedPrompt 的镜像是**转发**，不再自写一套实现" \
  "grep -q 'ThinkStream.classifyTail(renderedPrompt)' \$SRC/RenderedPrompt.kt"
c "RenderedPrompt 不得再出现旧的 UTF-16 长度窗口写法" \
  "! grep -q 'renderedPrompt.length - TAIL_WINDOW' \$SRC/RenderedPrompt.kt"
# F-2 的第三处：ThinkingControl 里还有同一判据的第三份实现（本轮一并收口）。
c "ThinkingControl 的同一判据也是**转发**（第三处收口，不得另写一套）" \
  "grep -q 'ThinkStream.classifyTail(renderedPrompt)' \$SRC/ThinkingControl.kt"
c "旧的 UTF-16 长度窗口写法在全仓绝迹（substring(length - N) 形态）" \
  "! grep -rqE 'substring\(maxOf\(0, [A-Za-z]*\.length - TAIL_WINDOW' \$SRC"
c "旧常量名 TAIL_WINDOW（无单位后缀）已绝迹" \
  "! grep -rq 'const val TAIL_WINDOW ' \$SRC"
c "宿主测直接调共享判据（不是另写一份近似）" \
  "grep -q 'classify_think_tail' \$TEST"
# 宿主测的 C++ 侧必须调**真实现**（不是自己再写一遍）；Kotlin 侧那一份是
# 有意为之的**等价物**（否则"两侧同档"就成了自证：同一份代码当然同档），
# 它由逐个用例的期望值钉住。所以这里只钉"C++ 侧不再是复刻"。
c "宿主测的 C++ 侧调真实现（不是复刻）" \
  "grep -q 'switch (classify_rendered_think_tail(prompt))' \$TEST"
c "宿主测里 C++ 侧与 Kotlin 侧是**两份**（否则同档是自证）" \
  "python3 - \$TEST <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
sys.exit(0 if (s.count('static int cpp_classify') == 1 and s.count('static int kt_classify') == 1) else 1)
PY"

# 判据本体只允许一处：Kotlin 侧只能有一个 classifyTail 的定义点。
N=$(grep -rc 'fun classifyTail(' "$SRC" | awk -F: '{s+=$2} END {print s+0}')
c "Kotlin 侧判据本体唯一（classifyTail 定义点应为 1 处，实际 $N）" "[ \"\$N\" = \"1\" ]"

# 两侧窗口数值从**各自源码**抽出后比对，不写死 —— 写死的话改一边不会红。
c "两侧尾窗口数值逐一相同（probe_util.h vs ThinkStream.kt）" \
  "python3 - \$PU \$SRC/ThinkStream.kt <<'PY'
import sys, re
hdr = open(sys.argv[1], encoding='utf-8').read()
kt  = open(sys.argv[2], encoding='utf-8').read()
m1 = re.search(r'kThinkTailWindowBytes = (\d+)', hdr)
m2 = re.search(r'TAIL_WINDOW_BYTES = (\d+)', kt)
if not m1 or not m2: sys.exit(1)
sys.exit(0 if m1.group(1) == m2.group(1) else 1)
PY"

# ── 3) 契约注释在位（防止后人"顺手改回去"） ────────────────────────────────
c "JNI 侧写明「它为什么必须与同一个解码器」" \
  "grep -q '它为什么必须与' \$JNI"
c "JNI 侧写明非法序列的两个具体例子（overlong / CESU-8）" \
  "grep -q 'CESU-8' \$JNI"
c "Kotlin 侧写明两窗口单位分叉的后果（中文 prompt 差 3 倍）" \
  "grep -q '85 个汉字' \$SRC/ThinkStream.kt"
c "RenderedPrompt 的镜像注释写明「只做转发」" \
  "grep -q '只做转发' \$SRC/RenderedPrompt.kt"

if [ "$bad" -eq 0 ]; then
    echo "=== 渲染尾窗口/长度段守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 渲染尾窗口/长度段守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
