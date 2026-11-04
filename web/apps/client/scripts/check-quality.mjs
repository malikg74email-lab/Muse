/**
 * 质量与容错的静态断言：断线三态、骨架延迟、区块错误边界、占位页、无障碍、对比度。
 *
 * 与其它脚本同一套路：纯函数直接跑，组件用 renderToStaticMarkup 渲染，
 * 接线类的东西（错误边界、焦点陷阱、aria-label）按源码结构断言 —— 没有浏览器、
 * 也不引 jsdom，能断言的就说清楚，不能断言的留给人工验证。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync, readdirSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".quality-check.mjs");
const TOKENS = JSON.parse(readFileSync(join(ROOT, "src", "design", "tokens.json"), "utf8"));
const CSS = readFileSync(join(ROOT, "src", "styles.css"), "utf8");

const ENTRY = `
export { connectionBanner, composerDisabledHint, MANUAL_RETRY_AFTER } from "./src/host/connectionStatus.ts";
export { isSkeletonDue, SKELETON_DELAY_MS } from "./src/host/useDelayedFlag.ts";
export { trapNextIndex } from "./src/host/ImageLightbox.tsx";
export { HostPlaceholder } from "./src/host/HostPlaceholder.tsx";
export { MessageBubble } from "./src/host/MessageBubble.tsx";
export { readCapabilities } from "./src/host/capabilities.ts";
export { readLanguage } from "./src/host/useLocale.ts";
export { I18nProvider } from "./src/i18n/I18nProvider.tsx";
export { translate } from "./src/i18n/translate.ts";
export { ZH, EN, MESSAGES } from "./src/i18n/messages.ts";
export { resolveLocale, LOCALES, FALLBACK_LOCALE, DEFAULT_LOCALE } from "./src/i18n/locale.ts";
export { formatRelativeTime, formatNumber, formatMonthDay, formatDayLabel } from "./src/i18n/format.ts";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "quality-entry.tsx", loader: "tsx" },
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

// ── WCAG 对比度（自己算，不引工具） ────────────────────────────────
function parseColor(value) {
  const text = String(value).trim();
  const hex = text.match(/^#([0-9a-fA-F]{6})$/);
  if (hex !== null) {
    const n = Number.parseInt(hex[1], 16);
    return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 1 };
  }
  const rgba = text.match(/^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/);
  if (rgba !== null) {
    return { r: Number(rgba[1]), g: Number(rgba[2]), b: Number(rgba[3]), a: rgba[4] === undefined ? 1 : Number(rgba[4]) };
  }
  throw new Error(`Unable to parse color: ${text}`);
}

function over(fg, bg) {
  const alpha = fg.a ?? 1;
  return {
    r: fg.r * alpha + bg.r * (1 - alpha),
    g: fg.g * alpha + bg.g * (1 - alpha),
    b: fg.b * alpha + bg.b * (1 - alpha),
    a: 1,
  };
}

function luminance(color) {
  const channel = (value) => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(color.r) + 0.7152 * channel(color.g) + 0.0722 * channel(color.b);
}

function contrast(fgToken, bgToken) {
  const bg = parseColor(bgToken);
  const fg = over(parseColor(fgToken), bg);
  const l1 = luminance(fg);
  const l2 = luminance(bg);
  const [light, dark] = l1 >= l2 ? [l1, l2] : [l2, l1];
  return (light + 0.05) / (dark + 0.05);
}

const mod = await load();

try {
  // ── 1. 断线三态与输入禁用说明 ───────────────────────────────────
  // 纯逻辑只给「状态 → 词条 key + 参数」，文案由组件按当前语言 resolve；
  // 所以这里断言两件事：映射到的 key 对不对，以及该 key 的 zh 文案还是原来那句。
  const zh = (key, params) => mod.translate("zh", key, params);
  const offline = mod.connectionBanner({ online: false, status: "reconnecting", attempt: 2, everConnected: true });
  assert(offline.visible && offline.textKey === "connection.offline", `Offline uses the connection.offline entry: ${offline.textKey}`);
  assert(zh(offline.textKey) === "No network connection", `Offline copy resolves to "No network connection": ${zh(offline.textKey)}`);
  assert(!offline.retry, "Offline offers no manual retry (retrying cannot connect either)");

  const online1 = mod.connectionBanner({ online: true, status: "connected", attempt: 0, everConnected: true });
  assert(!online1.visible, "The disconnect banner disappears once connected");

  const first = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 0, everConnected: false });
  assert(first.visible && first.textKey === "connection.connecting", `First connection uses connection.connecting: ${first.textKey}`);
  assert(zh(first.textKey) === "Connecting to your phone…", `First-connection copy: ${zh(first.textKey)}`);
  assert(!first.retry, "No manual retry for the first 2 reconnect attempts");

  const tries2 = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 2, everConnected: true });
  assert(tries2.textKey === "connection.reconnecting", `Reconnecting uses connection.reconnecting: ${tries2.textKey}`);
  assert(zh(tries2.textKey, tries2.params) === "Reconnecting (attempt 2)", `Reconnect copy includes the attempt count: ${zh(tries2.textKey, tries2.params)}`);
  assert(!tries2.retry, "Attempt 2 still offers no manual retry");

  const tries3 = mod.connectionBanner({ online: true, status: "reconnecting", attempt: 3, everConnected: true });
  assert(zh(tries3.textKey, tries3.params) === "Reconnecting (attempt 3)" && tries3.retry, "Manual retry appears at attempt 3");

  const closed = mod.connectionBanner({ online: true, status: "closed", attempt: 3, everConnected: true });
  assert(closed.textKey === "connection.disconnected", `Disconnected uses connection.disconnected: ${closed.textKey}`);
  assert(zh(closed.textKey) === "Disconnected from Muse on your phone" && closed.retry, `Disconnected copy: ${zh(closed.textKey)}`);
  assert(mod.MANUAL_RETRY_AFTER === 3, "The manual retry threshold is 3 attempts");

  const hintText = (key) => (key === undefined ? "" : zh(key));
  const hintOffline = mod.composerDisabledHint({ online: false, status: "connected" });
  assert(hintOffline === "composer.hintOffline", `Offline composer hint uses composer.hintOffline: ${hintOffline}`);
  assert(hintText(hintOffline).includes("You're offline"), `Offline composer hint is "You're offline": ${hintText(hintOffline)}`);
  const hintReconnecting = mod.composerDisabledHint({ online: true, status: "reconnecting" });
  assert(hintReconnecting === "composer.hintReconnecting", `Reconnecting composer hint uses composer.hintReconnecting: ${hintReconnecting}`);
  assert(hintText(hintReconnecting).includes("Reconnecting"), `Reconnecting composer hint is "Reconnecting": ${hintText(hintReconnecting)}`);
  const hintClosed = mod.composerDisabledHint({ online: true, status: "closed" });
  assert(hintClosed === "composer.hintClosed", `Disconnected composer hint uses composer.hintClosed: ${hintClosed}`);
  assert(hintText(hintClosed).includes("Disconnected"), `Disconnected composer hint is "Disconnected": ${hintText(hintClosed)}`);
  assert(mod.composerDisabledHint({ online: true, status: "connected" }) === undefined, "The composer is usable when the connection is healthy");

  // ── 2. 骨架延迟 300ms ──────────────────────────────────────────
  assert(mod.SKELETON_DELAY_MS === 300, "The skeleton delay constant is 300ms");
  assert(!mod.isSkeletonDue(false, 10_000), "No skeleton when not loading");
  assert(!mod.isSkeletonDue(true, 120), "No skeleton within 120ms (avoids a flash)");
  assert(mod.isSkeletonDue(true, 300), "The skeleton appears only at 300ms");
  for (const file of ["HostChat.tsx", "HostSettings.tsx", "SettingsValuePage.tsx", "SessionDrawer.tsx"]) {
    const source = readFileSync(join(ROOT, "src", "host", file), "utf8");
    assert(source.includes("useDelayedFlag("), `${file} skeleton uses the useDelayedFlag delay`);
  }
  assert(/\.host-loading-quiet\b/.test(CSS), "During the delay a quiet placeholder is shown (not blank, and not a skeleton)");

  // ── 3. 区块错误边界 ────────────────────────────────────────────
  const boundary = readFileSync(join(ROOT, "src", "host", "BlockBoundary.tsx"), "utf8");
  assert(boundary.includes("getDerivedStateFromError") && boundary.includes("componentDidCatch"), "The block boundary implements both error hooks");
  assert(boundary.includes("重新加载这一块"), "The block boundary offers \"reload this section\"");
  assert(/<Fragment key=\{this\.state\.nonce\}>/.test(boundary), "Reloading = remounting the block under a new key");
  assert(/role="alert"/.test(boundary), "Block errors have alert semantics");
  const chat = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  // 区块名字也是词条：断言「哪一块包了边界」时看的是它取的 key，再校对 key 的文案没变。
  for (const [key, copy] of [["block.messages", "The message list"], ["block.sessions", "The session list"], ["block.settings", "The settings page"]]) {
    assert(mod.ZH[key] === copy, `Dictionary entry ${key} is "${copy}"`);
    const wrapped = new RegExp(`<BlockBoundary name=\\{t\\("${key.replace(/\./g, "\\.")}"\\)\\}`);
    assert(wrapped.test(chat), `"${copy}" is wrapped in its own block boundary (name comes from the dictionary)`);
  }
  assert(chat.includes("<MessageErrorBoundary"), "Per-message isolation still exists (the block boundary does not replace it)");

  // 未知 role / 未知附件形态：一行中性展示，不抛异常
  const full = mod.readCapabilities(["media", "tool_call_details", "message_status", "message_write", "message_multi_select"]);
  const renderMessage = (overrides) =>
    mod.renderToStaticMarkup(
      mod.createElement(mod.MessageBubble, {
        message: { id: "m1", role: "assistant", content: "Main text", createdAt: Date.now(), ...overrides },
        streaming: false,
        showActions: false,
        onRegenerate: () => undefined,
        onContinue: () => undefined,
        capabilities: full,
      }),
    );
  const unknownRole = renderMessage({ role: "moderator" });
  assert(unknownRole.includes("host-msg-unknown") && unknownRole.includes("Unknown message type"), "An unknown role degrades to one neutral line");
  assert(unknownRole.includes("Main text"), "An unknown role still shows the body text (content is not lost)");
  assert(renderMessage({ imageUrls: ["weird://x"] }).includes("This attachment can't be opened here"), "An unknown attachment form gets a neutral notice");
  assert(renderMessage({ imageUrls: ["file:///data/user/0/x.bin"] }).includes("This image is on your phone"), "Legacy local paths get the corresponding notice");

  // ── 4. 统一占位页 ─────────────────────────────────────────────
  const placeholder = mod.renderToStaticMarkup(
    mod.createElement(mod.HostPlaceholder, { title: "PROTO-无障碍", description: "PROTO-这个功能是做什么的", onBack: () => undefined }),
  );
  assert(placeholder.includes("This needs to be done on your phone"), "The placeholder page explains that the phone is required");
  assert(placeholder.includes("PROTO-这个功能是做什么的"), "The placeholder page uses the Host-supplied description to explain the feature");
  assert(placeholder.includes(">Back<"), "The placeholder page has a back button");
  assert(!/muse-capsule[^"]*is-primary/.test(placeholder), "The placeholder page has no primary action (no pretending there is something to do)");
  const placeholderNoDesc = mod.renderToStaticMarkup(
    mod.createElement(mod.HostPlaceholder, { title: "PROTO-终端", onBack: () => undefined }),
  );
  assert(placeholderNoDesc.includes("This feature is currently only available in Muse on your phone"), "Without a description it shows a neutral line, not a blank");
  const settingsSource = readFileSync(join(ROOT, "src", "host", "HostSettings.tsx"), "utf8");
  assert(
    settingsSource.includes("<HostPlaceholder") && !settingsSource.includes("这个页面还在迁移中"),
    "Unimplemented settings subpages and platform pages all go through HostPlaceholder",
  );

  // ── 5. 无障碍：图标按钮的 aria-label ───────────────────────────
  const iconClasses = ["host-icon-button", "host-lightbox-close", "host-select", "host-session-more", "host-sidebar-collapse", "host-quote-draft-close", "host-sidebar-toggle"];
  // 按类名 token 边界匹配：host-select 不能把 host-selection-all 也算成图标按钮。
  const hasIconClass = (tag) => iconClasses.some((cls) => new RegExp(`(^|[\\s"'])${cls}([\\s"']|$)`).test(tag));
  const hostDir = join(ROOT, "src", "host");
  const offenders = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx"))) {
    const source = readFileSync(join(hostDir, file), "utf8");
    for (const match of source.matchAll(/<button\b[\s\S]{0,400}?>/g)) {
      const tag = match[0];
      if (!hasIconClass(tag)) continue;
      if (!tag.includes("aria-label")) offenders.push(`${file}: ${tag.split("\n")[0]}`);
    }
  }
  assert(offenders.length === 0, `All icon buttons have an aria-label${offenders.length === 0 ? "" : `(missing: ${offenders.join(" / ")})`}`);
  assert(
    chat.includes('aria-label={t("chat.exitSelection")}') && chat.includes('aria-label={t("chat.cancelQuote")}'),
    "Icon buttons on the selection and quote bars have aria-labels (from the dictionary)",
  );
  assert(
    mod.ZH["chat.exitSelection"] === "Exit selection" && mod.ZH["chat.cancelQuote"] === "Cancel quote",
    "The exit-selection / cancel-quote dictionary copy is still present",
  );

  // ── 6. 焦点陷阱与 Esc ─────────────────────────────────────────
  assert(mod.trapNextIndex(3, 2, false) === 0, "Tab on the last control wraps to the first");
  assert(mod.trapNextIndex(3, 0, true) === 2, "Shift+Tab on the first control wraps to the last");
  assert(mod.trapNextIndex(3, 1, false) === 2, "A middle position advances in natural order");
  assert(mod.trapNextIndex(0, -1, false) === -1, "Nothing happens when there are no focusable controls");
  const lightbox = readFileSync(join(ROOT, "src", "host", "ImageLightbox.tsx"), "utf8");
  assert(lightbox.includes('event.key === "Escape"'), "The dialog closes with Esc");
  assert(/previous\?\.focus\(\)/.test(lightbox), "Focus returns to the original element after closing");
  assert(/closeRef\.current\?\.focus\(\)/.test(lightbox) && lightbox.includes("querySelectorAll"), "Opening moves focus into the dialog and traps Tab");
  assert(/aria-modal="true"/.test(lightbox) && /role="dialog"/.test(lightbox), "Dialog semantics are complete");
  assert(/\.host-scope :focus-visible/.test(CSS) && /outline: var\(--muse-icons-progress-stroke\) solid var\(--accent\)/.test(CSS), "Keyboard focus uses a primary-color outline (no outline: none)");

  // ── 7. 对比度：正文与次要文字在两种主题下都过 AA ────────────────
  assert(/--ink-muted:\s*var\(--ui-label-ink\)/.test(CSS), "The secondary text alias uses onSurface@80% (no longer the mid-gray outline)");
  for (const mode of ["light", "dark"]) {
    const scheme = TOKENS.colors.scheme[mode];
    const pairs = [
      ["Body ink", scheme.onSurface, scheme.surface],
      ["Body ink (on card variant background)", scheme.onSurface, scheme.surfaceVariant],
      ["Secondary ink-soft", scheme.onSurfaceVariant, scheme.surface],
      ["Secondary ink-muted", scheme.onSurfaceVeil80, scheme.surface],
      ["Secondary ink-muted (on card variant background)", scheme.onSurfaceVeil80, scheme.surfaceVariant],
    ];
    for (const [label, fg, bg] of pairs) {
      const ratio = contrast(fg, bg);
      assert(ratio >= 4.5, `${mode} · ${label} contrast ${ratio.toFixed(2)}:1 ≥ 4.5`);
    }
  }

  // ── 8. 语言解析 / 词条完整性 / 时间与数字的本地化 ────────────────
  // 语言值来自 settings.snapshot.values 里含 language 的那一项；Host 没下发时退回 display.language。
  const settingsWith = (values) => ({
    snapshot: {
      groups: [],
      toggles: [],
      writableKeys: [],
      display: { language: "en", themeMode: "system", fontSizeScale: "1", themeId: "default" },
      ...(values === undefined ? {} : { values }),
    },
    loading: false,
  });
  const languageValue = (key, value) => [{ key, type: "enum", value }];
  assert(mod.readLanguage(settingsWith(languageValue("language", "ja"))) === "ja", "The language comes from the language entry in settings.snapshot.values");
  assert(mod.readLanguage(settingsWith(languageValue("display.language", "ko"))) === "ko", "A prefixed key is still recognized (normalizeSettingKey normalizes it)");
  assert(mod.readLanguage(settingsWith(undefined)) === "en", "When the Host sends no values, it falls back to display.language");
  assert(mod.readLanguage({ loading: true }) === undefined, "No language value before the settings arrive");

  assert(mod.resolveLocale("ja", ["zh-CN"]) === "ja", "A language set in settings wins over the browser");
  assert(mod.resolveLocale("system", ["ja-JP", "en-US"]) === "ja", "system matches the browser language list entry by entry");
  assert(mod.resolveLocale(undefined, ["en-GB"]) === "en", "Without a language value, the browser language is used");
  assert(mod.FALLBACK_LOCALE === "en" && mod.resolveLocale("system", []) === "en", "An unrecognized language falls back to en");
  assert(mod.resolveLocale("fr", ["fr-FR"]) === "en", "An unrecognized language value from the Host also falls back to en");
  assert(mod.LOCALES.join(",") === "zh,en,ja,ko,ru", "Supports the five locales zh / en / ja / ko / ru");

  const zhKeys = Object.keys(mod.ZH);
  const enKeys = Object.keys(mod.EN);
  assert(zhKeys.length > 0 && enKeys.length === zhKeys.length && zhKeys.every((key) => enKeys.includes(key)), `ZH / EN entries correspond one-to-one (${zhKeys.length} entries)`);
  assert(zhKeys.every((key) => mod.ZH[key].length > 0 && (mod.EN[key] ?? "").length > 0), "No empty entries");
  const slots = (text) => (text.match(/\{\w+\}/g) ?? []).sort().join(",");
  assert(zhKeys.every((key) => slots(mod.ZH[key]) === slots(mod.EN[key] ?? "")), "ZH / EN placeholders match");
  // ja / ko / ru 都是全量词条，各自用自己的；只有单条缺失才回落 en。
  for (const locale of ["ja", "ko", "ru"]) {
    const dict = mod.MESSAGES[locale] ?? {};
    assert(
      zhKeys.every((key) => typeof dict[key] === "string" && dict[key].length > 0),
      `${locale} has the same key set as zh and no empty entries`,
    );
    assert(zhKeys.every((key) => slots(dict[key] ?? "") === slots(mod.ZH[key])), `${locale} placeholders match zh`);
    assert(mod.translate(locale, "connection.offline") === dict["connection.offline"], `${locale} uses its own entries instead of en`);
    assert(dict["connection.offline"] !== mod.EN["connection.offline"], `${locale} "no network" entry is genuinely translated`);
  }
  assert(mod.translate("en", "connection.reconnecting", { attempt: 4 }).includes("4"), "Parameters are also substituted in en");

  // 语言值一变就换文案：同一棵树套不同 locale，渲染结果跟着变，不需要刷新。
  const placeholderFor = (locale) =>
    mod.renderToStaticMarkup(
      mod.createElement(mod.I18nProvider, { locale }, mod.createElement(mod.HostPlaceholder, { title: "PROTO-T", onBack: () => undefined })),
    );
  assert(placeholderFor("zh").includes("This needs to be done on your phone"), "zh renders the (English) dictionary copy");
  assert(placeholderFor("en").includes("This needs to be done on your phone"), "en renders English");
  assert(placeholderFor("ja").includes("この機能はスマートフォンでの操作が必要です"), "ja renders Japanese");
  assert(placeholderFor("ru").includes("Для этой функции нужен телефон"), "ru renders Russian");
  assert(!placeholderFor("ru").includes("settings.placeholderTitle"), "A missing entry never exposes the key name");
  assert(chat.includes("<I18nProvider locale={locale}>") && chat.includes("useHostLocale(settings)"), "The chat shell applies the resolved language to the whole tree");
  assert(chat.includes("t(bannerModel.textKey, bannerModel.params)"), "The disconnect banner resolves its entry in the current language");

  // formatRelativeTime(date, now, locale)：纯函数，zh / en 每个分支都过一遍。
  const now = new Date(2026, 9, 31, 12, 0, 0).getTime(); // 本地时区，避开时区把「昨天」挪走
  const ago = (minutes) => now - minutes * 60_000;
  const rel = (minutes, locale) => mod.formatRelativeTime(ago(minutes), now, locale);
  assert(rel(0.1, "zh") === "Just now" && rel(0.1, "en") === "Just now", "Just-now branch, zh / en");
  assert(rel(5, "zh") === "5 min ago" && rel(5, "en") === "5 min ago", "Minutes branch, zh / en");
  assert(rel(180, "zh") === "3 h ago" && rel(180, "en") === "3 h ago", "Hours branch, zh / en");
  assert(rel(26 * 60, "zh") === "Yesterday" && rel(26 * 60, "en") === "Yesterday", "Yesterday branch, zh / en");
  assert(rel(3 * 24 * 60, "zh") === "3 days ago" && rel(3 * 24 * 60, "en") === "3 days ago", "Days branch, zh / en");
  assert(rel(30 * 24 * 60, "zh") !== rel(30 * 24 * 60, "en"), "Over a week shows the date, formatted per language");
  assert(mod.formatRelativeTime(undefined, now, "zh") === "", "A missing timestamp returns an empty string; the caller supplies the placeholder");

  // 日期 / 数字走 Intl：同一时刻在 zh / en 下格式不同，不是自己拼的字符串。
  assert(/[年月]/.test(mod.formatMonthDay(now, "zh")), `zh month/day uses Intl: ${mod.formatMonthDay(now, "zh")}`);
  assert(/[A-Za-z]/.test(mod.formatMonthDay(now, "en")), `en month/day uses Intl: ${mod.formatMonthDay(now, "en")}`);
  assert(mod.formatDayLabel(now, now, "zh").startsWith("Today"), "The same-day separator carries \"Today\"");
  assert(mod.formatNumber(1234567, "en") === "1,234,567", `en thousands grouping: ${mod.formatNumber(1234567, "en")}`);
  assert(mod.formatNumber(1234567, "ru") !== mod.formatNumber(1234567, "en"), "Grouping separators differ by language (ru uses a non-breaking space)");
  assert(chat.includes('t("chat.selectedCount", { count: formatNumber(selectedIds.length, locale) })'), "The selected count uses Intl number formatting");

  // ── 9. 硬编码扫描：src/host/*.tsx 里不该有没走词条的可见中文 ──────
  // 扫描器跳过注释（含 JSX 注释里的中文）；白名单是「承认的例外」，每条都写清理由。
  const WHITELIST = [
    {
      file: "SessionDrawer.tsx",
      line: '<span className="host-session-preview">{previews[session.id] ?? "　"}</span>',
      reason: "Fullwidth-space placeholder for an empty session preview: only holds the line height and is not read by screen readers",
    },
    {
      file: "SettingsValuePage.tsx",
      line: "＋",
      reason: "Fullwidth plus glyph on the number stepper button: the symbol itself is not translated and pairs with the minus sign",
    },
  ];
  const CJK = /[\u3400-\u9fff\u3000-\u303f\uff00-\uffef]/;
  /** 逐字符走一遍，只在「代码与字符串」里认中文：注释（含 JSX 注释）里的中文不算。 */
  const cjkHits = (source) => {
    const hits = [];
    const record = (index, lineNo) => {
      const start = source.lastIndexOf("\n", index) + 1;
      const end = source.indexOf("\n", index);
      hits.push({ lineNo, line: source.slice(start, end === -1 ? source.length : end).trim() });
    };
    let state = "code";
    let quote = "";
    let lineNo = 1;
    for (let index = 0; index < source.length; index += 1) {
      const ch = source[index];
      const next = source[index + 1];
      if (ch === "\n") lineNo += 1;
      if (state === "line") {
        if (ch === "\n") state = "code";
        continue;
      }
      if (state === "block") {
        if (ch === "*" && next === "/") {
          state = "code";
          index += 1;
        }
        continue;
      }
      if (state === "string") {
        if (ch === "\\") {
          index += 1;
          continue;
        }
        if (ch === quote) {
          state = "code";
          continue;
        }
        if (CJK.test(ch)) record(index, lineNo);
        continue;
      }
      if (ch === "/" && next === "/") {
        state = "line";
        index += 1;
        continue;
      }
      if (ch === "/" && next === "*") {
        state = "block";
        index += 1;
        continue;
      }
      if (ch === '"' || ch === "'" || ch === "`") {
        state = "string";
        quote = ch;
        continue;
      }
      if (CJK.test(ch)) record(index, lineNo);
    }
    return hits;
  };
  const allowed = new Set();
  const leftovers = [];
  for (const file of readdirSync(hostDir).filter((name) => name.endsWith(".tsx")).sort()) {
    for (const hit of cjkHits(readFileSync(join(hostDir, file), "utf8"))) {
      const match = WHITELIST.findIndex((entry) => entry.file === file && entry.line === hit.line);
      if (match >= 0) {
        allowed.add(match);
        continue;
      }
      leftovers.push(`${file}:${hit.lineNo}`);
    }
  }
  assert(leftovers.length === 0, `host/*.tsx has no visible Chinese that bypasses the dictionary${leftovers.length === 0 ? "" : `（${leftovers.join(" / ")}）`}`);
  assert(allowed.size === WHITELIST.length, `Whitelist has no stale entries (matched ${allowed.size}/${WHITELIST.length})`);
  for (const entry of WHITELIST) assert(entry.reason.length > 0, `Whitelist entry states its reason: ${entry.file}`);

  if (failures > 0) throw new Error(`Quality assertions failed (${failures} failures)`);
  console.log("\nAll quality assertions passed (disconnect states / skeleton delay / block boundaries / placeholder page / accessibility / contrast / language and dictionary).");
} finally {
  await rm(TEMP, { force: true });
}
