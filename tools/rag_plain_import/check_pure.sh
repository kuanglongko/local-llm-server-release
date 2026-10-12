#!/bin/sh
# 判 `RagArchive.decodePlainText` 是**纯逻辑**：不碰存储 / 上下文。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么是 sh + grep，而不是 python 里那个正经的解析器
# ═══════════════════════════════════════════════════════════════════════════
# 这条判据要防的是"这一整块被塞进一个 `Context` 参数，于是宿主再也跑不了单测"。
# 它的**假绿风险**在于"函数体没被真正截出来"（截到一半 / 截过头都见过）——
# 一旦如此，`grep` 一个都不会命中，判据恒绿。
# 所以这里用**最简单、没有解析器的**写法取函数体：从签名行起、到第一个"行首
# 就是右括号"的收尾行为止。它不认字符串里的花括号，代价是**可能截多了** ——
# 于是判据偏向"误报红"而不是"静默绿"，这正是这一族该有的失败方向
#（假红被人看一眼就知道是假的；假绿没人会看）。
#
# 取体失败（空体）也**报红**：判据取不到东西时不许当"通过"。
#
# 运行：sh tools/rag_plain_import/check_pure.sh <RagArchive.kt> <函数名>
set -e
FILE=$1
NAME=$2

# `-v sig=...` 走的是 awk 的 ERE：函数名里若带正则元字符会编译不过
#（本轮实测：`fun decodePlainText(` 里的 `(` 让 awk 直接报 missing ')'）。
# 所以用**字符串包含**判定，不用正则。
# 从签名行起，到位数**等于签名缩进**的那一行收尾 `}` 为止。
# ⚠ 不能用"第一个行首就是 `}` 的行"：函数体里的 `if (...) { ... }` 在同一个
#   缩进下也以 `}` 收尾，于是截出来的是一小段 —— 本轮实测就撞过（只截到第一个
#   `if` 的收尾），判据恒红且看起来像"源码写错了"。判据的价值全在"体取对了吗"。
#
# awk 里比较"这一行的缩进是否恰等于签名缩进"要拆成两步（ERE 不支持
# `{0,N}` 那种量词配动态数字），所以这里把缩进算好后用**长度相等**来判：
# `match` 给出第一个非空格的位置，减 1 就是缩进宽度。
body() { awk -v sig="fun $NAME(" '
  index($0, sig) > 0 && !start { start = NR; indent = match($0, /[^ ]/) - 1 }
  start && NR>=start {
    print
    if (NR>start && $0 ~ /^[ \t]*}[ \t]*$/) {
      cur = match($0, /[^ \t]/) - 1
      if (cur == indent) exit
    }
  }' "$FILE"; }

B=$(body)
if [ -z "$B" ]; then
  echo "取不到 $NAME 的函数体（锚点漂了）—— 判据取不到东西时不许当通过"
  exit 1
fi

# 允许出现的"存储/上下文"取值：一个都不许有。
if printf '%s' "$B" | grep -qE 'Context|Uri|ContentResolver|openInputStream|filesDir|getExternalFilesDir'; then
  echo "$NAME 不是纯逻辑：函数体里出现了存储 / 上下文取值"
  printf '%s' "$B" | grep -nE 'Context|Uri|ContentResolver|openInputStream|filesDir|getExternalFilesDir'
  exit 1
fi

# 反面自证：同一个取体函数对着 `readText` 必须**取得到且判红** ——
# 否则"本判据恒绿"这件事本身就没人查（取体函数坏了会静默放过一切）。
if [ "$NAME" = "decodePlainText" ]; then
  NAME=readText
  RB=$(body)
  if [ -z "$RB" ]; then
    echo "取体函数对 readText 取不到东西 —— 那么它对 decodePlainText 也可能是截空的（假绿）"
    exit 1
  fi
  if ! printf '%s' "$RB" | grep -qE 'ContentResolver|openInputStream'; then
    echo "取体函数对着 readText 也判绿了 —— 本判据分辨不出 纯 / 不纯（等于没写）"
    exit 1
  fi
fi
exit 0
