import { strict as assert } from "node:assert";
import { test } from "node:test";
import worker from "../src/index";

class MemoryKV {
  values = new Map<string, string>();
  async get(key: string, type?: string) {
    const value = this.values.get(key);
    if (value === undefined) return null;
    return type === "json" ? JSON.parse(value) : value;
  }
  async put(key: string, value: string) { this.values.set(key, value); }
  async delete(key: string) { this.values.delete(key); }
  async list(options: { prefix?: string; limit?: number; cursor?: string } = {}) {
    const names = [...this.values.keys()].filter(k => k.startsWith(options.prefix ?? ""));
    return { keys: names.map(name => ({ name })), list_complete: true, cursor: "" };
  }
}
const b64url = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64url");
const sha256 = async (value: string) => Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))).toString("hex");
// Encrypt one owner event exactly as the SENE VENTAS producer would, and store
// only its ciphertext in KV, as the POST endpoint would.
async function seedOwnerEvent(kv: any, encryptionBytes: Uint8Array, encryptionKey: CryptoKey, event: any) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv as unknown as BufferSource, additionalData: new TextEncoder().encode(event.event_id) as unknown as BufferSource, tagLength: 128 },
    encryptionKey, new TextEncoder().encode(JSON.stringify(event)) as unknown as BufferSource));
  await kv.put("owner-event:v1:" + event.event_id, JSON.stringify({
    eventId: event.event_id, installationId: event.installation_id, timestamp: String(Math.floor(Date.now() / 1000)),
    iv: b64url(iv), ciphertext: b64url(ciphertext)
  }));
}

test("owner delivery rejects requests when bridge secrets are absent", async () => {
  const env = { GUARDIAN_DATA: new MemoryKV(), GUARDIAN_ADMIN_TOKEN: "x", GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "", GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: "" } as any;
  const response = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events", { method: "POST" }), env);
  assert.equal(response.status, 503);
});

test("central owner credential reads events across installations without installation filtering", async () => {
  const kv = new MemoryKV();
  const centralToken = "central-owner-test-token-with-more-than-forty-characters";
  const encryptionBytes = crypto.getRandomValues(new Uint8Array(32));
  const encryptionKey = await crypto.subtle.importKey("raw", encryptionBytes as unknown as BufferSource, "AES-GCM", false, ["encrypt"]);
  const eventId = "123e4567-e89b-42d3-a456-426614174000";
  const event = {
    event_id: eventId, event_type: "activation.requested",
    installation_id: "abcdef12-3456-7890-abcd-ef1234567890", folio: "SENE-20261126-ABC123",
    request_type: "activation", created_at: new Date().toISOString(),
    expires_at: new Date(Date.now() + 3600000).toISOString(),
    payment_status: "pending", amount_cents: 29900, currency: "MXN",
    business_name: "Negocio de prueba", holder_name: "Titular de prueba",
    whatsapp: "3312345678", code: "ABCDEFG234"
  };
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv as unknown as BufferSource, additionalData: new TextEncoder().encode(eventId) as unknown as BufferSource, tagLength: 128 },
    encryptionKey, new TextEncoder().encode(JSON.stringify(event)) as unknown as BufferSource));
  await kv.put("owner-event:v1:" + eventId, JSON.stringify({
    eventId, installationId: event.installation_id, timestamp: String(Math.floor(Date.now() / 1000)),
    iv: b64url(iv), ciphertext: b64url(ciphertext)
  }));
  const env = {
    GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: b64url(encryptionBytes),
    GUARDIAN_DELIVERY_HMAC_KEY: b64url(crypto.getRandomValues(new Uint8Array(32))),
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await sha256(centralToken)
  } as any;

  const denied = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events"), env);
  assert.equal(denied.status, 401);

  const response = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events?installationId=some-other-installation", {
    headers: { authorization: "Bearer " + centralToken }
  }), env);
  assert.equal(response.status, 200);
  const body = await response.json() as { events: Array<{ event_id: string; installation_id: string; code: string }> };
  assert.equal(body.events.length, 1);
  assert.equal(body.events[0].event_id, eventId);
  assert.equal(body.events[0].installation_id, event.installation_id);
  assert.equal(body.events[0].code, event.code);
});

test("completed license events are active licenses, without code, and are not mixed into pending requests", async () => {
  const kv = new MemoryKV();
  const centralToken = "central-owner-test-token-with-more-than-forty-characters";
  const encryptionBytes = crypto.getRandomValues(new Uint8Array(32));
  const encryptionKey = await crypto.subtle.importKey("raw", encryptionBytes as unknown as BufferSource, "AES-GCM", false, ["encrypt"]);
  const base = {
    installation_id: "abcdef12-3456-7890-abcd-ef1234567890",
    request_type: "activation", created_at: new Date().toISOString(),
    amount_cents: 29900, currency: "MXN",
    business_name: "Negocio de prueba", holder_name: "Titular de prueba",
    whatsapp: "3312345678", code: "ABCDEFG234"
  };
  const requested = { ...base, event_id: "123e4567-e89b-42d3-a456-426614174001", event_type: "activation.requested",
    folio: "SENE-20261126-ABC123", expires_at: new Date(Date.now() + 3600000).toISOString(), payment_status: "pending" };
  const active = { ...base, event_id: "123e4567-e89b-42d3-a456-426614174002", event_type: "activation.completed",
    folio: "SENE-20261126-ABC124", payment_status: "paid",
    valid_from: new Date().toISOString(), valid_until: new Date(Date.now() + 30 * 86400000).toISOString() };
  const expired = { ...base, event_id: "123e4567-e89b-42d3-a456-426614174003", event_type: "renewal.completed",
    folio: "SENE-20261126-ABC125", request_type: "renewal", payment_status: "paid",
    valid_from: new Date(Date.now() - 60 * 86400000).toISOString(), valid_until: new Date(Date.now() - 86400000).toISOString() };
  await seedOwnerEvent(kv, encryptionBytes, encryptionKey, requested);
  await seedOwnerEvent(kv, encryptionBytes, encryptionKey, active);
  await seedOwnerEvent(kv, encryptionBytes, encryptionKey, expired);
  const env = {
    GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: b64url(encryptionBytes),
    GUARDIAN_DELIVERY_HMAC_KEY: b64url(crypto.getRandomValues(new Uint8Array(32))),
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await sha256(centralToken)
  } as any;
  const headers = { authorization: "Bearer " + centralToken };

  const pending = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events", { headers }), env);
  const pendingBody = await pending.json() as { events: Array<{ event_id: string }> };
  assert.equal(pendingBody.events.length, 1);
  assert.equal(pendingBody.events[0].event_id, requested.event_id);

  const activeResponse = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events?kind=active", { headers }), env);
  const activeBody = await activeResponse.json() as { total: number; licenses: Array<Record<string, unknown>> };
  assert.equal(activeBody.total, 1);
  assert.equal(activeBody.licenses.length, 1);
  assert.equal(activeBody.licenses[0].folio, "SENE-20261126-ABC124");
  assert.equal(activeBody.licenses[0].status, "active");
  assert.equal(activeBody.licenses[0].businessName, "Negocio de prueba");
  assert.equal("code" in activeBody.licenses[0], false);
  assert.equal("whatsapp" in activeBody.licenses[0], false);
});
