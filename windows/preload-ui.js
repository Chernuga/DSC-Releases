// Preload for the client UI page.
//
// Two jobs: seed the provider list before the page reads it, and expose the same
// bridge the Android app provides.
//
// SEEDING HAS TO HAPPEN HERE.
//
// The main process tried this with executeJavaScript on did-start-loading and
// dom-ready. Both are too late: the page runs `let settings = loadSettings()` as
// its script executes, so it had already read the old storage. The result was
// storage saying "local-standalone" while the running page used "deepseek-web"
// with an empty base URL - configured on paper, unconfigured in fact, which is
// what a fresh download saw.
//
// A preload runs before the document's scripts on every load, which is the one
// place early enough. localStorage is shared with the page, so writing it here is
// the same storage the page reads a moment later.
//
// THE BRIDGE IS SYNCHRONOUS ON PURPOSE.
//
// Android's @JavascriptInterface methods return a value directly, so the page
// calls them without awaiting:
//
//     picked = JSON.parse(bridge.pickFolder());
//
// A contextBridge method returns a Promise. JSON.parse(Promise) throws, which is
// why linking a folder failed here with "Folder picker failed" even though the
// picker itself had worked. ipcRenderer.sendSync blocks for the real value, so
// these methods behave exactly like Android's and the page runs unchanged.
//
// contextIsolation stays ON: the page gets the methods listed here and nothing
// else. No Node, no ipcRenderer, no filesystem beyond the linked folder.

const { contextBridge, ipcRenderer } = require("electron");

// -- Seed the provider list before the page's script runs ----------------------

try {
  // The record is built in the main process, which owns the port, so the two
  // cannot disagree about where the API lives.
  const provider = ipcRenderer.sendSync("workspace:localProvider");
  if (provider) {
    const KEY = "deepseek-ui-settings";
    let s = {};
    try {
      s = JSON.parse(localStorage.getItem(KEY) || "{}");
    } catch (e) {
      s = {};
    }

    // Whether this profile has ever held a provider list.
    const firstRun = !Array.isArray(s.providers) || !s.providers.length;

    if (!Array.isArray(s.providers)) s.providers = [];
    const at = s.providers.findIndex((p) => p && p.id === provider.id);
    if (at >= 0) s.providers[at] = provider;
    else s.providers.push(provider);

    // Select it on a first run, and whenever the choice is missing or still the
    // website's own default.
    const unset = !s.activeProviderId || s.activeProviderId === "deepseek-web";
    if (firstRun || unset) s.activeProviderId = provider.id;

    if (!Array.isArray(s.models) || !s.models.length) s.models = provider.models;
    s.model = s.model || "deepseek-web";

    localStorage.setItem(KEY, JSON.stringify(s));
  }
} catch (e) {
  // A storage failure must not stop the page from loading.
}

// -- The workspace bridge -------------------------------------------------------

/** Call the main process and return its result as Android would: directly. */
const call = (channel, ...args) => ipcRenderer.sendSync(channel, ...args);

contextBridge.exposeInMainWorld("AndroidWorkspace", {
  /** Open the system folder picker and remember the choice. */
  pickFolder: () => call("workspace:pickFolder"),

  /**
   * Re-attach to a folder after a restart.
   *
   * Android must re-assert a saved permission here; a desktop app only needs to
   * confirm the path still exists, which the main process does.
   */
  restoreFolder: (id, pathOrUri) => call("workspace:restoreFolder", id, pathOrUri),

  /** Every file in the folder, recursively, as relative paths. */
  listFiles: (id) => call("workspace:listFiles", id),

  /** One file, base64 encoded so binary content survives the crossing. */
  readFile: (id, relativePath) => call("workspace:readFile", id, relativePath),

  /** Write a file, creating parent folders as needed. */
  writeFile: (id, relativePath, base64) =>
    call("workspace:writeFile", id, relativePath, base64),

  /** Delete a file inside the folder. */
  deleteFile: (id, relativePath) => call("workspace:deleteFile", id, relativePath),

  /** Hand a file to the system's default application. */
  openFile: (id, relativePath, mimeType, contentBase64) =>
    call("workspace:openFile", id, relativePath, mimeType, contentBase64),
});

// The page checks for this before showing the fetch-last button, and uses it to
// decide that a real page is being driven. It has no methods of its own; the
// provider record it would seed is written above.
contextBridge.exposeInMainWorld("AndroidLocalProvider", {});
