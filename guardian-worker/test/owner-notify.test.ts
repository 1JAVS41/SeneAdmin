import { strict as assert } from "node:assert";
import { test } from "node:test";
import { notifyOwnerMuse, type OwnerNotifyEvent } from "../src/owner-notify";

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

type SentEmail = { url: string; body: Record<string, any> };
function mockFetch(status: number, calls: SentEmail[]) {
  return (async (url: any, init?: any) => {
    calls.push({ url: String(url), body: JSON.parse(String(init?.body ?? "{}")) });
    return new Response("{}", { status });
  }) as unknown as typeof fetch;
}
function throwingFetch() {
  return (async () => { throw new Error("network down"); }) as unknown as typeof fetch;
}

const notifyEnv = (kv: MemoryKV, overrides: Record<string, unknown> = {}) => ({
  GUARDIAN_DATA: kv as unknown as KVNamespace,
  GUARDIAN_NOTIFY_BREVO_KEY: "xkeysib-test-key",
  GUARDIAN_NOTIFY_FROM: "avisos-guardian@example.com",
  GUARDIAN_NOTIFY_TO: "owner@muse.ai",
  ...overrides,
});

const requestedEvent = (overrides: Record<string, unknown> = {}): OwnerNotifyEvent => ({
  event_id: "123e4567-e89b-42d3-a456-426614174200",
  event_type: "renewal.requested",
  folio: "SENE-20261127-ABC200",
  business_name: "Negocio de prueba",
  request_type: "renewal",
  amount_cents: 29900,
  currency: "MXN",
  created_at: new Date().toISOString(),
  expires_at: new Date(Date.now() + 3600000).toISOString(),
  ...overrides,
});

const completedEvent = (overrides: Record<string, unknown> = {}): OwnerNotifyEvent => ({
  event_id: "123e4567-e89b-42d3-a456-426614174201",
  event_type: "activation.completed",
  folio: "SENE-20261127-ABC201",
  business_name: "Negocio de prueba",
  request_type: "activation",
  amount_cents: 29900,
  currency: "MXN",
  created_at: new Date().toISOString(),
  valid_from: new Date().toISOString(),
  valid_until: new Date(Date.now() + 30 * 86400000).toISOString(),
  ...overrides,
});

test("requested event sends an email with folio, business, type and amount, and never the code", async () => {
  const kv = new MemoryKV();
  const calls: SentEmail[] = [];
  const originalFetch = globalThis.fetch;
  (globalThis as any).fetch = mockFetch(201, calls);
  // The stored event carries code/whatsapp/holder_name, but the email must
  // never include them: they are not part of OwnerNotifyEvent.
  const event = {
    ...requestedEvent(),
    code: "ABCDEFG234",
    whatsapp: "3312345678",
    holder_name: "Titular de prueba",
  } as unknown as OwnerNotifyEvent;
  let sent: boolean;
  try {
    sent = await notifyOwnerMuse(notifyEnv(kv), event);
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(sent, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "https://api.brevo.com/v3/smtp/email");
  const body = calls[0].body;
  assert.equal(body.sender.email, "avisos-guardian@example.com");
  assert.deepEqual(body.to, [{ email: "owner@muse.ai" }]);
  assert.match(body.subject, /^\[SENE GUARDIÁN\] /);
  assert.match(body.subject, /SENE-20261127-ABC200/);
  assert.match(body.textContent, /SENE-20261127-ABC200/);
  assert.match(body.textContent, /Negocio de prueba/);
  assert.match(body.textContent, /renovación/);
  assert.match(body.textContent, /\$299\.00 MXN/);
  const whole = JSON.stringify(body);
  assert.equal(whole.includes("ABCDEFG234"), false);
  assert.equal(whole.includes("3312345678"), false);
  assert.equal(whole.includes("Titular de prueba"), false);
});

test("completed event sends an email with folio, business, type and validity dates", async () => {
  const kv = new MemoryKV();
  const calls: SentEmail[] = [];
  const originalFetch = globalThis.fetch;
  (globalThis as any).fetch = mockFetch(200, calls);
  const event = {
    ...completedEvent(),
    code: "ABCDEFG234",
    whatsapp: "3312345678",
    holder_name: "Titular de prueba",
  } as unknown as OwnerNotifyEvent;
  let sent: boolean;
  try {
    sent = await notifyOwnerMuse(notifyEnv(kv), event);
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(sent, true);
  assert.equal(calls.length, 1);
  const body = calls[0].body;
  assert.match(body.subject, /^\[SENE GUARDIÁN\] /);
  assert.match(body.textContent, /SENE-20261127-ABC201/);
  assert.match(body.textContent, /Negocio de prueba/);
  assert.match(body.textContent, /nueva/);
  assert.match(body.textContent, new RegExp(event.valid_from!.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(body.textContent, new RegExp(event.valid_until!.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.equal(JSON.stringify(body).includes("ABCDEFG234"), false);
});

test("license.synced events are silent", async () => {
  const kv = new MemoryKV();
  const calls: SentEmail[] = [];
  const originalFetch = globalThis.fetch;
  (globalThis as any).fetch = mockFetch(200, calls);
  let sent: boolean;
  try {
    sent = await notifyOwnerMuse(notifyEnv(kv), completedEvent({ event_type: "license.synced" }));
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(sent, false);
  assert.equal(calls.length, 0);
});

test("second call with the same event_id does not send again", async () => {
  const kv = new MemoryKV();
  const calls: SentEmail[] = [];
  const originalFetch = globalThis.fetch;
  (globalThis as any).fetch = mockFetch(200, calls);
  try {
    assert.equal(await notifyOwnerMuse(notifyEnv(kv), requestedEvent()), true);
    assert.equal(await notifyOwnerMuse(notifyEnv(kv), requestedEvent()), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(calls.length, 1);
  assert.equal(await kv.get("notify:v1:123e4567-e89b-42d3-a456-426614174200"), "sent");
});

test("missing notification secrets send nothing and do not throw", async () => {
  const kv = new MemoryKV();
  const calls: SentEmail[] = [];
  const originalFetch = globalThis.fetch;
  (globalThis as any).fetch = mockFetch(200, calls);
  try {
    const env = notifyEnv(kv) as Record<string, unknown>;
    delete env.GUARDIAN_NOTIFY_BREVO_KEY;
    delete env.GUARDIAN_NOTIFY_FROM;
    delete env.GUARDIAN_NOTIFY_TO;
    assert.equal(await notifyOwnerMuse(env as any, requestedEvent()), false);
    assert.equal(await notifyOwnerMuse(notifyEnv(kv, {
      GUARDIAN_NOTIFY_BREVO_KEY: "",
      GUARDIAN_NOTIFY_FROM: "",
      GUARDIAN_NOTIFY_TO: "",
    }), requestedEvent()), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(calls.length, 0);
});

test("Brevo 500 returns false, does not throw, and leaves no idempotency key", async () => {
  const kv = new MemoryKV();
  const calls: SentEmail[] = [];
  const originalFetch = globalThis.fetch;
  (globalThis as any).fetch = mockFetch(500, calls);
  let sent: boolean;
  try {
    sent = await notifyOwnerMuse(notifyEnv(kv), requestedEvent());
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(sent, false);
  assert.equal(calls.length, 1);
  // No key was written, so a later retry may still send.
  assert.equal(await kv.get("notify:v1:123e4567-e89b-42d3-a456-426614174200"), null);
});

test("a network failure returns false and does not throw", async () => {
  const kv = new MemoryKV();
  const originalFetch = globalThis.fetch;
  (globalThis as any).fetch = throwingFetch();
  let sent: boolean;
  try {
    sent = await notifyOwnerMuse(notifyEnv(kv), requestedEvent());
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.equal(sent, false);
});
