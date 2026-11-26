/**
 * Encrypted owner-event transport for SERVER-SENE request codes.
 * KV stores ciphertext only. Plaintext is returned only through the centrally
 * authenticated owner route in index.ts, never through an installation/Muse token.
 */
export interface OwnerDeliveryEnv {
  GUARDIAN_DATA: KVNamespace;
  GUARDIAN_DELIVERY_ENC_KEY: string;
  GUARDIAN_DELIVERY_HMAC_KEY: string;
}
const enc = new TextEncoder();
const dec = new TextDecoder();
function fromB64(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("invalid_base64");
  const raw = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}
async function hmacKey(value: string): Promise<CryptoKey> {
  const raw = fromB64(value);
  if (raw.byteLength < 32) throw new Error("invalid_hmac_key");
  return crypto.subtle.importKey("raw", raw as unknown as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
}
async function aesKey(value: string): Promise<CryptoKey> {
  const raw = fromB64(value);
  if (raw.byteLength !== 32) throw new Error("invalid_aes_key");
  return crypto.subtle.importKey("raw", raw as unknown as BufferSource, "AES-GCM", false, ["decrypt"]);
}
function validUuid(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
function validInstallationId(value: unknown): value is string {
  return typeof value === "string" && /^[a-f0-9-]{20,100}$/i.test(value);
}
function validEvent(value: any, eventId: string): boolean {
  return !!value && value.event_id === eventId &&
    ["activation.requested", "renewal.requested"].includes(value.event_type) &&
    validInstallationId(value.installation_id) &&
    typeof value.folio === "string" && /^SENE-[A-Z0-9-]{8,40}$/.test(value.folio) &&
    ["activation", "renewal"].includes(value.request_type) &&
    typeof value.created_at === "string" && Number.isFinite(Date.parse(value.created_at)) &&
    typeof value.expires_at === "string" && Number.isFinite(Date.parse(value.expires_at)) &&
    value.payment_status === "pending" &&
    Number.isSafeInteger(value.amount_cents) && value.amount_cents > 0 &&
    value.currency === "MXN" &&
    typeof value.business_name === "string" && value.business_name.length >= 2 && value.business_name.length <= 80 &&
    typeof value.holder_name === "string" && value.holder_name.length >= 2 && value.holder_name.length <= 100 &&
    typeof value.whatsapp === "string" && /^\d{10,15}$/.test(value.whatsapp) &&
    typeof value.code === "string" && /^[A-HJ-NP-Z2-9]{10}$/.test(value.code);
}
export async function handleOwnerDelivery(request: Request, env: OwnerDeliveryEnv): Promise<Response> {
  if (!env.GUARDIAN_DELIVERY_ENC_KEY || !env.GUARDIAN_DELIVERY_HMAC_KEY)
    return json({ error: "delivery_not_configured" }, 503);
  if (request.method === "POST") {
    const timestamp = request.headers.get("x-sene-timestamp") ?? "";
    const eventId = request.headers.get("x-sene-event-id") ?? "";
    const signature = request.headers.get("x-sene-signature") ?? "";
    const seconds = Number(timestamp);
    if (!/^\d{10}$/.test(timestamp) || !Number.isFinite(seconds) ||
        Math.abs(Date.now() / 1000 - seconds) > 300 || !validUuid(eventId) || !signature)
      return json({ error: "invalid_delivery_headers" }, 400);
    let body: any;
    try {
      const raw = await request.text();
      if (raw.length > 48_000) return json({ error: "payload_too_large" }, 413);
      body = JSON.parse(raw);
    } catch { return json({ error: "invalid_json" }, 400); }
    if (body?.event_id !== eventId || typeof body.iv !== "string" ||
        typeof body.ciphertext !== "string" || body.iv.length > 32 || body.ciphertext.length > 44000)
      return json({ error: "invalid_envelope" }, 400);
    const canonical = [timestamp, eventId, body.iv, body.ciphertext].join(".");
    let plaintext: ArrayBuffer;
    try {
      const verified = await crypto.subtle.verify("HMAC", await hmacKey(env.GUARDIAN_DELIVERY_HMAC_KEY),
        fromB64(signature) as unknown as BufferSource, enc.encode(canonical) as unknown as BufferSource);
      if (!verified) return json({ error: "invalid_signature" }, 401);
      plaintext = await crypto.subtle.decrypt(
        { name: "AES-GCM", iv: fromB64(body.iv) as unknown as BufferSource, additionalData: enc.encode(eventId) as unknown as BufferSource, tagLength: 128 },
        await aesKey(env.GUARDIAN_DELIVERY_ENC_KEY), fromB64(body.ciphertext) as unknown as BufferSource);
    } catch { return json({ error: "invalid_envelope" }, 400); }
    let event: any;
    try { event = JSON.parse(dec.decode(plaintext)); } catch { return json({ error: "invalid_event" }, 400); }
    if (!validEvent(event, eventId)) return json({ error: "invalid_event" }, 400);
    const key = "owner-event:v1:" + eventId;
    if (await env.GUARDIAN_DATA.get(key)) return json({ accepted: true, duplicate: true });
    await env.GUARDIAN_DATA.put(key, JSON.stringify({
      eventId, installationId: event.installation_id, timestamp, iv: body.iv, ciphertext: body.ciphertext
    }), { expirationTtl: 86400 });
    return json({ accepted: true }, 202);
  }
  if (request.method === "GET") {
    // This endpoint is called only after index.ts validates the central owner
    // credential. It deliberately does not accept installationId as a filter:
    // all license events belong to the central owner channel.
    const events: unknown[] = [];
    let cursor: string | undefined;
    do {
      const page = await env.GUARDIAN_DATA.list({ prefix: "owner-event:v1:", limit: 100, cursor });
      for (const item of page.keys) {
        const stored = await env.GUARDIAN_DATA.get<any>(item.name, "json");
        if (!stored || typeof stored.installationId !== "string") continue;
        try {
          const plaintext = await crypto.subtle.decrypt(
            { name: "AES-GCM", iv: fromB64(stored.iv) as unknown as BufferSource, additionalData: enc.encode(stored.eventId) as unknown as BufferSource, tagLength: 128 },
            await aesKey(env.GUARDIAN_DELIVERY_ENC_KEY), fromB64(stored.ciphertext) as unknown as BufferSource);
          const event = JSON.parse(dec.decode(plaintext));
          if (validEvent(event, stored.eventId) && event.installation_id === stored.installationId) events.push(event);
        } catch { /* malformed ciphertext is never returned */ }
      }
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
    return json({ events });
  }
  return json({ error: "method_not_allowed" }, 405);
}
