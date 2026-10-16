// Main process: owns the window, the two remote views, and the local API.
//
// Mirrors the Android standalone app: the DeepSeek page is the driving session,
// the client UI talks to a loopback API, and a Controls view reports state.
// The layout difference is that a desktop window switches views from a tab
// strip rather than swiping.

const {
  app,
  BrowserWindow,
  WebContentsView,
  ipcMain,
  shell,
  dialog,
  clipboard,
} = require("electron");
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
    //     launcher to the folder holding the executable the user ran.
    //  2. APPIMAGE - the file the user actually ran, when packaged as an
    //     AppImage. process.execPath points inside the mounted image, which is
    //     read-only, so writing beside it could not work.
    //  3. The folder of the running executable - correct for the unpacked
    //     ("dir") build this project also ships.
    //  4. The source folder in development, where the Electron binary lives
    //     inside node_modules and writing beside it would bury the data.
    const portableDir = process.env.PORTABLE_EXECUTABLE_DIR;
    const appImageDir = process.env.APPIMAGE ? path.dirname(process.env.APPIMAGE) : null;
    const base = portableDir
      ? portableDir
      : appImageDir
        ? appImageDir
        : app.isPackaged
          ? path.dirname(process.execPath)
          : __dirname;

    const dataDir = path.join(base, DATA_DIR_NAME);

    // A folder beside the executable is the intent, but it is not always
    // writable - an AppImage mounted read-only, or an install under /opt. In
    // that case the per-user directory is right, and finding out here beats
    // failing later with a permission error.
    let target = dataDir;
    try {
      fs.mkdirSync(dataDir, { recursive: true });
      fs.accessSync(dataDir, fs.constants.W_OK);
    } catch (err) {
      // Electron's per-user location, honouring XDG_DATA_HOME.
      target = path.join(app.getPath("appData"), DATA_DIR_NAME);
      fs.mkdirSync(target, { recursive: true });
      console.log(`Directory beside the executable is not writable; using ${target}`);
    }

    app.setPath("userData", target);
    console.log(`Data directory: ${target}`);
  } catch (err) {
    // Must not stop the app from starting; it falls back to the default.
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
  } catch (e) {
    // Missing file is the normal first-run case, not an error worth reporting.
  }
  try {
    // The directory is created here rather than assumed.
    //
    // Electron creates userData for its own files, but this write is ours, and on
    // a first run the folder does not exist yet - so the write failed with ENOENT
    // and the remembered conversation was never stored. The resume feature then
    // found nothing on every launch, which looked like the watcher not working.
    fs.mkdirSync(path.dirname(lastChatPath()), { recursive: true });
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
  // modifying the website.
  //
  // This must run BEFORE the page's script reads its settings. It used to run on
  // did-finish-load, which is after that point - the write landed in localStorage
  // but the running page had already read its config, so the provider only showed
  // up once something else caused a reload. On a fresh install that meant it was
  // not there at all until the user opened Settings.
  //
  // did-start-loading fires before any page script runs; dom-ready repeats it in
  // case the first attempt was too early for localStorage to exist.
  const seed = () => {
    webUiView?.webContents
      .executeJavaScript(seedLocalProviderScript(), true)
      .then((r) => log(`Web UI provider seed: ${r}`))
      .catch(() => {});
  };
  webUiView.webContents.on("did-start-loading", seed);
  webUiView.webContents.on("dom-ready", seed);
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
  // Superseded: the preload writes the provider before the page reads it.
  //
  // Seeding from here ran on did-start-loading and dom-ready, both of which are
  // after the page executes `let settings = loadSettings()`. The write landed in
  // storage but the running page never read it, so the app looked configured while
  // using a provider with no base URL.
  return "delegated to preload-ui.js";
}

/**
 * The provider record the client UI should use for this machine.
 *
 * Built in one place so the port cannot drift between what is advertised and
 * where the API actually listens.
 */
function localProvider() {
  return {
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
      { id: "deepseek-web-think", label: "DeepSeek (Think)", desc: "Reasoning" },
    ],
  };
}

/** Serve the record to the preload, which writes it before the page reads it. */
onSync("workspace:localProvider", () => localProvider());

/**
 * Show one remote view and park the other off screen.
 *
 * Parking rather than detaching: a WebContentsView cannot be re-added to a
 * different parent, and removing it would destroy the DeepSeek session.
 */
let lastBounds = null;

/**
 * Which view is on screen.
 *
 * Opens on the client UI, matching Android, whose pager starts at page 1. The
 * DeepSeek tab is where the session lives and where you sign in, but it is not
 * what the app is for - the client UI is.
 */
let activeView = "webui";
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

// These are synchronous, on purpose.
//
// The page calls the bridge the way it calls Android's @JavascriptInterface
// methods - without awaiting, because those return a value directly:
//
//     picked = JSON.parse(bridge.pickFolder());
//
// contextBridge methods return Promises, so JSON.parse(Promise) threw and linking
// a folder reported "Folder picker failed" even though the picker itself had
// worked. ipcRenderer.sendSync + event.returnValue reproduces Android's behaviour
// exactly, so the page runs unchanged on both platforms.
//
// The work is local filesystem access that finishes in milliseconds. The picker
// blocks only while the dialog is open, which is inherent to a modal.

/** Wrap a synchronous handler: run it, then hand the result back to sendSync. */
function onSync(channel, fn) {
  ipcMain.on(channel, (event, ...args) => {
    try {
      event.returnValue = fn(...args);
    } catch (e) {
      // Never leave the channel hanging: sendSync would block the renderer
      // forever if returnValue were left unset.
      event.returnValue = jErr(`${channel} failed: ${e.message}`);
    }
  });
}

onSync("workspace:pickFolder", () => {
  // showOpenDialog is async; block here until the user has chosen. sendSync
  // requires a value before returning, and a modal picker is a blocking
  // interaction by nature.
  const result = dialog.showOpenDialogSync(mainWindow, {
    title: "Choose a folder to link",
    properties: ["openDirectory", "createDirectory"],
  });
  if (!result || !result.length) {
    return jErr("Folder selection was cancelled.");
  }
  const folder = result[0];
  const id = "ws" + Date.now().toString(36);
  linkedFolders.set(id, folder);
  return j({ ok: true, id, name: path.basename(folder) || folder, uri: folder });
});

onSync("workspace:restoreFolder", (id, folderPath) => {
  try {
    const stat = fs.statSync(folderPath);
    if (!stat.isDirectory()) return jErr("That path is not a folder.");
    linkedFolders.set(id, folderPath);
    return j({ ok: true, id, name: path.basename(folderPath) || folderPath });
  } catch (e) {
    return jErr(`The folder is no longer accessible: ${e.message}`);
  }
});

onSync("workspace:listFiles", (id) => {
  const root = linkedFolders.get(id);
  if (!root) return jErr("No folder is linked.");
  try {
    return j({ ok: true, files: listFilesRecursive(root) });
  } catch (e) {
    return jErr(`Could not read the folder: ${e.message}`);
  }
});

onSync("workspace:readFile", (id, relativePath) => {
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

onSync("workspace:writeFile", (id, relativePath, base64) => {
  const root = linkedFolders.get(id);
  if (!root) return jErr("No folder is linked.");
  const full = safeJoin(root, relativePath);
  if (!full) return jErr("That path is outside the linked folder.");
  const buf = Buffer.from(String(base64 || ""), "base64");
  try {
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, buf);
    return j({ ok: true, path: relativePath, size: buf.length });
  } catch (e) {
    // A read-only attribute is the common cause on Windows, and it is easy to
    // clear, so do that rather than reporting a failure the user cannot act on.
    // Writing to a file marked read-only is permitted once the flag is removed -
    // the attribute is a convention, not a permission.
    if (e.code === "EPERM" || e.code === "EACCES") {
      try {
        fs.chmodSync(full, 0o666);
        fs.writeFileSync(full, buf);
        return j({ ok: true, path: relativePath, size: buf.length, clearedReadOnly: true });
      } catch (e2) {
        return jErr(explainWriteFailure(relativePath, full, e2));
      }
    }
    return jErr(explainWriteFailure(relativePath, full, e));
  }
});

/**
 * Turn a write failure into something that says what to do about it.
 *
 * "EPERM: operation not permitted" names neither the file's state nor a remedy,
 * and the reported case - a file with the read-only attribute - looked like a
 * fault in the app.
 */
function explainWriteFailure(relativePath, full, e) {
  const code = e.code || "";
  if (code === "EPERM" || code === "EACCES") {
    let locked = "";
    try {
      // Present but unwritable after clearing the flag means something else holds
      // it - an editor, a sync client, or a scanner.
      if (fs.existsSync(full)) {
        const st = fs.statSync(full);
        locked = st.isFile() ? " The file may be open in another program." : "";
      }
    } catch (e2) {
      /* the stat is only for the hint */
    }
    return (
      `Could not write ${relativePath}: Windows refused the write even after ` +
      `clearing the read-only attribute.${locked}`
    );
  }
  return `Could not write ${relativePath}: ${e.message}`;
}

onSync("workspace:deleteFile", (id, relativePath) => {
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

onSync("workspace:openFile", (id, relativePath, mimeType, contentBase64) => {
  const root = id ? linkedFolders.get(id) : null;
  try {
    let target;
    if (root) {
      target = safeJoin(root, relativePath);
      if (!target) return jErr("That path is outside the linked folder.");
    } else {
      // No folder linked: write the workspace copy somewhere temporary so the
      // default application has a real file to open.
      const dir = path.join(app.getPath("temp"), "deepseek-code-preview");
      fs.mkdirSync(dir, { recursive: true });
      target = path.join(dir, path.basename(relativePath));
      fs.writeFileSync(target, Buffer.from(String(contentBase64 || ""), "base64"));
    }
    // Handing the path to the OS rather than awaiting it: the file exists and
    // opening it is the shell's business, so there is nothing to wait for.
    shell.openPath(target).catch((e) => log(`Could not open ${relativePath}: ${e}`));
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
    title: "DeepSeek Code",
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

/**
 * Show the DeepSeek view for the duration of the copy, with the client UI over it.
 *
 * Chromium only renders a view it considers visible, and the site's transcript is
 * a virtualised list - so a view that is not visible renders no rows and there is
 * nothing to copy. Android solves this by keeping the hidden tab INVISIBLE rather
 * than GONE; here the equivalent is to give the view real bounds and put the Web UI
 * on top, so the page is genuinely on screen while the user keeps looking at the
 * client.
 *
 * The z-order is the whole trick: both views occupy the same rectangle, and the
 * one added last sits on top. The client UI is raised for the duration and the
 * DeepSeek view raised again afterwards.
 *
 * Returns a function that restores the previous arrangement, so the restore runs
 * even if the copy throws.
 */
function parkOtherViewsForCopy() {
  const previous = activeView;

  if (!deepseekView || !webUiView || !lastBounds) {
    // Nothing laid out yet; the copy will fall back to the captured buffer.
    return () => {};
  }

  try {
    // Both views get the same real rectangle.
    deepseekView.setBounds(lastBounds);
    webUiView.setBounds(lastBounds);

    // The client UI on top, so the DeepSeek page renders underneath unseen.
    if (previous === "webui") {
      mainWindow.contentView.addChildView(webUiView);
      mainWindow.contentView.addChildView(deepseekView);
      mainWindow.contentView.addChildView(webUiView);
    } else {
      // The user is on another tab: put the client UI over the DeepSeek view so
      // the screen does not change, then restore the tab they were on.
      mainWindow.contentView.addChildView(deepseekView);
      mainWindow.contentView.addChildView(webUiView);
    }
  } catch (e) {
    log(`Could not overlay the views for the copy: ${e.message}`);
    return () => {};
  }

  return () => {
    try {
      // Hand the layout back to the tab the user was on.
      setActiveView(previous);
    } catch (e) {
      /* the window may be closing */
    }
  };
}

/**
 * Drive the page's own copy control and return what it put on the clipboard.
 *
 * The sequence, ported from Android where it was measured into existence:
 * scroll to the bottom (an off-screen reply is not in the DOM at all), find the
 * control, click it, then read the clipboard.
 *
 * The click is a real synthetic one at the control's coordinates. A programmatic
 * .click() on a hover-gated control does nothing - that was measured on Android,
 * seven buttons clicked with the clipboard hooked and nothing written.
 */
async function copyLastReplyViaButton(wc) {
  try {
    const scrolled = await wc.executeJavaScript(
      "window.__dsPage && window.__dsPage.scrollTranscriptToBottom()",
      true
    );
    // Give the virtualised list a moment to render the rows it just scrolled to.
    await new Promise((r) => setTimeout(r, 400));

    // Hover separately, then let the page render before searching.
    //
    // The site reveals its hover controls on React's next render. Hovering and
    // searching in one call meant the search could run before the control existed,
    // which fits the report that the newest message fails - its controls sit
    // against the composer and are the last to appear.
    await wc.executeJavaScript(
      "window.__dsPage && window.__dsPage.hoverRows()",
      true
    );
    await new Promise((r) => setTimeout(r, 250));

    let located = await wc.executeJavaScript(
      "window.__dsPage ? window.__dsPage.locateMessageCopyButton() : {ok:false,error:'no hook'}",
      true
    );

    // One retry, for a list still settling after the scroll.
    if (!located || !located.ok) {
      await new Promise((r) => setTimeout(r, 400));
      located = await wc.executeJavaScript(
        "window.__dsPage ? window.__dsPage.locateMessageCopyButton() : {ok:false,error:'no hook'}",
        true
      );
    }

    if (!located || !located.ok) {
      log(
        `copy: could not find the control (${(located && located.error) || "unknown"}); ` +
          `scrolled=${scrolled}`
      );
      return "";
    }
    log(`copy: control at ${located.x},${located.y} (row ${located.row}, scrolled=${scrolled})`);

    // Record the clipboard first, so a stale value can be told from a fresh one.
    const before = clipboard.readText();

    wc.sendInputEvent({ type: "mouseDown", x: located.x, y: located.y, button: "left", clickCount: 1 });
    wc.sendInputEvent({ type: "mouseUp", x: located.x, y: located.y, button: "left", clickCount: 1 });

    // The page writes asynchronously; poll briefly rather than guessing a delay.
    for (let i = 0; i < 20; i++) {
      await new Promise((r) => setTimeout(r, 100));
      const now = clipboard.readText();
      if (now && now !== before) {
        log(`copy: the clipboard changed after ${(i + 1) * 100}ms`);
        return now;
      }
    }
    log(
      `copy: the clipboard never changed at that point ` +
        `(was ${before.length} chars, still ${clipboard.readText().length})`
    );
    return "";
  } catch (e) {
    log(`copy: ${e.message}`);
    return "";
  }
}

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

  /**
   * The most recent reply, read without re-running it.
   *
   * Two sources, in the order Android uses:
   *
   *  1. The page's own copy control, driven by a real synthetic click. This works
   *     for ANY conversation on screen, including one opened from the sidebar -
   *     which is the case that matters, because the hook's buffer only fills when
   *     this app sent the prompt.
   *
   *  2. The hook's buffer, for when the click finds no control - a reply still
   *     streaming, or a page whose layout has moved on from the measured one.
   *
   * The clipboard is read through Electron rather than the page's API: a
   * clipboard read from page script needs transient activation and a focused
   * document, and neither is dependable inside a WebContentsView.
   */
  async lastReply() {
    if (!deepseekView || deepseekView.webContents.isDestroyed()) {
      throw new Error("The DeepSeek page is not available.");
    }
    const wc = deepseekView.webContents;

    // The page must be able to render its transcript, and a parked view has no
    // size, so bring it on screen for the duration.
    const restore = parkOtherViewsForCopy();
    try {
      const copied = await copyLastReplyViaButton(wc);
      if (copied) {
        log(`/last: took ${copied.length} chars from the page's copy control`);
        return { content: copied, reasoning: "" };
      }
      // Nothing to copy. Find out why, so the caller can say something useful
      // rather than appearing to do nothing.
      const state = await wc.executeJavaScript(
        [
          "(function(){",
          "  var rows = document.querySelectorAll('.ds-virtual-list-visible-items > *').length;",
          "  var onChat = /\\/a\\/chat\\/s\\//.test(location.pathname);",
          "  return JSON.stringify({ url: location.href, onChat: onChat, rows: rows });",
          "})()",
        ].join("\n"),
        true
      ).catch(() => null);

      const buffered = await wc.executeJavaScript(
        "window.__dsPage ? window.__dsPage.lastReply() : null",
        true
      );
      if (buffered && (buffered.content || buffered.reasoning)) {
        log(`/last: fell back to the captured stream (${(buffered.content || "").length} chars)`);
        return buffered;
      }

      // The remedy the user asked for. The page can end up in a state where the
      // newest message's controls are not reachable - they sit against the
      // composer and are the last to appear - and a restart clears it.
      let reason =
        "Could not read the newest message. Please restart the app and try again.";
      try {
        const st = JSON.parse(state || "{}");
        if (!st.onChat) {
          reason =
            "No conversation is open on the DeepSeek tab. Open one from its " +
            "sidebar, then fetch again.";
        } else if (!st.rows) {
          reason =
            "The conversation is open but has not finished rendering. " +
            "Please restart the app and try again.";
        }
      } catch (e) {
        /* keep the restart message */
      }
      log(`/last: ${reason}`);
      throw new Error(reason);
    } finally {
      restore();
    }
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
