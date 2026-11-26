/**
 * SENE GUARDIÁN private owner-event receiver.
 *
 * Isolated handler module. It is not yet mounted in the main Worker router.
 * Stores only the encrypted envelope in KV; decrypts only after the owner/Muse
 * installation token is validated. Configure dedicated 32-byte AES and HMAC
 * keys matching SERVER-SENE, plus GUARDIAN_DATA.
 */
export interface OwnerDeliveryEnv {
  GUARDIAN_DATA: KVNamespace;
  GUARDIAN_DELIVERY_ENC_KEY: string;
  GUARDIAN_DELIVERY_HMAC_KEY: string;
}
const enc = new TextEncoder();
const dec = new TextDecoder();

function fromB64(value: string): Uint8Array {
  const raw = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
function toB64(bytes: Uint8Array): string {
  let raw = "";
  for (const b of bytes) raw += String.fromCharCode(b);
  return btoa(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
  });
}
async function hmacKey(value: string): Promise<CryptoKey> {
  const raw = fromB64(value);
  if (raw.byteLength < 32) throw new Error("invalid_hmac_key");
  return crypto.subtle.importKey("raw", raw, { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
}
async function aesKey(value: string): Promise<CryptoKey> {
  const raw = fromB64(value);
  if (raw.byteLength !== 32) throw new Error("invalid_aes_key");
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["decrypt"]);
}
function bearer(request: Request): string {
  const value = request.headers.get("authorization") ?? "";
  return value.startsWith("Bearer ") ? value.slice(7) : "";
}
async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", enc.encode(value));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}
function equal(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let n = 0;
  for (let i = 0; i < a.length; i++) n |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return n === 0;
}
function validId(value: string): boolean { return /^[a-f0-9-]{16,64}$/i.test(value); }

/** Mount at POST /v1/guardian/owner-events and GET /v1/guardian/owner-events. */
export async function handleOwnerDelivery(request: Request, env: OwnerDeliveryEnv): Promise<Response> {
  if (!env.GUARDIAN_DELIVERY_ENC_KEY || !env.GUARDIAN_DELIVERY_HMAC_KEY)
    return json({ error: "delivery_not_configured" }, 503);

  if (request.method === "POST") {
    const timestamp = request.headers.get("x-sene-timestamp") ?? "";
    const eventId = request.headers.get("x-sene-event-id") ?? "";
    const signature = request.headers.get("x-sene-signature") ?? "";
    const seconds = Number(timestamp);
    if (!/^\d{10}$/.test(timestamp) || !Number.isFinite(seconds) ||
        Math.abs(Date.now() / 1000 - seconds) > 300 ||
        !/^[0-9a-f-]{36}$/i.test(eventId) || !signature)
      return json({ error: "invalid_delivery_headers" }, 400);

    let body: any;
    try {
      const raw = await request.text();
      if (raw.length > 48_000) return json({ error: "payload_too_large" }, 413);
      body = JSON.parse(raw);
    } catch { return json({ error: "invalid_json" }, 400); }
    if (body?.event_id !== eventId || typeof body.iv !== "string" ||
        typeof body.ciphertext !== "string") return json({ error: "invalid_envelope" }, 400);

    const canonical = [timestamp, eventId, body.iv, body.ciphertext].join(".");
    let verified = false;
    try {
      verified = await crypto.subtle.verify(
        "HMAC", await hmacKey(env.GUARDIAN_DELIVERY_HMAC_KEY),
        fromB64(signature), enc.encode(canonical)
      );
    } catch { return json({ error: "invalid_signature" }, 401); }
    if (!verified) return json({ error: "invalid_signature" }, 401);

    const key = "owner-event:v1:" + eventId;
    if (await env.GUARDIAN_DATA.get(key)) return json({ accepted: true, duplicate: true });
    // Ciphertext only: code never enters KV in plaintext.
    await env.GUARDIAN_DATA.put(key, JSON.stringify({
      eventId, timestamp, iv: body.iv, ciphertext: body.ciphertext
    }), { expirationTtl: 60 * 60 * 24 });
    return json({ accepted: true }, 202);
  }

  if (request.method === "GET") {
    const installationId = new URL(request.url).searchParams.get("installationId") ?? "";
    const token = bearer(request);
    if (!validId(installationId) || token.length < 40) return json({ error: "unauthorized" }, 401);
    const installation = await env.GUARDIAN_DATA.get<any>("installation:v1:" + installationId, "json");
    if (!installation || installation.revoked ||
        !equal(await sha256(token), installation.museTokenHash))
      return json({ error: "unauthorized" }, 401);

    const list = await env.GUARDIAN_DATA.list({ prefix: "owner-event:v1:", limit: 50 });
    const events: unknown[] = [];
    for (const item of list.keys) {
      const stored = await env.GUARDIAN_DATA.get<any>(item.name, "json");
      if (!stored) continue;
      try {
        const plaintext = await crypto.subtle.decrypt(
          { name: "AES-GCM", iv: fromB64(stored.iv), additionalData: enc.encode(stored.eventId), tagLength: 128 },
          await aesKey(env.GUARDIAN_DELIVERY_ENC_KEY), fromB64(stored.ciphertext)
        );
        const event = JSON.parse(dec.decode(plaintext));
        if (event.installation_id === installationId) events.push(event);
      } catch {
        // Invalid or unrelated ciphertext is never returned.
      }
    }
    return json({ events });
  }
  return json({ error: "method_not_allowed" }, 405);
}
