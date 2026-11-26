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
  inventory: { categories: [{ category: "INVENTARIO_MAESTRO", itemCount: 12, unitsInStock: 30, lowStockCount: 2, outOfStockCount: 1, updatedAt: new Date().toISOString() }] }
};
const envFor = async () => {
  const kv = new MemoryKV();
  await kv.put("installation:v1:" + id, JSON.stringify({
    schema: 1, revoked: false, ingestTokenHash: await sha256("unused"),
    museTokenHash: await sha256(token), createdAt: new Date().toISOString()
  }));
  await kv.put("snapshot:v1:" + id, JSON.stringify(snapshot));
  return { GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused", GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "", GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: "" } as any;
};
const post = (body: unknown, auth = token, origin?: string) => new Request("https://guardian.test/v1/muse/" + id + "/mcp", {
  method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + auth, ...(origin ? { origin } : {}) }, body: JSON.stringify(body)
});

test("Muse MCP initializes and exposes only one read-only administrative tool", async () => {
  const env = await envFor();
  const init = await worker.fetch(post({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "test", version: "1" } } }), env);
  assert.equal(init.status, 200);
  const list = await worker.fetch(post({ jsonrpc: "2.0", id: 2, method: "tools/list" }), env);
  const body = await list.json() as any;
  assert.deepEqual(body.result.tools.map((t: any) => t.name), ["get_business_overview"]);
  assert.equal(body.result.tools[0].annotations.readOnlyHint, true);
});

test("Muse tool returns only its own installation overview and never central license events", async () => {
  const env = await envFor();
  const response = await worker.fetch(post({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "get_business_overview", arguments: {} } }), env);
  const body = await response.json() as any;
  const result = JSON.parse(body.result.content[0].text);
  assert.equal(result.business.status, "active");
  assert.equal(result.installationId, undefined);
  assert.equal(JSON.stringify(result).includes("activation"), false);
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
