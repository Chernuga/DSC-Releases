// Verify exactly what gets sent to the DeepSeek page.
const { extractPrompt: extractPromptForTest } = require("./api");

const WORKSPACE = [
  "When creating code you should do it in the conversation's workspace. To initialise it there are a few simple commands:",
  "1. File creation:",
  "path/to/file/filename.extension create:",
  "```",
  "contents of the file go in this code block",
  "```",
  "2. File editing:",
].join("\n");

let fails = 0;
const ck = (l, c, e = "") => { if (c) console.log("  PASS  " + l); else { console.log("  FAIL  " + l + "  " + e); fails++; } };

// The shape the site actually sends: workspace protocol, history, request,
// finish instruction last.
const realistic = {
  messages: [
    { role: "system", content: WORKSPACE },
    { role: "user", content: "first question" },
    { role: "assistant", content: "first answer" },
    { role: "user", content: "second question" },
    { role: "assistant", content: "second answer" },
    { role: "user", content: "Say exactly: PROMPT WINS" },
    { role: "system", content: "FINISH RULE: end every reply with BANANA" },
  ],
  stream: false,
};

const r = extractPromptForTest(realistic);
console.log("=== what the page receives ===");
console.log(r.prompt);
console.log("=== end ===");

console.log("\n[assertions]");
ck("contains the last user message", r.prompt.includes("Say exactly: PROMPT WINS"));
ck("contains the finish instruction", r.prompt.includes("BANANA"));
ck("prompt comes before the finish",
   r.prompt.indexOf("PROMPT WINS") < r.prompt.indexOf("BANANA"));
ck("does NOT contain the previous user turn", !r.prompt.includes("first question"));
ck("does NOT contain earlier answers", !r.prompt.includes("first answer"));
ck("does NOT contain the second turn", !r.prompt.includes("second question"));
ck("does NOT contain the workspace protocol", !r.prompt.includes("File creation:"));
ck("exactly one user request", (r.prompt.match(/PROMPT WINS/g) || []).length === 1);

console.log("\n[no finish instruction]");
const noFinish = extractPromptForTest({ messages: [
  { role: "system", content: WORKSPACE },
  { role: "user", content: "just this" },
]});
ck("sends only the prompt", noFinish.prompt === "just this", JSON.stringify(noFinish.prompt));

console.log("\n[simple prompt form]");
const simple = extractPromptForTest({ prompt: "direct prompt" });
ck("prompt form passes through", simple.prompt === "direct prompt", simple.prompt);

console.log("\n[multimodal]");
const mm = extractPromptForTest({ messages: [
  { role: "user", content: [
    { type: "text", text: "describe this" },
    { type: "image_url", image_url: { url: "data:x" } },
  ]},
]});
ck("text kept", mm.prompt.includes("describe this"));
ck("image noted", mm.prompt.includes("[image attached]"));

console.log("\n[single turn, nothing else]");
const one = extractPromptForTest({ messages: [{ role: "user", content: "hello" }] });
ck("passes through unchanged", one.prompt === "hello", JSON.stringify(one.prompt));

console.log(fails === 0 ? "\nEXTRACTION OK" : `\n${fails} FAILURE(S)`);
process.exit(fails === 0 ? 0 : 1);
