import { strict as assert } from "node:assert";
import { test } from "node:test";
import worker from "../src/index";
import { QUOTA_DAILY_LIMIT, QUOTA_EXCEEDED_MESSAGE, quotaDay, quotaKey } from "../src/muse-mcp";

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
const token = "muse-installation-token-longer-than-forty-characters";
const sha256 = async (value: string) => Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))).toString("hex");
const id = "abcdef12-3456-7890-abcd-ef1234567890";
const snapshot = {
  schema: 1, installationId: id, capturedAt: new Date().toISOString(),
  business: { status: "active" },
  inventory: { categories: [{ category: "INVENTARIO_MAESTRO", itemCount: 12, unitsInStock: 30, lowStockCount: 2, outOfStockCount: 1, updatedAt: new Date().toISOString() }] },
  damagedProducts: { itemCount: 2, updatedAt: new Date().toISOString() },
  approvedMovements: { periods: [{ period: "2026-11", cashDisbursementCount: 1, inventoryConsumptionCount: 2, inventoryConsumptionUnits: 5 }] },
  sales: { periods: [{ period: "2026-11", workerCount: 1, reportCount: 1, salesNetCents: 50000, providerPaymentsCents: 1000, loansDisbursedCents: 0 }] },
  payroll: { periods: [
    { period: "2026-11-07", employeeCount: 2, payrollCount: 2, grossCents: 200000, absenceCents: 1000, earlyExitCents: 0, loanCents: 5000, consumptionCents: 2000, netCents: 192000, deliveredCents: 192000 },
  ] },
  workers: { employees: [{ name: "Ana", salaryCents: 100000 }, { name: "Luis", salaryCents: 90000 }] }
};
const licenseRecord = (validUntil: string) => ({
  installationId: id, folio: "SENE-20261126-ABC124", requestType: "activation",
  validFrom: new Date(Date.now() - 86400000).toISOString(), validUntil,
  updatedAt: new Date().toISOString()
});
const envFor = async () => {
  const kv = new MemoryKV();
  await kv.put("installation:v1:" + id, JSON.stringify({
    schema: 1, revoked: false, ingestTokenHash: await sha256("unused"),
    museTokenHash: await sha256(token), createdAt: new Date().toISOString()
  }));
  await kv.put("snapshot:v1:" + id, JSON.stringify(snapshot));
  await kv.put("license:v1:" + id, JSON.stringify(licenseRecord(new Date(Date.now() + 30 * 86400000).toISOString())));
  return { GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused", GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "", GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: "" } as any;
};
const post = (body: unknown, auth = token) => new Request("https://guardian.test/v1/muse/" + id + "/mcp", {
  method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + auth }, body: JSON.stringify(body)
});
const callTool = async (env: any, toolId: number, name: string, args: Record<string, unknown> = {}) => {
  const response = await worker.fetch(post({ jsonrpc: "2.0", id: toolId, method: "tools/call", params: { name, arguments: args } }), env);
  const body = await response.json() as any;
  return body.result;
};
const callToolRaw = async (env: any, toolId: number, name: string, args: Record<string, unknown> = {}) => {
  const response = await worker.fetch(post({ jsonrpc: "2.0", id: toolId, method: "tools/call", params: { name, arguments: args } }), env);
  return await response.json() as any;
};
const quotaCount = async (env: any) => {
  const value = await env.GUARDIAN_DATA.get(quotaKey(id, quotaDay()));
  return value === null ? 0 : Number(value);
};

test("quota: each tool call counts down and the result says how many remain today", async () => {
  const env = await envFor();
  const first = await callTool(env, 1, "list_workers");
  assert.equal(first.content[0].text.includes('"total":2'), true);
  assert.equal(first.content[1].text, "Solicitudes restantes hoy: 29 de 30.");
  assert.equal(first.isError, false);
  // The structured content is unchanged: the quota travels only in the
  // second text block, so existing consumers keep parsing the first block.
  assert.deepEqual(first.structuredContent, { total: 2, employees: [{ name: "Ana", salaryCents: 100000 }, { name: "Luis", salaryCents: 90000 }] });
  const second = await callTool(env, 2, "list_workers");
  assert.equal(second.content[1].text, "Solicitudes restantes hoy: 28 de 30.");
  assert.equal(await quotaCount(env), 2);
});

test("quota: the 30 daily requests are allowed and the 31st is refused with the exact message", async () => {
  const env = await envFor();
  let last: any;
  for (let i = 1; i <= QUOTA_DAILY_LIMIT; i++) {
    last = await callTool(env, i, "get_inventory_summary");
    assert.equal(last.isError, false);
  }
  assert.equal(last.content[1].text, "Solicitudes restantes hoy: 0 de 30.");
  assert.equal(await quotaCount(env), 30);
  const refused = await callTool(env, 31, "get_inventory_summary");
  assert.equal(refused.content[0].text, QUOTA_EXCEEDED_MESSAGE);
  assert.equal(refused.content.length, 1);
  assert.equal(refused.isError, false);
  assert.equal(JSON.stringify(refused).includes("INVENTARIO_MAESTRO"), false);
  assert.equal(await quotaCount(env), 30);
});

test("quota: text results also carry the countdown as a second block", async () => {
  const env = await envFor();
  const withoutWorkers: any = { ...snapshot };
  delete withoutWorkers.workers;
  await env.GUARDIAN_DATA.put("snapshot:v1:" + id, JSON.stringify(withoutWorkers));
  const result = await callTool(env, 1, "list_workers");
  assert.equal(result.content[0].text, "Todavía no hay trabajadores registrados para esta instalación.");
  assert.equal(result.content[1].text, "Solicitudes restantes hoy: 29 de 30.");
});

test("quota: invalid calls never consume quota", async () => {
  const env = await envFor();
  assert.equal((await callToolRaw(env, 1, "delete_inventory", {})).error.code, -32602);
  assert.equal((await callToolRaw(env, 2, "get_payroll", {})).error.code, -32602);
  assert.equal((await callToolRaw(env, 3, "get_business_overview", { installationId: "ffffffff-ffff-ffff-ffff-ffffffffffff" })).error.code, -32602);
  assert.equal(await quotaCount(env), 0);
});

test("quota: an expired license never consumes quota", async () => {
  const env = await envFor();
  await env.GUARDIAN_DATA.put("license:v1:" + id, JSON.stringify(licenseRecord(new Date(Date.now() - 86400000).toISOString())));
  const result = await callTool(env, 1, "get_business_overview");
  assert.equal(result.content[0].text, "tu licencia esta vencida realiza el pago para seguir usando los servicios de SENE-VENTAS");
  assert.equal(await quotaCount(env), 0);
});

test("quota: initialize, ping and tools/list never consume quota", async () => {
  const env = await envFor();
  const init = await worker.fetch(post({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "test", version: "1" } } }), env);
  const initBody = await init.json() as any;
  assert.equal(initBody.result.instructions.includes("30 solicitudes diarias"), true);
  const ping = await worker.fetch(post({ jsonrpc: "2.0", id: 2, method: "ping" }), env);
  assert.deepEqual((await ping.json() as any).result, {});
  const list = await worker.fetch(post({ jsonrpc: "2.0", id: 3, method: "tools/list" }), env);
  assert.equal((await list.json() as any).result.tools.length, 6);
  assert.equal(await quotaCount(env), 0);
});

test("quota: the counter resets the next day and is scoped per installation", async () => {
  const env = await envFor();
  const yesterday = new Date(Date.now() - 6 * 3600 * 1000 - 86400000).toISOString().slice(0, 10);
  await env.GUARDIAN_DATA.put(quotaKey(id, yesterday), "30");
  const result = await callTool(env, 1, "list_workers");
  assert.equal(result.content[1].text, "Solicitudes restantes hoy: 29 de 30.");
  assert.equal(await quotaCount(env), 1);
  // The key carries the installation id, so another business never shares
  // this installation's counter.
  assert.equal(quotaKey(id, quotaDay()).startsWith("quota:v1:" + id + ":"), true);
});
