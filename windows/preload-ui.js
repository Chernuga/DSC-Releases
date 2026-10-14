// Preload for the client UI page.
//
// Exposes the same bridge the Android app provides - AndroidWorkspace and
// AndroidLocalProvider - so the website needs no knowledge of which platform it
// is running on.
//
// SYNCHRONOUS ON PURPOSE.
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
// The work is all local filesystem access that completes in milliseconds; the one
// genuinely slow call - the folder picker - blocks only while the user is choosing,
// which is inherent to a modal dialog.
//
// contextIsolation stays ON: the page gets the methods listed here and nothing
// else. No Node, no ipcRenderer, no filesystem beyond the linked folder.

const { contextBridge, ipcRenderer } = require("electron");

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
// provider record it would seed is written directly by the main process.
contextBridge.exposeInMainWorld("AndroidLocalProvider", {});
