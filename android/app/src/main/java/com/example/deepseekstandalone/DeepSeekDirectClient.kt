package com.example.deepseekstandalone

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Talks to DeepSeek's web API directly, without a browser tab.
 *
 * The tab bridge works by typing into the page and scraping what it renders. That is
 * reliable, but it can only ever send one prompt: the page owns the conversation, so
 * there is no way to send a custom message list, and no way to run a tool call.
 *
 * This is the alternative, modelled on WebAI2API: it creates a session, proves work,
 * and posts a completion, receiving an SSE stream. Because the caller supplies the
 * whole message list, it can support things the tab bridge cannot.
 *
 * THREE THINGS IT NEEDS FROM THE PAGE
 *
 * Credentials come from the WebView that is already signed in - a token and cookies,
 * read once per request. The proof-of-work is solved by a WASM module that DeepSeek
 * ships as part of their own client; it is 26 KB, bundled in assets, and run inside the
 * same WebView because that is where a JS engine already exists.
 *
 * Everything else is plain HTTP, so it works off the main thread on its own.
 */
class DeepSeekDirectClient(
    private val webView: () -> WebView?,
    /** The bearer captured from a page request, or "" if none has been seen yet. */
    private val capturedBearer: () -> String = { "" },
    /** The bundled PoW module, or null when the app has none. */
    private val powWasm: (() -> ByteArray?)? = null,
) {

    companion object {
        private const val TAG = "DeepSeekDirect"
        private const val BASE = "https://chat.deepseek.com"
        private const val CLIENT_VERSION = "2.5.0"
        private const val CLIENT_PLATFORM = "web"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Mobile Safari/537.36"
    }

    /** Cookie and bearer as the page currently holds them. */
    data class Credentials(val cookie: String, val bearer: String)

    /** The challenge the server issues for one request path. */
    data class PowChallenge(
        val algorithm: String,
        val challenge: String,
        val salt: String,
        val signature: String,
        val difficulty: Long,
        val expireAt: Long,
        val targetPath: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("algorithm", algorithm)
            .put("challenge", challenge)
            .put("salt", salt)
            .put("signature", signature)
            .put("difficulty", difficulty)
            .put("expire_at", expireAt)
            .put("target_path", targetPath)
    }

    /**
     * Read the credentials the page is using right now.
     *
     * The page keeps its token in localStorage and its cookies in the WebView's store.
     * Both are read here rather than asked of the user: they already have a session,
     * and a second login would be a worse experience than reusing it.
     *
     * Returns null when the page is not signed in, so the caller can fall back rather
     * than sending a request that will fail with an opaque 401.
     */
    suspend fun readCredentials(): Credentials? {
        val view = webView() ?: return null

        // The site keeps its token in localStorage under "userToken".
        //
        // This used to scan for anything shaped like a JWT, which found nothing: the
        // value is not a three-segment JWT, so the scan reported "signed out" while the
        // page was signed in. The documented key is the dependable source; the scan is
        // kept as a fallback for a future rename.
        val script = """
            (function(){
              try {
                var t = localStorage.getItem('userToken');
                if (t && t.length > 20) return t;
                // Fallback: any key whose value decodes as a JWT with an expiry.
                function isJwt(v) {
                  if (!v || v.length < 40) return false;
                  var parts = v.split('.');
                  if (parts.length !== 3) return false;
                  var p = parts[1].replace(/-/g, '+').replace(/_/g, '/');
                  while (p.length % 4) p += '=';
                  try {
                    var o = JSON.parse(atob(p));
                    return !!(o && (o.exp || o.sub || o.user_id || o.iat));
                  } catch (e) { return false; }
                }
                for (var i = 0; i < localStorage.length; i++) {
                  var k = localStorage.key(i);
                  var v = localStorage.getItem(k) || '';
                  if (isJwt(v)) return v;
                }
                return '';
              } catch (e) { return ''; }
            })();
        """.trimIndent()

        // The captured token wins. localStorage's userToken is a different value that
        // the API rejects, so it is only a fallback for the case where no request has
        // been seen yet - which resolves itself the moment the page sends one.
        val captured = capturedBearer().trim()
        val stored = unquote(eval(script) ?: "").trim()
        val bearer = if (captured.isNotBlank()) captured else stored

        if (bearer.isBlank()) {
            Log.w(TAG, "No token available; the page is probably signed out")
            return null
        }
        Log.d(TAG, "token source: " + (if (captured.isNotBlank()) "captured request header" else "localStorage"))



        // The session cookie is HttpOnly, so document.cookie cannot see it. The
        // WebView's cookie store can, and it is what the server actually checks.
        val cookie = CookieReader.headerFor(view, BASE)
        return Credentials(cookie = cookie, bearer = bearer)
    }

    /** Create an empty chat session and return its id. */
    fun createSession(cred: Credentials): String? {
        val body = "{}"
        val res = post("$BASE/api/v0/chat_session/create", cred, body)
        if (res.code !in 200..299) {
            Log.w(TAG, "Session create failed: HTTP ${res.code} ${res.body.take(200)}")
            return null
        }
        Log.d(TAG, "Session create body: ${res.body.take(300)}")
        val json = try {
            JSONObject(res.body)
        } catch (e: Exception) {
            Log.w(TAG, "Session create returned non-JSON: ${res.body.take(200)}")
            return null
        }
        return json.optJSONObject("data")
            ?.optJSONObject("biz_data")
            ?.optJSONObject("chat_session")
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
    }

    /** Ask for a proof-of-work challenge for the given API path. */
    fun createChallenge(cred: Credentials, targetPath: String): PowChallenge? {
        val res = post(
            "$BASE/api/v0/chat/create_pow_challenge",
            cred,
            JSONObject().put("target_path", targetPath).toString()
        )
        val ch = JSONObject(res.body)
            .optJSONObject("data")
            ?.optJSONObject("biz_data")
            ?.optJSONObject("challenge")
            ?: return null

        return PowChallenge(
            algorithm = ch.optString("algorithm"),
            challenge = ch.optString("challenge"),
            salt = ch.optString("salt"),
            signature = ch.optString("signature"),
            difficulty = ch.optLong("difficulty"),
            expireAt = ch.optLong("expire_at"),
            targetPath = targetPath,
        )
    }

    /**
     * Solve a challenge using the bundled WASM module, inside the WebView.
     *
     * The module is DeepSeek's own hashing implementation; the answer cannot be computed
     * any other way. Rather than port a WebAssembly runtime, this runs it in the page's
     * JS engine, which already exists and is already loaded.
     *
     * Returns null when the module reports no solution, which happens if the challenge
     * expires mid-solve.
     */
    suspend fun solve(ch: PowChallenge): Long? {
        val view = webView() ?: return null

        // The module is fetched once and cached on the page.
        val script = """
            (async function(){
              try {
                if (!window.__dsPowModule) {
                  // The bytes are handed in as base64, not fetched.
                  //
                  // The page's fetch is wrapped by the bridge hook and ended up calling
                  // itself, and an https page cannot reach 127.0.0.1 or file:// at all -
                  // both were refused. Passing the module in sidesteps the network
                  // entirely, and 26 KB does not need a request.
                  window.__dsStage = 'b64:' + String((window.__dsPowB64 || '').length);
                  var bin = atob(window.__dsPowB64 || '');
                  if (!bin.length) throw new Error('pow module was not provided');
                  var bytes = new Uint8Array(bin.length);
                  for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                  window.__dsStage = 'instantiating:' + bytes.length;
                  var mod = await WebAssembly.instantiate(bytes.buffer, {});
                  window.__dsPowModule = mod.instance;
                  window.__dsStage = 'instantiated';
                }
                window.__dsStage = 'exports';
                var ex = window.__dsPowModule.exports;
                var mem = ex.memory;
                var alloc = ex.__wbindgen_export_0;
                var addToStack = ex.__wbindgen_add_to_stack_pointer;
                var wasmSolve = ex.wasm_solve;

                function encode(str) {
                  var bytes = new TextEncoder().encode(str);
                  var ptr = alloc(bytes.length, 1);
                  new Uint8Array(mem.buffer).set(bytes, ptr);
                  return [ptr, bytes.length];
                }

                var prefix = ch_salt + '_' + ch_expire + '_';
                var c = encode(ch_challenge);
                var p = encode(prefix);
                var retptr = addToStack(-16);
                window.__dsStage = 'solving';
                wasmSolve(retptr, c[0], c[1], p[0], p[1], ch_difficulty);
                window.__dsStage = 'solved';
                var view = new DataView(mem.buffer);
                var status = view.getInt32(retptr, true);
                var answer = view.getFloat64(retptr + 8, true);
                addToStack(16);
                return JSON.stringify({ status: status, answer: answer });
              } catch (e) {
                window.__dsPowError = String(e && e.stack ? e.stack : e);
                return JSON.stringify({ error: String(e) });
              }
            })();
        """
            .replace("ch_salt", JSONObject.quote(ch.salt))
            .replace("ch_expire", ch.expireAt.toString())
            .replace("ch_challenge", JSONObject.quote(ch.challenge))
            .replace("ch_difficulty", ch.difficulty.toString())
            .trimIndent()

        // Hand the module to the page before the script that uses it.
        //
        // This used to live in evalAwait and ran on every awaited call, including the
        // credential read. evaluateJavascript is asynchronous over IPC, so the
        // assignment and the solve could arrive out of order - leaving the page with no
        // module and a promise that never settles.
        val b64 = powBase64 ?: try {
            android.util.Base64.encodeToString(powBytes(), android.util.Base64.NO_WRAP)
                .also { powBase64 = it }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read pow.wasm: ${e.message}")
            return null
        }
        eval("window.__dsPowB64 =" + org.json.JSONObject.quote(b64) + "; window.__dsPowModule = null;")
        kotlinx.coroutines.delay(120)

        val raw = evalAwait(script)
        if (raw == null) {
            // The page never settled. Report what it recorded, if anything, so a fetch
            // or instantiate failure is visible rather than appearing as a timeout.
            val why = eval("window.__dsPowError || ''")
            val stage = eval("window.__dsStage || ''")
            Log.w(TAG, "PoW produced no result; stage=" + (stage ?: "?") + " error=" + (why ?: "(none)"))
            return null
        }
        val obj = try {
            JSONObject(unquote(raw))
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse PoW result: ${e.message}")
            return null
        }

        if (obj.has("error")) {
            Log.w(TAG, "PoW failed: ${obj.optString("error")}")
            return null
        }
        if (obj.optInt("status", 0) == 0) return null
        return obj.optDouble("answer", -1.0).toLong()
    }

    /**
     * Send a completion and stream the reply.
     *
     * [messages] is joined into a single prompt because the web API takes one prompt per
     * turn; the caller is responsible for having shaped the conversation. [onDelta]
     * receives text as it arrives.
     */
    fun streamCompletion(
        cred: Credentials,
        sessionId: String,
        prompt: String,
        parentMessageId: String? = null,
        thinking: Boolean,
        search: Boolean,
        onDelta: (text: String, isReasoning: Boolean) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): Result<String> {
        val targetPath = "/api/v0/chat/completion"
        val challenge = createChallenge(cred, targetPath)
            ?: return Result.failure(Exception("Could not create a PoW challenge"))

        // The solve is blocking only inside the WebView's JS engine; calling it here
        // keeps the flow linear even though it runs on the main thread.
        val answer = solveBlocking(challenge)
            ?: return Result.failure(Exception("PoW could not be solved"))

        val powResponse = android.util.Base64.encodeToString(
            JSONObject(challenge.toJson().toString())
                .put("answer", answer)
                .put("target_path", targetPath)
                .toString()
                .toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        val payload = JSONObject()
            .put("chat_session_id", sessionId)
            .put("model_type", "default")
            .put("parent_message_id", parentMessageId ?: JSONObject.NULL)
            .put("action", JSONObject.NULL)
            .put("preempt", false)
            .put("prompt", prompt)
            .put("ref_file_ids", org.json.JSONArray())
            .put("search_enabled", search)
            .put("thinking_enabled", thinking)

        val conn = openConnection("$BASE$targetPath", cred).apply {
            doOutput = true
            setRequestProperty("X-Ds-Pow-Response", powResponse)
            connectTimeout = 20_000
            readTimeout = 300_000
        }

        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = readAll(conn.errorStream)
                return Result.failure(Exception("HTTP $code: ${err.take(200)}"))
            }

            val sb = StringBuilder()
            // Fragment index -> whether it is reasoning. -1 means the current fragment.
            val thinkFrags = HashSet<Int>()
            var curFrag = -1
            val reasoningBuf = StringBuilder()
            // Whether bare appends belong to reasoning; set when a THINK fragment is current.
            var reasoning = false
            // How much of the current fragment has come from snapshots.
            var snapshotLen = 0
            conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (isCancelled()) break
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val data = t.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    try {
                        val obj = JSONObject(data)
                        val path = obj.optString("p", "")
                        val op = obj.optString("o", "").uppercase()
                        val v = obj.opt("v")

                        when {
                            // A new fragment is announced. This is where thinking ends
                            // and the answer begins: the fragment carries a type, and
                            // every "-1" append after it belongs to that type.
                            //
                            // The initial snapshot announces fragments too, but only
                            // this later append marks the transition - without it the
                            // whole reply stayed in the reasoning channel.
                            path == "response/fragments" && v is org.json.JSONArray -> {
                                for (i in 0 until v.length()) {
                                    val f = v.optJSONObject(i) ?: continue
                                    val tp = f.optString("type").uppercase()
                                    if (tp == "THINK" || tp == "THINKING") {
                                        reasoning = true
                                    } else if (tp == "RESPONSE") {
                                        reasoning = false
                                    }
                                    // Seed text from the announcement, if it carries any.
                                    val seed = f.optString("content", "")
                                    if (seed.isNotEmpty()) {
                                        if (reasoning) {
                                            reasoningBuf.append(seed)
                                            onDelta(seed, true)
                                        } else {
                                            sb.append(seed)
                                            onDelta(seed, false)
                                        }
                                    }
                                }
                            }

                            // An append to a fragment's content is text.
                            path.contains("/fragments/") && path.endsWith("/content") &&
                                (op.isEmpty() || op == "APPEND") && v is String -> {
                                // "-1" addresses the current fragment; a concrete index
                                // is addressed directly, which this protocol does not use
                                // but keeps the branch honest.
                                val idx = Regex("/fragments/(-?\\d+)/").find(path)
                                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                                val isThink = if (idx >= 0) thinkFrags.contains(idx) else reasoning
                                if (v.isNotEmpty()) {
                                    if (isThink) {
                                        reasoningBuf.append(v)
                                        onDelta(v, true)
                                    } else {
                                        sb.append(v)
                                        onDelta(v, false)
                                    }
                                }
                            }

                            // A bare string v, with no path, is also text: the stream
                            // sends some characters this way and they would otherwise be
                            // lost. Nothing else here is a bare string.
                            path.isEmpty() && v is String -> {
                                if (v.isNotEmpty()) {
                                    if (reasoning) {
                                        reasoningBuf.append(v)
                                        onDelta(v, true)
                                    } else {
                                        sb.append(v)
                                        onDelta(v, false)
                                    }
                                }
                            }

                            // A snapshot of the whole response. Its RESPONSE fragments
                            // are the answer, and a stream may open with one.
                            v is JSONObject -> {
                                val frags = v.optJSONObject("response")?.optJSONArray("fragments")
                                if (frags != null) {
                                    for (i in 0 until frags.length()) {
                                        val f = frags.optJSONObject(i) ?: continue
                                        // Remember which fragments are reasoning, so the
                                        // appends that follow can be routed correctly.
                                        // The LAST announced fragment is the one that
                                        // subsequent "-1" appends belong to, so its type
                                        // decides how those appends are routed.
                                        val tp = f.optString("type").uppercase()
                                        when (tp) {
                                            "THINK", "THINKING" -> reasoning = true
                                            "RESPONSE" -> reasoning = false
                                            else -> continue
                                        }

                                        // A snapshot carries the text too, so it is taken
                                        // here rather than skipped: the answer may arrive
                                        // entirely inside one snapshot.
                                        if (tp == "RESPONSE") {
                                            val c = f.optString("content", "")
                                            if (c.length > snapshotLen) {
                                                val extra = c.substring(snapshotLen)
                                                sb.append(extra)
                                                onDelta(extra, false)
                                                snapshotLen = c.length
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Not a JSON frame we understand; skip it.
                    }
                }
            }
            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    // -- plumbing -------------------------------------------------------------

    /** Run [solve] from a blocking context, since the caller is already off the UI thread. */
    private fun solveBlocking(ch: PowChallenge): Long? =
        kotlinx.coroutines.runBlocking { solve(ch) }

    /** The PoW module's bytes, supplied by the app when it constructs this. */
    private fun powBytes(): ByteArray =
        powWasm ?.invoke() ?: throw IllegalStateException("pow.wasm is not available")

    /** Cached base64 of the module. */
    private var powBase64: String? = null

    private data class Response(val code: Int, val body: String)

    private fun post(url: String, cred: Credentials, body: String): Response {
        val conn = openConnection(url, cred).apply {
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 200..299) readAll(conn.inputStream) else readAll(conn.errorStream)
            Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String, cred: Credentials): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Cookie", cred.cookie)
            setRequestProperty("Authorization", "Bearer ${cred.bearer}")
            setRequestProperty("Referer", "https://chat.deepseek.com/")
            setRequestProperty("Origin", "https://chat.deepseek.com")
            setRequestProperty("x-client-platform", CLIENT_PLATFORM)
            setRequestProperty("x-client-version", CLIENT_VERSION)
            setRequestProperty("x-client-locale", "en_US")
        }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use {
            val out = ByteArrayOutputStream()
            it.copyTo(out)
            out.toString("UTF-8")
        }
    }

    /** A single evaluateJavascript call, as a suspending function. */
    private suspend fun eval(script: String): String? =
        suspendCancellableCoroutine { cont ->
            val view = webView()
            if (view == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            view.post {
                view.evaluateJavascript(script) { value -> cont.resume(value) }
            }
        }

    /**
     * Run a script on the page that produces a promise, and wait for its value.
     *
     * evaluateJavascript only reports a synchronous return value, so the script's own
     * result is stored on window and polled for. The script is sent as its own statement
     * - never wrapped in a call, never wrapped in another function - because it is
     * already a complete IIFE and either wrapping breaks it:
     *
     *   Promise.resolve(<IIFE ending in "})();">)  -> the ";" ends the statement early
     *   (function(){ <IIFE> })()                   -> the inner result is discarded
     *
     * The body is therefore evaluated bare, with its promise picked up from
     * window.__dsPromise, which the template assigns in the same expression.
     */
    private suspend fun evalAwait(script: String, timeoutMs: Long = 30_000): String? {
        // A unique name per call, so two waits in flight cannot read each other's result.
        val slot = "__dsResult_" + System.nanoTime()

        val wrapped = """
            (function(){
              window.$slot = null;
              window.__dsStage = 'starting';
              if (typeof WebAssembly === 'undefined') {
                window.$slot = JSON.stringify({ error: 'WebAssembly unavailable' });
                return 'started';
              }
              try {
                window.__dsStage = 'invoking';
                var p = (function(){
                  return $script
                })();
                window.__dsStage = 'returned';
                Promise.resolve(p).then(function(v){ window.$slot = v; })
                                  .catch(function(e){ window.$slot = JSON.stringify({ error: String(e) }); });
              } catch (e) {
                window.$slot = JSON.stringify({ error: String(e) });
              }
              return 'started';
            })();
        """.trimIndent()

        eval(wrapped) ?: return null

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
            val v = eval("window.$slot")
            if (!v.isNullOrBlank() && v != "null") {
                eval("window.$slot = null;")
                return v
            }
        }

        Log.w(
            TAG,
            "Timed out waiting for the page; stage=" +
                (eval("window.__dsStage || ''") ?: "?")
        )
        return null
    }

    private fun unquote(raw: String): String {
        val t = raw.trim()
        if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return t
        return try {
            org.json.JSONTokener(t).nextValue() as? String ?: t
        } catch (e: Exception) {
            t.trim('"')
        }
    }
}
