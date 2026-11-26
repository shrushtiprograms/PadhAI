const express = require("express");
const { callTextAI } = require("../azureClient");

const router = express.Router();

// Turns the raw AI text into a clean { title, branches: [{ title, children: [] }] } object.
function parseMindMapJson(raw) {
  let text = String(raw || "").trim();
  text = text.replace(/^```(?:json)?/i, "").replace(/```$/, "").trim();

  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start === -1 || end <= start) {
    throw new Error("Mind map response was not valid JSON");
  }

  const obj = JSON.parse(text.slice(start, end + 1));
  const src = obj.mindmap || obj.mind_map || obj;

  const rawBranches = Array.isArray(src.branches) ? src.branches : [];
  const branches = rawBranches
    .map((b) => {
      const title = String((b && (b.title || b.name)) || "").trim();
      const rawChildren = b && Array.isArray(b.children) ? b.children : [];
      const children = rawChildren
        .map((c) => (typeof c === "string" ? c : String((c && (c.title || c.name)) || "")))
        .map((s) => s.trim())
        .filter(Boolean)
        .slice(0, 5);
      return { title, children };
    })
    .filter((b) => b.title)
    .slice(0, 8);

  if (branches.length === 0) {
    throw new Error("Mind map response had no branches");
  }

  return { title: String(src.title || "Mind Map").trim(), branches };
}

async function generateMindMap(notes, lang) {
  if (typeof callTextAI !== "function") {
    throw new Error("callTextAI is not exported from azureClient.js (add it to module.exports)");
  }

  const langInstr =
    lang === "hi"
      ? "Write every title and point in simple Hindi (Devanagari script)."
      : "Write in simple English.";

  const prompt = `You are an expert educator. Turn the notes below into a mind map for quick revision. ${langInstr}

Rules:
- "title": the central topic, at most 6 words
- Create 4 to 7 branches, one per main theme of the notes
- Each branch has a "title" of at most 5 words and a "children" array of 2 to 4 short points
- Each point is at most 10 words, a fact or key idea, not a full sentence
- Cover the whole content, do not repeat points
- Plain text only: no markdown, no numbering, no emojis

Return ONLY a valid JSON object with no extra text. Shape:
{"title":"...","branches":[{"title":"...","children":["...","..."]}]}

Notes:
${String(notes).slice(0, 12000)}`;

  console.log("[AI] generating mind map...");
  const raw = await callTextAI([{ text: prompt }], 2000);
  console.log("[AI] mind map response preview:", String(raw).slice(0, 300));
  return parseMindMapJson(raw);
}

router.post("/mindmap", async (req, res) => {
  const { notes, lang, language } = req.body || {};
  if (!notes || !String(notes).trim()) {
    return res.status(400).json({ error: "notes required" });
  }
  try {
    const mindmap = await generateMindMap(notes, lang || language || "en");
    res.json({ mindmap });
  } catch (e) {
    console.error("mindmap error", e);
    res.status(500).json({ error: "mindmap_failed", details: e.message || String(e) });
  }
});

module.exports = router;
