import { strict as assert } from "node:assert";
import { test } from "node:test";
import worker from "../src/index";
import { isActiveLicense, isSyncedLicenseEvent, isTransferEvent, deviceTransfersFromEvents, ownerEventRetention } from "../src/owner-delivery";

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

// --- License gate: license.synced + license:v1 record (design section 2) ---

const centralToken = "central-owner-test-token-with-more-than-forty-characters";
const installationId = "abcdef12-3456-7890-abcd-ef1234567890";
const deliveryEnv = async () => {
  const kv = new MemoryKV();
  const encryptionBytes = crypto.getRandomValues(new Uint8Array(32));
  const hmacBytes = crypto.getRandomValues(new Uint8Array(32));
  const encryptionKey = await crypto.subtle.importKey("raw", encryptionBytes as unknown as BufferSource, "AES-GCM", false, ["encrypt"]);
  const env = {
    GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: b64url(encryptionBytes),
    GUARDIAN_DELIVERY_HMAC_KEY: b64url(hmacBytes),
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await sha256(centralToken)
  } as any;
  return { kv, env, encryptionKey, hmacBytes };
};
// Deliver one owner event exactly as the SENE VENTAS producer would
// (AES-GCM + HMAC envelope, POST /v1/guardian/owner-events).
async function deliverEvent(ctx: Awaited<ReturnType<typeof deliveryEnv>>, event: any) {
  const eventId = event.event_id;
  const timestamp = String(Math.floor(Date.now() / 1000));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv as unknown as BufferSource, additionalData: new TextEncoder().encode(eventId) as unknown as BufferSource, tagLength: 128 },
    ctx.encryptionKey, new TextEncoder().encode(JSON.stringify(event)) as unknown as BufferSource));
  const hmacKey = await crypto.subtle.importKey("raw", ctx.hmacBytes as unknown as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const canonical = [timestamp, eventId, b64url(iv), b64url(ciphertext)].join(".");
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", hmacKey, new TextEncoder().encode(canonical) as unknown as BufferSource));
  return worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events", {
    method: "POST",
    headers: { "content-type": "application/json", "x-sene-timestamp": timestamp, "x-sene-event-id": eventId, "x-sene-signature": b64url(signature) },
    body: JSON.stringify({ event_id: eventId, iv: b64url(iv), ciphertext: b64url(ciphertext) }),
  }), ctx.env);
}
const licenseEventBase = (overrides: Record<string, unknown> = {}) => ({
  installation_id: installationId,
  request_type: "activation", created_at: new Date().toISOString(),
  amount_cents: 29900, currency: "MXN",
  business_name: "Negocio de prueba", holder_name: "Titular de prueba",
  whatsapp: "3312345678",
  valid_from: new Date().toISOString(),
  valid_until: new Date(Date.now() + 30 * 86400000).toISOString(),
  ...overrides,
});
const readLicenseRecord = async (kv: any) =>
  kv.get("license:v1:" + installationId, "json") as Promise<Record<string, unknown> | null>;

test("license.synced without code creates the license record and counts as active", async () => {
  const ctx = await deliveryEnv();
  const synced = {
    ...licenseEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174100", event_type: "license.synced",
    folio: "SENE-20261126-ABC124", payment_status: "paid",
  };
  // No `code` field on purpose: the plain code cannot be recovered from D1.
  assert.equal("code" in synced, false);
  const response = await deliverEvent(ctx, synced);
  assert.equal(response.status, 202);
  assert.deepEqual(await response.json(), { accepted: true });
  const record = await readLicenseRecord(ctx.kv);
  assert.equal(record?.installationId, installationId);
  assert.equal(record?.folio, "SENE-20261126-ABC124");
  assert.equal(record?.requestType, "activation");
  assert.equal(record?.validFrom, synced.valid_from);
  assert.equal(record?.validUntil, synced.valid_until);
  assert.equal(typeof record?.updatedAt, "string");
  // The owner active-licenses list also shows the synced license.
  const active = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events?kind=active", {
    headers: { authorization: "Bearer " + centralToken }
  }), ctx.env);
  const activeBody = await active.json() as { total: number; licenses: Array<Record<string, unknown>> };
  assert.equal(activeBody.total, 1);
  assert.equal(activeBody.licenses[0].folio, "SENE-20261126-ABC124");
  assert.equal("code" in activeBody.licenses[0], false);
  // Unit checks: synced counts as active, and its retention has no TTL.
  assert.equal(isSyncedLicenseEvent(synced), true);
  assert.equal(isActiveLicense(synced), true);
  assert.deepEqual(ownerEventRetention(synced), {});
});

test("pending event does NOT create a license record", async () => {
  const ctx = await deliveryEnv();
  const requested = {
    ...licenseEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174101", event_type: "activation.requested",
    folio: "SENE-20261126-ABC125", payment_status: "pending",
    expires_at: new Date(Date.now() + 3600000).toISOString(), code: "ABCDEFG234",
  };
  delete (requested as any).valid_from;
  delete (requested as any).valid_until;
  const response = await deliverEvent(ctx, requested);
  assert.equal(response.status, 202);
  assert.equal(await readLicenseRecord(ctx.kv), null);
});

test("renewal overwrites validUntil in the license record", async () => {
  const ctx = await deliveryEnv();
  const completed = {
    ...licenseEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174102", event_type: "activation.completed",
    folio: "SENE-20261126-ABC126", payment_status: "paid", code: "ABCDEFG234",
  };
  const renewal = {
    ...licenseEventBase({ request_type: "renewal" }),
    event_id: "123e4567-e89b-42d3-a456-426614174103", event_type: "renewal.completed",
    folio: "SENE-20261126-ABC127", payment_status: "paid", code: "ABCDEFG234",
    valid_from: new Date(Date.now() + 30 * 86400000).toISOString(),
    valid_until: new Date(Date.now() + 60 * 86400000).toISOString(),
  };
  assert.equal((await deliverEvent(ctx, completed)).status, 202);
  assert.equal((await readLicenseRecord(ctx.kv))?.validUntil, completed.valid_until);
  assert.equal((await deliverEvent(ctx, renewal)).status, 202);
  const record = await readLicenseRecord(ctx.kv);
  assert.equal(record?.validUntil, renewal.valid_until);
  assert.equal(record?.folio, "SENE-20261126-ABC127");
  assert.equal(record?.requestType, "renewal");
});

test("license.synced without code is valid, activation.completed without code stays invalid", async () => {
  const ctx = await deliveryEnv();
  const synced = {
    ...licenseEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174104", event_type: "license.synced",
    folio: "SENE-20261126-ABC128", payment_status: "paid",
  };
  assert.equal("code" in synced, false);
  assert.equal((await deliverEvent(ctx, synced)).status, 202);
  const completedNoCode = {
    ...licenseEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174105", event_type: "activation.completed",
    folio: "SENE-20261126-ABC129", payment_status: "paid",
  };
  assert.equal("code" in completedNoCode, false);
  const rejected = await deliverEvent(ctx, completedNoCode);
  assert.equal(rejected.status, 400);
  assert.deepEqual(await rejected.json(), { error: "invalid_event" });
  // The rejected event did not overwrite the record written by the synced one.
  assert.equal((await readLicenseRecord(ctx.kv))?.folio, "SENE-20261126-ABC128");
});

// --- Device transfer: transfer.completed (Parte B) ---

const newInstallationId = "bbbbbbbb-2222-4222-9222-222222222222";
const transferEventBase = (overrides: Record<string, unknown> = {}) => ({
  installation_id: newInstallationId,
  old_installation_id: installationId,
  folio: "SENE-20261126-ABC124",
  business_name: "Negocio de prueba",
  holder_name: "Titular de prueba",
  whatsapp: "3312345678",
  created_at: new Date().toISOString(),
  valid_until: new Date(Date.now() + 30 * 86400000).toISOString(),
  ...overrides,
});
const readLicenseRecordAt = async (kv: any, id: string) =>
  kv.get("license:v1:" + id, "json") as Promise<Record<string, unknown> | null>;

test("transfer.completed válido se acepta sin request_type, monto ni código, con valid_until null", async () => {
  const ctx = await deliveryEnv();
  const pending = {
    ...transferEventBase({ valid_until: null }),
    event_id: "123e4567-e89b-42d3-a456-426614174110", event_type: "transfer.completed",
  };
  // No request_type, no amount_cents, no currency, no code, no payment_status.
  assert.equal("request_type" in pending, false);
  assert.equal("code" in pending, false);
  const response = await deliverEvent(ctx, pending);
  assert.equal(response.status, 202);
  assert.deepEqual(await response.json(), { accepted: true });
  // A stray code field, if present, is ignored: the transfer is still valid.
  const withCode = {
    ...transferEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174111", event_type: "transfer.completed",
    code: "ABCDEFG234",
  };
  assert.equal((await deliverEvent(ctx, withCode)).status, 202);
  // Unit checks: it is a transfer event and its retention has no TTL.
  assert.equal(isTransferEvent(pending), true);
  assert.equal(isTransferEvent(withCode), true);
  assert.deepEqual(ownerEventRetention(pending), {});
  assert.deepEqual(deviceTransfersFromEvents([pending, withCode]).total, 2);
});

test("transfer.completed inválido se rechaza", async () => {
  const ctx = await deliveryEnv();
  const badFolio = {
    ...transferEventBase({ folio: "MALO" }),
    event_id: "123e4567-e89b-42d3-a456-426614174112", event_type: "transfer.completed",
  };
  const badOldId = {
    ...transferEventBase({ old_installation_id: "no-es-uuid" }),
    event_id: "123e4567-e89b-42d3-a456-426614174113", event_type: "transfer.completed",
  };
  const badValidUntil = {
    ...transferEventBase({ valid_until: "no-es-fecha" }),
    event_id: "123e4567-e89b-42d3-a456-426614174114", event_type: "transfer.completed",
  };
  const missingOldId = {
    ...transferEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174115", event_type: "transfer.completed",
  };
  delete (missingOldId as any).old_installation_id;
  for (const event of [badFolio, badOldId, badValidUntil, missingOldId]) {
    const rejected = await deliverEvent(ctx, event);
    assert.equal(rejected.status, 400);
    assert.deepEqual(await rejected.json(), { error: "invalid_event" });
  }
  assert.equal(await readLicenseRecordAt(ctx.kv, newInstallationId), null);
});

test("el traspaso mueve el registro de licencia al dispositivo nuevo y borra el viejo", async () => {
  const ctx = await deliveryEnv();
  const completed = {
    ...licenseEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174116", event_type: "activation.completed",
    folio: "SENE-20261126-ABC126", payment_status: "paid", code: "ABCDEFG234",
  };
  assert.equal((await deliverEvent(ctx, completed)).status, 202);
  assert.equal((await readLicenseRecordAt(ctx.kv, installationId))?.folio, "SENE-20261126-ABC126");
  const transfer = {
    ...transferEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174117", event_type: "transfer.completed",
    folio: "SENE-20261126-ABC126",
  };
  assert.equal((await deliverEvent(ctx, transfer)).status, 202);
  assert.equal(await readLicenseRecordAt(ctx.kv, installationId), null);
  const moved = await readLicenseRecordAt(ctx.kv, newInstallationId);
  assert.equal(moved?.installationId, newInstallationId);
  assert.equal(moved?.folio, "SENE-20261126-ABC126");
  assert.equal(moved?.requestType, "activation");
  assert.equal(moved?.validFrom, completed.valid_from);
  assert.equal(moved?.validUntil, completed.valid_until);
  assert.equal(typeof moved?.updatedAt, "string");
});

test("el traspaso sin registro viejo no crea nada", async () => {
  const ctx = await deliveryEnv();
  const transfer = {
    ...transferEventBase({ valid_until: null }),
    event_id: "123e4567-e89b-42d3-a456-426614174118", event_type: "transfer.completed",
  };
  assert.equal((await deliverEvent(ctx, transfer)).status, 202);
  assert.equal(await readLicenseRecordAt(ctx.kv, installationId), null);
  assert.equal(await readLicenseRecordAt(ctx.kv, newInstallationId), null);
});

test("el traspaso sobrescribe un registro ya existente en el dispositivo nuevo", async () => {
  const ctx = await deliveryEnv();
  const oldCompleted = {
    ...licenseEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174119", event_type: "activation.completed",
    folio: "SENE-20261126-ABC126", payment_status: "paid", code: "ABCDEFG234",
  };
  const newCompleted = {
    ...licenseEventBase({ installation_id: newInstallationId }),
    event_id: "123e4567-e89b-42d3-a456-426614174120", event_type: "activation.completed",
    folio: "SENE-20261126-ABC127", payment_status: "paid", code: "ABCDEFG234",
  };
  assert.equal((await deliverEvent(ctx, oldCompleted)).status, 202);
  assert.equal((await deliverEvent(ctx, newCompleted)).status, 202);
  assert.equal((await readLicenseRecordAt(ctx.kv, newInstallationId))?.folio, "SENE-20261126-ABC127");
  const transfer = {
    ...transferEventBase(),
    event_id: "123e4567-e89b-42d3-a456-426614174121", event_type: "transfer.completed",
    folio: "SENE-20261126-ABC126",
  };
  assert.equal((await deliverEvent(ctx, transfer)).status, 202);
  const moved = await readLicenseRecordAt(ctx.kv, newInstallationId);
  assert.equal(moved?.folio, "SENE-20261126-ABC126");
  assert.equal(moved?.validUntil, oldCompleted.valid_until);
});

test("GET ?kind=transfers devuelve los traspasos en forma segura, del más reciente al más antiguo", async () => {
  const kv = new MemoryKV();
  const centralToken2 = "central-owner-test-token-with-more-than-forty-characters";
  const encryptionBytes = crypto.getRandomValues(new Uint8Array(32));
  const encryptionKey = await crypto.subtle.importKey("raw", encryptionBytes as unknown as BufferSource, "AES-GCM", false, ["encrypt"]);
  const older = {
    ...transferEventBase({ created_at: new Date(Date.now() - 3600000).toISOString() }),
    event_id: "123e4567-e89b-42d3-a456-426614174122", event_type: "transfer.completed",
    folio: "SENE-20261126-ABC124",
  };
  const newer = {
    ...transferEventBase({ valid_until: null }),
    event_id: "123e4567-e89b-42d3-a456-426614174123", event_type: "transfer.completed",
    folio: "SENE-20261126-ABC125",
  };
  const requested = {
    installation_id: installationId, request_type: "activation", created_at: new Date().toISOString(),
    amount_cents: 29900, currency: "MXN", business_name: "Negocio de prueba",
    holder_name: "Titular de prueba", whatsapp: "3312345678", code: "ABCDEFG234",
    event_id: "123e4567-e89b-42d3-a456-426614174124", event_type: "activation.requested",
    folio: "SENE-20261126-ABC126", expires_at: new Date(Date.now() + 3600000).toISOString(), payment_status: "pending",
  };
  await seedOwnerEvent(kv, encryptionBytes, encryptionKey, older);
  await seedOwnerEvent(kv, encryptionBytes, encryptionKey, newer);
  await seedOwnerEvent(kv, encryptionBytes, encryptionKey, requested);
  const env = {
    GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: b64url(encryptionBytes),
    GUARDIAN_DELIVERY_HMAC_KEY: b64url(crypto.getRandomValues(new Uint8Array(32))),
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await sha256(centralToken2)
  } as any;
  const headers = { authorization: "Bearer " + centralToken2 };

  const transfersResponse = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events?kind=transfers", { headers }), env);
  const transfersBody = await transfersResponse.json() as { total: number; transfers: Array<Record<string, unknown>> };
  assert.equal(transfersBody.total, 2);
  assert.equal(transfersBody.transfers.length, 2);
  // Newest first.
  assert.equal(transfersBody.transfers[0].folio, "SENE-20261126-ABC125");
  assert.equal(transfersBody.transfers[1].folio, "SENE-20261126-ABC124");
  for (const listed of transfersBody.transfers) {
    assert.deepEqual(Object.keys(listed).sort(), ["businessName", "folio", "installationId", "oldInstallationId", "transferredAt", "validUntil"]);
    assert.equal(listed.businessName, "Negocio de prueba");
    assert.equal(listed.oldInstallationId, installationId);
    assert.equal(listed.installationId, newInstallationId);
  }
  assert.equal(transfersBody.transfers[0].validUntil, null);
  assert.equal(transfersBody.transfers[0].transferredAt, newer.created_at);
  const whole = JSON.stringify(transfersBody);
  assert.equal(whole.includes("3312345678"), false);
  assert.equal(whole.includes("Titular de prueba"), false);
  assert.equal(whole.includes("ABCDEFG234"), false);

  // Transfers are not mixed into pending requests or active licenses.
  const pending = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events", { headers }), env);
  const pendingBody = await pending.json() as { events: Array<{ event_id: string }> };
  assert.equal(pendingBody.events.length, 1);
  assert.equal(pendingBody.events[0].event_id, requested.event_id);
  const active = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events?kind=active", { headers }), env);
  const activeBody = await active.json() as { total: number };
  assert.equal(activeBody.total, 0);
});
