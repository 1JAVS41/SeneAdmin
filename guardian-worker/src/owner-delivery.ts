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
function validDate(value: unknown): value is string {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}
/** A license that is actually active, as reported by the SENE VENTAS producer.
 * It is built only from a completed activation/renewal event; it is never
 * invented from a pending request. The activation code is deliberately NOT
 * included here: the owner already has it from the request, and the active
 * list only needs folio, business, type and validity dates. */
export type ActiveLicense = {
  folio: string;
  installationId: string;
  businessName: string;
  requestType: "activation" | "renewal";
  validFrom: string;
  validUntil: string;
  status: "active";
};
export function isCompletedLicenseEvent(value: any): boolean {
  return !!value && ["activation.completed", "renewal.completed"].includes(value.event_type);
}
export function isSyncedLicenseEvent(value: any): boolean {
  return !!value && value.event_type === "license.synced";
}
/** A completed activation/renewal, or a one-time sync of an already-active
 * license (license.synced, design section 3): both count as active. The
 * synced event carries no activation code — the plain code of an
 * already-active license cannot be recovered from D1 (only code_hash
 * remains) — so it is valid without a `code` field. */
export function isActiveLicenseEvent(value: any): boolean {
  return isCompletedLicenseEvent(value) || isSyncedLicenseEvent(value);
}
export function isActiveLicense(value: any, now = Date.now()): boolean {
  return isActiveLicenseEvent(value) &&
    validDate(value.valid_from) && validDate(value.valid_until) &&
    Date.parse(value.valid_until) >= now;
}

/** Per-installation license record used by the client-Muse license gate
 * (design section 2): one KV read per call decides active vs expired. It
 * is written when a completed or synced license event is stored, never for
 * a pending request, and it has no expirationTtl: license history is never
 * deleted; expiry is decided by comparing validUntil with now. */
export type LicenseRecord = {
  installationId: string;
  folio: string;
  requestType: "activation" | "renewal";
  validFrom: string;
  validUntil: string;
  updatedAt: string;
};
export const LICENSE_RECORD_PREFIX = "license:v1:";
export function licenseRecordKey(installationId: string): string {
  return LICENSE_RECORD_PREFIX + installationId;
}
export function isLicenseRecordActive(record: unknown, now = Date.now()): boolean {
  if (!record || typeof record !== "object") return false;
  const r = record as Partial<LicenseRecord>;
  return typeof r.installationId === "string" && typeof r.folio === "string" &&
    (r.requestType === "activation" || r.requestType === "renewal") &&
    validDate(r.validFrom) && validDate(r.validUntil) &&
    Date.parse(r.validUntil as string) >= now;
}
export function licenseRecordFromEvent(event: any): LicenseRecord {
  return {
    installationId: event.installation_id as string,
    folio: event.folio as string,
    requestType: event.request_type as "activation" | "renewal",
    validFrom: event.valid_from as string,
    validUntil: event.valid_until as string,
    updatedAt: new Date().toISOString(),
  };
}
/** Keep only active licenses, in a safe public shape (no code, no whatsapp,
 * no holder name, no amount). Sorted by expiry, soonest first. */
export function activeLicensesFromEvents(events: unknown[], now = Date.now()): { total: number; licenses: ActiveLicense[] } {
  const licenses = (events as any[])
    .filter((event) => isActiveLicense(event, now))
    .map((event) => ({
      folio: event.folio as string,
      installationId: event.installation_id as string,
      businessName: event.business_name as string,
      requestType: event.request_type as "activation" | "renewal",
      validFrom: event.valid_from as string,
      validUntil: event.valid_until as string,
      status: "active" as const,
    }))
    .sort((a, b) => Date.parse(a.validUntil) - Date.parse(b.validUntil) || a.folio.localeCompare(b.folio));
  return { total: licenses.length, licenses };
}

// Retención del historial de licencias (regla del usuario, 2026-11-27): el
// historial de licencias se conserva SIEMPRE y nunca se borra por la
// limpieza, por si un cliente regresa a SENE-VENTAS. Por eso un evento
// completado se guarda SIN fecha de caducidad en KV (sin expirationTtl):
// sigue guardado aunque la licencia ya haya vencido. La caducidad de la
// licencia no cambia: valid_until se conserva intacto y solo
// activeLicensesFromEvents decide qué sigue activo según esa fecha.
// Una solicitud pendiente sí se guarda solo un día, como antes.
export function ownerEventRetention(event: any): { expirationTtl?: number } {
  return isActiveLicenseEvent(event) ? {} : { expirationTtl: 86400 };
}
function validEvent(value: any, eventId: string): boolean {
  if (!value || value.event_id !== eventId ||
      !validInstallationId(value.installation_id) ||
      typeof value.folio !== "string" || !/^SENE-[A-Z0-9-]{8,40}$/.test(value.folio) ||
      !["activation", "renewal"].includes(value.request_type) ||
      !validDate(value.created_at) ||
      !Number.isSafeInteger(value.amount_cents) || value.amount_cents <= 0 ||
      value.currency !== "MXN" ||
      typeof value.business_name !== "string" || value.business_name.length < 2 || value.business_name.length > 80 ||
      typeof value.holder_name !== "string" || value.holder_name.length < 2 || value.holder_name.length > 100 ||
      typeof value.whatsapp !== "string" || !/^\d{10,15}$/.test(value.whatsapp)) return false;
  // The activation code is required for requested and completed events, but
  // never for a synced event: the plain code of an already-active license
  // cannot be recovered from D1 (only code_hash remains).
  const hasCode = typeof value.code === "string" && /^[A-HJ-NP-Z2-9]{10}$/.test(value.code);
  if (["activation.requested", "renewal.requested"].includes(value.event_type)) {
    return hasCode && value.payment_status === "pending" && validDate(value.expires_at);
  }
  if (isCompletedLicenseEvent(value)) {
    // A completed activation/renewal: paid, with a validity window. It has
    // no request expiry; valid_until must be after valid_from.
    return hasCode && value.payment_status === "paid" &&
      validDate(value.valid_from) && validDate(value.valid_until) &&
      Date.parse(value.valid_until) > Date.parse(value.valid_from);
  }
  if (isSyncedLicenseEvent(value)) {
    // A one-time sync of an already-active license (design section 3):
    // paid, with a validity window, no code.
    return value.payment_status === "paid" &&
      validDate(value.valid_from) && validDate(value.valid_until) &&
      Date.parse(value.valid_until) > Date.parse(value.valid_from);
  }
  return false;
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
    // Historial de licencias permanente: un evento completado se guarda sin
    // expirationTtl (nunca se borra por limpieza); una solicitud pendiente se
    // guarda un día, como antes. Ver ownerEventRetention.
    await env.GUARDIAN_DATA.put(key, JSON.stringify({
      eventId, installationId: event.installation_id, timestamp, iv: body.iv, ciphertext: body.ciphertext
    }), ownerEventRetention(event));
    // License gate record (design section 2): a completed or synced event
    // writes license:v1:{installation_id} so the client Muse gate is one KV
    // read per call. A renewal overwrites validUntil with the new date. A
    // pending request never touches the record. No expirationTtl: license
    // history is kept; expiry is decided by comparing validUntil with now.
    if (isActiveLicenseEvent(event)) {
      await env.GUARDIAN_DATA.put(licenseRecordKey(event.installation_id), JSON.stringify(licenseRecordFromEvent(event)));
    }
    return json({ accepted: true }, 202);
  }
  if (request.method === "GET") {
    // This endpoint is called only after index.ts validates the central owner
    // credential. It deliberately does not accept installationId as a filter:
    // all license events belong to the central owner channel.
    // ?kind=active returns only active licenses (total + safe list, no code);
    // the default returns only pending requests, never completed events.
    const kind = new URL(request.url).searchParams.get("kind");
    const all: unknown[] = [];
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
          if (validEvent(event, stored.eventId) && event.installation_id === stored.installationId) all.push(event);
        } catch { /* malformed ciphertext is never returned */ }
      }
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
    if (kind === "active") return json(activeLicensesFromEvents(all));
    const events = all.filter((event: any) => ["activation.requested", "renewal.requested"].includes(event.event_type));
    return json({ events });
  }
  return json({ error: "method_not_allowed" }, 405);
}
