// Renderer: the app chrome and the Controls panel.
//
// The two remote pages are WebContentsViews created by the main process and
// positioned over the matching host element here. They are not iframes: an
// iframe cannot carry a preload script, and the DeepSeek page needs one to
// capture the completion stream.

const api = window.appApi;

const hosts = {
  deepseek: document.getElementById("host-deepseek"),
  webui: document.getElementById("host-webui"),
};
const controlsView = document.getElementById("view-controls");

// Opens on the client UI, matching Android, whose pager starts at page 1.
// The main process starts there too, so the highlighted tab and the view on
// screen agree from the first frame.
let currentView = "webui";

function showView(name) {
  currentView = name;

  for (const btn of document.querySelectorAll(".tab")) {
    btn.classList.toggle("active", btn.dataset.view === name);
  }

  hosts.deepseek.classList.toggle("visible", name === "deepseek");
  hosts.webui.classList.toggle("visible", name === "webui");
  controlsView.classList.toggle("visible", name === "controls");

  // Tell the main process which view is active and where it goes, in one go.
  // Sending these separately let it apply bounds to the wrong view.
  api.setView(name);
  layoutViews();
}

/**
 * Tell the main process where the active host sits.
 *
 * The remote pages are native rectangles, so any resize or tab change has to
 * push new bounds or they drift away from their placeholder. The view name is
 * included so the main process cannot apply them to the inactive view.
 */
function layoutViews() {
  const host = hosts[currentView];
  if (!host) return;
  const r = host.getBoundingClientRect();
  api.setViewBounds({
    view: currentView,
    x: Math.round(r.left),
    y: Math.round(r.top),
    width: Math.round(r.width),
    height: Math.round(r.height),
  });
}

for (const btn of document.querySelectorAll(".tab")) {
  btn.addEventListener("click", () => showView(btn.dataset.view));
}

window.addEventListener("resize", layoutViews);
window.addEventListener("scroll", layoutViews, true);

// -- controls ----------------------------------------------------------------

const el = {
  toggle: document.getElementById("api-toggle"),
  url: document.getElementById("api-url"),
  server: document.getElementById("st-server"),
  transport: document.getElementById("st-transport"),
  error: document.getElementById("st-error"),
  log: document.getElementById("log"),
  logCount: document.getElementById("log-count"),
  restart: document.getElementById("btn-restart"),
  reload: document.getElementById("btn-reload"),
  clear: document.getElementById("btn-clear"),
};

function render(s) {
  el.toggle.checked = s.apiEnabled;
  // The URL comes from the main process so the display can never disagree with
  // the port actually bound.
  el.url.textContent = s.apiUrl || `http://127.0.0.1:${s.apiPort}`;
  el.server.textContent = s.serverRunning ? "running" : "stopped";
  el.transport.textContent = s.transport;
  el.error.textContent = s.lastError || "none";
  el.logCount.textContent = s.log.length;
  el.log.textContent = s.log.join("\n");
  el.log.scrollTop = el.log.scrollHeight;
}

el.toggle.addEventListener("change", async () => {
  render(await api.setApiEnabled(el.toggle.checked));
});
el.restart.addEventListener("click", async () => {
  render(await api.restart());
});
el.reload.addEventListener("click", () => api.reloadDeepSeek());
el.clear.addEventListener("click", async () => {
  render(await api.clearLog());
});

api.onState(render);

// Initial paint.
api.getState().then(render);
api.getUrls().then((u) => {
  document.title = "DeepSeek Code";
  void u;
});

// The window can be resized before the first layout settles.
setTimeout(() => showView("deepseek"), 60);
