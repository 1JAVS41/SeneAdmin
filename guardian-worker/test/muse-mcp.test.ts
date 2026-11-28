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
    { period: "2026-10-31", employeeCount: 2, payrollCount: 2, grossCents: 190000, absenceCents: 0, earlyExitCents: 0, loanCents: 0, consumptionCents: 0, netCents: 190000, deliveredCents: 190000 },
  ] },
  workers: { employees: [{ name: "Ana", salaryCents: 100000 }, { name: "Luis", salaryCents: 90000 }] }
};
// License gate record (design section 2): the default test env seeds an
// active license, so the pre-existing tool tests keep exercising the tools.
// Gate tests below delete it or set validUntil in the past.
const EXPIRED_MESSAGE = "tu licencia esta vencida realiza el pago para seguir usando los servicios de SENE-VENTAS";
const licenseRecord = (validUntil: string) => ({
  installationId: id, folio: "SENE-20261126-ABC124", requestType: "activation",
  validFrom: new Date(Date.now() - 86400000).toISOString(), validUntil,
  updatedAt: new Date().toISOString()
});
const seedLicense = async (env: any, validUntil: string) => {
  await env.GUARDIAN_DATA.put("license:v1:" + id, JSON.stringify(licenseRecord(validUntil)));
};
const envFor = async () => {
  const kv = new MemoryKV();
  await kv.put("installation:v1:" + id, JSON.stringify({
    schema: 1, revoked: false, ingestTokenHash: await sha256("unused"),
    museTokenHash: await sha256(token), createdAt: new Date().toISOString()
  }));
  await kv.put("snapshot:v1:" + id, JSON.stringify(snapshot));
  const env = { GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused", GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "", GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: "" } as any;
  await seedLicense(env, new Date(Date.now() + 30 * 86400000).toISOString());
  return env;
};
const initialize = async (env: any, reqId: number | string) => {
  const response = await worker.fetch(post({ jsonrpc: "2.0", id: reqId, method: "initialize", params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "test", version: "1" } } }), env);
  return (await response.json() as any).result;
};
const businessOverview = async (env: any) => worker.fetch(new Request("https://guardian.test/v1/guardian/business-overview?installationId=" + id, {
  headers: { authorization: "Bearer " + token }
}), env);
const post = (body: unknown, auth = token, origin?: string) => new Request("https://guardian.test/v1/muse/" + id + "/mcp", {
  method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + auth, ...(origin ? { origin } : {}) }, body: JSON.stringify(body)
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

test("Muse MCP initializes and exposes only six read-only administrative tools", async () => {
  const env = await envFor();
  const init = await worker.fetch(post({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "test", version: "1" } } }), env);
  assert.equal(init.status, 200);
  const list = await worker.fetch(post({ jsonrpc: "2.0", id: 2, method: "tools/list" }), env);
  const body = await list.json() as any;
  assert.deepEqual(body.result.tools.map((t: any) => t.name), ["get_business_overview", "list_workers", "get_inventory_summary", "get_sales_summary", "list_payrolls", "get_payroll"]);
  assert.equal(body.result.tools.every((t: any) => t.annotations.readOnlyHint === true), true);
  assert.equal(body.result.tools.every((t: any) => t.annotations.destructiveHint === false), true);
  assert.equal(body.result.tools[0].description.includes("trabajadores"), true);
  // No hay herramientas de lista de artículos, de movimientos individuales,
  // de reportes, de solicitudes de nómina ni de asistencia: el snapshot no
  // tiene los datos para responderlas sin inventar campos.
  assert.equal(body.result.tools.some((t: any) => ["list_inventory_items", "get_inventory_item", "list_out_of_stock_items", "list_sales_movements", "list_admin_reports", "get_admin_report", "get_worker", "list_payroll_requests", "list_attendance", "get_attendance_summary"].includes(t.name)), false);
  // Solo get_payroll acepta un argumento (period); las demás no aceptan ninguno.
  const getPayroll = body.result.tools.find((t: any) => t.name === "get_payroll");
  assert.deepEqual(getPayroll.inputSchema.required, ["period"]);
  assert.equal(body.result.tools.filter((t: any) => t.name !== "get_payroll").every((t: any) => Object.keys(t.inputSchema.properties).length === 0), true);
});

test("Muse tool returns only its own installation overview and never central license events", async () => {
  const env = await envFor();
  const response = await worker.fetch(post({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "get_business_overview", arguments: {} } }), env);
  const body = await response.json() as any;
  const result = JSON.parse(body.result.content[0].text);
  assert.equal(result.business.status, "active");
  assert.deepEqual(result.workers.employees, [{ name: "Ana", salaryCents: 100000 }, { name: "Luis", salaryCents: 90000 }]);
  assert.equal(result.installationId, undefined);
  assert.equal(JSON.stringify(result).includes("activation"), false);
});

test("list_workers returns total and employees from the same overview", async () => {
  const env = await envFor();
  const result = await callTool(env, 4, "list_workers");
  const parsed = JSON.parse(result.content[0].text);
  assert.equal(parsed.total, 2);
  assert.deepEqual(parsed.employees, [{ name: "Ana", salaryCents: 100000 }, { name: "Luis", salaryCents: 90000 }]);
  assert.deepEqual(result.structuredContent, { total: 2, employees: [{ name: "Ana", salaryCents: 100000 }, { name: "Luis", salaryCents: 90000 }] });
  assert.equal(result.isError, false);
});

test("list_workers says so when the installation has no workers registered", async () => {
  const env = await envFor();
  const withoutWorkers: any = { ...snapshot };
  delete withoutWorkers.workers;
  await env.GUARDIAN_DATA.put("snapshot:v1:" + id, JSON.stringify(withoutWorkers));
  const result = await callTool(env, 5, "list_workers");
  assert.equal(result.content[0].text, "Todavía no hay trabajadores registrados para esta instalación.");
  assert.equal(result.isError, false);
});

test("get_inventory_summary returns categories and damaged products from the same overview", async () => {
  const env = await envFor();
  const result = await callTool(env, 6, "get_inventory_summary");
  const parsed = JSON.parse(result.content[0].text);
  assert.equal(parsed.categories.length, 1);
  assert.equal(parsed.categories[0].category, "INVENTARIO_MAESTRO");
  assert.equal(parsed.categories[0].outOfStockCount, 1);
  assert.equal(parsed.damagedProducts.itemCount, 2);
  assert.equal(result.isError, false);
});

test("get_sales_summary returns sales periods and approved movement periods from the same overview", async () => {
  const env = await envFor();
  const result = await callTool(env, 7, "get_sales_summary");
  const parsed = JSON.parse(result.content[0].text);
  assert.deepEqual(parsed.sales.periods, [{ period: "2026-11", workerCount: 1, reportCount: 1, salesNetCents: 50000, providerPaymentsCents: 1000, loansDisbursedCents: 0 }]);
  assert.deepEqual(parsed.approvedMovements.periods, [{ period: "2026-11", cashDisbursementCount: 1, inventoryConsumptionCount: 2, inventoryConsumptionUnits: 5 }]);
  assert.equal(result.isError, false);
});

test("list_payrolls returns payroll periods with totals from the same overview", async () => {
  const env = await envFor();
  const result = await callTool(env, 8, "list_payrolls");
  const parsed = JSON.parse(result.content[0].text);
  assert.equal(parsed.total, 2);
  assert.deepEqual(parsed.periods, (snapshot as any).payroll.periods);
  assert.deepEqual(result.structuredContent, { total: 2, periods: (snapshot as any).payroll.periods });
  assert.equal(result.isError, false);
});

test("list_payrolls says so when the installation has no payroll periods", async () => {
  const env = await envFor();
  const withoutPayroll: any = { ...snapshot };
  delete withoutPayroll.payroll;
  await env.GUARDIAN_DATA.put("snapshot:v1:" + id, JSON.stringify(withoutPayroll));
  const result = await callTool(env, 9, "list_payrolls");
  assert.equal(result.content[0].text, "Todavía no hay periodos de nómina registrados para esta instalación.");
  assert.equal(result.isError, false);
});

test("get_payroll returns the requested period by its date", async () => {
  const env = await envFor();
  const result = await callTool(env, 10, "get_payroll", { period: "2026-11-07" });
  const parsed = JSON.parse(result.content[0].text);
  assert.deepEqual(parsed, (snapshot as any).payroll.periods[0]);
  assert.deepEqual(result.structuredContent, (snapshot as any).payroll.periods[0]);
  assert.equal(result.isError, false);
});

test("get_payroll says so when the requested period is not in the snapshot", async () => {
  const env = await envFor();
  const result = await callTool(env, 11, "get_payroll", { period: "2026-01-01" });
  assert.equal(result.content[0].text, "No hay nómina registrada para el periodo 2026-01-01 en esta instalación.");
  assert.equal(result.isError, false);
});

test("get_payroll rejects a missing, malformed or extra argument", async () => {
  const env = await envFor();
  assert.equal((await callToolRaw(env, 12, "get_payroll", {})).error.code, -32602);
  assert.equal((await callToolRaw(env, 13, "get_payroll", { period: "2026-11" })).error.code, -32602);
  assert.equal((await callToolRaw(env, 14, "get_payroll", { period: "2026-13-40" })).error.code, -32602);
  assert.equal((await callToolRaw(env, 15, "get_payroll", { period: "2026-11-07", installationId: "ffffffff-ffff-ffff-ffff-ffffffffffff" })).error.code, -32602);
});

test("Muse MCP rejects wrong tokens and revoked installations", async () => {
  const env = await envFor();
  assert.equal((await worker.fetch(post({ jsonrpc: "2.0", id: 1, method: "tools/list" }, "wrong-token"), env)).status, 401);
  const record = await env.GUARDIAN_DATA.get("installation:v1:" + id, "json") as any;
  record.revoked = true;
  await env.GUARDIAN_DATA.put("installation:v1:" + id, JSON.stringify(record));
  assert.equal((await worker.fetch(post({ jsonrpc: "2.0", id: 1, method: "tools/list" }), env)).status, 401);
});

test("Muse MCP rejects writes and unknown methods", async () => {
  const env = await envFor();
  const write = await worker.fetch(post({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "delete_inventory", arguments: {} } }), env);
  assert.equal((await write.json() as any).error.code, -32602);
});


test("Muse MCP rejects browser origins unless explicitly allowlisted", async () => {
  const env = await envFor();
  const message = { jsonrpc: "2.0", id: 7, method: "tools/list" };
  const blocked = await worker.fetch(post(message, token, "https://untrusted.example"), env);
  assert.equal(blocked.status, 403);
  env.MUSE_ALLOWED_ORIGINS = "https://muse.example, https://admin.example";
  const allowed = await worker.fetch(post(message, token, "https://muse.example"), env);
  assert.equal(allowed.status, 200);
  const serverToServer = await worker.fetch(post(message), env);
  assert.equal(serverToServer.status, 200);
});

test("Muse MCP negotiates initialization, answers ping, and accepts initialized notification", async () => {
  const env = await envFor();
  const init = await worker.fetch(post({
    jsonrpc: "2.0", id: "init-1", method: "initialize",
    params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "Muse compatibility test", version: "1.0" } }
  }), env);
  const initBody = await init.json() as any;
  assert.equal(initBody.result.protocolVersion, "2025-03-26");
  assert.equal(init.headers.get("mcp-protocol-version"), "2025-03-26");
  const ping = await worker.fetch(post({ jsonrpc: "2.0", id: "ping-1", method: "ping" }), env);
  assert.deepEqual((await ping.json() as any).result, {});
  const notification = await worker.fetch(post({ jsonrpc: "2.0", method: "notifications/initialized" }), env);
  assert.equal(notification.status, 202);
  assert.equal(await notification.text(), "");
});

test("Muse MCP returns method-not-allowed for unsupported GET transport", async () => {
  const env = await envFor();
  const response = await worker.fetch(new Request("https://guardian.test/v1/muse/" + id + "/mcp", {
    method: "GET", headers: { authorization: "Bearer " + token }
  }), env);
  assert.equal(response.status, 405);
});

test("Muse MCP rejects arguments that could attempt to change installation scope", async () => {
  const env = await envFor();
  const response = await worker.fetch(post({
    jsonrpc: "2.0", id: "scope-1", method: "tools/call",
    params: { name: "get_business_overview", arguments: { installationId: "ffffffff-ffff-ffff-ffff-ffffffffffff" } }
  }), env);
  const body = await response.json() as any;
  assert.equal(body.error.code, -32602);
});

test("license gate: no license record blocks tools/call with the exact expiry message, not an error", async () => {
  const env = await envFor();
  await env.GUARDIAN_DATA.delete("license:v1:" + id);
  const result = await callTool(env, 100, "get_business_overview");
  assert.equal(result.content[0].text, EXPIRED_MESSAGE);
  assert.equal(result.isError, false);
  // initialize, tools/list and ping are not blocked: initialize carries the
  // expiry message as instructions instead.
  assert.equal((await initialize(env, 101)).instructions, EXPIRED_MESSAGE);
  const list = await worker.fetch(post({ jsonrpc: "2.0", id: 102, method: "tools/list" }), env);
  assert.equal((await list.json() as any).result.tools.length, 6);
  const ping = await worker.fetch(post({ jsonrpc: "2.0", id: 103, method: "ping" }), env);
  assert.deepEqual((await ping.json() as any).result, {});
});

test("license gate: active license lets tools work and initialize keeps the normal instructions", async () => {
  const env = await envFor();
  const result = await callTool(env, 104, "list_workers");
  const parsed = JSON.parse(result.content[0].text);
  assert.equal(parsed.total, 2);
  assert.equal(result.isError, false);
  assert.equal((await initialize(env, 105)).instructions,
    "SENE GUARDIAN ofrece consultas administrativas de solo lectura para una única instalación. No expone licencias, códigos comerciales, pagos ni acciones de escritura.");
});

test("license gate: expired license (validUntil in the past) blocks tools/call with the exact expiry message", async () => {
  const env = await envFor();
  await seedLicense(env, new Date(Date.now() - 86400000).toISOString());
  const result = await callTool(env, 106, "get_business_overview");
  assert.equal(result.content[0].text, EXPIRED_MESSAGE);
  assert.equal(result.isError, false);
  assert.equal((await initialize(env, 107)).instructions, EXPIRED_MESSAGE);
});

test("business-overview requires an active license", async () => {
  const env = await envFor();
  const ok = await businessOverview(env);
  assert.equal(ok.status, 200);
  assert.equal(((await ok.json()) as any).business.status, "active");
  await env.GUARDIAN_DATA.delete("license:v1:" + id);
  const blocked = await businessOverview(env);
  assert.equal(blocked.status, 403);
  assert.equal(((await blocked.json()) as any).message, EXPIRED_MESSAGE);
  await seedLicense(env, new Date(Date.now() - 86400000).toISOString());
  const expired = await businessOverview(env);
  assert.equal(expired.status, 403);
  assert.equal(((await expired.json()) as any).message, EXPIRED_MESSAGE);
});
