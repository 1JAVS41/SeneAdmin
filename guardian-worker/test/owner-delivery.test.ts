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
