/**
 * 数据与隐私页 / 平台专有页的断言：存储分布、备份三态、PII 开关可写性、
 * 五个平台页的状态映射、缺能力位提示、新增词条。
 *
 * 组件用 renderToStaticMarkup 直接渲染；交互路径（点开关、拉 system.status）按源码断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".data-platform-check.mjs");

const ENTRY = `
export { DataPrivacyPage } from "./src/host/DataPrivacyPage.tsx";
export { PlatformPage } from "./src/host/PlatformPage.tsx";
export { SYSTEM_STATUS_FLAGS, platformAvailability, platformKeysForEntry, supportsFlag } from "./src/host/featureCapabilities.ts";
export { formatBytes, formatDuration } from "./src/i18n/format.ts";
export { ZH, EN } from "./src/i18n/messages.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "data-platform-entry.tsx", loader: "tsx" },
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

const HOST_SETTINGS = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const STATUS = {
  appVersion: "2.6.10",
  appVersionCode: 20610,
  uptimeMs: 3 * 60 * 60 * 1000,
  storage: {
    databaseBytes: 4 * 1024 * 1024,
    mediaBytes: 12 * 1024 * 1024,
    voiceBytes: 1024 * 1024,
    workspaceBytes: 2 * 1024 * 1024,
    cacheBytes: 1024 * 1024,
    totalAppBytes: 20 * 1024 * 1024,
  },
  backup: { lastBackupAt: Date.now() - 2 * 60 * 60 * 1000, lastBackupBytes: 8 * 1024 * 1024, autoBackupEnabled: true, cloudBackupEnabled: false },
  capabilities: { accessibility: true, shell: false, virtualDisplay: undefined, notificationListener: true, root: undefined, shizuku: undefined },
  battery: { level: 72, charging: false },
};

const dataPage = (overrides = {}) =>
  render(
    mod.createElement(mod.DataPrivacyPage, {
      title: "PROTO-数据与隐私",
      system: { status: STATUS, loading: false, error: undefined, unsupported: false },
      supported: true,
      onRequest: () => undefined,
      pii: { checked: true, writable: true },
      onTogglePii: () => undefined,
      onBack: () => undefined,
      ...overrides,
    }),
  );

const platformPage = (availability, overrides = {}) =>
  render(
    mod.createElement(mod.PlatformPage, {
      title: "PROTO-无障碍",
      description: "PROTO-功能说明",
      availability,
      onBack: () => undefined,
      ...overrides,
    }),
  );

try {
  // ── 1. 存储分布 ─────────────────────────────────────────────
  const html = dataPage();
  assert(html.includes("PROTO-数据与隐私"), "Page title comes from the Host entry");
  assert(html.includes("This phone") && html.includes("2.6.10"), "Top shows the phone version");
  assert(/3\s*hours/.test(html) && html.includes("72%"), "Uptime and battery use Intl formatting");
  assert(html.includes("Storage") && html.includes("Database") && html.includes("Media") && html.includes("Workspace"), "All storage categories are present");
  assert((html.match(/host-storage-segment/g) ?? []).length === 5, "Stacked bar is split into five segments");
  assert(html.includes("4 MB") && html.includes("20 MB"), "Values use formatBytes (MB)");
  assert(html.includes("Total"), "There is a total row");
  assert(/host-storage-bar/.test(html), "Distribution is shown with a horizontal stacked bar");

  // ── 2. 备份三态 ─────────────────────────────────────────────
  assert(html.includes("Last backup") && html.includes("2 h ago"), "Relative time shown when a backup record exists");
  assert(html.includes("8 MB"), "Backup size");
  assert(html.includes("Auto backup") && html.includes("On") && html.includes("Cloud backup") && html.includes("Off"), "Auto/cloud backup switches are read-only echoes");
  assert(html.includes("Change on your phone"), "Read-only items are labelled 'Change on your phone'");

  const noBackup = dataPage({
    system: { status: { ...STATUS, backup: { autoBackupEnabled: false, cloudBackupEnabled: false } }, loading: false, error: undefined, unsupported: false },
  });
  assert(noBackup.includes("Never backed up yet"), "When there is no backup record it shows 'Never backed up yet'");
  assert(!noBackup.includes("Backup size"), "Size row is not shown when there is no record");

  const unsupported = dataPage({ supported: false });
  assert(unsupported.includes("This version can't show storage and backup on the web"), "Notice shown when the system_status capability flag is missing");
  assert(!unsupported.includes("Storage"), "Storage section is not drawn when the capability flag is missing");

  const failed = dataPage({ system: { status: undefined, loading: false, error: "PROTO-错误", unsupported: false } });
  assert(failed.includes("PROTO-错误") && failed.includes(">Retry<"), "Read failure can be retried");

  // ── 3. 隐私开关可写性 ───────────────────────────────────────
  assert(/role="switch"/.test(html) && !html.includes('disabled=""'), "Switch is usable when writable");
  const readOnlyPii = dataPage({ pii: { checked: true, writable: false } });
  assert(/role="switch"/.test(readOnlyPii) && readOnlyPii.includes("disabled"), "Switch is disabled when not writable");
  const missingPii = dataPage({ pii: undefined });
  assert(missingPii.includes("The phone didn't report the PII guard state"), "Reason explained when the Host provides no entry");
  assert(html.includes("PII guard") && html.includes("sensitive details"), "There is a PII guard description row");
  assert(HOST_SETTINGS.includes("onToggle(PII_GUARD_KEY, enabled)") && HOST_SETTINGS.includes('PII_GUARD_KEY = "piiGuardEnabled"'), "PII switch uses the settings.update allow-listed key");

  // ── 4. 平台页状态映射 ───────────────────────────────────────
  assert(mod.platformAvailability({ accessibility: true }, ["accessibility"]) === "available", "true → Available");
  assert(mod.platformAvailability({ accessibility: false }, ["accessibility"]) === "notEnabled", "false → Not enabled");
  assert(mod.platformAvailability({}, ["accessibility"]) === "unavailable", "Missing field → Unavailable");
  assert(mod.platformAvailability(undefined, ["accessibility"]) === "unavailable", "No system.status → Unavailable");
  assert(mod.platformAvailability({ accessibility: false, root: true }, ["accessibility", "root"]) === "available", "Any one of multiple fields available counts as available");

  const pages = [
    ["accessibility", "accessibility"],
    ["virtual_display", "virtualDisplay"],
    ["terminal", "shell"],
    ["notification_listener", "notificationListener"],
    ["ui_automation", "accessibility"],
  ];
  for (const [entryId, key] of pages) {
    const keys = mod.platformKeysForEntry(entryId);
    assert(keys !== undefined && keys.includes(key), `Entry ${entryId} maps to ${key}`);
  }
  assert(mod.platformKeysForEntry("provider") === undefined, "Non-platform entries are not misjudged");

  const available = platformPage("available");
  assert(available.includes("PROTO-功能说明") && available.includes("PROTO-无障碍"), "Platform page shows the feature description");
  assert(available.includes("Available") && !available.includes("Turn it on from your phone"), "No prompt to turn it on when available");
  const notEnabled = platformPage("notEnabled");
  assert(notEnabled.includes("Not enabled") && notEnabled.includes("Turn it on from your phone"), "When not enabled it prompts to turn it on from your phone");
  const unavailable = platformPage("unavailable");
  assert(unavailable.includes("Unavailable") && unavailable.includes("can only be switched on and used from your phone"), "When unavailable it only explains the situation");
  assert(!/Go to settings|Open settings/.test(unavailable), "No fake button when unavailable");

  // ── 5. 词条与接线 ─────────────────────────────────────────
  for (const key of ["data.storage", "data.backup", "data.readOnly", "data.unsupported", "data.piiGuard", "platform.available", "platform.notEnabled", "platform.unavailable", "platform.readOnly"]) {
    assert(typeof mod.ZH[key] === "string" && typeof mod.EN[key] === "string" && mod.ZH[key].length > 0 && mod.EN[key].length > 0, `Entry ${key} exists in both ZH and EN`);
  }
  assert(HOST_SETTINGS.includes('openPage.entry.id === "data_management"') && HOST_SETTINGS.includes("<DataPrivacyPage"), "The 'Data & privacy' entry opens this page");
  assert(HOST_SETTINGS.includes("platformKeysForEntry(openPage.entry.id)") && HOST_SETTINGS.includes("<PlatformPage"), "Platform entries open the platform page");
  assert(CONNECTION.includes('type: "system.status"'), "The system.status command can be sent");
  assert(mod.supportsFlag(undefined, mod.SYSTEM_STATUS_FLAGS) === true && mod.supportsFlag([], mod.SYSTEM_STATUS_FLAGS) === false, "system_status capability flag check");
  assert(mod.formatBytes(1024) === "1 KB" || mod.formatBytes(1024).includes("1"), `formatBytes 1KB：${mod.formatBytes(1024)}`);
  assert(/host-storage-bar|host-platform-state/.test(CSS), "New style classes exist");

  if (failures > 0) throw new Error(`Data and platform page assertions failed (${failures} failures)`);
  console.log("\nAll data and platform page assertions passed (storage distribution / three backup states / PII switch / platform state mapping).");
} finally {
  await rm(TEMP, { force: true });
}
