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
const hash = async (value: string) => Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))).toString("hex");
const rpc = (method: string, id: number) => JSON.stringify({ jsonrpc: "2.0", id, method });

test("only the central owner credential can open the owner MUSE connector", async () => {
  const token = "owner-muse-test-token-with-more-than-forty-characters";
  const env = {
    GUARDIAN_DATA: new MemoryKV(), GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "",
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await hash(token)
  } as any;
  const denied = await worker.fetch(new Request("https://guardian.test/v1/muse/owner/mcp", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + "x".repeat(48) },
    body: rpc("tools/list", 1)
  }), env);
  assert.equal(denied.status, 401);

  const allowed = await worker.fetch(new Request("https://guardian.test/v1/muse/owner/mcp", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + token },
    body: rpc("tools/list", 2)
  }), env);
  assert.equal(allowed.status, 200);
  const result = await allowed.json() as any;
  assert.equal(result.result.tools.length, 1);
  assert.equal(result.result.tools[0].name, "list_owner_license_requests");
  assert.equal(result.result.tools[0].annotations.readOnlyHint, true);
});

test("owner MUSE returns an empty safe result when no license requests exist", async () => {
  const token = "owner-muse-test-token-with-more-than-forty-characters";
  const env = {
    GUARDIAN_DATA: new MemoryKV(), GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "",
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await hash(token)
  } as any;
  const response = await worker.fetch(new Request("https://guardian.test/v1/muse/owner/mcp", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + token },
    body: JSON.stringify({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "list_owner_license_requests", arguments: {} } })
  }), env);
  assert.equal(response.status, 200);
  const body = await response.json() as any;
  assert.match(body.result.content[0].text, /no están disponibles|\[\]/);
});
