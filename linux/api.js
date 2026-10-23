// Local HTTP API.
//
// Same route shapes as the Android standalone bridge, so any client written for
// that one works here unchanged:
//
//   POST /coding|/general/chat/completions        streaming SSE
//   POST /coding|/general/chat/completions/full   one JSON response
//   POST /chat            streaming  {"prompt": "..."}
//   POST /chat/full       one JSON response
//   GET  /status /errors
//   GET|POST /stop
//
// The parser is deliberately shared between the streaming and non-streaming
// paths, so the two can never disagree about what the page produced.

const http = require("http");

function cors(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Max-Age", "86400");
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      // A prompt is text; refuse anything absurd rather than buffering forever.
      if (size > 32 * 1024 * 1024) {
        reject(new Error("Request body too large"));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function json(res, status, obj) {
  cors(res);
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=UTF-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

/**
 * Reduce an incoming request to the two things the page should receive.
 *
 * The DeepSeek page is a live chat session: it already holds the conversation
 * history in its own transcript. Replaying the whole message array made every
 * request appear as the entire conversation plus the finish instruction, which
 * is not what a person typing in that page would produce.
 *
 * What is sent instead is exactly what a user would type:
 *
 *   last user message, then the finish instruction
 *
 * System messages other than the finish belong to the client's own framing and
 * are dropped; the page's workspace behaviour comes from the site's settings.
 */
function extractPrompt(jsonReq) {
  if (typeof jsonReq.prompt === "string" && jsonReq.prompt.trim()) {
    return { prompt: jsonReq.prompt, thinking: false, search: false, model: jsonReq.model };
  }

  const messages = Array.isArray(jsonReq.messages) ? jsonReq.messages : [];

  // The most recent user turn is the request.
  let prompt = "";
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (!m || m.role !== "user") continue;
    const text = contentToText(m.content).trim();
    if (text) {
      prompt = text;
      break;
    }
  }

  // The finish instruction is the LAST system message, if the client sent one.
  //
  // The client sends two kinds of system message: the workspace protocol (the file
  // creation/editing commands) and the optional prompt finish. Both have to reach
  // the page, or the model never learns the command syntax and replies with plain
  // prose instead of file operations - which is why files could be created on
  // Android and not here. The finish is separated out only so it can be placed at
  // the end, after the user's message.
  let finish = "";
  let protocol = "";
  for (const raw of messages) {
    if (!raw || raw.role !== "system") continue;
    const text = contentToText(raw.content).trim();
    if (!text) continue;
    if (text.includes("File creation:") && text.includes("File editing:")) {
      protocol = text;
    } else {
      // The last non-protocol system message is the finish instruction.
      finish = text;
    }
  }

  const parts = [protocol, prompt, finish].filter((t) => t && t.trim());
  const combined = parts.join("\n\n");

  // null means "not specified", which is not the same as false.
  //
  // A request that omits these used to have them default to false, and the page
  // then switched both controls OFF - so any client that did not mention them (a
  // curl, another tool, an older build) silently changed the user's settings on the
  // DeepSeek tab. Omitted now leaves the page's own state alone.
  const thinkingSpecified =
    jsonReq.thinking !== undefined ||
    jsonReq.enable_thinking !== undefined ||
    jsonReq.thinking_enabled !== undefined;
  const searchSpecified =
    jsonReq.search !== undefined || jsonReq.search_enabled !== undefined;

  // The state is in the VALUE, not the key.
  //
  // A previous version tested /thinking|reason/i against jsonReq.thinking.type,
  // which matched the word "thinking" in the key rather than the state in the value
  // - so {"thinking":{"type":"enabled"}} read as false and quietly switched
  // DeepThink OFF on every send that asked for it.
  const truthyValue = (v) => {
    if (v === true) return true;
    if (typeof v === "string") return /^(enabled|on|true|yes|1)$/i.test(v.trim());
    if (v && typeof v === "object") {
      // { type: "enabled" } / { enabled: true } / { level: "high" }
      if (typeof v.enabled === "boolean") return v.enabled;
      if (typeof v.type === "string") return /^(enabled|on|true)$/i.test(v.type.trim());
      if (typeof v.level === "string") return v.level.trim().toLowerCase() !== "off";
    }
    return false;
  };

  const thinking = thinkingSpecified
    ? truthyValue(jsonReq.thinking) ||
      truthyValue(jsonReq.enable_thinking) ||
      truthyValue(jsonReq.thinking_enabled)
    : null;

  const search = searchSpecified
    ? truthyValue(jsonReq.search) || truthyValue(jsonReq.search_enabled)
    : null;

  return {
    prompt: combined,
    thinking,
    search,
    // Which DeepSeek conversation this request belongs to. The page is the chat,
    // so the client names the one it means. Absent means "leave the page where it
    // is", which is right for any client that has no notion of conversations.
    chatUrl: typeof jsonReq.chat_url === "string" ? jsonReq.chat_url : "",
    model: jsonReq.model,
  };
}

function contentToText(content) {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((p) => {
        if (!p || typeof p !== "object") return "";
        if (p.type === "text") return p.text || "";
        if (p.type === "image_url") return "[image attached]";
        return "";
      })
      .filter(Boolean)
      .join("\n");
  }
  return "";
}

function id() {
  return "chatcmpl-" + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function startApi({ host = "127.0.0.1", port, getHandlers, onLog }) {
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${host}:${port}`);
    const route = url.pathname;
    const h = getHandlers();

    if (req.method === "OPTIONS") {
      cors(res);
      res.writeHead(204);
      res.end();
      return;
    }

    try {
      if (route === "/status" && req.method === "GET") {
        // chat_url is where the page is showing right now. A client uses it to
        // remember which conversation each of its own conversations belongs to,
        // including when the user navigates the page by hand.
        let chatUrl = "";
        try {
          chatUrl = await h.currentChatUrl();
        } catch (e) {
          chatUrl = "";
        }
        return json(res, 200, {
          api_enabled: h.isEnabled(),
          tunnel: "local",
          transport: "direct (loopback)",
          chat_url: chatUrl,
        });
      }

      // Move the page to a named conversation, and answer once it is ready to be
      // typed into.
      //
      // Separate from a chat request so switching conversations can happen when
      // the client switches, not only when it sends. Awaiting it is what stops a
      // prompt being typed into the previous chat while the page is still moving.
      if (route === "/open" && req.method === "POST") {
        let url = "";
        try {
          url = String(JSON.parse((await readBody(req)) || "{}").url || "");
        } catch (e) {
          return json(res, 400, { error: { message: "Body must be JSON with a url." } });
        }
        if (!/^https:\/\/chat\.deepseek\.com\//.test(url)) {
          return json(res, 400, { error: { message: "Only DeepSeek chat URLs can be opened." } });
        }
        let ok = false;
        try {
          ok = await h.openChat(url);
        } catch (err) {
          return json(res, 502, { ok: false, error: { message: err.message } });
        }
        // ok:false means the page did not settle, so the caller knows the switch
        // failed rather than assuming it worked.
        return json(res, ok ? 200 : 504, ok
          ? { ok: true, chat_url: url }
          : { ok: false, error: { message: "The page did not finish loading that conversation." } });
      }

      // Mirror the client's toggles onto the page as they are flipped, so the
      // DeepSeek tab shows the same state rather than only matching at send time.
      if (route === "/toggles" && req.method === "POST") {
        let body = {};
        try {
          body = JSON.parse((await readBody(req)) || "{}");
        } catch (e) {
          return json(res, 400, { error: { message: "Body must be JSON." } });
        }
        const ok = await h.applyToggles(Boolean(body.thinking), Boolean(body.search));
        return json(res, 200, { ok });
      }

      // The DeepSeek page's own switch state, as the page reports it.
      if (route === "/toggle-state" && req.method === "GET") {
        let state = null;
        try {
          state = await h.toggleState();
        } catch (e) {
          state = null;
        }
        return json(res, 200, state || { thinking: null, search: null });
      }

      if (route === "/errors" && req.method === "GET") {
        return json(res, 200, { errors: [] });
      }

      if (route === "/stop" && (req.method === "GET" || req.method === "POST")) {
        const ok = await h.stop();
        return json(res, 200, { stopped: Boolean(ok) });
      }

      // Recover the most recent reply without running a completion.
      //
      // For a client whose request failed - a dropped stream, a throttle, a
      // restart - while the page went on to produce an answer. Reading it back
      // costs nothing and avoids spending another generation on a retry.
      const isLast = /^\/last$/.test(route) ||
        /^\/last\/full$/.test(route) ||
        /^\/(coding|general)\/chat\/completions\/last$/.test(route);

      if (isLast && (req.method === "GET" || req.method === "POST")) {
        let recovered;
        try {
          recovered = await h.lastReply();
        } catch (err) {
          return json(res, 502, { error: { message: err.message } });
        }

        const content = (recovered && recovered.content) || "";
        const reasoning = (recovered && recovered.reasoning) || "";

        if (!content && !reasoning) {
          return json(res, 404, {
            error: { message: "The page has no assistant reply to read." },
          });
        }

        return json(res, 200, {
          id: id(),
          object: "chat.completion",
          created: Math.floor(Date.now() / 1000),
          model: "deepseek-web",
          x_recovered: true,
          choices: [
            {
              index: 0,
              message: {
                role: "assistant",
                content,
                ...(reasoning ? { reasoning_content: reasoning } : {}),
              },
              finish_reason: "stop",
            },
          ],
        });
      }

      const isStream = /^\/chat$/.test(route) ||
        /^\/(coding|general)\/chat\/completions$/.test(route);
      const isFull = /^\/chat\/full$/.test(route) ||
        /^\/(coding|general)\/chat\/completions\/full$/.test(route);

      if ((isStream || isFull) && req.method === "POST") {
        let body;
        try {
          body = JSON.parse((await readBody(req)) || "{}");
        } catch (e) {
          return json(res, 400, { error: { message: `Invalid JSON: ${e.message}` } });
        }

        const { prompt, thinking, search, chatUrl, model } = extractPrompt(body);
        if (!prompt.trim()) {
          return json(res, 400, { error: { message: "Missing or empty prompt" } });
        }

        const controller = new AbortController();
        req.on("close", () => controller.abort());

        if (isFull) {
          // One JSON response: collect everything, emit once.
          let content = "";
          let reasoning = "";
          // Declared outside the try so the conversation URL the page reported
          // survives into the response.
          let settled = null;
          try {
            const result = await h.complete({
              prompt, thinking, search, chatUrl, signal: controller.signal,
              onDelta: (d) => {
                if (d.reasoning) reasoning += d.reasoning;
                if (d.content) content += d.content;
              },
            });
            settled = result;
            content = result.content || content;
            reasoning = result.reasoning || reasoning;
          } catch (err) {
            return json(res, 502, { error: { message: err.message } });
          }

          if (!content && !reasoning) {
            return json(res, 502, {
              error: { message: "No content arrived from the page. DeepSeek may be throttling - retry shortly." },
            });
          }

          // Ask the page whether it is still offering Continue.
          //
          // DeepSeek cuts a long answer off and shows that control; what arrived
          // is then a prefix, and presenting it as complete would be
          // misleading. A failure to determine this is treated as "not
          // truncated", since a spurious warning on every reply is worse than
          // missing one.
          let truncated = false;
          try {
            truncated = await h.isContinueOffered();
          } catch (e) {
            truncated = false;
          }
          if (truncated) {
            onLog("/full: the reply was cut off; the page is offering Continue");
          }

          return json(res, 200, {
            id: id(),
            object: "chat.completion",
            created: Math.floor(Date.now() / 1000),
            model: model || "deepseek-web",
            // The standard signal for a truncated reply, plus an explicit flag
            // so a client need not infer it from the finish reason alone.
            x_truncated: truncated,
            // Which DeepSeek conversation the page ended up on. A client that
            // started from the home page learns its chat's URL only after the
            // first request, so it has to be told.
            x_chat_url: (settled && settled.chatUrl) || "",
            choices: [
              {
                index: 0,
                message: { role: "assistant", content, ...(reasoning ? { reasoning_content: reasoning } : {}) },
                finish_reason: truncated ? "length" : "stop",
              },
            ],
          });
        }

        // Streaming: emit OpenAI-shaped deltas as they arrive.
        cors(res);
        res.writeHead(200, {
          "Content-Type": "text/event-stream; charset=UTF-8",
          "Cache-Control": "no-cache",
          Connection: "keep-alive",
        });
        const chunkId = id();
        const write = (kind, value) => {
          const frame = {
            id: chunkId,
            object: "chat.completion.chunk",
            created: Math.floor(Date.now() / 1000),
            model: model || "deepseek-web",
            choices: [{ index: 0, delta: { [kind]: value }, finish_reason: null }],
          };
          res.write(`data: ${JSON.stringify(frame)}\n\n`);
        };

        /**
         * Send text split into pieces small enough for a client to animate.
         *
         * The page batches: one patch can carry several paragraphs. Forwarding
         * that as a single delta leaves nothing between the start and the end for
         * the client to reveal, so the answer appears all at once however smooth
         * its animation is.
         */
        const writeInChunks = (kind, text) => {
          const CHUNK = 60;
          if (text.length <= CHUNK) {
            write(kind, text);
            return;
          }
          let at = 0;
          while (at < text.length) {
            let end = Math.min(at + CHUNK, text.length);
            // Prefer a word boundary so a chunk does not end mid-word.
            if (end < text.length) {
              const space = text.lastIndexOf(" ", end);
              if (space > at + CHUNK / 2) end = space + 1;
            }
            write(kind, text.slice(at, end));
            at = end;
          }
        };

        try {
          const final = await h.complete({
            prompt, thinking, search, chatUrl, signal: controller.signal,
            onDelta: (d) => {
              // Split before sending: the page batches its text, so one delta can
              // carry several paragraphs, and a wall of text arriving at once
              // cannot be revealed smoothly however fast the client draws it.
              if (d.reasoning) writeInChunks("reasoning_content", d.reasoning);
              if (d.content) writeInChunks("content", d.content);
            },
          });
          // Tell a client that started from the home page which chat the page
          // ended up on, so it can send the next turn to the same one.
          if (final && final.chatUrl) {
            write("chat_url", final.chatUrl);
          }
        } catch (err) {
          res.write(`data: ${JSON.stringify({ error: { message: err.message } })}\n\n`);
        }
        res.write("data: [DONE]\n\n");
        res.end();
        return;
      }

      return json(res, 404, { error: "Not found" });
    } catch (err) {
      onLog(`Server error on ${route}: ${err.message}`);
      if (!res.headersSent) json(res, 500, { error: { message: err.message } });
      else res.end();
    }
  });

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => {
      resolve({
        close: () =>
          new Promise((done) => {
            server.close(() => done());
            // Close idle keep-alive sockets so the port frees promptly.
            server.closeAllConnections?.();
          }),
      });
    });
  });
}

module.exports = { startApi, extractPrompt };
