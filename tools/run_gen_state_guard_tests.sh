#!/bin/sh
# 自测 `tools/run_gen_state_guard.sh`：把它对着**已知该红**的桩源码跑，
# 断言"该红的红、点得出是哪一条"，还原后必须恢复全绿。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一份防的是"守卫自己失效"，而本守卫的失效模式特别容易发生
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫里有一条**负向**断言（"首行不得把采样器复位当成这一轮做的事"）。
# 负向断言天生恒绿：pattern 一旦写错（比如写成源码里根本不存在的措辞），
# "不出现"就永远成立，这条判据从此零信息量 —— 而它在 CI 里照样打印 PASS。
# 所以桩② 必须**真的把那句谎话写回去**，守卫要变红；只跑一次看它绿是发现不了的。
#
# 另一处是"reset 归属"那段的三分支：`有调用点 / 有标注 / 都没有`。
# 前两支都该绿，第三支必须红 —— 桩③ 把两条依据同时抽掉，判"都没有"这一支真的可达。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 运行：sh tools/run_gen_state_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_gen_state_guard.sh
JNI=app/src/main/cpp/llama_jni.cpp

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$JNI" "$TMP/jni.bak"
restore() { cp "$TMP/jni.bak" "$JNI"; rm -rf "$TMP"; }
# 退出即还原（EXIT / INT / TERM / HUP 都走同一个）——只 trap EXIT 时
# CI 的 kill（默认 TERM）不会触发，打桩源码就会留在工作区。
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}
must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *) ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}
must_green() { # 名字
    if run_guard; then ck "$1：守卫保持全绿" 1
    else ck "$1：守卫保持全绿（实际红了）" 0; sed -n '1,40p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,40p' "$TMP/guard.out"; fi

# ── 1. 桩①：链不再整条重建（新对象这条依据被抽掉）────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    if (S.smpl) { llama_sampler_free(S.smpl); S.smpl = nullptr; }\n    llama_sampler * ch = llama_sampler_chain_init(llama_sampler_chain_default_params());\n"
new = "    // 桩：复用旧链，不再重建\n    llama_sampler * ch = S.smpl ? S.smpl : llama_sampler_chain_init(llama_sampler_chain_default_params());\n"
assert s.count(old) == 1, "桩①：没找到 newSampler 的 free + chain_init"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（链不再重建）" "newSampler 先释放旧链"
restore_src() { cp "$TMP/jni.bak" "$JNI"; }
restore_src

# ── 2. 桩②：把那句谎话写回首行（负向断言必须真能被打脸）──────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    // 本轮开始前的状态清理：清 pending / n_rem / gen_in_ledger，并清上一次的 abort 标志。\n"
new = "    // 本轮开始前的采样器复位（stop 状态机等）与上一次的 abort 标志清理。\n"
assert s.count(old) == 1, "桩②：没找到修复后的首行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（首行又声称采样器复位）" "首行不把「采样器复位」当成这一轮做的事"
restore_src

# ── 3. 桩③：reset 的复位依据两边同时抽掉（"都没有"这一支必须可达）────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    //   代价：`stop_sampler_reset`（iface 里的 .reset）因此**当前没有调用点**，\n"
new = "    //   代价：`stop_sampler_reset`（iface 里的 .reset）。\n"
assert s.count(old) == 1, "桩③：没找到『当前没有调用点』那句标注"
s = s.replace(old, new, 1)
# 同时把 emitted 的清零说明也拿掉（它是这条判据的另一半依据之一）
old2 = "    // emitted 必须一起清零：它是\"generated 里已下发到哪\"的账，\n"
assert s.count(old2) == 1, "桩③：没找到 emitted 说明"
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩③（reset 依据两边都抽掉）" "reset 既没有调用点、也没有标注说明"
restore_src

# ── 4. 桩④：iface 里的 .reset 接线被拿掉 ────────────────────────────────
"$PY" - "$JNI" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    /* .reset               = */ stop_sampler_reset,\n"
new = "    /* .reset               = */ nullptr,\n"
assert s.count(old) == 1, "桩④：没找到 .reset 接线"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩④（.reset 接线被拿掉）" "reset 挂进 iface"
restore_src

# ── 5. 还原后必须恢复全绿（防"桩没还原干净"）────────────────────────────
must_green "还原后"

echo ""
if [ "$bad" -eq 0 ]; then
    echo "=== 生成轮次状态守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 生成轮次状态守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
