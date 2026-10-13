package com.example.deepseekstandalone

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Standalone build: the DeepSeek page, the control UI and the web client all
 * live in this one app, with no Cloudflare Worker in the path.
 *
 * Layout differs from the tunnelled build. Instead of one screen holding the
 * WebView plus a config panel, there are three pages you swipe between:
 *
 *   0  DeepSeek  - the real chat.deepseek.com page (drives the session)
 *   1  Controls  - server state, error log, reload, page counts
 *   2  Web UI    - the GitHub-hosted client, talking to 127.0.0.1:8080
 *
 * The pages are deliberately separate surfaces: the DeepSeek page is the live
 * session and should not be covered by controls, and the client page needs the
 * full width to be usable.
 */
class MainActivity : ComponentActivity(), ServerHandler {

    companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 9001
        /** Result code for a file chooser opened from a page's <input type=file>. */
        const val FILE_CHOOSER_REQUEST = 7002
        const val DEEPSEEK_URL = "https://chat.deepseek.com"
        /** The published client UI. */
        const val WEB_UI_URL = "https://chernuga.github.io/ZhiPu-Integration/"
    }

    private lateinit var prefs: SharedPreferences

    /** The DeepSeek page. Observable so composition attaches it once created. */
    private var webView by mutableStateOf<WebView?>(null)
    private var webViewBridge: WebViewBridge? = null

    /**
     * The client UI page. Kept separate from the DeepSeek page so the two never
     * share a document. Observable so composition picks it up once created.
     */
    private var uiWebView by mutableStateOf<WebView?>(null)


    private var localHttpServer: LocalHttpServer? = null

    /**
     * SAF bridge for the web UI's linked-folder workspace.
     *
     * Created eagerly so it exists before any page loads; the interface is
     * registered on the client WebView and called from its JavaScript.
     */
    internal val workspaceBridge by lazy { WorkspaceBridge(this) }

    /**
     * Pending result for a file chooser opened from a page.
     *
     * A WebView cannot open the system picker itself, so [WebChromeClient] hands
     * the request here and the activity launches it. One at a time: a second
     * chooser cannot open while the first is still up.
     */
    private var fileChooserCallback: android.webkit.ValueCallback<Uri?>? = null

    // Observable Compose state
    private var isApiEnabledState by mutableStateOf(false)
    private var isServerRunningState by mutableStateOf(false)
    private var lastErrorState by mutableStateOf<String?>(null)
    private val errorLogList = mutableStateListOf<String>()

    /** Live page counters, refreshed by the Controls page. */
    private var streamedChunksState by mutableStateOf(0)

    /** Non-null while the DOM-fallback prompt is on screen. */
    private var domPromptState by mutableStateOf<String?>(null)
    private var domPromptAnswer: CompletableDeferred<Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Lets the hosted page be inspected over chrome://inspect when its
        // requests fail silently.
        WebView.setWebContentsDebuggingEnabled(true)

        prefs = getSharedPreferences("deepseek_standalone_prefs", MODE_PRIVATE)
        // The API is on by default here: there is no tunnel to configure, so an
        // off-by-default server would just look broken on first launch.
        isApiEnabledState = prefs.getBoolean("key_api_enabled", true)

        val view = WebView(this)
        webView = view
        webViewBridge = WebViewBridge(view) { err -> logError(err) }

        // Build the client UI WebView up front, alongside the DeepSeek one.
        // Creating it lazily inside the pager meant it was rebuilt whenever the
        // page scrolled out of view, losing the loaded document.
        uiWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Seed the page's provider list with one pointing at this device.
            //
            // The site already loads its providers from localStorage, so it can
            // be configured exactly as it would be through its own Settings
            // panel - no change to the website. Injected at document start so
            // the page never renders an unconfigured state first.
            addJavascriptInterface(ProviderSeeder(), "AndroidLocalProvider")

            // Lets the page link a real device folder as a conversation
            // workspace, and read and write files inside it.
            addJavascriptInterface(workspaceBridge, "AndroidWorkspace")

            // Merge the local provider into the page's stored settings before
            // its own script runs.
            //
            // The site reads localStorage at startup, so doing this first means
            // the local provider is present from the very first render and the
            // page needs no knowledge of this app. It is idempotent: an existing
            // entry with the same id is replaced, and the user's other providers
            // and choices are left untouched.
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    seedLocalProvider(view ?: return)
                }
            }

            // Handle <input type="file">.
            //
            // Without this the page can render a file picker but tapping it does
            // nothing: a WebView cannot open the system chooser on its own, so
            // the request must be launched by the activity.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: android.webkit.ValueCallback<Array<Uri>?>?,
                    params: FileChooserParams?
                ): Boolean {
                    if (fileChooserCallback != null) return false // one at a time
                    // Wrap the single-URI result in the array the API expects.
                    fileChooserCallback = object : android.webkit.ValueCallback<Uri?> {
                        override fun onReceiveValue(value: Uri?) {
                            callback?.onReceiveValue(if (value != null) arrayOf(value) else null)
                        }
                    }
                    return try {
                        startActivityForResult(params!!.createIntent(), FILE_CHOOSER_REQUEST)
                        true
                    } catch (e: Exception) {
                        fileChooserCallback = null
                        logError("Could not open the file chooser: ${e.message}")
                        false
                    }
                }
            }

            loadUrl(WEB_UI_URL)
        }

        if (isApiEnabledState) {
            startBridgeServices()
        }

        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StandaloneScreen(
                        modifier = Modifier.padding(innerPadding),
                        isApiEnabled = isApiEnabledState,
                        onApiEnabledChange = { enabled ->
                            isApiEnabledState = enabled
                            prefs.edit().putBoolean("key_api_enabled", enabled).apply()
                            if (enabled) startBridgeServices() else stopBridgeServices()
                        },
                        isServerRunning = isServerRunningState,
                        lastError = lastErrorState,
                        errorLog = errorLogList,
                        streamedChunks = streamedChunksState,
                        webView = webView,
                        onWebViewReady = { },
                        onReloadWebView = { webViewBridge?.loadUrl(DEEPSEEK_URL) },
                        onClearErrors = {
                            errorLogList.clear()
                            lastErrorState = null
                        },
                        onRefreshStats = { refreshStats(); probeUiPage(); probePageVersion() },
                        onTestSend = { sendFromUiPage("Say exactly: PAGETEST") },
                        onReloadUiWebView = { uiWebView?.reload() },
                        uiWebView = uiWebView,
                        domPrompt = domPromptState,
                        onDomPromptAnswer = { proceed -> domPromptAnswer?.complete(proceed) }
                    )
                }
            }
        }

        loadDeepSeekOnce()
    }

    /**
     * Results from the folder picker and the file chooser.
     *
     * The folder picker is started by WorkspaceBridge (the page cannot open one
     * itself); its result is handed straight back to the waiting call.
     */
    @Deprecated("startActivityForResult is still the simplest route for ACTION_OPEN_DOCUMENT_TREE")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == WorkspaceBridge.PICK_FOLDER_REQUEST) {
            val uri = data?.data
            val ok = resultCode == RESULT_OK && uri != null
            workspaceBridge.onFolderPicked(if (ok) uri else null, cancelled = !ok)
            if (ok) logError("Workspace folder linked: ${uri}")
            else logError("Workspace folder selection cancelled")
            return
        }

        // A file chooser opened from the page (an <input type=file>).
        fileChooserCallback?.let { cb ->
            fileChooserCallback = null
            if (resultCode == RESULT_OK && data != null) cb.onReceiveValue(data.data)
            else cb.onReceiveValue(null)
        }
    }

    override fun onDestroy() {
        stopBridgeServices()
        lifecycleScope.coroutineContext.cancelChildren()
        webViewBridge?.let { runCatching { it.dispose() } }
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        webViewBridge = null
        uiWebView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
        uiWebView = null
        super.onDestroy()
    }

    private var initialLoadStarted = false

    /** Idempotent: only the first call loads the page. */
    private fun loadDeepSeekOnce() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        webViewBridge?.loadUrl(DEEPSEEK_URL)
    }

    /**
     * Insert or update the local provider in the page's own settings blob.
     *
     * Runs before the page's scripts, so the site sees it as ordinary
     * configuration. The settings key and record shape come from the site; this
     * only writes them, it does not change the site.
     */
    private fun seedLocalProvider(view: WebView) {
        if (!view.url.orEmpty().startsWith("https://chernuga.github.io/")) return
        val js = """
            (function(){
              try {
                var bridge = window.AndroidLocalProvider;
                if (!bridge) return 'no-bridge';
                var KEY = 'deepseek-ui-settings';
                var p = JSON.parse(bridge.providerJson());
                var s = {};
                try { s = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch (e) { s = {}; }
                if (!Array.isArray(s.providers)) s.providers = [];
                var i = -1;
                for (var k = 0; k < s.providers.length; k++) {
                  if (s.providers[k] && s.providers[k].id === p.id) { i = k; break; }
                }
                if (i >= 0) s.providers[i] = p; else s.providers.push(p);
                // Select it on first run only, so a deliberate choice sticks.
                if (!s.activeProviderId || s.activeProviderId === 'deepseek-web') {
                  s.activeProviderId = p.id;
                }
                if (!s.model) s.model = 'deepseek-web';
                localStorage.setItem(KEY, JSON.stringify(s));
                return 'seeded';
              } catch (e) { return 'error: ' + e; }
            })();
        """.trimIndent()
        view.evaluateJavascript(js) { result -> logError("Local provider seed: $result") }
    }
    /**
     * Ask the hosted page what it thinks its configuration is.
     *
     * When a request never leaves the page there is nothing in the server log to
     * inspect, so the page's own view of the provider is the only evidence.
     */
    private fun probeUiPage() {
        val v = uiWebView ?: return
        val js = """
            (function(){
              try {
                var s = JSON.parse(localStorage.getItem('deepseek-ui-settings') || '{}');
                var p = (s.providers || []).find(function(x){ return x.id === s.activeProviderId; }) || {};
                var out = {
                  active: s.activeProviderId || null,
                  label: p.label || null,
                  baseUrl: p.baseUrl || null,
                  suffix: p.pathSuffix || null,
                  mode: p.responseMode || null,
                  hasUrlFn: typeof buildRequestUrl === 'function',
                  url: (typeof buildRequestUrl === 'function') ? buildRequestUrl() : null,
                  textareas: document.querySelectorAll('textarea').length,
                  sendButtons: document.querySelectorAll('button').length
                };
                return JSON.stringify(out);
              } catch (e) { return 'probe error: ' + e; }
            })();
        """.trimIndent()
        v.evaluateJavascript(js) { r -> logError("WebUI probe: $r") }
    }
    /**
     * Report which build of the hosted page the WebView is running.
     *
     * A stale cached copy is invisible otherwise: the page works, just with old
     * behaviour, so the only way to tell is to ask it.
     */
    private fun probePageVersion() {
        val v = uiWebView ?: return
        val js = """
            (function(){
              try {
                var html = document.documentElement.outerHTML;
                return JSON.stringify({
                  hasMergedFix: html.indexOf('Merge the prompt finish into the final user turn') >= 0,
                  hasOldLeadingFold: html.indexOf('folded into the LEADING system message') >= 0,
                  hasTrailingFinish: html.indexOf('settings.promptFinish.trim() });') >= 0,
                  scriptLen: html.length
                });
              } catch (e) { return 'err: ' + e; }
            })();
        """.trimIndent()
        v.evaluateJavascript(js) { r -> logError("PageVersion: $r") }
    }
    /**
     * Send a message through the hosted page's own send path.
     *
     * Synthetic taps on the composer are unreliable once the soft keyboard
     * opens, so this drives the page's own entry point instead: the same code a
     * real click runs, including how it builds the request.
     */
    private fun sendFromUiPage(text: String) {
        val v = uiWebView ?: return
        val safe = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val js = """
            (function(){
              try {
                var ta = document.getElementById('input');
                if (!ta) return 'no-input';
                ta.value = "$safe";
                ta.dispatchEvent(new Event('input', { bubbles: true }));
                if (typeof send !== 'function') return 'no-send-fn';
                send();
                return 'sent';
              } catch (e) { return 'error: ' + e; }
            })();
        """.trimIndent()
        v.evaluateJavascript(js) { r -> logError("UiPageSend: $r") }
    }

    // -- server -----------------------------------------------------------------

    private fun startBridgeServices() {
        stopBridgeServices()

        // Hold the process in the foreground so the WebView and the local server
        // keep working with the screen off.
        requestNotificationPermissionIfNeeded()
        BridgeService.start(this)

        try {
            localHttpServer = LocalHttpServer(
                host = "127.0.0.1",
                port = 8080,
                scope = lifecycleScope,
                handler = this,
                onErrorLogged = { err -> logError(err) }
            )
            localHttpServer?.start()
            isServerRunningState = true
            logError("Local API listening on http://127.0.0.1:8080")
        } catch (e: Exception) {
            isServerRunningState = false
            logError(
                "Failed to start local server on 127.0.0.1:8080: ${e.message}. " +
                    "If the tunnelled app is installed, stop it first - both use " +
                    "port 8080 and only one can hold it."
            )
        }
    }

    private fun stopBridgeServices() {
        try {
            localHttpServer?.stop()
        } catch (ignored: Exception) {}
        localHttpServer = null
        isServerRunningState = false
        BridgeService.stop(this)
    }

    private fun refreshStats() {
        streamedChunksState = webViewBridge?.rawChunkCount() ?: 0
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    // -- logging ----------------------------------------------------------------

    private fun logError(msg: String) {
        runOnUiThread {
            lastErrorState = msg
            errorLogList.add(msg)
            if (errorLogList.size > 200) errorLogList.removeAt(0)
        }
    }

    override fun getLastError(): String? = lastErrorState
    override fun getErrorLog(): List<String> = errorLogList.toList()
    override fun isApiEnabled(): Boolean = isApiEnabledState
    override fun isLoggedIn(): Boolean = webViewBridge?.isLoggedIn() ?: false

    // -- ServerHandler: delegate to the bridge ----------------------------------

    override suspend fun readRenderedAnswer(): String = webViewBridge?.readRenderedAnswer() ?: ""
    override suspend fun readRenderedReasoning(): String = webViewBridge?.readRenderedReasoning() ?: ""
    override suspend fun debugTranscript(): String = webViewBridge?.debugTranscript() ?: "{}"
    override suspend fun debugAnswerText(): String = webViewBridge?.debugAnswerText() ?: "{}"
    override suspend fun rawStreamStats(): String = webViewBridge?.rawStreamStats() ?: "{}"
    override suspend fun hookPresence(): String = webViewBridge?.hookPresence() ?: "{}"
    override suspend fun streamedAnswerText(): String = webViewBridge?.streamedAnswerText() ?: ""
    override suspend fun streamedReasoningText(): String = webViewBridge?.streamedReasoningText() ?: ""
    override suspend fun isContinueOffered(): Boolean = webViewBridge?.isContinueOffered() ?: false
    override suspend fun recoverLastAnswer(): Pair<String, String> =
        webViewBridge?.recoverLastAnswer() ?: ("" to "")
    override fun noteStreamedText(text: String, isReasoning: Boolean) {
        webViewBridge?.noteStreamedText(text, isReasoning)
    }
    override suspend fun stopGeneration(): Boolean = webViewBridge?.stopGeneration() ?: false
    override suspend fun isVerificationShowing(): Boolean = webViewBridge?.isVerificationShowing() ?: false
    override suspend fun reloadAndWait(): Boolean = webViewBridge?.reloadAndWait() ?: false

    override suspend fun sendPrompt(prompt: String, onChunk: (String) -> Unit): Result<Unit> =
        // The bridge exposes one entry point; the plain form is the same call
        // with the page-level toggles left alone.
        sendPrompt(prompt, thinking = false, search = false, onChunk = onChunk)

    override suspend fun sendPrompt(
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        onChunk: (String) -> Unit
    ): Result<Unit> =
        webViewBridge?.sendPrompt(prompt, thinking, search, onChunk)
            ?: Result.failure(Exception("WebView not ready"))

    override suspend fun confirmDomFallback(reason: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            domPromptAnswer = deferred
            domPromptState = reason
        }
        val answer = withTimeoutOrNull(60_000L) { deferred.await() } ?: false
        withContext(Dispatchers.Main) {
            domPromptState = null
            domPromptAnswer = null
        }
        return answer
    }
}

// =============================================================================
// UI
// =============================================================================

/** The three swipeable pages, in order. */
private val PAGE_TITLES = listOf("DeepSeek", "Web UI", "Controls")

@Composable
fun StandaloneScreen(
    modifier: Modifier = Modifier,
    isApiEnabled: Boolean,
    onApiEnabledChange: (Boolean) -> Unit,
    isServerRunning: Boolean,
    lastError: String?,
    errorLog: List<String>,
    streamedChunks: Int,
    webView: WebView?,
    onWebViewReady: () -> Unit,
    onReloadWebView: () -> Unit,
    onClearErrors: () -> Unit,
    onRefreshStats: () -> Unit,
    uiWebView: WebView?,
    onReloadUiWebView: () -> Unit,
    onTestSend: () -> Unit,
    domPrompt: String? = null,
    onDomPromptAnswer: (Boolean) -> Unit = {}
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_TITLES.size })
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // The native hosts are remembered so they outlive recomposition.
    val deepseekHost = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.widget.FrameLayout?>(null) }
    val uiHost = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.widget.FrameLayout?>(null) }

    if (domPrompt != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onDomPromptAnswer(false) },
            title = { Text("No streamed reply") },
            text = {
                Text(
                    "$domPrompt\n\n" +
                        "Read the answer from the page instead? This can return a " +
                        "previous message's text if the page has not finished rendering."
                )
            },
            confirmButton = {
                TextButton(onClick = { onDomPromptAnswer(true) }) { Text("Read from page") }
            },
            dismissButton = {
                TextButton(onClick = { onDomPromptAnswer(false) }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Page titles double as navigation, so a swipe and a tap both work.
        PageTabs(
            titles = PAGE_TITLES,
            current = pagerState.currentPage,
            onSelect = { target ->
                scope.launch { pagerState.animateScrollToPage(target) }
            }
        )

        // A HorizontalPager translates its pages sideways, and an Android
        // WebView does not render correctly under that transform: it draws as a
        // blank or black surface even though the page is loaded. The pager is
        // therefore used ONLY as a gesture/state source for the swipe, with its
        // pages empty and invisible; the actual content is a single stack below
        // that shows one page at a time by visibility.
        //
        // This keeps the swipe-to-switch behaviour while giving the WebViews a
        // stationary parent.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .alpha(0f),
            userScrollEnabled = true
        ) { /* intentionally empty: content lives in the stack below */ }

        Box(Modifier.fillMaxSize()) {
            // Both WebViews are hosted in Android FrameLayouts that are added
            // once and never removed; switching pages only toggles their
            // visibility. A WebView cannot survive being detached and
            // re-attached (it blanks), so the containers must be permanent.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).apply {
                        addView(
                            webView,
                            android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        deepseekHost.value = this
                    }
                }
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).apply {
                        addView(
                            uiWebView,
                            android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        uiHost.value = this
                    }
                }
            )

            // Compose draws in order, so the host for the active page must be
            // the one on top; the other is hidden outright.
            androidx.compose.runtime.SideEffect {
                deepseekHost.value?.visibility =
                    if (pagerState.currentPage == 0) android.view.View.VISIBLE
                    else android.view.View.GONE
                uiHost.value?.visibility =
                    if (pagerState.currentPage == 1) android.view.View.VISIBLE
                    else android.view.View.GONE
            }

            if (pagerState.currentPage == 2) {
                ControlsPage(
                    isApiEnabled = isApiEnabled,
                    onApiEnabledChange = onApiEnabledChange,
                    isServerRunning = isServerRunning,
                    lastError = lastError,
                    errorLog = errorLog,
                    streamedChunks = streamedChunks,
                    onReloadPage = onReloadWebView,
                    onReloadUi = onReloadUiWebView,
                    onClearErrors = onClearErrors,
                    onRefreshStats = onRefreshStats,
                    onTestSend = onTestSend
                )
            }
        }
    }
}

/** Tappable page titles with an active underline. */
@Composable
private fun PageTabs(titles: List<String>, current: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        titles.forEachIndexed { index, title ->
            val active = index == current
            TextButton(onClick = { onSelect(index) }) {
                Text(
                    title,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ControlsPage(
    isApiEnabled: Boolean,
    onApiEnabledChange: (Boolean) -> Unit,
    isServerRunning: Boolean,
    lastError: String?,
    errorLog: List<String>,
    streamedChunks: Int,
    onReloadPage: () -> Unit,
    onReloadUi: () -> Unit,
    onClearErrors: () -> Unit,
    onRefreshStats: () -> Unit,
    onTestSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Standalone bridge", style = MaterialTheme.typography.headlineSmall)
        Text(
            "No Cloudflare Worker. The API is served on this device and the " +
                "Web UI page talks to it directly.",
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Enable local API", style = MaterialTheme.typography.titleMedium)
                Text(
                    "http://127.0.0.1:8080",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Switch(checked = isApiEnabled, onCheckedChange = onApiEnabledChange)
        }

        StatusRow("Server", if (isServerRunning) "running" else "stopped")
        StatusRow("Transport", "direct (local only)")
        StatusRow("Streamed chunks", streamedChunks.toString())
        StatusRow("Last error", lastError ?: "none")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onReloadPage) { Text("Reload DeepSeek") }
            OutlinedButton(onClick = onRefreshStats) { Text("Refresh") }
            OutlinedButton(onClick = onTestSend) { Text("Test send") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Error log (${errorLog.size})", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClearErrors) { Text("Clear") }
        }

        if (errorLog.isEmpty()) {
            Text("Nothing logged.", style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Newest first: the most recent failure is the one being debugged.
                errorLog.asReversed().take(60).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Text(
            "Swipe left or right, or tap a title above, to change page.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Supplies the local provider definition to the web UI page.
 *
 * The page asks for this and merges it into its own provider list, so the
 * standalone bridge appears as a normal selectable provider. Nothing about the
 * website changes: this only pre-fills what its Settings panel would store.
 *
 * Every method is annotated because the WebView exposes the object to page
 * JavaScript; on API 17+ an unannotated method is invisible to the page.
 */
class ProviderSeeder {

    /** Base URL the page should use. Loopback: only this device can reach it. */
    @android.webkit.JavascriptInterface
    fun baseUrl(): String = "http://127.0.0.1:8080"

    /**
     * The provider record, as JSON.
     *
     * `route` and `pathSuffix` are left empty so the page's own logic decides
     * the path from the response mode, and `responseMode` is "full": the bridge
     * is running in the same process as the page, so a long answer arriving as
     * hundreds of streamed frames would contend with the UI thread.
     */
    @android.webkit.JavascriptInterface
    fun providerJson(): String = """
        {
          "id": "local-standalone",
          "label": "This device (standalone)",
          "baseUrl": "http://127.0.0.1:8080",
          "apiKey": "local",
          "route": "coding",
          "pathSuffix": "/chat/completions/full",
          "responseMode": "full",
          "modelsPath": "/models",
          "note": "Served by this app. No Worker, no network hop. Keep the screen on while a request runs.",
          "models": [
            { "id": "deepseek-web",       "label": "DeepSeek (Web)",       "desc": "Current web model" },
            { "id": "deepseek-web-think", "label": "DeepSeek (DeepThink)", "desc": "Reasoning" }
          ]
        }
    """.trimIndent()

    /** True once this object is available, used to confirm the bridge exists. */
    @android.webkit.JavascriptInterface
    fun ping(): String = "ok"
}