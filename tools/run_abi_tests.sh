#!/bin/sh
# ABI 边界守卫：钉住 chat_abi.h 的存在与内容，并防"有人又绕回去裸调"。
#
# 为什么要有这一份：PR #13 那个"带 tools 必闪退"的根因是 C++ ABI 跨边界不兼容
# （libstdc++ 的 St7__cxx11 vs libc++ 的 NSt6__ndk1）。症状是**运行时 SIGSEGV**，
# 不是编译错误，所以只靠 review 很容易再犯。这里用静态断言把规则钉死：
#   1. 只允许 chat_abi.h 一个文件 include vendor 的 chat.h；
#   2. chat_abi.h 里的 static_assert 必须覆盖 libc++ / json 版本 / 结构体偏移三类；
#   3. llama_jni.cpp 不许再出现裸的 chat/include 相对引用。
set -e
cd "$(dirname "$0")/.."
CPP=app/src/main/cpp/llama_jni.cpp
ABI=app/src/main/cpp/chat_abi.h
ABICPP=app/src/main/cpp/chat_abi.cpp

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "chat_abi.h 存在"                      "[ -f \$ABI ]"
c "chat_abi.cpp 存在"                    "[ -f \$ABICPP ]"
c "只有 chat_abi.h 能 include chat.h"    "[ \$(grep -rl 'chat/include/chat.h' app/src/main/cpp --include='*.h' --include='*.cpp' | wc -l) -eq 1 ]"
c "llama_jni.cpp 走 chat_abi.h"          "grep -q '#include \"chat_abi.h\"' \$CPP"
c "禁止 libstdc++（__GLIBCXX__ 编译期报错）" "grep -q 'defined(__GLIBCXX__)' \$ABI"
c "强制 libc++（_LIBCPP_VERSION 检查）"   "grep -q '_LIBCPP_VERSION' \$ABI"
c "钉住 std::string == 24B"              "grep -q 'sizeof(std::string) == 24' \$ABI"
c "钉住 json 必须 3.12.0"                "grep -q 'NLOHMANN_JSON_VERSION_MAJOR == 3' \$ABI"
c "钉住 inputs 总尺寸 168B"              "grep -q 'sizeof(common_chat_templates_inputs) == 168' \$ABI"
c "逐字段 offsetof 断言 >= 14 条"         "[ \$(grep -c 'offsetof(common_chat_templates_inputs' \$ABI) -ge 14 ]"
c "有运行期 self_check"                  "grep -q 'const char \\* self_check' \$ABI && grep -q 'abi::self_check' \$CPP"
c "backendInit 里调了 self_check"        "sed -n '/backendInit/,/^}/p' \$CPP | grep -q 'abi::self_check'"
c "chat_abi.cpp 进 CMake 源列表"          "grep -q 'chat_abi.cpp' app/src/main/cpp/CMakeLists.txt"
c "解析失败哨兵只有一个定义"              "[ \$(grep -c 'kNoToolCalls = \"null\"' \$CPP) -eq 1 ]"
c "apply/parse 都带 try 兜住库内异常"     "[ \$(grep -c 'catch (\\.\\.\\.)' \$CPP) -ge 6 ]"
c "ABI 扫描脚本存在"                      "[ -x tools/check_abi.py ]"
# 这一条是本轮血案的直接原因：自检的 string 布局判据曾被写反（读末字节而非首字节），
# 在真 libc++ ABI v1 上恒误报，把不存在的 ABI 故障当成根因追了好几轮。
c "string 布局判据测试存在"                "[ -f tools/string_layout_test.cpp ]"
c "string 布局判据测试可执行"              "[ -x tools/run_string_layout_tests.sh ]"
c "自检读首字节而非末字节"                  "[ \$(grep -c 'buf\[0\]' app/src/main/cpp/chat_abi.cpp) -ge 1 ]"

if [ "$bad" -eq 0 ]; then
    echo "=== ABI 边界守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== ABI 边界守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
