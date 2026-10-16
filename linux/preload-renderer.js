// Renderer preload: the only bridge between the app chrome and the main process.
//
// contextIsolation is on, so this explicitly enumerates what the page may call
// rather than handing over ipcRenderer.
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("appApi", {
  getState: () => ipcRenderer.invoke("state:get"),
  getUrls: () => ipcRenderer.invoke("urls:get"),

  setApiEnabled: (enabled) => ipcRenderer.invoke("api:setEnabled", enabled),
  restart: () => ipcRenderer.invoke("api:restart"),
  clearLog: () => ipcRenderer.invoke("log:clear"),
  reloadDeepSeek: () => ipcRenderer.invoke("page:reload"),

  /** Tell the main process which remote view is on screen and where. */
  setView: (name) => ipcRenderer.send("view:set", name),
  setViewBounds: (bounds) => ipcRenderer.send("view:bounds", bounds),

  onState: (cb) => ipcRenderer.on("state:update", (_e, s) => cb(s)),
});
