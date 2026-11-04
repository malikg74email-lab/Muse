/**
 * 消息列表性能与动效的断言。
 *
 * 纯逻辑（memo 比较 / 快照合并 / 窗口化 / perf 开关）直接跑；CSS 侧断言
 * 「动效只用 motion 令牌」「减少动态时全部停掉」；接线（窗口化、占位、perf 日志）
 * 按源码结构断言——没有浏览器，能断言的都断了。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".perf-check.mjs");

const ENTRY = `
export { areBubblePropsEqual, areMessagesEqual, mergeMessages } from "./src/host/messageEqual.ts";
export { DEFAULT_ROW_HEIGHT, measureAverage, planWindow, shouldUpdateAverage, WINDOW_OVERSCAN, WINDOW_THRESHOLD } from "./src/host/messageWindow.ts";
export { formatPerfLine, perfFlag } from "./src/host/perf.ts";
`;

let failures = 0;
const assert = (condition, message) => {
  if (!condition) {
    failures += 1;
    console.error(`✗ ${message}`);
    return false;
  }
  console.log(`✓ ${message}`);
  return true;
};

async function load() {
  await build({
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "perf-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    logLevel: "warning",
    outfile: TEMP,
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const BUBBLE = readFileSync(join(ROOT, "src", "host", "MessageBubble.tsx"), "utf8");

const message = (id, overrides = {}) => ({
  id,
  role: "assistant",
  content: `Content ${id}`,
  createdAt: 1_700_000_000_000,
  ...overrides,
});

try {
  // ── 1. memo 比较：只认影响渲染的字段 ─────────────────────────
  const base = message("m1");
  assert(mod.areMessagesEqual(base, base) === true, "the same reference is judged equal directly");
  assert(mod.areMessagesEqual(base, message("m1")) === true, "a new object with the same content is judged equal (memo should skip)");
  assert(mod.areMessagesEqual(base, message("m1", { content: "changed" })) === false, "a change to the body text requires a re-render");
  assert(mod.areMessagesEqual(base, message("m1", { status: "failed" })) === false, "a status change requires a re-render");
  assert(mod.areMessagesEqual(base, message("m1", { reasoning: "think for a moment" })) === false, "a change to the reasoning process requires a re-render");
  assert(mod.areMessagesEqual(base, message("m1", { quotedContent: "quote" })) === false, "a change to the quoted block requires a re-render");
  assert(mod.areMessagesEqual(base, message("m2")) === false, "different ids are judged not equal");
  assert(
    mod.areMessagesEqual(
      message("m3", { imageUrls: ["/api/media/a"] }),
      message("m3", { imageUrls: ["/api/media/a"] }),
    ) === true,
    "the media URL array is compared by content (not by reference)",
  );
  assert(
    mod.areMessagesEqual(
      message("m3", { imageUrls: ["/api/media/a"] }),
      message("m3", { imageUrls: ["/api/media/b"] }),
    ) === false,
    "a change to the media URL requires a re-render",
  );
  const call = (result) => ({
    toolCalls: [{ callId: "c1", name: "read_file", argumentsPreview: "{}", resultPreview: result, success: true, durationMs: 12 }],
  });
  assert(mod.areMessagesEqual(message("m4", call("ok")), message("m4", call("ok"))) === true, "identical tool call details are judged equal");
  assert(mod.areMessagesEqual(message("m4", call("ok")), message("m4", call("replaced"))) === false, "a change to the tool result requires a re-render");

  const caps = { known: true };
  const bubbleProps = (overrides = {}) => ({
    message: base,
    streaming: false,
    showActions: false,
    capabilities: caps,
    favorited: false,
    actionsOpen: false,
    selectionMode: false,
    ...overrides,
  });
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps()) === true, "when bubble props have not changed, rendering is skipped");
  assert(
    mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ onCopy: () => undefined, onAction: () => undefined })) === true,
    "callbacks changing to a new reference do not affect the judgment (callbacks are all stable and are not included in the comparison)",
  );
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ streaming: true })) === false, "a streaming state change requires a re-render");
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ actionsOpen: true })) === false, "menu opening/closing requires a re-render");
  assert(mod.areBubblePropsEqual(bubbleProps(), bubbleProps({ favorited: true })) === false, "a favorite state change requires a re-render");
  assert(/export const MessageBubble = memo\(MessageBubbleView, areBubblePropsEqual\)/.test(BUBBLE), "the message component really is wrapped in memo");

  // ── 2. 快照合并：没变的消息换回旧引用 ─────────────────────────
  const first = message("a");
  const second = message("b");
  const previous = [first, second];

  const sameNext = [message("a"), message("b")];
  const mergedSame = mod.mergeMessages(previous, sameNext);
  assert(mergedSame === previous, "when the whole thing has not changed, the old array is reused directly");

  const streamingNext = [message("a"), message("b", { content: "streaming…" })];
  const mergedStreaming = mod.mergeMessages(previous, streamingNext);
  assert(mergedStreaming[0] === first, "an unchanged message reuses the old object (only then does memo work)");
  assert(mergedStreaming[1] === streamingNext[1], "the changed one uses the new object");
  assert(mergedStreaming.length === 2 && mergedStreaming !== previous, "the merge result is a new array, not an in-place modification");

  const appended = mod.mergeMessages(previous, [message("a"), message("b"), message("c")]);
  assert(appended.length === 3 && appended[0] === first && appended[1] === second, "when one is appended, the earlier references do not change");
  assert(appended[2]?.id === "c", "the newly appended message enters the list");

  const replacedFirst = mod.mergeMessages(previous, [message("a", { content: "changed" }), message("b")]);
  assert(
    replacedFirst[0] !== first && replacedFirst !== previous,
    "when it changes right at the start, only the common prefix is reused (here the prefix is empty) and the whole thing is replaced — no moving objects across positions",
  );

  assert(mod.mergeMessages([], previous) === previous, "merging an empty list returns the new snapshot directly");

  // ── 3. 窗口化：阈值与可视区计算 ──────────────────────────────
  assert(mod.WINDOW_THRESHOLD === 150 && mod.WINDOW_OVERSCAN === 20, "threshold 150, 20 items above and below");
  const small = mod.planWindow({ itemCount: 150, scrollTop: 0, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(small.active === false && small.start === 0 && small.end === 150, "when there are no more than 150 items, everything is rendered");

  const big = mod.planWindow({ itemCount: 400, scrollTop: 1000, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(big.active === true, "exceeding the threshold enables windowing");
  assert(big.start === 0 && big.end === 36, `Scroll 1000px / row height 100 / viewport 600 → render [0,36): actual [${big.start},${big.end})`);
  assert(big.topPad === 0 && big.bottomPad === (400 - 36) * 100, "placeholders at both ends fill in the total height by the average row height");

  const middle = mod.planWindow({ itemCount: 400, scrollTop: 5000, viewportHeight: 600, averageHeight: 100, pinnedToEnd: false });
  assert(middle.start === 30 && middle.end === 76, `Middle position window [30,76): actual [${middle.start},${middle.end})`);
  assert(middle.topPad === 30 * 100, "top placeholder = start × row height");

  const tail = mod.planWindow({ itemCount: 400, scrollTop: 0, viewportHeight: 600, averageHeight: 100, pinnedToEnd: true });
  assert(tail.end === 400 && tail.start === 374, `When pinned to the bottom, anchored to the end: actual [${tail.start},${tail.end})`);
  assert(tail.bottomPad === 0, "when pinned to the bottom, no placeholder is left at the bottom");

  const unknown = mod.planWindow({ itemCount: 400, scrollTop: 0, viewportHeight: 600, averageHeight: 0, pinnedToEnd: true });
  assert(unknown.end === 400 && unknown.start === 400 - Math.ceil(600 / mod.DEFAULT_ROW_HEIGHT) - 20, "when the row height has not been measured yet, the default estimate is used");
  const overscroll = mod.planWindow({ itemCount: 200, scrollTop: 1e9, viewportHeight: 600, averageHeight: 50, pinnedToEnd: false });
  assert(overscroll.end === 200 && overscroll.bottomPad === 0, "when scrolled too far, the window is clamped to the end and the placeholder is 0");
  assert(mod.planWindow({ itemCount: 0, scrollTop: 0, viewportHeight: 0, averageHeight: 0, pinnedToEnd: false }).active === false, "an empty list does not enable windowing");

  assert(mod.measureAverage([100, 200]) === 150, "the average row height is the mean of the already rendered rows");
  assert(mod.measureAverage([]) === undefined && mod.measureAverage([0, -1]) === undefined, "when there is no valid row height, returns undefined");
  assert(mod.shouldUpdateAverage(0, 80) === true, "the first measured row height is adopted");
  assert(mod.shouldUpdateAverage(100, 105) === false, "a 5% deviation is not updated (dead zone)");
  assert(mod.shouldUpdateAverage(100, 130) === true, "only a 30% deviation is updated");

  // ── 4. ?perf=1 只在带参时生效 ────────────────────────────────
  assert(mod.perfFlag("?perf=1") === true && mod.perfFlag("perf=1") === true, "only enabled when perf=1 is present");
  assert(mod.perfFlag("") === false && mod.perfFlag("?perf=0") === false && mod.perfFlag("?debug=1") === false, "no log is printed when the parameter is absent");
  const line = mod.formatPerfLine(120, 12.34);
  assert(line.includes("120 messages") && line.includes("12.3ms"), `Log format: ${line}`);

  // ── 5. 接线 ───────────────────────────────────────────────
  assert(/useMessageWindow\(/.test(CHAT) && /windowPlan\.topPad/.test(CHAT) && /windowPlan\.bottomPad/.test(CHAT), "the chat page renders top and bottom placeholders using the windowing plan");
  assert(/items\.slice\(windowPlan\.start, windowPlan\.end\)/.test(CHAT), "only the items inside the window are rendered");
  assert(CHAT.includes('data-host-row'), "each row carries a measurement marker (the row height is filled back in from this)");
  assert(/perfFlag\(/.test(CHAT) && /formatPerfLine\(/.test(CHAT) && /onSnapshot:/.test(CHAT), "when ?perf=1 is present, the snapshot elapsed time is recorded");
  assert(/stickRef\.current,?\s*$/m.test(CHAT) || CHAT.includes("pinnedToEnd: stickRef.current"), "when pinned to the bottom, the window is anchored to the end");

  // ── 6. 动效与减少动态 ─────────────────────────────────────
  assert(
    /\.host-msg-row\[data-new="true"\] \{ animation: host-row-in var\(--muse-motion-durations-content-enter\)/.test(CSS),
    "a new message entering uses the content-enter duration (only given to the newest one, to avoid repeated entry when scrolling with windowing)",
  );
  assert(/var\(--muse-motion-easings-emphasized\)/.test(CSS), "easing takes the motion token");
  assert(/\.host-msg-status,\s*\n?\s*\.host-msg-failed \{ animation: host-status-in/.test(CSS), "status changes have a transition");
  assert(/\.host-drawer \{[\s\S]{0,200}var\(--muse-motion-durations-nav-horizontal\)/.test(CSS), "drawer open/close duration uses the motion token");
  assert(/\.host-scrim \{ transition: opacity var\(--muse-motion-durations-normal\)/.test(CSS), "scrim fade-in duration uses the motion token");
  const reduced = CSS.slice(CSS.indexOf("@media (prefers-reduced-motion: reduce)"));
  for (const selector of [".host-msg-row", ".host-msg-status", ".host-msg-failed", ".host-drawer", ".host-scrim", ".host-picker"]) {
    assert(reduced.includes(selector), `with reduced motion, ${selector} is also turned off`);
  }
  assert(/animation: none !important/.test(reduced) && /transition: none !important/.test(reduced), "reduced motion means no movement rather than faster");
  assert(/\.host-window-pad \{ flex: 0 0 auto/.test(CSS), "the window placeholder block does not participate in flexing");

  if (failures > 0) throw new Error(`Performance and motion assertions failed (${failures} items)`);
  console.log("\nAll performance and motion assertions passed (memo / merge / windowing / perf switch / motion).");
} finally {
  await rm(TEMP, { force: true });
}
