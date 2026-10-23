// Single source of truth for values that must agree across the app.
//
// The port in particular is used in three places - the HTTP server, the
// provider record seeded into the client UI, and the Controls display - and a
// mismatch between them is invisible until a request fails. Keeping it here
// means they cannot drift.
//
// 8099 rather than 8080: the phone bridge also uses 8080, and adb port
// forwarding to a device claims that port on this machine. Choosing a
// different one lets both run at once without either failing to bind.

module.exports = {
  API_PORT: 8099,
  API_HOST: "127.0.0.1",
  DEEPSEEK_URL: "https://chat.deepseek.com",
  WEB_UI_URL: "https://chernuga.github.io/ZhiPu-Integration/",

  /**
   * Folder name for persisted data, created beside the executable.
   *
   * A portable app must not write to %APPDATA%: the whole point is that the
   * folder can be moved or copied and everything comes with it. Electron's own
   * userData path is redirected here in main.js, which also relocates the
   * cookies and localStorage the DeepSeek login lives in - so the account and
   * any page state survive a restart without any extra handling.
   */
  DATA_DIR_NAME: "DeepSeekData",

  /**
   * File, inside the data directory, holding the last DeepSeek conversation URL.
   *
   * The site puts the chat id in the path (chat.deepseek.com/a/chat/s/<uuid>), so
   * the whole URL is remembered and replayed verbatim rather than rebuilt from an
   * id - the path shape is the site's to change.
   */
  LAST_CHAT_FILE: "last-chat.txt",
};
