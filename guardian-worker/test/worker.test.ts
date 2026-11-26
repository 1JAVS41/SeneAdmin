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
const snapshot = (installationId: string, capturedAt = "2026-11-26T12:00:00.000Z") => ({
  schema: 1,
  installationId,
  capturedAt,
  business: { status: "active" },
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

  const ingest = await call("POST", "/v1/snapshot", snapshot(idA), a.ingestToken);
  assert.equal(ingest.status, 200);

  const read = await call("GET", "/v1/guardian/business-overview?installationId=" + idA, undefined, a.museReadToken);
  assert.equal(read.status, 200);
  const result = await read.json() as any;
  assert.equal(result.inventory.categories[0].itemCount, 8);

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
