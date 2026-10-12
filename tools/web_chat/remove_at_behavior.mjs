// 从**落盘源码**里逐字抽出 `removeAt`，在 Node 里原样执行 ——
// 证明"生成中删除"的真实行为，而不是"读代码觉得不对"。
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么必须实跑：这一族的失效**没有症状**
// ═══════════════════════════════════════════════════════════════════════════
// `bubbles` 在本轮**发送时**就登记两条，`history` 只在**成功收尾时**才 push。
// 中间那段窗口里 `hi < history.length` 恒不成立 —— 于是气泡真删了、history 没动，
// 收尾时又把那两条 push 进去。用户"看到了删除生效"，模型"还记得"。
//
// 断言只能靠**跑**：静态看"两处都有 splice"永远是绿的（旧代码正是如此）。
//
// 用法：node tools/web_chat/remove_at_behavior.mjs <WebChatPage.kt 路径>
import { readFileSync } from "node:fs";

const src = readFileSync(process.argv[2], "utf8");

/** 从 `function <name>(` 起，按花括号配平取出函数源码。 */
function extractFn(name) {
  const head = `function ${name}(`;
  const i = src.indexOf(head);
  if (i < 0) throw new Error("未找到 " + head);
  const open = src.indexOf("{", i);
  let depth = 0;
  for (let j = open; j < src.length; j++) {
    if (src[j] === "{") depth++;
    else if (src[j] === "}") {
      depth--;
      if (depth === 0) return src.slice(i, j + 1);
    }
  }
  throw new Error(name + " 花括号不配平");
}

const fnSrc = extractFn("removeAt");

const rmLog = [];      // 被移出 DOM 的节点
const shown = [];      // show() 收到的回执

// 页面里的全局替身：`abort` / `history` / `bubbles` 由每个场景注入，
// `show` / `$` / 节点的 `parentNode.removeChild` 只需语义等价。
const show = (el, text) => { shown.push(String(text)); };
const $ = () => ({});
const node = (tag) => ({ tag, parentNode: { removeChild: (el) => rmLog.push(el.tag) } });

/** 造一个沙箱并返回其中的 `removeAt`（它闭包捕获注入的三个变量）。 */
function build(abort, history, bubbles) {
  const factory = new Function(
    "abort", "history", "bubbles", "show", "$",
    fnSrc + "\nreturn removeAt;"
  );
  return { removeAt: factory(abort, history, bubbles, show, $), history, bubbles };
}

let fail = 0;
let ok = 0;
const ck = (name, cond, detail) => {
  if (cond) { console.log("PASS  " + name); ok++; }
  else { console.log("FAIL  " + name + (detail ? "（" + detail + "）" : "")); fail++; }
};

// ── 场景 1：生成中长按 AI 那条 -> 必须被闸门挡住，且**什么都不许改** ────────
// 这是 D-2 的核心：此前它会"删掉气泡、history 不动、收尾又 push 回去"。
{
  const live = { abort() {} };                  // 有一轮在飞
  const w = build(live, [], [node("me"), node("ai")]);
  rmLog.length = 0; shown.length = 0;
  w.removeAt(1);
  ck("生成中删 AI 那条：气泡没有被移出 DOM（闸门挡住了）", rmLog.length === 0, "removeChild=" + rmLog.length);
  ck("生成中删 AI 那条：history 仍为空（没有偷删）", w.history.length === 0);
  ck("生成中删 AI 那条：气泡列表长度不变", w.bubbles.length === 2);
  ck("生成中删 AI 那条：给的是可读回执（不是静默）",
     shown.length === 1 && shown[0].includes("生成中"), JSON.stringify(shown));
}

// ── 场景 2：生成结束后删一条 -> 两边**都**要动（这才是"删了算数"）──────────
{
  const w = build(
    null,                                        // 没有在飞的一轮
    [{ role: "user", content: "Q1" }, { role: "assistant", content: "A1" }],
    [node("a"), node("b"), node("c")]
  );
  rmLog.length = 0; shown.length = 0;
  w.removeAt(1);                                 // 删第 2 条
  ck("定型后删一条：气泡被移出 DOM", rmLog.length === 1 && rmLog[0] === "b", JSON.stringify(rmLog));
  ck("定型后删一条：气泡列表少一条", w.bubbles.length === 2);
  ck("定型后删一条：history 真的少了那一条（不是静默跳过）",
     w.history.length === 1 && w.history[0].content === "Q1", JSON.stringify(w.history));
  ck("定型后删一条：回执说的是已删除",
     shown.length === 1 && shown[0].includes("已删除"), JSON.stringify(shown));
}

// ── 场景 3：两个视图已错位（气泡比 history 多）-> 不许**静默**跳过 ──────────
// 旧写法在这一支是 `if (hi >= 0 && hi < history.length)` —— 越界就什么都不做，
// 却照样回一句"已删除这一条"。那正是"删了不算"的形态。
{
  const w = build(null, [], [node("a"), node("b")]);
  rmLog.length = 0; shown.length = 0;
  w.removeAt(1);
  ck("错位时：气泡照旧移除（页面上不该有删不掉的东西）", rmLog.length === 1);
  ck("错位时：明说那条不在历史里（不是静默跳过 + 报已删除）",
     shown.length === 1 && shown[0].includes("不在历史里") && !shown[0].startsWith("已删除"),
     JSON.stringify(shown));
}

// ── 场景 4（反向对照）：把旧写法换回来，场景 1/3 必须**真的坏掉** ────────────
// 这条防"上面的断言恒真"：它们必须能对**旧实现**报红。
{
  const oldFn = `function removeAt(idx) {
  const sysCount = history.filter((m) => m.role === 'system').length;
  const hi = idx + sysCount;
  if (hi >= 0 && hi < history.length) history.splice(hi, 1);
  const el = bubbles[idx];
  if (el && el.parentNode) el.parentNode.removeChild(el);
  bubbles.splice(idx, 1);
  show($('out'), '已删除这一条（不再进入后续请求的历史）。', '');
}`;
  const factory = new Function("abort", "history", "bubbles", "show", "$",
                               oldFn + "\nreturn removeAt;");
  // 旧实现下：生成中删除 -> 气泡被移除、history 没动 -> 用户以为删掉了。
  {
    const live = { abort() {} };
    const h = []; const b = [node("me"), node("ai")];
    rmLog.length = 0; shown.length = 0;
    factory(live, h, b, show, $)(1);
    ck("反向对照（旧写法）：生成中删除**确实**移除了气泡（这就是那个 bug）",
       rmLog.length === 1, "removeChild=" + rmLog.length);
    ck("反向对照（旧写法）：history 没动，而回执却说已删除（静默失效）",
       h.length === 0 && shown.length === 1 && shown[0].includes("已删除"));
  }
}

// 收尾报**条数**：`tools/count_suite_totals.py` 拿这份读数去对账
// `suite-counts.json`，它只认带数字的汇总行 —— 只印"全部符合预期"
// 会让清单记成 None，对账时报一条**假漂移**。
console.log(`\nPASS ${ok} / FAIL ${fail}`);
process.exit(fail ? 1 : 0);
