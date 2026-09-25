#!/bin/sh
# 「发布器的清洗规则」与「发布器的卫生门禁」必须自洽 —— 且 README 不得把
# 唯一的**质量**门禁写成 APK 构建链路。锚的是**结构关系**，不是存在性。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么要有这个文件（J-3 / J-4）
# ═══════════════════════════════════════════════════════════════════════════
# J-3：`scripts/release.py` 里有两套**各自独立维护**的东西 ——
#   规则（`release-rules.json`，按 old→new 精确匹配）与门禁（`HYGIENE` 正则）。
#   规则覆盖不到的地方由门禁兜底，门禁挡住的地方又只能靠人手往规则里补。
#   两者打架时**此前只在发布那一刻**才暴露，而且报出来的是"发布失败"，
#   不是"这里有一条规则该补" —— 排障方向全错。
#   实测（`main` @ 744fe9c）：README.md 正文有三处日常线版本号（396 / 932 / 1349），
#   规则的覆盖只有一条，套用清洗后**门禁照样报 3 处脏**，每次发布都会被挡。
#   而规则自身的失效形态是同一个：规则基线停在 `78aa39a`，之后有人往被清洗
#   文件里新写痕迹，没有任何东西提醒补规则。
#
# J-4：README「构建」一节把 CI 写成 `.github/workflows/build.yml`，而它
#   **全文 0 次引用 `tools/run_*`** —— 真正跑全部离线套件的是 `.cnb.yml`，
#   而 README 里 `grep -c 'cnb.yml'` = 0。照 README 理解的人会以为"CI 只编 APK"，
#   不知道那些守卫在哪跑、改坏了什么会在哪一环被拦。判据锚的是**名字口径**：
#   讲"质量门禁"时点名的必须是那份**真的会跑 `tools/run_*`** 的文件。
#
# 全是静态断言，不依赖任何工具链。运行：sh tools/run_release_hygiene_guard.sh
set -e
cd "$(dirname "$0")/.."

REL=scripts/release.py
RULES=scripts/release-rules.json
README=README.md
GH=.github/workflows/build.yml
CI=.cnb.yml

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ── 用真 python 抽结构关系（grep 表达不了"这个函数体内""这行在锁内"这类判据）──
python3 - "$REL" "$RULES" "$README" "$GH" "$CI" > "$TMP/report.txt" <<'PY'
import json, os, re, sys

rel_path, rules_path, readme_path, gh_path, ci_path = sys.argv[1:6]
rel = open(rel_path, encoding='utf-8').read()
README = open(readme_path, encoding='utf-8').read()
GH = open(gh_path, encoding='utf-8').read()
CI = open(ci_path, encoding='utf-8').read()


def emit(k, v):
    print('%s=%s' % (k, v))


def strip_comments(src):
    """去掉注释但**保持行号** —— 报出来的行号必须对得上真源码，
    否则比不报更坏（模块 I 的 PR-1 与 F-2 都踩过这个坑）。

    ⚠ 不能用 `re.sub(r'#.*$', '', ln)`：`.py` 里 `#` 会出现在**字符串字面量**
    内部 —— 本文件恰好有一处 `s.startswith(('#', '//', '*', '/*'))`，
    按行正则一削就把它削成 `s.startswith((`，函数体当场断成两截，
    判据反而在**正确源码**上报假故障（本守卫第一版实测如此）。
    这里改用 tokenize 拿真正的注释区间，按行号清零。"""
    import io, tokenize
    lines = src.splitlines()
    try:
        for tok in tokenize.generate_tokens(io.StringIO(src).readline):
            if tok.type == tokenize.COMMENT:
                ln = lines[tok.start[0] - 1]
                lines[tok.start[0] - 1] = ln[:tok.start[1]]
    except tokenize.TokenError:
        pass          # 语法不完整时退化成"不去注释"，宁可判严不判松
    return '\n'.join(lines)


def func_body(src, name):
    """取 `def <name>(` 到下一个顶层 def/EOF 之间的正文（含签名行）。"""
    m = re.search(r'^def\s+%s\s*\(' % re.escape(name), src, re.M)
    if not m:
        return ''
    rest = src[m.start():]
    nxt = re.search(r'^def\s+\w+\s*\(', rest[1:], re.M)
    return rest if not nxt else rest[:nxt.start() + 1]


# ── A) 自检必须与门禁**共用**同一个扫描器 ───────────────────────────────────
# 只查"文件里有没有 hygiene"是存在性断言；本条要的是**同一个函数**。
REL_NO_COMMENT = strip_comments(rel)
body_scan = func_body(REL_NO_COMMENT, 'hygiene_hits')
body_self = func_body(REL_NO_COMMENT, 'selfcheck_hygiene')
body_main = func_body(REL_NO_COMMENT, 'main')
emit('HYG_IN_SHARED_FUNC', '1' if body_scan else '0')
emit('HYG_EXT_IN_SHARED_FUNC', '1' if 'HYGIENE_EXT' in body_scan else '0')
emit('HYG_SKIP_IN_SHARED_FUNC', '1' if 'HYGIENE_SKIP' in body_scan else '0')

# 自检里必须**调**那个共享扫描器（钉调用，不钉定义）
emit('SELFCHECK_CALLS_SHARED', '1' if 'hygiene_hits(' in body_self else '0')
# 自检必须把规则套到产物上（old -> new），而不是只看名字
emit('SELFCHECK_APPLIES_RULES',
     '1' if re.search(r"r\['old'\]", body_self) and re.search(r"r\['new'\]", body_self) else '0')

# 主流程的卫生门禁也必须走同一个扫描器（否则两处各写一份近似 = 判据漂移的起点）
emit('MAIN_USES_SHARED',
     '1' if re.search(r'\bhygiene_hits\s*\(', body_main) else '0')

# ── B) 自检必须**真的参与阻断判定**，不能只是打印 ───────────────────────────
# 这条是"存在但无效"那一类：`selfcheck_hygiene()` 被调了、也打印了，
# 但 blocked 里没有它 —— 那么它一点都不拦。
if not body_main:
    emit('SELFCHECK_IN_BLOCKED', '0')
else:
    # 括号平衡地取整段赋值右侧 —— `.*?\)` 会在第一个 `)` 处截断，
    # 而这里右侧**本身**就含括号（`(bool(dirty) and ...)`），截断后
    # 只剩前半段，判据会在"真的写对了"的源码上报假故障（第一版实测如此）。
    m = re.search(r'blocked\s*=\s*\(', body_main)
    blk = ''
    if m:
        i, depth = m.end() - 1, 0
        while i < len(body_main):
            if body_main[i] == '(':
                depth += 1
            elif body_main[i] == ')':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        blk = body_main[m.end():i]
    emit('SELFCHECK_IN_BLOCKED',
         '1' if ('blocked_selfcheck' in blk or 'selfcheck' in blk) else '0')
# 调用必须**在** blocked 赋值之前（顺序关系，不是存在性）
pos_call = body_main.find('selfcheck_hygiene(')
pos_blocked = body_main.find('blocked = (')
emit('SELFCHECK_BEFORE_BLOCKED',
     '1' if 0 <= pos_call < pos_blocked else '0')
# 必须给得出"红线"（把"还可以更好"与"这次发不出去"分开），留一条可调的余地
emit('SELFCHECK_HAS_STRICT', '1' if 'strict' in body_self else '0')
# 必须**真的**用 strict 决定是否阻断，而不是收下参数不用
# ⚠ 不能写成 `'hygiene_selfcheck_strict' in body_main` —— 那条**恒真于"收下却
# 不用"**（桩⑥ 第一版实测全绿）：只要该标识符在 main 的**任何**位置出现过
# （含注释、含 `print(...)` 这种无效用法），它就判 PASS。
#
# 红线的唯一用途是决定 `blocked_selfcheck` 这个布尔量。所以判据分两段：
#   (a) 给 `blocked_selfcheck` 赋值的那几行里，**必须**有一行同时带红线参数
#       （即"红线真的参与了这个布尔量的计算"）；
#   (b) 这个布尔量必须出现在 `blocked = (` 的表达式里（"这个布尔量真的参与放行"）。
# 两段都过才叫"红线真的影响阻断"。桩⑥ 取的是 (a) 那一半被绕掉的写法。
_bl_assign = [ln for ln in body_main.splitlines()
              if 'blocked_selfcheck' in ln and '=' in ln]
_bi = body_main.find('blocked = (')
_bl_expr = body_main[_bi:_bi + 600] if _bi >= 0 else ''
emit('SELFCHECK_REDLINE_LINE_FOUND', '1' if _bl_assign and _bl_expr.strip() else '0')
emit('SELFCHECK_STRICT_USED_IN_BLOCKED',
     '1' if any('hygiene_selfcheck_strict' in ln for ln in _bl_assign)
     and 'blocked_selfcheck' in _bl_expr else '0')

# ── C) 规则与门禁必须自洽：README 的日常线版本号必须有规则覆盖 ─────────────
rules = json.load(open(rules_path, encoding='utf-8'))['rules']
HYGIENE = [
    ('日常线版本号', re.compile(r'\bv0\.\d+(\.\d+)*\b')),
    ('短标签残留', re.compile(r'\[(htp|htp-mem|perf)\]')),
    ('会话号', re.compile(r'\bsession-\d{4,}\b')),
    ('墙钟时间戳', re.compile(r'\b\d{2}:\d{2}:\d{2}(\.\d+)?\b')),
    ('开发留档引用', re.compile(r'HTP-DEBUG-STATUS|开发留档|留档|本次复核|曾试图|结案|设备白名单')),
]
by_path = {}
for r in rules:
    by_path.setdefault(r['path'], []).append(r)

# 判据：README 里的每一处"日常线版本号"，都要么被某条规则的 old 片段覆盖，
#       要么本来就在门禁不扫的位置。这里用"清洗后门禁净不净"来判定 —— 与
#       发布器自检同一语义，同一个正则表，不另写一份近似。
def hygiene_hits(path):
    if not os.path.exists(path):
        return []
    txt = open(path, encoding='utf-8').read()
    rs = by_path.get(path, [])
    for r in rs:
        if r['old'] in txt:
            txt = txt.replace(r['old'], r['new'])
    ext = os.path.splitext(path)[1]
    hits = []
    for i, ln in enumerate(txt.splitlines(), 1):
        s = ln.strip()
        if ext == '.md' or s.startswith(('#', '//', '*', '/*')) or 'uiLog' in s:
            for name, pat in HYGIENE:
                if pat.search(ln):
                    hits.append((i, name))
    return hits


readme_hits = hygiene_hits(readme_path)
emit('README_HYG_AFTER_RULES', len(readme_hits))
emit('README_HYG_DETAIL',
     ';'.join('%d:%s' % (i, n) for i, n in readme_hits[:6]))

# 防恒真：README 里确实**有**会被门禁命中的内容（否则上面那条在
# "README 根本没这类痕迹"时恒绿，挡不住"规则被删光"）。
raw_readme = open(readme_path, encoding='utf-8').read()
raw_hits = sum(1 for ln in raw_readme.splitlines()
               for _n, pat in HYGIENE if pat.search(ln))
emit('README_RAW_HITS', raw_hits)

# 规则集必须**引用**README（否则上面那条空洞）
emit('RULES_TOUCH_README', '1' if by_path.get('README.md') else '0')

# 规则基线的"落后"必须**可查**：generated_from.dev 必须存在且可解析，
# 否则 release.py 只能打印"未登记"，漂移永远没人看。
gf = json.load(open(rules_path, encoding='utf-8')).get('generated_from') or {}
emit('RULES_BASELINE', gf.get('dev') or '')

# ── D) J-4：讲"质量门禁"时点名的必须是真会跑 tools/run_* 的那份 ────────────
gh_runs = len(re.findall(r'tools/run_', GH))
ci_runs = len(re.findall(r'tools/run_', CI))
emit('GH_RUNS', gh_runs)
emit('CI_RUNS', ci_runs)
# README 必须提到 .cnb.yml
emit('README_MENTIONS_CNB', '1' if 'cnb.yml' in README else '0')
# 且必须**先**说 CNB 那条（口径：CNB 是质量门禁、GH 是 APK 分发）
i_cnb = README.find('.cnb.yml')
i_gh = README.find('.github/workflows/build.yml')
emit('README_CNB_FIRST', '1' if 0 <= i_cnb < i_gh else '0')
# README 必须点明 GH 那条**不跑** tools/run_*
seg = README[i_gh:i_gh + 400] if i_gh >= 0 else ''
emit('README_GH_NOT_TOOLS',
     '1' if ('不跑' in seg and 'tools/run_' in seg) else '0')
# README 必须点明两条流水线的**职责不同**（否则读者仍会以为是一条）
seg2 = README[max(0, i_cnb - 900):i_cnb] if i_cnb >= 0 else ''
emit('README_TWO_PIPELINES',
     '1' if ('两条' in seg2 or '两条' in README[i_cnb:i_cnb + 300]) else '0')
# paths-ignore 的口径必须写清是 GH workflow 自己的行为，与 .cnb.yml 无关
emit('README_PATHSIGNORE_SCOPED',
     '1' if 'paths-ignore' in README and '与 `.cnb.yml` 无关' in README else '0')

# ── E) 参数必须真的被 argparse 注册（写了 help 却没注册 = 死参数）──────────
# ⚠ 只在文件里搜字符串是不够的：help 文案、注释里都会出现同一个名字，
# 于是"注册被删掉"这条桩会**恒绿**（桩⑪ 第一版实测如此 —— 它误命中了
# `--hygiene-selfcheck-strict` 那一行）。这里要求它出现在**注册调用**里。
def registered(flag):
    return '1' if re.search(
        r"add_argument\s*\(\s*['\"]%s['\"]" % re.escape(flag), rel) else '0'


emit('ARG_STRICT_REGISTERED', registered('--hygiene-selfcheck-strict'))
emit('ARG_VERBOSE_REGISTERED', registered('--hygiene-selfcheck-verbose'))
emit('ARG_SKIP_REGISTERED', registered('--skip-hygiene-selfcheck'))
emit('ARG_SKIP_HONOURED',
     '1' if 'args.skip_hygiene_selfcheck' in body_main else '0')
PY

v() { awk -F= -v k="$1" '$1==k {sub(/^[^=]*=/,""); print; exit}' "$TMP/report.txt"; }

# ── A) 自检与门禁共用同一个扫描器 ───────────────────────────────────────────
c "卫生扫描器抽成了一个共享函数（hygiene_hits）" \
  "[ \"\$(v HYG_IN_SHARED_FUNC)\" = 1 ]"
c "共享扫描器里真的用了 HYGIENE_EXT（扫哪些文件与扫哪些行同一处定义）" \
  "[ \"\$(v HYG_EXT_IN_SHARED_FUNC)\" = 1 ]"
c "共享扫描器里真的用了 HYGIENE_SKIP（vendor/上游头排除面没丢）" \
  "[ \"\$(v HYG_SKIP_IN_SHARED_FUNC)\" = 1 ]"
c "发布器自检**调用**了共享扫描器（钉调用，不钉定义）" \
  "[ \"\$(v SELFCHECK_CALLS_SHARED)\" = 1 ]"
c "发布器自检真的把规则 old→new 套到产物上" \
  "[ \"\$(v SELFCHECK_APPLIES_RULES)\" = 1 ]"
c "主流程的卫生门禁也走同一个扫描器（两处各写一份 = 判据漂移的起点）" \
  "[ \"\$(v MAIN_USES_SHARED)\" = 1 ]"

# ── B) 自检真的参与阻断判定 ─────────────────────────────────────────────────
c "发布器自检的结论真的进了 blocked（不只是打印）" \
  "[ \"\$(v SELFCHECK_IN_BLOCKED)\" = 1 ]"
c "自检调用**排在**blocked 赋值之前（顺序关系，不是存在性）" \
  "[ \"\$(v SELFCHECK_BEFORE_BLOCKED)\" = 1 ]"
c "自检留了一条可调红线（把\"还可以更好\"与\"这次发不出去\"分开）" \
  "[ \"\$(v SELFCHECK_HAS_STRICT)\" = 1 ]"
c "真的抽到了 blocked_selfcheck 的赋值行与放行表达式（防下一条恒真）" \
  "[ \"\$(v SELFCHECK_REDLINE_LINE_FOUND)\" = 1 ]"
c "那条红线**真的**参与 blocked_selfcheck 计算、且该布尔量真的参与放行" \
  "[ \"\$(v SELFCHECK_STRICT_USED_IN_BLOCKED)\" = 1 ]"

# ── C) 规则与门禁自洽 ───────────────────────────────────────────────────────
c "README 按规则清洗后过得了卫生门禁（残留：$(v README_HYG_DETAIL)）" \
  "[ \"\$(v README_HYG_AFTER_RULES)\" = 0 ]"
# 防上一条恒真：README 里确实有会被门禁命中的内容，规则不该被删光
c "README 确实含会被门禁命中的内容（防上一条恒真，原始命中 $(v README_RAW_HITS) 处）" \
  "[ \"\$(v README_RAW_HITS)\" -ge 3 ]"
c "规则集里确实有 README 的条目（否则上一条空洞）" \
  "[ \"\$(v RULES_TOUCH_README)\" = 1 ]"
c "规则基线的 dev 提交已登记（漂移提示才不会永远是\"未登记\"）" \
  "[ -n \"\$(v RULES_BASELINE)\" ]"

# ── D) J-4 README 口径 ──────────────────────────────────────────────────────
c "APK 构建链路（.github/workflows/build.yml）确实不跑 tools/run_*（实测 $(v GH_RUNS) 处）" \
  "[ \"\$(v GH_RUNS)\" = 0 ]"
c "CNB 流水线确实在跑 tools/run_*（实测 $(v CI_RUNS) 处）" \
  "[ \"\$(v CI_RUNS)\" -ge 20 ]"
c "README 提到了 .cnb.yml（真质量门禁的名字）" \
  "[ \"\$(v README_MENTIONS_CNB)\" = 1 ]"
c "README 先讲 .cnb.yml（质量门禁）再讲 build.yml（APK 分发）" \
  "[ \"\$(v README_CNB_FIRST)\" = 1 ]"
c "README 点明 build.yml 那条**不跑** tools/run_*" \
  "[ \"\$(v README_GH_NOT_TOOLS)\" = 1 ]"
c "README 点明这是**两条**职责不同的流水线" \
  "[ \"\$(v README_TWO_PIPELINES)\" = 1 ]"
c "README 把 paths-ignore 的口径限定为 GH workflow 自己的行为" \
  "[ \"\$(v README_PATHSIGNORE_SCOPED)\" = 1 ]"

# ── E) 参数注册 ─────────────────────────────────────────────────────────────
c "--hygiene-selfcheck-strict 真的在 argparse 里注册了" \
  "[ \"\$(v ARG_STRICT_REGISTERED)\" = 1 ]"
c "--hygiene-selfcheck-verbose 真的在 argparse 里注册了" \
  "[ \"\$(v ARG_VERBOSE_REGISTERED)\" = 1 ]"
c "--skip-hygiene-selfcheck 真的在 argparse 里注册了" \
  "[ \"\$(v ARG_SKIP_REGISTERED)\" = 1 ]"
c "--skip-hygiene-selfcheck 真的被读到（不是死参数）" \
  "[ \"\$(v ARG_SKIP_HONOURED)\" = 1 ]"

echo ""
if [ "$bad" = "0" ]; then echo "=== 发布器自洽守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 发布器自洽守卫：PASS $ok / FAIL $bad ==="; exit 1
