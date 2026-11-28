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
  assert.equal(result.result.tools.length, 3);
  assert.deepEqual(result.result.tools.map((tool: any) => tool.name), ["list_owner_license_requests", "list_owner_active_licenses", "list_owner_device_transfers"]);
  assert.equal(result.result.tools[0].annotations.readOnlyHint, true);
  assert.equal(result.result.tools[1].annotations.readOnlyHint, true);
  assert.equal(result.result.tools[2].annotations.readOnlyHint, true);
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

test("owner MUSE returns total 0 for active licenses when none exist", async () => {
  const token = "owner-muse-test-token-with-more-than-forty-characters";
  const b64url = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64url");
  const env = {
    GUARDIAN_DATA: new MemoryKV(), GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: b64url(crypto.getRandomValues(new Uint8Array(32))),
    GUARDIAN_DELIVERY_HMAC_KEY: b64url(crypto.getRandomValues(new Uint8Array(32))),
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await hash(token)
  } as any;
  const response = await worker.fetch(new Request("https://guardian.test/v1/muse/owner/mcp", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + token },
    body: JSON.stringify({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "list_owner_active_licenses", arguments: {} } })
  }), env);
  assert.equal(response.status, 200);
  const body = await response.json() as any;
  assert.equal(body.result.isError, false);
  assert.equal(body.result.structuredContent.total, 0);
  assert.deepEqual(body.result.structuredContent.licenses, []);
});

test("an installation token cannot open the owner MUSE connector", async () => {
  const token = "owner-muse-test-token-with-more-than-forty-characters";
  const env = {
    GUARDIAN_DATA: new MemoryKV(), GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "",
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await hash(token)
  } as any;
  const response = await worker.fetch(new Request("https://guardian.test/v1/muse/owner/mcp", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + "i".repeat(48) },
    body: JSON.stringify({ jsonrpc: "2.0", id: 5, method: "tools/call", params: { name: "list_owner_active_licenses", arguments: {} } })
  }), env);
  assert.equal(response.status, 401);
});

test("owner MUSE lists device transfers in the safe shape", async () => {
  const token = "owner-muse-test-token-with-more-than-forty-characters";
  const b64url = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64url");
  const kv = new MemoryKV();
  const encryptionBytes = crypto.getRandomValues(new Uint8Array(32));
  const encryptionKey = await crypto.subtle.importKey("raw", encryptionBytes as unknown as BufferSource, "AES-GCM", false, ["encrypt"]);
  const transfer = {
    event_id: "123e4567-e89b-42d3-a456-426614174300", event_type: "transfer.completed",
    installation_id: "bbbbbbbb-2222-4222-9222-222222222222",
    old_installation_id: "abcdef12-3456-7890-abcd-ef1234567890",
    folio: "SENE-20261126-ABC124", business_name: "Negocio de prueba",
    holder_name: "Titular de prueba", whatsapp: "3312345678",
    created_at: new Date().toISOString(),
    valid_until: new Date(Date.now() + 30 * 86400000).toISOString(),
  };
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv as unknown as BufferSource, additionalData: new TextEncoder().encode(transfer.event_id) as unknown as BufferSource, tagLength: 128 },
    encryptionKey, new TextEncoder().encode(JSON.stringify(transfer)) as unknown as BufferSource));
  await kv.put("owner-event:v1:" + transfer.event_id, JSON.stringify({
    eventId: transfer.event_id, installationId: transfer.installation_id, timestamp: String(Math.floor(Date.now() / 1000)),
    iv: b64url(iv), ciphertext: b64url(ciphertext)
  }));
  const env = {
    GUARDIAN_DATA: kv, GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: b64url(encryptionBytes),
    GUARDIAN_DELIVERY_HMAC_KEY: b64url(crypto.getRandomValues(new Uint8Array(32))),
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await hash(token)
  } as any;
  const call = (name: string, id: number) => worker.fetch(new Request("https://guardian.test/v1/muse/owner/mcp", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + token },
    body: JSON.stringify({ jsonrpc: "2.0", id, method: "tools/call", params: { name, arguments: {} } })
  }), env);
  const response = await call("list_owner_device_transfers", 10);
  assert.equal(response.status, 200);
  const body = await response.json() as any;
  assert.equal(body.result.isError, false);
  assert.equal(body.result.structuredContent.total, 1);
  const [listed] = body.result.structuredContent.transfers;
  assert.deepEqual(Object.keys(listed).sort(), ["businessName", "folio", "installationId", "oldInstallationId", "transferredAt", "validUntil"]);
  assert.equal(listed.folio, "SENE-20261126-ABC124");
  assert.equal(listed.businessName, "Negocio de prueba");
  assert.equal(listed.oldInstallationId, "abcdef12-3456-7890-abcd-ef1234567890");
  assert.equal(listed.installationId, "bbbbbbbb-2222-4222-9222-222222222222");
  assert.equal(listed.validUntil, transfer.valid_until);
  assert.equal(listed.transferredAt, transfer.created_at);
  const whole = body.result.content[0].text;
  assert.equal(whole.includes("3312345678"), false);
  assert.equal(whole.includes("Titular de prueba"), false);
});

test("owner MUSE returns total 0 for device transfers when none exist", async () => {
  const token = "owner-muse-test-token-with-more-than-forty-characters";
  const b64url = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64url");
  const env = {
    GUARDIAN_DATA: new MemoryKV(), GUARDIAN_ADMIN_TOKEN: "unused",
    GUARDIAN_DELIVERY_ENC_KEY: b64url(crypto.getRandomValues(new Uint8Array(32))),
    GUARDIAN_DELIVERY_HMAC_KEY: b64url(crypto.getRandomValues(new Uint8Array(32))),
    GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: await hash(token)
  } as any;
  const response = await worker.fetch(new Request("https://guardian.test/v1/muse/owner/mcp", {
    method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + token },
    body: JSON.stringify({ jsonrpc: "2.0", id: 11, method: "tools/call", params: { name: "list_owner_device_transfers", arguments: {} } })
  }), env);
  assert.equal(response.status, 200);
  const body = await response.json() as any;
  assert.equal(body.result.isError, false);
  assert.equal(body.result.structuredContent.total, 0);
  assert.deepEqual(body.result.structuredContent.transfers, []);
});
