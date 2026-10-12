const WA_BASE = "https://api.whatsapp.com/agent/v1";
const DEFAULT_MODEL = process.env.GEMINI_MODEL || "gemini-2.5-flash";

const SYSTEM_INSTRUCTION = `You are Bixsigma AI, an advanced AI assistant created by Mirza Qasim. Your mindset is disciplined, focused, independent-minded, confident without arrogance, and practical. Help with general questions, learning, coding, writing, planning, and problem solving. Reply in the user's language, including Roman Urdu, Urdu, and English. When an image is provided, analyze it carefully. Never claim to have seen content you did not receive. Be honest about uncertainty. You are an AI assistant, not a human. Your creator is Mirza Qasim.`;

const required = ["WHATSAPP_API_TOKEN", "GEMINI_API_KEY"];
for (const name of required) {
  if (!process.env[name]) {
    console.error(`Missing required GitHub Actions secret: ${name}`);
    process.exit(1);
  }
}

const statePath = new URL("./state.json", import.meta.url);
let state;
try {
  state = JSON.parse(await (await import("node:fs/promises")).readFile(statePath, "utf8"));
} catch {
  state = { startedAt: Math.floor(Date.now() / 1000), nextOffset: "0" };
}
if (!state.startedAt) state.startedAt = Math.floor(Date.now() / 1000);
if (state.nextOffset === undefined || state.nextOffset === null) state.nextOffset = "0";

const fs = await import("node:fs/promises");

async function pollOnce() {
  const url = new URL(`${WA_BASE}/updates`);
  url.searchParams.set("offset", String(state.nextOffset));
  url.searchParams.set("limit", "10");
  url.searchParams.set("timeout", "0");

  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${process.env.WHATSAPP_API_TOKEN}` },
  });

  if (response.status === 204) {
    console.log("No new WhatsApp updates.");
    return;
  }
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 500);
    throw new Error(`WhatsApp updates failed (${response.status}): ${detail}`);
  }

  const payload = await response.json();
  const messages = [];
  for (const entry of payload.entry ?? []) {
    for (const change of entry.changes ?? []) {
      for (const message of change.value?.messages ?? []) messages.push(message);
    }
  }

  for (const message of messages) {
    if (!message?.id || !message?.from || !String(message.from).startsWith("user:")) continue;
    if (Number(message.timestamp || 0) < Number(state.startedAt)) {
      continue;
    }
    await handleMessage(message);
  }

  if (payload.next_offset !== undefined && payload.next_offset !== null) {
    state.nextOffset = String(payload.next_offset);
  }
  await saveState();
}

async function handleMessage(message) {
  let promptText = "";
  let imagePart = null;

  if (message.type === "text") {
    promptText = String(message.text?.body || "").trim();
  } else if (message.type === "image" && message.image?.id) {
    promptText = String(message.image.caption || "Please analyze the image I sent.").trim();
    imagePart = await getGeminiImagePart(message.image.id);
  } else {
    promptText = "The user sent a message type not supported by this starter version. Politely explain that text and images are supported for now.";
  }

  const parts = [{ text: promptText || "Please help me." }];
  if (imagePart) parts.push(imagePart);
  const reply = await askGemini([{ role: "user", parts }]);
  await sendWhatsAppText(message.from, reply);
  console.log(`Replied to ${message.id} (${message.type}).`);
}

async function askGemini(contents) {
  const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(DEFAULT_MODEL)}:generateContent?key=${encodeURIComponent(process.env.GEMINI_API_KEY)}`;
  const response = await fetch(endpoint, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTION }] },
      contents,
      generationConfig: { temperature: 0.7, maxOutputTokens: 1200 },
    }),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error("Gemini API error:", response.status, JSON.stringify(data).slice(0, 600));
    throw new Error(`Gemini API request failed (${response.status}); check the GEMINI_API_KEY and GEMINI_MODEL secret/setting.`);
  }
  const result = (data.candidates?.[0]?.content?.parts ?? []).map((part) => part.text || "").join("").trim();
  return (result || "I couldn't prepare a reply just now. Please try again.").slice(0, 4000);
}

async function getGeminiImagePart(mediaId) {
  const metadataResponse = await fetch(`${WA_BASE}/media/${encodeURIComponent(mediaId)}`, {
    headers: { Authorization: `Bearer ${process.env.WHATSAPP_API_TOKEN}` },
  });
  if (!metadataResponse.ok) throw new Error(`Could not get WhatsApp media metadata (${metadataResponse.status}).`);
  const metadata = await metadataResponse.json();
  if (!metadata.url) throw new Error("WhatsApp media metadata did not include a download URL.");

  const imageResponse = await fetch(metadata.url, {
    headers: { Authorization: `Bearer ${process.env.WHATSAPP_API_TOKEN}` },
  });
  if (!imageResponse.ok) throw new Error(`Could not download WhatsApp image (${imageResponse.status}).`);
  const mimeType = imageResponse.headers.get("content-type") || metadata.mime_type || "image/jpeg";
  const buffer = Buffer.from(await imageResponse.arrayBuffer());
  if (buffer.byteLength > 5 * 1024 * 1024) throw new Error("Image is over WhatsApp's 5 MB image limit.");
  return { inlineData: { mimeType, data: buffer.toString("base64") } };
}

async function sendWhatsAppText(recipient, text) {
  const response = await fetch(`${WA_BASE}/messages`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${process.env.WHATSAPP_API_TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      messaging_product: "whatsapp",
      to: recipient,
      type: "text",
      text: { body: String(text).slice(0, 4096) },
    }),
  });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 500);
    throw new Error(`WhatsApp send failed (${response.status}): ${detail}`);
  }
}

async function saveState() {
  await fs.writeFile(statePath, `${JSON.stringify(state, null, 2)}\n`, "utf8");
}

await pollOnce();
await saveState();
