/**
 * 设置子页的渲染断言：归页、控件映射、枚举 label、只读行、写入接线、失败回滚、缺能力位提示。
 *
 * 覆盖五个真实子页（外观 / 聊天 / 语音 / 记忆 / 通用）。断言只用 Host 契约里的字段：
 * `values[].{key,type,value,options[].label}` + `capabilities.settings_values`，
 * 界面文案（枚举项、只读值）全部来自 fixture 里的 PROTO-* 串——只要前端偷偷自建
 * 中文表，这些断言就会因为找不到字段而失败。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".settings-pages-check.mjs");

const ENTRY = `
export { SettingsValuePage } from "./src/host/SettingsValuePage.tsx";
export {
  SETTINGS_PAGE_SPECS,
  isReadOnlyKey,
  normalizeSettingKey,
  numberStepFor,
  pageForEntry,
  pageForValueKey,
  stepNumber,
  valuesForPage,
} from "./src/host/settingsPages.ts";
export { applySettingValue, settingValueOf } from "./src/host/settingsState.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "settings-pages-entry.tsx", loader: "tsx" },
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

const CAPS_ON = { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: true };
const CAPS_OFF = { known: true, media: true, toolCallDetails: true, messageStatus: true, settingsValues: false };

/** 一行 = 一个 ListRow；interactive 的行是 <button>，非 interactive 的是 <div>。 */
const countRows = (html) => (html.match(/class="muse-listrow(?:"| )/g) ?? []).length;
const countSwitches = (html) => (html.match(/role="switch"/g) ?? []).length;

const option = (value, label) => ({ value, label });

/** 覆盖五页的假数据；键名按 App 的命名习惯，label / value 用 PROTO-* 保证只可能来自协议。 */
const VALUES = [
  {
    key: "appearance.themeMode",
    type: "enum",
    value: "dark",
    options: [option("light", "PROTO-浅色"), option("dark", "PROTO-深色"), option("system", "PROTO-跟随系统")],
  },
  { key: "appearance.fontSizeScale", type: "number", value: 1 },
  {
    key: "chatPreferences.enterToSend",
    type: "enum",
    value: "enter",
    options: [option("enter", "PROTO-回车发送"), option("button", "PROTO-按钮发送")],
  },
  { key: "chat.showToolCalls", type: "boolean", value: true },
  { key: "chat.showTimestamps", type: "boolean", value: false },
  { key: "chatPreferences.streamResponse", type: "boolean", value: true },
  {
    key: "chat.thinkingLevel",
    type: "enum",
    value: "medium",
    options: [option("low", "PROTO-低"), option("medium", "PROTO-中"), option("high", "PROTO-高")],
  },
  {
    key: "tts.engine",
    type: "enum",
    value: "system",
    options: [option("system", "PROTO-系统TTS"), option("openai", "PROTO-OpenAI")],
  },
  { key: "tts.speechRate", type: "number", value: 1.1 },
  { key: "tts.voiceName", type: "text", value: "PROTO-音色名" },
  { key: "memoryEnabled", type: "boolean", value: true },
  { key: "memoryAutoSave", type: "boolean", value: false },
  {
    key: "language",
    type: "enum",
    value: "zh",
    options: [option("zh", "PROTO-中文"), option("en", "PROTO-English")],
  },
  { key: "modelConfigured", type: "text", value: "PROTO-已配置" },
];

const SNAPSHOT = {
  groups: [
    {
      key: "settings_group_chat",
      groupTitle: "PROTO-群组",
      entries: [
        {
          id: "chat",
          titleKey: "settings_screen_chat",
          title: "PROTO-聊天",
          groupKey: "settings_group_chat",
          iconKey: "chat",
          kind: "link",
        },
      ],
    },
  ],
  toggles: [],
  display: { language: "zh", themeMode: "system", fontSizeScale: "1", themeId: "default" },
  writableKeys: [],
  values: VALUES,
};

const pageOf = (id) => mod.SETTINGS_PAGE_SPECS.find((spec) => spec.id === id);

function renderPage(pageId, overrides = {}) {
  const page = pageOf(pageId);
  return render(
    mod.createElement(mod.SettingsValuePage, {
      page,
      title: "PROTO-标题",
      snapshot: SNAPSHOT,
      loading: false,
      error: undefined,
      capabilities: CAPS_ON,
      onBack: () => undefined,
      onRetry: () => undefined,
      onWrite: () => undefined,
      assistants: [],
      providers: [],
      assistantDetails: {},
      assistantDetailUnsupported: false,
      memory: { items: [], hasMore: false, loading: false, error: undefined, query: undefined, unsupported: false },
      capabilityFlags: [],
      onRequestAssistantDetail: () => undefined,
      onRequestProviders: () => undefined,
      onRequestMemory: () => undefined,
      onStartSession: () => undefined,
      knowledge: { bases: [], loading: false, error: undefined, unsupported: false },
      knowledgeDocuments: { kbId: undefined, items: [], hasMore: false, loading: false, error: undefined, unsupported: false },
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      onRequestKnowledge: () => undefined,
      onRequestKnowledgeDocuments: () => undefined,
      onRequestTasks: () => undefined,
      ...overrides,
    }),
  );
}

try {
  // ── 1. 归页：目录条目 → 页面，值键 → 页面 ────────────────────────
  assert(mod.pageForEntry("appearance")?.id === "appearance", "Appearance entry → appearance page");
  assert(mod.pageForEntry("chat")?.id === "chat", "Chat entry → chat page");
  assert(mod.pageForEntry("tts")?.id === "voice", "TTS entry → voice page");
  assert(mod.pageForEntry("memory")?.id === "memory", "Memory entry → memory page");
  assert(mod.pageForEntry("general")?.id === "general", "General entry → general page");
  assert(mod.pageForEntry("provider") === undefined, "Categories not yet built still go to the placeholder page");
  assert(mod.normalizeSettingKey("chatPreferences.enterToSend") === "chatpreferencesentertosend", "Key normalization removes dots and lowercases");

  for (const [key, pageId] of [
    ["appearance.themeMode", "appearance"],
    ["appearance.fontSizeScale", "appearance"],
    ["chatPreferences.enterToSend", "chat"],
    ["chat.showToolCalls", "chat"],
    ["chat.showTimestamps", "chat"],
    ["chatPreferences.streamResponse", "chat"],
    ["chat.thinkingLevel", "chat"],
    ["tts.engine", "voice"],
    ["tts.speechRate", "voice"],
    ["tts.voiceName", "voice"],
    ["memoryEnabled", "memory"],
    ["memoryAutoSave", "memory"],
    ["language", "general"],
    ["modelConfigured", "general"],
  ]) {
    assert(mod.pageForValueKey(key)?.id === pageId, `Value key ${key} maps to ${pageId} page`);
  }

  // ── 2. 每页的行数与控件映射 ──────────────────────────────────────
  const appearance = renderPage("appearance");
  assert(countRows(appearance) === 2, "Appearance page has 2 rows (theme mode + font size)");
  assert(appearance.includes("PROTO-深色"), "Enum row shows the Host-provided label on the right (current value)");
  assert(!appearance.includes(">dark<"), "Enum row does not use the raw value as its display name");
  assert(/aria-label="[^"]*Decrease"/.test(appearance), "Number row has a − button (accessible name)");
  assert(/aria-label="[^"]*Increase"/.test(appearance), "Number row has a ＋ button (accessible name)");
  assert(appearance.includes(">1<"), "Number row shows the current value");

  const chat = renderPage("chat");
  assert(countRows(chat) === 5, "Chat page has 5 rows (send key / tool calls / timestamps / streaming / thinking level)");
  assert(countSwitches(chat) === 3, "Chat page renders 3 boolean rows as switches");
  assert(chat.includes("PROTO-回车发送") && chat.includes("PROTO-中"), "Each of the two enum rows shows its own Host label");

  const voice = renderPage("voice");
  assert(countRows(voice) === 3, "Voice page has 3 rows (engine / speech rate / voice name)");
  assert(voice.includes("PROTO-系统TTS"), "TTS engine row shows the Host label");
  assert(voice.includes(">1.1<"), "Speech rate row shows the current number");
  assert(voice.includes("PROTO-音色名"), "Voice name row shows the Host-provided value");

  const memory = renderPage("memory");
  assert(countRows(memory) === 2, "Memory page has 2 rows (toggle + auto-save)");
  assert(countSwitches(memory) === 2, "Both memory page rows are switches");

  const general = renderPage("general");
  assert(countRows(general) === 2, "General page has 2 rows (language + whether a model is configured)");
  assert(general.includes("PROTO-中文"), "Language row shows the Host label");
  assert(general.includes("PROTO-已配置"), "Model-configured row shows the Host-provided value");

  // ── 3. 只读行：音色名 / 模型是否已配置不给写入口 ─────────────────
  assert(mod.isReadOnlyKey("tts.voiceName") && mod.isReadOnlyKey("modelConfigured"), "Read-only keys are recognized");
  assert(!mod.isReadOnlyKey("tts.speechRate"), "Writable keys are not mistaken for read-only");
  const readOnlyRowHtml = render(
    mod.createElement(mod.SettingsValuePage, {
      page: pageOf("voice"),
      title: "PROTO-标题",
      snapshot: { ...SNAPSHOT, values: [{ key: "tts.voiceName", type: "text", value: "PROTO-音色名" }] },
      loading: false,
      error: undefined,
      capabilities: CAPS_ON,
      onBack: () => undefined,
      onRetry: () => undefined,
      onWrite: () => undefined,
      assistants: [],
      providers: [],
      assistantDetails: {},
      assistantDetailUnsupported: false,
      memory: { items: [], hasMore: false, loading: false, error: undefined, query: undefined, unsupported: false },
      capabilityFlags: [],
      onRequestAssistantDetail: () => undefined,
      onRequestProviders: () => undefined,
      onRequestMemory: () => undefined,
      onStartSession: () => undefined,
      knowledge: { bases: [], loading: false, error: undefined, unsupported: false },
      knowledgeDocuments: { kbId: undefined, items: [], hasMore: false, loading: false, error: undefined, unsupported: false },
      tasks: { items: [], loading: false, error: undefined, unsupported: false },
      onRequestKnowledge: () => undefined,
      onRequestKnowledgeDocuments: () => undefined,
      onRequestTasks: () => undefined,
    }),
  );
  assert(!readOnlyRowHtml.includes("host-stepper") && countSwitches(readOnlyRowHtml) === 0, "Read-only rows have no write controls");
  const readOnlyBooleanHtml = renderPage("general", {
    snapshot: { ...SNAPSHOT, values: [{ key: "modelConfigured", type: "boolean", value: true }] },
  });
  assert(countSwitches(readOnlyBooleanHtml) === 1 && readOnlyBooleanHtml.includes("disabled"), "A read-only key sent as a boolean only gets a disabled switch");

  // ── 4. 枚举行：label 缺失时回退原始 value ───────────────────────
  const fallbackHtml = renderPage("chat", {
    snapshot: {
      ...SNAPSHOT,
      values: [
        {
          key: "chat.thinkingLevel",
          type: "enum",
          value: "experimental",
          options: [option("low", "PROTO-低")],
        },
      ],
    },
  });
  assert(fallbackHtml.includes(">experimental<"), "When the current enum value is not in options, the raw value is shown directly");

  // ── 5. 写入接线：每种控件都调用 onWrite（源码级） ────────────────
  const pageSource = readFileSync(join(ROOT, "src", "host", "SettingsValuePage.tsx"), "utf8");
  assert(/onWrite\(value\.key, next\)/.test(pageSource), "Switch writes go through onWrite(key, boolean)");
  assert(/onWrite\(value\.key, stepNumber\(current, -1\)\)/.test(pageSource), "Number − writes go through onWrite(key, number)");
  assert(/onWrite\(value\.key, stepNumber\(current, 1\)\)/.test(pageSource), "Number ＋ writes go through onWrite(key, number)");
  assert(/onWrite\(value\.key, option\.value\)/.test(pageSource), "Enum option writes go through onWrite(key, optionValue)");
  const hostSettingsSource = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(/onWrite=\{onWrite\}/.test(hostSettingsSource), "The main page passes onWrite through to subpages");
  assert(
    /pageForEntry\(openPage\.entry\.id\)/.test(hostSettingsSource) &&
      /<SettingsValuePage/.test(hostSettingsSource) &&
      /<HostPlaceholder/.test(hostSettingsSource),
    "Opening a built category goes to the real subpage; other categories use the shared placeholder page",
  );
  const hostChatSource = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  assert(/onWrite=\{updateSettingValue\}/.test(hostChatSource), "The chat shell wires useHostConnection writes to the settings page");
  const connectionSource = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
  assert(/type: "settings\.update", key, value/.test(connectionSource), "Writes send the settings.update command");
  assert(/typeof value === "boolean" \? \(value \? "true" : "false"\) : value/.test(connectionSource), "Booleans are sent as the Host's existing true / false literals");
  assert(
    /applySettingValue\(currentSnapshot, rejected\.key, rejected\.previous\)/.test(connectionSource),
    "When the Host returns an error, roll back to the value from before the write",
  );
  assert(
    /optionsRef\.current\.onError\(describeHostError\(event\.error, tt\("error\.settingsSave"\), tt\)\)/.test(connectionSource),
    "After rollback, the Host's reason is passed to the toast (failure reasons are not swallowed)",
  );

  // ── 6. 乐观更新与失败回滚（纯函数） ─────────────────────────────
  const before = mod.settingValueOf(SNAPSHOT, "chat.showToolCalls");
  assert(before === true, "Before writing, the original value from values is read");
  const optimistic = mod.applySettingValue(SNAPSHOT, "chat.showToolCalls", false);
  assert(mod.settingValueOf(optimistic, "chat.showToolCalls") === false, "Optimistic update immediately changes the value in values");
  assert(SNAPSHOT.values.find((v) => v.key === "chat.showToolCalls").value === true, "Optimistic update does not modify the original object (pure function)");
  const rolledBack = mod.applySettingValue(optimistic, "chat.showToolCalls", before);
  assert(mod.settingValueOf(rolledBack, "chat.showToolCalls") === true, "Applying the pre-write value once more completes the rollback");
  const numbers = mod.applySettingValue(SNAPSHOT, "tts.speechRate", 1.2);
  assert(mod.settingValueOf(numbers, "tts.speechRate") === 1.2, "Number writes likewise only change the target key");
  const togglesProjection = mod.applySettingValue(
    {
      ...SNAPSHOT,
      toggles: [{ key: "piiGuardEnabled", enabled: true }],
    },
    "piiGuardEnabled",
    false,
  );
  assert(togglesProjection.toggles[0].enabled === false, "Boolean writes also update the toggles projection (the old main page follows along)");
  const legacySnapshot = { ...SNAPSHOT, values: undefined };
  assert(mod.applySettingValue(legacySnapshot, "chat.showToolCalls", false).values === undefined, "A legacy snapshot without values does not gain a values field out of nowhere");

  // 数字步进：整数按 1，小数按 0.1，不越过 0
  assert(mod.numberStepFor(1) === 1 && mod.numberStepFor(1.1) === 0.1, "Integer and decimal step sizes differ");
  assert(mod.stepNumber(1, 1) === 2 && mod.stepNumber(1.1, 1) === 1.2, "＋ advances by the step without leaving floating-point residue");
  assert(mod.stepNumber(0, -1) === 0, "− does not go below 0");

  // ── 7. 能力位 / 加载态 / 空态 ───────────────────────────────────
  const unsupported = renderPage("appearance", { capabilities: CAPS_OFF });
  assert(unsupported.includes("This version can't be changed from the web"), "When the settings_values capability is missing, a clear notice is shown");
  assert(countRows(unsupported) === 1 && countSwitches(unsupported) === 0, "When the capability is missing, no adjustable rows are rendered");

  const oldHost = renderPage("appearance", { snapshot: { ...SNAPSHOT, values: undefined } });
  assert(oldHost.includes("This version can't be changed from the web"), "When the Host does not send values, the same notice is shown (instead of an empty list)");

  const loading = renderPage("appearance", { snapshot: undefined, loading: true });
  // 骨架延迟 300ms（useDelayedFlag）：刚进子页时只给安静占位
  assert(loading.includes('role="status"') && !loading.includes("muse-skeleton"), "Before values arrive, a quiet placeholder is shown first (skeleton delayed by 300ms)");

  const failed = renderPage("appearance", { snapshot: undefined, loading: false, error: "PROTO-读取失败" });
  assert(failed.includes("PROTO-读取失败") && failed.includes(">Retry<"), "Read failure shows a retryable error bar");

  const emptyPage = renderPage("memory", { snapshot: { ...SNAPSHOT, values: [] } });
  assert(emptyPage.includes("Nothing to adjust on this page"), "When this page has no matching values, the reason is explained instead of showing a blank");

  // ── 8. 样式：新类名只走令牌 ────────────────────────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-setting-value", "host-stepper", "host-stepper-button", "host-stepper-value", "host-setting-check"]) {
    assert(css.includes(`.${selector}`), `Styles define .${selector}`);
  }

  if (failures > 0) throw new Error(`Settings subpage assertions failed (${failures})`);
  console.log("\nAll settings subpage assertions passed (page mapping / controls / read-only / writes / rollback / capabilities).");
} finally {
  await rm(TEMP, { force: true });
}
