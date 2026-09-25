#!/bin/sh
# 自测 `tools/run_kv_cache_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 `grep -q '某段源码'` 形式的**存在性**断言，它有两个自欺模式：
#
#   1. **恒真**：pattern 写成到处都有的文本（例如只 grep `kv_invalidate`
#      而不断言**每条路径都调了它**），于是把作废逻辑删掉照样 PASS；
#   2. **恒假后被静音**：pattern 与源码差一个字符，守卫永远 FAIL，
#      很快就有人把 `exit 1` 改成 `|| true` —— 那时它彻底没用了。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑：把源码临时改成故障版本，守卫必须变红、且必须指出是哪一条；
# 再改回来，必须恢复全绿。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）—— 与 run_think_routing_guard_tests.sh 同一套路。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_kv_cache_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_kv_cache_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/kv_prefix.h
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"; cp "$HDR" "$TMP/hdr.bak"
cp "$HTTP" "$TMP/http.bak"; cp "$ENG" "$TMP/eng.bak"
restore() {
    cp "$TMP/jni.bak" "$JNI"; cp "$TMP/hdr.bak" "$HDR"
    cp "$TMP/http.bak" "$HTTP"; cp "$TMP/eng.bak" "$ENG"
    rm -rf "$TMP"
}
trap restore EXIT

run_guard() { # -> 打印输出，返回守卫退出码
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}
reset_src() { cp "$TMP/jni.bak" "$JNI"; cp "$TMP/hdr.bak" "$HDR"
              cp "$TMP/http.bak" "$HTTP"; cp "$TMP/eng.bak" "$ENG"; }

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

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

# ── 1. 桩①：换模型时忘了作废账本（本次功能唯一一个"静默答非所问"入口）─────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    kv_invalidate("模型卸载/换模型");\n'
assert s.count(old) == 1, "桩①：没找到卸载路径的作废调用"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩①（换模型不作废账本）" "模型卸载/换模型时作废账本"
reset_src

# ── 2. 桩②：生成出来的 token 不记账（只慢，永远不会变红）──────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    if (S.kv_valid) { S.last_tokens.push_back((long long) tok); S.gen_in_ledger++; }\n"
assert s.count(old) == 1, "桩②：没找到账本追加那行"
open(p, 'w', encoding='utf-8').write(s.replace(old, "", 1))
EOF
must_red "桩②（生成 token 不记账）" "nativeStep 把采样出的 token 追加进账本"
reset_src

# ── 3. 桩③：prefill 失败路径上把账本置成了"有效" ───────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            kv_invalidate("prefill decode 失败");'
new = '            S.kv_valid = true;'
assert s.count(old) == 1, "桩③：没找到 decode 失败分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（prefill 失败还置有效）" "prefill decode 失败时作废账本"
reset_src

# ── 4. 桩④：判据把整段 prompt 都当已缓存（采样步没有 logits）──────────────
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    int reuse = std::min(lcp, p.total - 1);"
new = "    int reuse = lcp;"
assert s.count(old) == 1, "桩④：没找到 reuse 的 min 约束"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（整段当已缓存）" "复用长度必须 < 整段 prompt"
reset_src

# ── 5. 桩⑤：/health 少报一个 KV 字段（回归里静默失效就看不出来了）─────────
"$PY" - "$HTTP" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('"kv_reuse_tokens":$kvReuse') == 1, "桩⑤：没找到 kv_reuse_tokens 字段"
open(p, 'w', encoding='utf-8').write(s.replace('"kv_reuse_tokens":$kvReuse', '"kv_xx":0', 1))
EOF
must_red "桩⑤（/health 少报 kv_reuse_tokens）" "HttpApi /health 报 kv_reuse_tokens"
reset_src

# ── 6. 桩⑥：缓存分支让请求失败（把纯加速功能变成可用性风险）───────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        llama_memory_seq_rm(mem, 0, -1, -1);\n        S.n_used = 0;\n        jp(\"[kv] %s"
new = "        llama_memory_seq_rm(mem, 0, -1, -1);\n        S.n_used = 0;\n        if (plan.total > 0) return JNI_FALSE;\n        jp(\"[kv] %s"
assert s.count(old) == 1, "桩⑥：没找到未命中分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（缓存分支让请求失败）" "复用计划的应用段不产生失败返回"
reset_src

# ── 7. 桩⑦：去掉复用门槛（1~2 token 的假命中刷满日志）─────────────────────
"$PY" - "$HDR" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    if (lcp < kMinReuseTokens) return p;"
new = "    if (lcp < 1) return p;"
assert s.count(old) == 1, "桩⑦：没找到门槛判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（去掉复用门槛）" "门槛真的参与判定"
reset_src

# ── 8. 收尾：还原后必须恢复全绿 ─────────────────────────────────────────────
if run_guard; then ck "收尾：还原源码后守卫恢复全绿" 1
else ck "收尾：还原源码后守卫恢复全绿（实际红了 -> 有桩没还原干净）" 0
     sed -n '1,80p' "$TMP/guard.out"; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== KV 复用守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== KV 复用守卫自测：PASS $ok / FAIL $bad ==="; exit 1
