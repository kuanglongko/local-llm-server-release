#!/bin/sh
# 自测 `tools/run_stop_match_guard.sh`：对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红、且指得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条测试防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫的判据全是 `grep` / 小段 python 形式的**结构断言**，它有两个自欺模式：
#   1. 恒真：pattern 写成一段到处都有的文本，改回故障版本也照样 PASS；
#   2. 恒假后被静音：pattern 与真源码差一个空格，守卫永远 FAIL，很快有人
#      把 `exit 1` 改成 `|| true` —— 那时它就彻底没用了。
# 唯一有效的做法是**对着已知该红的桩跑**：把源码改成故障版本，守卫必须变红，
# 且必须指出是哪一条；再改回来必须恢复全绿。
#
# 桩全部来自**本轮真正的旧写法**（见 git show cefb5c0:app/src/main/cpp/stop_sequences.h），
# 不是编出来的近似。桩是"就地改真源码 + 改完还原"，不维护副本。
#
# 运行：sh tools/run_stop_match_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_stop_match_guard.sh
SS=app/src/main/cpp/stop_sequences.h
TEST=tools/stop_sampler_test.cpp

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$SS" "$TMP/ss.bak"
cp "$TEST" "$TMP/test.bak"
JNI=app/src/main/cpp/llama_jni.cpp
cp "$JNI" "$TMP/jni0.bak"
restore_all() { cp "$TMP/ss.bak" "$SS"; cp "$TMP/test.bak" "$TEST"; cp "$TMP/jni0.bak" "$JNI"; }
restore() { restore_all; rm -rf "$TMP"; }
trap restore EXIT

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}

must_red() { # 名字 期望命中的「守卫条目描述」关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0
    else ck "$name：守卫变红" 1; fi
    # 判据：在**FAIL 行**里能找到这条描述。
    # 只 grep -F key（不加 FAIL 约束）会把该条目的 PASS 行也算进去 ——
    # 那样"变红了但没指出是哪一条"会被误判成通过。
    if grep -F "$key" "$TMP/guard.out" | grep -q 'FAIL'; then
        ck "$name：指到了「$key」" 1
    else
        ck "$name：指到了「$key」" 0
    fi
}

# 0) 先自证：未改动时守卫必须全绿（否则后面"变红"没有意义）
if run_guard; then ck "基线（未改动）全绿" 1; else ck "基线（未改动）全绿" 0; fi

# ── 桩①：命中判据退回"只看 next 前 rem 字节"（漏判根因）──
restore_all
"$PY" - "$SS" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'if (full.compare(full.size() - s.size(), s.size(), s, 0, s.size()) == 0) {'
new = 'if (full.compare(full.size() - s.size(), s.size(), s, 0, s.size()) == 0) { // stub\n                if (next.compare(0, rem, s, have, rem) == 0) { (void)rem; (void)have; }'
assert old in s
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
must_red "桩①：只看 next 前 rem 字节（漏判）" "旧的「next.compare(0, rem, ...)」写法已绝迹"

# ── 桩②：暂扣判据退化成"full 整体是前缀"（丢掉后缀扫描）──
restore_all
"$PY" - "$SS" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "if (full.compare(i, tail, s, 0, tail) == 0) return true;"
new = "if (full.compare(0, tail, s, 0, tail) == 0) return true; (void)i;"
assert old in s
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
must_red "桩②：暂扣退化成 full 整体前缀（跨 generated 的 stop 失效）" "暂扣判据按后缀扫描（full[i:] 是 s 的真前缀）"

# ── 桩③：命中丢弃范围退回"整个 token 一并丢" ──
restore_all
"$PY" - "$SS" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "st.generated = full.substr(0, hit_cut_index(st, next));"
new = "st.withheld.clear(); (void)full;  // stub: 整个 token 一并丢"
assert old in s
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
must_red "桩③：命中丢整个 token（正文被吞）" "advance 命中分支按 hit_cut_index 切，而不是清空 withheld"

# ── 桩④：can_continue_fast 退回"拿 generated 枚举后缀" ──
restore_all
"$PY" - "$SS" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "if (!is_prefix_of(s, st.withheld)) continue;"
new = "const std::string cur = st.full(); if (!is_prefix_of(s, cur)) continue;"
assert old in s
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
must_red "桩④：can_continue_fast 拿 generated 去算（判据分叉）" "can_continue_fast 按 withheld 与 stop 的前缀关系判"

# ── 桩⑤：advance 退回裸 `withheld += next`（不回退 generated 尾巴）──
restore_all
"$PY" - "$SS" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "split_release_withhold(st, next);"
new = "st.withheld += next;"
assert old in s
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
must_red "桩⑤：bare withheld += next（起点留在 generated 里）" "advance 用 split_release_withhold 推进暂扣（不是裸 withheld += next）"

# ── 桩⑥：宿主测退回"逐字符喂"（掩盖 token 内部起点）──
# run 与 run_stream 一起改：只改一处的话，守卫只钉了一条路径，
# 另一条退回逐字符就没人管 —— 而 stream 侧恰恰是最容易露"前缀泄漏"的地方。
restore_all
"$PY" - "$TEST" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "for (const std::string & p : pieces) {"
new = "for (char c : pieces.empty() ? std::string() : pieces[0]) { std::string p(1, c);"
assert s.count(old) == 2, s.count(old)
open(p, 'w', encoding='utf-8').write(s.replace(old, new))
PY
must_red "桩⑥：宿主测逐字符喂（掩盖 token 内部起点）" "宿主测的 run 按整个 token 文本喂状态机"
must_red "桩⑥b：宿主测逐字符喂（stream 侧）" "宿主测的 run_stream 也按整个 token 文本喂状态机"

# ── 桩⑦：删掉「起点在已放行区内」的用例（覆盖退回旧子集）──
restore_all
"$PY" - "$TEST" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("起点在已放行区内", "X起点X")
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩⑦：删掉「起点在已放行区内」用例" "宿主测构造了「起点在已放行区内」的用例"

# ── 桩⑧：删掉「stop 不在结尾」的假命中用例 ──
restore_all
"$PY" - "$TEST" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
s = s.replace("G-1b：stop 不在结尾", "X")
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩⑧：删掉「stop 不在结尾」用例" "宿主测构造了「stop 不在结尾」的假命中用例"

# ── 桩⑨：llama_jni.cpp 抹掉 emitted 与回退的耦合说明（防止后人"顺手删注释"）──
restore_all
cp app/src/main/cpp/llama_jni.cpp "$TMP/jni.bak"
"$PY" - app/src/main/cpp/llama_jni.cpp <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert "split_release_withhold" in s
s = s.replace("split_release_withhold", "X", 1)
open(p, 'w', encoding='utf-8').write(s)
PY
must_red "桩⑨：抹掉 emitted 与回退的耦合说明" "llama_jni.cpp 写明 split_release_withhold 与 emitted 的耦合"
cp "$TMP/jni.bak" app/src/main/cpp/llama_jni.cpp

# 还原后必须恢复全绿
restore_all
if run_guard; then ck "还原后恢复全绿" 1; else ck "还原后恢复全绿" 0; fi

printf '\n'
if [ "$bad" -eq 0 ]; then
    echo "=== stop 匹配判据守卫 自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== stop 匹配判据守卫 自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
