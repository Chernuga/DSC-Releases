// Stream hook, injected into the DeepSeek page's OWN world.
//
// This must run in the page world, not the preload's isolated world: patching
// XMLHttpRequest in an isolated world patches a copy the page never touches, so
// the hook appears installed and captures nothing.
//
// Facts it relies on, established against the live site:
//   * transport is XMLHttpRequest, not fetch
//   * endpoint is /api/v0/chat/completion (singular)
//   * payloads are JSON-Patch ops, not OpenAI delta.content
//   * fragments are typed THINK (reasoning) and RESPONSE (answer)
//   * a bare {"v":"..."} append continues the fragment named by the last
//     /content path seen
//
// It posts everything to the preload via window.postMessage, which is the only
// channel available across the world boundary.

(function () {
  if (window.__dsPageHook) return;
  window.__dsPageHook = true;

  let channelId = null;
  let capturing = false;
  let contentBuf = "";
  let reasoningBuf = "";
  let lastWasThink = false;
  const fragmentIsThink = new Map();
  let finishResolve = null;

  function post(payload) {
    try {
      window.postMessage({ __dsBridge: true, ...payload }, "*");
    } catch (e) {
      /* the preload is gone */
    }
  }

  function log(text) {
    post({ kind: "log", text: String(text) });
  }

  function resetCapture() {
    capturing = true;
    contentBuf = "";
    reasoningBuf = "";
    lastWasThink = false;
    fragmentIsThink.clear();
  }

  function isThink(frag) {
    const t = String((frag && frag.type) || "").toUpperCase();
    return t === "THINK" || t === "THINKING" || t === "REASONING";
  }

  function emit(text, isReasoning) {
    if (!text) return;
    if (isReasoning) reasoningBuf += text;
    else contentBuf += text;
    if (channelId) {
      post({ kind: "delta", channelId, text, isReasoning });
    }
  }

  function keyFor(raw) {
    if (raw >= 0) return raw;
    const keys = [...fragmentIsThink.keys()];
    return keys.length ? Math.max(...keys) : -1;
  }

  function isKnownThinkPath(path) {
    const m = /\/fragments\/(-?\d+)\//.exec(path);
    if (!m) return false;
    return fragmentIsThink.get(keyFor(parseInt(m[1], 10))) === true;
  }

  function updateLastFragmentFrom(path) {
    const m = /\/fragments\/(-?\d+)\//.exec(path);
    if (!m) return;
    const key = keyFor(parseInt(m[1], 10));
    if (fragmentIsThink.has(key)) lastWasThink = fragmentIsThink.get(key);
  }

  function handlePayload(raw) {
    let p;
    try {
      p = JSON.parse(raw);
    } catch {
      return;
    }

    const path = p.p || "";
    const op = p.o || "";
    const value = p.v;

    // A fragment array announces types and may carry seed content.
    if (path === "response/fragments" && Array.isArray(value)) {
      for (const frag of value) {
        const id = frag && frag.id !== undefined ? frag.id : 0;
        fragmentIsThink.set(id, isThink(frag));
        lastWasThink = isThink(frag);
      }
      for (const frag of value) {
        if (frag && !isThink(frag) && frag.content) emit(frag.content, false);
      }
      return;
    }

    if (/\/content$/.test(path)) {
      if (op && op.toUpperCase() !== "APPEND") {
        updateLastFragmentFrom(path);
        return;
      }
      if (typeof value === "string" && value) {
        const reasoning = /thinking|reasoning/i.test(path) || isKnownThinkPath(path);
        updateLastFragmentFrom(path);
        emit(value, reasoning);
      }
      return;
    }

    if (path) return; // status, elapsed_secs and other unrelated patches

    // Bare ops: a string append, or a full state snapshot.
    if (typeof value === "string") {
      if (value) emit(value, lastWasThink);
      return;
    }

    if (value && typeof value === "object" && value.response) {
      const frags = Array.isArray(value.response.fragments) ? value.response.fragments : [];
      for (const frag of frags) {
        const id = frag && frag.id !== undefined ? frag.id : 0;
        fragmentIsThink.set(id, isThink(frag));
      }
      if (frags.length) lastWasThink = isThink(frags[frags.length - 1]);

      // Thinking first, so the answer does not inherit the wrong flag.
      let think = "";
      for (const frag of frags) if (isThink(frag) && frag.content) think += frag.content;
      if (think) {
        emit(think, true);
        return;
      }
      let answer = "";
      for (const frag of frags) if (!isThink(frag) && frag.content) answer += frag.content;
      if (answer) emit(answer, false);
    }
  }

  function parseSse(chunk, carry) {
    const combined = carry + chunk;
    const lines = combined.split("\n");
    const rest = lines.pop() ?? "";
    for (const line of lines) {
      const t = line.trim();
      if (!t.startsWith("data:")) continue;
      const payload = t.slice(5).trim();
      if (!payload || payload === "[DONE]") continue;
      handlePayload(payload);
    }
    return rest;
  }

  // -- the XHR hook ----------------------------------------------------------

  const isCompletion = (u) =>
    typeof u === "string" && u.includes("/api/v0/chat/completion");

  const origOpen = XMLHttpRequest.prototype.open;
  const origSend = XMLHttpRequest.prototype.send;

  XMLHttpRequest.prototype.open = function (method, url) {
    try {
      this.__dsUrl = url;
    } catch (e) {}
    return origOpen.apply(this, arguments);
  };

  XMLHttpRequest.prototype.send = function () {
    const self = this;
    try {
      if (isCompletion(self.__dsUrl) && !self.__dsHooked) {
        self.__dsHooked = true;
        let last = 0;
        let carry = "";
        self.addEventListener("progress", function () {
          try {
            const text = self.responseText || "";
            if (text.length < last) {
              last = 0;
              carry = "";
            }
            const fresh = text.slice(last);
            last = text.length;
            if (!capturing) resetCapture();
            carry = parseSse(fresh, carry);
          } catch (e) {
            /* responseText not readable as text */
          }
        });
        self.addEventListener("loadend", function () {
          capturing = false;
          if (channelId) post({ kind: "done", channelId });
          if (finishResolve) {
            const r = finishResolve;
            finishResolve = null;
            r();
          }
        });
      }
    } catch (e) {}
    return origSend.apply(this, arguments);
  };

  // -- page control ----------------------------------------------------------

  function findInput() {
    return (
      document.querySelector("textarea#chat-input") ||
      document.querySelector("textarea") ||
      document.querySelector('div[contenteditable="true"]')
    );
  }

  function typeInto(input, text) {
    if (input.tagName.toLowerCase() === "textarea") {
      // React tracks its own value setter, so the instance property is bypassed
      // and the events it listens for are dispatched.
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLTextAreaElement.prototype,
        "value"
      )?.set;
      input.focus();
      if (setter) setter.call(input, text);
      else input.value = text;
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.dispatchEvent(new Event("change", { bubbles: true }));
    } else {
      input.innerText = text;
      input.dispatchEvent(new Event("input", { bubbles: true }));
    }
  }

  function circleButtons() {
    return [
      ...document.querySelectorAll(
        'div[role="button"][class*="ds-button--circle"], div[role="button"].ds-button--circle'
      ),
    ];
  }

  function isStopButton(el) {
    const d = (el.querySelector("svg path") || {}).getAttribute
      ? el.querySelector("svg path").getAttribute("d") || ""
      : "";
    return d.indexOf("M2 4.88") === 0;
  }

  function isDisabled(el) {
    if (!el) return true;
    if (el.getAttribute("aria-disabled") === "true") return true;
    const cls = typeof el.className === "string" ? el.className : "";
    return /disabled/i.test(cls);
  }

  /**
   * Set a page toggle to a wanted state.
   *
   * This used to only ever turn a control on, so a toggle left on in the page
   * could not be turned off from the client - the request said "off" but the page
   * still searched. It now compares the wanted state with the control's own and
   * clicks only when they differ.
   */
  function pressToggle(want, words) {
    const wanted = Boolean(want);
    for (const el of document.querySelectorAll('div[role="button"], button')) {
      const label = (el.innerText || "").trim().toLowerCase();
      if (!label || !words.some((w) => label.includes(w))) continue;
      const cls = typeof el.className === "string" ? el.className : "";
      const on = cls.includes("active") || el.getAttribute("aria-pressed") === "true";
      if (on !== wanted) el.click();
      return;
    }
  }

  window.__dsPage = {
    status() {
      return {
        url: location.href,
        loggedIn: !/\/sign_in|\/login/i.test(location.pathname),
        hasInput: Boolean(findInput()),
      };
    },

    /**
     * Set the page's DeepThink and Search controls to match the client.
     *
     * Exposed so flipping a toggle in the client is visible on the DeepSeek tab
     * straight away, rather than only taking effect at the next send.
     */
    applyToggles(thinking, search) {
      pressToggle(thinking, ["deepthink", "think"]);
      pressToggle(search, ["search"]);
      return { ok: true };
    },

    async sendPrompt(prompt, thinking, search, chan) {
      channelId = chan || null;
      resetCapture();

      pressToggle(thinking, ["deepthink", "think"]);
      pressToggle(search, ["search"]);

      const input = findInput();
      if (!input) {
        channelId = null;
        return { ok: false, error: "Could not find the chat input on the DeepSeek page." };
      }
      typeInto(input, prompt);

      // Resolve when loadend fires, with a ceiling so a silent page cannot hang
      // the caller forever.
      const done = new Promise((resolve) => {
        finishResolve = resolve;
        setTimeout(() => {
          if (finishResolve === resolve) {
            finishResolve = null;
            resolve();
          }
        }, 10 * 60 * 1000);
      });

      const pressEnter = () => {
        const opts = {
          key: "Enter", code: "Enter", keyCode: 13, which: 13,
          bubbles: true, cancelable: true,
        };
        input.dispatchEvent(new KeyboardEvent("keydown", opts));
        input.dispatchEvent(new KeyboardEvent("keyup", opts));
      };

      // Retry while React re-enables the control; a single attempt at a fixed
      // delay silently did nothing when it was still disabled.
      let attempts = 0;
      const trySend = () => {
        attempts++;
        const notStop = circleButtons().filter((b) => !isStopButton(b));
        const btn = notStop.length ? notStop[notStop.length - 1] : null;
        if (btn && !isDisabled(btn)) {
          btn.click();
          return;
        }
        if (attempts === 1) pressEnter();
        if (attempts < 12) setTimeout(trySend, 300);
        else log("Send control never became ready.");
      };
      setTimeout(trySend, 250);

      await done;
      channelId = null;
      return { ok: true, content: contentBuf, reasoning: reasoningBuf };
    },

    /**
     * Scroll the transcript to the bottom.
     *
     * The site renders messages in a virtualised list, so an off-screen reply is
     * not in the DOM at all. Scrolling is a prerequisite for reading anything,
     * not a nicety.
     */
    scrollTranscriptToBottom() {
      try {
        const candidates = [];
        const list = document.querySelector(".ds-virtual-list-items");
        let node = list;
        while (node && node !== document.body) {
          const s = getComputedStyle(node);
          if (/(auto|scroll)/.test(s.overflowY) && node.scrollHeight > node.clientHeight) {
            candidates.push(node);
          }
          node = node.parentElement;
        }
        const marked = document.querySelectorAll('.ds-scroll-area, [class*="gutter"]');
        for (const el of marked) candidates.push(el);

        let scrolled = 0;
        for (const el of candidates) {
          if (el.scrollHeight > el.clientHeight) {
            el.scrollTop = el.scrollHeight;
            scrolled++;
          }
        }
        return scrolled > 0;
      } catch (e) {
        return false;
      }
    },

    /**
     * Reveal the hover-only controls on every rendered row.
     *
     * Separate from the search on purpose: the site shows its controls on React's
     * next render, so a caller that hovers and searches in one tick can look
     * before the control exists. Hovering here, waiting, then searching is what
     * gives the newest message's controls time to appear.
     */
    hoverRows() {
      try {
        let rows = document.querySelectorAll(".ds-virtual-list-visible-items > *");
        if (!rows.length) rows = document.querySelectorAll(".ds-virtual-list-items > *");
        for (const el of rows) {
          const r = el.getBoundingClientRect();
          const o = {
            bubbles: true, cancelable: true, view: window,
            clientX: r.left + r.width / 2, clientY: r.top + r.height / 2,
          };
          el.dispatchEvent(new MouseEvent("mouseover", o));
          el.dispatchEvent(new MouseEvent("mouseenter", o));
          el.dispatchEvent(new MouseEvent("mousemove", o));
        }
        return rows.length;
      } catch (e) {
        return 0;
      }
    },

    /**
     * Locate the copy control for the newest assistant reply.
     *
     * Returns {ok, x, y} in page pixels, for a real synthetic click. Ported
     * unchanged from the Android build, where the measurements behind it were
     * taken: the rows live in .ds-virtual-list-visible-items, and the
     * message-level control is the LAST button carrying the copy glyph, because
     * the per-thinking-block and per-code-block copies wear the same icon.
     */
    locateMessageCopyButton() {
      try {
        const svgPath = (el) => {
          const p = el.querySelector("svg path");
          return p ? p.getAttribute("d") || "" : "";
        };

        let rows = document.querySelectorAll(".ds-virtual-list-visible-items > *");
        if (!rows.length) rows = document.querySelectorAll(".ds-virtual-list-items > *");
        if (!rows.length) {
          return { ok: false, error: "no message rows rendered" };
        }

        const COPY_GLYPH = "M6.14929 4.02032";

        let best = null;
        for (let i = 0; i < rows.length; i++) {
          const btns = rows[i].querySelectorAll(".ds-button");
          for (const b of btns) {
            const aria = (b.getAttribute("aria-label") || "") + " " + (b.title || "");
            const named = /copy/i.test(aria);
            const isGlyph = svgPath(b).indexOf(COPY_GLYPH) === 0;
            if (!named && !isGlyph) continue;
            if (!b.offsetParent) continue;
            const r = b.getBoundingClientRect();
            if (r.width < 1 || r.height < 1) continue;
            // Later matches overwrite earlier ones, so the final assignment is
            // the message-level control.
            best = {
              x: Math.round(r.left + r.width / 2),
              y: Math.round(r.top + r.height / 2),
              named,
              row: i,
            };
          }
        }
        if (!best) return { ok: false, error: "no copy control found" };
        best.ok = true;
        return best;
      } catch (e) {
        return { ok: false, error: String(e) };
      }
    },

    /**
     * The most recent reply, as raw text.
     *
     * Prefers the buffer this hook filled from the wire, which holds exactly the
     * characters the page received - the same string the site's own copy button
     * writes to the clipboard. The rendered DOM is NOT equivalent: it replaces
     * fences with the copy and download controls.
     *
     * The buffer only fills when THIS app sent the prompt. Opening an older
     * conversation leaves it empty, which is why the caller falls back to
     * driving the page's own copy control.
     */
    lastReply() {
      return { content: contentBuf, reasoning: reasoningBuf };
    },

    /**
     * Is the page offering its Continue control?
     *
     * DeepSeek stops a long answer and shows a control to resume it; the text
     * captured so far is then a prefix, not the whole reply. Reported so a
     * client can say so rather than present a partial answer as complete.
     */
    isContinueOffered() {
      try {
        const nodes = document.querySelectorAll(
          'button, div[role="button"], [class*="continue"]'
        );
        for (const el of nodes) {
          const text = (
            (el.innerText || el.textContent || "") + " " + (el.getAttribute("aria-label") || "")
          ).trim().toLowerCase();
          if (!text) continue;
          // Only a short label counts; a paragraph mentioning "continue" is
          // prose, not the control.
          if (text.length > 40) continue;
          if (text.includes("continue") || text.includes("resume")) {
            if (el.offsetParent !== null) return true;
          }
        }
      } catch (e) {
        /* fall through to false */
      }
      return false;
    },

    stop() {
      const stopBtn = circleButtons().find(isStopButton);
      if (stopBtn) {
        stopBtn.click();
        return true;
      }
      return false;
    },
  };
})();
