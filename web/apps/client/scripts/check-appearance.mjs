/**
 * 外观（主题 / 字号）的断言：三态主题的属性写入、四档字号映射、theme-color 更新、
 * 切换失败回滚、根属性与 CSS 选择器是否对得上。
 *
 * 纯逻辑直接跑；DOM 应用用一个极小的 target 替身（不引 jsdom）；
 * CSS 侧按源码结构断言选择器与倍率，并检查两块暗色声明没有漂。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".appearance-check.mjs");

const ENTRY = `
export {
  applyAppearance,
  FONT_SCALE_FACTOR,
  FONT_SCALES,
  readFontScale,
  readThemeMode,
  resolveScheme,
  THEME_MODES,
  themeColorFor,
} from "./src/host/appearance.ts";
export { readAppearance } from "./src/host/useAppearance.ts";
export { applySettingValue, settingValueOf } from "./src/host/settingsState.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "appearance-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    loader: { ".css": "empty" },
    external: ["react"],
    outfile: TEMP,
    logLevel: "warning",
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const PRIMITIVES = readFileSync(join(ROOT, "src", "components", "primitives.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const TOKEN_CSS = readFileSync(join(ROOT, "src", "design", "token-css.ts"), "utf8");
const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");

/** 取某个 `@media (…)` 的块体。 */
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

/** 取某条规则（选择器 + `{`）的声明体。 */
function ruleBody(source, selector) {
  const start = source.indexOf(selector);
  if (start < 0) return "";
  const open = source.indexOf("{", start);
  const close = source.indexOf("}", open);
  if (open < 0 || close < 0) return "";
  return source.slice(open + 1, close);
}

/** 把声明体拆成 { "--ui-surface": "var(…)" } 这样的映射。 */
function declarations(body) {
  const map = {};
  for (const part of body.split(";")) {
    const index = part.indexOf(":");
    if (index < 0) continue;
    const name = part.slice(0, index).trim();
    const value = part.slice(index + 1).trim();
    if (name.length > 0) map[name] = value;
  }
  return map;
}

const settingsWith = (values, display = { language: "zh", themeMode: "system", fontSizeScale: "1", themeId: "default" }) => ({
  snapshot: { groups: [], toggles: [], display, writableKeys: [], values },
  loading: false,
});

try {
  // ── 1. 三态主题：属性写入 + 优先级 ─────────────────────────────
  const makeTarget = (tokens = {}) => {
    const dataset = {};
    const meta = { content: "initial" };
    return { dataset, meta, readToken: (name) => tokens[name] ?? "" };
  };

  const system = makeTarget({ "--muse-color-scheme-light-surface": "#FAFAF8", "--muse-color-scheme-dark-surface": "#000000" });
  mod.applyAppearance(system, "system", "medium", false);
  assert(system.dataset.theme === "system", "system mode writes data-theme=system (delegated to the media query)");
  assert(system.dataset.fontScale === "medium", "font size tier is written as data-font-scale");

  const forcedDark = makeTarget({ "--muse-color-scheme-light-surface": "#FAFAF8", "--muse-color-scheme-dark-surface": "#000000" });
  mod.applyAppearance(forcedDark, "dark", "medium", false);
  assert(forcedDark.dataset.theme === "dark", "dark forces dark (even if the system is light)");

  const forcedLight = makeTarget();
  mod.applyAppearance(forcedLight, "light", "medium", true);
  assert(forcedLight.dataset.theme === "light", "light forces light (even if the system is dark)");

  assert(mod.resolveScheme("system", true) === "dark", "system + system dark → dark");
  assert(mod.resolveScheme("system", false) === "light", "system + system light → light");
  assert(mod.resolveScheme("light", true) === "light", "forced light is not affected by the system");
  assert(mod.resolveScheme("dark", false) === "dark", "forced dark is not affected by the system");

  assert(JSON.stringify(mod.THEME_MODES) === '["system","light","dark"]', "the three theme tiers are exactly system/light/dark");
  assert(mod.readThemeMode("DARK") === "dark" && mod.readThemeMode(" system ") === "system", "theme values tolerate both case and whitespace");
  assert(mod.readThemeMode("blue") === undefined, "an unrecognized theme value is treated as not provided");

  // CSS：深色两处声明（媒体查询 + 属性）且互相一致
  const mediaDark = declarations(ruleBody(mediaBlock(PRIMITIVES, "prefers-color-scheme: dark"), ':root:not([data-theme="light"])'));
  const attrDark = declarations(ruleBody(PRIMITIVES, ':root[data-theme="dark"]'));
  assert(Object.keys(attrDark).length > 10, `the attribute dark block has ${Object.keys(attrDark).length} declarations`);
  assert(
    JSON.stringify(mediaDark) === JSON.stringify(attrDark),
    "the dark declarations in the media query and the attribute match item by item (to prevent drift)",
  );
  assert(/var\(--muse-color-scheme-dark-surface\)/.test(attrDark["--ui-surface"] ?? ""), "forced dark points to the dark scheme token");
  assert(PRIMITIVES.includes(':root:not([data-theme="light"])'), "when the system is dark but light is forced, the media query is skipped");
  assert(CSS.includes(':root[data-theme="dark"] .host-scope { color-scheme: dark; }'), "host scope follows forced dark");
  assert(CSS.includes(':root[data-theme="light"] .host-scope { color-scheme: light; }'), "host scope follows forced light");

  // ── 2. theme-color 更新 ──────────────────────────────────────
  assert(system.meta.content === "#FAFAF8", `system (system light) theme-color takes the light surface: ${system.meta.content}`);
  assert(makeTarget && mod.themeColorFor("dark", (name) => (name.endsWith("dark-surface") ? "#000000" : "#FAFAF8")) === "#000000", "themeColorFor takes the token by scheme");
  const darkMeta = makeTarget({ "--muse-color-scheme-dark-surface": "#000000", "--muse-color-scheme-light-surface": "#FAFAF8" });
  mod.applyAppearance(darkMeta, "dark", "medium", false);
  assert(darkMeta.meta.content === "#000000", "after switching to dark, theme-color changes to the dark surface accordingly");
  const noTokens = makeTarget();
  mod.applyAppearance(noTokens, "dark", "medium", false);
  assert(noTokens.meta.content === "initial", "when the token cannot be read, meta is left untouched (no empty string left behind)");

  // ── 3. 四档字号映射 ─────────────────────────────────────────
  assert(JSON.stringify(mod.FONT_SCALES) === '["small","medium","large","xlarge"]', "the four tiers are exactly small/medium/large/xlarge");
  assert(mod.FONT_SCALE_FACTOR.small === 0.9 && mod.FONT_SCALE_FACTOR.medium === 1, "small tier 0.9, medium tier 1.0");
  assert(mod.FONT_SCALE_FACTOR.large === 1.1 && mod.FONT_SCALE_FACTOR.xlarge === 1.2, "large tier 1.1, xlarge 1.2");
  assert(mod.readFontScale("xlarge") === "xlarge" && mod.readFontScale("LARGE") === "large", "font size tier names tolerate case");
  assert(mod.readFontScale("1") === "medium", "the number 1 normalizes to medium");
  assert(mod.readFontScale(0.9) === "small" && mod.readFontScale("1.2") === "xlarge", "a numeric factor normalizes to the nearest tier");
  assert(mod.readFontScale("huge") === undefined && mod.readFontScale(0) === undefined, "an unrecognized factor is treated as not provided");

  for (const scale of ["small", "medium", "large", "xlarge"]) {
    assert(CSS.includes(`:root[data-font-scale="${scale}"]`), `CSS provides a data-font-scale selector for ${scale}`);
  }
  const largeRule = declarations(ruleBody(CSS, ':root[data-font-scale="large"]'));
  assert(largeRule["--text-scale"] === "1.1", "the large tier sets --text-scale to 1.1");
  assert(largeRule["--host-bubble-boost"] === "1.08", "the large tier also widens bubbles by 8%");
  const xlargeRule = declarations(ruleBody(CSS, ':root[data-font-scale="xlarge"]'));
  assert(xlargeRule["--host-bubble-boost"] === "1.16", "the xlarge tier widens by 16% (0.7 × 1.16 ≈ 0.81 still does not overflow the screen)");

  // 排版令牌真的乘了倍率，间距令牌没被整体缩放
  assert(/calc\(\$\{value\}px \* var\(--text-scale, 1\)\)/.test(TOKEN_CSS), "font size / line height tokens are multiplied by --text-scale");
  assert(/key !== "fontSize" && key !== "lineHeight"/.test(TOKEN_CSS), "only fontSize / lineHeight are scaled, spacing is untouched");
  assert(/max-width: calc\(100% \* var\(--muse-component-bubble-max-width-ratio\) \* var\(--host-bubble-boost\)\)/.test(CSS), "bubble max width widens with font size");
  assert(/--host-page: calc\(var\(--muse-space-screen\) \* var\(--text-scale\)\)/.test(CSS), "page margins adjust proportionally with font size");
  assert(/padding: calc\(var\(--muse-component-bubble-inner-padding-y\) \* var\(--text-scale\)\)/.test(CSS), "bubble inner padding adjusts proportionally with font size (does not stick to the edge)");

  // ── 4. 从设置值读外观 + 失败回滚 ─────────────────────────────
  const darkSnapshot = settingsWith([
    { key: "appearance.themeMode", type: "enum", value: "dark" },
    { key: "appearance.fontSizeScale", type: "enum", value: "xlarge" },
  ]);
  const read0 = mod.readAppearance(darkSnapshot);
  assert(read0.mode === "dark" && read0.scale === "xlarge", "theme and font size are read from values (keys with a prefix are also recognized)");

  const legacy = settingsWith(undefined, { language: "zh", themeMode: "light", fontSizeScale: "1.1", themeId: "default" });
  const readLegacy = mod.readAppearance(legacy);
  assert(readLegacy.mode === "light" && readLegacy.scale === "large", "an old Host that only provides display can still be read");

  const empty = mod.readAppearance({ loading: false });
  assert(empty.mode === "system" && empty.scale === "medium", "when there are no settings, falls back to system + medium");

  // 乐观切换：values 一变主题就变；回滚后恢复
  const optimistic = mod.applySettingValue(darkSnapshot.snapshot, "appearance.themeMode", "light");
  assert(mod.readAppearance({ ...darkSnapshot, snapshot: optimistic }).mode === "light", "after an optimistic write, the theme immediately becomes light");
  const rolledBack = mod.applySettingValue(optimistic, "appearance.themeMode", "dark");
  assert(mod.readAppearance({ ...darkSnapshot, snapshot: rolledBack }).mode === "dark", "after rollback, the theme returns to dark");
  assert(CHAT.includes("useAppearance(settings)"), "the chat shell connects setting values to the appearance");
  assert(/requestSettings\(\)/.test(CHAT), "settings are fetched once on connect (without waiting for the settings page to open)");

  // ── 5. 自绘元素跟着 scheme 走（只用令牌） ────────────────────
  const tokenDriven = [
    [".host-lightbox", "--host-scrim"],
    [".host-voice-track", "--host-veil"],
    [".host-details", "--host-veil"],
    [".host-bubble-tools", "flex"],
  ];
  for (const [selector, needle] of tokenDriven) {
    const body = ruleBody(CSS, `${selector} {`);
    assert(body.length > 0 && body.includes(needle), `${selector} uses a token (${needle})`);
  }
  assert(
    /\.muse-skeleton \{[\s\S]{0,400}?var\(--ui-surface-variant\)/.test(PRIMITIVES),
    "the skeleton uses a token background color and changes automatically in dark mode",
  );

  if (failures > 0) throw new Error(`Appearance assertions failed (${failures} items)`);
  console.log("\nAll appearance assertions passed (three-state theme / four font size tiers / theme-color / rollback / linkage).");
} finally {
  await rm(TEMP, { force: true });
}
