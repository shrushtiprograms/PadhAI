const fetch = require('node-fetch');
const pdfParse = require('pdf-parse');

const fs = require('fs');
const os = require('os');
const path = require('path');

const { execFile } = require('child_process');
const { promisify } = require('util');

const execFileAsync = promisify(execFile);

// ==================== API KEYS ====================
// Each provider can have several keys (for example one from you and one from
// a teammate). When one key runs out of quota, the next one is used.
// .env example:
//   GOOGLE_API_KEY=teammate_key
//   GOOGLE_API_KEY_2=your_key
//   GROQ_API_KEY=teammate_key
//   GROQ_API_KEY_2=your_key
//   NVIDIA_API_KEY=teammate_key
//   NVIDIA_API_KEY_2=your_key
// Up to 5 keys per provider are supported (_2 ... _5).
function parseKeys(...envNames) {
  const keys = [];
  for (const name of envNames) {
    for (const part of String(process.env[name] || '').split(',')) {
      const key = part.trim();
      if (key && !keys.includes(key)) keys.push(key);
    }
  }
  return keys;
}

const GOOGLE_API_KEYS = parseKeys('GOOGLE_API_KEY', 'GOOGLE_API_KEY_2', 'GOOGLE_API_KEY_3', 'GOOGLE_API_KEY_4', 'GOOGLE_API_KEY_5');
const GROQ_API_KEYS = parseKeys('GROQ_API_KEY', 'GROQ_API_KEY_2', 'GROQ_API_KEY_3', 'GROQ_API_KEY_4', 'GROQ_API_KEY_5');
const NVIDIA_API_KEYS = parseKeys('NVIDIA_API_KEY', 'NVIDIA_API_KEY_2', 'NVIDIA_API_KEY_3', 'NVIDIA_API_KEY_4', 'NVIDIA_API_KEY_5');

// The first Google key is still used for image generation and text-to-speech.
const GOOGLE_API_KEY = GOOGLE_API_KEYS[0];

const GEMINI_MODEL = 'gemini-3.8-flash';

const GEMINI_URL = () =>
  `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`;

const GROQ_MODEL = 'qwen/qwen3.8-27b';
const GROQ_URL = 'https://api.groq.com/openai/v1/chat/completions';

// ==================== NVIDIA NIM ====================
// Text-only model: used for study summaries, quizzes, flashcards and chat.
// Vision requests continue to use Gemini/Groq because this model accepts text input.
const NVIDIA_MODEL = process.env.NVIDIA_MODEL || 'nvidia/nemotron-3.5-lightning-30b-a3b';
const NVIDIA_URL = 'https://integrate.api.nvidia.com/v1/chat/completions';

// Avoid repeatedly calling Gemini while a key's quota is exhausted (per key).
const geminiBlockedUntil = {};

function getGeminiRetryDelayMs(message) {
  const match = message.match(
    /Please retry in\s+(?:(\d+)h)?(?:(\d+)m)?([\d.]+)s/i
  );

  if (!match) {
    // If Google doesn't provide a retry duration, pause briefly.
    return 60_000;
  }

  const hours = Number(match[1] || 0);
  const minutes = Number(match[2] || 0);
  const seconds = Number(match[3] || 0);

  return Math.max(
    60_000,
    (hours * 3600 + minutes * 60 + seconds) * 1000
  );
}

// ==================== GEMINI ====================

async function callGemini(parts) {
  if (GOOGLE_API_KEYS.length === 0) {
    throw new Error('GOOGLE_API_KEY is not configured');
  }

  const errors = [];

  for (let i = 0; i < GOOGLE_API_KEYS.length; i++) {
    try {
      return await callGeminiWithKey(i, parts);
    } catch (error) {
      errors.push(`key ${i + 1}: ${error.message}`);

      // Only switch to the next key when this one is out of quota or invalid.
      // A busy server (503) or a timeout would hit the next key the same way.
      if (!error.rotateKey) throw error;

      console.warn(`[Gemini] Key ${i + 1} unusable; trying the next key if one is available...`);
    }
  }

  throw new Error(`Gemini unavailable on all keys: ${errors.join(' | ').slice(0, 600)}`);
}

async function callGeminiWithKey(keyIndex, parts) {
  const apiKey = GOOGLE_API_KEYS[keyIndex];
  const blockedUntil = geminiBlockedUntil[keyIndex] || 0;

  if (Date.now() < blockedUntil) {
    const minutesLeft = Math.ceil((blockedUntil - Date.now()) / 60_000);
    const err = new Error(
      `Gemini quota cooldown active; retry in about ${minutesLeft} minutes.`
    );
    err.rotateKey = true;
    throw err;
  }

  const controller = new AbortController();

  // Give Gemini up to 45 seconds.
  const timeout = setTimeout(() => {
    controller.abort();
  }, 45000);

  try {
    console.log(`[Gemini] Sending request (key ${keyIndex + 1}/${GOOGLE_API_KEYS.length})...`);

    const res = await fetch(GEMINI_URL(), {
      method: 'POST',

      headers: {
        'Content-Type': 'application/json',
        'x-goog-api-key': apiKey
      },

      body: JSON.stringify({
        contents: [
          {
            parts
          }
        ]
      }),

      signal: controller.signal
    });

    const raw = await res.text();

    console.log('[Gemini] HTTP status:', res.status);
    console.log('[Gemini] Response body preview:', raw.slice(0, 2000));

    if (res.status === 429) {
      geminiBlockedUntil[keyIndex] = Date.now() + getGeminiRetryDelayMs(raw);

      console.warn(
        `[Gemini] Quota exhausted on key ${keyIndex + 1}. Skipping this key until its retry window.`
      );

      const err = new Error(`Gemini quota exceeded (429): ${raw.slice(0, 500)}`);
      err.rotateKey = true;
      throw err;
    }

    if (res.status >= 500) {
      throw new Error(
        `Gemini server error (${res.status}): ${raw.slice(0, 500)}`
      );
    }

    if (!res.ok) {
      const err = new Error(
        `Gemini API error (${res.status}): ${raw.slice(0, 500)}`
      );
      // A rejected key (not a bad request) should move on to the next key.
      err.rotateKey =
        res.status === 401 ||
        res.status === 403 ||
        /API key not valid|API_KEY_INVALID/i.test(raw);
      throw err;
    }

    let data;

    try {
      data = JSON.parse(raw);
    } catch {
      throw new Error('Gemini returned invalid JSON');
    }

    if (data.error) {
      throw new Error(
        `Gemini API error: ${
          data.error.message || JSON.stringify(data.error)
        }`
      );
    }

    const text =
      data.candidates?.[0]?.content?.parts?.find(
        part => part.text
      )?.text;

    if (!text) {
      throw new Error('Gemini returned no response text');
    }

    return text;

  } catch (error) {

    if (error.name === 'AbortError') {
      throw new Error(
        'Gemini request timed out after 45 seconds'
      );
    }

    throw error;

  } finally {
    clearTimeout(timeout);
  }
}

// ==================== NVIDIA ====================

const nvidiaBlockedUntil = {};

async function callNvidia(parts, maxCompletionTokens = 2048) {
  if (NVIDIA_API_KEYS.length === 0) {
    throw new Error('NVIDIA_API_KEY is not configured');
  }

  // The selected Nemotron endpoint is text-only. Do not send image/PDF binary parts.
  if (parts.some(part => part.inline_data || part.inlineData)) {
    throw new Error('NVIDIA text model does not accept image/PDF input; skipping NVIDIA for this request');
  }

  const errors = [];

  for (let i = 0; i < NVIDIA_API_KEYS.length; i++) {
    if (Date.now() < (nvidiaBlockedUntil[i] || 0)) {
      errors.push(`key ${i + 1}: cooling down after a rate limit`);
      continue;
    }

    try {
      return await callNvidiaWithKey(i, parts, maxCompletionTokens);
    } catch (error) {
      errors.push(`key ${i + 1}: ${error.message}`);

      if (!error.rotateKey) throw error;

      console.warn(`[NVIDIA] Key ${i + 1} unusable; trying the next key if one is available...`);
    }
  }

  throw new Error(`NVIDIA unavailable on all keys: ${errors.join(' | ').slice(0, 600)}`);
}

async function callNvidiaWithKey(keyIndex, parts, maxCompletionTokens = 2048) {
  const apiKey = NVIDIA_API_KEYS[keyIndex];

  const prompt = parts
    .filter(part => typeof part.text === 'string')
    .map(part => part.text)
    .join('\n\n')
    .trim();

  if (!prompt) {
    throw new Error('No text prompt provided to NVIDIA');
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 45000);

  try {
    console.log(`[NVIDIA] Sending text request (key ${keyIndex + 1}/${NVIDIA_API_KEYS.length})...`);

    const wantsJson = /JSON array|valid JSON|JSON object/i.test(prompt);

    const requestBody = {
      model: NVIDIA_MODEL,
      messages: [{ role: 'user', content: prompt }],
      temperature: 0.1,
      max_tokens: Math.min(Math.max(Number(maxCompletionTokens) || 2048, 128), 4096),
      stream: false,
      // Nemotron can otherwise spend its output budget on a reasoning trace.
      chat_template_kwargs: { enable_thinking: false }
    };

    // NVIDIA supports JSON mode for structured outputs. Use an object wrapper
    // for quiz/flashcard prompts so the response is valid JSON, then unwrap it.
    if (wantsJson) {
      requestBody.response_format = { type: 'json_object' };
    }

    const res = await fetch(NVIDIA_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey}`,
        'Accept': 'application/json'
      },
      body: JSON.stringify(requestBody),
      signal: controller.signal
    });

    const raw = await res.text();
    console.log('[NVIDIA] HTTP status:', res.status);

    if (!res.ok) {
      if (res.status === 429) {
        nvidiaBlockedUntil[keyIndex] = Date.now() + 60_000;
      }

      const err = new Error(`NVIDIA API error (${res.status}): ${raw.slice(0, 1000)}`);
      err.rotateKey = res.status === 401 || res.status === 403 || res.status === 429;
      throw err;
    }

    let data;
    try {
      data = JSON.parse(raw);
    } catch {
      throw new Error('NVIDIA returned invalid JSON');
    }

    const text = data.choices?.[0]?.message?.content;
    if (typeof text !== 'string' || !text.trim()) {
      throw new Error('NVIDIA returned no response text');
    }

    return text.trim();
  } catch (error) {
    if (error.name === 'AbortError') {
      throw new Error('NVIDIA request timed out after 45 seconds');
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

// Text-only fallback chain for extracted PDF text and other text-only tasks.
async function callTextAI(parts, maxCompletionTokens = 2048) {
  try {
    console.log('[AI] Trying NVIDIA...');
    return await callNvidia(parts, maxCompletionTokens);
  } catch (nvidiaError) {
    console.error('[AI] NVIDIA failed:', nvidiaError.message);
  }

  try {
    console.log('[AI] Falling back to Groq for text...');
    return await callGroq(parts, maxCompletionTokens);
  } catch (groqError) {
    console.error('[AI] Groq text fallback failed:', groqError.message);
    throw new Error(`Text AI providers failed. NVIDIA: ${NVIDIA_API_KEYS.length ? 'request failed' : 'not configured'}. Groq: ${groqError.message}`);
  }
}


async function callAI(parts, maxCompletionTokens = 2048) {
  // 1. Try Gemini first.
  try {
    console.log('[AI] Trying Gemini...');
    return await callGemini(parts);
  } catch (geminiError) {
    console.error('[AI] Gemini failed:', geminiError.message);
  }

  // 2. Try Groq next to avoid waiting 45 seconds for NVIDIA.
  try {
    console.log('[AI] Falling back to Groq...');
    return await callGroq(parts, maxCompletionTokens);
  } catch (groqError) {
    console.error('[AI] Groq failed:', groqError.message);
  }

  // 3. NVIDIA is the final fallback.
  try {
    console.log('[AI] Falling back to NVIDIA...');
    return await callNvidia(parts, maxCompletionTokens);
  } catch (nvidiaError) {
    console.error('[AI] NVIDIA fallback failed:', nvidiaError.message);

    throw new Error(
      `All AI providers failed. Check Gemini, Groq, and NVIDIA errors in the backend logs.`
    );
  }
}


async function performGroqRequest(parts, maxCompletionTokens = 2048, apiKey) {
  if (!apiKey) {
    throw new Error('GROQ_API_KEY is not configured');
  }

  // Convert Gemini-style parts into Groq's OpenAI-compatible
  // multimodal message format.
  const content = [];

  for (const part of parts) {

    // Normal text prompt
    if (part.text) {
      content.push({
        type: 'text',
        text: part.text
      });
    }

    // Image/PDF binary data
    if (part.inline_data) {
      const mimeType = part.inline_data.mime_type;
      const base64Data = part.inline_data.data;

      // Groq vision supports images.
      if (mimeType.startsWith('image/')) {
        content.push({
          type: 'image_url',
          image_url: {
            url: `data:${mimeType};base64,${base64Data}`
          }
        });

        console.log(
          `[Groq] Image attached to vision request (${mimeType})`
        );
      }

      // Never send raw PDFs to Groq.
      if (mimeType === 'application/pdf') {
        throw new Error(
          'Groq cannot directly analyze raw PDF files. ' +
          'PDF text extraction or page-image conversion is required.'
        );
      }
    }
  }

  if (content.length === 0) {
    throw new Error('No valid content provided to Groq');
  }

  console.log('[Groq] Sending multimodal request...');

  const res = await fetch(GROQ_URL, {
    method: 'POST',

    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${apiKey}`
    },

    body: JSON.stringify({
      model: GROQ_MODEL,

      messages: [
        {
          role: 'user',
          content
        }
      ],

      temperature: 0.2,
      // Groq's free tier allows only ~1,000 output tokens per minute,
      // so larger requests are rejected with "Request too large".
      max_completion_tokens: Math.min(maxCompletionTokens, 900)
    })
  });

  const raw = await res.text();

  console.log('[Groq] HTTP status:', res.status);

  if (!res.ok) {
    throw new Error(
      `Groq API error (${res.status}): ${raw.slice(0, 1000)}`
    );
  }

  let data;

  try {
    data = JSON.parse(raw);
  } catch {
    throw new Error('Groq returned invalid JSON');
  }

  const text = data.choices?.[0]?.message?.content;

  if (!text) {
    throw new Error('Groq returned no response text');
  }

  return text;
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// Groq's current organization limit is 1,000 output tokens per minute (per key).
// Serialize fallback calls and leave a buffer between requests made with the
// same key. With several keys, the key that is free soonest is used.
const GROQ_MIN_GAP_MS = 61_000;
let groqQueue = Promise.resolve();
const groqKeyState = GROQ_API_KEYS.map(() => ({ lastFinishedAt: 0 }));

function callGroq(parts, maxCompletionTokens = 2048) {
  if (GROQ_API_KEYS.length === 0) {
    return Promise.reject(new Error('GROQ_API_KEY is not configured'));
  }

  const request = groqQueue.then(async () => {
    // Pick the key that will be available soonest.
    let bestIndex = 0;
    let bestWaitMs = Infinity;

    groqKeyState.forEach((state, index) => {
      const waitMs = state.lastFinishedAt
        ? Math.max(0, GROQ_MIN_GAP_MS - (Date.now() - state.lastFinishedAt))
        : 0;

      if (waitMs < bestWaitMs) {
        bestWaitMs = waitMs;
        bestIndex = index;
      }
    });

    if (bestWaitMs > 0) {
      console.warn(
        `[Groq] Quota protection (key ${bestIndex + 1}/${GROQ_API_KEYS.length}): waiting ${Math.ceil(bestWaitMs / 1000)}s before next request.`
      );
      await sleep(bestWaitMs);
    }

    try {
      return await performGroqRequest(
        parts,
        maxCompletionTokens,
        GROQ_API_KEYS[bestIndex]
      );
    } finally {
      groqKeyState[bestIndex].lastFinishedAt = Date.now();
    }
  });

  // A failed request must not permanently break the queue.
  groqQueue = request.then(() => undefined, () => undefined);
  return request;
}


async function callGroqWithRetry(
  parts,
  maxRetries = 4,
  maxCompletionTokens = 2048
) {
  for (let attempt = 1; attempt <= maxRetries + 1; attempt++) {

    try {

      return await callGroq(parts, maxCompletionTokens);

    } catch (error) {

      const message = error.message || '';

      const isRateLimit =
        message.includes('429') ||
        message.toLowerCase().includes('rate limit');

      if (!isRateLimit || attempt > maxRetries) {
        throw error;
      }

      // Try to extract Groq's suggested wait time.
      const match = message.match(
        /try again in ([0-9.]+)s/i
      );

      let waitSeconds = match
        ? parseFloat(match[1])
        : 12;

      // Add a small safety margin.
      waitSeconds += 2;

      // Prevent an unreasonable wait.
      waitSeconds = Math.min(
        Math.max(waitSeconds, 5),
        60
      );

      console.warn(
        `[Groq] Rate limit reached. ` +
        `Waiting ${waitSeconds.toFixed(1)} seconds ` +
        `before retry ${attempt}/${maxRetries}...`
      );

      await sleep(waitSeconds * 1000);
    }
  }

  throw new Error('Groq request failed after retries');
}

// wrapper that handles text or image summarization
async function summarizeText(text, detailLevel = 'brief', imageBase64 = null, lang = 'en') {
  const detail = detailLevel === 'detailed' ? 'detailed and comprehensive' : 'concise';
  const langInstruction = lang === 'hi'
    ? 'Respond entirely in Hindi (Devanagari script).'
    : 'Respond in English.';

  if (imageBase64) {
    let mimeType = 'image/jpeg';
    let cleanB64 = imageBase64;
    if (imageBase64.startsWith('data:')) {
      const m = imageBase64.match(/data:([^;]+)/);
      if (m) mimeType = m[1];
      cleanB64 = imageBase64.split(',')[1];
    }
    console.log('[Gemini] summarize image, mime:', mimeType);
    const prompt = `You are an expert AI tutor. ${langInstruction} Analyse this image which may contain handwritten or typed notes. Produce a ${detail} summary:\n- Transcribe all visible text accurately.\n- Organise the content into clear bullet points.\n- Highlight key concepts, definitions, and important takeaways.`;
    return await callAI([
      { text: prompt },
      { inline_data: { mime_type: mimeType, data: cleanB64 } }
    ]);
  }

  if (!text || !text.trim()) throw new Error('Either text or imageBase64 must be provided');
  if (text.length > 60000) { console.warn('Trimming text to 60k chars'); text = text.slice(0, 60000); }
  console.log('[Gemini] summarize text, len:', text.length);
  const summaryLength = detailLevel === 'detailed'
    ? 'Use up to 8 concise bullet points and keep the whole summary under about 650 words.'
    : 'Use 5-6 concise bullet points and keep the whole summary under about 300 words.';
  const prompt = `You are an expert AI tutor. ${langInstruction} Summarise the following notes clearly.\n- ${summaryLength}\n- Include the most important concepts, definitions, and takeaways.\n- Rephrase and condense; do not copy sentences verbatim.\n- Do not add an introduction or conclusion unless needed.\n\nNotes:\n${text}`;
  return await callTextAI([{ text: prompt }], detailLevel === 'detailed' ? 800 : 600);
}

async function extractHandwrittenPdfWithVision(
  pdfBase64,
  lang = 'en'
) {
  const tempDir = await fs.promises.mkdtemp(
    path.join(os.tmpdir(), 'padhai-pdf-')
  );

  const pdfPath = path.join(
    tempDir,
    'input.pdf'
  );

  const outputPrefix = path.join(
    tempDir,
    'page'
  );

  try {

    console.log(
      '[PDF Vision] Creating temporary PDF...'
    );

    await fs.promises.writeFile(
      pdfPath,
      Buffer.from(pdfBase64, 'base64')
    );

    console.log(
      '[PDF Vision] Converting PDF pages to images...'
    );

    /*
     * 90 DPI is enough for handwriting while keeping
     * the image size/token usage lower than 120 DPI.
     */
    // If poppler is not on PATH, set POPPLER_PATH in .env to its bin folder,
    // e.g. POPPLER_PATH=C:\\Users\\you\\AppData\\Local\\...\\Library\\bin
    const pdftoppmName = process.platform === 'win32' ? 'pdftoppm.exe' : 'pdftoppm';
    const pdftoppmCommand = process.env.POPPLER_PATH
      ? path.join(process.env.POPPLER_PATH.trim().replace(/^"|"$/g, ''), pdftoppmName)
      : 'pdftoppm';

    await execFileAsync(pdftoppmCommand, [
      '-png',
      '-r',
      '90',
      pdfPath,
      outputPrefix
    ]);

    const files = (await fs.promises.readdir(tempDir))
      .filter(file =>
        /^page-\d+\.png$/i.test(file)
      )
      .sort((a, b) => {

        const pageA = parseInt(
          a.match(/(\d+)/)[1],
          10
        );

        const pageB = parseInt(
          b.match(/(\d+)/)[1],
          10
        );

        return pageA - pageB;
      });

    console.log(
      `[PDF Vision] Converted ${files.length} pages`
    );

    if (files.length === 0) {
      throw new Error(
        'PDF pages could not be converted to images.'
      );
    }

    const batchTexts = [];

    const visionInstruction =
      lang === 'hi'
        ? 'Respond entirely in Hindi (Devanagari script).'
        : 'Respond in English.';

    /*
     * qwen/qwen3.8-27b supports up to 3 images
     * in one vision request.
     */
    const BATCH_SIZE = 3;

    for (
      let start = 0;
      start < files.length;
      start += BATCH_SIZE
    ) {

      const batchFiles =
        files.slice(
          start,
          start + BATCH_SIZE
        );

      const firstPage =
        start + 1;

      const lastPage =
        start + batchFiles.length;

      console.log(
        `[PDF Vision] Analysing pages ` +
        `${firstPage}-${lastPage}/${files.length}...`
      );

      const batchParts = [];

      const batchPrompt = `You are reading handwritten study notes from PDF pages ${firstPage} to ${lastPage}.

${visionInstruction}

Carefully read ALL provided pages.

Rules:
- Extract only the actual academic content visible in the pages.
- Preserve headings, definitions, formulas, examples and important bullet points.
- Keep the page order.
- Combine related points when appropriate.
- Do NOT invent missing information.
- If handwriting is genuinely unclear, write [unclear].
- Do NOT describe the physical appearance of the pages.
- Return concise but complete study content.
- Do not add outside knowledge.`;

      batchParts.push({
        text: batchPrompt
      });

      for (let i = 0; i < batchFiles.length; i++) {

        const filePath = path.join(
          tempDir,
          batchFiles[i]
        );

        const imageBuffer =
          await fs.promises.readFile(filePath);

        const imageBase64 =
          imageBuffer.toString('base64');

        batchParts.push({
          inline_data: {
            mime_type: 'image/png',
            data: imageBase64
          }
        });
      }

      const batchText =
        await callGroqWithRetry(
          batchParts,
          4
        );

      batchTexts.push(
        `===== PAGES ${firstPage}-${lastPage} =====\n` +
        batchText
      );

      console.log(
        `[PDF Vision] Pages ${firstPage}-${lastPage} completed`
      );

      /*
       * Small pause between batches.
       * This reduces the chance of immediately hitting
       * the organization input-token limit.
       */
      if (lastPage < files.length) {
        console.log(
          '[PDF Vision] Waiting briefly before next batch...'
        );

        await sleep(5000);
      }
    }

    console.log(
      '[PDF Vision] All page batches analysed successfully'
    );

    const combinedText =
      batchTexts.join('\n\n');

    console.log(
      '[PDF Vision] Creating final summary...'
    );

    const summaryPrompt = `You are an expert AI tutor.

${lang === 'hi'
  ? 'Respond entirely in Hindi (Devanagari script).'
  : 'Respond in English.'
}

Create a clear, study-friendly summary from the handwritten PDF content below.

Rules:
- Use ONLY the extracted content below.
- Do NOT add outside information.
- Do NOT invent missing information.
- Organize the answer with useful headings.
- Use bullet points where appropriate.
- Include important definitions.
- Include important formulas and equations.
- Include important examples.
- Highlight the most important concepts and takeaways.
- Preserve the meaning of the original notes.
- If something is marked [unclear], do not guess it.
- Remove unnecessary repetition.

===== EXTRACTED HANDWRITTEN PDF CONTENT =====

${combinedText}

===== END EXTRACTED CONTENT =====`;

    const finalSummary =
      await callTextAI(
        [
          {
            text: summaryPrompt
          }
        ],
        2048
      );

    console.log(
      '[PDF Vision] Final summary created successfully'
    );

    return finalSummary;

  } finally {

    try {

      await fs.promises.rm(
        tempDir,
        {
          recursive: true,
          force: true
        }
      );

      console.log(
        '[PDF Vision] Temporary files cleaned up'
      );

    } catch (cleanupError) {

      console.warn(
        '[PDF Vision] Cleanup warning:',
        cleanupError.message
      );
    }
  }
}

async function summarizePDF(
  pdfBase64,
  detailLevel = 'brief',
  lang = 'en'
) {
  const detail =
    detailLevel === 'detailed'
      ? 'detailed and comprehensive'
      : 'concise';

  const langInstruction =
    lang === 'hi'
      ? 'Respond entirely in Hindi (Devanagari script).'
      : 'Respond in English.';

  const prompt = `You are an expert AI tutor. ${langInstruction}

Analyse this PDF document and produce a ${detail} summary.

Rules:
- Base the answer ONLY on the actual PDF content.
- Do NOT invent information.
- Use clear bullet points for key concepts and definitions.
- Highlight the most important takeaways.
- Describe equations, tables, and diagrams in plain text when possible.
- Do NOT copy paragraphs verbatim; summarise and condense.`;

  console.log('[Gemini] summarize PDF');
  // ============================================================
  // STEP 1: Try Gemini with the actual PDF
  // ============================================================

  try {

    return await callGemini([
      { text: prompt },
      {
        inline_data: {
          mime_type: 'application/pdf',
          data: pdfBase64
        }
      }
    ]);

  } catch (geminiError) {

    console.error(
      '[PDF] Gemini PDF analysis failed:',
      geminiError.message
    );

    console.log(
      '[PDF] Falling back to PDF text extraction...'
    );
  }

  // ============================================================
  // STEP 2: Try normal PDF text extraction
  // ============================================================

  try {

    const pdfBuffer =
      Buffer.from(pdfBase64, 'base64');

    // Some PDFs make the text extractor crash (for example "bad XRef entry").
    // Treat that like "no selectable text" so the page-image fallback can run.
    let pdfData = { text: '', numpages: 0 };

    try {
      pdfData = await pdfParse(pdfBuffer);
    } catch (parseError) {
      console.warn(
        '[PDF] Text extraction failed:',
        parseError.message
      );
    }

    let extractedText =
      (pdfData.text || '').trim();

    console.log(
      '[PDF] Pages:',
      pdfData.numpages
    );

    console.log(
      '[PDF] Extracted text length:',
      extractedText.length
    );

    // ========================================================
    // STEP 3A: Normal text PDF
    // ========================================================

    if (extractedText) {

      if (extractedText.length > 50000) {

        console.warn(
          '[PDF] Text is large. Limiting to 50,000 characters.'
        );

        extractedText =
          extractedText.slice(0, 50000);
      }

      const fallbackPrompt = `${prompt}

The PDF could not be analysed directly by Gemini.

The following text was extracted directly from the PDF.
Use ONLY this text as the source for the answer.

----- PDF CONTENT START -----

${extractedText}

----- PDF CONTENT END -----`;

      console.log(
        '[PDF] Sending extracted text to the text AI chain...'
      );

      const groqResult =
        await callTextAI([
          {
            text: fallbackPrompt
          }
        ]);

      console.log(
        '[PDF] Text fallback succeeded'
      );

      return groqResult;
    }

    // ========================================================
    // STEP 3B: Scanned / handwritten PDF
    // ========================================================

    console.log(
      '[PDF] No selectable text found.'
    );

    console.log(
      '[PDF] Switching to page-image vision fallback...'
    );

    return await extractHandwrittenPdfWithVision(
      pdfBase64,
      lang
    );

  } catch (fallbackError) {

    console.error(
      '[PDF] Fallback failed:',
      fallbackError.message
    );

    // pdftoppm (poppler) is not installed on this computer.
    if (
      fallbackError.code === 'ENOENT' ||
      /ENOENT/.test(String(fallbackError.message))
    ) {
      throw new Error(
        'This PDF has no selectable text (it looks scanned) and the AI could not read it directly. ' +
        'Please try again in a few minutes, or use a text-based PDF.'
      );
    }

    throw new Error(
      `PDF analysis failed: ${fallbackError.message}`
    );
  }
}

// Generate an AI infographic/slide image for the summary using Gemini image generation
async function generateSlideImage(summary) {
  if (!GOOGLE_API_KEY) throw new Error('GOOGLE_API_KEY not set – check .env');
  const url = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp-image-generation:generateContent?key=${GOOGLE_API_KEY}`;
  const prompt = `Create a visually appealing, professional educational summary slide/infographic. It should look like a high-quality study card with:
- A bold title at the top: "AI Summary"
- The key points listed as clean bullet points with icons
- A modern, clean design with a blue and white color scheme
- Clear, readable typography
- Visual hierarchy with sections

Content to visualise:
${summary.slice(0, 800)}`;

  const body = {
    contents: [{ parts: [{ text: prompt }] }],
    generationConfig: { responseModalities: ['IMAGE'] }
  };

  console.log('[Gemini-Image-Gen] Generating slide image...');
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const raw = await res.text();
  if (raw.trimStart().startsWith('<')) throw new Error(`Gemini image API HTTP ${res.status}: ${raw.slice(0, 200)}`);
  const data = JSON.parse(raw);
  if (data.error) throw new Error(`Gemini image error ${data.error.code}: ${data.error.message}`);

  // find the image part in the response
  const parts = data.candidates?.[0]?.content?.parts || [];
  const imgPart = parts.find(p => p.inlineData || p.inline_data);
  if (!imgPart) throw new Error('No image returned from Gemini image generation');
  const inline = imgPart.inlineData || imgPart.inline_data;
  return { mimeType: inline.mime_type, base64: inline.data };
}

// ---------------------------------------------------------------------------
// TTS – Google Text-to-Speech REST API
// ---------------------------------------------------------------------------

const AZURE_SPEECH_KEY = process.env.AZURE_SPEECH_KEY;
const AZURE_SPEECH_REGION = process.env.AZURE_SPEECH_REGION;

async function synthesizeSpeech(text, language = 'en-US', voice = 'en-US-JennyNeural') {
  if (!GOOGLE_API_KEY) throw new Error('Google API key not set for TTS');
  const url = `https://texttospeech.googleapis.com/v1/text:synthesize?key=${GOOGLE_API_KEY}`;
  const body = {
    input: { text },
    voice: { languageCode: language, name: voice },
    audioConfig: { audioEncoding: 'MP3' }
  };
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const data = await res.json();
  if (data.error) {
    throw new Error(`Google TTS error: ${JSON.stringify(data.error)}`);
  }
  return data.audioContent; // already base64
}

function escapeXml(unsafe) {
  return unsafe.replace(/[<>&"']/g, function (c) {
    switch (c) {
      case '<': return '&lt;';
      case '>': return '&gt;';
      case '&': return '&amp;';
      case '"': return '&quot;';
      case "'": return '&apos;';
    }
  });
}


// OCR via Gemini vision – no Azure CV needed
async function extractTextFromImage(imageBase64) {
  let mimeType = 'image/jpeg';
  let cleanB64 = imageBase64;
  if (imageBase64.startsWith('data:')) {
    const m = imageBase64.match(/data:([^;]+)/);
    if (m) mimeType = m[1];
    cleanB64 = imageBase64.split(',')[1];
  }
  console.log('[Gemini] OCR image, mime:', mimeType);
  const prompt = 'You are an OCR engine. Transcribe ALL text visible in this image exactly as written, preserving line breaks and structure. Output only the transcribed text, nothing else.';
  return await callAI([
    { text: prompt },
    { inline_data: { mime_type: mimeType, data: cleanB64 } }
  ]);
}

// Collects every COMPLETE top-level {...} object from a JSON array body.
// Works even when the response was cut off before the array was closed.
function extractCompleteObjects(text, startIndex) {
  const objects = [];
  let depth = 0;
  let inString = false;
  let escaped = false;
  let objStart = -1;

  for (let i = startIndex; i < text.length; i++) {
    const ch = text[i];

    if (inString) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }

    if (ch === '"') { inString = true; continue; }

    if (ch === '{') {
      if (depth === 0) objStart = i;
      depth++;
    } else if (ch === '}') {
      depth--;
      if (depth === 0 && objStart >= 0) {
        try { objects.push(JSON.parse(text.slice(objStart, i + 1))); } catch {}
        objStart = -1;
      } else if (depth < 0) {
        break;
      }
    } else if (ch === ']' && depth === 0) {
      break;
    }
  }
  return objects;
}

function parseStructuredArray(raw, arrayKeys = []) {
  const cleaned = String(raw)
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .replace(/```(?:json)?/gi, '')
    .trim();

  const pickArray = (parsed) => {
    if (Array.isArray(parsed)) return parsed;
    if (parsed && typeof parsed === 'object') {
      for (const key of arrayKeys) {
        if (Array.isArray(parsed[key])) return parsed[key];
      }
      for (const value of Object.values(parsed)) {
        if (Array.isArray(value)) return value;
      }
    }
    return null;
  };

  // 1. The whole response is valid JSON.
  try {
    const arr = pickArray(JSON.parse(cleaned));
    if (arr) return arr;
  } catch {}

  // 2. Valid JSON object surrounded by extra text.
  const objStart = cleaned.indexOf('{');
  const objEnd = cleaned.lastIndexOf('}');
  if (objStart >= 0 && objEnd > objStart) {
    try {
      const arr = pickArray(JSON.parse(cleaned.slice(objStart, objEnd + 1)));
      if (arr) return arr;
    } catch {}
  }

  // 3. A bare JSON array surrounded by extra text.
  const arrStart = cleaned.indexOf('[');
  const arrEnd = cleaned.lastIndexOf(']');
  if (arrStart >= 0 && arrEnd > arrStart) {
    try {
      const parsed = JSON.parse(cleaned.slice(arrStart, arrEnd + 1));
      if (Array.isArray(parsed)) return parsed;
    } catch {}
  }

  // 4. Cut-off or slightly broken JSON: keep every complete item we can find.
  let salvageFrom = -1;
  for (const key of arrayKeys) {
    const m = cleaned.match(new RegExp('"' + key + '"\\s*:\\s*\\['));
    if (m) { salvageFrom = m.index + m[0].length; break; }
  }
  if (salvageFrom < 0 && arrStart >= 0) salvageFrom = arrStart + 1;

  if (salvageFrom >= 0) {
    const items = extractCompleteObjects(cleaned, salvageFrom);
    if (items.length > 0) {
      console.warn(`[AI] JSON was incomplete; recovered ${items.length} complete item(s).`);
      return items;
    }
  }

  console.error('[AI] Could not parse structured response. Tail of response:', cleaned.slice(-300));
  throw new Error(
    'AI returned incomplete or invalid JSON. Retry the request; if it persists, check the provider output/token limit.'
  );
}

// AI quiz generation – returns array of MCQ question objects
async function generateQuiz(notes) {
  const prompt = `You are an expert educator. Based on the following notes, generate a comprehensive multiple-choice quiz with exactly 10 questions.

Rules:
- Cover ALL major topics and concepts from the notes
- Each question must have exactly 4 options (A, B, C, D)
- Only one option is correct
- Make distractors plausible but clearly wrong upon understanding
- Vary question types: definition, application, comparison, true/false style
- Keep each question, option, and explanation concise; each explanation must be one short sentence

Return ONLY a valid JSON object, with no markdown and no extra explanation. The object must have one key named "questions" whose value is an array of exactly 10 question objects. Each question object must have:
- "question": string
- "options": array of exactly 4 strings
- "correct": integer 0, 1, 2, or 3
- "explanation": one short sentence
Example shape: {"questions":[{"question":"...","options":["A","B","C","D"],"correct":0,"explanation":"..."}]}

Notes:
${notes.slice(0, 12000)}`;

  console.log('[Gemini] generating quiz...');
  const raw = await callTextAI([{ text: prompt }], 2500);

  console.log('[Gemini] quiz response length:', raw.length);
  console.log('[Gemini] quiz response preview:', raw.slice(0, 500));
  // strip any accidental markdown code fences
  const parsed = parseStructuredArray(raw, ['questions', 'quiz']);
  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new Error('Quiz response did not contain any questions');
  }
  return parsed;
}

// AI flashcard generation – returns array of { front, back } objects
async function generateFlashcards(notes) {
  const prompt = `You are an expert educator. Based on the following notes, generate comprehensive flashcards that cover every key concept, term, formula, and fact.

Rules:
- Generate 8 to 10 flashcards depending on content density
- Front: a concise question, term, or concept (max 15 words)
- Back: a clear answer in 1-2 concise sentences
- Cover definitions, key facts, processes, formulas, and comparisons
- Do NOT repeat concepts

Return ONLY a valid JSON object, with no markdown and no extra explanation. The object must have one key named "flashcards" whose value is an array of 8 to 10 flashcard objects. Each object must contain exactly two string fields: "front" and "back".
Example shape: {"flashcards":[{"front":"What is ...?","back":"..."}]}

Notes:
${notes.slice(0, 12000)}`;

  console.log('[Gemini] generating flashcards...');
  const raw = await callTextAI([{ text: prompt }], 2500);
  const parsed = parseStructuredArray(raw, ['flashcards', 'cards']);
  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new Error('Flashcard response did not contain any cards');
  }
  return parsed;
}

// AI key points extraction – returns structured markdown bullet points
async function generateKeyPoints(notes, lang = 'en') {
  const langInstr = lang === 'hi' ? 'Respond entirely in Hindi (Devanagari script).' : 'Respond in English.'
  const prompt = `You are an expert educator. ${langInstr} Extract and organise the key points, important concepts, definitions, and takeaways from the following notes.

Format your response as well-structured markdown:
- Use ## headings for major sections if the content has distinct topics
- Use bullet points (- ) for key facts and concepts
- Use **bold** for important terms and definitions
- Use numbered lists for processes or steps
- Be concise but comprehensive — cover everything important
- Do NOT include unnecessary filler text

Notes:
${notes.slice(0, 12000)}`;

  console.log('[Gemini] generating key points, lang:', lang);
  return await callTextAI([{ text: prompt }]);
}

// AI detailed explanation – returns structured markdown explanation
async function generateExplanation(notes, lang = 'en') {
  const langInstr = lang === 'hi' ? 'Respond in Hindi (Devanagari script).' : 'Respond in English.'
  const prompt = `You are an expert AI tutor. ${langInstr} Provide a detailed, easy-to-understand explanation of the following study material.

Format your response as well-structured markdown:
- Start with a ## Overview section
- Break down complex concepts step by step
- Use ## headings for each major topic
- Use bullet points for sub-points and examples
- Use **bold** for key terms
- Add a ## Key Takeaways section at the end
- Use analogies and real-world examples where helpful

Notes / Summary:
${notes.slice(0, 12000)}`;

  console.log('[Gemini] generating explanation, lang:', lang);
  return await callTextAI([{ text: prompt }]);
}

// AI chat response for follow-up questions
async function generateChatResponse(context, history, question, lang = 'en') {
  const langInstr = lang === 'hi'
    ? 'Respond entirely in Hindi (Devanagari script).'
    : 'Respond in English.'

  const historyText = history.length > 0
    ? 'Previous conversation:\n' + history.slice(-12).map(m =>
        `${m.role === 'user' ? 'Student' : 'AI Tutor'}: ${m.content.slice(0, 400)}`
      ).join('\n') + '\n\n'
    : ''

  const prompt = `You are an expert AI tutor helping a student understand their study material. ${langInstr}

Study Material:
${context.slice(0, 8000)}

${historyText}Student's Question: ${question}

Provide a clear, helpful, and educational answer. Use markdown formatting (bold, bullets, numbered lists) where it aids clarity. Be concise but thorough.`

  console.log('[AI] generating chat response, lang:', lang)
  return await callTextAI([{ text: prompt }])
}

module.exports = { callTextAI, summarizeText, summarizePDF, generateSlideImage, generateQuiz, generateFlashcards, generateKeyPoints, generateExplanation, generateChatResponse, synthesizeSpeech, extractTextFromImage };