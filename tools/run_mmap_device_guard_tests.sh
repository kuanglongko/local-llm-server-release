#!/bin/sh
# run_mmap_device_guard.sh 的自测：**每条判据都要能红**，且红在真的坏形态上。
#
# 为什么必须有这一条：守卫失效的形态是"全绿"，而全绿与"真的通过了"同形。
# 这里把本轮修复逐条打桩成**它自己最可能的坏法**，逐条跑守卫并期望变红；
# 最后换回真源码，期望全绿 —— 否则判据可能只是"讨厌某种写法"。
#
# 桩都取自真实历史形态或真机上真的会发生的退化：
#   · 照抄一份设备名名单（库升级/变体变化即失效，正是本判据存在的理由）；
#   · 只看 buffer_from_host_ptr、不看默认 buft 是不是 host（会误判成"能承接"）；
#   · "一台都没有"静默（恰恰是"内存必然 ≈ 模型大小"的现场被吞掉）；
#   · 诊断挪到加载之后（加载前已知的结论与加载后的读数再也对不上）；
#   · 恢复"复制到应用内"那句换不到省内存的建议；
#   · repack 档位只加日志、`mp.use_extra_bufts` 没真落值（第六条判据专打这个）；
#   · repack 档位日志挪到加载之后（加载后多出的那份匿名拷贝再也认不出来）。
#
# 运行：sh tools/run_mmap_device_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
chk() {
    name="$1"; want="$2"
    if sh tools/run_mmap_device_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=/tmp/mmap_device_stash
rm -rf "$STASH"; mkdir -p "$STASH"
cp "$JNI" "$STASH/llama_jni.cpp"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; }
trap restore EXIT

stub() {
    name="$1"; want="$2"; prog="$3"
    cp "$STASH/llama_jni.cpp" "$JNI"
    printf '%s\n' "$prog" | python3 - "$JNI"
    chk "$name" "$want"
}

# ① 照抄设备名名单，不再读库的能力位（库升级/换变体即静默失效）
stub "改用写死的设备名名单（不读能力位）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "                const bool can_host_ptr = props.caps.buffer_from_host_ptr;"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "                const bool can_host_ptr = (nm && strncmp(nm, \"CPU\", 3) == 0);", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ② 只信 buffer_from_host_ptr，不看默认 buft 是否 host（两处都看才不出错）
stub "丢掉『默认 buft 是不是 host』这一半（会误判）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "                default_is_host = lm_ggml_backend_buft_is_host(bt);"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "                default_is_host = true; (void) bt;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ②-2 分类不再报默认 buft 名（逐组不可观测，用户又要从设备名猜组）
stub "分类丢掉默认 buft 名（逐组不可观测）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        buft_name = lm_ggml_backend_buft_name(bt);"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "        buft_name = \"?\"; (void) bt;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ②-3 分类丢掉 host 标记（只报 buft 名，仍看不出该组走哪条路）
stub "分类丢掉 host 标记（看不出该组走哪条路）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        bool default_is_host = false;"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "        const bool default_is_host = false;", 1)
s = s.replace("                default_is_host = lm_ggml_backend_buft_is_host(bt);\n",
              "                (void) bt;\n", 1)
# 把逐台输出里的 host 标记去掉（这才是"报不出来"）
old2 = "                          nm ? nm : \"?\", buft_name ? buft_name : \"无\",\n                          default_is_host ? \"是\" : \"否\", mmap_dev_class_name(cls));"
assert old2 in s, "源码形状变了，本自测要一起更新"
s = s.replace(old2, "                          nm ? nm : \"?\", buft_name ? buft_name : \"无\","
                     "\n                          \"-\", mmap_dev_class_name(cls));", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ③ "一台都没有"静默（把"内存必然 ≈ 模型大小"的现场吞掉）
# 锚的是 log_mmap_device_diag **函数体自身**的结尾（`    }\n}`），
# 而不是"它后面紧跟的那个 struct" —— 后者会在本文件被插入新函数时失效，
# 让自测自己变成假绿/报错（本轮就踩到了：M4 的钉池函数插在同位置之后）。
stub "『池内无设备可承接』静默不报" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
fn_start = s.index("static void log_mmap_device_diag()")
# 函数体结束 = 从这里起第一处 "    }\n}" —— 只可能在 log_mmap_device_diag 里。
tail = "    }\n}"
j = s.index(tail, fn_start)
i = s.index("    } else {\n", fn_start)
s = s[:i] + s[i + len("    } else {\n"):j] + s[j + len(tail):]
io.open(p, "w", encoding="utf-8").write(s)
'

# ④ 诊断挪到加载之后（加载前已知的结论与加载后的读数再也对不上）
stub "诊断挪到 load_from_file 之后（顺序写反）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
call = "    log_mmap_device_diag();\n"
assert call in s
s = s.replace(call, "", 1)
anchor = "    if (!S.model) { LOGE(\"model load failed: %s\", modelPath.c_str()); return JNI_FALSE; }\n"
assert anchor in s
s = s.replace(anchor, anchor + call, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑤ 恢复"复制到应用内"那句换不到省内存的建议
stub "恢复『请复制到应用内』（换不到省内存的建议）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "判据看上一行的 [mmap释放] 读数：RSS 真落下来即共享。"
assert old in s
s = s.replace(old, "要真正省内存，请把模型「复制到应用内」后重新加载。", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑥ 加载日志丢掉 gpu_layers（分组与 gpu_layers 无关这一事实又变成要猜的）
stub "加载日志丢掉 gpu_layers" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "gpu_layers=%d"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "", 1)
old2 = "mp.load_mode, (int) nGpuLayers, modelPath.c_str());"
assert old2 in s
s = s.replace(old2, "mp.load_mode, modelPath.c_str());", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦ repack 档位：只加日志不算，必须真**显式落值**（同 load_mode 那一课）
stub "mp.use_extra_bufts 没落值（只加了日志）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "    mp.use_extra_bufts = model_use_extra_bufts();\n"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "", 1)
# 只留日志里的字样（不剥字符串字面量的话，这条会假绿）
old2 = "jlog(\"[repack] use_extra_bufts=%d（来源：%s）"
assert old2 in s
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦-2 repack 档位日志挪到加载之后（加载后的 RSS 多出一份，认不出是 repack）
stub "repack 档位日志挪到 load_from_file 之后" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
call = "    log_extra_bufts_diag();\n"
assert call in s, "源码形状变了，本自测要一起更新"
s = s.replace(call, "", 1)
anchor = "    if (!S.model) { LOGE(\"model load failed: %s\", modelPath.c_str()); return JNI_FALSE; }\n"
assert anchor in s
s = s.replace(anchor, anchor + call, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦-3 档位不再区分来源（声明与使用两处一起删 —— 只改一处会漏成假绿）
stub "档位日志不再报来源（三级读数只留数字）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
i = s.index("    jlog(\"[repack] use_extra_bufts=%d（来源：%s）")
j = s.index("\n}\n", i)
s = s[:i] + "    jlog(\"[repack] use_extra_bufts=%d\", on ? 1 : 0);" + s[j:]
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦-5 判定退回"只读系统属性"（本版被真机否掉的那条链）
stub "档位判定退回只读系统属性（自有入口的值不参与）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
i = s.index("static bool model_use_extra_bufts() {")
j = s.index("\n}\n", i) + 3
body = ("static bool model_use_extra_bufts() {\n"
        "    char v[32] = {0};\n"
        "    if (!probe_getprop(kPropExtraBufts, v, sizeof(v))) return true;\n"
        "    for (const char * const * p = kProbeOffValues; *p; ++p) {\n"
        "        if (strcmp(v, *p) == 0) return false;\n"
        "    }\n"
        "    return true;\n"
        "}\n\n")
s = s[:i] + body + s[j:]
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦-6 三种来源不再可区分（"-1 当哨兵"被抹掉）
stub "『没设过』与『显式 0』合并（-1 哨兵被抹掉）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "    g_extra_bufts_ui = (mode == 0 || mode == 1) ? (int) mode : -1;"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "    g_extra_bufts_ui = (mode == 1) ? 1 : 0;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦-7 属性被抬回唯一通道（排在自有档位之前）
stub "属性被抬回唯一通道（排在自有档位判定之前）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "    if (g_extra_bufts_ui == 0) return false;\n    if (g_extra_bufts_ui == 1) return true;\n"
assert old in s, "源码形状变了，本自测要一起更新"
i = s.index("static bool model_use_extra_bufts() {")
s = s[:i] + s[i:].replace(old, "", 1).replace("static bool model_use_extra_bufts() {\n",
        "static bool model_use_extra_bufts() {\n" + old, 1) if False else s
# 直接把自有档位判定整段删掉：属性随即成为唯一通道
s2 = io.open(p, encoding="utf-8").read()
i2 = s2.index("static bool model_use_extra_bufts() {")
s2 = s2[:i2] + s2[i2:].replace(old, "", 1)
io.open(p, "w", encoding="utf-8").write(s2)
'

# ⑦-8 推理期 RSS：采样函数只定义不调用（"量了"其实没量）
stub "推理期 RSS 采样只定义不调用" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "    rss_note_peak();\n"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "    (void) 0;\n", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦-9 峰值不再复位（换模型后继承上一个模型的数 —— 对账失效）
stub "峰值跨模型不复位（读数与这次加载对不上）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "    g_rss_peak_kb = LM_RSS_PEAK_RESET;\n"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦-4 档位日志丢掉因果说明（读日志的人看不出这份拷贝是什么）
# 桩把**整条 jlog 调用**换掉，而不是只删"与映射并存"那半句字面量 ——
# 判据看的是 `use_extra_bufts=%d` 这一行是否还在，只删半句会漏。
stub "repack 档位日志只报数字、不报因果" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
i = s.index("    jlog(\"[repack] use_extra_bufts=%d")
j = s.index("\n\n", i)
s = s[:i] + "    jlog(\"[repack] 已读取档位\");" + s[j:]
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑩ JNI 导出丢了 C 链接 —— 0.9.125 装机实测的根因形态：定义被写在
# 打桩方式是**把主名那一句定义摘出 extern 块**（其余导出仍在块内），
# 正是真机上「大部分 JNI 正常、只有这一个符号找不到」的形态。
stub "JNI 导出 nativeSetRepack 掉出 extern \"C\" 块（符号被重整）" red '
import io, sys
p = sys.argv[1]
s = io.open(p, encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).read()
i = s.find(chr(101)+chr(120)+chr(116)+chr(101)+chr(114)+chr(110))
j = s.find(chr(109)+chr(97)+chr(114)+chr(107), i) + 4
block = s[i:j]
fn = chr(74)+chr(97)+chr(118)+chr(97)+chr(95) + "com_xiaowan_localinference_LlmEngine_nativeSetRepack"
assert fn in block
keep = block.replace(fn, chr(32), 1)
s = s[:i] + keep + s[j:]
s = s + chr(10) + fn + "JNIEnv jclass jint mode return" + chr(10)
io.open(p, "w").write(s)
'

# ⑪-1 落值不记账（判定 → 落值 的中间断开处又变回不可观测）
stub "mp.use_extra_bufts 的落值没有记账（判定与落值对不上）" red '
import io, sys
p = sys.argv[1]
s = io.open(p, encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).read()
old = "g_extra_bufts_applied = mp.use_extra_bufts"
assert old in s
s = s.replace(old, chr(0), 1)
io.open(p, "w").write(s)
'

# ⑪-2 结果行挪到加载**前**（那就只剩判定，实测增量没有意义）
stub "repack 结果行挪到加载之前（实测增量失效）" red '
import io, sys
p = sys.argv[1]
s = io.open(p, encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).read()
call = "    log_repack_applied()"
assert call in s
s = s.replace(call, chr(32)+chr(32), 1)
anchor = "    log_extra_bufts_diag()"
assert anchor in s
s = s.replace(anchor, call + chr(10) + anchor, 1)
io.open(p, "w").write(s)
'

# ⑪-3 增量写死成 0（『量不到』与『真的是 0』又同形）
stub "加载增量改成写死的 0（量不到与真的没建同形）" red '
import io, sys
p = sys.argv[1]
s = io.open(p, encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).read()
old = "g_repack_bytes = (after - g_repack_rss_before_kb)"
assert old in s
s = s.replace(old, "g_repack_bytes = (" + chr(48)+chr(32)+chr(45)+chr(32)+chr(48), 1)
io.open(p, "w").write(s)
'

# ⑪-4 旧名别名被删（跨版本覆盖安装的 ABI 兜底没了）
stub "删掉旧名别名 nativeSetExtraBufts（覆盖安装失去兜底）" red '
import io, sys
p = sys.argv[1]
s = io.open(p, encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).read()
lines = s.split(chr(10))
fn = chr(74)+chr(97)+chr(118)+chr(97)+chr(95) + "com_xiaowan_localinference_LlmEngine_nativeSetExtraBufts(JNIEnv *, jclass, jint mode) {"
hit = [k for k, l in enumerate(lines) if fn in l]
assert len(hit) == 1, hit
del lines[hit[0]]
s = chr(10).join(lines)
io.open(p, "w").write(s)
'

# ⑪-5 落值位被自己的重置语句冲掉 —— 0.9.128 真机现场（用户报"修复日志显示错误"）。
# 桩把重置挪**回** diag 里（漏掉时最自然的写法），落值随后的赋值被它清掉。
stub "落值位被重置语句冲掉（[repack结果] 恒为 -1，0.9.128 现场）" red '
import io, sys
p = sys.argv[1]
s = io.open(p, encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).read()
anchor = chr(32)*4 + chr(103) + "_repack_bytes = -1;"
assert anchor in s, "diag 函数体形状变了"
i = s.index(anchor)
ins = chr(32)*4 + "g_extra_bufts_applied = -1;" + chr(10)
s = s[:i] + ins + s[i:]
io.open(p, "w", encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).write(s)
'

# ⑪-6 哨兵重置排在落值**之后**（顺序反了，同样把落值冲掉）
stub "哨兵重置排在 mp.use_extra_bufts 赋值之后（顺序反了）" red '
import io, sys
p = sys.argv[1]
enc = chr(117)+chr(116)+chr(102)+chr(45)+chr(56)
s = io.open(p, encoding=enc).read()
reset = chr(32)*4 + "g_extra_bufts_applied = -1;" + chr(10)
assert reset in s, "重置语句形状变了"
s = s.replace(reset, "", 1)
applied = chr(32)*4 + "g_extra_bufts_applied = mp.use_extra_bufts ? 1 : 0;" + chr(10)
assert applied in s, "落值语句形状变了"
s = s.replace(applied, applied + reset, 1)
io.open(p, "w", encoding=enc).write(s)
'

# ⑪-7 加载后那行不再读落值位（自证点被拆掉，重置掉了也看不出来）
stub "结果行不再读落值位（落值被冲掉也无人察觉）" red '
import io, sys
p = sys.argv[1]
s = io.open(p, encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).read()
old = chr(32)*9 + "g_extra_bufts_applied,"
assert old in s, "结果行实参形状变了"
s = s.replace(old, chr(32)*9 + "g_extra_bufts_prop_raw,", 1)
io.open(p, "w", encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)).write(s)
'

# ⑧ 反向：真源码（本轮修复后的写法）必须全绿
stub "修复后的写法（反向对照）" green '
import sys
'

if [ "$bad" -eq 0 ]; then
    echo "=== mmap 权重落点守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== mmap 权重落点守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
