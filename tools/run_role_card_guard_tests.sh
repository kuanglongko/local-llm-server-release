#!/bin/sh
# 自测 `tools/run_role_card_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是 grep/awk 的存在性与结构性断言，它有三个自欺模式：
#
#   1. **恒真**：pattern 写成到处都有的文本。本轮最容易恒真的两条是
#      "文件里出现过 `resetKvCache`"（清空/删除路径也在用）与
#      "文件里出现过 `不会跟着变`"（注释里就写着）。所以判据一律锚**函数体**。
#   2. **恒假后被静音**：pattern 与源码差一个字符 → 永远 FAIL → 有人
#      把 exit 1 改成 `|| true`。
#   3. **锚错位置**：钉"文件里出现过某函数"而不是"**在这条路径里**按这个
#      顺序做了这件事"。
#
# 桩全部来自"真正会写错的写法"：
#   · 切卡不作废 KV 账本（手机发烫，无提示）
#   · 会话只存卡片 id、不存内容快照（卡片一删人设静默没了）
#   · 改卡片顺手改所有会话（不可恢复，用户不知情）
#   · 写盘直接 writeText（写一半断电 = 整个卡片库消失）
#   · 读盘把 .tmp 也当库（上次写了一半的中间态被当成正式内容）
#   · 重填下拉不挡住框架触发的那一次选中（切会话静默摘卡）
#   · 坏卡静默丢、不计数
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_role_card_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_role_card_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
RC=$SRC/RoleCard.kt
RCS=$SRC/RoleCardStore.kt
SS=$SRC/SessionStore.kt
ACT=$SRC/EngineActivity.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$RC" "$TMP/rc.bak"; cp "$RCS" "$TMP/rcs.bak"
cp "$SS" "$TMP/ss.bak"; cp "$ACT" "$TMP/act.bak"
restore() { cp "$TMP/rc.bak" "$RC"; cp "$TMP/rcs.bak" "$RCS";
            cp "$TMP/ss.bak" "$SS"; cp "$TMP/act.bak" "$ACT"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/rc.bak" "$RC"; cp "$TMP/rcs.bak" "$RCS";
              cp "$TMP/ss.bak" "$SS"; cp "$TMP/act.bak" "$ACT"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

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
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：切卡**不**作废 KV 账本（手机发烫，零提示）───────────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
i = s.index("private fun pickCard(position: Int)")
j = s.index("private fun refreshCardSnapshot()", i)
seg = s[i:j]
old = "        LlmEngine.resetKvCache()\n"
assert seg.count(old) == 1, "桩①：pickCard 里没找到 resetKvCache"
open(p, 'w', encoding='utf-8').write(s[:i] + seg.replace(old, "", 1) + s[j:])
PY_EOF
must_red "桩①（切卡不作废 KV）" "★ 切卡作废 KV 账本"
reset_src

# ── 2. 桩②：会话只存 id、不存内容快照（卡片一删人设静默没了）─────────────
# ⚠ 这个桩必须动 **chatHistory 那条路径**，不能只把字段名删掉 ——
#   判据锚的是"喂模型那一处不按 id 去库里现取"，而"删字段"会让另一条判据变红。
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val snapshot = SessionStore.cardSnapshotOf(this, currentSessionId)
        val effective = RoleCard.effectiveSystem(snapshot, sys)"""
new = """        val effective = RoleCardStore.get(this, SessionStore.cardIdOf(this, currentSessionId))?.system ?: sys"""
assert s.count(old) == 1, "桩②：chatHistory 里没找到 effectiveSystem 那两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩②（按 id 去库里现取 system）" "喂模型那一处**不**按 id 去卡片库现取"
reset_src

# ── 3. 桩③：改卡片顺手去改所有会话（不可恢复，用户不知情）────────────────
"$PY" - "$RCS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        return save(ctx, next)
    }

    /**
     * 删一张卡。"""
new = """        if (save(ctx, next)) {
            // 桩③：顺手把所有挂了这张卡的会话也改成新内容
            SessionStore.setCard(ctx, "x", id, system)
        }
        return true
    }

    /**
     * 删一张卡。"""
assert s.count(old) == 1, "桩③：没找到 update 的收尾"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩③（改卡片顺手改所有会话）" "改卡片只写卡片库"
reset_src

# ── 4. 桩④：写盘直接 writeText（写一半断电 = 整个卡片库消失）─────────────
"$PY" - "$RCS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            tmp.writeText(encode(cards))"""
new = """            file(ctx).writeText(encode(cards))"""
assert s.count(old) == 1, "桩④：没找到 tmp.writeText"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩④（写盘不走 tmp+rename）" "先写 .tmp 再 rename"
reset_src

# ── 5. 桩⑤：读盘把 `.tmp` 也当库（上次写了一半的中间态被当成正式内容）───
"$PY" - "$RCS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val f = file(ctx)
        if (!f.exists()) return RoleCard.Loaded(emptyList(), RoleCard.CardFault.NONE)"""
new = """        val f = file(ctx)
        if (!f.exists()) {
            val t = File(ctx.filesDir, TMP)
            if (t.exists()) return parse(t.readText())
            return RoleCard.Loaded(emptyList(), RoleCard.CardFault.NONE)
        }"""
assert s.count(old) == 1, "桩⑤：没找到 load 里判断文件在不在那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑤（读盘把 .tmp 也当库）" "读盘只认正式文件"
reset_src

# ── 6. 桩⑥：重填下拉不挡住框架触发的那一次选中（切会话静默摘卡）──────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (cardPickerFilling) return
                pickCard(pos)
            }"""
new = """            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                pickCard(pos)
            }"""
assert s.count(old) == 1, "桩⑥：没找到 onItemSelected 的挡板"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑥（不挡住框架那次选中）" "吃掉框架触发的那一次选中"
reset_src

# ── 7. 桩⑦：坏卡静默丢、不计数 ────────────────────────────────────────────
#
# ⚠ 这个桩的**判据形状**值得记一笔：`{ dropped++; continue }` 在 `parse` 里出现
#   两次，而 `grep -qF` 只要**任意一处**命中就为真 —— 所以"只删掉其中一支的计数"
#   这种桩**不会**让守卫变红。第一版就是这么写的，桩没红；而守卫那条判据当时
#   也只 anchor 到"文件里出现过 dropped++"，同样抓不住它。两边一起改：
#   守卫那条锚成"**两类各自**在同一行里既跳过又计数"，桩则改成**两支一起**
#   静默丢（与"读盘不再计数"这个真实改法同形）。
#   ⚠ 诚实说：即便如此，这条判据仍然只有"两处一起坏"才红得起来 ——
#   单支坏掉的情况它抓不住（`grep` 的灵敏度到这儿为止，如实记在这里）。
"$PY" - "$RCS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "if (o == null) { dropped++; continue }"
assert s.count(old) == 1, "桩⑦：没找到对象分支"
s = s.replace(old, "if (o == null) continue", 1)
old2 = "if (RoleCard.validate(name, sys) != null) { dropped++; continue }"
assert s.count(old2) == 1, "桩⑦：没找到校验分支"
s = s.replace(old2, "if (RoleCard.validate(name, sys) != null) continue", 1)
open(p, 'w', encoding='utf-8').write(s)
PY_EOF
must_red "桩⑦（坏卡静默丢、两支都不计数）" "⑥ 两类坏卡**各自**跳过并计数"
reset_src

# ── 8. 桩⑧：超限静默截断（用户以为卡片就这么多）──────────────────────────
"$PY" - "$RCS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            return RoleCard.Loaded(sorted.take(RoleCard.MAX_CARDS), RoleCard.CardFault.OVER_LIMIT, dropped)"""
new = """            return RoleCard.Loaded(sorted.take(RoleCard.MAX_CARDS), RoleCard.CardFault.NONE, dropped)"""
assert s.count(old) == 1, "桩⑧：没找到超限那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑧（超限静默截断）" "顶到上限只读最近的、且**如实报**"
reset_src

# ── 9. 桩⑨：保存卡片不说"不会影响已有会话"（用户以为没存上）──────────────
"$PY" - "$ACT" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                "已保存「$name」。已挂卡的会话**不会**跟着变（它们用挂卡时的内容），" +"""
new = """                "已保存「$name」。" +"""
assert s.count(old) == 1, "桩⑨：没找到保存卡片那句文案"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑨（保存卡片不说明影响面）" "明说**已挂卡的会话不会跟着变"
reset_src

# ── 10. 桩⑩：导出只写手打的 system（卡片内容在导出里消失 → 用户误判"没注入"）
#
# 这是"审计出口说谎"那一类：prompt 里确实有卡片内容，但用户**唯一**能看到
# "到底喂了什么"的导出件里没有它 —— 他只会得出"卡片没生效"这个**错误结论**，
# 然后去重挂卡、反复试，症状一直不变。桩就写回"只写手打的 system"这个旧写法。
"$PY" - "$SS" <<'PY_EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            // 生效的 system = 快照优先（与 chatHistory 同一判据，不另写一份）。
            system = RoleCard.effectiveSystem(
                o.optString("cardSnapshot", ""), o.optString("system", "")),"""
new = """            system = o.optString("system", ""),"""
assert s.count(old) == 1, "桩⑩：没找到 exportOne 里过 effectiveSystem 那一处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY_EOF
must_red "桩⑩（导出只写手打的 system）" "导出写的是**生效的** system"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 角色卡（V1）守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 角色卡（V1）守卫自测：PASS $ok / FAIL $bad ==="; exit 1
