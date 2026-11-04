/**
 * 会话与助手可用性的断言：助手选择弹层、行内重命名校验、归档可见性、删除文案、
 * 滚动位置记忆、focus_not_held 的专属提示。
 *
 * 纯逻辑（sessionActions / scrollMemory / hostErrors）直接跑；列表与弹层用
 * renderToStaticMarkup 渲染；交互路径（点开重命名、点助手）按源码接线断言。
 *
 * 用法：npm --workspace @muse/client run test
 */
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMP = join(ROOT, ".sessions-check.mjs");

const ENTRY = `
export {
  assistantIdForNewSession,
  isRenameValid,
  normalizeSessionTitle,
  shouldAskAssistant,
  visibleSessions,
} from "./src/host/sessionActions.ts";
export { ZH } from "./src/i18n/messages.ts";
export { ScrollMemory } from "./src/host/scrollMemory.ts";
export { describeHostError } from "./src/host/hostErrors.ts";
export { translate } from "./src/i18n/translate.ts";
export { HostAvatar } from "./src/host/HostAvatar.tsx";
export { AssistantPicker } from "./src/host/AssistantPicker.tsx";
export { SessionSidebar } from "./src/host/SessionDrawer.tsx";
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
    stdin: { contents: ENTRY, resolveDir: ROOT, sourcefile: "sessions-entry.tsx", loader: "tsx" },
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

const CHAT = readFileSync(join(ROOT, "src", "host", "HostChat.tsx"), "utf8");
const DRAWER = readFileSync(join(ROOT, "src", "host", "SessionDrawer.tsx"), "utf8");
const CONNECTION = readFileSync(join(ROOT, "src", "host", "useHostConnection.ts"), "utf8");

const session = (id, title, overrides = {}) => ({ id, title, assistantId: "a1", updatedAt: 1_700_000_000_000, archived: false, pinned: false, ...overrides });

try {
  // ── 1. 新建会话：多助手先选，单助手直接建 ──────────────────────
  const two = [{ id: "a1", name: "Muse" }, { id: "a2", name: "Iris" }];
  const one = [{ id: "a1", name: "Muse" }];
  assert(mod.shouldAskAssistant(two) === true, "With more than one assistant, one must be picked first");
  assert(mod.shouldAskAssistant(one) === false, "With only one assistant, no picker is shown");
  assert(mod.shouldAskAssistant([]) === false, "When the assistant list has not arrived yet, create directly (using the Host's default assistant)");
  assert(mod.assistantIdForNewSession(one) === "a1", "With only one assistant, it is included");
  assert(mod.assistantIdForNewSession(two) === undefined, "With multiple assistants, it does not decide for the user");
  assert(/shouldAskAssistant\(assistants\)/.test(CHAT), "Creating a session goes through the \"should we ask for an assistant first\" check");
  assert(/assistantIdForNewSession\(assistants\)/.test(CHAT), "The single-assistant path includes the assistantId");
  assert(/type: "session\.new", assistantId/.test(CHAT), "After an assistant is chosen, session.new carries the assistantId");

  const pickerHtml = render(
    mod.createElement(mod.AssistantPicker, {
      assistants: [
        { id: "a1", name: "PROTO-Muse", avatarUri: "/api/media/face-1", isDefault: true },
        { id: "a2", name: "PROTO-Iris" },
      ],
      onPick: () => undefined,
      onClose: () => undefined,
    }),
  );
  assert(pickerHtml.includes('role="dialog"') && pickerHtml.includes("aria-label=\"Choose an assistant\""), "Assistant picking is a dialog");
  assert(pickerHtml.includes("PROTO-Muse") && pickerHtml.includes("PROTO-Iris"), "Both assistant rows list their names");
  assert(pickerHtml.includes(">Default<"), "The default assistant has a badge");
  assert(pickerHtml.includes('src="/api/media/face-1"'), "With a usable avatar URL, an image is rendered");
  assert((pickerHtml.match(/role="option"/g) ?? []).length === 2, "Each assistant is an option");
  assert(pickerHtml.includes("Cancel"), "The overlay has a cancel entry");

  const avatarFallback = render(mod.createElement(mod.HostAvatar, { name: "Muse" }));
  assert(avatarFallback.includes("host-avatar-initial") && avatarFallback.includes(">M<"), "Without an avatar, the first letter of the name is used");
  const avatarLocal = render(mod.createElement(mod.HostAvatar, { name: "Muse", uri: "file:///data/face.png" }));
  assert(avatarLocal.includes(">M<") && !avatarLocal.includes("<img"), "A phone-local avatar URL falls back to the initial, without a broken image");

  // ── 2. 行内重命名：空名不允许 ─────────────────────────────────
  assert(mod.isRenameValid("") === false, "An empty name cannot be submitted");
  assert(mod.isRenameValid("   ") === false, "Whitespace-only also counts as an empty name");
  assert(mod.isRenameValid(" New name ") === true, "A normal title can be submitted");
  assert(mod.normalizeSessionTitle("  New   name ") === "New name", "Before submitting, leading/trailing whitespace is trimmed and inner whitespace is collapsed");
  assert(DRAWER.includes("host-session-rename") && /disabled=\{!isRenameValid\(renameDraft\)\}/.test(DRAWER), "With an empty name, \"Save\" is disabled");
  assert(DRAWER.includes('t("sessions.renameEmpty")'), "An empty name shows a hint");
  assert(/event\.key === "Enter"/.test(DRAWER) && /event\.key === "Escape"/.test(DRAWER), "Rename supports Enter to submit / Esc to cancel");
  assert(/setRenamingId\(session\.id\)/.test(DRAWER), "\"Rename\" in the ⋯ menu switches to inline input");
  assert(!/window\.prompt\(/.test(DRAWER), "Renaming no longer uses window.prompt");

  // ── 3. 归档可见性开关 ─────────────────────────────────────────
  const mixed = [session("s1", "In progress"), session("s2", "The archived one", { archived: true })];
  assert(mod.visibleSessions(mixed, false).length === 1, "By default archived sessions are not listed");
  assert(mod.visibleSessions(mixed, true).length === 2, "After turning on \"Show archived\" they are listed");
  // Host 以后会把归档会话也放进快照：过滤必须留在面板这一层，不能在上层提前艁掉
  assert(/visibleSessions\(sessions, showArchived\)/.test(DRAWER), "Archive filtering is computed in the session panel from the toggle");
  assert(/sessions=\{sessions\}/.test(CHAT), "The chat shell passes the snapshot's sessions to the panel as-is (including archived)");

  const sidebar = (showArchived) =>
    render(
      mod.createElement(mod.SessionSidebar, {
        sessions: mixed,
        ready: true,
        showArchived,
        onToggleArchived: () => undefined,
        currentId: "s1",
        previews: {},
        buildId: "test",
        onSelect: () => undefined,
        onNew: () => undefined,
        onRequestPreviews: () => undefined,
        onRename: () => undefined,
        onArchive: () => undefined,
        onDelete: () => undefined,
        onOpenSettings: () => undefined,
        onLogout: () => undefined,
        collapsed: false,
        onToggleSidebar: () => undefined,
      }),
    );
  const hidden = sidebar(false);
  assert(!hidden.includes("The archived one"), "With the toggle off, archived sessions do not appear");
  assert(hidden.includes("Show archived") && hidden.includes('role="switch"'), "The header has a \"Show archived\" toggle");
  const shown = sidebar(true);
  assert(shown.includes("The archived one") && shown.includes("Archived"), "With the toggle on, archived sessions appear and are marked \"Archived\"");
  assert(/onToggleArchived/.test(DRAWER), "The toggle state is held by the parent");

  // ── 4. 删除确认文案（词条在 i18n 表里，聊天壳负责套参数）────────
  assert(mod.ZH["sessions.deleteConfirm"].includes("{name}"), "The session delete confirmation is an entry with a placeholder");
  assert(mod.ZH["sessions.deleteConfirm"].includes("all of its messages") && mod.ZH["sessions.deleteConfirm"].includes("can't be undone"), "The confirmation entry says all messages will be deleted and this can't be undone");
  assert(CHAT.includes('t("sessions.deleteConfirm", { name: title.trim() || t("sessions.untitled") })'), "The chat shell fills the entry with the session name, falling back to the untitled name");
  assert(!CHAT.includes("This action cannot be undone"), "The old dead wording has been removed");

  // ── 5. 滚动位置记忆 ───────────────────────────────────────────
  const memory = new mod.ScrollMemory();
  assert(memory.recall("s1") === undefined, "With no record, there is no position");
  memory.remember("s1", 420);
  memory.remember("s2", 0);
  assert(memory.recall("s1") === 420 && memory.recall("s2") === 0, "Each session remembers its own position");
  memory.remember("s1", 900);
  assert(memory.recall("s1") === 900, "Recording again overwrites the old value");
  memory.forget("s1");
  assert(memory.recall("s1") === undefined, "After forgetting, the position is no longer returned");
  memory.remember("bad", -5);
  assert(memory.recall("bad") === undefined, "An invalid position is not recorded");
  const capped = new mod.ScrollMemory();
  for (let index = 0; index < 260; index += 1) capped.remember(`s${index}`, index);
  assert(capped.size() === 200, "The number of remembered entries is capped and does not grow without limit");
  assert(/scrollMemoryRef\.current\.remember|scrollMemoryRef\.current\.recall/.test(CHAT), "The chat page stores and recalls positions with this memory");
  assert(/pendingRestoreRef/.test(CHAT), "After switching sessions, it waits for the content to change before applying the position once");

  // ── 6. focus_not_held 的专属提示 ─────────────────────────────
  const zh = (key) => mod.translate("zh", key);
  assert(mod.describeHostError("focus_not_held", "x", zh) === zh("error.focusNotHeld"), "focus_not_held is replaced with its dedicated prompt");
  assert(zh("error.focusNotHeld").includes("Another device"), `Prompt for the zh locale: ${zh("error.focusNotHeld")}`);
  assert(mod.translate("en", "error.focusNotHeld").includes("Another device"), "The English prompt also has dedicated wording");
  assert(mod.describeHostError("session_not_found", "x", zh).includes("no longer exists"), "session_not_found also has readable wording");
  const notInSession = mod.describeHostError("message_not_in_current_session", "x", zh);
  assert(notInSession.includes("isn't in this conversation"), `message_not_in_current_session in plain words: ${notInSession}`);
  const deletedRace = mod.describeHostError("message_not_found", "x", zh);
  assert(deletedRace.includes("just been deleted") || deletedRace.includes("no longer exists"), `message_not_found (deletion race) in plain words: ${deletedRace}`);
  assert(mod.describeHostError("某个不太熟的错误", "fallback", zh) === "某个不太熟的错误", "An unrecognised reason is passed through as-is");
  assert(mod.describeHostError(undefined, "fallback", zh) === "fallback", "With no reason, the fallback wording is used");
  assert((CONNECTION.match(/describeHostError\(/g) ?? []).length >= 3, "All three error-event paths go through translation");

  // ── 7. 顶栏助手信息 ───────────────────────────────────────────
  assert(CHAT.includes("currentAssistant") && CHAT.includes("<HostAvatar name={currentAssistant.name}"), "The top bar renders the current assistant's avatar");
  assert(CHAT.includes('t("chat.pickAssistant")'), "With no session, the top bar shows a prompt");
  assert(CHAT.includes("${currentAssistant.name} · "), "The top bar subtitle includes the assistant name");

  // ── 8. 样式 ───────────────────────────────────────────────────
  const css = readFileSync(join(ROOT, "src", "styles.css"), "utf8");
  for (const selector of ["host-avatar", "host-picker", "host-session-rename", "host-archived-toggle", "host-switch-loading", "host-topbar-head"]) {
    assert(css.includes(`.${selector}`), `The stylesheet defines .${selector}`);
  }

  if (failures > 0) throw new Error(`Session and assistant assertions failed (${failures} failures)`);
  console.log("\nAll session and assistant assertions passed (assistant picking / rename / archiving / delete wording / scroll memory / error prompts).");
} finally {
  await rm(TEMP, { force: true });
}
