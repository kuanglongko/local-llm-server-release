#!/bin/sh
# 自测 `tools/run_schema_sampler_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据分两类，两类各有自欺模式：
#
#   A. 存在性断言（`grep -q '某段源码'`）：pattern 写成到处都有的文本就恒真 ——
#      例如只 grep `catch` 而不断言"**每一跳**都 catch 了"，那把某一跳的 catch
#      删掉照样 PASS。（桩②③④ 就是在测这个。）
#   B. 顺序/计数断言（行号比较、`grep -c` 等于常数）：写得不够具体也会漂 ——
#      例如把 attach 调到选择器之后（桩⑤）、或只给一个端点接上（桩⑥）。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：就地改真源码成故障版本，守卫必须变红并指出是哪一条；
# 再改回来，必须恢复全绿。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_kv_cache_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_schema_sampler_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_schema_sampler_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
PURE=app/src/main/java/com/xiaowan/localinference/JsonSchemaFormat.kt
SRC_RP=app/src/main/java/com/xiaowan/localinference/RenderedPrompt.kt
PROBE=app/src/main/cpp/probe_util.h
CI=.cnb.yml
RLP=tools/root_literal_probe_test.cpp

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$KT" "$TMP/kt.bak"; cp "$HTTP" "$TMP/http.bak"
cp "$PURE" "$TMP/pure.bak"; cp "$GUARD" "$TMP/guard.bak"; cp "$SRC_RP" "$TMP/rp.bak"
cp "$PROBE" "$TMP/probe.bak"; cp "$CI" "$TMP/ci.bak"
# ⚠ RLP 必须在还原集里：桩53 会改它，而它之前不在 restore/reset_src 的名单里 ——
#   结果是"桩还原了、单测文件没还原"，下一次运行的**基线**就带上了上一次桩的改动
#   （自测报"基线：未改动源码树：守卫全绿（实际红了）"，而原因与被测代码无关）。
cp "$RLP" "$TMP/rlp.bak"
restore() {
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"; cp "$TMP/http.bak" "$HTTP"
    cp "$TMP/pure.bak" "$PURE"; cp "$TMP/guard.bak" "$GUARD"; cp "$TMP/rp.bak" "$SRC_RP"
    cp "$TMP/probe.bak" "$PROBE"; cp "$TMP/ci.bak" "$CI"
    cp "$TMP/rlp.bak" "$RLP"
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
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/kt.bak" "$KT"; cp "$TMP/http.bak" "$HTTP"
    cp "$TMP/pure.bak" "$PURE"; cp "$TMP/guard.bak" "$GUARD"; cp "$TMP/rp.bak" "$SRC_RP"
    cp "$TMP/probe.bak" "$PROBE"; cp "$TMP/ci.bak" "$CI"; cp "$TMP/rlp.bak" "$RLP"
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
    name=$1
    if run_guard; then ck "$name：守卫全绿" 1
    else ck "$name：守卫全绿（实际红了）" 0; sed -n '1,60p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 1. 桩①：schema 为 null 也挂采样器（改掉所有既有请求的输出分布）────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (!sg.gbnf.empty())
                attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,
                                       sg.pegLiteral.c_str());"""
new = """            attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,
                                   sg.pegLiteral.c_str());"""
assert s.count(old) == 1, "桩①：没找到调用点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（无条件挂 grammar）" "调用点先判 gbnf 非空才挂采样器"
reset_src

# ── 2. 桩②：取 GBNF 那一跳去掉 catch（异常穿 JNI = SIGABRT）──────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    } catch (const std::exception & e) {
        // 这一条是**正常的能力边界**，不是代码 bug：schema 写了库不支持的写法。"""
new = """    } catch (int) {
        // 桩：吃掉 exception 分支
        // 这一条是**正常的能力边界**，不是代码 bug：schema 写了库不支持的写法。"""
assert s.count(old) == 1, "桩②：没找到 templates_apply 的 catch"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（templates_apply 无 std::exception catch）" "取 GBNF 的那一跳（templates_apply）就地 catch"
reset_src

# ── 3. 桩③：编译 GBNF 那一跳去掉 catch ────────────────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    } catch (const std::exception & e) {
        jp("[schema] llama_sampler_init_grammar 抛异常: %s -> 降级为无约束\\n", e.what());"""
new = """    } catch (int) {
        jp("[schema] llama_sampler_init_grammar 抛异常: %s -> 降级为无约束\\n", 0);"""
assert s.count(old) == 1, "桩③：没找到 sampler_init_grammar 的 catch"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（init_grammar 无 std::exception catch）" "编译 GBNF 的那一跳（llama_sampler_init_grammar）就地 catch"
reset_src

# ── 4. 桩④：失败时不再降级留痕（变成静默失效）──────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''        jp("[schema] 模板未产出 grammar（该模板可能不支持 json_schema）-> 降级为无约束\\n");
        jlog("[schema] 模板未产出 GBNF，已降级为无约束采样");'''
new = '''        jp("[schema] 模板未产出 grammar -> 静默\\n");'''
assert s.count(old) == 1, "桩④：没找到空 GBNF 的降级留痕"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（空 GBNF 静默降级）" "空 GBNF 明确降级并留痕"
reset_src

# ── 5. 桩⑤：把 grammar 插到所有选择器之后（等于不生效）──────────────────
"$PY" - "$JNI" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
block = """    {
        const char * sc = jschema ? env->GetStringUTFChars(jschema, nullptr) : nullptr;
        const char * tm = jtmpl ? env->GetStringUTFChars(jtmpl, nullptr) : nullptr;
        if (sc) {
            // 生成后缀（渲染侧给出）：null = 无 / 未知 -> 退回旧口径（见 gbnf_from_json_schema）。
            // 传空串是**有意义**的：调用方明确说"这一轮没有生成后缀"。
            const char * gp = jgenPrompt ? env->GetStringUTFChars(jgenPrompt, nullptr) : nullptr;
            // 两步分开：先让模板引擎把 schema 变成 GBNF，再把 GBNF 编成采样器。
            // 任一步失败都只记录、不失败请求（见 gbnf_from_json_schema 的判据 ②）。
            const schema_grammar_result sg =
                gbnf_from_json_schema(sc, tm, gp, jthinkingOn == JNI_TRUE);
            // 第四个实参：**真后缀**（= 渲染侧交回的那段 cp.generation_prompt）。
            // 预填只把 grammar 推过"首字面量"那一截；真后缀比它长的那一段（错位段）
            // 也要一起咽下去，否则 grammar 的落点与模型续写的位置差着那一段 ——
            // 见 advance_grammar_past_mismatch 的文件头（第十处成因）。
            if (!sg.gbnf.empty())
                attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,
                                       sg.pegLiteral.c_str());
            if (gp) env->ReleaseStringUTFChars(jgenPrompt, gp);
        }
        if (tm) env->ReleaseStringUTFChars(jtmpl, tm);
        if (sc) env->ReleaseStringUTFChars(jschema, sc);
    }

"""
assert s.count(block) == 1, "桩⑤：没找到 grammar 挂载块"
s2 = s.replace(block, "", 1)
# 追加到链尾：push_back 到 dist 之后
tail = """    llama_sampler_chain_add(ch, llama_sampler_init_dist((uint32_t) seed));
    }
"""
assert s2.count(tail) == 1, "桩⑤：没找到链尾"
s2 = s2.replace(tail, tail + """
    {
        const char * sc = jschema ? env->GetStringUTFChars(jschema, nullptr) : nullptr;
        if (sc) {
            const schema_grammar_result sg = gbnf_from_json_schema(sc, nullptr, nullptr);
            if (!sg.gbnf.empty()) attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), nullptr,
                                                          nullptr);
            env->ReleaseStringUTFChars(jschema, sc);
        }
    }
""", 1)
open(p, 'w', encoding='utf-8').write(s2)
EOF
must_red "桩⑤（grammar 排在选择器之后）" "grammar 排在第一个选择器之前"
reset_src

# ── 6. 桩⑥：只给 chat 端点接上（completions 静默不生效）────────────────
"$PY" - "$HTTP" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "stops = stops, responseFormat = respFormat, generationPrompt = genPrompt,"
assert s.count(old) == 2, "桩⑥：没找到两处 newSampler 调用"
s = s.replace(old, "stops = stops, responseFormat = ResponseFormat.None, generationPrompt = genPrompt,", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑥（只有一个端点接上）" "两个端点都把 respFormat 传进 newSampler"
reset_src

# ── 7. 桩⑦：Kotlin 侧 native 声明漏掉 schema（形参漂移 -> UnsatisfiedLinkError）──
"$PY" - "$KT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "stops: Array<String>?, schema: String?, genPrompt: String?, tmpl: String?, thinkingOn: Boolean): Boolean"
new = "stops: Array<String>?, schema: String?): Boolean"
assert s.count(old) == 1, "桩⑦：没找到 native 声明"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（Kotlin 形参漏 schema）" "Kotlin nativeNewSampler 声明的尾参逐字一致"
reset_src

# ── 8. 桩⑧：判据文件里把 json_object 折成 null（该类型静默失效）──────────
"$PY" - "$PURE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        ResponseFormat.JsonObject -> ""'
new = '        ResponseFormat.JsonObject -> null'
assert s.count(old) == 1, "桩⑧：没找到 JsonObject 映射"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（json_object 折成 null）" "JsonObject->空串"
reset_src

# ── 9. 桩⑨：守卫的判据被写成恒真（模拟"pattern 写歪"）────────────────────
# 直接改守卫源码：把"grammar 排在选择器之前"的判据换成恒真表达式，
# 断言自测能发现守卫失效（否则它就是在骗人）。
"$PY" - "$GUARD" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'c "grammar 排在第一个选择器之前（否则等于不生效）" "[ \\"\\$ATTACH\\" -lt \\"\\$FIRST_SEL\\" ]"'
new = 'c "grammar 排在第一个选择器之前（否则等于不生效）" "true"'
assert s.count(old) == 1, "桩⑨：没找到顺序判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
# 把 grammar 挂载块移到链尾（真实故障），恒真判据应当**没能**发现 -> 自测要红
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,'
new = 'attach_grammar_sampler(ch, sg.gbnf.c_str(), sg.prefill.c_str(), gp,  // 桩⑨'
assert s.count(old) == 1
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
# 判据被改恒真后，守卫不再能发现"顺序错误" —— 用 must_red 会失败，正是我们要证明的。
# 这里断言的是：**自测能识别出守卫失效**（即守卫此时变绿 = 恒真判据没起到作用）。
if run_guard; then ck "桩⑨：守卫被判据恒真化后确实失去发现能力（自测能识别）" 1
else ck "桩⑨：守卫被判据恒真化后确实失去发现能力（自测能识别）" 0; fi
reset_src

# ── 9b. 桩⑩：删掉前置声明（= main 上真实坏过的状态）─────────────────────
# 这条桩对应的是**真实发生过**的故障，不是假想：PR #83 把
# attach_grammar_sampler / gbnf_from_json_schema 插在 nativeNewSampler **之后**
# 且没加前置声明 —— 本地 Kotlin 类型检查与 41 个离线套件全绿（没有一条会编
# 这个 .cpp），只有 CI 编 APK 时才炸。
# 桩做法：把两行前置声明去掉，其余一字不动。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
decls = ('static schema_grammar_result gbnf_from_json_schema(const char * schemaJson, const char * tmplOverride,\n'
         '                                                   const char * genPrompt, bool thinkingOn);\n'
         'static bool attach_grammar_sampler(llama_sampler * chain, const char * gbnf, const char * prefill,\n'
         '                                    const char * genSuffixForMismatch, const char * pegLiteral);\n')
assert s.count(decls) == 1, '桩⑩：没找到前置声明块'
s = s.replace(decls, '', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑩（删掉前置声明）" "前置声明与定义逐字一致"

# 对偶：只把声明挪到调用点**之后**（顺序错，声明存在）必须同样变红 ——
# 这是"存在性断言"与"顺序断言"的分离检验，防有人把第二条判据写成恒真。
reset_src
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
decl = ('static schema_grammar_result gbnf_from_json_schema(const char * schemaJson, const char * tmplOverride,\n'
        '                                                   const char * genPrompt, bool thinkingOn);\n'
        'static bool attach_grammar_sampler(llama_sampler * chain, const char * gbnf, const char * prefill,\n'
        '                                    const char * genSuffixForMismatch, const char * pegLiteral);\n')
assert s.count(decl) == 1, '桩⑩b：没找到声明块'
s = s.replace(decl, '', 1)
# 插到 nativeNewSampler 的**函数体开头**之后（即调用点之后）
marker = 'JNIEXPORT jboolean JNICALL\nJava_com_xiaowan_localinference_LlmEngine_nativeNewSampler('
assert s.count(marker) == 1
idx = s.index(marker)
brace = s.index('{', s.index('jboolean jthinkingOn)', idx))
s = s[:brace + 1] + '\n    ' + decl.replace('\n', '\n    ').rstrip() + s[brace + 1:]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑩b（声明挪到调用点之后）" "声明出现在调用点之前"
# ── 9e. 桩⑬：生成后缀写死成常量 true（真机故障的那个形态）────────────────
# 这一桩防的是本轮修的那个 bug：grammar 按"prompt 不以生成后缀结尾"推导，
# 于是模型自己把生成后缀吐一遍（content 以 `<|im_start|>assistant\n` 开头，
# 数组 schema 只吐一个 `[ ]`）。把"由渲染侧给出"改回写死的 true，守卫必须变红。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    const bool haveGenPrompt = (genPrompt != nullptr);
    if (haveGenPrompt) in.add_generation_prompt = (*genPrompt != '\\0');"""
new = """    in.add_generation_prompt = true;   // 桩⑬：写死（真机故障形态）"""
assert s.count(old) == 1, "桩⑬：没找到生成后缀的判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑬（生成后缀写死成 true）" "生成后缀由调用方透传"

# ── 9c. 桩⑪：模板又写死成空串（库内自选那份与运行时不是同一份）──────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "tmpls = common_chat_templates_init(S.model, tmpl);"
new = 'tmpls = common_chat_templates_init(S.model, "");   // 桩⑪：写死空串'
assert s.count(old) == 1, "桩⑪：没找到模板取用点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑪（模板写死空串）" "模板由调用方透传"

# ── 9d. 桩⑫：把"确认没有生成后缀"折成 null（方向相反的同一种错）─────────
"$PY" - "$PURE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            ResponseFormat.GenerationPrompt.EMPTY -> ""'
new = '            ResponseFormat.GenerationPrompt.EMPTY -> null   // 桩⑫：方向相反的同一种错'
assert s.count(old) == 1, "桩⑫：没找到生成后缀映射"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑫（『确认没有』折成 null）" "生成后缀的映射只有一处"
reset_src

# ── 9f. 桩⑭：渲染出口**不把生成后缀交回宿主**（0.9.87 的真因）─────────────
# 这一桩对应**真实发生过的**故障，且形态是本仓库最该防的那类"每处单看都接上了"：
#   · native 的 newSampler 有了 genPrompt 尾参   -> 单看：接上了
#   · Kotlin 的 nativeNewSampler 也声明了它      -> 单看：接上了
#   · 日志里 `genPrompt=` 字段也打印了           -> 单看：接上了
# 唯独渲染出口（new_rendered_prompt）没把 cp.generation_prompt 交回去 ——
# 于是后缀永远是 null，日志里那个字段恒为「无」，native 退回旧口径。
#
# 桩做法：把调用点退回旧签名（只回传 prompt），其余一字不动。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "return new_rendered_prompt(env, cp.prompt, cp.generation_prompt);"
new = "return new_rendered_prompt(env, cp.prompt);   // 桩⑭：不回传生成后缀（0.9.87 真因）"
assert s.count(old) == 2, "桩⑭：没找到两个渲染出口"
open(p, 'w', encoding='utf-8').write(s.replace(old, new))
EOF
must_red "桩⑭（渲染出口不回传生成后缀）" "渲染出口把生成后缀一起交回"

# 对偶：只改**两个出口中的一个**（带 tools 那条漏了）也必须变红 ——
# 只给一个出口接线 = 换个入口就换一种行为，是本仓库反复踩的"改一处漏一处"。
reset_src
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "return new_rendered_prompt(env, cp.prompt, cp.generation_prompt);"
new = "return new_rendered_prompt(env, cp.prompt);   // 桩⑭b：只漏一个出口"
assert s.count(old) == 2, "桩⑭b：没找到两个渲染出口"
# 只替换最后一次出现（带 tools 的那条）
i = s.rindex(old)
open(p, 'w', encoding='utf-8').write(s[:i] + new + s[i+len(old):])
EOF
must_red "桩⑭b（只漏一个渲染出口）" "渲染出口把生成后缀一起交回"
reset_src

# ── 9g. 桩⑮：宿主 parse 不再读后缀（回传了但没人消费，等于白接）──────────
# 与桩⑭ 是这条链的两端，两端都得有判据：只测一端 = 另一端断了也不会红。
"$PY" - "$SRC_RP" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            return RenderedPrompt(prompt, openAtStart = openAtStart, generationSuffix = suffix)"
new = "            return RenderedPrompt(prompt, openAtStart = openAtStart)   // 桩⑮：不读后缀"
assert s.count(old) == 1, "桩⑮：没找到 parse 的后缀构造"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑮（宿主 parse 不读后缀）" "宿主 parse 会读取后缀"
reset_src

# ── 9h. 桩⑯：回传格式的长度段宽度两端漂了（分叉不报错，只会切错位置）──────
"$PY" - "$SRC_RP" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        private const val SUFFIX_HEX_LEN = 8"
new = "        private const val SUFFIX_HEX_LEN = 6   // 桩⑯：与 C++ 漂了"
assert s.count(old) == 1, "桩⑯：没找到长度段宽度"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑯（长度段宽度两端漂移）" "回传格式的长度段宽度两端一致"
reset_src

# ── 9i. 桩⑰：长度单位退回 UTF-8 字节数（ASCII 后缀下看不出来的那类错）──────
# 这一桩防的是"单位口径漂了、而常见用例恰好区分不出"：ASCII 后缀（22/30/41 那几种）
# 的字节数与 UTF-16 单元数相等，所以这条判据错了在真机常见模型上**零症状**；
# 只有换一个含汉字 / emoji 的生成后缀才会把切点切错。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'snprintf(hex, sizeof(hex), "%08zx", utf16_len(genSuffix))'
new = 'snprintf(hex, sizeof(hex), "%08zx", genSuffix.size())   // 桩⑰：退回字节数'
assert s.count(old) == 1, "桩⑰：没找到长度段拼法"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑰（长度单位退回 UTF-8 字节数）" "长度单位是 UTF-16 code unit"
reset_src

# ── 9j. 桩⑱：grammar 采样器**不预填**生成前缀（0.9.89 修的那处成因）──────
# 这一桩是本轮的核心判据：库推出来的 GBNF 以生成前缀的字面量开头，而那段已经在
# prompt 里了 —— 不预填时 grammar 从根节点起步、逼模型把模板写好的尾巴再吐一遍。
# 真机表现正是"复测结果还是一样"：content 前面多一段 / 数组 schema 只剩 `[ ]`。
# 桩做法：把预填循环去掉（其余一字不动），守卫必须变红。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                    llama_sampler_accept(gs, toks[i]);\n                    n_prefill++;"
new = "                    // stub18: 不预填（真机故障形态）\n                    (void) toks;"
assert s.count(old) == 1, "stub18: 没找到预填循环"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑱（grammar 采样器不预填生成前缀）" "grammar 采样器在挂链前被 accept 预填"
reset_src

# 对偶：预填了，但预填文本产出的那一跳被摘掉（prefill 恒为空 = 等于没预填）。
# 只测"调用点有没有 accept"是不够的 —— 值恒为空时调用点照样在，判据会假绿。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    out.prefill = grammar_prefill_from_literal(out.pegLiteral, renderedSuffix);"
new = '    out.prefill = "";   // stub18b: 预填值恒为空'
assert s.count(old) == 1, "stub18b: 没找到 prefill 产出点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑱b（预填值恒为空）" "预填文本由 gbnf_from_json_schema 一起产出"
reset_src

# ── 9k. 桩⑲：首字面量只取根节点、不往序列里钻（根常是 sequence -> 取不到就静默不预填）──
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        } else if constexpr (std::is_same_v<T, common_peg_sequence_parser>) {
            for (const auto & child : p.children) {
                const std::string lit = peg_leading_literal(arena, child, depth + 1);
                if (!lit.empty()) return lit;
            }
            return "";"""
new = """        } else if constexpr (std::is_same_v<T, common_peg_sequence_parser>) {
            return "";   // stub19: 不往序列里钻（根常是 sequence，取不到就静默不预填）"""
assert s.count(old) == 1, "stub19: 没找到序列分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑲（首字面量不往序列里钻）" "首字面量往下钻序列"
reset_src

# ── 9l. 桩⑳：预填上限退回库算的 41B 生成后缀（= 0.9.90 回归、也是本轮 SIGABRT 的真根因）──
# 这条桩对应**真机上刚发生**的故障：Qwen3 关思考 + json_schema 时，库给出
# gen_prompt=41B（含 <think>…</think>）而 GBNF 根首字面量只有 22B。按 41B 喂，
# 喂到 <think> 那一步 grammar 已无栈可推 → 抛 "empty grammar stack"；
# 0.9.90/0.9.91 又把被打坏的采样器挂进链 → 采样时 LM_GGML_ASSERT(!stacks.empty()) → abort。
# ⚠ 0.9.91 的"字节边界收敛"救不了这条：那次切点整除、收敛一次都没触发（日志可证）。
# 桩做法：把 prefill 上限换回 0.9.90 的"库后缀 ∩ 真后缀"，其余一字不动。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    out.prefill = grammar_prefill_from_literal(out.pegLiteral, renderedSuffix);"
new = "    out.prefill = grammar_prefill_from_suffixes(cp.generation_prompt, renderedSuffix);   // stub20"
assert s.count(old) == 1, "stub20: 没找到 prefill 上限"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑳（预填上限退回库算的 41B 生成后缀 = 0.9.90 回归）" "预填上限不得退回库算的 41B 后缀"
reset_src

# 对偶：上限对了、但预填失败仍把**被打坏**的采样器挂进链（0.9.90/0.9.91 的形态）——
# 这条防"只修了上限、没修挂链"。挂坏状态 = 采样时空栈断言 lm_ggml_abort = 整个进程闪退。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            llama_sampler_free(gs);\n            return false;\n        } catch (...) {"
new = "            break_out = true;\n        } catch (...) {"
assert s.count(old) == 1, "stub20b: 没找到预填失败 catch"
s = s.replace(old, new, 1)
marker = "    llama_sampler_chain_add(chain, gs);"
assert s.count(marker) == 1, "stub20b: 没找到挂链点"
s = s.replace(marker, "    if (true) { " + marker + " }", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑳b（预填失败仍把坏状态挂进链 = 真机 abort 形态）" "预填失败时"
reset_src

# ── 9m. 桩㉓：C++ 侧丢掉 thinkingOn（= 本轮真根因：合并时 C++ 半被丢、Kotlin 半还在）──
# 这条桩对应**真实发生过**的故障，不是假想：PR #89 的修复同时改了 C++ 与 Kotlin，
# 但解决冲突时"C++ 取 main 侧"把 C++ 那半丢了 —— 于是 Kotlin 声明 15 参、C++ 只有 13 参。
# 真机上 JVM 按**短符号名**回退解析，多出来的实参被 ABI 忽略，C++ 永远收不到
# thinkingOn、静默按默认 true 重算 PEG。装机后输出与修复前逐字一样。
# 桩做法：把 C++ 入口的 jthinkingOn 去掉、调用点退回旧签名，其余一字不动。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old1 = """        jobjectArray jstops, jstring jschema, jstring jgenPrompt, jstring jtmpl,
        jboolean jthinkingOn) {"""
new1 = """        jobjectArray jstops, jstring jschema, jstring jgenPrompt, jstring jtmpl) {"""
assert s.count(old1) == 1, "stub23: 没找到 C++ 入口尾参"
s = s.replace(old1, new1, 1)
old2 = "                gbnf_from_json_schema(sc, tm, gp, jthinkingOn == JNI_TRUE);"
new2 = "                gbnf_from_json_schema(sc, tm, gp);"
assert s.count(old2) == 1, "stub23: 没找到 C++ 调用点"
s = s.replace(old2, new2, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉓（C++ 丢掉 thinkingOn = 本轮真根因）" "形参**个数相等**"
reset_src

# 对偶：只把 C++ 的 in.enable_thinking 那一行摘掉（形参还在、值没接线）——
# 这条防"只判形参个数、不判真的写进了模板输入"。个数一样但没接线照样该红。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    in.enable_thinking = thinkingOn;"
new = "    // stub23b: 思考开关没写进模板输入"
assert s.count(old) == 1, "stub23b: 没找到 in.enable_thinking 赋值"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩㉓b（形参在但没写进 templates_inputs）" "把思考开关写进 templates_inputs"
reset_src

# ── 9n. 桩㉔：预填整段 accept、不按 token 边界收敛（= 本轮真机 SIGABRT 的形态）──
# 这条桩对应**真机上刚发生**的故障，不是假想：probe-20260921-160945.txt 现场
#     gen_prompt=41B(…) rendered_suffix=41B prefill=41B peg_lit=41B think=0   ← 量全对齐
#     llama-grammar.cpp:942: LM_GGML_ASSERT(!stacks.empty()) failed -> lm_ggml_abort
#     → SIGNAL 6 (Aborted)，栈顶 llama_grammar_apply_impl ← llama_sampler_sample ← nativeStep
# 上一轮把"对齐量"全修对了却仍在 accept 那一跳崩：token 是原子的、grammar 位置是字节级的，
# 只要某个 token 跨过预填结尾，整段 piece 都会被 accept —— 多喂的字节让 grammar 空栈 abort。
# 桩做法：把"按字节收敛"整段去掉、退回逐 piece 全喂（就是本轮真机装的那份），守卫必须变红。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                for (size_t i = 0; i < i_first; i++) {"""
new = """                for (size_t i = 0; i < toks.size(); i++) {   // stub24: 不收敛，逐 piece 全喂"""
assert s.count(old) == 1, "stub24: 没找到收敛后的预填循环"
s = s.replace(old, new, 1)
old2 = """                        if (next > boundary) break;   // 这个 piece 跨过喂入口径的末尾 -> 不喂"""
new2 = """                        if (false) break;   // stub24: 收敛被架空"""
assert s.count(old2) == 1, "stub24: 没找到收敛判据"
s = s.replace(old2, new2, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉔（预填不按 token 边界收敛 = 本轮真机 SIGABRT）" "预填按 token 字节边界收敛"
reset_src

# 对偶：收敛判据写的是 **token 个数** 而不是字节数。
# 这条防"口径漂了、而 ASCII 模板恰好区分不出"：41B 纯 ASCII 下 piece 数与字节数
# 看起来都能"通过"，只有含 CJK / 多字节 piece 的模板才会整体错位。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                        const size_t next = bytes + piece.size();
                        if (next > boundary) break;   // 这个 piece 跨过喂入口径的末尾 -> 不喂"""
new = """                        const size_t next = bytes + 1;   // stub24b: 按 piece 个数收敛
                        if (next > toks.size()) break;   // stub24b: 比较对象也换成个数"""
assert s.count(old) == 1, "stub24b: 没找到收敛算术"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉔b（收敛按 token 个数而不是字节数）" "收敛判据按**字节数**"
reset_src

# 对偶：收敛写歪成"一个都不喂"（等于退回不预填的老症状 —— 约束落点又偏回根节点）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                        i_first = i + 1;"
new = '                        i_first = i;   // stub24c: 收敛写成一个都不喂'
assert s.count(old) == 1, "stub24c: 没找到 i_first 推进"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉔c（收敛把预填整体丢掉 = 退回老症状）" "收敛后至少还能喂一个 piece"
reset_src

# 对偶：日志退回只报计算量（真机就是这一版把“看着对齐”读成了“没问题”）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''       " fed=%zuB/%zuB\\n",'''
new = '''\\n",'''
assert s.count(old) == 1, "stub24d: 没找到 fed= 判据行"
s = s.replace(old, new, 1)
old2 = '''       fed_prefill_bytes, prefillEff.size());'''
new2 = '''       (size_t) 0);   // stub24d: 不报实际喂入量'''
assert s.count(old2) == 1, "stub24d: 没找到 fed 实参"
s = s.replace(old2, new2, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉔d（判据日志退回只报计算量）" "判据日志同时给出 prefill= 与 fed="
reset_src

# ── 10. 就位点判据（本轮新增："看得见采样那一步"）────────────────────────
# 桩㉕：就位点那行不落日志 -> 下一轮仍然只能拿 prefill=NB 反推
#      （真机就是这一版把 prefill=22B peg_lit=22B 读成了"对齐了、没问题"）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''    jp("[schema] grammar 就位点：prefill=%zuB 之后 grammar 期望续写 / 真 prompt 后缀=%zuB"
       " fit=%d mismatch=%zuB(%s)%s\\n",'''
new = '''    jp("",   // stub25: 就位点那一行不再打出来
       " fit=%d mismatch=%zuB(%s)%s\\n",'''
assert s.count(old) == 1, "stub25: 没找到就位点判据行"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
# ⚠ 这条桩本身修过一次：第一版写成 `if (false) jp("...")`，而判据 grep 的是
# **字符串字面量** —— 加 `if (false)` 后字面量仍在源码里，判据照绿（弱断言：
# 只判"这句话在不在源码里"，不判"它真的会被打出来"）。改成改掉字面量本身之后，
# 该红的终于红了。这正是本自测存在的理由。
must_red "桩㉕（就位点判据不落日志）" "就位点判据进了日志"
reset_src

# 桩㉕b：只报"是否一致"，不报错位段的**原文** —— 那就退回"只知道短喂了、不知道短喂了谁"，
# 而真机上要的恰恰是那一段（它是不是 `<think>…</think>` 决定修法完全不同）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''       fit.mismatch.size(), escape_for_probe(fit.mismatch, 60).c_str(),'''
new = '''       fit.mismatch.size(), "",'''
assert s.count(old) == 1, "stub25b: 没找到 mismatch 原文实参"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉕b（只报字节数、不报错位段原文）" "错位段（mismatch）原文被打出来"
reset_src

# 桩㉕c：就位点算术在 llama_jni.cpp 里**另写一份**（不复用 probe_util.h 的纯函数）——
# 本仓库栽过"宿主侧镜像 native 逻辑，镜像与真实现分叉后测试全绿而真机照错"。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    const grammar_fit_result fit = grammar_fit_check(out.prefill, renderedSuffix);"
new = '''    grammar_fit_result fit;   // stub25c: 就地另写一份，不用 probe_util.h
    fit.expectBytes = out.prefill.size();
    fit.actualBytes = renderedSuffix.size();
    fit.aligned     = fit.expectBytes == fit.actualBytes ? 1 : 0;'''
assert s.count(old) == 1, "stub25c: 没找到就位点调用点"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉕c（就位点算术另写一份，不复用纯函数）" "就位点算术来自 probe_util.h 的纯函数"
reset_src

# 桩㉖：预填上限退回 0.9.90 那份"库算后缀 ∩ 真后缀"——
# 也就是**按那条已被证伪的"短喂会失效"解释去补喂**（真机 SIGABRT 的成因）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    out.prefill = grammar_prefill_from_literal(out.pegLiteral, renderedSuffix);"
new = "    out.prefill = grammar_prefill_from_suffixes(cp.generation_prompt, renderedSuffix);   // stub26"
assert s.count(old) == 1, "stub26: 没找到预填上限"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉖（按已被证伪的解释去补喂：上限退回 41B 库后缀）" "实现不得按'短喂会让约束失效'去补喂"
reset_src

# 桩㉗：把**更正说明删掉**（源码里不再留"space ::= 的空分支"这条更正）——
# 下一轮读到旧注释，又会照"短喂让约束失效"推一遍，于是再去补喂、再撞一次 SIGABRT。
# ⚠ 这条桩的第一版选错了断言面：它用"补喂"表达"按证伪的解释写回"，而补喂**不改变
#   预填上限的来源**（仍是 grammar_prefill_from_literal），于是那条判据照绿。
#   桩改对之后才照出真正该钉的东西：**更正必须留在源码里**。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """    // 而真 prompt 里思考块**已经写死**。当时据此推断"space() 不能匹配空串、全体候选
    // 被置 -inf" —— ⚠ **该推断已在 0.9.93 证伪**：库内
    //     space ::= | " " | "\\n"{1,2} [ \\t]{0,20}
    // 第一个分支就是空，它能匹配空串。所以"思考开关透传"这件事**本身仍然是对的**
    // （两份 PEG 该出自同一次求值），但"短喂会让约束整个失效"不是它成立的理由。
    // 详见 HTP-STATUS 第二十七节 27.1 与 probe_util.h 的 grammar_fit_check 注释。
"""
new = """    // stub27: 更正说明被删，只剩旧解释
"""
assert s.count(old) == 1, "stub27: 没找到更正说明"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
# ⚠ 第二版桩只删了 llama_jni.cpp 里那一处，而 probe_util.h 里还有一份同样的
# 更正说明 —— 判据 grep 的是 probe_util.h，于是照绿。桩必须把两处都删掉，
# 否则它测的是"两个文件里至少一个还有"，而不是"更正存在"。
"$PY" - "app/src/main/cpp/probe_util.h" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '''//     space ::= | " " | "\\n"{1,2} [ \\t]{0,20}'''
# ⚠ 第三版才写对：probe_util.h 里**多处**都有这行（更正段 / 就位点段 / 第十处成因段），
# 所以必须 replace **全部**，只替一处判据照样绿。
# ⚠ 第四版（0.9.96）：条数从"恒等于 2"改成"≥2" —— 写死条数的桩会在**新增一处引用时**
#   直接 assert 失败（自测自己红了，而这不是被测对象的错）。桩要断言的是
#   "这几处都被删干净了"，而不是"世界上恰好有 2 处" —— 后者是把用例绑死在行数上。
assert s.count(old) >= 2, "stub27b: probe_util.h 里的更正行少于两处"
# 判据钉的是「规则原文 **与** 结论都在」两串，所以桩必须把**两串都删掉**，
# 而且**任何出现形态**都要删（行首 `//     ` / 反引号里 / 句中的）—— 只删一种形态时，
# 另一种还在源码里，判据照绿（这是本桩第二处、也是第三处'桩写歪'）。
s = s.replace(' space ::= | " " | ', ' stub27b A ')
s = s.replace('它能匹配空串', 'stub27b B')
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉗（更正说明被删掉，源码只剩旧解释）" "更正结论写进了源码注释"
reset_src

# ── 9j. 桩㉘：端点不落响应体探针（本轮的缺口本身）────────────────────────
# 这一桩对应**真实存在了八轮**的取证缺口：链上的日志只有 `chat ok: n tok`，
# 响应体一个字都没落盘。`[ ]` 与 `["a","b","c"]` 都是 13 tok，读数上分不开，
# 于是每轮复测都只能靠"用户说还是有代码块"这个结论反推。
# 桩做法：把两个端点的 probeBody 那一行删掉，其余一字不动。
"$PY" - "$HTTP" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
line = "                    emitLog(JsonSchemaFormat.probeBody(JsonSchemaFormat.reqTagOf(id), sb.toString()))\n"
assert s.count(line) >= 1, "桩㉘：没找到 chat 端点的响应体探针"
s = s.replace(line, "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩㉘（chat 端点不落响应体探针）" "两个生成端点都落这一行"
reset_src

# 对偶：只漏**裸补全**那个端点（换个入口就换一种取证能力 = 本仓库反复踩的"改一处漏一处"）。
"$PY" - "$HTTP" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
line = "                        emitLog(JsonSchemaFormat.probeBody(JsonSchemaFormat.reqTagOf(id), sb.toString()))\n"
assert s.count(line) == 1, "桩㉘b：没找到 completions 端点的响应体探针"
s = s.replace(line, "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩㉘b（只漏裸补全端点）" "两个生成端点都落这一行"
reset_src

# ── 9k. 桩㉙：探针不报元素个数（读数退化成"又一个计数"）──────────────────
# 这一桩防的是"探针打了却读不出来"：只报长度与头部，`[ ]` 与 3 元素仍然分不开，
# 而这一格恰恰是 Qwen3 那例唯一的区别（用户的原话就是"数组那例带围栏"）。
"$PY" - "$PURE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'append(" array=").append(elems)'
new = 'append(" array=").append(-1)   // 桩㉙：不报元素个数'
assert s.count(old) == 1, "桩㉙：没找到元素个数的落点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "桩㉙（探针不报元素个数）" "元素个数来自**解析结果**"
reset_src

# ── 9l. 桩㉚：换行不转义（一行日志被日志文件按行撕开）────────────────────
# 真机故障的差别**就在换行上**（`<|im_start|>assistant\n` 与 `\n\n</think>\n\n`）。
# 不转义时 `[body]` 那一行会被拆成好几行，grep 只捞得到第一段 —— 等于没打。
"$PY" - "$PURE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "'" + chr(92) + "n' -> append("
new = "'" + chr(92) + "n' -> appendNoopHack("
assert s.count(old) == 1, "桩㉚：没找到换行转义分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "桩㉚（换行不转义）" "探针把换行转义成可见字符"
reset_src

# ── 9m. 桩㉛：围栏不剥就报 json=fail（两种成因混成一条读数）──────────────
# "模型吐了 ```json 围栏"与"模型吐了彻底非法的东西"是两种故障，修法完全不同：
# 前者是生成后缀没对齐，后者是 grammar 压根没生效。不剥围栏就把两者压成同一个
# `json=fail`，下一轮又要靠猜。
"$PY" - "$PURE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 用 probeBody 里那一处的**上下文**取唯一（stripFenceForDelivery 里也有一处同样的调用，
# 只按那一行会命中三处 —— 桩必须落在探针那一处，否则改的不是它要测的东西）。
old = """            content.takeLast(PROBE_TAIL).escapeProbe() else ""
        val stripped = stripCodeFence(content)"""
new = """            content.takeLast(PROBE_TAIL).escapeProbe() else ""
        val stripped = content   // 桩㉛：不剥围栏"""
assert s.count(old) == 1, "桩㉛：没找到剥围栏调用点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "桩㉛（围栏不剥直接判 json）" "围栏在报 json= 之前先剥离"
reset_src

# ── 9n. 桩㉜：请求侧日志不带关联码（输出侧带、请求侧不带 = 仍配不上对）────
# 本轮真机日志暴露的缺口：请求侧那三行逐行可读，**一行都对不上** —— 无请求 id、
# 三例长得一模一样（只有 name= 不同），与生成后的 `[body]` 只能靠数顺序配对。
# 只给输出侧加码、不给请求侧加，等于把缝挪了个位置：还是得靠数。
"$PY" - "$HTTP" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'emitLog("[id=$reqTag] " + JsonSchemaFormat.describe(respFormat))'
new = 'emitLog(JsonSchemaFormat.describe(respFormat))   // 桩㉜：请求侧丢掉关联码'
assert s.count(old) == 2, "桩㉜：没找到请求侧的关联码落点（应有 2 处）"
open(p, 'w', encoding='utf-8').write(s.replace(old, new))
PYEOF
must_red "桩㉜（请求侧丢了关联码）" "请求侧的 response_format 日志也带同一个关联码"
reset_src

# 对偶：只给 chat 端点带、漏掉裸补全端点（"改一处漏一处"的老坑）。
"$PY" - "$HTTP" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'emitLog("[id=$reqTag] " + JsonSchemaFormat.describe(respFormat))'
assert s.count(old) == 2, "桩㉜b：没找到请求侧的关联码落点"
i = s.index(old)
open(p, 'w', encoding='utf-8').write(
    s[:i] + 'emitLog(JsonSchemaFormat.describe(respFormat))   // 桩㉜b' + s[i+len(old):])
PYEOF
must_red "桩㉜b（只漏裸补全端点的关联码）" "请求侧的 response_format 日志也带同一个关联码"
reset_src

# ── 9o. 桩㉝：关联码不是从 id 取的（两端各拼一次 = 形态必然漂）──────────
# 这一桩对应"关联码必须是**同一个来源**"：如果一端用 id 尾巴、另一端自己拼时间戳，
# 两边看起来都"有关联码"，实际永远配不上对 —— 而判据只看"有没有 reqTag"时照样绿。
"$PY" - "$HTTP" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "JsonSchemaFormat.reqTagOf(id)"
new = '"?"   // 桩㉝：不从 id 取'
assert s.count(old) >= 2, "桩㉝：没找到 reqTagOf(id) 的调用点"
open(p, 'w', encoding='utf-8').write(s.replace(old, new))
PYEOF
must_red "桩㉝（关联码不从 id 取）" "关联码只由 reqTagOf 一处产出"
reset_src

# ── 9p. 桩㉞：关联码挪到生成之后才定型（[body] 那时拿不到码）────────────
# 顺序类成因：`reqTag` 若在 `newSampler` 之后才算出来，`[body]` 落到日志时它是空串
# —— 而"有关联码"这条存在性判据**照样绿**（源码里确实有这一行）。所以必须有顺序断言。
"$PY" - "$HTTP" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            val reqTag = JsonSchemaFormat.reqTagOf(id)\n"
assert s.count(old) == 2, "桩㉞：没找到 reqTag 的定型处"
i = s.index(old)
open(p, 'w', encoding='utf-8').write(s[:i] + s[i+len(old):])
PYEOF
must_red "桩㉞（关联码挪到生成之后）" "关联码在**生成之前**定型"
reset_src

# ── 11. 错位段推进（第十处成因，0.9.96 修）────────────────────────────────
# 桩㉟：干脆不推进错位段（= 0.9.92~0.9.95 的行为，也是"症状一直没变"的那一版）。
# 判据是"补喂那一跳存在"，所以桩只要把调用点删掉就该红。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 判据查的是 `advance_grammar_past_mismatch(` 这个**调用点**，所以桩必须把它删掉
# （只把 if 条件改成 false 时，调用点还在源码里，判据照绿 —— 这是本桩第一处'桩写歪'）。
old = "const prefill_advance_result adv = advance_grammar_past_mismatch("
assert s.count(old) == 1, "桩㉟：没找到错位段推进调用点"
s = s.replace(old, "const prefill_advance_result adv = (prefill_advance_result){}; if (false) advance_grammar_past_mismatch(", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㉟（不推进错位段）" "错位段被喂给 grammar（修法本体"
reset_src

# 桩㊱：把错位段的补喂改成"整段按 token 喂"（= 0.9.90 真机 SIGABRT 的形态）。
# 这一条与桩㉟同等重要：本处的修法如果写回"整段 accept"，症状会从"输出仍带前缀"
# 变成"直接闪退" —— 更坏，且正是前几轮踩过的那条路。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                        const std::string one(1, c);"
assert s.count(old) == 2, "桩㊱：没找到逐字节推进"
s = s.replace(old, "                        const std::string one = fitForAdvance.mismatch;   // 桩㊱：整段硬喂", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊱（错位段整段硬喂 = 0.9.90 的 SIGABRT 形态）" "补喂**逐字节**（不切 token"

# 桩㊲：试探不成功也照样把字节喂进原件（把"grammar 点头"这条判据拆掉）——
# 这等于把 grammar 交给我们自己的推测：不认的字节喂进去就是空栈 abort。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                if (!adv.accepted.empty()) {"
assert s.count(old) == 1, "桩㊲：没找到全成功才重放的判据"
s = s.replace(old, "                if (true) {   // 桩㊲：无条件重放", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊲（不认也照喂：把 grammar 交给推测）" "grammar 不认的字节"

# 桩㊳：推进用的"错位段"不取自真后缀（自己拼一段）—— 
# 那样喂进 grammar 的是**模型没写过**的文本，属于另一种故障（喂 prompt 里没有的字节）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                const grammar_fit_result fitForAdvance = grammar_fit_check(pf, realSuffix);"
assert s.count(old) == 1, "桩㊳：没找到错位段来源"
s = s.replace(old, "                grammar_fit_result fitForAdvance; fitForAdvance.mismatch = \"\\n\";   // 桩㊳：自己拼一段", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊳（错位段不取自真后缀）" "补喂的那一段取自真后缀"

# 桩㊴：未推进时不留痕（症状与旧版逐字相同，没有那行就分不出"没试过"与"试了不认"）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                    jp(\"[schema] 错位段未被 grammar 接受（%zuB），落点保持 %zuB\""
assert s.count(old) == 1, "桩㊴：没找到未推进的留痕"
s = s.replace(old, "                    jp(\"\"", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊴（未推进时不留痕）" "落点推进与未推进"
reset_src

# ── 9b. 第十一处成因（根字面量对齐到真后缀，0.9.97）的桩 ──────────────────
# 这一节的修法**改的是 grammar 文本**（把根字面量由 22B 换成 41B），
# 与 5h) 的"喂给 grammar"是两条不同的路。桩要分别钉住：
#   · 对齐没做（还是走 5h 的老路）-> 真机会退回"错位段未被接受"；
#   · 对齐做了但预填没跟上 -> 声明 41B / 喂 22B，落点又差回 19B（0.9.96 的错配形态）；
#   · 对齐做了但用错分隔符（` = ` 而非 ` ::= `）-> 静默"什么都没变"。

# 桩㊵：不做对齐（把 realign 调用删掉）—— 症状会与 0.9.96 逐字相同。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    const std::string gbnfEffective = gbnf_realign_root_literal(gbnf, pegLit, realSuffix);"
assert s.count(old) == 1, "桩㊵：没找到对齐调用"
s = s.replace(old, "    const std::string gbnfEffective = std::string(gbnf);   // 桩㊵：不对齐", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊵（根字面量不对齐 = 退回 0.9.96 的症状）" "对齐用真后缀改写根字面量"
reset_src

# 桩㊶：对齐了但预填不同步（把 prefillEff 的同步删掉）—— 声明 41B / 喂 22B。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    if (realigned) prefillEff = realSuffix;"
assert s.count(old) == 1, "桩㊶：没找到预填同步"
s = s.replace(old, "    // 桩㊶：预填不跟着对齐", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊶（对齐了但预填没跟上：声明 41B / 喂 22B）" "对齐生效后预填跟着喂满真后缀"
reset_src

# 桩㊷：对齐与预填由**两个不同**判据驱动（把 gbnfFinal 写死成对齐后的）。
# 这一条钉的是"两者必须同步"：分开写时"声明与实喂错开"可以静默成立。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    const std::string gbnfFinal = realigned ? gbnfEffective : std::string(gbnf);"
assert s.count(old) == 1, "桩㊷：没找到 gbnfFinal"
s = s.replace(old, "    const std::string gbnfFinal = gbnfEffective;   // 桩㊷：不由 realigned 驱动", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊷（改写的 GBNF 与预填不由同一判据驱动）" "realigned 判据驱动"
reset_src

# 桩㊸：不作"整 token 边界"检查（真后缀非边界时预填被收敛截短 -> 落点又错开）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            exact = (bytes == realSuffix.size());"
assert s.count(old) == 1, "桩㊸：没找到整 token 边界判据"
s = s.replace(old, "            exact = true;   // 桩㊸：不检查边界", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊸（不作整 token 边界检查 = 可能出现半对齐）" "真后缀不是整 token 边界时放弃对齐"
reset_src

# 桩㊹：对齐不成立时不退回原预填量（把回退干掉）—— 会让"没对齐"的输入也被喂真后缀。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    std::string prefillEff = prefill ? std::string(prefill) : std::string();"
assert s.count(old) == 1, "桩㊹：没找到回退"
s = s.replace(old, "    std::string prefillEff = std::string();   // 桩㊹：不回退", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊹（对齐不成立时不退回原预填量）" "对齐不成立时逐字节退回原 GBNF"
reset_src

# 桩㊺：对齐的纯函数删掉（判据必须钉住它本体在 probe_util.h）。
"$PY" - "$PROBE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "static inline std::string gbnf_realign_root_literal(const std::string & gbnf,"
assert s.count(old) == 1, "桩㊺：没找到纯函数"
s = s.replace(old, "static inline std::string stub45_realign(const std::string & gbnf,", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊺（对齐纯函数不在 probe_util.h）" "根字面量对齐的纯函数在 probe_util.h"
reset_src

# 桩㊻：单测里去掉分隔符 ` ::= ` 那条反向断言（分隔符写错会静默"什么都没变"）。
cp tools/root_literal_probe_test.cpp "$TMP/rlp.bak"
"$PY" - tools/root_literal_probe_test.cpp <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "分隔符口径钉在 ` ::= `"
assert s.count(old) == 1, "桩㊻：没找到分隔符断言"
s = s.replace(old, "分隔符口径", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊻（单测缺分隔符反向断言）" "单测（含分隔符"
reset_src

# 桩㊼：日志里写回 `strlen(gbnfFinal)` —— 真机编不过（`0.9.97` 的原样）。
# 这一条钉的是"接口用错"这一类：`%zu` 要长度、`gbnfFinal` 是 `std::string`，
# 宿主侧 `run_jni_schema_syntax.sh` 会红，所以断言写成"必须 size() 且不得 strlen"。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 两处都改：`gbnfFinal.size()` 现在出现在两行日志里（挂载行 + 落点行），
# 桩要把**两处都**打回 `strlen(gbnfFinal)` —— 只改一处时另一处仍会让守卫变绿，
# 而真机编不过这件事只要有一处就够了（0.9.98 就是这样过的）。
# ⚠ 两条取串必须**互不包含**：`"       gbnfFinal.size(), n_prefill,"` 是落点行那条的
#   前缀，用 count==1 断言会当场假红（本桩第一版就是这么挂的）。各带自己的尾巴即可。
old = "       gbnfFinal.size(), n_prefill, !prefillEff.empty()"
assert s.count(old) == 1, "桩㊼：没找到 gbnfFinal.size()（挂载行）"
s = s.replace(old, "       strlen(gbnfFinal), n_prefill, !prefillEff.empty()", 1)
old_b = "         gbnfFinal.size(), n_prefill, fed_prefill_bytes);"
assert s.count(old_b) == 1, "桩㊼：没找到 gbnfFinal.size()（落点行）"
s = s.replace(old_b, "         strlen(gbnfFinal), n_prefill, fed_prefill_bytes);", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊼（日志改回 strlen(gbnfFinal)：真机编不过）" "日志报的是 gbnfFinal.size()"
reset_src

# 桩㊽：CI 把 JNI 语法检查退回 REQUIRED=0 —— 真编译错会被 SKIP 静默放过。
"$PY" - "$CI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "REQUIRED=1 sh tools/run_jni_schema_syntax.sh"
assert s.count(old) == 1, "桩㊽：没找到 REQUIRED=1"
s = s.replace(old, "REQUIRED=0 sh tools/run_jni_schema_syntax.sh", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊽（JNI 语法检查退回 REQUIRED=0 = 真编译错被 SKIP）" "REQUIRED=1"
reset_src

# ══════════════════════════════════════════════════════════════════════
# ㊾ ~ ㊿：预填的**字节账**（第十二处成因，`0.9.99` 修）
# ══════════════════════════════════════════════════════════════════════
# 真机 `0.9.98` 的三行读数（用户 23:16 贴的原文）：
#     gbnf=916B -> 939B                     <- 根字面量**真的**从 22B 改成 41B 了
#     根字面量对齐：peg_lit=22B -> 真后缀=41B（已对齐）
#     已挂载 grammar 采样器：prefill=7 tok(已预填) fed=41B/41B
# 前两行成立、第三行的分子**是算出来的**（= prefillEff.size()，与真喂进多少无关），
# 于是"改写生效了"与"约束还有效吗"之间那道缝一直没被任何断言盖住。
# grammar 是字节级、预填那一跳是 token 级：一个 piece 里可能同时含"grammar 声明的
# 字面量"与"模型还没开始写"的字节 —— 喂进去就多于声明量，grammar 被推过它自己
# 声明的落点；**不抛异常**，代价是语法被悄悄写坏（`{`/`[` 全匹配不上 -> 全体 -inf）。

# 桩㊾：预填那一跳**不看声明量**（退回"只按预填文本长度收敛"）。
# 这正是 `0.9.98` 的形态：收敛线取 `pf.size()`，而 grammar 声明的是另一个数。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                const size_t boundary = plan.usable ? plan.declaredBytes : pf.size();"
new = "                const size_t boundary = pf.size();   // stub49: 不看 grammar 的声明量"
assert s.count(old) == 1, "stub49: 没找到收敛线"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊾（收敛线不取 grammar 声明量 = 0.9.98 的形态）" "收敛线取 grammar 声明的落点"
reset_src

# 桩㊿：`fed=` 的分子写回"算出来的"预填量 —— 故障会被读成正常（0.9.98 真机就是如此）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "       fed_prefill_bytes, prefillEff.size());"
new = "       prefillEff.size(), prefillEff.size());   // stub50: 分子改成算出来的"
assert s.count(old) == 1, "stub50: 没找到 fed 实参"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩㊿（fed= 分子改成算出来的 = 故障被读成正常）" "fed= 的分子是实际累加的字节数"
reset_src

# 桩51：账不平不落日志 —— 症状与"没对齐"逐字相同，下一轮分不出是哪种。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            jp("[schema] ⚠ 预填字节账：账不平（实喂 %zuB != grammar 声明的根字面量 %zuB）"'
new = '            jp("[schema] stub51: 账不平不落日志"'
assert s.count(old) == 1, "stub51: 没找到账不平行"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩51（账不平不留痕 = 与未对齐逐字同症状）" "账不平必须显形"
reset_src

# 桩52：预填口径的纯函数被搬回 llama_jni.cpp（宿主上编不了 = 只能靠 review 肉眼保证）。
# 与 5i 的㊺ 同源：这条判定一旦写错就是"不崩、不报错、只是约束失效"。
"$PY" - "$PROBE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "static inline prefill_byte_plan plan_grammar_prefill_bytes("
assert s.count(old) == 1, "stub52: 没找到纯函数"
s = s.replace(old, "static inline prefill_byte_plan plan_grammar_prefill_bytes_RENAMED(", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩52（预填字节账纯函数不在 probe_util.h）" "预填字节账的纯函数在 probe_util.h"
reset_src

# 桩53：单测缺"多喂被挡住"这条**反向断言** ——
# 只判"短喂照喂"时，"预填长于声明量"那条路照样绿，而它正是本处的故障本身。
"$PY" - tools/root_literal_probe_test.cpp <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        ck(\"反向：预填**长于**声明量且不是其前缀 -> usable=0（多喂被挡住）\","
assert s.count(old) == 1, "stub53: 没找到反向断言"
s = s.replace(old, "        ck(\"stub53: 反向断言被删\",", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩53（单测缺'多喂被挡住'的反向断言）" "多喂被挡'反向断言被守卫钉住"
reset_src

# 桩54：转义表漏掉 `]`（退回"只转义四个字符"的旧形态）。
# 这条**真机看不出来**（Qwen3 的 41B 后缀只含换行），离线是唯一会红的地方 ——
# 而它的后果是"换一种模板就静默失效或写出语法错的 GBNF"。
"$PY" - "$PROBE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            case ']':  out += \"\\\\]\";  break;"
assert s.count(old) == 1, "stub54: 没找到 ] 的转义分支"
s = s.replace(old, "            // stub54: ] 的转义被删", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩54（转义表漏 ] = 换模板就静默失效/写出语法错 GBNF）" "转义表含右方括号的转义分支"
reset_src

# 桩55：转义表漏掉 `-`。
"$PY" - "$PROBE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            case '-':  out += \"\\\\-\";  break;"
assert s.count(old) == 1, "stub55: 没找到 - 的转义分支"
s = s.replace(old, "            // stub55: - 的转义被删", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩55（转义表漏 -）" "转义表含短横的转义分支"
reset_src

# 桩56：日志落点行退回 `strlen(gbnfFinal)`（只改这一处时挂载行仍绿 —— 真机照样编不过）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "         gbnfFinal.size(), n_prefill, fed_prefill_bytes);"
assert s.count(old) == 1, "stub56: 没找到落点行"
s = s.replace(old, "         strlen(gbnfFinal), n_prefill, fed_prefill_bytes);", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩56（落点行退回 strlen(gbnfFinal)：一处编不过就够）" "日志报的是 gbnfFinal.size()"
reset_src

# 桩57：`[mark]` 那行报**原件**字节数（真机 0.9.98 同一轮出现 939B 与 916B 两个数）。
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    jlog("[schema] 已挂载 grammar 采样器（GBNF %zuB，预填 %d tok / %zuB）",\n         gbnfFinal.size(), n_prefill, fed_prefill_bytes);'
assert s.count(old) == 1, "stub57: 没找到落点 mark 行"
s = s.replace(old, '    jlog("[schema] 已挂载 grammar 采样器（GBNF %zuB，预填 %d tok / %zuB）",\n         strlen(gbnf), n_prefill, fed_prefill_bytes);', 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩57（mark 行报原件字节数 = 同一轮两个 GBNF 数字）" "mark 行也报 gbnfFinal"
reset_src

# 桩58：剥离函数对 None 也动手（改了"本身就带围栏"的普通文本的字节）。
# 这是剥围栏这条特性最危险的一类回归：它让"没要求结构化输出"的请求输出被改。
"$PY" - "$PURE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (f is ResponseFormat.None) return content"
assert s.count(old) == 1, "stub58: 没找到 None 短路"
s = s.replace(old, "        // stub58: None 短路被删", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩58（剥围栏对 None 也动手 = 改普通文本字节）" "未要求结构化输出时原样下发"
reset_src

# 桩59：把"剥后必须仍是 JSON"那条判据删掉 —— 于是残段会被当完整 JSON 发出去。
# 它是"宁可让调用方看见围栏，也不发残段"的**唯一**守卫。
"$PY" - "$PURE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        if (!inspectJson(stripped).first) return content"
assert s.count(old) == 1, "stub59: 没找到剥后校验"
s = s.replace(old, "        // stub59: 剥后校验被删", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩59（剥后不校验 = 残段当完整 JSON 发出去）" "剥后非 JSON 时退回原件"
reset_src

# 桩60：只在 chat 端点剥离、漏掉裸补全端点（后者的 json_schema 仍是老行为）。
"$PY" - "$HTTP" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "                        val rawOut = sb.toString()\n                        val delivered = JsonSchemaFormat.stripFenceForDelivery(respFormat, rawOut)"
assert s.count(old) == 1, "stub60: 没找到裸补全端点的剥离处"
s = s.replace(old, "                        val rawOut = sb.toString()\n                        val delivered = rawOut", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩60（裸补全端点漏接 = 那边仍是老行为）" "两个生成端点都下发前剥离"
reset_src

# 桩61：把剥离挪到**探针之前** —— 探针从此记的不是"模型真吐了什么"，
# 而是"我们改成了什么"。这正是本仓库反复栽的那类"读数被行为污染"。
# ⚠ 不整段硬编码注释：只按"探针那一行 -> 剥离块结尾那一行"之间的**原文**做位移，
#   这样注释改动不会让桩自己失效（桩失效 = 这条自测变成假绿，比没有更糟）。
"$PY" - "$HTTP" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
probe = "                    emitLog(JsonSchemaFormat.probeBody(JsonSchemaFormat.reqTagOf(id), sb.toString()))\n"
endln = "                    if (delivered !== rawOut) { sb.setLength(0); sb.append(delivered) }\n"
i = s.index(probe)
j = s.index(endln, i) + len(endln)
block = s[i:j]
assert block.count(probe) == 1 and "stripFenceForDelivery" in block, "stub61: 块内容不符"
rest = block[len(probe):]
s = s[:i] + rest + probe + s[j:]
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩61（剥离挪到探针之前 = 探针记的不是模型原话）" "剥离排在探针"
reset_src

# ── 10. 收尾：还原后必须恢复全绿 ────────────────────────────────────────
must_green "收尾：还原源码后"

echo ""
if [ "$bad" = "0" ]; then echo "=== 结构化输出守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 结构化输出守卫自测：PASS $ok / FAIL $bad ==="; exit 1
