#!/bin/sh
# 判据：HttpApi 里**每一条** `writeJson(out` 调用都必须把 corsOrigin 透传下去。
#
# 失败时打印行号（只报"有绕过"等于逼下一个人把这条守卫删掉）。
#
# 判据不是"看接下来 N 行里有没有 corsOrigin" —— 那是**会漏的**：
# 漏传的那条调用后面紧跟着的下一条调用里就有 corsOrigin，窗口一开大就把它算进去了
#（本守卫的自测里桩⑦就是这么抓出来的：把 modelsJson 那行的 corsOrigin 删掉，
#  按 8 行窗口判定照样全绿）。所以这里**按括号配对**截出这条调用自身的参数，
# 在它自己的范围内找 —— 换行不影响，也不会吃进邻居。
#
# 用法：sh tools/cors_writejson_guard.sh app/src/main/java/com/xiaowan/localinference/HttpApi.kt
# 退出码：0 = 全部透传；1 = 有绕过（并列出行号）。
HTTP=${1:-app/src/main/java/com/xiaowan/localinference/HttpApi.kt}
PY=${PYTHON:-python3}

if [ ! -f "$HTTP" ]; then
    echo "MISSING file: $HTTP"
    exit 1
fi
command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本判据只用标准库）"; exit 2; }

"$PY" - "$HTTP" <<'PYEOF'
import sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()
needle = "writeJson(out"
miss = []
total = 0
i = 0
while True:
    i = src.find(needle, i)
    if i < 0:
        break
    total += 1
    # 从 `writeJson(` 的左括号开始做括号配对，截出这条调用自身的参数串。
    j = src.index("(", i + len("writeJson"))
    depth = 0
    k = j
    while k < len(src):
        ch = src[k]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        k += 1
    call = src[j : k + 1]
    if "corsOrigin" not in call:
        miss.append(src.count("\n", 0, i) + 1)   # 1-based 行号
    i = k

if not miss:
    print("OK  %d 处 writeJson 调用全部透传 corsOrigin" % total)
    sys.exit(0)
for ln in miss:
    print("MISSING corsOrigin at %s:%d" % (path, ln))
sys.exit(1)
PYEOF
