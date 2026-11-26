/**
 * StudyGenie NVIDIA NIM client
 * Uses NVIDIA's OpenAI-compatible Chat Completions API.
 * Requires Node.js 18+ (native fetch).
 */
const NVIDIA_ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";
const DEFAULT_MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b";

function getConfig() {
  const apiKey = process.env.NVIDIA_API_KEY;
  const model = process.env.NVIDIA_MODEL || DEFAULT_MODEL;

  if (!apiKey) {
    throw new Error("NVIDIA_API_KEY is missing. Add it to your backend .env file.");
  }
  return { apiKey, model };
}

async function nvidiaChat(messages, options = {}) {
  const { apiKey, model } = getConfig();
  const timeoutMs = options.timeoutMs ?? 45000;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(NVIDIA_ENDPOINT, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
        Accept: "application/json"
      },
      body: JSON.stringify({
        model,
        messages,
        temperature: options.temperature ?? 0.2,
        max_tokens: options.maxTokens ?? 1200,
        stream: false
      }),
      signal: controller.signal
    });

    const raw = await response.text();
    let data;
    try {
      data = raw ? JSON.parse(raw) : {};
    } catch {
      throw new Error(`NVIDIA returned non-JSON response (HTTP ${response.status}): ${raw.slice(0, 500)}`);
    }

    if (!response.ok) {
      const detail = data?.detail || data?.error?.message || data?.message || raw;
      const error = new Error(`NVIDIA API error ${response.status}: ${String(detail).slice(0, 700)}`);
      error.status = response.status;
      throw error;
    }

    const content = data?.choices?.[0]?.message?.content;
    if (typeof content !== "string" || !content.trim()) {
      throw new Error("NVIDIA API returned an empty response.");
    }
    return content.trim();
  } catch (error) {
    if (error.name === "AbortError") {
      throw new Error(`NVIDIA API timed out after ${timeoutMs} ms.`);
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

async function summarizeText(text, options = {}) {
  if (!text || !String(text).trim()) throw new Error("Text to summarize is empty.");
  const maxInputChars = options.maxInputChars ?? 30000;
  const source = String(text).slice(0, maxInputChars);

  return nvidiaChat([
    {
      role: "system",
      content: "You are StudyGenie, a careful study assistant. Summarize source material accurately. Do not invent facts. Use clear headings, concise bullet points, key definitions, and important examples when present."
    },
    {
      role: "user",
      content: `Create useful study notes from the following material. Preserve important facts, terms, formulas, and examples. If the source is incomplete, do not fill gaps with guesses.\n\nMATERIAL:\n${source}`
    }
  ], { maxTokens: options.maxTokens ?? 1200, timeoutMs: options.timeoutMs ?? 45000 });
}

async function generateQuiz(text, count = 10, options = {}) {
  if (!text || !String(text).trim()) throw new Error("Source text for the quiz is empty.");
  const safeCount = Math.max(1, Math.min(Number(count) || 10, 20));
  const source = String(text).slice(0, options.maxInputChars ?? 22000);

  const result = await nvidiaChat([
    {
      role: "system",
      content: "You create accurate educational quizzes from provided study material. Return ONLY valid JSON. Do not use Markdown fences. Do not include facts not supported by the material."
    },
    {
      role: "user",
      content: `Create exactly ${safeCount} multiple-choice questions from this material. Return a JSON array. Each item must have: "question" (string), "options" (array of exactly 4 strings), "correctAnswer" (zero-based integer from 0 to 3), and "explanation" (string). Ensure the correct answer matches one option.\n\nMATERIAL:\n${source}`
    }
  ], { maxTokens: options.maxTokens ?? 1800, timeoutMs: options.timeoutMs ?? 45000 });

  return parseJsonFromModel(result);
}

async function generateFlashcards(text, count = 10, options = {}) {
  if (!text || !String(text).trim()) throw new Error("Source text for flashcards is empty.");
  const safeCount = Math.max(1, Math.min(Number(count) || 10, 30));
  const source = String(text).slice(0, options.maxInputChars ?? 22000);

  const result = await nvidiaChat([
    {
      role: "system",
      content: "You create accurate study flashcards from source material. Return ONLY valid JSON, without Markdown fences. Do not invent facts."
    },
    {
      role: "user",
      content: `Create ${safeCount} flashcards from the material. Return a JSON array of objects with exactly two string fields: "question" and "answer". Keep answers useful but concise.\n\nMATERIAL:\n${source}`
    }
  ], { maxTokens: options.maxTokens ?? 1400, timeoutMs: options.timeoutMs ?? 45000 });

  return parseJsonFromModel(result);
}

function parseJsonFromModel(text) {
  const cleaned = String(text)
    .replace(/^```(?:json)?\s*/i, "")
    .replace(/\s*```$/, "")
    .trim();

  try {
    return JSON.parse(cleaned);
  } catch {
    const start = cleaned.indexOf("[");
    const end = cleaned.lastIndexOf("]");
    if (start >= 0 && end > start) {
      try {
        return JSON.parse(cleaned.slice(start, end + 1));
      } catch {}
    }
    throw new Error("The AI returned invalid JSON. Please retry with fewer questions or shorter source text.");
  }
}

module.exports = {
  nvidiaChat,
  summarizeText,
  generateQuiz,
  generateFlashcards,
  DEFAULT_MODEL
};
