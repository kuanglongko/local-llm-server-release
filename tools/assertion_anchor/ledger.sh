#!/bin/sh
# 用**替换 grep 的记账 shim**跑一个 guard，把每条 grep 真实收到的 pattern 与文件记下来。
#
# 为什么必须跑而不是静态解析：判据体是两层转义的（eval 的双引号层 + grep 的单引号层），
# python 读源码拿到的是**未展开**的形式，与 grep 真收到的对不上 —— 静态版误报过 46 条
# 假"零命中"（pattern 里的 `\"`、`\\`、反引号全被多算一层）。只有让 sh 自己展开才对。
#
# 输出（TSV）：`POS  <pattern>  <file>` —— grep 正常退出（命中）
#              `NEG  <pattern>  <file>` —— grep 非零退出（未命中）
# 记住：POS/NEG 是"这次真跑的结果"，正是我们要判断的对象。
set -e
GUARD="$1"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/grep" <<'G'
#!/bin/sh
# 原样转交真 grep，同时把"pattern + 文件 + 真结果"记下来。
out=$(/usr/bin/grep "$@")
rc=$?
# 取 pattern：第一个非 `-` 开头的参数；文件：最后一个参数
pat=""; file=""; flags=""
for a in "$@"; do
  case "$a" in
    -*) flags="$flags $a" ;;
    *) if [ -z "$pat" ]; then pat="$a"; fi; file="$a" ;;
  esac
done
if [ "$rc" = "0" ]; then st=POS; else st=NEG; fi
printf '%s\t%s\t%s\t%s\n' "$st" "$pat" "$file" "$flags" >> "$GREP_LOG"
[ -n "$out" ] && printf '%s\n' "$out"
exit $rc
G
chmod +x "$TMP/bin/grep"
GREP_LOG="$TMP/log" PATH="$TMP/bin:$PATH" sh "$GUARD" >/dev/null 2>&1 || true
cat "$TMP/log" 2>/dev/null || true
