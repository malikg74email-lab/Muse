/**
 * 设置搜索的断言：本地过滤、面包屑、直达路由、空态、快捷键接线。
 *
 * 数据只用 fixture 里的 `settings.snapshot`（groups + entries + values），
 * 与运行时一致：搜索结果全部来自本地过滤，没有任何协议请求参与。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-search-check.mjs");

const ENTRY = `
export { breadcrumbText, buildSettingsSearchRows, searchSettings, settingsPageTitle } from "./src/host/settingsQuery.ts";
export { SettingsSearch } from "./src/host/SettingsSearch.tsx";
export { SettingsValuePage } from "./src/host/SettingsValuePage.tsx";
export { createElement } from "react";
export { renderToStaticMarkup } from "react-dom/server";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-search-entry.tsx", loader: "tsx" },
    bundle: true,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    loader: { ".css": "empty" },
    external: ["react", "react-dom", "react-dom/server"],
    outfile: TEMP,
    logLevel: "warning",
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();
const render = (element) => mod.renderToStaticMarkup(element);

const entry = (id, title, extra = {}) => ({
  id,
  titleKey: `key_${id}`,
  title,
  groupKey: "group",
  iconKey: "chat",
  kind: "link",
  ...extra,
});

const SNAPSHOT = {
  groups: [
    {
      key: "settings_group_appearance",
      groupTitle: "外观分类",
      entries: [
        entry("appearance", "外观", { desc: "主题与字号" }),
        entry("miniphone", "小手机", { enabled: false }),
      ],
    },
    {
      key: "settings_group_chat",
      groupTitle: "聊天分类",
      entries: [
        entry("chat", "聊天", { desc: "消息与生成" }),
        entry("pii", "隐私保护", { kind: "switch", toggleKey: "piiGuardEnabled", checked: true }),
        entry("weird", "未知条目", { kind: "mystery" }),
      ],
    },
  ],
  toggles: [{ key: "piiGuardEnabled", enabled: true }],
  display: { language: "zh", themeMode: "system", fontSizeScale: "1", themeId: "default" },
  writableKeys: [],
  values: [
    { key: "appearance.themeMode", type: "enum", value: "dark", options: [{ value: "dark", label: "PROTO-深色" }] },
    { key: "chat.showToolCalls", type: "boolean", value: true },
    { key: "unmapped.thing", type: "text", value: "x" },
  ],
};

const results = (query) => mod.searchSettings(SNAPSHOT, query);
const titles = (query) => results(query).map((hit) => hit.title);

try {
  // ── 1. 索引：只放可进入的条目与可归属的值 ────────────────────────
  const rows = mod.buildSettingsSearchRows(SNAPSHOT);
  assert(rows.length === 4, `Index has 4 rows (2 navigable entries + 2 attributable values): actual ${rows.length}`);
  assert(!rows.some((row) => row.title === "小手机"), "Entries with enabled=false are not indexed");
  assert(!rows.some((row) => row.title === "隐私保护"), "Switch entries are not indexed (they live on the category page, with nowhere to go)");
  assert(!rows.some((row) => row.title === "未知条目"), "Entries with an unknown kind are not indexed");
  assert(!rows.some((row) => row.title.includes("Unmapped")), "Values that cannot be attributed to a subpage are not indexed");

  // ── 2. 过滤：条目名 / 分类名 / 值标题都能命中 ───────────────────
  assert(titles("外观").includes("外观"), "Hit by entry name");
  assert(titles("聊天分类").includes("聊天"), "Hit by category name");
  assert(titles("theme").includes("Theme Mode"), "Hit by value row title (key-derived words)");
  assert(titles("THEME").includes("Theme Mode"), "Case-insensitive");
  assert(titles("  theme  ").includes("Theme Mode"), "Whitespace at both ends of the keyword is ignored");
  assert(titles("外观").includes("Theme Mode"), "Values can also be hit via their owning subpage title");
  assert(results("隐私").length === 0, "When only a switch entry matches, the result is empty (switches are not in the index)");
  assert(results("不存在的词").length === 0, "No match returns an empty array");
  assert(results("").length === 0, "An empty keyword returns no rows (does not flood with 48 rows)");

  // ── 3. 排序：标题命中优先于只命中面包屑的 ──────────────────────
  const appearanceHits = results("外观");
  assert(appearanceHits[0]?.title === "外观", `Title hit ranks first: ${appearanceHits.map((hit) => hit.title).join(",")}`);
  assert(appearanceHits.length === 2, "The appearance query hits both the entry and the value row below it");

  // ── 4. 面包屑：分类 › 条目 ─────────────────────────────────────
  const entryHit = results("外观").find((hit) => hit.title === "外观");
  assert(
    entryHit !== undefined && mod.breadcrumbText(entryHit) === "外观分类 › 外观",
    `Entry breadcrumb: ${entryHit === undefined ? "" : mod.breadcrumbText(entryHit)}`,
  );
  const valueHit = results("theme").find((hit) => hit.title === "Theme Mode");
  assert(
    valueHit !== undefined && mod.breadcrumbText(valueHit) === "外观 › Theme Mode",
    `Value row breadcrumb: ${valueHit === undefined ? "" : mod.breadcrumbText(valueHit)}`,
  );

  // ── 5. 直达目标 ───────────────────────────────────────────────
  assert(
    entryHit?.target.kind === "entry" && entryHit.target.entryId === "appearance",
    "Entry results point to the corresponding subpage entry id",
  );
  assert(
    valueHit?.target.kind === "value" &&
      valueHit.target.valueKey === "appearance.themeMode" &&
      valueHit.target.pageId === "appearance",
    "Value results carry their owning subpage and the key to highlight",
  );
  assert(mod.settingsPageTitle(SNAPSHOT, { id: "chat", entryIds: ["chat"], keyTokens: [] }) === "聊天", "Subpage title comes from the catalog entry");

  // ── 6. 渲染：结果行 / 空态 / 提示 ─────────────────────────────
  const searchView = (query) =>
    render(
      mod.createElement(mod.SettingsSearch, {
        snapshot: SNAPSHOT,
        query,
        onQueryChange: () => undefined,
        onPick: () => undefined,
        onClose: () => undefined,
      }),
    );
  const hitHtml = searchView("theme");
  assert(hitHtml.includes("Theme Mode") && hitHtml.includes("外观 › Theme Mode"), "Result rows show both the title and the breadcrumb");
  assert(hitHtml.includes('role="listbox"') && hitHtml.includes('role="option"'), "Result list has listbox/option semantics");
  assert(hitHtml.includes("aria-label=\"Exit search\""), "Search state has a close entry point");
  const idle = searchView("");
  assert(idle.includes("Type to search categories, items and settings") && !idle.includes('role="option"'), "With an empty keyword, a hint is shown and no results are listed");
  const empty = searchView("不存在的词");
  assert(empty.includes("No settings match") && empty.includes("Clear search"), "With no match, a hint and 'Clear search' are shown");
  assert(empty.includes('role="status"'), "Empty state has status semantics");
  const withResult = searchView("外观");
  assert((withResult.match(/role="option"/g) ?? []).length === 2, "The number of rendered results matches the filtered results");

  // ── 7. 接线：快捷键与高亮 ─────────────────────────────────────
  const hostSettings = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(hostSettings.includes('event.key === "/"') && hostSettings.includes("metaKey"), "Supports / and Cmd/Ctrl+K to open search");
  assert(hostSettings.includes('event.key.toLowerCase() === "k"'), "The Cmd/Ctrl+K check is present");
  assert(hostSettings.includes("event.preventDefault()"), "The shortcut prevents the default behaviour");
  assert(hostSettings.includes('aria-label={t("settings.search")}'), "The category page has a search entry at the top");
  assert(hostSettings.includes("host-settings-search"), "The search entry is a sticky container");
  assert(hostSettings.includes("<SettingsSearch"), "The full-screen search state is mounted here");
  assert(hostSettings.includes("focusKey: target.valueKey"), "A direct value result passes focusKey to the subpage");

  const searchSource = readFileSync(join(ROOT, "src", "host", "SettingsSearch.tsx"), "utf8");
  assert(/event\.key (===|!==) "Escape"/.test(searchSource), "Search state closes with Esc");
  assert(searchSource.includes('event.key === "ArrowDown"') && searchSource.includes('event.key === "ArrowUp"'), "Up/Down keys move the selection");
  assert(searchSource.includes('event.key !== "Enter"') && searchSource.includes("onPick(hit)"), "Enter opens the selected item");

  const valuePageHtml = render(
    mod.createElement(mod.SettingsValuePage, {
      page: { id: "chat", entryIds: ["chat"], keyTokens: [] },
      title: "聊天",
      snapshot: SNAPSHOT,
      loading: false,
      error: undefined,
      capabilities: { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: true, messageWrite: true, multiSelect: true },
      focusKey: "chat.showToolCalls",
      onBack: () => undefined,
      onRetry: () => undefined,
      onWrite: () => undefined,
    }),
  );
  assert(valuePageHtml.includes("host-setting-highlight"), "Direct navigation highlights the target row");
  assert((valuePageHtml.match(/host-setting-highlight/g) ?? []).length === 1, "Only one row is highlighted");
  assert(/host-setting-highlight[\s\S]{0,400}Show Tool Calls/.test(valuePageHtml), "The highlighted row is the one the result points to");

  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-settings-search", "host-search-trigger", "host-search-result", "host-search-empty", "host-setting-highlight"]) {
    assert(css.includes(`.${selector}`), `Styles define .${selector}`);
  }
  assert(/\.host-settings-search\s*\{[^}]*position:\s*sticky/.test(css), "The search entry is sticky");

  if (failures > 0) throw new Error(`Settings search assertions failed (${failures})`);
  console.log("\nAll settings search assertions passed (filtering / breadcrumbs / direct navigation / empty state / shortcuts).");
} finally {
  await rm(TEMP, { force: true });
}
