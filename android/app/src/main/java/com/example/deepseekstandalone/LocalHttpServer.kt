package com.example.deepseekstandalone

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

interface ServerHandler {
    fun isApiEnabled(): Boolean
    fun isLoggedIn(): Boolean
    /**
     * Transport state for /status.
     *
     * This build has no tunnel: clients talk to 127.0.0.1 directly, so there is
     * nothing to connect. The key stays because the web UI's health check reads
     * it, and removing it would make an expected field look like a failure.
     */
    fun getTunnelStatus(): String = "local"
    fun getLastError(): String?
    fun getErrorLog(): List<String>
    /** The conversation the page is showing, for a client's own bookkeeping. */
    suspend fun currentChatUrl(): String = ""
    /** Move the page to a conversation and answer whether it settled. */
    suspend fun openChat(url: String): Boolean = false
    /** Mirror the client's DeepThink / Search switches onto the page. */
    suspend fun applyToggles(thinking: Boolean, search: Boolean): Boolean = false
    suspend fun sendPrompt(prompt: String, onChunk: (String) -> Unit): Result<Unit>
    /** Same, with the page-level DeepThink / web-search options applied first. */
    suspend fun sendPrompt(
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        onChunk: (String) -> Unit
    ): Result<Unit> = sendPrompt(prompt, onChunk)
    /**
     * Send a prompt into a named conversation.
     *
     * The page IS the conversation: a prompt is typed into whatever chat it is
     * showing. Without the URL every client conversation pours its messages into
     * one chat, so the caller names the one it means. A blank URL leaves the page
     * where it is, which is right for a client with no notion of chats.
     */
    suspend fun sendPrompt(
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        chatUrl: String,
        onChunk: (String) -> Unit
    ): Result<Unit> = sendPrompt(prompt, thinking, search, onChunk)
    /**
     * The rendered answer text from the page. Used as the response body when the
     * site's own stream does not surface its content patches to us.
     */
    suspend fun readRenderedAnswer(): String
    /**
     * The rendered DeepThink / reasoning text for the newest turn, or "" when
     * thinking was off. Emitted as delta.reasoning_content.
     */
    suspend fun readRenderedReasoning(): String = ""
    /** TEMPORARY diagnostic: live transcript structure. */
    suspend fun debugTranscript(): String = "{}"
    /** TEMPORARY diagnostic: innerText vs textContent for the last answer. */
    suspend fun debugAnswerText(): String = "{}"
    /** TEMPORARY diagnostic: raw network stream capture. */
    suspend fun rawStreamStats(): String = "{}"
    /** TEMPORARY diagnostic: is the network hook installed? */
    suspend fun hookPresence(): String = "{}"
    /**
     * Full answer text reconstructed from the raw stream, or "" if none.
     *
     * Preferred over scraping: it comes from the same bytes the page received,
     * so it cannot return an older turn or a half-rendered paragraph.
     */
    suspend fun streamedAnswerText(): String = ""
    /** Same, for the DeepThink text. */
    suspend fun streamedReasoningText(): String = ""
    /**
     * True when the page is offering a Continue control, meaning the reply was
     * cut off for length and what was sent is a prefix.
     */
    suspend fun isContinueOffered(): Boolean = false
    /** The newest assistant reply already present on the page, or "" if none. */
    suspend fun recoverLastAnswer(): Pair<String, String> = "" to ""
    /**
     * Scroll the transcript, tap the page's own copy control and read what it
     * put on the clipboard.
     *
     * This is the only way to obtain the site's authoritative markdown. Its
     * transcript is virtualised, so an off-screen reply is absent from the DOM
     * entirely, and the rendered text replaces code fences with the copy and
     * download controls. The button itself holds the clean string.
     */
    suspend fun copyLastReplyViaButton(): String = ""
    /** Record parsed text so the finished answer can be rebuilt from the wire. */
    fun noteStreamedText(text: String, isReasoning: Boolean) {}
    /**
     * Ask the user whether to fall back to scraping the page.
     *
     * Returns true to proceed, false to fail the request instead. Implementations
     * should show a prompt; the default declines, because a silent DOM read is
     * how stale answers were previously returned.
     */
    suspend fun confirmDomFallback(reason: String): Boolean = false
    /** Click the site's stop control and abandon the in-flight completion. */
    suspend fun stopGeneration(): Boolean
    /** True when the page is showing a bot/human verification overlay. */
    suspend fun isVerificationShowing(): Boolean
    /** Reload the page to clear that overlay. Does not bypass it. */
    suspend fun reloadAndWait(): Boolean
}

class LocalHttpServer(
    host: String = "127.0.0.1",
    port: Int = 8080,
    private val scope: CoroutineScope,
    private val handler: ServerHandler,
    private val onErrorLogged: (String) -> Unit
) : NanoHTTPD(host, port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Answer CORS preflight before dispatch.
        //
        // In the tunnelled build the Cloudflare Worker added these headers, so
        // the local server never needed them. Here the browser page calls
        // 127.0.0.1 directly, which is cross-origin: without a preflight
        // response and an allow-origin header the fetch is blocked and every
        // request fails before it is sent.
        if (method == Method.OPTIONS) {
            return withCors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
        }

        return withCors(dispatch(session, uri, method))
    }

    private fun dispatch(session: IHTTPSession, uri: String, method: Method): Response {
        return when {
            uri == "/chat" && method == Method.POST -> handleChat(session)
            (uri == "/coding/chat/completions" || uri == "/general/chat/completions") && method == Method.POST -> handleChatCompletions(session)
            // Non-streaming: one JSON response with the finished text.
            //
            // Streaming sends one SSE frame per token. A client that renders
            // each frame individually is slow and can be overwhelmed by a long
            // answer, so these return the assembled text once, when generation
            // has actually finished.
            uri == "/chat/full" && method == Method.POST -> handleChatFull(session, openAiShape = false)
            (uri == "/coding/chat/completions/full" || uri == "/general/chat/completions/full") &&
                method == Method.POST -> handleChatFull(session, openAiShape = true)
            uri == "/status" && method == Method.GET -> handleStatus()
            uri == "/open" && method == Method.POST -> handleOpen(session)
            uri == "/toggles" && method == Method.POST -> handleToggles(session)
            // Recover the last reply the page already produced.
            //
            // For when a request failed but the answer exists on the page - a
            // timeout, a dropped stream, a throttled call. Reading it back costs
            // nothing and avoids spending another generation on a retry.
            (uri == "/last" || uri == "/last/full" ||
                uri == "/coding/chat/completions/last" ||
                uri == "/general/chat/completions/last") &&
                (method == Method.GET || method == Method.POST) -> handleLastResponse(uri)
            // Stop the in-flight generation in the page.
            uri == "/stop" && (method == Method.POST || method == Method.GET) -> handleStop()
            uri == "/errors" && method == Method.GET -> handleErrors()
            else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }
    }

    /**
     * Return the most recent assistant reply that is present on the page.
     *
     * This does not run a completion: it copies what the page already holds and
     * reports it in the shape the caller asked for. Nothing is sent to DeepSeek,
     * so a client can recover from a network failure without spending a turn.
     */
    private fun handleLastResponse(uri: String): Response {
        // Ask the page for the copy button's own text first.
        //
        // This is the site's authoritative markdown - the same string its copy
        // control writes to the clipboard. Reading the rendered DOM does not
        // give it: the transcript is virtualised, so an off-screen reply is not
        // in the DOM at all, and the rendered text has code fences replaced by
        // the copy/download controls. Only when this fails does the stream
        // buffer get used, and only then the DOM.
        val copied = runBlocking {
            try { handler.copyLastReplyViaButton() } catch (e: Exception) { "" }
        }

        if (copied.isNotBlank()) {
            onErrorLogged("/last: took ${copied.length} chars from the page's copy control")
            return respondWithReply(uri, copied, "", recovered = true)
        }

        val (answer, reasoning) = runBlocking {
            try { handler.recoverLastAnswer() } catch (e: Exception) { "" to "" }
        }

        if (answer.isBlank() && reasoning.isBlank()) {
            return jsonResponse(
                Response.Status.NOT_FOUND,
                JSONObject().put(
                    "error",
                    JSONObject().put("message", "The page has no assistant reply to read.")
                )
            )
        }

        onErrorLogged("/last: recovered ${answer.length} chars from the page")
        return respondWithReply(uri, answer, reasoning, recovered = true)
    }

    /**
     * Wrap a recovered reply in whichever envelope the caller's route expects.
     *
     * Shared so the copy-button path and the stream/DOM path cannot drift apart
     * in shape.
     */
    private fun respondWithReply(
        uri: String,
        answer: String,
        reasoning: String,
        recovered: Boolean
    ): Response {
        val openAiShape = uri.contains("chat/completions")
        val payload = if (openAiShape) {
            JSONObject().apply {
                put("id", "chatcmpl-" + UUID.randomUUID().toString())
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", "deepseek-web")
                put("x_recovered", recovered)
                put(
                    "choices",
                    JSONArray().put(
                        JSONObject().apply {
                            put("index", 0)
                            put(
                                "message",
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", answer)
                                    if (reasoning.isNotBlank()) put("reasoning_content", reasoning)
                                }
                            )
                            put("finish_reason", "stop")
                        }
                    )
                )
            }
        } else {
            JSONObject().apply {
                put("content", answer)
                if (reasoning.isNotBlank()) put("reasoning_content", reasoning)
                put("x_recovered", recovered)
            }
        }
        return jsonResponse(Response.Status.OK, payload)
    }

    /**
     * Non-streaming completion: run the turn to completion, return the text once.
     *
     * Nothing is written to the client until the page stops generating, so the
     * response is a single JSON document. Deltas are still consumed internally
     * (that is how progress and the answer are observed) but are not forwarded,
     * which is what keeps a long answer from arriving as hundreds of frames.
     */
    private fun handleChatFull(session: IHTTPSession, openAiShape: Boolean): Response {
        if (!handler.isApiEnabled()) {
            return jsonResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                JSONObject().put("error", JSONObject().put("message", "API service disabled"))
            )
        }
        if (!handler.isLoggedIn()) {
            return jsonResponse(
                Response.Status.UNAUTHORIZED,
                JSONObject().put("error", JSONObject().put("message", "Not logged in"))
            )
        }

        val bodyMap = HashMap<String, String>()
        try {
            session.parseBody(bodyMap)
        } catch (e: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", JSONObject().put("message", "Failed to parse body: ${e.message}"))
            )
        }

        val postData = bodyMap["postData"] ?: ""
        val jsonReq = try { JSONObject(postData) } catch (e: Exception) { JSONObject() }

        // Accept the simple {"prompt": "..."} form and the OpenAI messages form.
        val prompt = jsonReq.optString("prompt", "").ifBlank {
            val messages = jsonReq.optJSONArray("messages")
            if (messages != null && messages.length() > 0) {
                messages.getJSONObject(messages.length() - 1).optString("content", "")
            } else ""
        }
        // The conversation this belongs to, when the client names one.
        val chatUrl = jsonReq.optString("chat_url", "")
        // The page-level options, read the same way as the streaming route so the
        // two behave identically.
        val thinkingEnabled =
            jsonReq.optJSONObject("thinking")?.optString("type")
                ?.equals("enabled", ignoreCase = true) == true
        val searchEnabled =
            jsonReq.optJSONObject("search")?.optBoolean("enabled", false) == true
        if (prompt.isBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", JSONObject().put("message", "Missing or empty prompt"))
            )
        }

        // Accept "stream" for shape compatibility but always answer in one piece.
        val wantReasoning = jsonReq.optBoolean("include_reasoning", false)
        val model = jsonReq.optString("model", "deepseek-web")

        var answer = StringBuilder()
        var reasoning = StringBuilder()

        // Reset per-turn parser state.
        //
        // This only happened on the OpenAI-shaped endpoint, so the plain
        // handlers inherited the previous turn's fragment types: the opening
        // append of a new turn inherited the old THINK flag and the model's
        // reasoning was emitted as answer text.
        resetFragmentTracking()

        // Inactivity-based waiting, not a wall-clock deadline.
        //
        // A fixed 170s ceiling cut off long answers that were still arriving and
        // reported them as network errors, which they were not. What indicates a
        // dead request is silence, so the watchdog is reset every time text
        // arrives: a slow answer may run as long as it keeps making progress,
        // while a genuinely stalled one fails after the quiet window.
        val lastProgressAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        /** Set by the watchdog when it gives up on a silent stream. */
        var stalled = false
        var sendResult: Result<Unit>? = null

        val result = runBlocking {
            try {
                // The send runs normally; a separate watchdog observes progress
                // and cancels only when the stream has gone quiet.
                val sendJob = launch {
                    sendResult = handler.sendPrompt(prompt, thinkingEnabled, searchEnabled, chatUrl) { chunk ->
                        val (text, isReasoning) = extractDelta(chunk)
                        if (text.isEmpty()) return@sendPrompt
                        if (isReasoning) reasoning.append(text) else answer.append(text)
                        // Also record it in the bridge.
                        //
                        // The streaming handler did this and this one did not, so
                        // a reply fetched through /full left the bridge's raw
                        // accumulator empty. recoverLastAnswer then fell through
                        // to the DOM fallback and returned rendered text - with
                        // the code fences replaced by the copy/download buttons
                        // and the file-command markers stripped, so a recovered
                        // reply could not drive file edits.
                        handler.noteStreamedText(text, isReasoning)
                        lastProgressAt.set(System.currentTimeMillis())
                    }
                }

                val watchdog = launch {
                    while (isActive) {
                        delay(500L)
                        val quietFor = System.currentTimeMillis() - lastProgressAt.get()
                        if (quietFor >= INACTIVITY_TIMEOUT_MS) {
                            onErrorLogged(
                                "No output for ${INACTIVITY_TIMEOUT_MS / 1000}s; " +
                                    "stopping and reporting the stall (not a length timeout)"
                            )
                            // Abandon the in-flight generation in the page too,
                            // so it does not keep running unattended.
                            runCatching { handler.stopGeneration() }
                            stalled = true
                            sendJob.cancel()
                            return@launch
                        }
                    }
                }

                sendJob.join()
                watchdog.cancel()

                if (stalled) {
                    Result.failure(
                        Exception(
                            "The page stopped producing output for " +
                                "${INACTIVITY_TIMEOUT_MS / 1000}s. Any text already received " +
                                "is kept; use the fetch button to pull the full reply."
                        )
                    )
                } else {
                    sendResult ?: Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        // The stream can finish while the page is still settling; the
        // accumulated wire text is the authoritative finished answer.
        var finalAnswer = answer.toString()
        var finalReasoning = reasoning.toString()
        if (finalAnswer.isBlank() || finalReasoning.isBlank()) {
            val (wireAnswer, wireReasoning) = runBlocking {
                val a = try { handler.streamedAnswerText() } catch (e: Exception) { "" }
                val r = try { handler.streamedReasoningText() } catch (e: Exception) { "" }
                a to r
            }
            if (finalAnswer.isBlank()) finalAnswer = wireAnswer
            if (finalReasoning.isBlank()) finalReasoning = wireReasoning
        }

        if (finalAnswer.isBlank()) {
            val msg = result.exceptionOrNull()?.message
                ?: "No content arrived on the network stream. This usually means DeepSeek " +
                    "throttled the request - retry shortly."
            onErrorLogged("/full: no content ($msg)")
            return jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", JSONObject().put("message", msg))
            )
        }

        // Ask the page whether it is still offering a Continue.
        //
        // DeepSeek cuts a long answer off and shows that control; the text we
        // hold is then a prefix. Reporting it lets the client say so instead of
        // presenting a partial answer as complete. Failure to determine this is
        // treated as "not truncated": a spurious warning on every reply would be
        // worse than missing one.
        //
        // runBlocking because handleChatFull is a plain function called from
        // NanoHTTPD's worker thread, and this is a quick DOM query.
        val truncated = runBlocking {
            try { withTimeoutOrNull(5_000L) { handler.isContinueOffered() } ?: false }
            catch (e: Exception) { false }
        }
        if (truncated) {
            onErrorLogged("/full: reply was cut off; the page is offering Continue")
        }

        val payload = if (openAiShape) {
            JSONObject().apply {
                put("id", "chatcmpl-" + UUID.randomUUID().toString())
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", model)
                // OpenAI uses finish_reason "length" for exactly this case, so
                // a standard client understands it without extra handling.
                put("x_truncated", truncated)
                put(
                    "choices",
                    JSONArray().put(
                        JSONObject().apply {
                            put("index", 0)
                            put(
                                "message",
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", finalAnswer)
                                    if (wantReasoning && finalReasoning.isNotBlank()) {
                                        put("reasoning_content", finalReasoning)
                                    }
                                }
                            )
                            put("finish_reason", if (truncated) "length" else "stop")
                        }
                    )
                )
            }
        } else {
            JSONObject().apply {
                put("content", finalAnswer)
                if (wantReasoning && finalReasoning.isNotBlank()) {
                    put("reasoning_content", finalReasoning)
                }
                put("x_truncated", truncated)
            }
        }

        return jsonResponse(Response.Status.OK, payload)
    }

    /**
     * Allow the GitHub-hosted UI to call this server.
     *
     * "*" is deliberate: the server only listens on 127.0.0.1, so only software
     * already running on this device can reach it, and the page origin varies
     * (github.io, a local file, a dev server).
     */
    private fun <T : Response> withCors(response: T): T = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Max-Age", "86400")
    }

    /** Write one OpenAI-style delta chunk. Shared by both chat endpoints. */
    private fun emitChunk(
        pipe: StreamPipe,
        chunkId: String,
        kind: String,
        value: String
    ) {
        val obj = JSONObject().apply {
            put("id", chunkId)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("choices", JSONArray().put(
                JSONObject().apply {
                    put("index", 0)
                    put("delta", JSONObject().put(kind, value))
                    put("finish_reason", JSONObject.NULL)
                }
            ))
        }
        pipe.write("data: $obj\n\n".toByteArray(Charsets.UTF_8))
    }

    private fun handleChat(session: IHTTPSession): Response {
        if (!handler.isApiEnabled()) {
            return jsonResponse(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "API service disabled"))
        }

        if (!handler.isLoggedIn()) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Not logged in"))
        }

        val bodyMap = HashMap<String, String>()
        try {
            session.parseBody(bodyMap)
        } catch (e: Exception) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Failed to parse body: ${e.message}"))
        }

        val postData = bodyMap["postData"] ?: ""
        val prompt = try {
            JSONObject(postData).optString("prompt", "")
        } catch (e: Exception) {
            ""
        }

        if (prompt.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Missing or empty prompt"))
        }

        val pipe = StreamPipe()
        val chunkId = "chatcmpl-" + UUID.randomUUID().toString()

        // Reset per-turn parser state; see handleChatFull for why.
        resetFragmentTracking()

        scope.launch(Dispatchers.IO) {
            try {
                // Route the raw patches through the SAME parser the
                // OpenAI-compatible endpoint uses.
                //
                // This handler previously wrote each captured payload verbatim
                // ("data: " + raw). The hook now captures the site's own
                // JSON-Patch frames, so the client received protocol noise
                // ("{\"p\":\"response/fragments/-1/content\",...}") instead of
                // an answer - and never a delta.content at all.
                var emittedAny = false
                var reasoningSeen = false
                val patchReasoning = StringBuilder()

                val result = handler.sendPrompt(prompt) { chunk ->
                    val (text, isReasoning) = extractDelta(chunk)
                    if (text.isEmpty()) return@sendPrompt

                    if (isReasoning) {
                        patchReasoning.append(text)
                        emitChunk(pipe, chunkId, "reasoning_content", text)
                    } else {
                        emitChunk(pipe, chunkId, "content", text)
                    }
                    emittedAny = true
                    reasoningSeen = reasoningSeen || isReasoning
                }

                if (result.isSuccess) {
                    // Nothing parsed from the live stream: rebuild the finished
                    // answer from the wire text we accumulated, which avoids the
                    // DOM entirely and cannot return an older turn.
                    if (!emittedAny) {
                        val full = try { handler.streamedAnswerText() } catch (e: Exception) { "" }
                        if (full.isNotBlank()) {
                            onErrorLogged("/chat: recovered ${full.length} chars from the stream")
                            val r = try { handler.streamedReasoningText() } catch (e: Exception) { "" }
                            if (r.isNotBlank()) emitChunk(pipe, chunkId, "reasoning_content", r)
                            emitChunk(pipe, chunkId, "content", full)
                            emittedAny = true
                        }
                    }
                    if (!emittedAny) {
                        onErrorLogged("/chat: no content on the stream for this message")
                        val errJson = JSONObject().put(
                            "error",
                            JSONObject().put(
                                "message",
                                "No content arrived on the network stream. This usually " +
                                    "means DeepSeek throttled the request - retry shortly."
                            )
                        ).toString()
                        pipe.write("data: $errJson\n\n".toByteArray(Charsets.UTF_8))
                    }
                    pipe.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Send failed"
                    val errJson = JSONObject().put("error", errMsg).toString()
                    pipe.write("data: $errJson\n\n".toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                onErrorLogged("Server send error: ${e.message}")
            } finally {
                pipe.finish()
            }
        }

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipe)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        return response
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        if (!handler.isApiEnabled()) {
            return jsonResponse(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", JSONObject().put("message", "API service disabled")))
        }

        if (!handler.isLoggedIn()) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", JSONObject().put("message", "Not logged in")))
        }

        val bodyMap = HashMap<String, String>()
        try {
            session.parseBody(bodyMap)
        } catch (e: Exception) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", JSONObject().put("message", "Failed to parse body: ${e.message}")))
        }

        val postData = bodyMap["postData"] ?: ""
        val jsonReq = try {
            JSONObject(postData)
        } catch (e: Exception) {
            JSONObject()
        }

        val model = jsonReq.optString("model", "glm-5.3")
        val thinkingObj = jsonReq.optJSONObject("thinking")
        val thinkingEnabled = thinkingObj?.optString("type")?.equals("enabled", ignoreCase = true) == true
        val searchEnabled = jsonReq.optJSONObject("search")
            ?.optBoolean("enabled", false) == true
        // Which DeepSeek chat this request belongs to. Blank means "leave the page
        // where it is", which is what a client without conversations sends.
        val chatUrl = jsonReq.optString("chat_url", "")
        // A plain "prompt" is accepted as well as a messages array, matching the
        // desktop API, so a client can send the simplest possible body.
        val plainPrompt = jsonReq.optString("prompt", "").trim()
        val messages = jsonReq.optJSONArray("messages")

        val parsed = if (plainPrompt.isNotEmpty()) {
            Pair(plainPrompt, true)
        } else {
            parseMessagesToPrompt(messages)
        }
        val prompt = parsed.first
        val hasUser = parsed.second
        if (!hasUser || prompt.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", JSONObject().put("message", "No user message found")))
        }

        val pipe = StreamPipe()

        scope.launch(Dispatchers.IO) {
            val chunkId = "chatcmpl-" + UUID.randomUUID().toString()
            var emittedAny = false

            // Reasoning seen in the completion patches. Preferred over the DOM
            // because it belongs to this exact turn.
            val patchReasoning = StringBuilder()

            // Temporary: parser accounting so a silent rejection is visible.
            var rawSeen = 0
            var parsedOk = 0
            val parseSamples = ArrayList<String>()

            fun emit(kind: String, value: String) {
                val obj = JSONObject().apply {
                    put("id", chunkId)
                    put("object", "chat.completion.chunk")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", model)
                    val deltaObj = JSONObject().put(kind, value)
                    val choiceObj = JSONObject().apply {
                        put("index", 0)
                        put("delta", deltaObj)
                        put("finish_reason", JSONObject.NULL)
                    }
                    put("choices", JSONArray().put(choiceObj))
                }
                pipe.write("data: $obj\n\n".toByteArray(Charsets.UTF_8))
                emittedAny = true
            }

            try {
                // Fresh fragment bookkeeping for this turn, otherwise the
                // previous request's fragment types leak into this one.
                resetFragmentTracking()

                // Clear a stuck verification overlay BEFORE sending. Checking
                // only after the send is too late: the Worker allows 60s for the
                // first frame, and a doomed attempt burns that budget, so the
                // retry can never deliver.
                if (handler.isVerificationShowing()) {
                    onErrorLogged("Verification overlay already present; reloading first")
                    handler.reloadAndWait()
                }

                // Snapshot the transcript BEFORE sending.
                //
                // With the document-start hook this is now only a SAFETY NET:
                // the stream normally supplies the text, and the DOM is read
                // only if the stream produced nothing. The snapshot still
                // matters for that fallback, because the page keeps previous
                // turns mounted and a naive read returns the older answer.
                val answerBefore = try { handler.readRenderedAnswer() } catch (e: Exception) { "" }
                val reasoningBefore = try { handler.readRenderedReasoning() } catch (e: Exception) { "" }

                var result = handler.sendPrompt(prompt, thinkingEnabled, searchEnabled, chatUrl) { chunk ->
                    rawSeen++
                    val (text, isReasoning) = extractDelta(chunk)
                    if (text.isNotEmpty()) {
                        parsedOk++
                        // Accumulate so the finished text is available even if
                        // the client disconnects or deltas were dropped.
                        handler.noteStreamedText(text, isReasoning && thinkingEnabled)
                        if (isReasoning && thinkingEnabled) {
                            // Remember it so the fallback path below does not
                            // have to go to the DOM for reasoning.
                            patchReasoning.append(text)
                            emit("reasoning_content", text)
                        } else {
                            emit("content", text)
                        }
                    } else if (parseSamples.size < 6) {
                        // Keep a few unparsed payloads so the failure is visible.
                        parseSamples.add(chunk.take(110))
                    }
                }

                // The stream produced nothing: fall back to the finished text
                // rebuilt from the wire, and only then ask about scraping.
                // (See below.)

                // The stream is the primary source; the hook also accumulates
                // the full text, which is used here instead of scraping the
                // page. Scraping is a last resort and asks first.
                var rendered = ""
                if (result.isSuccess && !emittedAny) {
                    // Preferred recovery: the raw bytes we already captured.
                    val fromStream = try { handler.streamedAnswerText() } catch (e: Exception) { "" }
                    if (fromStream.isNotBlank()) {
                        onErrorLogged("Using ${fromStream.length} chars reconstructed from the raw stream")
                        emit("content", fromStream)
                    } else {
                        val reason = "No content arrived on the network stream for this message."
                        onErrorLogged("Stream produced no content; asking before DOM fallback")
                        val proceed = try { handler.confirmDomFallback(reason) }
                        catch (e: Exception) { false }

                        if (proceed) {
                            onErrorLogged("User approved DOM fallback")
                            rendered = readRenderedWithWait(answerBefore)
                        } else {
                            val errObj = JSONObject().put(
                                "error",
                                JSONObject().put(
                                    "message",
                                    "No content on the network stream, and the page-scraping " +
                                        "fallback was declined. This is usually DeepSeek " +
                                        "throttling the request - wait a moment and retry."
                                )
                            )
                            pipe.write("data: ${errObj}\n\n".toByteArray(Charsets.UTF_8))
                            pipe.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                            return@launch
                        }
                    }
                }

                // A verification interstitial is often transient. Reload the page
                // once and resend rather than failing immediately. This never
                // attempts to solve or bypass the check - it only clears the
                // stuck overlay so the user can complete it if it returns.
                if (result.isSuccess && !emittedAny && isChallengeText(rendered)) {
                    onErrorLogged("Verification overlay detected; reloading and retrying once")
                    val ready = try { handler.reloadAndWait() } catch (e: Exception) { false }
                    if (ready && !handler.isVerificationShowing()) {
                        result = handler.sendPrompt(prompt, thinkingEnabled, searchEnabled, chatUrl) { chunk ->
                            val (text, isReasoning) = extractDelta(chunk)
                            if (text.isNotEmpty()) {
                                if (isReasoning && thinkingEnabled) emit("reasoning_content", text)
                                else emit("content", text)
                            }
                        }
                        if (result.isSuccess && !emittedAny) {
                            // The page was reloaded, so the transcript is being
                            // rebuilt; the pre-send snapshot no longer applies.
                            // Take a fresh baseline of the PREVIOUS turn (the
                            // page shows history after a reload) and wait for it
                            // to change.
                            kotlinx.coroutines.delay(500L)
                            val baseline = try { handler.readRenderedAnswer() }
                            catch (e: Exception) { "" }
                            rendered = readRenderedWithWait(baseline)
                        }
                    }
                }

                if (result.isSuccess && !emittedAny) {
                    if (isChallengeText(rendered)) {
                        // Still blocked: the check needs a human. Reporting it as
                        // content would be worse than failing.
                        onErrorLogged("Blocked by site verification: $rendered")
                        val errObj = JSONObject().put(
                            "error",
                            JSONObject().put(
                                "message",
                                "DeepSeek is showing a verification prompt. Open the " +
                                    "bridge app on the phone, complete the check on the " +
                                    "page, then send again."
                            )
                        )
                        pipe.write("data: ${errObj}\n\n".toByteArray(Charsets.UTF_8))
                        pipe.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                        return@launch
                    }

                    // Emit the reasoning BEFORE the answer, matching how
                    // reasoning models stream: thinking first, then content.
                    // Some clients render them as separate blocks and would
                    // otherwise show an empty thought panel.
                    if (thinkingEnabled) {
                        // Prefer the thinking scraped from the completion
                        // patches; the DOM is only a fallback. This avoids the
                        // virtual-list problems that made DOM reads return an
                        // earlier turn's content.
                        var reasoning = patchReasoning.toString()
                        if (reasoning.isBlank()) {
                            // Same settle rule as the answer: the thinking panel
                            // also fills progressively, so taking the first
                            // change yielded a truncated prefix.
                            // Budget: the Worker drops the request at 60s, so
                            // this wait must leave room for the answer read
                            // that follows. 25s is ample for a thinking panel.
                            var lastThink = ""
                            var lastThinkChange = 0L
                            val rDeadline = System.currentTimeMillis() + 25_000L
                            while (System.currentTimeMillis() < rDeadline) {
                                val cur = try { handler.readRenderedReasoning() }
                                catch (e: Exception) {
                                    onErrorLogged("Failed to read rendered reasoning: ${e.message}")
                                    ""
                                }
                                if (cur.isNotBlank() && cur != reasoningBefore) {
                                    if (cur != lastThink) {
                                        lastThink = cur
                                        lastThinkChange = System.currentTimeMillis()
                                    } else if (System.currentTimeMillis() - lastThinkChange >= SETTLE_MS) {
                                        reasoning = cur
                                        break
                                    }
                                }
                                kotlinx.coroutines.delay(300L)
                            }
                            // A still-growing prefix is better than nothing.
                            if (reasoning.isBlank() && lastThink.isNotBlank() &&
                                lastThink != reasoningBefore
                            ) {
                                reasoning = lastThink
                            }
                        }
                        if (reasoning.isNotBlank() && !isChallengeText(reasoning)) {
                            emit("reasoning_content", reasoning)
                        }
                    }

                    // The answer itself. This MUST be emitted: an earlier edit
                    // replaced this line with the empty-answer guard below,
                    // which silently dropped every response while reasoning
                    // still streamed - the "reasoning perfect, responses empty"
                    // symptom.
                    if (rendered.isNotBlank()) {
                        emit("content", rendered)
                    } else {
                        // Report what the page actually showed, so a silent
                        // empty reply is diagnosable from /errors alone.
                        val nowRaw = try { handler.readRenderedAnswer() } catch (e: Exception) { "" }
                        onErrorLogged(
                            "No answer text: before=" + answerBefore.take(40) +
                                " | now=" + nowRaw.take(40) +
                                " | emittedAny=" + emittedAny
                        )
                    }
                }

                if (result.isSuccess) {
                    // Tell a streaming client when the page cut the reply short,
                    // so it can prompt for Continue rather than treat a prefix as
                    // the whole answer.
                    val truncated = try { handler.isContinueOffered() } catch (e: Exception) { false }
                    if (truncated) {
                        onErrorLogged("/chat: reply was cut off; the page is offering Continue")
                    }
                    val finalChunkObj = JSONObject().apply {
                        put("id", chunkId)
                        put("object", "chat.completion.chunk")
                        put("created", System.currentTimeMillis() / 1000)
                        put("model", model)
                        put("x_truncated", truncated)
                        val choiceObj = JSONObject().apply {
                            put("index", 0)
                            put("delta", JSONObject())
                            // "length" is the OpenAI signal for a truncated reply.
                            put("finish_reason", if (truncated) "length" else "stop")
                        }
                        put("choices", JSONArray().put(choiceObj))
                    }
                    pipe.write("data: ${finalChunkObj.toString()}\n\n".toByteArray(Charsets.UTF_8))
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Send failed"
                    val errObj = JSONObject().put("error", JSONObject().put("message", errMsg))
                    pipe.write("data: ${errObj.toString()}\n\n".toByteArray(Charsets.UTF_8))
                }
                pipe.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                onErrorLogged("Server send error: ${e.message}")
                try {
                    val errObj = JSONObject().put("error", JSONObject().put("message", e.message ?: "Unknown error"))
                    pipe.write("data: ${errObj.toString()}\n\n".toByteArray(Charsets.UTF_8))
                    pipe.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                } catch (ignored: Exception) {}
            } finally {
                pipe.finish()
            }
        }

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipe)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        return response
    }

    /**
     * Poll the rendered answer until it DIFFERS from the pre-send snapshot.
     *
     * Waiting merely for a non-empty value was the one-turn-behind bug: the
     * previous turn's answer is already mounted and non-empty, so the very
     * first poll matched it and the caller received stale text. A changed value
     * is what proves the page rendered a reply to the message we just sent.
     */
    private suspend fun readRenderedWithWait(previous: String): String {
        // The Worker closes the stream at 60s. Stay inside that: return the
        // best complete answer we have rather than being cut off mid-wait.
        val deadline = System.currentTimeMillis() + 175_000L
        var last = ""
        var lastChangedAt = 0L
        var attempts = 0

        // The page writes the answer progressively, so the first non-empty read
        // is only a PREFIX. Returning on the first change truncated every long
        // reply. Instead, wait until the text stops growing for a short settle
        // window, then take the final value.
        while (System.currentTimeMillis() < deadline) {
            attempts++
            val answer = try { handler.readRenderedAnswer() }
            catch (e: Exception) {
                onErrorLogged("Failed to read rendered answer: ${e.message}")
                ""
            }

            if (answer.isNotBlank() && answer != previous) {
                if (answer != last) {
                    // Still growing.
                    last = answer
                    lastChangedAt = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastChangedAt >= SETTLE_MS) {
                    // Unchanged for the settle window: generation has finished.
                    return answer
                }
            }
            kotlinx.coroutines.delay(300L)
        }

        // Timed out. Returning a prefix is better than nothing, but say so.
        if (last.isNotBlank() && last != previous) {
            onErrorLogged(
                "Answer still growing at timeout after ${attempts} polls; " +
                    "returning ${last.length} chars"
            )
            return last
        }
        onErrorLogged(
            "Answer did not change after ${attempts} polls (possible upstream failure)"
        )
        return ""
    }

    private companion object {
        /**
         * How long the answer text must stay unchanged before we treat it as
         * complete. Long replies stream for many seconds; without this we
         * returned whatever prefix had rendered at the first poll.
         */
        const val SETTLE_MS = 1_500L

        /**
         * How long the stream may stay silent before the request is treated as
         * stalled. This is a quiet window, not a deadline: any output resets it,
         * so an answer that takes many minutes but keeps arriving is never cut
         * off, and a genuinely dead connection fails promptly.
         */
        const val INACTIVITY_TIMEOUT_MS = 120_000L
    }

    /** Reasoning fragments live under a `thinking` path. */
    private fun isReasoningPath(path: String): Boolean =
        path.contains("thinking", ignoreCase = true) || path.contains("reasoning", ignoreCase = true)

    /**
     * Point the "current fragment" at the one named by a /content path.
     *
     * Bare appends that follow carry no path, so they inherit this. Without it
     * the answer kept inheriting the THINK flag from the reasoning fragment
     * that preceded it.
     */
    private fun updateLastFragmentFrom(path: String) {
        val m = Regex("/fragments/(-?\\d+)/").find(path) ?: return
        val raw = m.groupValues[1].toIntOrNull() ?: return
        val key = if (raw < 0) (fragmentIsThink.keys.maxOrNull() ?: return) else raw
        fragmentIsThink[key]?.let { lastFragmentWasThink = it }
    }

    /** Record fragment types from an APPEND-ed fragment array. */
    private fun rememberFragmentArray(fragments: JSONArray) {
        for (i in 0 until fragments.length()) {
            val fragment = fragments.optJSONObject(i) ?: continue
            val id = fragment.optInt("id", i)
            fragmentIsThink[id] = isThinkFragment(fragment)
            // A newly appended fragment becomes the current one.
            lastFragmentWasThink = isThinkFragment(fragment)
        }
    }

    /**
     * Pull answer text out of a full/initial state object such as
     * {"response":{"fragments":[{"type":"RESPONSE","content":"t"}]}}.
     * Only the initial snapshot usually carries text this way; later fragments
     * arrive as APPEND patches.
     */
    private fun extractFromState(state: JSONObject): String {
        val response = state.optJSONObject("response") ?: return ""
        val fragments = response.optJSONArray("fragments") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until fragments.length()) {
            val fragment = fragments.optJSONObject(i) ?: continue
            if (isThinkFragment(fragment)) continue // surfaced as reasoning
            val content = fragment.optString("content", "")
            if (content.isNotEmpty()) sb.append(content)
        }
        return sb.toString()
    }

    /**
     * True when a fragment is the model's thinking rather than its answer.
     *
     * The live protocol uses type "THINK". The earlier "THINKING"/"REASONING"
     * spellings are kept because the field has changed across site versions -
     * missing "THINK" let reasoning text leak into the answer body.
     */
    private fun isThinkFragment(fragment: JSONObject): Boolean {
        val type = fragment.optString("type", "")
        return type.equals("THINK", ignoreCase = true) ||
            type.equals("THINKING", ignoreCase = true) ||
            type.equals("REASONING", ignoreCase = true)
    }

    /**
     * Collect the thinking text from a state snapshot, for use as
     * reasoning_content.
     */
    private fun extractThinkingFromState(state: JSONObject): String {
        val response = state.optJSONObject("response") ?: return ""
        val fragments = response.optJSONArray("fragments") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until fragments.length()) {
            val fragment = fragments.optJSONObject(i) ?: continue
            if (!isThinkFragment(fragment)) continue
            val content = fragment.optString("content", "")
            if (content.isNotEmpty()) sb.append(content)
        }
        return sb.toString()
    }

    /**
     * Handle DeepSeek's JSON-Patch framings, which carry the real answer text.
     *
     * Shapes seen in the live stream:
     *   {"p":"response/fragments/-1/content","o":"APPEND","v":"text"}
     *   {"v":{"response":{"fragments":[{"id":2,"type":"THINK","content":"We"}]}}}
     *   {"v":" works"}                      <- bare append to the last path
     *   {"p":"response/status","o":"SET","v":"FINISHED"}
     *
     * Returns (text, isReasoning). Empty text means "not answer content".
     *
     * The fragment id matters: a patch targeting a fragment we have already
     * classified as THINK must stay reasoning even when the path alone does not
     * say so, which is why the type is tracked per index.
     */
    private fun extractPatch(parsed: JSONObject): Pair<String, Boolean> {
        val path = parsed.optString("p", "")
        val op = parsed.optString("o", "")

        if (path.isNotEmpty()) {
            // A brand-new fragment is announced as
            //   {"p":"response/fragments","o":"APPEND","v":[{"id":3,"type":"RESPONSE",...}]}
            // This is the ONLY place the type is stated, so it must be recorded
            // or everything after it is misclassified. The object also carries
            // the fragment's FIRST text ("content":"Hello"), so that seed has
            // to be emitted too - dropping it lost the opening word of every
            // answer.
            if (path == "response/fragments" && op.equals("APPEND", ignoreCase = true)) {
                val arr = parsed.opt("v")
                if (arr is JSONArray) {
                    rememberFragmentArray(arr)
                    val sb = StringBuilder()
                    var anyThink = false
                    for (i in 0 until arr.length()) {
                        val fragment = arr.optJSONObject(i) ?: continue
                        val c = fragment.optString("content", "")
                        if (c.isEmpty()) continue
                        if (isThinkFragment(fragment)) anyThink = true else sb.append(c)
                    }
                    val seeded = sb.toString()
                    if (seeded.isNotEmpty()) return Pair(seeded, false)
                    if (anyThink) {
                        // A THINK seed: emit it as reasoning.
                        val thinkBuilder = StringBuilder()
                        for (i in 0 until arr.length()) {
                            val fragment = arr.optJSONObject(i) ?: continue
                            if (!isThinkFragment(fragment)) continue
                            thinkBuilder.append(fragment.optString("content", ""))
                        }
                        if (thinkBuilder.isNotEmpty()) return Pair(thinkBuilder.toString(), true)
                    }
                }
                return Pair("", false)
            }

            if (path.endsWith("/content")) {
                // The append op is frequently OMITTED:
                //   {"p":"response/fragments/-1/content","o":"APPEND","v":" need"}
                //   {"p":"response/fragments/-1/content","v":"!"}
                // Requiring "APPEND" dropped the second form and every bare
                // append that followed it.
                val isAppend = op.isEmpty() || op.equals("APPEND", ignoreCase = true)
                if (!isAppend) return Pair("", false)

                val value = parsed.opt("v")
                if (value is String && value.isNotEmpty()) {
                    val reasoning = isReasoningPath(path) || isKnownThinkPath(path)
                    // Keep the "current fragment" pointer in step with the
                    // path, so the bare appends that follow are attributed to
                    // the same fragment.
                    updateLastFragmentFrom(path)
                    return Pair(value, reasoning)
                }
            }
            return Pair("", false)
        }

        // No path: either a bare append, or a full state snapshot.
        //
        // A snapshot is NOT necessarily the turn we asked for. The site
        // re-sends the whole message tree, so harvesting fragments from every
        // snapshot replayed the PREVIOUS answer (ask "5*5", get "64" back from
        // the earlier turn). Only the message whose id we are waiting on may
        // contribute text.
        if (op.isEmpty()) {
            val value = parsed.opt("v")
            if (value is String) {
                if (value.isEmpty()) return Pair("", false)
                // A bare append continues the fragment named by the last
                // /content path we saw; its type decides content vs reasoning.
                //
                // Do NOT gate this on currentMessageId. That id is only set if a
                // state snapshot happened to expose message_id in the exact
                // shape we expect, and when it did not, every bare append - the
                // answer text itself - was discarded, so the stream parsed to
                // nothing. The snapshot guard below still prevents replaying an
                // older turn, which is the case that actually mattered.
                return Pair(value, lastFragmentWasThink)
            }
            if (value is JSONObject) {
                val response = value.optJSONObject("response")
                val msgId = response?.opt("message_id")?.toString()
                    ?: response?.opt("id")?.toString()

                // First snapshot of this turn fixes which message is ours.
                if (msgId != null && currentMessageId == null) {
                    currentMessageId = msgId
                }
                // Ignore snapshots for any other message.
                if (msgId != null && msgId != currentMessageId) {
                    return Pair("", false)
                }

                rememberFragmentTypes(value)
                val thinking = extractThinkingFromState(value)
                if (thinking.isNotEmpty()) return Pair(thinking, true)
                return Pair(extractFromState(value), false)
            }
        }
        return Pair("", false)
    }

    /**
     * The assistant message id we are currently streaming.
     *
     * Set from the first state snapshot that carries one, and cleared per
     * request, so older messages in the tree cannot be mistaken for the answer
     * to the current prompt.
     */
    private var currentMessageId: String? = null

    /** fragment index -> true when that fragment is thinking. */
    private val fragmentIsThink = HashMap<Int, Boolean>()

    /** True when the last fragment we saw was thinking. */
    private var lastFragmentWasThink: Boolean = false

    /** Record the type of every fragment in a snapshot. */
    private fun rememberFragmentTypes(state: JSONObject) {
        val response = state.optJSONObject("response") ?: return
        val fragments = response.optJSONArray("fragments") ?: return
        for (i in 0 until fragments.length()) {
            val fragment = fragments.optJSONObject(i) ?: continue
            // Key by the fragment's own id, not its array position. The live
            // payload is {"id":2,"type":"THINK",...} and patch paths reference
            // that id ("/fragments/2/content"), so an index-keyed map
            // misattributes text when the list order shifts.
            val id = fragment.optInt("id", i)
            fragmentIsThink[id] = isThinkFragment(fragment)
        }
        // The "last" fragment is the one subsequent bare appends belong to.
        if (fragments.length() > 0) {
            val last = fragments.optJSONObject(fragments.length() - 1)
            if (last != null) lastFragmentWasThink = isThinkFragment(last)
        }
    }

    /**
     * Resolve ".../fragments/<n>/content" against the recorded types.
     * "-1" means the newest fragment, which is the last id we recorded.
     */
    private fun isKnownThinkPath(path: String): Boolean {
        val m = Regex("/fragments/(-?\\d+)/").find(path) ?: return false
        val raw = m.groupValues[1].toIntOrNull() ?: return false
        val key = if (raw < 0) (fragmentIsThink.keys.maxOrNull() ?: return false) else raw
        return fragmentIsThink[key] == true
    }

    /** Clear per-request fragment state before each completion. */
    private fun resetFragmentTracking() {
        fragmentIsThink.clear()
        lastFragmentWasThink = false
        currentMessageId = null
    }

    /**
     * Text the site shows instead of an answer when it wants a human check.
     * These must never be forwarded as model output.
     */
    private fun isChallengeText(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.lowercase()
        return t.contains("one more step") ||
            t.contains("before you proceed") ||
            t.contains("verify you are human") ||
            t.contains("verification") ||
            t.contains("unusual traffic") ||
            t.contains("captcha")
    }

    private fun extractContentText(contentObj: Any?): String {
        if (contentObj == null) return ""
        if (contentObj is String) return contentObj
        if (contentObj is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until contentObj.length()) {
                val part = contentObj.optJSONObject(i) ?: continue
                val type = part.optString("type", "text")
                if (type == "text") {
                    sb.append(part.optString("text", ""))
                }
            }
            return sb.toString()
        }
        return contentObj.toString()
    }

    private fun parseMessagesToPrompt(messages: JSONArray?): Pair<String, Boolean> {
        if (messages == null || messages.length() == 0) return Pair("", false)
        // Only the most recent user message is sent. The web UI keeps the
        // thread's context itself, so replaying the transcript would just
        // paste the whole conversation back into the input box.
        for (i in messages.length() - 1 downTo 0) {
            val msg = messages.optJSONObject(i) ?: continue
            if (!msg.optString("role", "").equals("user", ignoreCase = true)) continue
            val text = extractContentText(msg.opt("content"))
            if (text.isNotBlank()) return Pair(text, true)
        }
        return Pair("", false)
    }

    /**
     * The DeepSeek web UI does not stream OpenAI chunks. It streams JSON Patch
     * operations against its own message tree, for example:
     *
     *   {"p":"response/fragments/-1/content","o":"APPEND","v":"unnel"}
     *   {"v":{"response":{"fragments":[{"type":"RESPONSE","content":"t"}]}}}
     *   {"v":" works"}                       <- bare append to the last path
     *   {"p":"response/status","o":"SET","v":"FINISHED"}
     *
     * Only APPENDs to a `.../content` path carry answer text; everything else
     * is bookkeeping and must be dropped.
     */
    private fun extractDelta(chunk: String): Pair<String, Boolean> {
        val parsed = try {
            JSONObject(chunk)
        } catch (e: Exception) {
            // Not JSON at all: the bridge handed us plain text, so use it as-is.
            return Pair(chunk, false)
        }

        // ---- DeepSeek JSON Patch protocol --------------------------------
        // Delegated so the fragment-id bookkeeping lives in one place.
        val path = parsed.optString("p", "")
        val op = parsed.optString("o", "")
        if (path.isNotEmpty() || op.isEmpty()) {
            val (text, reasoning) = extractPatch(parsed)
            if (text.isNotEmpty()) return Pair(text, reasoning)
            // A path that carried no content is protocol bookkeeping; anything
            // else may still be an OpenAI-shaped payload from another host.
            if (path.isNotEmpty()) return Pair("", false)
        }

        // ---- OpenAI-compatible shapes (kept as a fallback) ----------------
        val choices = parsed.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            if (delta != null) {
                val reasoning = delta.optString("reasoning_content", "")
                if (reasoning.isNotEmpty()) {
                    return Pair(reasoning, true)
                }
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    return Pair(content, false)
                }
            }

            val message = choice?.optJSONObject("message")
            if (message != null) {
                val content = extractContentText(message.opt("content"))
                if (content.isNotEmpty()) {
                    return Pair(content, false)
                }
                val reasoning = message.optString("reasoning_content", "")
                if (reasoning.isNotEmpty()) {
                    return Pair(reasoning, true)
                }
            }
        }

        // A legacy text-completion payload.
        val text = parsed.optString("text", "")
        if (text.isNotEmpty()) {
            return Pair(text, false)
        }

        // It *is* JSON but carries no content we understand (a role-only first
        // chunk, a usage-only chunk, a finish chunk). Returning the raw JSON
        // here would splatter protocol text into the middle of the answer, so
        // report "nothing" and let the caller skip it.
        return Pair("", false)
    }

    /** Wraps extractPatch so a parse failure cannot abort the stream. */
    private fun extractPatchVerdict(parsed: JSONObject, raw: String): Pair<String, Boolean> =
        runCatching { extractPatch(parsed) }.getOrElse { Pair("", false) }

    /** Stop whatever the page is currently generating. */
    private fun handleStop(): Response {
        val ok = runBlocking {
            try { withTimeoutOrNull(8_000L) { handler.stopGeneration() } ?: false }
            catch (e: Exception) { false }
        }
        return jsonResponse(
            Response.Status.OK,
            JSONObject().put("stopped", ok)
        )
    }

    private fun handleStatus(): Response {
        val json = JSONObject().apply {
            put("api_enabled", handler.isApiEnabled())
            put("tunnel", handler.getTunnelStatus())
            put("last_error", handler.getLastError() ?: JSONObject.NULL)
            // The conversation the page is showing. A client polls this to
            // remember which chat each of its own conversations belongs to,
            // including when the user navigates the DeepSeek tab by hand.
            put("chat_url", runBlocking { handler.currentChatUrl() })
        }
        return jsonResponse(Response.Status.OK, json)
    }

    /**
     * Move the page to a named conversation, and answer once it is ready.
     *
     * Used when the client switches conversations rather than when it sends, so
     * the navigation happens once and can be awaited. A failure is reported rather
     * than hidden, because a prompt typed mid-navigation lands in the wrong chat.
     */
    private fun handleOpen(session: IHTTPSession): Response {
        val bodyMap = HashMap<String, String>()
        try {
            session.parseBody(bodyMap)
        } catch (e: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", JSONObject().put("message", "Failed to parse body: ${e.message}"))
            )
        }
        val jsonReq = try { JSONObject(bodyMap["postData"] ?: "") } catch (e: Exception) { JSONObject() }
        val url = jsonReq.optString("url", "")
        if (!url.startsWith("https://chat.deepseek.com/")) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", JSONObject().put("message", "Only DeepSeek chat URLs can be opened."))
            )
        }
        val ok = runBlocking { handler.openChat(url) }
        return if (ok) {
            jsonResponse(Response.Status.OK, JSONObject().apply {
                put("ok", true)
                put("chat_url", url)
            })
        } else {
            // 504 Gateway Timeout is not in NanoHTTPD's Status enum.
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().apply {
                    put("ok", false)
                    put("error", JSONObject().put("message", "The page did not finish loading that conversation."))
                }
            )
        }
    }

    /** Mirror the client's DeepThink / Search switches onto the page. */
    private fun handleToggles(session: IHTTPSession): Response {
        val bodyMap = HashMap<String, String>()
        try {
            session.parseBody(bodyMap)
        } catch (e: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", JSONObject().put("message", "Failed to parse body: ${e.message}"))
            )
        }
        val jsonReq = try { JSONObject(bodyMap["postData"] ?: "") } catch (e: Exception) { JSONObject() }
        val ok = runBlocking {
            handler.applyToggles(jsonReq.optBoolean("thinking", false), jsonReq.optBoolean("search", false))
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("ok", ok))
    }

    private fun handleErrors(): Response {
        val array = JSONArray()
        handler.getErrorLog().forEach { array.put(it) }
        return jsonResponse(Response.Status.OK, array)
    }

    private fun jsonResponse(status: Response.Status, body: Any): Response {
        val response = newFixedLengthResponse(status, "application/json", body.toString())
        return response
    }
}

object LogUtils {
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }
}

/**
 * A streaming InputStream fed from a coroutine, used as the body of a chunked
 * NanoHTTPD response.
 *
 * This replaces java.io.PipedInputStream. That class has a fixed 1024-byte
 * circular buffer and per-thread read/write bookkeeping, and when the write
 * side is closed while a read is still outstanding the reader sees a
 * premature end of body. NanoHTTPD reads the body from its own worker thread
 * while the app writes from a coroutine on Dispatchers.IO, so the reader
 * reported:
 *
 *     Unexpected end of stream on http://127.0.0.1:8080/...
 *
 * A LinkedBlockingQueue gives the same producer/consumer handoff with no
 * thread affinity, no fixed buffer, and a clean end-of-stream signal.
 *
 * Thread safety: [write] and [finish] may be called from any thread. A single
 * reader thread should call [read].
 */
class StreamPipe : InputStream() {
    private sealed interface Item {
        class Data(val bytes: ByteArray) : Item
        data object End : Item
    }

    private val queue = LinkedBlockingQueue<Item>()
    private val singleByte = ByteArray(1)
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    @Volatile
    private var closed = false

    /** Queue a chunk of body bytes. Never blocks. */
    fun write(bytes: ByteArray) {
        if (closed) return
        queue.add(Item.Data(bytes))
    }

    /** Signal end of body. Idempotent; replaces PipedOutputStream.close(). */
    fun finish() {
        queue.add(Item.End)
    }

    override fun read(): Int {
        val n = read(singleByte, 0, 1)
        return if (n <= 0) -1 else singleByte[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        if (len == 0) return 0

        while (true) {
            val current = pending
            if (current != null) {
                val n = minOf(len, current.size - pendingOffset)
                System.arraycopy(current, pendingOffset, b, off, n)
                pendingOffset += n
                if (pendingOffset >= current.size) {
                    pending = null
                    pendingOffset = 0
                }
                return n
            }

            val item = try {
                queue.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("read interrupted", e)
            }

            when (item) {
                is Item.End -> return -1
                is Item.Data -> {
                    pending = item.bytes
                    pendingOffset = 0
                }
            }
        }
    }

    override fun close() {
        closed = true
        queue.clear()
        // Unblock a reader parked in take().
        queue.add(Item.End)
    }
}
