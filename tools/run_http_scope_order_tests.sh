#!/bin/sh
# 自测 `tools/run_http_scope_order.py`：拿**打桩源文件**跑它，
# 断言"该红的红、指得出是哪一条，且该绿的不误报"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么这条自测必须有
# ═══════════════════════════════════════════════════════════════════════════
# 这份检查是**词法**的：它不认识类型、作用域与重载，只比较行号先后。
# 词法检查有两个自欺方向，且都不报错：
#   ① **恒真**：把"读者"匹配得太宽（命名实参 `method = ""`、字符串键
#      `put("id", ...)`、跨行 lambda 形参 `{ t, asReason ->`），
#      于是真源码里到处"先用后声明"，守卫永远红 → 很快被 `|| true` 静音；
#   ② **恒假**：只按整行 grep 找同名，漏掉断行/缩进变化，于是它永远绿，
#      而 CI 照样红。
# 开发这一版时**两种都真踩到了**：第一版误报 19 条（①，把上一个函数里的
# 同名局部变量也算进前缀），改完又剩 1 条（②的对偶，跨行 lambda 形参漏判）。
# 只对着真源码跑一次看它绿，发现不了这两件事 —— 必须对着已知该红的桩跑。
#
# 桩是"写一份最小 .kt + 就地改真源码"，不维护整份副本。
# 不需要工具链，只要 sh + python3。
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
CHECK=tools/run_http_scope_order.py
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HTTP" "$TMP/http.bak"
restore() { cp "$TMP/http.bak" "$HTTP"; rm -rf "$TMP"; }
trap restore EXIT
reset_src() { cp "$TMP/http.bak" "$HTTP"; }

run_check() {   # run_check <文件>  -> 0 绿 / 1 红
    set +e
    "$PY" "$CHECK" "$1" > "$TMP/out" 2>&1
    rc=$?
    set -e
    return $rc
}

# ── 桩①（该红）：最小复刻本轮 CI 故障 ──────────────────────────────
# 故意把读放在声明之前，两行就够。这是"该红的必须红"的底线。
cat > "$TMP/stub1.kt" <<'KT'
object S {
    fun f(): Int {
        val y = x + 1
        val x = 2
        return y
    }
}
KT
if run_check "$TMP/stub1.kt"; then ck "桩①（最小 read-before-declare）该红" 0;
else
    grep -q "FAIL.*'x'" "$TMP/out" && ck "桩①（最小 read-before-declare）该红" 1 \
        || { echo "  桩①红了但没点出 'x'："; cat "$TMP/out"; ck "桩①点得出是哪一条" 0; }
fi

# ── 桩②（该绿）：正常顺序，不许误报 ────────────────────────────────
cat > "$TMP/stub2.kt" <<'KT'
object S {
    fun f(): Int {
        val x = 2
        val y = x + 1
        return y
    }
}
KT
if run_check "$TMP/stub2.kt"; then ck "桩②（顺序正确）该绿" 1; else ck "桩②（顺序正确）该绿" 0; fi

# ── 桩③（该绿）：同名局部变量在**另一个函数**里出现（第一版就是这里误报）──
# 这是"恒真"方向的守门：若实现把上一个函数的前缀也算进来，这条会红。
cat > "$TMP/stub3.kt" <<'KT'
object S {
    fun a(): Int {
        val shared = 1
        return shared
    }

    fun b(): Int {
        val shared = 2
        return shared
    }
}
KT
if run_check "$TMP/stub3.kt"; then ck "桩③（不同函数里的同名局部变量）不误报" 1;
else echo "  桩③误报了："; cat "$TMP/out"; ck "桩③（不同函数里的同名局部变量）不误报" 0; fi

# ── 桩④（该绿）：命名实参 / 字符串键 / lambda 形参都是"声明位" ────────
# 这三类是**真源码里的实际形态**，误判它们 = 守卫恒红。
cat > "$TMP/stub4.kt" <<'KT'
object S {
    fun put(k: String, v: String) {}

    fun f(cur: String?): Int {
        cur?.let { alias ->
            put("id", alias)
        }
        val item = make(method = "", path = "")
        return item
    }

    fun make(method: String, path: String): Int = 0

    fun g(piece: String): Int {
        var out = ""
        if (piece.isNotEmpty()) run(piece) { t, asReason ->
            if (asReason) out = t else out = t
        }
        return out.length
    }

    fun run(s: String, cb: (String, Boolean) -> Unit) {}
}
KT
if run_check "$TMP/stub4.kt"; then ck "桩④（命名实参/字符串键/lambda 形参）不误报" 1;
else echo "  桩④误报了："; cat "$TMP/out"; ck "桩④（命名实参/字符串键/lambda 形参）不误报" 0; fi

# ── 桩⑤（该红）：对真源码就地打桩 = 本轮 CI 红的原样复刻 ─────────────
# 与 run_http_hardening_guard_tests.sh 的桩⑪同一个桩（那边断言"守卫变红"，
# 这边断言"检查器本身指得出名字与行号"）。
"$PY" - $HTTP <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
i = s.index('            // ---- CORS 白名单判定')
k = s.index('            // 畸形请求**必须**挡在路由与鉴权之前')
block = s[i:k]
s = s[:i] + s[k:]
anchor = '            // /health 与 /v1/models 为高频轮询端点'
assert s.count(anchor) == 1, "stub5: anchor not found"
j = s.index(anchor)
open(p, "w", encoding="utf-8").write(s[:j] + block + s[j:])
PYEOF
set +e
"$PY" "$CHECK" "$HTTP" > "$TMP/out" 2>&1
rc=$?
set -e
if [ "$rc" != "0" ]; then
    grep -q "corsOrigin" "$TMP/out" && ck "桩⑤（真源码复刻 CI 故障）红了并点出 corsOrigin" 1 \
        || { echo "  桩⑤红了但没点出 corsOrigin："; cat "$TMP/out"; ck "桩⑤点得出 corsOrigin" 0; }
else ck "桩⑤（真源码复刻 CI 故障）该红" 0; fi
reset_src

# ── 收尾：还原后必须恢复全绿 ───────────────────────────────────────
if run_check "$HTTP"; then ck "收尾：还原源码后检查恢复全绿" 1;
else echo "  收尾仍红："; cat "$TMP/out"; ck "收尾：还原源码后检查恢复全绿" 0; fi

echo ""
if [ "$bad" = "0" ]; then echo "=== 局部变量顺序检查自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 局部变量顺序检查自测：PASS $ok / FAIL $bad ==="; exit 1
