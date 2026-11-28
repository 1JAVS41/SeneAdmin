import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index";

class MemoryKV {
  data = new Map<string, string>();
  async get<T>(key: string, type?: string): Promise<T | string | null> {
    const value = this.data.get(key);
    if (value === undefined) return null;
    return (type === "json" ? JSON.parse(value) : value) as T;
  }
  async put(key: string, value: string): Promise<void> { this.data.set(key, value); }
  async delete(key: string): Promise<void> { this.data.delete(key); }
}
const admin = "a".repeat(48);
const env = { GUARDIAN_DATA: new MemoryKV(), GUARDIAN_ADMIN_TOKEN: admin } as any;
const call = (method: string, path: string, body?: unknown, token?: string) =>
  worker.fetch(new Request("https://guardian.test" + path, {
    method,
    headers: {
      ...(body === undefined ? {} : { "content-type": "application/json" }),
      ...(token ? { authorization: "Bearer " + token } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }), env);

async function provision(id: string) {
  const response = await call("POST", "/v1/admin/installations", { installationId: id }, admin);
  assert.equal(response.status, 201);
  return await response.json() as { ingestToken: string; museReadToken: string };
}
// License gate (design section 2, PR #20): reading business-overview (and
// the client Muse tools) requires an active license record. The tests below
// provision installations and then read them, so each installation they read
// is seeded with an active license first — the same convention as
// muse-mcp.test.ts. Ingestion is never gated: a snapshot can always be
// stored, with or without a license record (see the "ingestion works
// without a license record" test below).
const seedLicense = async (id: string) => {
  await env.GUARDIAN_DATA.put("license:v1:" + id, JSON.stringify({
    installationId: id,
    folio: "SENE-20261127-TEST01",
    requestType: "activation",
    validFrom: new Date(Date.now() - 86400000).toISOString(),
    validUntil: new Date(Date.now() + 30 * 86400000).toISOString(),
    updatedAt: new Date().toISOString(),
  }));
};
const snapshot = (installationId: string, capturedAt = "2026-11-26T12:00:00.000Z") => ({
  schema: 1,
  installationId,
  capturedAt,
  business: { status: "active" },
  damagedProducts: { itemCount: 3, updatedAt: capturedAt },
  approvedMovements: { periods: [{ period: "2026-11", cashDisbursementCount: 1, inventoryConsumptionCount: 2, inventoryConsumptionUnits: 4 }] },
  payroll: { periods: [{ period: "2026-11-23", employeeCount: 3, payrollCount: 3, grossCents: 300000, absenceCents: 0, earlyExitCents: 0, loanCents: 0, consumptionCents: 0, netCents: 300000, deliveredCents: 200000 }] },
  workers: { employees: [{ name: "Ana", salaryCents: 100000 }, { name: "Luis", salaryCents: 100000 }, { name: "Maria", salaryCents: 100000 }] },
  sales: { periods: [{ period: "2026-11", workerCount: 3, reportCount: 3, salesNetCents: 1250000, providerPaymentsCents: 250000, loansDisbursedCents: 50000 }] },
  inventory: { categories: [{
    category: "INVENTARIO_SENE",
    itemCount: 8,
    unitsInStock: 31,
    lowStockCount: 2,
    outOfStockCount: 1,
    updatedAt: capturedAt,
  }] },
});

test("health endpoint is public and contains no business data", async () => {
  const response = await call("GET", "/v1/health");
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { service: "SENE GUARDIAN", status: "online", api: 1 });
});

test("installation credentials are isolated, revocable and snapshots are read-only", async () => {
  const idA = "11111111-1111-4111-8111-111111111111";
  const idB = "22222222-2222-4222-8222-222222222222";
  const a = await provision(idA);
  const b = await provision(idB);
  await seedLicense(idA);

  const ingest = await call("POST", "/v1/snapshot", snapshot(idA), a.ingestToken);
  assert.equal(ingest.status, 200);

  const read = await call("GET", "/v1/guardian/business-overview?installationId=" + idA, undefined, a.museReadToken);
  assert.equal(read.status, 200);
  const result = await read.json() as any;
  assert.equal(result.inventory.categories[0].itemCount, 8);
  assert.equal(result.damagedProducts.itemCount, 3);
  assert.equal(result.approvedMovements.periods[0].inventoryConsumptionUnits, 4);
  assert.equal(result.payroll.periods[0].employeeCount, 3);
  assert.deepEqual(result.workers.employees.map((e: any) => e.name), ["Ana", "Luis", "Maria"]);
  assert.deepEqual(result.workers.employees.map((e: any) => e.salaryCents), [100000, 100000, 100000]);
  assert.equal(result.sales.periods[0].salesNetCents, 1250000);

  const crossRead = await call("GET", "/v1/guardian/business-overview?installationId=" + idA, undefined, b.museReadToken);
  assert.equal(crossRead.status, 401);
  const crossWrite = await call("POST", "/v1/snapshot", snapshot(idA), b.ingestToken);
  assert.equal(crossWrite.status, 401);

  const stale = await call("POST", "/v1/snapshot", snapshot(idA, "2026-11-25T12:00:00.000Z"), a.ingestToken);
  assert.equal(stale.status, 409);

  const revoked = await call("POST", "/v1/admin/installations/revoke", { installationId: idA }, admin);
  assert.equal(revoked.status, 200);
  const afterRevoke = await call("GET", "/v1/guardian/business-overview?installationId=" + idA, undefined, a.museReadToken);
  assert.equal(afterRevoke.status, 401);
});

test("invalid payload and unauthenticated admin are rejected", async () => {
  const noAdmin = await call("POST", "/v1/admin/installations", { installationId: "33333333-3333-4333-8333-333333333333" }, "wrong");
  assert.equal(noAdmin.status, 401);
  const invalid = await call("POST", "/v1/snapshot", { schema: 1, installationId: "bad" }, "x".repeat(48));
  assert.equal(invalid.status, 400);
});


test("snapshot accepts payroll for a single employee with a workers list", async () => {
  const id = "66666666-6666-4666-8666-666666666666";
  const credentials = await provision(id);
  await seedLicense(id);
  const body = snapshot(id);
  body.payroll.periods[0].employeeCount = 1;
  body.payroll.periods[0].payrollCount = 1;
  body.workers = { employees: [{ name: "Ana", salaryCents: 100000 }] };
  const response = await call("POST", "/v1/snapshot", body, credentials.ingestToken);
  assert.equal(response.status, 200);
  const read = await call("GET", "/v1/guardian/business-overview?installationId=" + id, undefined, credentials.museReadToken);
  assert.equal(read.status, 200);
  const result = await read.json() as any;
  assert.deepEqual(result.workers.employees, [{ name: "Ana", salaryCents: 100000 }]);
});

test("snapshot strips extra fields and workers stay in their own installation", async () => {
  const idA = "77777777-7777-4777-8777-777777777777";
  const idB = "88888888-8888-4888-8888-888888888888";
  const a = await provision(idA);
  const b = await provision(idB);
  await seedLicense(idA);
  const body: any = snapshot(idA);
  body.workers.employees[0].phone = "555-0000";
  body.extraField = "must-not-be-stored";
  const response = await call("POST", "/v1/snapshot", body, a.ingestToken);
  assert.equal(response.status, 200);
  const read = await call("GET", "/v1/guardian/business-overview?installationId=" + idA, undefined, a.museReadToken);
  const result = await read.json() as any;
  assert.deepEqual(Object.keys(result.workers.employees[0]).sort(), ["name", "salaryCents"]);
  assert.equal(result.extraField, undefined);
  assert.equal(result.workers.employees[0].phone, undefined);
  const crossRead = await call("GET", "/v1/guardian/business-overview?installationId=" + idA, undefined, b.museReadToken);
  assert.equal(crossRead.status, 401);
});

test("snapshot rejects invalid damaged-product aggregate", async () => {
  const id = "55555555-5555-4555-8555-555555555555";
  const credentials = await provision(id);
  const body = snapshot(id);
  body.damagedProducts.itemCount = -1;
  const response = await call("POST", "/v1/snapshot", body, credentials.ingestToken);
  assert.equal(response.status, 400);
});

test("snapshot accepts unknown low-stock metric without inventing a threshold", async () => {
  const id = "44444444-4444-4444-8444-444444444444";
  const credentials = await provision(id);
  await seedLicense(id);
  const body = snapshot(id);
  body.inventory.categories[0].lowStockCount = null as any;
  const response = await call("POST", "/v1/snapshot", body, credentials.ingestToken);
  assert.equal(response.status, 200);
  const read = await call("GET", "/v1/guardian/business-overview?installationId=" + id, undefined, credentials.museReadToken);
  assert.equal(read.status, 200);
  const result = await read.json() as any;
  assert.equal(result.inventory.categories[0].lowStockCount, null);
});

test("snapshot ingestion works without a license record, but reading it requires one", async () => {
  // The license gate (PR #20) must ONLY block the Muse's answers. The app's
  // data upload must always be accepted, even before any license event has
  // ever arrived for the installation — otherwise a new business could never
  // send its first snapshot.
  const id = "99999999-9999-4999-8999-999999999999";
  const credentials = await provision(id);
  const response = await call("POST", "/v1/snapshot", snapshot(id), credentials.ingestToken);
  assert.equal(response.status, 200);
  // No license record seeded: the read is blocked with the exact expiry
  // message path (403), while the stored snapshot stays intact.
  const blocked = await call("GET", "/v1/guardian/business-overview?installationId=" + id, undefined, credentials.museReadToken);
  assert.equal(blocked.status, 403);
  // Once the license record exists, the same snapshot reads fine.
  await seedLicense(id);
  const read = await call("GET", "/v1/guardian/business-overview?installationId=" + id, undefined, credentials.museReadToken);
  assert.equal(read.status, 200);
  const result = await read.json() as any;
  assert.equal(result.inventory.categories[0].itemCount, 8);
});
