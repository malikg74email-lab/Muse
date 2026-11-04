/**
 * i18n 断言：词条完整度、逐条回落、语言解析、相对时间、以及「源码里没有硬编码中文」。
 *
 * 最后一项是扫描 `src/host/*.tsx`：去掉注释后仍然出现 CJK，就说明有文案没走 t()。
 * Host 下发的文案（设置条目、错误原因）本来就只在运行时出现，不会写进源码。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync, readdirSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".i18n-check.mjs");

const ENTRY = `
export { MESSAGES, ZH, EN, JA, KO, RU, type MessageKey } from "./src/i18n/messages.ts";
export { formatMessage, translate, createTranslator, resolveTemplate } from "./src/i18n/translate.ts";
export { resolveLocale, matchLocale, isLocale, DEFAULT_LOCALE, LOCALES } from "./src/i18n/locale.ts";
export { formatRelativeTime, formatDayLabel, formatSessionTime, formatNumber } from "./src/i18n/format.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "i18n-entry.ts", loader: "ts" },
    bundle: true,
    format: "esm",
    platform: "node",
    logLevel: "warning",
    outfile: TEMP,
  });
  return import(`${pathToFileURL(TEMP).href}?t=${Date.now()}`);
}

const mod = await load();

/** 去掉块注释与行注释（保留换行，便于报行号）。 */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, " "))
    .replace(/(^|[^:])\/\/[^\n]*/g, (match, prefix) => prefix + " ".repeat(match.length - prefix.length));
}

try {
  // ── 1. 词条表完整性 ─────────────────────────────────────────
  const zhKeys = Object.keys(mod.ZH).sort();
  const enKeys = Object.keys(mod.EN).sort();
  assert(zhKeys.length > 100, `ZH entries: ${zhKeys.length} total`);
  assert(JSON.stringify(zhKeys) === JSON.stringify(enKeys), "ZH and EN dictionary keys align exactly");
  const emptyZh = zhKeys.filter((key) => mod.ZH[key].trim().length === 0);
  const emptyEn = enKeys.filter((key) => mod.EN[key].trim().length === 0);
  assert(emptyZh.length === 0 && emptyEn.length === 0, "No empty entries");
  const untranslated = enKeys.filter((key) => mod.EN[key] === mod.ZH[key] && /[\u4e00-\u9fff]/.test(mod.EN[key]));
  assert(untranslated.length === 0, `No Chinese remains in the EN entries${untranslated.length === 0 ? "" : `：${untranslated.join(",")}`}`);

  // ── 2. ja / ko / ru：键集与 zh 完全一致、无空串、真的翻译了 ──
  const DICTS = { ja: mod.JA, ko: mod.KO, ru: mod.RU };
  for (const [locale, dict] of Object.entries(DICTS)) {
    const keys = Object.keys(dict).sort();
    const missing = zhKeys.filter((key) => !(key in dict));
    const extra = keys.filter((key) => !(key in mod.ZH));
    assert(
      missing.length === 0 && extra.length === 0 && keys.length === zhKeys.length,
      `${locale} key set matches zh exactly (${keys.length} entries${missing.length === 0 ? "" : `, missing: ${missing.join(",")}`}${extra.length === 0 ? "" : `, extra: ${extra.join(",")}`})`,
    );
    const empty = keys.filter((key) => dict[key].trim().length === 0);
    assert(empty.length === 0, `${locale} has no empty entries${empty.length === 0 ? "" : `：${empty.join(",")}`}`);
    // 占位符不能丢也不能多：{count} 这类必须原样保留。
    const slots = (text) => (text.match(/\{\w+\}/g) ?? []).sort().join(",");
    const badSlots = zhKeys.filter((key) => slots(dict[key]) !== slots(mod.ZH[key]));
    assert(badSlots.length === 0, `${locale} placeholders match zh${badSlots.length === 0 ? "" : `：${badSlots.join(",")}`}`);
  }

  // 抽查若干条：确实换成了目标语言，而不是把英文原样抄了一份。
  const SPOT_CHECK = [
    "common.cancel",
    "common.retry",
    "composer.send",
    "sessions.rename",
    "settings.title",
    "memory.search",
    "approval.approve",
    "time.justNow",
    "login.submit",
    "common.loadMore",
  ];
  for (const [locale, dict] of Object.entries(DICTS)) {
    const sameAsEn = SPOT_CHECK.filter((key) => dict[key] === mod.EN[key]);
    assert(
      sameAsEn.length === 0,
      `${locale} spot-checked ${SPOT_CHECK.length} entries, none equal to the EN text${sameAsEn.length === 0 ? "" : `：${sameAsEn.join(",")}`}`,
    );
    // 抽查的每一条都直接用自己的词条，不会被 en 顶掉。
    const notOwn = SPOT_CHECK.filter((key) => mod.translate(locale, key) !== dict[key]);
    assert(notOwn.length === 0, `${locale} uses its own translated entries first${notOwn.length === 0 ? "" : `：${notOwn.join(",")}`}`);
  }

  // 回落只在「某一条缺失」时发生，而且只回落那一条，不整本词典换掉。
  const partialJa = { ...mod.JA };
  delete partialJa["common.close"];
  const partialDicts = { ...mod.MESSAGES, ja: partialJa };
  assert(
    mod.resolveTemplate("ja", "common.close", partialDicts) === mod.EN["common.close"],
    "When ja is missing one entry, that entry falls back to English",
  );
  assert(
    mod.resolveTemplate("ja", "common.cancel", partialDicts) === mod.JA["common.cancel"],
    "When ja is missing one entry, the rest still use ja's own entries",
  );
  assert(mod.translate("ja", "common.cancel") === mod.JA["common.cancel"], "translate uses ja's own entries");
  assert(mod.translate("ko", "settings.title") === mod.KO["settings.title"], "translate uses ko's own entries");
  assert(mod.translate("ru", "composer.send") === mod.RU["composer.send"], "translate uses ru's own entries");
  for (const locale of mod.LOCALES) {
    const text = mod.translate(locale, "chat.jumpLatest");
    assert(text.length > 0 && text !== "chat.jumpLatest", `${locale} never exposes the key name`);
  }

  // ── 3. 占位符插值 ───────────────────────────────────────────
  assert(mod.formatMessage("Item {count}", { count: 3 }) === "Item 3", "Placeholder is replaced");
  assert(mod.formatMessage("Placeholder without a parameter {x}", {}) === "Placeholder without a parameter {x}", "Placeholder is left as-is when the parameter is missing");
  assert(mod.translate("zh", "chat.selectedCount", { count: 2 }) === "2 selected", "Dictionary entry + interpolation");
  assert(mod.translate("en", "chat.selectedCount", { count: 2 }) === "2 selected", "English interpolation");

  // ── 4. 语言解析 ─────────────────────────────────────────────
  assert(mod.resolveLocale("en", ["zh-CN"]) === "en", "An explicit setting takes priority");
  assert(mod.resolveLocale("system", ["ja-JP", "en"]) === "ja", "system uses the first recognizable entry in the browser language list");
  assert(mod.resolveLocale("system", ["fr-FR"]) === "en", "Falls back to en when nothing matches");
  assert(mod.resolveLocale(undefined, []) === mod.DEFAULT_LOCALE, "Falls back to the default when no language information is available");
  assert(mod.resolveLocale("system", ["en-US"]) === "en", "A regional tag resolves to its primary language");
  assert(mod.matchLocale("ko_KR") === "ko" && mod.matchLocale("xx") === undefined, "matchLocale handles underscores and unknown languages");
  assert(mod.resolveLocale("nonsense", ["en-GB"]) === "en", "An unrecognized value is treated as system");

  // ── 5. 相对时间（zh / en 各分支）────────────────────────────
  const now = new Date(2026, 9, 30, 15, 0, 0).getTime();
  const at = (minutesAgo) => now - minutesAgo * 60_000;
  assert(mod.formatRelativeTime(at(0.2), now, "zh") === "Just now", "zh: under a minute is \"Just now\"");
  assert(mod.formatRelativeTime(at(0.2), now, "en") === "Just now", "en: under a minute is Just now");
  assert(mod.formatRelativeTime(at(12), now, "zh") === "12 min ago", "zh: minutes ago");
  assert(mod.formatRelativeTime(at(12), now, "en") === "12 min ago", "en: minutes ago");
  assert(mod.formatRelativeTime(at(180), now, "zh") === "3 h ago", "zh: hours ago");
  assert(mod.formatRelativeTime(at(180), now, "en") === "3 h ago", "en: hours ago");
  const yesterday = new Date(2026, 9, 29, 20, 0, 0).getTime();
  assert(mod.formatRelativeTime(yesterday, now, "zh") === "Yesterday", "zh: yesterday");
  assert(mod.formatRelativeTime(yesterday, now, "en") === "Yesterday", "en: yesterday");
  assert(mod.formatRelativeTime(at(3 * 24 * 60), now, "zh") === "3 days ago", "zh: days ago");
  assert(mod.formatRelativeTime(at(3 * 24 * 60), now, "en") === "3 days ago", "en: days ago");
  const longAgo = new Date(2026, 5, 1, 9, 0, 0).getTime();
  assert(/6月1日/.test(mod.formatRelativeTime(longAgo, now, "zh")), "zh: over a week shows the date");
  assert(/Jun/.test(mod.formatRelativeTime(longAgo, now, "en")), "en: over a week shows the date (Intl abbreviation)");
  assert(mod.formatRelativeTime(undefined, now, "zh") === "", "A missing timestamp returns an empty string");

  // ── 6. 日期/数字走 Intl ─────────────────────────────────────
  const sample = new Date(2026, 9, 28, 14, 25, 0).getTime();
  assert(mod.formatNumber(1234.5, "en", { maximumFractionDigits: 1 }) === "1,234.5", "Number localization (en grouping)");
  assert(mod.formatDayLabel(sample, now, "zh").startsWith("10月28日"), "Day separator date (zh)");
  assert(/Oct 28/.test(mod.formatDayLabel(sample, now, "en")), "Day separator date (en)");
  assert(mod.formatSessionTime(sample, "zh", now) === "10月28日", "Session time (zh)");

  // ── 7. 源码里没有硬编码中文 ─────────────────────────────────
  const hostDir = join(ROOT, "src", "host");
  const offenders = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx"))) {
    const source = stripComments(readFileSync(join(hostDir, file), "utf8"));
    const lines = source.split(/\r?\n/);
    lines.forEach((line, index) => {
      if (/[\u4e00-\u9fff]/.test(line)) offenders.push(`${file}:${index + 1} ${line.trim().slice(0, 60)}`);
    });
  }
  assert(offenders.length === 0, `No hardcoded Chinese in src/host/*.tsx${offenders.length === 0 ? "" : `：\n  ${offenders.join("\n  ")}`}`);

  if (failures > 0) throw new Error(`i18n assertions failed (${failures} failures)`);
  console.log("\nAll i18n assertions passed (dictionary alignment / per-entry fallback / interpolation / locale resolution / relative time / no hardcoded Chinese).");
} finally {
  await rm(TEMP, { force: true });
}
