// 从**落盘源码**里抽出 `renderLog` 的"正文写入"分流判据，翻译成可执行 JS 后逐字跑 ——
// 证明过滤态下页面有没有在说自相矛盾的话。
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么必须实跑
// ═══════════════════════════════════════════════════════════════════════════
// 旧写法 `if (filtered) { if (justSwitchedTab) logTv.text = tail }` 是**二选一**：
// 过滤态下要么实时（清掉用户的文本选择）、要么冻结（正文停在旧快照）。
// 而说明行是**每次重算**的 —— 于是：
//
//     T1 点「HTP」   -> 正文 2 行、说明"命中 2 行"       （一致）
//     T2 稳态来新行  -> 正文**仍是 2 行**、说明"命中 3 行"  ← 自相矛盾
//
// 最坏的一支：命中数 0 -> 正 时正文不重写，用户看到"命中 3 行"、下面一行都没有。
//
// 静态守卫只能查"写过 logTv.text"，查不出"写与不写**在什么条件下**分叉"。
//
// 抽取范围：从 `val tail = …` 到 `if (curTab != 3) return` 之前。
// 翻译只做三件事（每一件都写在下面的 TRANSLATE 里，可审计）：
//   ① Kotlin 的 `val x =` -> `let x =`（这里没有类型标注，逐字安全）；
//   ② `logTv.text = v` -> `logTv.post(v)`（替身用 post 记每次写入）；
//   ③ `logLastBody` 提升为闭包外的状态（它在 Kotlin 里是字段，跨调用保留）；
//   ④ `takeLast` / `joinToString` 这两个 Kotlin 扩展函数挂在原型上，方法式调用逐字可用。
//
// 用法：node tools/log_filter/render_log_behavior.mjs <EngineActivity.kt 路径>
import { readFileSync } from "node:fs";

const src = readFileSync(process.argv[2], "utf8");

/** 抽到"正文写入"那一段的源码（原样，含注释）。 */
function extractBodyBlock() {
  const i = src.indexOf("val tail = lines.takeLast(shown).joinToString");
  if (i < 0) throw new Error("未找到正文写入段");
  const j = src.indexOf("if (curTab != 3) return", i);
  if (j < 0) throw new Error("未找到正文写入段的结尾（curTab 判据）");
  return src.slice(i, j);
}

const kotlinBlock = extractBodyBlock();

/** Kotlin -> JS。只认这三种改写，多一样都不许。 */
function translate(kotlin) {
  let js = kotlin;
  js = js.replace(/\bval\s+(\w+)\s*=/g, "let $1 =");          // ① val -> let
  js = js.replace(/logTv\.text\s*=\s*/g, "logTv.post(");      // ② 赋值 -> 记一笔
  // ② 的右值要以 `)` 收尾，所以给每一条赋值语句的行尾补一个括号。
  js = js.split("\n").map((l) => {
    if (!/\blogTv\.post\(/.test(l)) return l;
    return l.replace(/;\s*$/, "").replace(/\s*$/, "") + ")";
  }).join("\n");
  return js;
}
const jsBlock = translate(kotlinBlock);

/**
 * Kotlin 标准库替身。只补被抽出的那一段**真的用到**的两个：
 *   · `List.takeLast(n)`  -> `takeLast(arr, n)`
 *   · `Iterable.joinToString(sep)` -> `joinToString(arr, sep)`
 * 各写一份、语义与 Kotlin 逐条对齐（n <= 0 给空、n >= size 给全量；
 * joinToString 不追加分隔符到末尾），并**自证**（见下面的 shim 自检）：
 * 这两个替身若写错，"正文行数"这类断言就会在错的基础上通过。
 */
function takeLast(arr, n) {
  if (n <= 0) return [];
  return arr.slice(Math.max(0, arr.length - n));
}
function joinToString(arr, sep) { return arr.join(sep === undefined ? ", " : sep); }

/**
 * 把 Kotlin 的扩展函数装到 JS 原型上。
 *
 * Kotlin 的扩展函数在调用处**长得像方法**（`lines.takeLast(n)` /
 * `arr.joinToString("\n")`）。要给这两种接收者都补上，最省事、也最贴近原语法的
 * 做法是直接挂在原型上 —— 这样抽取到的源码可以**逐字**执行，不用先改写成
 * 函数式调用（改写越多，"这还是不是落盘源码"就越可疑）。
 *
 * 装在**模块顶部**（而不是只塞进 new Function 的作用域）：自检与实跑用的是
 * 同一份实现，否则"替身对不对"这件事本身就没被验过。
 * 重复安装是幂等的（可配置 + 可写）。
 */
function installKotlinShims() {
  Object.defineProperty(Array.prototype, "takeLast", {
    value(n) { return n <= 0 ? [] : this.slice(Math.max(0, this.length - n)); },
    configurable: true, writable: true,
  });
  Object.defineProperty(Array.prototype, "joinToString", {
    value(sep) { return this.join(sep === undefined ? ", " : sep); },
    configurable: true, writable: true,
  });
}
installKotlinShims();

/** 造一个"写入被记下来"的 logTv 替身。 */
function makeLogTv() {
  let text = "";
  const writes = [];
  return {
    get text() { return text; },
    post(v) { writes.push(v); text = v; },
    writes,
  };
}

/**
 * 造一个 run(lines, shown, logTv, filtered, justSwitchedTab)。
 * `logLastBody` 是 Kotlin 里的字段 —— 这里做成外层变量，跨调用保留。
 * 初值必须与 Kotlin 的 `null` 一致（它保证冷启动那一帧必写）。
 */
function makeRenderer(block) {
  // shim 直接**定义在被执行的代码体里**：抽出的源码用的是 Kotlin 扩展函数的方法式
  // 语法，翻译后变成函数式调用，链式（`takeLast(a,n).joinToString(s)`）必须能接上 ——
  // 把 shim 放进同一作用域是最不容易出错的做法，也便于在自检里直接比对语义。
  const shims = "";   // 扩展函数已在模块顶部装好（installKotlinShims），这里不需要重复
  const code = shims + `\nlet logLastBody = null;\n` +
    `function run(lines, shown, logTv, filtered, justSwitchedTab) {\n` +
    block + `\n}`;
  return new Function("lines", "shown", "logTv", "filtered", "justSwitchedTab",
                      code + "\nreturn run;")();
}

/** 旧写法（给反向对照用）：与 git 历史里那一行逐字等价。 */
const OLD_BLOCK = `  let tail = lines.takeLast(shown).joinToString("\\n");
  if (filtered) { if (justSwitchedTab) logTv.post(tail); } else logTv.post(tail);`;

let fail = 0;
let ok = 0;
const ck = (name, cond, detail) => {
  if (cond) { console.log("PASS  " + name); ok++; }
  else { console.log("FAIL  " + name + (detail ? "（" + detail + "）" : "")); fail++; }
};

// 自证：抽取到的块必须**真的**是当前实现（否则这份测试在测空气）。
ck("抽到的块里含 logLastBody（说明抽的是修好的那一版，不是旧行）",
   /logLastBody/.test(kotlinBlock), JSON.stringify(kotlinBlock.slice(0, 120)));
ck("抽到的块里有 filtered 分流", /if \(filtered\)/.test(kotlinBlock));

// shim 自检（防"替身写错 -> 断言在错的基础上通过"）
ck("shim takeLast 语义与 Kotlin 对齐（挂在 Array 原型上，方法式调用可用）",
   JSON.stringify([1, 2, 3].takeLast(2)) === "[2,3]" &&
   JSON.stringify([1, 2, 3].takeLast(0)) === "[]" &&
   JSON.stringify([1, 2, 3].takeLast(9)) === "[1,2,3]");
ck("shim joinToString 不追加尾分隔符", ["a", "b"].joinToString("\n") === "a\nb");
ck("链式可用（takeLast(...).joinToString(...)）",
   ["a", "b", "c"].takeLast(2).joinToString("-") === "b-c");

const run = makeRenderer(jsBlock);
const runOld = makeRenderer(OLD_BLOCK);

// ── 场景 1：切到日志页 -> 点「HTP」（命中 2）-> 稳态来一行（命中 3）──────────
{
  const logTv = makeLogTv();
  run(["a1"], 1, logTv, true, true);                        // T1：换档
  const afterT1 = logTv.text;
  run(["a1", "a2", "a3"], 3, logTv, true, false);           // T2：稳态，命中变 3
  ck("T1 换档后正文写进去了（不是空）", afterT1.includes("a1"), JSON.stringify(afterT1));
  ck("T2 命中集合变了 -> 正文跟着变（不再停在旧快照）",
     logTv.text.includes("a3"), JSON.stringify(logTv.text));
  ck("T2 正文行数与命中数一致（3 行）",
     logTv.text.split("\n").length === 3, JSON.stringify(logTv.text));
}

// ── 场景 2：过滤态下**内容没变**的稳态帧 -> 不写（保住文本选择）──────────────
{
  const logTv = makeLogTv();
  const lines = ["a1", "a2"];
  run(lines, 2, logTv, true, true);                         // 首帧：写
  const n = logTv.writes.length;
  for (let k = 0; k < 5; k++) run(lines.slice(), 2, logTv, true, false);
  ck("内容没变的稳态帧不写正文（选择保住了）",
     logTv.writes.length === n, "写入 " + logTv.writes.length + " 次（期望仍 " + n + "）");
}

// ── 场景 3：命中数 0 -> 正（最坏的一支）-> 正文必须从空变为有 ────────────────
{
  const logTv = makeLogTv();
  run([], 0, logTv, true, true);                            // 命中 0 -> 正文空
  ck("命中 0 时正文为空", logTv.text === "", JSON.stringify(logTv.text));
  run(["h1", "h2", "h3"], 3, logTv, true, false);           // 命中变 3
  ck("命中 0 -> 正：正文从空变为有（不会出现「说了命中却看不到行」）",
     logTv.text.includes("h1"), JSON.stringify(logTv.text));
}

// ── 场景 4：不过滤时照旧每帧刷新（这条没被改坏的余地）──────────────────────
{
  const logTv = makeLogTv();
  run(["x1"], 1, logTv, false, false);
  run(["x1", "x2"], 2, logTv, false, false);
  ck("不过滤时每帧都写（原来的行为不变）",
     logTv.writes.length === 2 && logTv.text === "x1\nx2", JSON.stringify(logTv.writes));
}

// ── 场景 5（反向对照）：换成旧写法，场景 1 与 3 必须**真的坏掉** ─────────────
{
  const logTv = makeLogTv();
  runOld(["a1"], 1, logTv, true, true);
  const frozen = logTv.text;
  runOld(["a1", "a2", "a3"], 3, logTv, true, false);
  ck("反向对照（旧写法）：正文停在旧快照（1 行），而命中已是 3 —— 自相矛盾",
     logTv.text === frozen && logTv.text.split("\n").length === 1, JSON.stringify(logTv.text));

  const logTv2 = makeLogTv();
  runOld([], 0, logTv2, true, true);
  runOld(["h1", "h2", "h3"], 3, logTv2, true, false);
  ck("反向对照（旧写法）：命中 0 -> 正 时正文仍是空（说了命中却看不到行）",
     logTv2.text === "", JSON.stringify(logTv2.text));
}

// 收尾报**条数**：`tools/count_suite_totals.py` 拿这份读数去对账
// `suite-counts.json`，它只认带数字的汇总行 —— 只印"全部符合预期"
// 会让清单记成 None，对账时报一条**假漂移**。
console.log(`\nPASS ${ok} / FAIL ${fail}`);
process.exit(fail ? 1 : 0);
