import { strict as assert } from "node:assert";
import { test } from "node:test";
import worker, { prunePeriods, RETENTION_PERIODS_PER_CATEGORY, RETENTION_SNAPSHOT_TTL_SECONDS } from "../src/index";
import { activeLicensesFromEvents, ownerEventRetention } from "../src/owner-delivery";

// Retención y limpieza cada 30 días (regla del usuario, 2026-11-27):
// por negocio se conservan solo los últimos 10 periodos de cada categoría
// agregada del snapshot; el historial de licencias no se borra nunca; la
// caducidad de cada licencia no cambia. La poda se hace al guardar, sin
// solicitudes extra al servidor.

class MemoryKV {
  values = new Map<string, string>();
  putOptions = new Map<string, any>();
  async get(key: string, type?: string) {
    const value = this.values.get(key);
    if (value === undefined) return null;
    return type === "json" ? JSON.parse(value) : value;
  }
  async put(key: string, value: string, options?: any) {
    this.values.set(key, value);
    this.putOptions.set(key, options ?? {});
  }
  async delete(key: string) { this.values.delete(key); }
  async list(options: { prefix?: string; limit?: number; cursor?: string } = {}) {
    const names = [...this.values.keys()].filter(k => k.startsWith(options.prefix ?? ""));
    return { keys: names.map(name => ({ name })), list_complete: true, cursor: "" };
  }
}
const b64url = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64url");
const sha256 = async (value: string) => Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))).toString("hex");
const id = "abcdef12-3456-7890-abcd-ef1234567890";
const ingestToken = "ingest-token-for-retention-test-longer-than-forty-characters";
const month = (m: number) => `2025-${String(m).padStart(2, "0")}`;

test("prunePeriods keeps only the last 10 periods, in chronological order", () => {
  assert.equal(RETENTION_PERIODS_PER_CATEGORY, 10);
  const periods = Array.from({ length: 12 }, (_, i) => ({ period: month(12 - i), value: i }));
  const kept = prunePeriods(periods);
  assert.equal(kept.length, 10);
  assert.deepEqual(kept.map(p => p.period), Array.from({ length: 10 }, (_, i) => month(i + 3)));
  // Con 10 o menos periodos no se recorta nada.
  const short = [{ period: "2025-01" }, { period: "2025-02" }];
  assert.deepEqual(prunePeriods(short), short);
});

test("snapshot ingest prunes each category to the last 10 periods and keeps the workers roster whole", async () => {
  const kv = new MemoryKV();
  await kv.put("installation:v1:" + id, JSON.stringify({
    schema: 1, revoked: false, ingestTokenHash: await sha256(ingestToken),
    museTokenHash: await sha256("unused-muse-token-longer-than-forty-characters"), createdAt: new Date().toISOString()
  }));
  const env = {
    GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "", GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: ""
  } as any;
  const snapshot = {
    schema: 1, installationId: id, capturedAt: new Date().toISOString(),
    business: { status: "active" },
    inventory: { categories: [{ category: "INVENTARIO_MAESTRO", itemCount: 12, unitsInStock: 30, lowStockCount: 2, outOfStockCount: 1, updatedAt: new Date().toISOString() }] },
    approvedMovements: { periods: Array.from({ length: 12 }, (_, i) => ({
      period: month(i + 1), cashDisbursementCount: 1, inventoryConsumptionCount: 2, inventoryConsumptionUnits: 5 })) },
    payroll: { periods: Array.from({ length: 12 }, (_, i) => ({
      period: `${month(i + 1)}-05`, employeeCount: 2, payrollCount: 2, grossCents: 1000,
      absenceCents: 0, earlyExitCents: 0, loanCents: 0, consumptionCents: 0, netCents: 1000, deliveredCents: 1000 })) },
    sales: { periods: Array.from({ length: 12 }, (_, i) => ({
      period: month(i + 1), workerCount: 1, reportCount: 1, salesNetCents: 50000, providerPaymentsCents: 1000, loansDisbursedCents: 0 })) },
    // La plantilla actual no es historial: 12 trabajadores se guardan los 12.
    workers: { employees: Array.from({ length: 12 }, (_, i) => ({ name: `Trabajador ${String(i + 1).padStart(2, "0")}`, salaryCents: 100000 + i })) }
  };
  const response = await worker.fetch(new Request("https://guardian.test/v1/snapshot", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + ingestToken },
    body: JSON.stringify(snapshot)
  }), env);
  assert.equal(response.status, 200);
  assert.equal((await response.json() as any).accepted, true);

  const stored = await kv.get("snapshot:v1:" + id, "json") as any;
  assert.equal(stored.approvedMovements.periods.length, 10);
  assert.deepEqual(stored.approvedMovements.periods.map((p: any) => p.period), Array.from({ length: 10 }, (_, i) => month(i + 3)));
  assert.equal(stored.payroll.periods.length, 10);
  assert.deepEqual(stored.payroll.periods.map((p: any) => p.period), Array.from({ length: 10 }, (_, i) => `${month(i + 3)}-05`));
  assert.equal(stored.sales.periods.length, 10);
  assert.deepEqual(stored.sales.periods.map((p: any) => p.period), Array.from({ length: 10 }, (_, i) => month(i + 3)));
  assert.equal(stored.workers.employees.length, 12);
});

test("snapshot is stored with a 30-day TTL (cleanup every 30 days)", async () => {
  assert.equal(RETENTION_SNAPSHOT_TTL_SECONDS, 30 * 24 * 60 * 60);
  const kv = new MemoryKV();
  await kv.put("installation:v1:" + id, JSON.stringify({
    schema: 1, revoked: false, ingestTokenHash: await sha256(ingestToken),
    museTokenHash: await sha256("unused-muse-token-longer-than-forty-characters"), createdAt: new Date().toISOString()
  }));
  const env = {
    GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "", GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: ""
  } as any;
  const snapshot = {
    schema: 1, installationId: id, capturedAt: new Date().toISOString(),
    business: { status: "active" },
    inventory: { categories: [{ category: "INVENTARIO_MAESTRO", itemCount: 1, unitsInStock: 1, lowStockCount: null, outOfStockCount: 0, updatedAt: new Date().toISOString() }] }
  };
  const response = await worker.fetch(new Request("https://guardian.test/v1/snapshot", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + ingestToken },
    body: JSON.stringify(snapshot)
  }), env);
  assert.equal(response.status, 200);
  assert.equal(kv.putOptions.get("snapshot:v1:" + id)?.expirationTtl, 30 * 24 * 60 * 60);
});

// --- Historial de licencias: nunca se borra, la caducidad no cambia ---

test("completed license events are retained forever, pending requests only one day", () => {
  const completed = { event_type: "activation.completed", valid_from: new Date().toISOString(), valid_until: new Date(Date.now() + 30 * 86400000).toISOString() };
  assert.deepEqual(ownerEventRetention(completed), {});
  assert.equal("expirationTtl" in ownerEventRetention(completed), false);
  const pending = { event_type: "activation.requested" };
  assert.deepEqual(ownerEventRetention(pending), { expirationTtl: 86400 });
  // Una licencia ya vencida también se conserva: el historial nunca se borra.
  const expiredCompleted = { event_type: "renewal.completed", valid_from: new Date(Date.now() - 60 * 86400000).toISOString(), valid_until: new Date(Date.now() - 86400000).toISOString() };
  assert.equal("expirationTtl" in ownerEventRetention(expiredCompleted), false);
});

test("retention does not change license expiry: expired licenses stay stored but are not listed as active", () => {
  const now = Date.now();
  const active = {
    event_type: "activation.completed", folio: "SENE-20261126-ABC124", installation_id: id,
    business_name: "Negocio de prueba", request_type: "activation",
    valid_from: new Date(now).toISOString(), valid_until: new Date(now + 30 * 86400000).toISOString()
  };
  const expired = {
    event_type: "renewal.completed", folio: "SENE-20261126-ABC125", installation_id: id,
    business_name: "Negocio de prueba", request_type: "renewal",
    valid_from: new Date(now - 60 * 86400000).toISOString(), valid_until: new Date(now - 86400000).toISOString()
  };
  const result = activeLicensesFromEvents([active, expired], now);
  assert.equal(result.total, 1);
  assert.equal(result.licenses[0].folio, "SENE-20261126-ABC124");
  assert.equal(result.licenses[0].validUntil, active.valid_until);
});

// Envía un evento del propietario tal como lo haría el productor de SENE VENTAS.
async function postOwnerEvent(kv: MemoryKV, env: any, encryptionBytes: Uint8Array, hmacBytes: Uint8Array, event: any) {
  const timestamp = String(Math.floor(Date.now() / 1000));
  const eventId = event.event_id;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encryptionKey = await crypto.subtle.importKey("raw", encryptionBytes as unknown as BufferSource, "AES-GCM", false, ["encrypt"]);
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv as unknown as BufferSource, additionalData: new TextEncoder().encode(eventId) as unknown as BufferSource, tagLength: 128 },
    encryptionKey, new TextEncoder().encode(JSON.stringify(event)) as unknown as BufferSource));
  const canonical = [timestamp, eventId, b64url(iv), b64url(ciphertext)].join(".");
  const hmacKey = await crypto.subtle.importKey("raw", hmacBytes as unknown as BufferSource, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", hmacKey, new TextEncoder().encode(canonical) as unknown as BufferSource));
  return worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events", {
    method: "POST",
    headers: {
      "x-sene-timestamp": timestamp, "x-sene-event-id": eventId,
      "x-sene-signature": b64url(signature), "content-type": "application/json"
    },
    body: JSON.stringify({ event_id: eventId, iv: b64url(iv), ciphertext: b64url(ciphertext) })
  }), env);
}

test("POST stores a completed license event without expiry and a pending request with one day", async () => {
  const kv = new MemoryKV();
  const centralToken = "central-owner-test-token-with-more-than-forty-characters";
  const encryptionBytes = crypto.getRandomValues(new Uint8Array(32));
  const hmacBytes = crypto.getRandomValues(new Uint8Array(32));
  const env = {
    GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: b64url(encryptionBytes),
    GUARDIAN_DELIVERY_HMAC_KEY: b64url(hmacBytes),
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await sha256(centralToken)
  } as any;
  const base = {
    installation_id: id, request_type: "activation", created_at: new Date().toISOString(),
    amount_cents: 29900, currency: "MXN",
    business_name: "Negocio de prueba", holder_name: "Titular de prueba",
    whatsapp: "3312345678", code: "ABCDEFG234"
  };
  const completed = {
    ...base, event_id: "123e4567-e89b-42d3-a456-426614174010", event_type: "activation.completed",
    folio: "SENE-20261126-ABC130", payment_status: "paid",
    valid_from: new Date().toISOString(), valid_until: new Date(Date.now() + 30 * 86400000).toISOString()
  };
  const pending = {
    ...base, event_id: "123e4567-e89b-42d3-a456-426614174011", event_type: "activation.requested",
    folio: "SENE-20261126-ABC131", payment_status: "pending",
    expires_at: new Date(Date.now() + 3600000).toISOString()
  };
  const completedResponse = await postOwnerEvent(kv, env, encryptionBytes, hmacBytes, completed);
  assert.equal(completedResponse.status, 202);
  assert.equal("expirationTtl" in (kv.putOptions.get("owner-event:v1:" + completed.event_id) ?? {}), false);
  const pendingResponse = await postOwnerEvent(kv, env, encryptionBytes, hmacBytes, pending);
  assert.equal(pendingResponse.status, 202);
  assert.equal(kv.putOptions.get("owner-event:v1:" + pending.event_id)?.expirationTtl, 86400);

  // La licencia completada sigue listada como activa, con su vencimiento intacto.
  const activeResponse = await worker.fetch(new Request("https://guardian.test/v1/guardian/owner-events?kind=active", {
    headers: { authorization: "Bearer " + centralToken }
  }), env);
  const activeBody = await activeResponse.json() as { total: number; licenses: Array<{ folio: string; validUntil: string }> };
  assert.equal(activeBody.total, 1);
  assert.equal(activeBody.licenses[0].folio, "SENE-20261126-ABC130");
  assert.equal(activeBody.licenses[0].validUntil, completed.valid_until);
});
