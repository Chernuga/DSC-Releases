package com.example.deepseekstandalone

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap

class WebViewBridge(
    val webView: WebView,
    private val onErrorLogged: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Parser sink for the current request.
     *
     * Volatile: written on the main thread, read on the WebView JS-bridge
     * thread. Without the barrier the delivering thread could observe a stale
     * null and silently discard the payload.
     */
    @Volatile
    private var activeChunkCallback: ((String) -> Unit)? = null
    private var activeCompletionDeferred: CompletableDeferred<Boolean>? = null
    private var activeErrorCallback: ((String) -> Unit)? = null

    /** The registered document-start script, so it can be removed on teardown. */
    private var documentStartScript: androidx.webkit.ScriptHandler? = null

    /**
     * Last URL loaded, mirrored from the main thread so [isLoggedIn] can be
     * called safely from the HTTP server's own thread.
     */
    @Volatile
    private var currentUrl: String = ""

    init {
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Custom realistic User-Agent (stripping the 'wv' token)
        val defaultUserAgent = settings.userAgentString
        val customUserAgent = defaultUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
        settings.userAgentString = customUserAgent

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        webView.addJavascriptInterface(this, "AndroidBridge")

        // Install the stream hook BEFORE the page's own scripts run.
        //
        // This is the crucial timing detail: DeepSeek's bundle grabs its own
        // reference to window.fetch / XMLHttpRequest early. Wrapping them later
        // (from onPageFinished, as this used to do) does not affect an
        // already-captured reference, so the completion stream was never seen
        // and the code fell back to scraping the DOM.
        installDocumentStartHook()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url ?: ""
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentUrl = url ?: view?.url ?: currentUrl
                injectBridgeScript()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                currentUrl = url ?: currentUrl
                // DeepSeek is a SPA: sending a message moves to /a/chat/s/<id>
                // without a full page load. The document-start hook survives
                // that (same document), but the helper globals do not, so they
                // are re-installed here.
                injectBridgeScript()
            }
        }
    }

    /**
     * Register the network hook at document start.
     *
     * WebViewCompat handles the API-level split: native on API 28+, and an
     * equivalent mechanism on older versions. If the feature is unavailable the
     * page-level hook installed from onPageFinished still applies, so this is
     * an upgrade rather than a hard requirement.
     */
    private fun installDocumentStartHook() {
        try {
            val feature = WebViewFeature.DOCUMENT_START_SCRIPT
            if (!WebViewFeature.isFeatureSupported(feature)) {
                onErrorLogged("Document-start injection unsupported; falling back to page-level hook")
                return
            }
            val result = WebViewCompat.addDocumentStartJavaScript(
                webView,
                streamHookScript(),
                setOf("https://chat.deepseek.com")
            )
            documentStartScript = result
        } catch (e: Exception) {
            onErrorLogged("Document-start hook failed: ${e.message}")
        }
    }

    /** Removes the document-start hook, if one was registered. */
    private fun removeDocumentStartHook() {
        try {
            documentStartScript?.remove()
        } catch (ignored: Exception) {
        }
        documentStartScript = null
    }

    /**
     * The network-level hook: tee the SSE body on its way to the page.
     *
     * Deliberately narrow - it only observes, never modifies. The original
     * response is returned untouched so the page behaves exactly as before.
     */
    private fun streamHookScript(): String = """
        (function () {
          if (window.__dsStreamHook === true) return;
          window.__dsStreamHook = true;

          function endpoint(u) {
            u = String(u || '');
            // The live path is /api/v0/chat/completion (singular).
            return u.indexOf('/api/v0/chat/completion') >= 0;
          }

          function forward(text) {
            try {
              if (window.AndroidBridge && window.AndroidBridge.onStreamChunk) {
                window.AndroidBridge.onStreamChunk(text);
              }
            } catch (e) { /* bridge gone */ }
          }

          // Temporary: surface hook activity so failures are observable.
          function hookLog(msg) {
            try {
              if (window.AndroidBridge && window.AndroidBridge.onHookLog) {
                window.AndroidBridge.onHookLog(String(msg));
              }
            } catch (e) { /* bridge gone */ }
          }

          function done() {
            try {
              if (window.AndroidBridge && window.AndroidBridge.onStreamDone) {
                window.AndroidBridge.onStreamDone();
              }
            } catch (e) { /* bridge gone */ }
          }

          // Report the request body the page actually sent. This is ground
          // truth for the DeepThink / Search flags: no guessing at button
          // classes or aria attributes, because the site serialised its own
          // state into the payload.
          function reportRequest(body) {
            try {
              if (body && window.AndroidBridge && window.AndroidBridge.onStreamRequest) {
                window.AndroidBridge.onStreamRequest(String(body));
              }
            } catch (e) { /* bridge gone */ }
          }

          function bodyOf(args) {
            try {
              var init = args[1];
              if (init && init.body) {
                if (typeof init.body === 'string') return init.body;
                return null; // non-string bodies are not our requests
              }
              var a0 = args[0];
              if (a0 && typeof a0 === 'object' && a0.body && typeof a0.body === 'string') {
                return a0.body;
              }
            } catch (e) { /* ignore */ }
            return null;
          }

          // ---- fetch -------------------------------------------------------
          // Assigning over window.fetch can fail if the property is non-writable
          // on this WebView build; report the outcome instead of failing mutely.
          var origFetch = window.fetch;
          if (origFetch) {
            try {
              // Use a non-configurable accessor so the page cannot silently
              // replace the wrapper. A plain assignment is overwritten when
              // DeepSeek's bundle re-installs its own fetch after document
              // start, which is why the patch disappeared.
              var wrapped = function () {
              var args = arguments;
              var url = '';
              try {
                url = (typeof args[0] === 'string') ? args[0]
                    : (args[0] && args[0].url) ? args[0].url : '';
              } catch (e) { url = ''; }

              var p = origFetch.apply(this, args);
              if (!endpoint(url)) return p;

              // Record the flags the page chose (thinking_enabled / search_enabled).
              reportRequest(bodyOf(args));

              return p.then(function (res) {
                try {
                  var clone = res.clone();
                  if (clone.body && clone.body.getReader) {
                    var reader = clone.body.getReader();
                    var dec = new TextDecoder('utf-8');
                    (function pump() {
                      reader.read().then(function (r) {
                        if (r.done) { done(); return; }
                        forward(dec.decode(r.value, { stream: true }));
                        pump();
                      }).catch(function () { done(); });
                    })();
                  }
                } catch (e) { hookLog('tee failed: ' + e); }
                return res;
              });
            };

            // Keep our wrapper in place even if the page re-assigns fetch:
            // the setter re-applies the wrapper transparently.
            try {
              var current = wrapped;
              Object.defineProperty(window, 'fetch', {
                configurable: true,
                get: function () { return current; },
                set: function (v) {
                  // The page is installing its own fetch; keep calling through
                  // our wrapper but delegate to the new implementation, so both
                  // the page and the tee see every request.
                  hookLog('fetch reassigned by page; re-wrapping');
                  var next = v;
                  current = function () {
                    var args = arguments;
                    var url = '';
                    try {
                      url = (typeof args[0] === 'string') ? args[0]
                          : (args[0] && args[0].url) ? args[0].url : '';
                    } catch (e) { url = ''; }
                    var p2 = next.apply(this, args);
                    if (!endpoint(url)) return p2;
                    reportRequest(bodyOf(args));
                    return p2.then(function (res) {
                      try {
                        var cl = res.clone();
                        if (cl.body && cl.body.getReader) {
                          var rd = cl.body.getReader();
                          var dc = new TextDecoder('utf-8');
                          (function pump2() {
                            rd.read().then(function (r) {
                              if (r.done) { done(); return; }
                              forward(dc.decode(r.value, { stream: true }));
                              pump2();
                            }).catch(function () { done(); });
                          })();
                        }
                      } catch (e) {}
                      return res;
                    });
                  };
                }
              });
            } catch (e) {
              window.fetch = wrapped;
              hookLog('defineProperty failed, plain assign: ' + e);
            }
            hookLog('install: patched=' + (window.fetch !== origFetch));
            } catch (e) {
              hookLog('install failed: ' + e);
            }
          }

          // ---- XMLHttpRequest ---------------------------------------------
          // DeepSeek has used XHR for this endpoint historically, so both
          // transports are covered.
          var origOpen = XMLHttpRequest.prototype.open;
          var origSend = XMLHttpRequest.prototype.send;

          XMLHttpRequest.prototype.open = function (method, url) {
            try {
              this.__dsUrl = url;
            } catch (e) {}
            return origOpen.apply(this, arguments);
          };

          XMLHttpRequest.prototype.send = function (body) {
            var self = this;
            try {
              if (endpoint(self.__dsUrl) && !self.__dsHooked) {
                self.__dsHooked = true;
                if (typeof body === 'string') reportRequest(body);
                var last = 0;
                self.addEventListener('progress', function () {
                  // responseText throws on some responseType values, so the
                  // read is guarded rather than assumed.
                  try {
                    var t = self.responseText || '';
                    if (t.length > last) {
                      forward(t.substring(last));
                      last = t.length;
                    }
                  } catch (e) { /* not readable as text */ }
                });
                self.addEventListener('loadend', function () {
                  // This hook owns completion: the page-level hook stands down
                  // when the document-start hook is active, so nothing else
                  // would signal the end of the request.
                  try {
                    if (window.AndroidBridge && window.AndroidBridge.onCompletionDone) {
                      window.AndroidBridge.onCompletionDone();
                    }
                  } catch (e) { /* bridge gone */ }
                  done();
                });
              }
            } catch (e) {}
            return origSend.apply(this, arguments);
          };
        })();
    """.trimIndent()

    fun loadUrl(url: String = "https://chat.deepseek.com") {
        handler.post {
            webView.loadUrl(url)
        }
    }

    /** Diagnostic: is the network hook actually present in the page? */
    suspend fun hookPresence(): String = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              var xhrPatched = ('' + XMLHttpRequest.prototype.open).indexOf('__dsXhr') >= 0;
              return JSON.stringify({
                streamHookFlag: window.__dsStreamHook === true,
                fetchPatched: ('' + window.fetch).indexOf('__dsStreamHook') >= 0,
                fetchIsWrapped: !!(window.fetch && window.fetch.__dsWrapped),
                xhrOpenPatched: xhrPatched,
                xhrSendSource: ('' + XMLHttpRequest.prototype.send).slice(0, 120),
                href: location.href,
                hasAndroidBridge: !!(window.AndroidBridge && window.AndroidBridge.onStreamChunk)
              });
            })();
        """.trimIndent()
        evalJsAwait(script)
    }

    /**
     * Is the page offering a "Continue" control?
     *
     * DeepSeek cuts a long answer off and shows a Continue button. When that
     * happens the reply the client received is a prefix, and saying so is
     * better than presenting it as complete.
     *
     * Detection is deliberately broad - the label, the aria text and a few
     * known class hints - because the control's markup has changed across site
     * versions, and a false negative here silently reports a truncated answer
     * as finished.
     */
    suspend fun isContinueOffered(): Boolean = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              try {
                var nodes = document.querySelectorAll('button, div[role="button"], [class*="continue"]');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  var txt = ((el.innerText || el.textContent || '') + ' ' +
                             (el.getAttribute('aria-label') || '')).trim().toLowerCase();
                  if (!txt) continue;
                  // Only a short label counts; a paragraph mentioning
                  // "continue" is prose, not the control.
                  if (txt.length > 40) continue;
                  if (txt.indexOf('continue') >= 0 || txt.indexOf('resume') >= 0) {
                    if (el.offsetParent !== null) return true;
                  }
                }
                return false;
              } catch (e) { return false; }
            })();
        """.trimIndent()
        evalJsAwait(script).trim() == "true"
    }

    /**
     * Scroll the transcript to the bottom and reveal the newest reply.
     *
     * The site renders its messages in a virtualised list, so an off-screen
     * message is not in the DOM at all. Scrolling is therefore a prerequisite
     * for reading anything, not an optimisation.
     *
     * Returns the scroll container's geometry so the caller knows where to look.
     */
    suspend fun scrollTranscriptToBottom(): Boolean = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              try {
                // Every scrollable ancestor of the message list, plus any
                // element the site marks as a scroll area.
                var candidates = [];
                var list = document.querySelector('.ds-virtual-list-items');
                var node = list;
                while (node && node !== document.body) {
                  var s = getComputedStyle(node);
                  if (/(auto|scroll)/.test(s.overflowY) && node.scrollHeight > node.clientHeight) {
                    candidates.push(node);
                  }
                  node = node.parentElement;
                }
                var marked = document.querySelectorAll('.ds-scroll-area, [class*="gutter"]');
                for (var i = 0; i < marked.length; i++) candidates.push(marked[i]);

                var scrolled = 0;
                for (var j = 0; j < candidates.length; j++) {
                  var el = candidates[j];
                  if (el.scrollHeight > el.clientHeight) {
                    el.scrollTop = el.scrollHeight;
                    scrolled++;
                  }
                }
                return scrolled > 0 ? 'true' : 'false';
              } catch (e) { return 'false'; }
            })();
        """.trimIndent()
        evalJsAwait(script).trim() == "true"
    }

    /**
     * Locate the copy control for the newest assistant reply, in page pixels.
     *
     * Returns the parsed result: {ok, x, y} or {ok:false, error}. See
     * evalJsObject for why the transport quoting is stripped.
     */
    suspend fun locateMessageCopyButton(): JSONObject? = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              try {
                function svgPath(el) {
                  var p = el.querySelector('svg path');
                  return p ? (p.getAttribute('d') || '') : '';
                }

                // Rows live in .ds-virtual-list-visible-items; there are no
                // ds-message elements on the page at all (measured on the live
                // site - assuming that class is why the first attempt reported
                // "no message nodes in the DOM").
                var rows = document.querySelectorAll('.ds-virtual-list-visible-items > *');
                if (!rows.length) rows = document.querySelectorAll('.ds-virtual-list-items > *');
                if (!rows.length) {
                  return JSON.stringify({ ok: false, error: 'no message rows rendered' });
                }

                function hover(el) {
                  var r = el.getBoundingClientRect();
                  var o = { bubbles: true, cancelable: true, view: window,
                            clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
                  el.dispatchEvent(new MouseEvent('mouseover', o));
                  el.dispatchEvent(new MouseEvent('mouseenter', o));
                  el.dispatchEvent(new MouseEvent('mousemove', o));
                }

                // The copy glyph, measured by clicking every button on the page
                // with the clipboard hooked. Buttons carrying it are: the
                // per-thinking-block copies, the per-code-block copies, and -
                // last in the assistant row - the whole-message copy. Only the
                // last one yields the message, which is what is wanted.
                var COPY_GLYPH = 'M6.14929 4.02032';

                // Walk EVERY row and keep the LAST matching button overall, not
                // the last row that merely contains a thinking block. The rows
                // interleave user messages with assistant turns, and the
                // assistant turn contains many thinking-fragment controls, so
                // "last row with a think block" selected an assistant sub-block
                // and copied a fragment of the previous reply.
                var best = null;
                for (var i = 0; i < rows.length; i++) {
                  var btns = rows[i].querySelectorAll('.ds-button');
                  for (var j = 0; j < btns.length; j++) {
                    var b = btns[j];
                    var aria = (b.getAttribute('aria-label') || '') + ' ' + (b.title || '');
                    var named = /copy/i.test(aria);
                    var isGlyph = svgPath(b).indexOf(COPY_GLYPH) === 0;
                    if (!named && !isGlyph) continue;
                    if (!b.offsetParent) continue;
                    var r = b.getBoundingClientRect();
                    if (r.width < 1 || r.height < 1) continue;
                    // Later matches overwrite earlier ones, so the final
                    // assignment is the message-level control.
                    best = {
                      x: Math.round(r.left + r.width / 2),
                      y: Math.round(r.top + r.height / 2),
                      named: named,
                      row: i,
                      btn: j
                    };
                  }
                }
                if (!best) {
                  return JSON.stringify({ ok: false, error: 'no copy control found' });
                }
                best.ok = true;
                return JSON.stringify(best);
              } catch (e) {
                return JSON.stringify({ ok: false, error: String(e) });
              }
            })();
        """.trimIndent()
        evalJsObject(script)
    }

    /**
     * Recover the last assistant reply, as raw text.
     *
     * The stream hook already accumulates the exact characters the page
     * received, which is the same thing the site's copy button produces -
     * including any file-command markers, which the rendered DOM does NOT have
     * (verified: the rendered blocks carry no ``` or create:/find:/replace:
     * markers, and React's fibre is not safely walkable from injected script).
     *
     * Scraping the DOM was therefore wrong for this purpose: it returned the
     * rendered text with the commands stripped, so a recovered reply could not
     * drive file edits. The captured stream is tried first and the DOM is only a
     * fallback for when the hook saw nothing (for example a reply that arrived
     * before the app started watching).
     */
    suspend fun recoverLastAnswer(): Pair<String, String> = withContext(Dispatchers.Main) {
        val rawAnswer = streamedAnswerText()
        val rawReasoning = streamedReasoningText()

        if (rawAnswer.isNotBlank()) {
            return@withContext rawAnswer to rawReasoning
        }

        // Nothing captured this session: fall back to reading the page.
        val answer = try { readRenderedAnswer() } catch (e: Exception) { "" }
        val reasoning = try { readRenderedReasoning() } catch (e: Exception) { "" }
        answer to reasoning
    }

    /** Release the document-start hook. Call before destroying the WebView. */
    fun dispose() {
        removeDocumentStartHook()
    }

    /** Current page URL, mirrored from the main thread. */
    fun currentUrlOrDefault(): String = currentUrl

    fun isLoggedIn(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://chat.deepseek.com") ?: ""
        // Never read webView.url here. isLoggedIn() is called from NanoHTTPD's
        // request-processing thread, and every WebView method must run on the
        // main thread; touching it off-thread throws
        //   "A WebView method was called on thread 'NanoHttpd Request Processor'"
        // which surfaced to the tunnel as a 500 / unexpected end of stream.
        // currentUrl is kept up to date from the main thread instead.
        return currentUrl.contains("chat.deepseek.com") && cookies.isNotEmpty()
    }

    fun injectBridgeScript() {
        val script = injectedScript()

        handler.post {
            webView.evaluateJavascript(script, null)
        }
    }

    /** The bridge payload, factored out so it can also be awaited. */
    private fun injectedScript(): String = """
            (function() {
                if (window.__deepseek_bridge_injected__) return;
                window.__deepseek_bridge_injected__ = true;

                // Marker that the hooks below are installed. A SPA navigation
                // clears these globals, so this is the reliable readiness check.
                window.__dsXhrHooked = true;

                // Intercept Fetch API
                const originalFetch = window.fetch;
                window.fetch = async function(...args) {
                    const response = await originalFetch.apply(this, args);
                    try {
                        const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                        if (url.includes('/completion') || url.includes('/chat')) {
                            const clone = response.clone();
                            if (clone.body) {
                                const reader = clone.body.getReader();
                                const decoder = new TextDecoder('utf-8');
                                (async () => {
                                    let buffer = '';
                                    try {
                                        while (true) {
                                            const { done, value } = await reader.read();
                                            if (done) {
                                                if (window.AndroidBridge && window.AndroidBridge.onCompletionDone) {
                                                    window.AndroidBridge.onCompletionDone();
                                                }
                                                break;
                                            }
                                            buffer += decoder.decode(value, { stream: true });
                                            const lines = buffer.split('\n');
                                            buffer = lines.pop() || '';
                                            for (const line of lines) {
                                                const trimmed = line.trim();
                                                if (!trimmed) continue;
                                                let rawData = trimmed;
                                                if (trimmed.startsWith('data:')) {
                                                    rawData = trimmed.substring(5).trim();
                                                    if (rawData === '[DONE]') {
                                                        if (window.AndroidBridge && window.AndroidBridge.onCompletionDone) {
                                                            window.AndroidBridge.onCompletionDone();
                                                        }
                                                        continue;
                                                    }
                                                }
                                                if (rawData.length > 0 && window.AndroidBridge && window.AndroidBridge.onChunk) {
                                                    window.AndroidBridge.onChunk(rawData);
                                                }
                                            }
                                        }
                                    } catch (err) {
                                        if (window.AndroidBridge && window.AndroidBridge.onError) {
                                            window.AndroidBridge.onError(err.toString());
                                        }
                                    }
                                })();
                            }
                        }
                    } catch(e) {
                        console.error('Bridge fetch error', e);
                    }
                    return response;
                };

                // Intercept XMLHttpRequest fallback
                const originalOpen = XMLHttpRequest.prototype.open;
                const originalSend = XMLHttpRequest.prototype.send;
                // Guards against installing the progress listener twice on the
                // same request, which duplicated every streamed fragment.
                const hookedXhrs = new WeakSet();
                XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                    this._url = url;
                    return originalOpen.call(this, method, url, ...rest);
                };
                XMLHttpRequest.prototype.send = function(...args) {
                    // Stand down when the document-start hook is present.
                    //
                    // Both hooks match this endpoint and use DIFFERENT dedupe
                    // guards (__dsHooked vs __dsHookedXhr), so neither noticed
                    // the other and every fragment was delivered twice: once
                    // via onChunk and once via onStreamChunk. That duplication
                    // corrupted the assembled answer.
                    if (window.__dsStreamHook === true) {
                        return originalSend.apply(this, args);
                    }
                    if (this._url && (this._url.includes('/completion') || this._url.includes('/chat'))) {
                        // Hook each request exactly once. Two progress listeners
                        // on one XHR forwarded every fragment twice.
                        if (window.__dsHookedXhr && window.__dsHookedXhr.has(this)) {
                            return originalSend.call(this, args);
                        }
                        if (!window.__dsHookedXhr) window.__dsHookedXhr = new WeakSet();
                        window.__dsHookedXhr.add(this);

                        let lastIndex = 0;
                        // Payloads already forwarded for THIS request. Guards
                        // against re-reading the same response body (which
                        // multiplied every fragment many times over).
                        const seen = Object.create(null);
                        // Two framings are possible and they must not be mixed:
                        //   * SSE      -> "data: ..." lines ending with a blank line
                        //   * NDJSON   -> one bare JSON object per line
                        // A blank line ends an SSE event; only then is its
                        // buffered "data:" payload forwarded. Control lines
                        // (event:, id:, retry:) are never content.
                        let pending = '';
                        let sseData = [];
                        const forward = (payload) => {
                            // Forward each distinct payload once per request.
                            if (!payload || seen[payload]) return;
                            seen[payload] = 1;
                            if (window.AndroidBridge) window.AndroidBridge.onChunk(payload);
                        };
                        const flushEvent = () => {
                            if (sseData.length) {
                                const payload = sseData.join('\n');
                                if (payload === '[DONE]') {
                                    if (window.AndroidBridge) window.AndroidBridge.onCompletionDone();
                                } else {
                                    forward(payload);
                                }
                            }
                            sseData = [];
                        };
                        const handleLine = (line) => {
                            if (line === '') { flushEvent(); return; }
                            if (line.startsWith('data:')) {
                                sseData.push(line.substring(5).trim());
                                return;
                            }
                            if (line.startsWith('event:') || line.startsWith('id:') ||
                                line.startsWith('retry:') || line.startsWith(':')) {
                                return;
                            }
                            flushEvent();
                            forward(line);
                        };
                        this.addEventListener('progress', () => {
                            try {
                                // Never forward bytes we have already forwarded.
                                // Re-reading from a stale offset duplicated every
                                // streamed fragment (the page emitted the same
                                // content many times over).
                                if (this.responseText.length < lastIndex) {
                                    lastIndex = 0; // response shrank: restart scan
                                }
                                const newText = this.responseText.substring(lastIndex);
                                lastIndex = this.responseText.length;

                                pending += newText;
                                const lines = pending.split('\n');
                                pending = lines.pop() || '';

                                for (const line of lines) handleLine(line.trim());
                            } catch (e) {
                                // Response not readable as text; nothing to forward.
                            }
                        });
                        this.addEventListener('loadend', () => {
                            try {
                                if (pending.trim()) handleLine(pending.trim());
                                flushEvent();
                                pending = '';
                            } catch (e) { /* ignore */ }
                            if (window.AndroidBridge) window.AndroidBridge.onCompletionDone();
                        });
                    }
                    return originalSend.call(this, args);
                };

                // Global sendPrompt helper
                window.sendPrompt = function(text) {
                    try {
                        const input = document.querySelector('textarea#chat-input') ||
                                      document.querySelector('textarea') ||
                                      document.querySelector('div[contenteditable="true"]');
                        if (!input) {
                            if (window.AndroidBridge) window.AndroidBridge.onError('Chat input element not found');
                            return false;
                        }

                        if (input.tagName.toLowerCase() === 'textarea') {
                            // React/Vue track the value setter, so bypass the
                            // instance property and fire the events they listen for.
                            const proto = window.HTMLTextAreaElement.prototype;
                            const nativeSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                            input.focus();
                            if (nativeSetter) nativeSetter.call(input, text);
                            else input.value = text;
                            input.dispatchEvent(new Event('input', { bubbles: true }));
                            input.dispatchEvent(new Event('change', { bubbles: true }));
                        } else {
                            input.innerText = text;
                            input.dispatchEvent(new Event('input', { bubbles: true }));
                        }

                        // The send control is a div[role=button] carrying a
                        // ds-button--circle class. Two traps made this
                        // unreliable:
                        //   * several circle buttons exist (Stop among them),
                        //     and querySelector returned the FIRST - sometimes
                        //     the wrong one;
                        //   * .disabled does not exist on a div[role=button],
                        //     so a genuinely disabled control passed the check
                        //     and the click was swallowed.
                        // Match by position instead: the send control is the
                        // last circle button in the composer, and the Stop
                        // glyph is a square path.
                        function circleButtons() {
                            var all = document.querySelectorAll(
                              'div[role="button"][class*="ds-button--circle"], div[role="button"].ds-button--circle'
                            );
                            return Array.prototype.slice.call(all);
                        }

                        function isStopButton(el) {
                            // The stop glyph begins "M2 4.88" (a filled square).
                            var p = el.querySelector('svg path');
                            var d = p ? (p.getAttribute('d') || '') : '';
                            return d.indexOf('M2 4.88') === 0;
                        }

                        function isDisabled(el) {
                            if (!el) return true;
                            if (el.disabled === true) return true;
                            if (el.getAttribute('aria-disabled') === 'true') return true;
                            var cls = (el.className && typeof el.className === 'string') ? el.className : '';
                            // The site marks an inactive circle control with a
                            // disabled modifier class.
                            if (/disabled/i.test(cls)) return true;
                            return false;
                        }

                        // Enter is a genuine second path: the site submits on
                        // keydown, so this works even when the button is mid
                        // re-render.
                        function pressEnter() {
                            var opts = {
                                key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
                                bubbles: true, cancelable: true
                            };
                            input.dispatchEvent(new KeyboardEvent('keydown', opts));
                            input.dispatchEvent(new KeyboardEvent('keyup', opts));
                        }

                        // Keep trying for a while: React re-enables the button
                        // a moment after the text is set, and a single attempt
                        // 400ms later silently did nothing when it was late.
                        var attempts = 0;
                        var MAX_ATTEMPTS = 12;   // ~3.6s total

                        function trySend() {
                            attempts++;

                            var btns = circleButtons().filter(function (b) {
                                return !isStopButton(b);
                            });
                            // Last non-stop circle button = the send control.
                            var btn = btns.length ? btns[btns.length - 1] : null;

                            if (btn && !isDisabled(btn)) {
                                btn.click();
                                return true;
                            }

                            // Not ready yet: try Enter as a fallback path, and
                            // retry the button on the next tick.
                            if (attempts === 1) {
                                pressEnter();
                            }
                            if (attempts < MAX_ATTEMPTS) {
                                setTimeout(trySend, 300);
                                return false;
                            }

                            // Final fallback: submit the surrounding form.
                            var form = input.closest('form');
                            if (form && typeof form.requestSubmit === 'function') {
                                try { form.requestSubmit(); } catch (e) { /* ignore */ }
                            }
                            if (window.AndroidBridge && window.AndroidBridge.onError) {
                                window.AndroidBridge.onError(
                                    'Send control not ready after ' + MAX_ATTEMPTS + ' attempts'
                                );
                            }
                            return false;
                        }

                        setTimeout(trySend, 250);

                        return true;
                    } catch (e) {
                        if (window.AndroidBridge) window.AndroidBridge.onError(e.toString());
                        return false;
                    }
                };
            })();
        """.trimIndent()

    /**
     * Click the site's stop control, if present, and end any local completion
     * wait so the caller's request finishes immediately.
     */
    suspend fun stopGeneration(): Boolean = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              try {
                if (window.__dsStopOverride) { window.__dsStopOverride(); return 'local'; }
                var nodes = document.querySelectorAll('div[role="button"], button');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  var c = (el.className && typeof el.className === 'string') ? el.className : '';
                  if (c.indexOf('ds-button--circle') < 0) continue;
                  var svg = el.querySelector('svg');
                  if (!svg) continue;
                  // The send/stop control swaps icons; the stop glyph is a square.
                  var path = svg.querySelector('path');
                  var d = path ? (path.getAttribute('d') || '') : '';
                  if (d.indexOf('M2 4.88') === 0) {
                    el.click();
                    return 'clicked';
                  }
                }
                return 'notfound';
              } catch (e) { return 'error'; }
            })();
        """.trimIndent()
        // End the local wait regardless of whether the page had a stop button.
        activeCompletionDeferred?.complete(true)
        val raw = evalJsAwait(script)
        raw.contains("clicked") || raw.contains("local")
    }

    /** True when the page is currently showing a bot/human verification overlay. */
    suspend fun isVerificationShowing(): Boolean = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              try {
                var t = (document.body && document.body.innerText) || '';
                t = t.toLowerCase();
                return (t.indexOf('one more step') >= 0 ||
                        t.indexOf('before you proceed') >= 0 ||
                        t.indexOf('verify you are human') >= 0) ? 'true' : 'false';
              } catch (e) { return 'false'; }
            })();
        """.trimIndent()
        evalJsAwait(script).contains("true")
    }

    /**
     * Reload the page to clear a verification interstitial, then wait for the
     * composer to come back. This is a plain reload - it does not attempt to
     * solve or bypass the challenge.
     */
    suspend fun reloadAndWait(): Boolean = withContext(Dispatchers.Main) {
        webView.reload()
        var ready = false
        for (i in 1..20) {
            kotlinx.coroutines.delay(1000L)
            val ok = evalJsAwait(
                "(document.querySelector('textarea') ? 'true' : 'false')"
            ).contains("true")
            if (ok) { ready = true; break }
        }
        if (ready) ensureBridgeInjected()
        ready
    }

    /** Run JS on the WebView and await its result. */
    private suspend fun evalJsAwait(script: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value ?: "null") { _, _, _ -> }
            }
        }

    /**
     * Evaluate a script that returns a JSON *string* and parse it.
     *
     * WebView.evaluateJavascript JSON-encodes whatever the script returns, so a
     * script ending in JSON.stringify(...) hands back a quoted string, not an
     * object. Passing that straight to JSONObject throws
     * "Value \"...\" of type java.lang.String cannot be converted to JSONObject",
     * which is exactly how the first copy attempt failed. The outer layer is
     * removed here so callers get the object they asked for.
     *
     * Returns null when the script failed, returned null, or produced something
     * that is not a JSON object.
     */
    suspend fun evalJsObject(script: String): JSONObject? = withContext(Dispatchers.Main) {
        val raw = evalJsAwait(script)
        if (raw.isBlank() || raw == "null") return@withContext null
        val text = try {
            // Unwrap the transport quoting.
            if (raw.startsWith("\"")) JSONTokener(raw).nextValue() as? String ?: raw else raw
        } catch (e: Exception) {
            raw
        }
        try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }


    /**
     * Read the newest assistant answer straight out of the rendered page.
     *
     * The completion XHR only delivers metadata envelopes to the page's own JS
     * (session ids, model type); the streamed answer patches are consumed
     * internally and never surface through the hooks. The rendered DOM is the
     * authoritative result and is independent of how the site transports it.
     *
     * Returns the full answer text, or "" when no answer is rendered yet.
     */
    /**
     * TEMPORARY diagnostic: report the real transcript structure and which node
     * the answer scraper actually selects.
     *
     * Every previous attempt to fix the "one turn behind" bug was verified
     * against a DOM I had assumed rather than the live one, which is why the
     * fixes did not hold. This exposes the ground truth.
     */
    suspend fun debugTranscript(): String = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              function cls(e){ return (e && e.className && typeof e.className === 'string') ? e.className : ''; }
              function txt(e){ return ((e && (e.innerText || e.textContent)) || '').trim(); }
              function short(s, n){ s = s || ''; return s.length > n ? s.slice(0, n) + 'â€¦' : s; }

              var out = { counts: {}, messages: [], pickedAnswer: null, pickedReasoning: null };

              out.counts.dsMessage = document.querySelectorAll('[class*="ds-message"]').length;
              out.counts.assistantContent = document.querySelectorAll('[class*="ds-assistant-message-main-content"]').length;
              out.counts.thinkContent = document.querySelectorAll('[class*="ds-think-content"]').length;
              out.counts.markdown = document.querySelectorAll('[class*="ds-markdown"]').length;
              out.counts.bubbleText = document.querySelectorAll('[class*="bubble-text"]').length;

              // Every ds-message, with what it holds.
              var msgs = document.querySelectorAll('[class*="ds-message"]');
              for (var i = 0; i < msgs.length && i < 25; i++) {
                var m = msgs[i];
                var ans = m.querySelector('[class*="ds-assistant-message-main-content"]');
                var think = m.querySelector('[class*="ds-think-content"]');
                var bubble = m.querySelector('[class*="bubble-text"]');
                out.messages.push({
                  i: i,
                  cls: short(cls(m), 70),
                  isUser: !!bubble,
                  userText: bubble ? short(txt(bubble), 40) : null,
                  hasAnswer: !!ans,
                  answer: ans ? short(txt(ans), 60) : null,
                  hasThink: !!think,
                  think: think ? short(txt(think), 50) : null
                });
              }

              // Which node does the CURRENT scraper pick?
              for (var k = msgs.length - 1; k >= 0; k--) {
                var mm = msgs[k];
                if (mm.querySelector('.bubble-text, [class*="bubble-text"]')) continue;
                var node = mm.querySelector('[class*="ds-assistant-message-main-content"]');
                if (!node) continue;
                var t = txt(node);
                if (t) { out.pickedAnswer = { index: k, text: short(t, 70) }; break; }
              }
              for (var r = msgs.length - 1; r >= 0; r--) {
                var rm = msgs[r];
                if (rm.querySelector('.bubble-text, [class*="bubble-text"]')) continue;
                var rn = rm.querySelector('[class*="ds-think-content"]');
                if (!rn) continue;
                var rt = txt(rn);
                if (rt) { out.pickedReasoning = { index: r, text: short(rt, 70) }; break; }
              }

              // The last user bubble, so we can tell whether we answered it.
              var bubbles = document.querySelectorAll('[class*="bubble-text"]');
              if (bubbles.length) out.lastUserText = short(txt(bubbles[bubbles.length - 1]), 60);

              return JSON.stringify(out);
            })();
        """.trimIndent()
        evalJsAwait(script)
    }

    /**
     * Compare innerText (layout-aware, viewport-limited) against textContent
     * (full DOM text) to test whether an un-scrolled transcript is what
     * truncates long answers.
     */
    suspend fun debugAnswerText(): String = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              $blockTextFn
              function txtInfo(el){
                if (!el) return null;
                var i = (((el.innerText) || '')).trim();
                var c = (((el.textContent) || '')).trim();
                // The structure-preserving output, which is what ships.
                var b = __dsBlockText(el);
                return {
                  innerLen: i.length, textLen: c.length, blockLen: b.length,
                  blockLines: b.split('\n').length,
                  blockSample: b.slice(0, 400),
                  innerTail: i.slice(-60), textTail: c.slice(-60)
                };
              }
              function lastAnswer(){
                var ms = document.querySelectorAll('[class*="ds-message"]');
                for (var i = ms.length - 1; i >= 0; i--) {
                  if (ms[i].querySelector('.bubble-text, [class*="bubble-text"]')) continue;
                  var n = ms[i].querySelector('[class*="ds-assistant-message-main-content"]');
                  if (n) return n;
                }
                return null;
              }
              var el = lastAnswer();
              var sc = document.querySelector('[class*="ds-virtual-list"], [class*="scroll"]');
              return JSON.stringify({
                answer: txtInfo(el),
                scrollTop: sc ? sc.scrollTop : null,
                scrollHeight: sc ? sc.scrollHeight : null,
                clientHeight: sc ? sc.clientHeight : null
              });
            })();
        """.trimIndent()
        evalJsAwait(script)
    }

    private val blockTextFn: String = """
        // Structure-preserving text extraction, injected into the scrapers.
        //
        // textContent has no layout information, so it inserts no line breaks
        // between block elements. Collapsing all whitespace therefore merged
        // every paragraph into one wall of text. This walks the tree and emits
        // newlines at block boundaries, keeping <pre> verbatim.
        function __dsBlockText(root){
          if (!root) return '';
          var BLOCK = /^(P|DIV|LI|UL|OL|PRE|BLOCKQUOTE|H[1-6]|TR|SECTION|ARTICLE|TABLE|HR)${'$'}/;
          var pre = null;
          var out = '';

          function walk(n){
            if (n.nodeType === 3) { out += n.nodeValue || ''; return; }
            if (n.nodeType !== 1) return;
            var tag = (n.tagName || '').toUpperCase();

            if (tag === 'PRE') {
              if (pre === null) pre = [];
              pre.push(n.textContent || '');
              out += '\u0000PRE' + (pre.length - 1) + '\u0000';
              return;
            }
            if (tag === 'BR') { out += '\n'; return; }
            if (tag === 'HR') { out += '\n\n'; return; }

            var isBlock = BLOCK.test(tag);
            if (isBlock && out && !/\n${'$'}/.test(out)) out += '\n';
            for (var i = 0; i < n.childNodes.length; i++) walk(n.childNodes[i]);
            if (isBlock && out && !/\n${'$'}/.test(out)) out += '\n';
          }

          walk(root);

          out = out
            .replace(/[ \t\u00a0]+/g, ' ')
            .replace(/ *\n */g, '\n')
            .replace(/\n{3,}/g, '\n\n')
            .trim();

          if (pre !== null) {
            out = out.replace(/\u0000PRE(\d+)\u0000/g, function(_, i){
              return '\n' + pre[Number(i)].replace(/\s+${'$'}/, '') + '\n';
            });
            out = out.replace(/\n{3,}/g, '\n\n').trim();
          }
          return out;
        }
    """.trimIndent()

    suspend fun readRenderedAnswer(): String = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              $blockTextFn
              try {
                // Verified against the live page. Each turn is a
                //   <div class="ds-message ...">          <- one message
                //     <div class="ds-think-content">      <- reasoning
                //     <div class="ds-markdown ds-assistant-message-main-content">
                //                                          <- the answer
                //
                // Selection must be anchored on the LAST ASSISTANT MESSAGE and
                // then searched within it. Querying the whole document and
                // taking the last match returned the PREVIOUS turn's answer
                // (and for the reasoning block, the previous turn's thinking),
                // because older messages stay mounted in the virtualised list.
                // innerText is LAYOUT-AWARE: it returns only text that is
                // actually rendered. The transcript is scrollable and
                // virtualised, so a long answer that extends below the fold
                // came back as a prefix - the answer looked "cut off" until
                // the user scrolled. textContent returns the full DOM text
                // regardless of scroll position or visibility.
                //
                // textContent has no layout information, so it does NOT insert
                // line breaks between block elements; collapsing all whitespace
                // merged every paragraph into one wall of text. Walk the tree
                // and emit newlines at block boundaries first, then collapse
                // only the whitespace INSIDE a line.
                function norm(el){ return __dsBlockText(el); }

                // Every message element, in document order.
                var msgs = document.querySelectorAll('[class*="ds-message"]');
                if (!msgs.length) {
                  return '';
                }

                // Walk backwards to the newest message that actually holds an
                // assistant answer, skipping the user's own bubbles.
                for (var i = msgs.length - 1; i >= 0; i--) {
                  var m = msgs[i];
                  // The user's turn uses .bubble-text; never treat it as an answer.
                  if (m.querySelector('.bubble-text, [class*="bubble-text"]')) continue;
                  var node = m.querySelector('[class*="ds-assistant-message-main-content"]');
                  if (!node) continue;
                  var t = norm(node);
                  if (t) return t;
                }

                // Markup-change fallback: the newest non-reasoning markdown
                // block, still excluding user bubbles.
                var md = document.querySelectorAll('[class*="ds-markdown"]');
                for (var j = md.length - 1; j >= 0; j--) {
                  var el = md[j];
                  if (el.closest('[class*="ds-think-content"]')) continue;
                  if (el.closest('.bubble, [class*="bubble"]')) continue;
                  if (norm(el)) return norm(el);
                }
                return '';
              } catch (e) { return ''; }
            })();
        """.trimIndent()
        evalJsAwait(script).let { raw ->
            try {
                org.json.JSONTokener(raw).nextValue() as? String ?: ""
            } catch (e: Exception) {
                raw.trim('"')
            }
        }
    }

    /**
     * Read the rendered reasoning ("DeepThink") text for the newest turn.
     *
     * The page renders thinking into its own collapsible block, separate from
     * the answer. The streamed JSON patches for it are consumed internally and
     * never reach the hooks, so the DOM is the only reliable source - same
     * situation as [readRenderedAnswer].
     */
    suspend fun readRenderedReasoning(): String = withContext(Dispatchers.Main) {
        val script = """
            (function(){
              $blockTextFn
              try {
                // Verified against the live page: DeepThink output is rendered
                // into <div class="ds-think-content">, which wraps a
                // ds-markdown body. The collapsed header ("Thought for 1
                // second") sits outside this container, so its text does not
                // leak into the result.
                //
                // Scoped to the LAST ASSISTANT MESSAGE for the same reason as
                // the answer: a document-wide query returned the previous
                // turn's thinking, which is why reasoning was consistently one
                // step behind the question.
                // textContent, not innerText: thinking also renders below the
                // fold for long reasoning, and innerText would truncate it.
                var msgs = document.querySelectorAll('[class*="ds-message"]');
                for (var i = msgs.length - 1; i >= 0; i--) {
                  var m = msgs[i];
                  if (m.querySelector('.bubble-text, [class*="bubble-text"]')) continue;
                  var node = m.querySelector('[class*="ds-think-content"]');
                  if (!node) continue;
                  // Structure-preserving extraction: see readRenderedAnswer.
                  // A flat whitespace collapse merged the thinking's paragraphs
                  // into one block.
                  var t = __dsBlockText(node);
                  if (t) return t;
                }

                // Fallback: newest thinking block anywhere.
                var nodes = document.querySelectorAll('[class*="ds-think-content"]');
                for (var k = nodes.length - 1; k >= 0; k--) {
                  var txt = __dsBlockText(nodes[k]);
                  if (txt) return txt;
                }
                return '';
              } catch (e) { return ''; }
            })();
        """.trimIndent()
        evalJsAwait(script).let { raw ->
            try {
                org.json.JSONTokener(raw).nextValue() as? String ?: ""
            } catch (e: Exception) {
                raw.trim('"')
            }
        }
    }

    /**
     * Guarantee the bridge (fetch + XHR hooks and window.sendPrompt) is
     * installed before we drive the page.
     *
     * evaluateJavascript is asynchronous, so a fire-and-forget injection can
     * still be pending when the completion request goes out - that is what left
     * every tunnel request with an empty answer. Here we inject and then verify.
     */
    private suspend fun ensureBridgeInjected() {
        repeat(3) { attempt ->
            // Check for the XHR hook specifically, not window.sendPrompt: a
            // page navigation can leave sendPrompt defined while wiping the
            // hooks, which made this think the bridge was already installed.
            val present = evalJsAwait("window.__dsXhrHooked === true ? 'true' : 'false'").trim()
            if (present == "true") return

            // Clear the guard, then apply the current script and verify.
            evalJsAwait("window.__deepseek_bridge_injected__ = false; true")
            webView.evaluateJavascript(injectedScript(), null)
            // Let the main-thread script execution complete before checking.
            kotlinx.coroutines.delay(150L * (attempt + 1))
        }
    }
    /**
     * Apply the DeepThink / web-search toggles on the page, then send.
     * The site owns these modes, so the only reliable way to switch them is to
     * press the same controls a user would.
     */
    suspend fun sendPrompt(
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        onChunkReceived: (String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.Main) {
        if (!isLoggedIn()) {
            return@withContext Result.failure(Exception("Not logged in"))
        }
        ensureBridgeInjected()

        // Match by visible label; fall back to no-op when a control is absent.
        val toggleScript = """
            (function(){
              function press(label){
                var candidates = document.querySelectorAll('div[role="button"], button, [class*="pill"], [class*="toggle"]');
                for (var i = 0; i < candidates.length; i++) {
                  var el = candidates[i];
                  var txt = (el.innerText || '').trim().toLowerCase();
                  if (!txt) continue;
                  if (label === 'thinking' && txt.indexOf('deepthink') < 0 && txt.indexOf('think') < 0) continue;
                  if (label === 'search' && txt.indexOf('search') < 0) continue;
                  var cls = (el.className && typeof el.className === 'string') ? el.className : '';
                  var on = cls.indexOf('active') >= 0 || el.getAttribute('aria-pressed') === 'true';
                  if (!on) el.click();
                  return true;
                }
                return false;
              }
              var wantThinking = ${if (thinking) "true" else "false"};
              var wantSearch = ${if (search) "true" else "false"};
              var r = {};
              r.thinking = wantThinking ? press('thinking') : false;
              r.search = wantSearch ? press('search') : false;
              return JSON.stringify(r);
            })();
        """.trimIndent()
        evalJsAwait(toggleScript)
        // Let the page settle before submitting.
        kotlinx.coroutines.delay(250L)

        sendPromptInternal(prompt, onChunkReceived)
    }

    private suspend fun sendPromptInternal(
        prompt: String,
        onChunkReceived: (String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.Main) {
        if (!isLoggedIn()) {
            return@withContext Result.failure(Exception("Not logged in"))
        }

        // Install the interceptors and WAIT for them to be in place: the hook
        // must exist before the completion request is issued, otherwise the
        // stream is never captured. evaluateJavascript is asynchronous, so this
        // has to be awaited rather than fired and forgotten.
        ensureBridgeInjected()

        val deferred = CompletableDeferred<Boolean>()

        // Reset FIRST, then publish the callback.
        //
        // The JS bridge delivers chunks on its own thread. Resetting after the
        // callback was live could clear the line buffer mid-stream, and reading
        // the callback without a happens-before edge meant the delivering thread
        // could still see null - the capture showed payloadsForwarded climbing
        // while callbackSet was false, and the answer was silently dropped.
        resetRawStream()

        activeChunkCallback = { payload ->
            onChunkReceived(payload)
        }
        callbackSetAt = System.currentTimeMillis()
        activeCompletionDeferred = deferred
        activeErrorCallback = { err ->
            onErrorLogged(err)
            if (!deferred.isCompleted) {
                deferred.completeExceptionally(Exception(err))
            }
        }

        val escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        webView.evaluateJavascript("window.sendPrompt(\"$escapedPrompt\");") { result ->
            if (result == "false") {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(Exception("Failed to locate chat input field in WebView"))
                }
            }
        }

        // Generation must be allowed to run far longer than a typical HTTP
        // timeout: the Worker waits 60s for the FIRST frame and then only
        // requires activity, so a long answer is fine as long as this does not
        // cut the WebView stream off mid-generation. Cutting it off closes the
        // local response body early, which surfaces to the Worker's client as
        // "unexpected end of stream".
        val success = withTimeoutOrNull(10 * 60_000L) {
            deferred.await()
        }

        // Wait for the wire to go quiet before detaching.
        //
        // The page-level loadend can fire while XHR progress events for the
        // SAME response are still being delivered. Detaching the callback here
        // dropped them: the capture showed payloadsForwarded climbing while
        // callbackSet was already false, and the client got an empty answer.
        //
        // Poll the payload counter instead of a queue depth - payloads are
        // posted after the deferred completes, so a queue-length check reads
        // zero and proves nothing.
        var lastCount = forwardedPayloads
        var quietTicks = 0
        val drainDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < drainDeadline && quietTicks < 3) {
            kotlinx.coroutines.delay(120L)
            val now = forwardedPayloads
            if (now == lastCount) quietTicks++ else { quietTicks = 0; lastCount = now }
        }

        activeChunkCallback = null
        activeCompletionDeferred = null
        activeErrorCallback = null

        if (success == true) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Send prompt timed out or failed after 10 minutes"))
        }
    }

    @JavascriptInterface
    fun onChunk(json: String) {
        handler.post {
            activeChunkCallback?.invoke(json)
        }
    }

    @JavascriptInterface
    fun onError(msg: String) {
        handler.post {
            activeErrorCallback?.invoke(msg)
        }
    }

    @JavascriptInterface
    fun onCompletionDone() {
        // The page reports completion at loadend, which can precede the final
        // XHR progress events for the same response. Wait briefly for the raw
        // stream to settle so its last fragments are delivered before the
        // caller is told the request is finished.
        handler.post {
            val d = activeCompletionDeferred ?: return@post
            if (d.isCompleted) return@post
            if (forwardedPayloads == lastSeenPayloadCount && streamEnded) {
                d.complete(true)
            } else {
                lastSeenPayloadCount = forwardedPayloads
                // Re-check shortly; the stream is usually a few ms behind.
                handler.postDelayed({
                    if (!d.isCompleted) d.complete(true)
                }, 400L)
            }
        }
    }

    /** Payload count at the previous completion check. */
    @Volatile
    private var lastSeenPayloadCount: Int = -1

    // =========================================================================
    // Raw network stream hook
    // =========================================================================
    // The document-start hook tees the SSE body on its way to the page, so
    // these receive the real bytes rather than a DOM reconstruction. The
    // framing is DeepSeek's own: lines are "data: <json>", separated by blank
    // lines, and the payloads are JSON-Patch operations (see
    // LocalHttpServer.extractPatch for the interpretation).

    /** Per-request carry-over for a line split across two network chunks. */
    private val streamLineBuffer = StringBuilder()

    /** Raw chunks for the current request, for diagnostics. */
    private val rawChunks = java.util.Collections.synchronizedList(ArrayList<String>())

    @Volatile
    private var rawChunkCount: Int = 0

    /** How many `data:` payloads have been handed to the parser. */
    @Volatile
    private var forwardedPayloads: Int = 0

    /** Payloads posted to the main thread but not yet delivered. */
    private val pendingChunkPosts = java.util.concurrent.atomic.AtomicInteger(0)

    @JavascriptInterface
    fun onStreamChunk(text: String) {
        if (text.isEmpty()) return
        rawChunkCount++
        if (rawChunks.size < 400) rawChunks.add(text)

        // Split complete lines; keep the trailing partial for the next chunk.
        val combined = streamLineBuffer.toString() + text
        streamLineBuffer.setLength(0)

        var forwarded = 0
        val lines = combined.split("\n")
        for (i in 0 until lines.size - 1) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty()) continue
            // [DONE] and the challenge envelope are handled by the parser.
            forwarded++
            postPayload(payload)
        }
        forwardedPayloads += forwarded
        // Last element is either "" (text ended with \n) or a partial line.
        streamLineBuffer.append(lines.last())
    }

    /**
     * Hand one payload to the parser.
     *
     * Invoked SYNCHRONOUSLY rather than via handler.post.
     *
     * The callback runs on a WebView JS-bridge thread, while the parser state
     * in LocalHttpServer was being resumed on the main thread. Posting meant
     * completion could detach the callback while payloads were still queued,
     * which is exactly what the capture showed (payloadsForwarded climbing with
     * callbackSet false). Calling inline removes the queue entirely.
     */
    private fun postPayload(payload: String) {
        val now = System.currentTimeMillis()
        if (firstPayloadAt == 0L) firstPayloadAt = now
        lastPayloadAt = now

        val cb = activeChunkCallback
        if (cb == null) {
            droppedPayloads++
            if (dropSamples.size < 5) dropSamples.add(payload.take(80))
            return
        }
        try {
            cb.invoke(payload)
        } catch (e: Exception) {
            onErrorLogged("Chunk callback failed: ${e.message}")
        }
    }

    /** Payloads that arrived with no callback attached. */
    @Volatile
    private var droppedPayloads: Int = 0

    /** Samples of dropped payloads, for diagnosis. */
    private val dropSamples = java.util.Collections.synchronizedList(ArrayList<String>())

    /** When the callback was last set, and when payloads last arrived. */
    @Volatile
    private var callbackSetAt: Long = 0
    @Volatile
    private var lastPayloadAt: Long = 0
    @Volatile
    private var firstPayloadAt: Long = 0

    @JavascriptInterface
    fun onStreamDone() {
        // Flush a trailing line that never got its newline.
        val tail = streamLineBuffer.toString().trim()
        streamLineBuffer.setLength(0)
        if (tail.startsWith("data:")) {
            val payload = tail.substring(5).trim()
            if (payload.isNotEmpty()) {
                forwardedPayloads++
                postPayload(payload)
            }
        }
        // Do NOT complete the deferred here; the page-level hook's loadend owns
        // that signal, and completing from both raced them against each other.
        streamEnded = true
    }

    /** True once the raw stream reported EOF. */
    @Volatile
    private var streamEnded: Boolean = false

    /**
     * Answer text accumulated from the raw stream's own fragments.
     *
     * Built from the JSON-Patch payloads the hook captured, so it needs no DOM
     * access. This is the preferred source when a client wants the finished
     * text rather than incremental deltas - and unlike scraping it cannot
     * return a previous turn's message or a partial paragraph.
     */
    private val streamedAnswer = StringBuilder()
    private val streamedReasoning = StringBuilder()

    @Synchronized
    fun noteStreamedText(text: String, isReasoning: Boolean) {
        if (text.isEmpty()) return
        if (isReasoning) streamedReasoning.append(text) else streamedAnswer.append(text)
    }

    /** The full answer seen on the wire for the current request. */
    @Synchronized
    fun streamedAnswerText(): String = streamedAnswer.toString()

    /** The full reasoning seen on the wire for the current request. */
    @Synchronized
    fun streamedReasoningText(): String = streamedReasoning.toString()

    private fun resetStreamedText() {
        synchronized(this) {
            streamedAnswer.setLength(0)
            streamedReasoning.setLength(0)
        }
    }

    /** Temporary: hook activity log. */
    private val hookLogs = java.util.Collections.synchronizedList(ArrayList<String>())

    @JavascriptInterface
    fun onHookLog(msg: String) {
        synchronized(hookLogs) {
            if (hookLogs.size < 200) hookLogs.add(msg)
        }
    }

    /** Temporary: the hook's own view of what happened. */
    fun hookLogText(): String = synchronized(hookLogs) {
        hookLogs.joinToString(" | ").replace("\"", "'")
    }

    /**
     * The request body the page actually sent.
     *
     * Ground truth for the DeepThink / Search flags. The site serialises its
     * own state into `thinking_enabled` and `search_enabled`, so reading these
     * is exact - unlike inferring state from button classes, which produced
     * the toggle bugs.
     */
    @JavascriptInterface
    fun onStreamRequest(body: String) {
        if (body.isEmpty()) return
        lastRequestFlags = try {
            val json = org.json.JSONObject(body)
            RequestFlags(
                thinking = json.optBoolean("thinking_enabled", false),
                search = json.optBoolean("search_enabled", false),
                prompt = json.optString("prompt", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Flags read from the most recent intercepted completion request. */
    data class RequestFlags(val thinking: Boolean, val search: Boolean, val prompt: String)

    @Volatile
    var lastRequestFlags: RequestFlags? = null
        private set

    /** Diagnostic: how many raw network chunks have been seen. */
    /** Raw network chunks captured for the current request. */
    fun rawChunkCount(): Int = rawChunkCount

    fun rawStreamStats(): String {
        val body = synchronized(rawChunks) {
            rawChunks.joinToString(" | ") { it.take(120).replace("\n", "\\n") }
        }
        return "{\"chunks\":$rawChunkCount," +
            "\"payloadsForwarded\":$forwardedPayloads," +
            "\"callbackSet\":" + (activeChunkCallback != null) + "," +
            "\"dropped\":$droppedPayloads," +
            "\"callbackSetAt\":$callbackSetAt," +
            "\"firstPayloadAt\":$firstPayloadAt," +
            "\"lastPayloadAt\":$lastPayloadAt," +
            "\"dropSamples\":\"" + synchronized(dropSamples) { dropSamples.joinToString(" ~ ").replace("\"", "'") } + "\"," +
            "\"hookLog\":\"" + hookLogText().take(500) + "\"," +
            "\"sample\":\"${body.take(400).replace("\"", "'")}\"}"
    }

    /** Clear raw capture before a new request. */
    fun resetRawStream() {
        rawChunkCount = 0
        forwardedPayloads = 0
        streamEnded = false
        pendingChunkPosts.set(0)
        streamLineBuffer.setLength(0)
        resetStreamedText()
        synchronized(rawChunks) { rawChunks.clear() }
        synchronized(hookLogs) { hookLogs.clear() }
    }
}
