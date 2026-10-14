// Main process: owns the window, the two remote views, and the local API.
//
// Mirrors the Android standalone app: the DeepSeek page is the driving session,
// the client UI talks to a loopback API, and a Controls view reports state.
// The layout difference is that a desktop window switches views from a tab
// strip rather than swiping.

const { app, BrowserWindow, WebContentsView, ipcMain, shell, dialog } = require("electron");
const path = require("path");
const fs = require("fs");
const { startApi } = require("./api");
const {
  API_PORT,
  API_HOST,
  DEEPSEEK_URL,
  WEB_UI_URL,
  DATA_DIR_NAME,
  LAST_CHAT_FILE,
} = require("./config");

// ---------------------------------------------------------------------------
// Portable data location.
//
// Everything Electron persists - cookies, localStorage, cache - lives under
// userData. Pointing it at a folder beside the executable is what makes the app
// genuinely portable: the DeepSeek login, the site's conversations and its
// settings all travel with the folder, and nothing is written to %APPDATA%.
//
// This must run before app is ready, because the path is read during startup.
// ---------------------------------------------------------------------------
(function redirectUserData() {
  try {
    // Where the data lives, in order of preference:
    //
    //  1. PORTABLE_EXECUTABLE_DIR - set by the electron-builder portable
    //     launcher to the folder holding the .exe the user ran. Needed because
    //     a portable build unpacks itself to %TEMP% and runs from there, so
    //     process.execPath points at a throwaway copy: the account and
    //     conversations would land in Temp and be cleared on reboot.
    //  2. The folder of the running executable - correct for the unpacked
    //     ("dir") build this project actually ships.
    //  3. The source folder in development, where the Electron binary lives
    //     inside node_modules and writing beside it would bury the data.
    const portableDir = process.env.PORTABLE_EXECUTABLE_DIR;
    const base = portableDir
      ? portableDir
      : app.isPackaged
        ? path.dirname(process.execPath)
        : __dirname;

    const dataDir = path.join(base, DATA_DIR_NAME);
    fs.mkdirSync(dataDir, { recursive: true });
    app.setPath("userData", dataDir);
    console.log(`Data directory: ${dataDir}`);
  } catch (err) {
    // A read-only location (Program Files, a locked USB stick) must not stop
    // the app from starting; it simply falls back to the default location.
    console.error(`Could not use a portable data directory: ${err.message}`);
  }
})();

// The session cookie is the whole point of persistence; make sure it is kept.
app.commandLine.appendSwitch("disable-features", "CalculateNativeWinOcclusion");

if (!app.requestSingleInstanceLock()) {
  app.quit();
  process.exit(0);
}

const state = {
  apiEnabled: true,
  serverRunning: false,
  transport: "direct (local only)",
  lastError: null,
  log: [],
};

let mainWindow = null;
let apiServer = null;
/** @type {WebContentsView|null} */
let deepseekView = null;
/** @type {WebContentsView|null} */
let webUiView = null;

function log(msg) {
  const line = `${new Date().toLocaleTimeString()}  ${msg}`;
  state.log.push(line);
  if (state.log.length > 500) state.log.shift();
  state.lastError = msg;
  console.log(line);
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send("state:update", snapshot());
  }
}

function snapshot() {
  return {
    apiEnabled: state.apiEnabled,
    serverRunning: state.serverRunning,
    transport: state.transport,
    lastError: state.lastError,
    log: state.log.slice(-200),
    apiPort: API_PORT,
    apiUrl: `http://${API_HOST}:${API_PORT}`,
  };
}

// -- last conversation -------------------------------------------------------

/**
 * Where the remembered conversation URL is kept.
 *
 * Beside the other persisted data, so it travels with the portable folder and is
 * cleared only if the folder is.
 */
function lastChatPath() {
  return path.join(app.getPath("userData"), LAST_CHAT_FILE);
}

/** The last conversation URL, if one was recorded. */
function readLastChatUrl() {
  try {
    const saved = fs.readFileSync(lastChatPath(), "utf8").trim();
    // Only replay a real conversation on the expected host: a stale or edited
    // value must not be able to point the app somewhere else.
    if (saved.startsWith("https://chat.deepseek.com/a/chat/s/")) return saved;
    return null;
  } catch (e) {
    return null;
  }
}

/**
 * Remember the conversation currently open.
 *
 * Only a conversation URL is stored, so the home page cannot overwrite a good
 * value and the app does not forget where it was as soon as the user goes back.
 */
function rememberChatUrl(url) {
  const u = String(url || "");
  if (!u.startsWith("https://chat.deepseek.com/a/chat/s/")) return;
  // Drop query and fragment noise so the same chat compares equal to itself.
  const clean = u.split("?")[0].split("#")[0];
  try {
    if (fs.readFileSync(lastChatPath(), "utf8").trim() === clean) return;
    fs.writeFileSync(lastChatPath(), clean, "utf8");
  } catch (e) {
    log(`Could not record the conversation URL: ${e.message}`);
  }
}

// -- remote views ------------------------------------------------------------

/**
 * Create the two remote pages once, at startup.
 *
 * They stay attached for the life of the app and are shown or hidden by moving
 * them on and off screen. Recreating them per tab would reload the DeepSeek
 * session on every switch, and a WebContentsView cannot be re-parented.
 */
function createRemoteViews() {
  deepseekView = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  deepseekView.setBounds(PARKED);
  mainWindow.contentView.addChildView(deepseekView);

  // Inject the hook into the PAGE's own world at document start.
  //
  // It cannot live in the preload: with contextIsolation the preload gets its
  // own XMLHttpRequest, so patching there patched a copy the page never uses -
  // the hook reported itself installed and captured nothing. executeJavaScript
  // runs in the page world, which is what the Android build achieves with its
  // document-start injection.
  const hookSource = fs.readFileSync(path.join(__dirname, "hook.js"), "utf8");
  deepseekView.webContents.on("did-start-loading", () => {
    deepseekView?.webContents.executeJavaScript(hookSource, true).catch(() => {});
  });
  // did-start-loading can fire before the document exists; this second attempt
  // covers the case where the first ran too early.
  deepseekView.webContents.on("dom-ready", () => {
    deepseekView?.webContents.executeJavaScript(hookSource, true).catch(() => {});
  });

  // Reopen the conversation that was last on screen, so the app does not start
  // on the empty home page every time. Falls back to the home page when nothing
  // has been recorded yet.
  const startUrl = readLastChatUrl();
  if (startUrl) log(`Reopening the last conversation`);
  deepseekView.webContents.loadURL(startUrl || DEEPSEEK_URL);

  // Follow the conversation as the user browses.
  //
  // DeepSeek is a single-page app: switching chats calls history.pushState,
  // which fires neither of the loading events. Polling the view's real URL is the
  // dependable signal, and a repeat of the same URL costs nothing because
  // rememberChatUrl compares before writing.
  const urlWatcher = setInterval(() => {
    try {
      if (!deepseekView || deepseekView.webContents.isDestroyed()) return;
      rememberChatUrl(deepseekView.webContents.getURL());
    } catch (e) {
      /* the view may be mid-teardown */
    }
  }, 1500);
  app.on("before-quit", () => clearInterval(urlWatcher));

  webUiView = new WebContentsView({
    webPreferences: {
      // The workspace bridge, so the page's Link Folder, Open and workspace tree
      // work here exactly as they do on Android.
      preload: path.join(__dirname, "preload-ui.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });
  webUiView.setBounds(PARKED);
  mainWindow.contentView.addChildView(webUiView);

  // Seed the site's own provider list so the client UI points here, without
  // modifying the website. Runs before the page's script reads its settings.
  webUiView.webContents.on("did-finish-load", () => {
    webUiView?.webContents
      .executeJavaScript(seedLocalProviderScript(), true)
      .then((r) => log(`Web UI provider seed: ${r}`))
      .catch(() => {});
  });
  webUiView.webContents.loadURL(WEB_UI_URL);

  for (const v of [deepseekView, webUiView]) {
    v.webContents.setWindowOpenHandler(({ url }) => {
      shell.openExternal(url);
      return { action: "deny" };
    });
  }
}

/**
 * Merge a "local-standalone" provider into the site's stored settings.
 *
 * The site already reads its provider list from localStorage, so this is
 * indistinguishable from configuring it by hand - no change to the website.
 */
function seedLocalProviderScript() {
  const provider = {
    id: "local-standalone",
    label: "This PC (standalone)",
    baseUrl: `http://${API_HOST}:${API_PORT}`,
    apiKey: "local",
    route: "coding",
    pathSuffix: "/chat/completions/full",
    responseMode: "full",
    modelsPath: "/models",
    note: "Served by the desktop app. No Worker, no network hop.",
    models: [
      { id: "deepseek-web", label: "DeepSeek (Web)", desc: "Current web model" },
      { id: "deepseek-web-think", label: "DeepSeek (DeepThink)", desc: "Reasoning" },
    ],
  };

  return `
    (function(){
      try {
        var KEY = 'deepseek-ui-settings';
        var p = ${JSON.stringify(provider)};
        var s = {};
        try { s = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch (e) { s = {}; }
        if (!Array.isArray(s.providers)) s.providers = [];
        var i = -1;
        for (var k = 0; k < s.providers.length; k++) {
          if (s.providers[k] && s.providers[k].id === p.id) { i = k; break; }
        }
        if (i >= 0) s.providers[i] = p; else s.providers.push(p);
        if (!s.activeProviderId || s.activeProviderId === 'deepseek-web') s.activeProviderId = p.id;
        if (!s.model) s.model = 'deepseek-web';
        localStorage.setItem(KEY, JSON.stringify(s));
        return 'seeded';
      } catch (e) { return 'seed error: ' + e; }
    })();
  `;
}

/**
 * Show one remote view and park the other off screen.
 *
 * Parking rather than detaching: a WebContentsView cannot be re-added to a
 * different parent, and removing it would destroy the DeepSeek session.
 */
let lastBounds = null;
let activeView = "deepseek";
const PARKED = { x: -20000, y: 0, width: 800, height: 600 };

function setActiveView(name) {
  // The Controls tab has no remote view of its own; park both so the local
  // panel is not covered.
  activeView = name;

  const deepseekVisible = name === "deepseek";
  const webUiVisible = name === "webui";

  if (deepseekView) deepseekView.setBounds(deepseekVisible && lastBounds ? lastBounds : PARKED);
  if (webUiView) webUiView.setBounds(webUiVisible && lastBounds ? lastBounds : PARKED);
}

/**
 * Record where the active host sits.
 *
 * Bounds belong to the ACTIVE view only. Applying them to both put the two
 * views in the same rectangle, so whichever was added last stayed on top and
 * the tabs appeared to do nothing.
 */
function setViewBounds({ view, x, y, width, height }) {
  if (width <= 0 || height <= 0) return; // not laid out yet
  lastBounds = { x, y, width, height };

  const deepseekVisible = (view || activeView) === "deepseek";
  const webUiVisible = (view || activeView) === "webui";

  if (deepseekView) deepseekView.setBounds(deepseekVisible ? lastBounds : PARKED);
  if (webUiView) webUiView.setBounds(webUiVisible ? lastBounds : PARKED);
}

// -- workspace ---------------------------------------------------------------
//
// Backs the AndroidWorkspace bridge the client UI expects. Android needs the
// Storage Access Framework for this; a desktop app runs as the user and can read
// the filesystem directly, so a linked folder is simply a path.
//
// The method names, arguments and JSON-string results deliberately mirror the
// Android implementation, so the page's existing code works on either platform
// without knowing which it is running on.

/** id -> absolute folder path, for folders linked in this session. */
const linkedFolders = new Map();

/** Reject anything that would escape the linked folder. */
function safeJoin(root, relativePath) {
  const target = path.resolve(root, String(relativePath || ""));
  const base = path.resolve(root);
  if (target !== base && !target.startsWith(base + path.sep)) return null;
  return target;
}

/** Paths the workspace ignores: build output and dependencies. */
function isIgnoredPath(rel) {
  return /(^|[\\/])(\.git|node_modules|build|dist|\.gradle|__pycache__)([\\/]|$)/.test(rel);
}

function listFilesRecursive(root, dir = root, out = []) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (e) {
    return out;
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    const rel = path.relative(root, full);
    if (isIgnoredPath(rel)) continue;
    if (entry.isDirectory()) {
      listFilesRecursive(root, full, out);
    } else if (entry.isFile()) {
      let size = 0;
      try { size = fs.statSync(full).size; } catch (e) { /* unreadable */ }
      out.push({ path: rel.split(path.sep).join("/"), size });
    }
  }
  return out;
}

const j = (obj) => JSON.stringify(obj);
const jErr = (message) => JSON.stringify({ ok: false, error: message });

ipcMain.handle("workspace:pickFolder", async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "Choose a folder to link",
    properties: ["openDirectory", "createDirectory"],
  });
  if (result.canceled || !result.filePaths.length) {
    return jErr("Folder selection was cancelled.");
  }
  const folder = result.filePaths[0];
  const id = "ws" + Date.now().toString(36);
  linkedFolders.set(id, folder);
  return j({ ok: true, id, name: path.basename(folder) || folder, uri: folder });
});

ipcMain.handle("workspace:restoreFolder", async (_e, id, folderPath) => {
  try {
    const stat = fs.statSync(folderPath);
    if (!stat.isDirectory()) return jErr("That path is not a folder.");
    linkedFolders.set(id, folderPath);
    return j({ ok: true, id, name: path.basename(folderPath) || folderPath });
  } catch (e) {
    return jErr(`The folder is no longer accessible: ${e.message}`);
  }
});

ipcMain.handle("workspace:listFiles", async (_e, id) => {
  const root = linkedFolders.get(id);
  if (!root) return jErr("No folder is linked.");
  try {
    return j({ ok: true, files: listFilesRecursive(root) });
  } catch (e) {
    return jErr(`Could not read the folder: ${e.message}`);
  }
});

ipcMain.handle("workspace:readFile", async (_e, id, relativePath) => {
  const root = linkedFolders.get(id);
  if (!root) return jErr("No folder is linked.");
  const full = safeJoin(root, relativePath);
  if (!full) return jErr("That path is outside the linked folder.");
  try {
    const buf = fs.readFileSync(full);
    return j({ ok: true, base64: buf.toString("base64"), size: buf.length });
  } catch (e) {
    return jErr(`Could not read ${relativePath}: ${e.message}`);
  }
});

ipcMain.handle("workspace:writeFile", async (_e, id, relativePath, base64) => {
  const root = linkedFolders.get(id);
  if (!root) return jErr("No folder is linked.");
  const full = safeJoin(root, relativePath);
  if (!full) return jErr("That path is outside the linked folder.");
  try {
    fs.mkdirSync(path.dirname(full), { recursive: true });
    const buf = Buffer.from(String(base64 || ""), "base64");
    fs.writeFileSync(full, buf);
    return j({ ok: true, path: relativePath, size: buf.length });
  } catch (e) {
    return jErr(`Could not write ${relativePath}: ${e.message}`);
  }
});

ipcMain.handle("workspace:deleteFile", async (_e, id, relativePath) => {
  const root = linkedFolders.get(id);
  if (!root) return jErr("No folder is linked.");
  const full = safeJoin(root, relativePath);
  if (!full) return jErr("That path is outside the linked folder.");
  try {
    fs.rmSync(full, { force: true });
    return j({ ok: true });
  } catch (e) {
    return jErr(`Could not delete ${relativePath}: ${e.message}`);
  }
});

ipcMain.handle("workspace:openFile", async (_e, id, relativePath, mimeType, contentBase64) => {
  const root = id ? linkedFolders.get(id) : null;
  try {
    if (root) {
      const full = safeJoin(root, relativePath);
      if (!full) return jErr("That path is outside the linked folder.");
      // Opening from disk rather than a copy, so a page's relative links to its
      // sibling files resolve.
      const err = await shell.openPath(full);
      if (err) return jErr(err);
      return j({ ok: true });
    }

    // No folder linked: write the workspace copy somewhere temporary so the
    // default application has a real file to open.
    const dir = path.join(app.getPath("temp"), "deepseek-code-preview");
    fs.mkdirSync(dir, { recursive: true });
    const full = path.join(dir, path.basename(relativePath));
    fs.writeFileSync(full, Buffer.from(String(contentBase64 || ""), "base64"));
    const err = await shell.openPath(full);
    if (err) return jErr(err);
    return j({ ok: true });
  } catch (e) {
    return jErr(`Could not open ${relativePath}: ${e.message}`);
  }
});

// -- window ------------------------------------------------------------------

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1320,
    height: 880,
    minWidth: 980,
    minHeight: 640,
    show: false,
    backgroundColor: "#12141a",
    title: "DeepSeek Standalone",
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload-renderer.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
  mainWindow.once("ready-to-show", () => mainWindow.show());
  mainWindow.on("closed", () => {
    mainWindow = null;
  });

  createRemoteViews();
}

// -- API ---------------------------------------------------------------------

async function startServer() {
  if (apiServer) return;
  try {
    apiServer = await startApi({
      host: API_HOST,
      port: API_PORT,
      getHandlers: () => handlers,
      onLog: log,
    });
    state.serverRunning = true;
    log(`Local API listening on http://${API_HOST}:${API_PORT}`);
  } catch (err) {
    state.serverRunning = false;
    log(
      `Failed to start the local API on ${API_HOST}:${API_PORT}: ${err.message}. ` +
        `Another program may already be using that port.`
    );
  }
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send("state:update", snapshot());
  }
}

async function stopServer() {
  if (!apiServer) return;
  try {
    await apiServer.close();
  } catch {
    /* already closed */
  }
  apiServer = null;
  state.serverRunning = false;
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send("state:update", snapshot());
  }
}

/**
 * How long the stream may stay silent before the request is treated as stalled.
 *
 * A quiet window, not a deadline: any delta resets it, so an answer that takes
 * many minutes but keeps arriving is never cut off, while a genuinely dead
 * connection fails promptly. The page's own sender has a fixed ten-minute
 * ceiling, which is the wrong shape - it stops a live answer for being long.
 */
const INACTIVITY_TIMEOUT_MS = 120_000;

const handlers = {
  isEnabled: () => state.apiEnabled,
  log,

  /**
   * Run one completion, and treat silence - not elapsed time - as failure.
   *
   * The page's own sender has a fixed ten-minute ceiling, which cut off long
   * answers that were still arriving and reported them as failures. What
   * actually indicates a dead request is that nothing has arrived for a while,
   * so a watchdog is reset on every delta: a slow but live answer may run as long
   * as it needs, while a genuinely stalled one fails after the quiet window.
   */
  async complete({ prompt, thinking, search, onDelta, signal }) {
    if (!deepseekView || deepseekView.webContents.isDestroyed()) {
      throw new Error("The DeepSeek page is not available.");
    }

    let lastProgressAt = Date.now();
    let stalled = false;

    const watchdog = setInterval(() => {
      if (Date.now() - lastProgressAt >= INACTIVITY_TIMEOUT_MS) {
        stalled = true;
        log(
          `No output for ${INACTIVITY_TIMEOUT_MS / 1000}s; stopping and reporting the stall ` +
            `(not a length timeout)`
        );
        clearInterval(watchdog);
        deepseekView?.webContents
          .executeJavaScript("window.__dsPage && window.__dsPage.stop()", true)
          .catch(() => {});
      }
    }, 1000);

    const channelId = `req-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const onChunk = (_evt, payload) => {
      if (!payload || payload.channelId !== channelId) return;
      lastProgressAt = Date.now();
      if (payload.delta) onDelta(payload.delta);
    };
    const onPageLog = (_evt, text) => log(`Page: ${text}`);
    ipcMain.on("page:delta", onChunk);
    ipcMain.on("page:log", onPageLog);

    const abort = () => {
      deepseekView?.webContents
        .executeJavaScript("window.__dsPage && window.__dsPage.stop()", true)
        .catch(() => {});
    };
    signal?.addEventListener("abort", abort, { once: true });

    try {
      const result = await deepseekView.webContents.executeJavaScript(
        `window.__dsPage.sendPrompt(${JSON.stringify(prompt)}, ${Boolean(thinking)}, ${Boolean(search)}, ${JSON.stringify(channelId)})`,
        true
      );
      if (!result || !result.ok) {
        throw new Error((result && result.error) || "The page did not accept the prompt.");
      }
      if (stalled) {
        throw new Error(
          `The page stopped producing output for ${INACTIVITY_TIMEOUT_MS / 1000}s. ` +
            `Any text already received is kept; use the fetch button to pull the full reply.`
        );
      }
      return { content: result.content || "", reasoning: result.reasoning || "" };
    } finally {
      clearInterval(watchdog);
      ipcMain.removeListener("page:delta", onChunk);
      ipcMain.removeListener("page:log", onPageLog);
      signal?.removeEventListener("abort", abort);
    }
  },

  /** The most recent reply, read from the hook's buffer without re-running it. */
  async lastReply() {
    if (!deepseekView || deepseekView.webContents.isDestroyed()) {
      throw new Error("The DeepSeek page is not available.");
    }
    return await deepseekView.webContents.executeJavaScript(
      "window.__dsPage ? window.__dsPage.lastReply() : null",
      true
    );
  },

  /** Whether the page is offering its Continue control. */
  async isContinueOffered() {
    if (!deepseekView || deepseekView.webContents.isDestroyed()) return false;
    try {
      return Boolean(
        await deepseekView.webContents.executeJavaScript(
          "window.__dsPage && window.__dsPage.isContinueOffered ? window.__dsPage.isContinueOffered() : false",
          true
        )
      );
    } catch (e) {
      return false;
    }
  },

  stop: async () => {
    if (!deepseekView) return false;
    return await deepseekView.webContents.executeJavaScript(
      "window.__dsPage && window.__dsPage.stop()",
      true
    );
  },
};

// -- IPC ---------------------------------------------------------------------

ipcMain.handle("state:get", () => snapshot());
ipcMain.handle("urls:get", () => ({ deepseek: DEEPSEEK_URL, webUi: WEB_UI_URL, apiPort: API_PORT }));
ipcMain.handle("api:setEnabled", async (_e, enabled) => {
  state.apiEnabled = Boolean(enabled);
  if (state.apiEnabled) await startServer();
  else await stopServer();
  return snapshot();
});
ipcMain.handle("api:restart", async () => {
  await stopServer();
  await startServer();
  return snapshot();
});
ipcMain.handle("log:clear", () => {
  state.log = [];
  state.lastError = null;
  return snapshot();
});
ipcMain.handle("page:reload", () => {
  deepseekView?.webContents.reload();
  return true;
});

ipcMain.on("view:set", (_e, name) => setActiveView(name));
ipcMain.on("view:bounds", (_e, bounds) => setViewBounds(bounds));

// -- lifecycle ---------------------------------------------------------------

app.whenReady().then(async () => {
  // Persist session state to disk as it changes.
  //
  // The DeepSeek login is a cookie plus site storage. Electron keeps these in
  // the session partition, which is written under userData - and userData is
  // redirected above, so the login lands in the portable folder. Flushing here
  // means a hard kill (power loss, task manager) does not lose a fresh login.
  try {
    const { session } = require("electron");
    const ses = session.defaultSession;
    ses.cookies.on("changed", () => {
      ses.flushStorageData();
    });
    // Restore anything already on disk before the first page load.
    await ses.cookies.get({ domain: ".deepseek.com" }).catch(() => {});
    log(`Session restored from ${app.getPath("userData")}`);
  } catch (err) {
    log(`Session restore skipped: ${err.message}`);
  }

  createWindow();
  await startServer();
});

app.on("window-all-closed", async () => {
  await stopServer();
  app.quit();
});

app.on("second-instance", () => {
  if (mainWindow) {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.focus();
  }
});
