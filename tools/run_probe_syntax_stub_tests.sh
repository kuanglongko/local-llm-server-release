#!/bin/sh
# run_probe_syntax_tests.sh 的自测：把**真源码**改回 ccf0557 的原始字节，
# 断言语法检查必须变红，且红在"日志宏未定义"这一条上。
#
# 为什么必须有这一条：
#   这次真机编译失败（llama_jni.cpp 用了从未定义的 `LOGW`）之所以能穿过全套
#   守卫，是因为**没有任何一条判据会去解析日志宏**：
#     · 结构守卫锚的是"rename 在 open 之前"这类关系，LOGW 看着像 LOGI 的近亲；
#     · 桩头另抄了一份 LOGI/LOGE —— 源码里多一个宏，桩头这边照样自洽；
#     · probe_rotate_prev **不在**抽取单元里，"单元之外"等于"没人管"。
#   三处叠加的结果：`LOGW` 只出现在源码第 313 行，守卫全绿、GCC 报错。
#   所以这里要对着**那一次的真实字节**钉一发：改回 LOGW -> 必红。
#
# 本脚本与 run_probe_syntax_tests.sh 一样需要宿主 C++ 编译器；缺了则 skip（不算失败）。
#
# 运行：sh tools/run_probe_syntax_stub_tests.sh
cd "$(dirname "$0")/.."                     # 仓库根：下面的相对路径与守卫一致
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：本脚本会就地打桩真源码

CXX=${CXX:-g++}
command -v "$CXX" >/dev/null 2>&1 || { echo "SKIP  缺少 $CXX（本测试需要宿主 C++ 编译器）"; exit 0; }

JNI=app/src/main/cpp/llama_jni.cpp
ok=0; bad=0
chk() { # chk <描述> <期望:red|green>
    name="$1"; want="$2"
    if sh tools/run_probe_syntax_tests.sh >/tmp/probe_syntax_stub.out 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; sed 's/^/      /' /tmp/probe_syntax_stub.out | head -6; bad=$((bad+1)); fi
}

STASH=$(mktemp -d "${TMPDIR:-/tmp}/probe_syntax_stub_XXXXXX")
cp "$JNI" "$STASH/llama_jni.cpp"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; }
_exit_cleaned=0
_on_exit() { [ "$_exit_cleaned" = 1 ] && return 0; _exit_cleaned=1; restore; rm -rf "$STASH"; }
worktree_lock_acquire "$0"
trap _on_exit EXIT INT TERM HUP

# ① ccf0557 的原始字节：probe_rotate_prev 里那个从未定义的 LOGW。
restore
python3 - "$JNI" <<'PY'
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = 'LOGE("probe: 上代会话文件未能滚动'
assert old in s, "源码形状变了（这行已改回 LOGE 之外的样子？），本自测要一起更新"
io.open(p, "w", encoding="utf-8").write(s.replace(old, 'LOGW("probe: 上代会话文件未能滚动', 1))
PY
chk "ccf0557 原样：rotate 里用未定义的 LOGW" red

# ② 红要红在"宏未声明"上，不是"这段抽不出来"之类的别的原因。
restore
python3 - "$JNI" <<'PY'
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
s = s.replace('LOGE("probe: 上代会话文件未能滚动', 'LOGW("probe: 上代会话文件未能滚动', 1)
io.open(p, "w", encoding="utf-8").write(s)
PY
if sh tools/run_probe_syntax_tests.sh 2>&1 | grep -q "'LOGW' was not declared"; then
    echo "PASS  失败信息指得出是 LOGW 未声明（不是别的原因）"; ok=$((ok+1))
else
    echo "FAIL  失败信息里没有 \"'LOGW' was not declared\""; bad=$((bad+1))
fi

# ③ 反向：修复后的写法必须绿（判据不是"讨厌某种写法"）。
restore
chk "修复后的写法" green

restore
if [ "$bad" -eq 0 ]; then
    echo "=== 探针语法检查自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 探针语法检查自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
