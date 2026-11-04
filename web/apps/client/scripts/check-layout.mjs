/**
 * 宽屏适配的静态断言：三档断点、左栏与抽屉的互斥、内容最大宽约束、快捷键。
 *
 * 这些是纯 CSS / 结构约束，没有浏览器就没法在运行时断言，所以直接对 `styles.css`
 * 与组件源码做结构化检查：先把 `@media (min-width: …)` 的块体裁出来，再断言块里
 * 到底写了什么。这样「桌面档藏掉抽屉」「手机档不显示左栏」这类互斥关系一旦被误改，
 * `npm test` 立刻失败。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const CSS_RAW = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const DRAWER = readFileSync(join(ROOT, "src", "host", "SessionDrawer.tsx"), "utf8");
const LIGHTBOX = readFileSync(join(ROOT, "src", "host", "ImageLightbox.tsx"), "utf8");

/** 注释里也会出现选择器名，先剥掉注释再找规则体，避免命中注释。 */
const CSS = CSS_RAW.replace(/\/\*[\s\S]*?\*\//g, "");

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

/** 裁出某个 `@media (…)` 的块体（含花括号内的全部内容）。 */
function mediaBlock(source, query) {
  const marker = `@media (${query}) {`;
  const start = source.indexOf(marker);
  if (start < 0) return "";
  const open = source.indexOf("{", start);
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === "{") depth += 1;
    else if (source[index] === "}") {
      depth -= 1;
      if (depth === 0) return source.slice(open + 1, index);
    }
  }
  return "";
}

/** 某个选择器紧随其后的声明体（取到下一个 `}`）。 */
function ruleBody(block, selector) {
  const start = block.indexOf(selector);
  if (start < 0) return "";
  const open = block.indexOf("{", start);
  const close = block.indexOf("}", open);
  if (open < 0 || close < 0) return "";
  return block.slice(open + 1, close);
}

const tablet = mediaBlock(CSS, "min-width: 600px");
const desktop = mediaBlock(CSS, "min-width: 1024px");

try {
  // ── 1. 三档断点 ─────────────────────────────────────────────────
  assert(CSS.includes("@media (min-width: 600px) {"), "there is a tablet tier breakpoint (min-width: 600px)");
  assert(CSS.includes("@media (min-width: 1024px) {"), "there is a desktop tier breakpoint (min-width: 1024px)");
  assert(tablet.length > 0 && desktop.length > 0, "both breakpoints have block bodies (not empty shells)");
  assert(
    !/min-width:\s*(7\d\d|8\d\d|9\d\d)px/.test(CSS),
    "no width breakpoints other than the three tiers are inserted (the mobile tier is the default style, with no query added)",
  );

  // ── 2. 左栏与抽屉互斥：手机 / 平板只有抽屉，桌面只有左栏 ──────────
  assert(/display:\s*none/.test(ruleBody(CSS, ".host-sidebar {")), "the left column is hidden by default on the mobile / tablet tiers");
  const sidebarDesktop = ruleBody(desktop, ".host-sidebar {");
  assert(/display:\s*flex/.test(sidebarDesktop), "the left column is always present on the desktop tier (display: flex)");
  assert(/flex-direction:\s*column/.test(sidebarDesktop), "the left column is a vertical column (the session list scrolls itself)");
  assert(
    /flex:\s*0 0 var\(--host-sidebar-w\)/.test(sidebarDesktop) && /width:\s*var\(--host-sidebar-w\)/.test(sidebarDesktop),
    "left column width uses --host-sidebar-w (280-320 tier)",
  );
  assert(/\.host-scrim,\s*\.host-drawer\s*\{\s*display:\s*none/.test(desktop), "on the desktop tier neither the drawer nor the scrim appears (mutually exclusive with the left column)");
  assert(/button\.quiet\.host-menu-button\s*\{\s*display:\s*none/.test(desktop), "the desktop tier top bar does not show the hamburger icon");
  // 这两个「隐藏」必须比 `button.host-icon-button { display: inline-grid }` 更具体，否则同级类名会被盖掉
  assert(/button\.host-icon-button\s*\{[^}]*display:\s*inline-grid/.test(CSS), "the icon button base class really does carry its own display (so the hiding rule needs .quiet to override it)");

  // ── 3. 内容最大宽与留白 ─────────────────────────────────────────
  const threadTablet = ruleBody(tablet, ".host-thread");
  assert(/max-width:\s*var\(--host-content-max\)/.test(threadTablet), "the tablet tier message column is width-limited (var(--host-content-max))");
  assert(/margin-inline:\s*auto/.test(threadTablet), "the tablet tier message column is centered, with whitespace on both sides");
  assert(/--host-content-max:\s*640px/.test(ruleBody(CSS, ".host-scope {")), "the tablet tier content max width defaults to 640 (defined in .host-scope)");
  assert(/--host-content-max:\s*720px/.test(desktop), "the desktop tier content max width switches to 720");
  assert(
    /\.host-composer\s*\{[^}]*padding-inline:\s*max\([^}]*var\(--host-content-max\)/.test(tablet),
    "the tablet tier input bar follows the content width and is centered (not full-screen width)",
  );
  assert(
    /\.host-composer\s*\{[^}]*padding-inline:\s*max\(var\(--muse-space-large-gap\)/.test(desktop),
    "the desktop tier input bar has at least 24 left/right inner padding (largeGap)",
  );
  assert(
    /\.host-scroll\s*\{\s*padding-inline:\s*var\(--muse-space-large-gap\)/.test(desktop),
    "the desktop tier message area has at least 24 left/right inner padding",
  );
  assert(
    /\.host-root:not\(\.is-settings\)\s*\{\s*flex-direction:\s*row/.test(desktop),
    "the desktop tier shell becomes two left/right columns (chat view only, the settings page remains single-column)",
  );
  assert(
    /\.host-root\.is-settings \.muse-screen-body\s*\{[^}]*max-width:\s*var\(--host-content-max\)/.test(desktop),
    "the settings page is width-limited and centered on the desktop tier, not stretched to the full screen",
  );
  assert(/--host-sidebar-w:\s*300px/.test(ruleBody(CSS, ".host-scope {")), "the left column width variable takes 300 (the middle value of 280-320)");
  // 左栏可折叠：默认收起态不占位，收起后顶栏出展开按钮
  assert(/\.host-sidebar\.is-collapsed\s*\{\s*display:\s*none/.test(desktop), "on the desktop tier, the collapsed left column takes no space");
  assert(/\.host-root\.is-sidebar-collapsed \.host-sidebar-toggle\s*\{\s*display:\s*inline-grid/.test(desktop), "after collapsing, an expand button appears in the top bar");
  assert(
    /display:\s*none/.test(ruleBody(CSS, "button.quiet.host-sidebar-toggle {")),
    "the expand button is hidden by default (does not appear on the mobile / tablet tiers)",
  );

  // ── 4. 结构：左栏常驻 + 主列包住顶栏 / 消息 / 输入栏 ──────────────
  assert(/<SessionSidebar/.test(CHAT), "the chat page renders the always-present left column");
  assert(/<div className="host-main">/.test(CHAT), "the top bar / message area / input bar are wrapped into the main column");
  assert(/host-menu-button/.test(CHAT), "The hamburger button carries a class name that the desktop tier can hide");
  assert(/className="host-root host-scope is-settings"/.test(CHAT), "the settings view carries the is-settings marker");
  assert(/export function SessionSidebar/.test(DRAWER), "the left column is an independently exported component (sharing the same panel as the drawer)");
  assert(/host-sidebar-settings[\s\S]{0,160}t\("common\.settings"\)/.test(DRAWER), "there is a settings entry at the top of the left column");
  assert(/variant === "drawer"[\s\S]{0,300}host-drawer-close/.test(DRAWER), "the drawer keeps a close button (mobile tier)");
  assert(/<SessionDrawer/.test(CHAT), "the mobile / tablet tiers still mount the drawer (hidden by CSS on the desktop tier)");
  assert(/\(min-width: 1024px\)/.test(DRAWER), "the breakpoint used for left column prefetch matches the CSS desktop tier (1024)");
  assert(/host-sidebar-collapse/.test(DRAWER), "the left column header has a collapse button");
  assert(/collapsed=\{sidebarCollapsed\}/.test(CHAT) && /is-sidebar-collapsed/.test(CHAT), "the left column collapsed state is held by the chat page");

  // ── 5. 会话行沿 ListRow 几何 + 选中态 ───────────────────────────
  const sessionRow = ruleBody(CSS, "button.host-session {");
  assert(/min-height:\s*var\(--muse-component-list-row-min-height\)/.test(sessionRow), "session row height uses ListRow's 60");
  assert(
    /padding:\s*var\(--muse-component-list-row-padding-y\) var\(--muse-component-list-row-padding-x\)/.test(sessionRow),
    "session row inner padding uses ListRow's 16/12",
  );
  assert(/\.host-session-wrap\.is-active/.test(CSS), "the selected session has a highlighted state");

  // ── 6. 快捷键：Enter 发送 / Shift+Enter 换行 / Esc 关全屏 ────────
  assert(/if \(event\.key !== "Enter"\) return;/.test(CHAT) && /if \(event\.shiftKey\) return;/.test(CHAT), "Enter sends, Shift+Enter is not intercepted (newline)");
  assert(/event\.nativeEvent\.isComposing/.test(CHAT), "no accidental sending while composing with an input method");
  assert(/event\.key === "Escape"/.test(LIGHTBOX), "Esc closes the full-screen image");

  if (failures > 0) throw new Error(`Wide-screen layout assertions failed (${failures} items)`);
  console.log("\nAll wide-screen layout assertions passed (three-tier breakpoints / left column and drawer mutually exclusive / content width limit / keyboard shortcuts).");
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
