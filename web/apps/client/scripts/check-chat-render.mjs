/**
 * 聊天页细节的渲染断言：时间分隔、折叠块、复制、逐条错误隔离，
 * 以及 Host 新补协议后的图片 / 语音 / 消息状态 / 重试 / 能力协商。
 *
 * 断言的都是「协议字段 → 界面」这一层，包含老 Host（字段缺失）的降级路径：
 * 缺 capabilityFlags 时按老行为兜底，显式缺某一位时才关闭对应界面。
 *
 * 用法：npm --workspace @muse/client run test
 * 机制与 check-settings-render.mjs 相同：vite 自带的 esbuild 打成临时 ESM 再 import，
 * 不新增依赖，临时文件在 finally 里删掉。
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".chat-render-check.mjs");

const ENTRY = `
export { buildStreamItems, toolStatusOf } from "./src/host/chatStream.ts";
export { formatDaySeparator, dayKey, formatToolDuration, formatVoiceDuration } from "./src/host/format.ts";
export { resolveMessageImages, resolveVoiceUrl } from "./src/host/messageMedia.ts";
export { readCapabilities } from "./src/host/capabilities.ts";
export { actionsForMessage, deleteConfirmKey, quoteExcerpt, selectionCopyText } from "./src/host/messageActions.ts";
export { MessageBubble } from "./src/host/MessageBubble.tsx";
export { MessageImages } from "./src/host/MessageImages.tsx";
export { ToolCallBlocks } from "./src/host/ToolCallBlocks.tsx";
export { VoiceBar } from "./src/host/VoiceBar.tsx";
export { ImageLightbox } from "./src/host/ImageLightbox.tsx";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "chat-render-entry.tsx", loader: "tsx" },
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
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** 固定「现在」，避免跑测时刻跨分钟让断言飘。 */
const NOW = new Date(2026, 9, 30, 14, 30, 0).getTime();

const message = (id, role, extra = {}) => ({
  id,
  role,
  content: "",
  createdAt: NOW,
  ...extra,
});

/** 老 Host：未下发 capabilityFlags。 */
const LEGACY = mod.readCapabilities(undefined);
/** 新 Host：能力位齐全。 */
const FULL = mod.readCapabilities([
  "chat",
  "streaming",
  "media",
  "tool_call_details",
  "message_status",
  "settings_values",
  "message_write",
  "message_multi_select",
]);

const render = (element) => mod.renderToStaticMarkup(element);
const bubble = (overrides) =>
  mod.createElement(mod.MessageBubble, {
    streaming: false,
    showActions: false,
    onRegenerate: () => undefined,
    onContinue: () => undefined,
    capabilities: LEGACY,
    ...overrides,
  });

try {
  // ── 1. 时间分隔：按天插，同分钟不重复打断 ─────────────────────────
  const sameDay = [
    message("u1", "user", { createdAt: NOW - 5 * MINUTE, content: "Morning" }),
    message("a1", "assistant", { createdAt: NOW - 4 * MINUTE, content: "Hi" }),
    message("u2", "user", { createdAt: NOW - 3 * MINUTE, content: "Are you there?" }),
  ];
  const items = mod.buildStreamItems(sameDay, NOW);
  const dividers = items.filter((item) => item.kind === "divider");
  assert(dividers.length === 1, "Consecutive messages on the same day have only one time divider (the one at the top)");
  assert(dividers[0]?.label.startsWith("Today "), `Today's divider uses "Today HH:mm": ${String(dividers[0]?.label)}`);
  assert(items.filter((item) => item.kind === "message").length === 3, "All three messages enter the list");

  const crossed = [
    message("d1", "user", { createdAt: NOW - 2 * DAY }),
    message("d2", "user", { createdAt: NOW - DAY }),
    message("d3", "user", { createdAt: NOW }),
  ];
  const crossedItems = mod.buildStreamItems(crossed, NOW);
  const crossedLabels = crossedItems.filter((item) => item.kind === "divider").map((item) => item.label);
  assert(crossedLabels.length === 3, `One divider is inserted for each day crossed: ${crossedLabels.join(" | ")}`);
  assert(crossedLabels[1]?.startsWith("Yesterday "), `Yesterday uses the relative style: ${String(crossedLabels[1])}`);
  assert(/^Oct 28 \d{2}:\d{2}$/.test(String(crossedLabels[0])), `Older ones use the existing style with a date: ${String(crossedLabels[0])}`);

  // 同一分钟内的连续消息（只隔几十秒）不能被分隔条反复打断
  const sameMinute = [40, 20, 5].map((secondsAgo, index) =>
    message(`s${index}`, index % 2 === 0 ? "user" : "assistant", { createdAt: NOW - secondsAgo * 1_000 }),
  );
  const sameMinuteItems = mod.buildStreamItems(sameMinute, NOW);
  assert(
    sameMinuteItems.filter((item) => item.kind === "divider").length === 1,
    "Consecutive messages within the same minute are not repeatedly broken up by dividers: only the top one remains",
  );

  // 退化数据：时间戳乱序回到同一天时，不会连着插两条文案相同的分隔
  const shuffled = [
    message("x1", "user", { createdAt: new Date(2026, 9, 20, 10, 0, 0).getTime() }),
    message("x2", "user", { createdAt: new Date(2026, 9, 19, 10, 0, 0).getTime() }),
    message("x3", "user", { createdAt: new Date(2026, 9, 20, 10, 0, 30).getTime() }),
  ];
  const shuffledLabels = mod
    .buildStreamItems(shuffled, NOW)
    .filter((item) => item.kind === "divider")
    .map((item) => item.label);
  assert(
    shuffledLabels.every((label, index) => index === 0 || label !== shuffledLabels[index - 1]),
    `Out-of-order timestamps don't insert two dividers with the same text back to back: ${shuffledLabels.join(" | ")}`,
  );

  const broken = [message("b1", "user", { createdAt: 0 }), message("b2", "assistant", { createdAt: -1 })];
  const brokenItems = mod.buildStreamItems(broken, NOW);
  assert(brokenItems.every((item) => item.kind === "message"), "Invalid timestamps produce no divider and messages render as usual");

  const dup = [message("same", "user"), message("same", "assistant")];
  const dupKeys = mod.buildStreamItems(dup, NOW).filter((item) => item.kind === "message").map((item) => item.key);
  assert(new Set(dupKeys).size === 2, `Keys for duplicate ids are deduplicated: ${dupKeys.join(", ")}`);

  // ── 2. 工具调用：默认折叠的块，标题行带工具名与状态 ───────────────
  const call = message("t1", "assistant", { content: '{"path":"a.txt"}', toolName: "read_file", toolCallId: "call-1" });
  const status = mod.toolStatusOf(call, { isStreaming: true, isLast: true, approvalToolCallIds: new Set(["call-1"]) });
  assert(status === "awaiting", "When the toolCallId matches a pending approval, the status is \"Needs approval\"");
  const running = mod.toolStatusOf(call, { isStreaming: true, isLast: true, approvalToolCallIds: new Set() });
  assert(running === "running", "While generating and on the last message, the status is \"Running\"");
  const done = mod.toolStatusOf(message("t2", "tool", { toolName: "read_file", toolCallId: "call-1", content: "Result" }), {
    isStreaming: false,
    isLast: false,
    approvalToolCallIds: new Set(),
  });
  assert(done === "done", "A role=tool result message has status \"Done\"");

  const toolHtml = render(bubble({ message: call, toolStatus: "running" }));
  assert(toolHtml.includes("<details"), "The tool call block is a details collapsible element");
  assert(!toolHtml.includes("<details open"), "The tool call block is collapsed by default");
  assert(toolHtml.includes("read_file") && toolHtml.includes("Running"), "The title row shows both the tool name and the status");

  // ── 3. 思考过程：同款折叠，与工具块共用组件 ──────────────────────
  const thinkingHtml = render(bubble({ message: message("r1", "assistant", { content: "The answer", reasoning: "Think one step first" }) }));
  assert(thinkingHtml.includes("Reasoning") && thinkingHtml.includes("Think one step first"), "Reasoning renders as a collapsible block");
  assert(
    (thinkingHtml.match(/class="host-details"/g) ?? []).length === 1,
    "The reasoning block and tool block use the same collapsible component (host-details)",
  );

  // ── 4. 操作菜单：只给正文非空的非空菜单，项数随能力位与角色变 ────
  const menuLabels = (html) => [...html.matchAll(/<span>([^<]+)<\/span>/g)].map((match) => match[1]);
  const legacyMenu = render(
    bubble({ message: message("c1", "user", { content: "Copy me" }), onCopy: () => undefined, actionsOpen: true }),
  );
  assert(legacyMenu.includes("aria-label=\"Copy this message\""), "Legacy Host (no message_write) only offers Copy");
  assert(legacyMenu.includes("is-open"), "The open state is written on the bubble and the style layer reveals the action bar from it");
  assert((legacyMenu.match(/host-bubble-tools/g) ?? []).length === 1, "There can be only one action bar in the list");
  assert(
    menuLabels(legacyMenu).filter((label) => ["Copy", "Quote", "Favorite", "Delete", "Select"].includes(label)).join(",") === "Copy",
    `The only menu item is Copy: ${menuLabels(legacyMenu).join(",")}`,
  );
  const withoutCopy = render(bubble({ message: message("c2", "assistant", { content: "   " }), onCopy: () => undefined }));
  assert(!withoutCopy.includes("host-bubble-tools"), "The action bar is not rendered when the menu is not open");
  const emptyMenu = render(bubble({ message: message("c3", "user", { content: "   " }), capabilities: FULL, actionsOpen: true }));
  assert(!emptyMenu.includes("aria-label=\"Copy this message\""), "Empty body gets no Copy item (other write actions are unaffected)");

  // ── 4b. 菜单矩阵：能力位 × 角色 × 收藏态 ────────────────────────
  const idsOf = (items) => items.map((item) => item.id).join(",");
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: LEGACY })) === "copy",
    "Legacy Host: the menu only has Copy",
  );
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: FULL })) ===
      "copy,quote,favorite,select,delete",
    "New Host: Copy / Quote / Favorite / Select / Delete",
  );
  assert(
    idsOf(mod.actionsForMessage({ role: "assistant", hasContent: true, favorited: true, capabilities: FULL })) ===
      "copy,quote,unfavorite,select,delete",
    "When already favorited, the menu item becomes \"Unfavorite\"",
  );
  const writeOnly = { ...FULL, multiSelect: false };
  assert(
    !idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: writeOnly })).includes("select"),
    "Without message_multi_select there is no Select entry",
  );
  const selectOnly = { ...FULL, messageWrite: false };
  assert(
    idsOf(mod.actionsForMessage({ role: "user", hasContent: true, favorited: false, capabilities: selectOnly })) === "copy,select",
    "Without message_write only Copy remains (the Select entry stays; bulk actions are gated by the action bar itself)",
  );
  for (const role of ["tool", "system"]) {
    assert(
      idsOf(mod.actionsForMessage({ role, hasContent: true, favorited: false, capabilities: FULL })) === "copy",
      `${role} messages only get Copy`,
    );
  }
  const favoriteMenu = render(
    bubble({
      message: message("c4", "user", { content: "Favorite me" }),
      capabilities: FULL,
      favorited: true,
      actionsOpen: true,
    }),
  );
  assert(favoriteMenu.includes("Unfavorite") && !favoriteMenu.includes(">Favorite<"), "When favorited it renders \"Unfavorite\" instead of \"Favorite\"");
  assert(favoriteMenu.includes("Quote") && favoriteMenu.includes("Select") && favoriteMenu.includes("Delete"), "All five menu items appear in the open action bar");
  assert(favoriteMenu.includes("is-danger"), "The Delete item carries the destructive style marker");
  const toolMenu = render(
    bubble({
      message: message("c5", "tool", { content: "Tool result", toolName: "read_file" }),
      capabilities: FULL,
      actionsOpen: true,
    }),
  );
  assert(toolMenu.includes("host-tool is-open") && menuLabels(toolMenu).includes("Copy"), "A tool message, when open, also only has Copy");
  assert(!toolMenu.includes("Quote"), "A tool message gets no Quote");

  // ── 5. 媒体地址归一：新 Host 相对路径 + 老数据四种形态 ────────────
  const images = mod.resolveMessageImages([
    "https://example.com/a.png",
    "data:image/png;base64,AAA",
    "A".repeat(80),
    "file:///data/user/0/io.zer0.muse/files/images/x.bin",
    "/api/media/tok-1",
  ]);
  assert(images[0]?.kind === "src" && images[0].src === "https://example.com/a.png", "http URLs are used as-is");
  assert(images[1]?.kind === "src" && images[1].src.startsWith("data:"), "data URIs are used as-is");
  assert(images[2]?.kind === "src" && images[2].src.startsWith("data:image/png;base64,"), "Bare base64 gets the data prefix added");
  assert(images[3]?.kind === "unavailable", "file:// local paths are judged unavailable");
  assert(images[4]?.kind === "src" && images[4].src === "/api/media/tok-1", "The new Host's /api/media/<token> is used directly as a same-origin src");
  assert(mod.resolveVoiceUrl("/api/media/voice-1") === "/api/media/voice-1", "Voice URLs accept same-origin /api/media paths");
  assert(mod.resolveVoiceUrl("file:///data/voice.m4a") === undefined, "A voice file:// reference is judged unavailable");

  // ── 6. 图片消息：单图/多图、点击全屏、加载失败占位、media 能力位 ──
  const imageHtml = render(
    bubble({ message: message("i1", "assistant", { content: "Look at the picture", imageUrls: ["/api/media/tok-1"] }), capabilities: FULL }),
  );
  assert(imageHtml.includes('src="/api/media/tok-1"'), "imageUrls render same-origin relative paths directly");
  assert(imageHtml.includes("aria-label=\"View larger\"") && imageHtml.includes('class="quiet host-bubble-image"'), "The image can be clicked to view full-screen");
  assert(!imageHtml.includes("is-grid"), "A single image does not use the multi-image grid");
  const multiImageHtml = render(
    bubble({
      message: message("i2", "assistant", { imageUrls: ["/api/media/a", "/api/media/b"] }),
      capabilities: FULL,
    }),
  );
  assert(multiImageHtml.includes("host-bubble-images is-grid"), "Multiple images use a two-column grid");

  const fileImageHtml = render(
    bubble({
      message: message("i3", "assistant", { imageUrls: ["file:///data/x.bin"] }),
      capabilities: FULL,
    }),
  );
  assert(fileImageHtml.includes("host-bubble-image-missing") && !fileImageHtml.includes("<img"), "An unavailable image gets a placeholder instead of a broken image");

  const noMediaHtml = render(
    bubble({
      message: message("i4", "assistant", { imageUrls: ["/api/media/tok-1"], voiceUrl: "/api/media/v", voiceDurationMs: 5_000 }),
      capabilities: mod.readCapabilities(["chat", "streaming"]),
    }),
  );
  assert(!noMediaHtml.includes("host-bubble-image"), "Without the media capability, images are not rendered");
  assert(!noMediaHtml.includes("host-voice"), "Without the media capability, voice is not rendered");

  const lightboxHtml = render(mod.createElement(mod.ImageLightbox, { src: "/api/media/tok-1", onClose: () => undefined }));
  assert(lightboxHtml.includes('role="dialog"') && lightboxHtml.includes('aria-modal="true"'), "Full-screen view is an overlay with dialog semantics");
  assert(lightboxHtml.includes('src="/api/media/tok-1"') && lightboxHtml.includes("aria-label=\"Close preview\""), "The overlay provides the image and a close entry");

  // ── 7. 语音消息：可播放 vs 只有时长 ─────────────────────────────
  const voiceHtml = render(
    bubble({
      message: message("v1", "assistant", { voiceUrl: "/api/media/voice-1", voiceDurationMs: 12_000 }),
      capabilities: FULL,
    }),
  );
  assert(voiceHtml.includes("<audio") && voiceHtml.includes('src="/api/media/voice-1"'), "With a voiceUrl, an audio element is rendered");
  assert(voiceHtml.includes("aria-label=\"Play voice message\""), "The voice bar provides a play entry");
  assert(voiceHtml.includes("12″"), "The voice bar shows the duration converted from durationMs");
  assert(mod.formatVoiceDuration(12_000) === "12″" && mod.formatVoiceDuration(90_000) === "1′30″", "Duration formatting: seconds / minutes and seconds");

  const staticVoiceHtml = render(
    bubble({ message: message("v2", "assistant", { voiceDurationMs: 7_000 }), capabilities: FULL }),
  );
  assert(!staticVoiceHtml.includes("<audio"), "Without a voiceUrl, no audio element is attached");
  assert(staticVoiceHtml.includes("host-voice is-static") && staticVoiceHtml.includes("Voice · 7″"), "With only a duration, it degrades to a static bar");

  // ── 8. 消息状态：发送中 / 失败 + 重试 / 能力位关闭 ────────────────
  const sendingHtml = render(
    bubble({ message: message("st1", "user", { content: "Are you there?", status: "sending" }), capabilities: FULL }),
  );
  assert(sendingHtml.includes("Sending…") && sendingHtml.includes("host-msg-status"), "status=sending shows a lightweight sending indicator");

  const failedHtml = render(
    bubble({
      message: message("st2", "user", { content: "Are you there?", status: "failed", errorText: "手机没有网络" }),
      capabilities: FULL,
      onRetry: () => undefined,
    }),
  );
  assert(failedHtml.includes("手机没有网络"), "When failed, errorText is shown inside the bubble");
  assert(failedHtml.includes("host-retry") && failedHtml.includes(">Retry<"), "When failed, a \"Retry\" button is offered");
  assert(failedHtml.includes('role="alert"'), "The failure row has alert semantics");

  const failedNoText = render(
    bubble({ message: message("st3", "user", { status: "failed" }), capabilities: FULL, onRetry: () => undefined }),
  );
  assert(failedNoText.includes("Couldn't send"), "When errorText is missing, it falls back to \"Couldn't send\"");

  const noStatusCapability = render(
    bubble({
      message: message("st4", "user", { status: "failed", errorText: "手机没有网络" }),
      capabilities: mod.readCapabilities(["media"]),
      onRetry: () => undefined,
    }),
  );
  assert(
    !noStatusCapability.includes("host-msg-failed") && !noStatusCapability.includes("host-retry"),
    "Without the message_status capability, status and retry are not shown",
  );
  const sentHtml = render(bubble({ message: message("st5", "user", { content: "Sent", status: "sent" }), capabilities: FULL }));
  assert(!sentHtml.includes("host-msg-status"), "status=sent takes no extra space");

  // ── 9. 工具调用明细：toolCalls[] 优先，缺字段回退旧推断 ───────────
  const detailedCalls = [
    { callId: "c1", name: "read_file", argumentsPreview: '{"path":"a.txt"}', resultPreview: "hello", success: true, durationMs: 1_200 },
    { callId: "c2", name: "shell_exec", success: false, durationMs: 300 },
  ];
  const callsHtml = render(
    bubble({ message: message("tc1", "assistant", { content: "Check this", toolCalls: detailedCalls }), capabilities: FULL }),
  );
  assert((callsHtml.match(/class="host-details"/g) ?? []).length === 2, "Each tool call renders one collapsible block");
  assert(callsHtml.includes("read_file") && callsHtml.includes("shell_exec"), "Each block's title uses the call's own name");
  assert(callsHtml.includes("Success · 1.2s") && callsHtml.includes("Failed · 0.3s"), "The title row shows success or failure and the duration");
  assert(callsHtml.includes("Arguments") && callsHtml.includes("hello"), "The expanded area shows arguments and a result preview");
  assert(!callsHtml.includes("<details open"), "Tool details are collapsed by default");

  const callsWithoutCapability = render(
    bubble({
      message: message("tc2", "assistant", { content: "Check this", toolCalls: detailedCalls }),
      capabilities: mod.readCapabilities(["chat"]),
    }),
  );
  assert(!callsWithoutCapability.includes("read_file"), "Without tool_call_details, detail blocks are not rendered");
  const legacyToolHtml = render(
    bubble({
      message: message("tc3", "assistant", { content: "Check this", toolName: "read_file", toolCallId: "c9" }),
      capabilities: mod.readCapabilities(["chat"]),
      toolStatus: "requested",
    }),
  );
  assert(legacyToolHtml.includes("Tool call · read_file"), "When toolCalls is empty, it falls back to the block inferred from toolName");

  // ── 10. 能力协商：老 Host 不退化成全隐藏 ─────────────────────────
  assert(LEGACY.known === false && LEGACY.media === true, "When capabilityFlags are not sent, it falls back to legacy behaviour (media is not hidden)");
  assert(FULL.known === true && FULL.media && FULL.toolCallDetails && FULL.messageStatus, "All three capabilities are recognised");
  assert(mod.readCapabilities(["media"]).messageStatus === false, "A missing capability turns the feature off");
  const legacyImageHtml = render(
    bubble({ message: message("lg1", "assistant", { imageUrls: ["/api/media/tok-1"] }), capabilities: LEGACY }),
  );
  assert(legacyImageHtml.includes('src="/api/media/tok-1"'), "Under a legacy Host, reachable media URLs are still rendered");

  // ── 10b. 引用：气泡内的引用块 + 输入区上的草稿条 ──────────────────
  const quotedHtml = render(
    bubble(
      {
        message: message("q1", "user", { content: "This is my answer", quotedContent: "PROTO-quoted original text" }),
        capabilities: FULL,
      },
    ),
  );
  assert(quotedHtml.includes("host-quote") && quotedHtml.includes("PROTO-quoted original text"), "quotedContent renders as a quote block inside the bubble");
  const noQuoteHtml = render(bubble({ message: message("q2", "user", { content: "No quote" }), capabilities: FULL }));
  assert(!noQuoteHtml.includes("host-quote"), "When the Host does not send quotedContent, no quote block is shown (degraded)");
  assert(mod.quoteExcerpt("  First line\n\nSecond line  ") === "First line Second line", "The quote excerpt flattens newlines and extra whitespace");
  assert(mod.quoteExcerpt("x".repeat(120), 20) === `${"x".repeat(20)}…`, "An over-long quote excerpt is truncated with an ellipsis");

  // ── 10c. 多选与批量：纯逻辑 + 接线 ──────────────────────────────
  assert(mod.selectionCopyText([{ content: "a" }, { content: "  " }, { content: "b" }]) === "a\n\nb", "Multi-select copy only joins messages that have body text");
  assert(
    mod.deleteConfirmKey(1) === "message.deleteConfirmOne",
    `A single deletion uses the single-message entry: ${mod.deleteConfirmKey(1)}`,
  );
  assert(
    mod.deleteConfirmKey(3) === "message.deleteConfirmMany",
    `A bulk deletion uses the bulk entry: ${mod.deleteConfirmKey(3)}`,
  );

  // ── 11. 接线与样式：宿主把能力位、重试、全屏都接上 ────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of [
    "host-day-sep",
    "host-details-status",
    "host-bubble-tools",
    "host-msg-row",
    "host-select",
    "host-selection-bar",
    "host-quote",
    "host-quote-draft",
    "host-bubble-image",
    "host-bubble-image-missing",
    "host-voice",
    "host-tool-field",
    "host-msg-status",
    "host-msg-failed",
    "host-lightbox",
    "host-msg-error",
  ]) {
    assert(css.includes(`.${selector}`), `The stylesheet defines .${selector}`);
  }
  const chatSource = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
  assert(
    chatSource.includes("<MessageErrorBoundary") && chatSource.includes("buildStreamItems"),
    "Each message in the list is wrapped in error isolation, and dividers come from buildStreamItems",
  );
  assert(
    chatSource.includes('className="host-day-sep"') && chatSource.includes('role="separator"'),
    "Dividers enter the message stream with separator semantics",
  );
  assert(chatSource.includes("copyTextToClipboard"), "Copy goes through the clipboard helper with a fallback (LAN http has no Clipboard API)");
  assert(
    chatSource.includes("readCapabilities") && chatSource.includes("capabilities={capabilities}"),
    "Capabilities are read from the snapshot and passed to every message",
  );
  assert(
    chatSource.includes('type: "chat.retry", messageId'),
    "Retry sends chat.retry with this message's messageId",
  );
  assert(chatSource.includes("<ImageLightbox"), "Full-screen view is mounted on this chat page");
  const imagesSource = readFileSync(join(ROOT, "src", "host", "MessageImages.tsx"), "utf8");
  assert(imagesSource.includes("onError"), "A failed image load switches to a placeholder (instead of leaving a broken image)");

  // ── 11b. 多选 / 写操作 / 引用在聊天壳里的接线 ────────────────────
  assert(
    chatSource.includes("host-selection-bar") &&
      chatSource.includes('t("chat.selectedCount", { count: formatNumber(selectedIds.length, locale) })'),
    "In multi-select, a fixed action bar sits at the bottom and the top bar shows the selected count (numbers grouped for the current language)",
  );
  assert(chatSource.includes('t("chat.selectAll")') && chatSource.includes('t("chat.exitSelection")'), "The top action bar has Select all and Exit",
  );
  assert(
    chatSource.includes('className={selectedIds.includes(item.message.id) ? "host-select is-on" : "host-select"}') &&
      chatSource.includes('role="checkbox"'),
    "In multi-select, a checkbox is rendered to the left of each message",
  );
  assert(
    chatSource.includes("deleteConfirmKey(ids.length)") && chatSource.includes("window.confirm"),
    "Deletion first shows a confirmation (wording: only the local record is removed; this can't be undone)",
  );
  assert(
    chatSource.includes('{ type: "message.delete", messageIds: [...ids] }') &&
      chatSource.includes("setRemovedIds((current) => current.filter((id) => !ids.includes(id)))"),
    "Deletion goes through message.delete; if the Host refuses, hidden messages are put back",
  );
  assert(
    chatSource.includes("event.skippedMessageIds") && chatSource.includes('t("chat.deleteSkipped", { count: skipped.length })'),
    "Messages skipped in the receipt are shown again (not relying only on error)",
  );
  assert(
    chatSource.includes('sendOptimistic({ type: "message.favorite", messageId, favored: next }') &&
      chatSource.includes("delete restored[messageId]"),
    "Favorite flips locally first; if the Host refuses, the optimistic value is reverted",
  );
  assert(
    chatSource.includes("quoteExcerpt(message.content)") && chatSource.includes("host-quote-draft") &&
      chatSource.includes("setQuoteDraft(undefined)"),
    "The quote draft bar can be cancelled",
  );
  assert(
    chatSource.includes("quotedMessageId: quoted.messageId"),
    "Sending carries the quoted message's id with chat.send (field name quotedMessageId)",
  );
  assert(
    !/\{ messageId: quoted\.messageId \}/.test(chatSource),
    "chat.send no longer uses the old messageId field name",
  );
  const apiSource = readFileSync(join(ROOT, "src", "api.ts"), "utf8");
  assert(
    /readonly quotedMessageId\?: string;/.test(apiSource),
    "HostCommand declares quotedMessageId",
  );
  assert(
    chatSource.includes('capabilities.messageWrite && (') && chatSource.includes("host-selection-danger"),
    "Delete/Favorite in the bulk action bar are also gated by the message_write capability",
  );
  assert(
    chatSource.includes("favorited={isFavorited(item.message.id, item.message.favorite)}"),
    "Favorite state is decided by the Host field plus the local optimistic override",
  );
  const bubbleSource = readFileSync(join(ROOT, "src", "host", "MessageBubble.tsx"), "utf8");
  assert(
    bubbleSource.includes("actionsForMessage(") && bubbleSource.includes("onAction?.(message.id, action.id)"),
    "The bubble does not decide menu items itself; it delegates to actionsForMessage and reports the choice",
  );

  if (failures > 0) throw new Error(`Chat render assertions failed (${failures} failures)`);
  console.log("\nAll chat render assertions passed (day dividers / collapsible blocks / menu matrix / quotes / multi-select / images / voice / status / capability negotiation).");
} finally {
  await rm(TEMP, { force: true });
}
