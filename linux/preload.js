// Preload for the DeepSeek page.
//
// IMPORTANT: with contextIsolation on, this runs in an ISOLATED world. Patching
// XMLHttpRequest here patches the isolated copy, which the page never uses - the
// hook installed successfully and captured nothing. The stream hook therefore
// lives in hook.js and is injected into the PAGE's own world by the main
// process; all this preload does is relay captured text back over IPC.

const { ipcRenderer } = require("electron");

/**
 * Receive captured text from the page-world hook and forward it to the main
 * process, tagged with the request it belongs to.
 */
window.addEventListener("message", (event) => {
  // Only messages this app posts, from this frame.
  if (event.source !== window) return;
  const data = event.data;
  if (!data || data.__dsBridge !== true) return;

  if (data.kind === "delta") {
    ipcRenderer.send("page:delta", {
      channelId: data.channelId,
      delta: data.isReasoning ? { reasoning: data.text } : { content: data.text },
    });
  } else if (data.kind === "done") {
    ipcRenderer.send("page:delta", { channelId: data.channelId, done: true });
  } else if (data.kind === "log") {
    ipcRenderer.send("page:log", data.text);
  }
});

// Nothing else is exposed: the main process drives the page with
// executeJavaScript in the page world, where the hook's API actually lives.
