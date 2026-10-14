// Preload for the client UI page.
//
// Exposes the same bridge the Android app provides - AndroidWorkspace and
// AndroidLocalProvider - so the website needs no knowledge of which platform it
// is running on. Every feature the page has (Link Folder, Open, the workspace
// tree) works here exactly as it does on the phone, because the page sees the
// same methods with the same names.
//
// The implementation differs: Android uses the Storage Access Framework, where
// the user grants access to a folder and the app works through content:// URIs.
// A desktop app needs none of that - it runs as the user and can read the
// filesystem directly - so a folder here is just a path.
//
// contextIsolation stays ON. These objects are handed to the page deliberately,
// through contextBridge, rather than by weakening isolation; the page gets the
// handful of methods listed here and nothing else, no Node and no ipcRenderer.

const { contextBridge, ipcRenderer } = require("electron");

// Every call crosses to the main process, which does the filesystem work. The
// results are JSON strings, matching the Android bridge's shape so the page's
// existing parsing works unchanged.
const invoke = (channel, ...args) => ipcRenderer.invoke(channel, ...args);

contextBridge.exposeInMainWorld("AndroidWorkspace", {
  /** Open the system folder picker and remember the choice. */
  pickFolder: () => invoke("workspace:pickFolder"),

  /**
   * Re-attach to a folder after a restart.
   *
   * Android must re-assert a saved permission here; a desktop app only needs to
   * confirm the path still exists, which the main process does.
   */
  restoreFolder: (id, pathOrUri) => invoke("workspace:restoreFolder", id, pathOrUri),

  /** Every file in the folder, recursively, as relative paths. */
  listFiles: (id) => invoke("workspace:listFiles", id),

  /** One file, base64 encoded so binary content survives the crossing. */
  readFile: (id, relativePath) => invoke("workspace:readFile", id, relativePath),

  /** Write a file, creating parent folders as needed. */
  writeFile: (id, relativePath, base64) =>
    invoke("workspace:writeFile", id, relativePath, base64),

  /** Delete a file inside the folder. */
  deleteFile: (id, relativePath) => invoke("workspace:deleteFile", id, relativePath),

  /** Hand a file to the system's default application. */
  openFile: (id, relativePath, mimeType, contentBase64) =>
    invoke("workspace:openFile", id, relativePath, mimeType, contentBase64),
});

// The page checks for this before showing the fetch-last button, and uses it to
// decide that a real page is being driven. It has no methods of its own; the
// provider record it would seed is written directly by the main process.
contextBridge.exposeInMainWorld("AndroidLocalProvider", {});
