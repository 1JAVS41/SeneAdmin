import { handleOwnerDelivery } from "./owner-delivery";
import { handleMuseMcp } from "./muse-mcp";
import { handleOwnerMuseMcp } from "./owner-muse-mcp";

export interface Env {
  GUARDIAN_DATA: KVNamespace;
  GUARDIAN_ADMIN_TOKEN: string;
  GUARDIAN_DELIVERY_ENC_KEY: string;
  GUARDIAN_DELIVERY_HMAC_KEY: string;
  GUARDIAN_OWNER_CENTRAL_TOKEN_HASH: string;
  MUSE_ALLOWED_ORIGINS?: string;
}

type Category = {
  category: "INVENTARIO_MAESTRO" | "INVENTARIO_SENE";
  itemCount: number;
  unitsInStock: number;
  lowStockCount: number | null;
  outOfStockCount: number;
  updatedAt: string;
};
type Snapshot = {
  schema: 1;
  installationId: string;
  capturedAt: string;
  business: { status: "active" | "unknown" };
  inventory: { categories: Category[] };
  damagedProducts?: { itemCount: number; updatedAt: string };
  approvedMovements?: { periods: Array<{
    period: string; cashDisbursementCount: number; inventoryConsumptionCount: number; inventoryConsumptionUnits: number;
  }> };
  payroll?: { periods: Array<{
    period: string; employeeCount: number; payrollCount: number; grossCents: number; absenceCents: number;
    earlyExitCents: number; loanCents: number; consumptionCents: number; netCents: number; deliveredCents: number;
  }> };
  sales?: { periods: Array<{ period: string; workerCount: number; reportCount: number; salesNetCents: number; providerPaymentsCents: number; loansDisbursedCents: number }> };
};
type Installation = {
  schema: 1;
  revoked: boolean;
  ingestTokenHash: string;
  museTokenHash: string;
  createdAt: string;
  revokedAt?: string;
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

function bearer(request: Request): string {
  const value = request.headers.get("authorization") ?? "";
  return value.startsWith("Bearer ") ? value.slice(7) : "";
}
function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}
function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function validId(value: string): boolean {
  return /^[a-f0-9-]{16,64}$/i.test(value);
}
function validSnapshot(value: unknown): value is Snapshot {
  if (!value || typeof value !== "object") return false;
  const v = value as Partial<Snapshot>;
  if (v.schema !== 1 || typeof v.installationId !== "string" || !validId(v.installationId) ||
      typeof v.capturedAt !== "string" || !Number.isFinite(Date.parse(v.capturedAt)) ||
      !v.business || !["active", "unknown"].includes(v.business.status) ||
      !Array.isArray(v.inventory?.categories) || v.inventory.categories.length > 2) return false;
  const seen = new Set<string>();
  return v.inventory.categories.every(c =>
    (c.category === "INVENTARIO_MAESTRO" || c.category === "INVENTARIO_SENE") &&
    !seen.has(c.category) && !!seen.add(c.category) &&
    Number.isInteger(c.itemCount) && c.itemCount >= 0 &&
    Number.isInteger(c.unitsInStock) && c.unitsInStock >= 0 &&
    (c.lowStockCount === null || (Number.isInteger(c.lowStockCount) && c.lowStockCount >= 0 && c.lowStockCount <= c.itemCount)) &&
    Number.isInteger(c.outOfStockCount) && c.outOfStockCount >= 0 &&
    c.outOfStockCount <= c.itemCount &&
    typeof c.updatedAt === "string" && Number.isFinite(Date.parse(c.updatedAt))) &&
    (v.damagedProducts === undefined || (
      Number.isInteger(v.damagedProducts.itemCount) && v.damagedProducts.itemCount >= 0 &&
      v.damagedProducts.itemCount <= 100000 &&
      typeof v.damagedProducts.updatedAt === "string" && Number.isFinite(Date.parse(v.damagedProducts.updatedAt)))) &&
    (v.approvedMovements === undefined || (
      Array.isArray(v.approvedMovements.periods) && v.approvedMovements.periods.length <= 36 &&
      v.approvedMovements.periods.every(p => /^\d{4}-\d{2}$/.test(p.period) &&
        Number.isSafeInteger(p.cashDisbursementCount) && p.cashDisbursementCount >= 0 &&
        Number.isSafeInteger(p.inventoryConsumptionCount) && p.inventoryConsumptionCount >= 0 &&
        Number.isSafeInteger(p.inventoryConsumptionUnits) && p.inventoryConsumptionUnits >= 0))) &&
    (v.payroll === undefined || (
      Array.isArray(v.payroll.periods) && v.payroll.periods.length <= 36 &&
      v.payroll.periods.every(p => /^\d{4}-\d{2}-\d{2}$/.test(p.period) &&
        Number.isInteger(p.employeeCount) && p.employeeCount >= 3 && p.employeeCount <= 10000 &&
        Number.isInteger(p.payrollCount) && p.payrollCount >= p.employeeCount && p.payrollCount <= 10000 &&
        [p.grossCents,p.absenceCents,p.earlyExitCents,p.loanCents,p.consumptionCents,p.netCents,p.deliveredCents]
          .every(n => Number.isSafeInteger(n) && n >= 0)))) &&
    (v.sales === undefined || (
      Array.isArray(v.sales.periods) && v.sales.periods.length <= 36 &&
      v.sales.periods.every(p => /^\d{4}-\d{2}$/.test(p.period) &&
        Number.isInteger(p.workerCount) && p.workerCount >= 1 && p.workerCount <= 10000 &&
        Number.isInteger(p.reportCount) && p.reportCount >= p.workerCount && p.reportCount <= 10000 &&
        Number.isSafeInteger(p.salesNetCents) &&
        [p.providerPaymentsCents,p.loansDisbursedCents].every(n => Number.isSafeInteger(n) && n >= 0))));
}
async function installation(env: Env, id: string): Promise<Installation | null> {
  return env.GUARDIAN_DATA.get<Installation>(`installation:v1:${id}`, "json");
}
async function authorizedFor(env: Env, id: string, token: string, scope: "ingest" | "muse"): Promise<boolean> {
  if (!token || token.length < 40 || !validId(id)) return false;
  const record = await installation(env, id);
  if (!record || record.revoked) return false;
  const expected = scope === "ingest" ? record.ingestTokenHash : record.museTokenHash;
  return constantTimeEqual(await sha256(token), expected);
}
async function readJson(request: Request, maxBytes: number): Promise<unknown> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > maxBytes) throw new Error("payload_too_large");
  if (!request.body) throw new Error("invalid_json");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        throw new Error("payload_too_large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder().decode(bytes));
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/v1/health")
      return json({ service: "SENE GUARDIAN", status: "online", api: 1 });

    if (request.method === "POST" && url.pathname === "/v1/admin/installations") {
      if (!constantTimeEqual(bearer(request), env.GUARDIAN_ADMIN_TOKEN) || env.GUARDIAN_ADMIN_TOKEN.length < 40)
        return json({ error: "unauthorized" }, 401);
      let body: any;
      try { body = await readJson(request, 2048); } catch { return json({ error: "invalid_json" }, 400); }
      const id = typeof body?.installationId === "string" ? body.installationId : "";
      if (!validId(id)) return json({ error: "invalid_installation" }, 400);
      if (await installation(env, id)) return json({ error: "already_exists" }, 409);
      const ingestToken = randomToken();
      const museToken = randomToken();
      const record: Installation = {
        schema: 1, revoked: false,
        ingestTokenHash: await sha256(ingestToken),
        museTokenHash: await sha256(museToken),
        createdAt: new Date().toISOString(),
      };
      await env.GUARDIAN_DATA.put(`installation:v1:${id}`, JSON.stringify(record));
      return json({ installationId: id, ingestToken, museReadToken: museToken, shownOnce: true }, 201);
    }

    if (request.method === "POST" && url.pathname === "/v1/admin/installations/revoke") {
      if (!constantTimeEqual(bearer(request), env.GUARDIAN_ADMIN_TOKEN) || env.GUARDIAN_ADMIN_TOKEN.length < 40)
        return json({ error: "unauthorized" }, 401);
      let body: any;
      try { body = await readJson(request, 2048); } catch { return json({ error: "invalid_json" }, 400); }
      const id = typeof body?.installationId === "string" ? body.installationId : "";
      if (!validId(id)) return json({ error: "invalid_installation" }, 400);
      const record = await installation(env, id);
      if (!record) return json({ error: "not_found" }, 404);
      record.revoked = true;
      record.revokedAt = new Date().toISOString();
      await env.GUARDIAN_DATA.put(`installation:v1:${id}`, JSON.stringify(record));
      await env.GUARDIAN_DATA.delete(`snapshot:v1:${id}`);
      // Central license events are not installation-owned; revoking a business
      // installation must never delete or redirect central license requests.
      return json({ revoked: true, installationId: id });
    }

    if (request.method === "POST" && url.pathname === "/v1/guardian/owner-events")
      return handleOwnerDelivery(request, env);

    if (request.method === "GET" && url.pathname === "/v1/guardian/owner-events") {
      const token = bearer(request);
      const expectedHash = env.GUARDIAN_OWNER_CENTRAL_TOKEN_HASH ?? "";
      if (token.length < 40 || !/^[a-f0-9]{64}$/i.test(expectedHash) ||
          !constantTimeEqual(await sha256(token), expectedHash.toLowerCase()))
        return json({ error: "unauthorized" }, 401);
      return handleOwnerDelivery(request, env);
    }

    if (url.pathname === "/v1/muse/owner/mcp") {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      const token = bearer(request);
      const expectedHash = env.GUARDIAN_OWNER_CENTRAL_TOKEN_HASH ?? "";
      if (token.length < 40 || !/^[a-f0-9]{64}$/i.test(expectedHash) ||
          !constantTimeEqual(await sha256(token), expectedHash.toLowerCase()))
        return json({ error: "unauthorized" }, 401);
      return handleOwnerMuseMcp(request, async () => {
        const response = await handleOwnerDelivery(new Request("https://internal/v1/guardian/owner-events", {
          method: "GET", headers: { authorization: "Bearer " + token }
        }), env);
        if (!response.ok) return null;
        const payload = await response.json() as { events?: unknown[] };
        return Array.isArray(payload.events) ? payload.events : [];
      });
    }

    const museMatch = url.pathname.match(/^\/v1\/muse\/([a-f0-9-]{16,64})\/mcp$/i);
    if (museMatch) {
      if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
      // MCP Streamable HTTP security: reject browser origins unless explicitly allowlisted.
      // Server-to-server clients that omit Origin remain supported.
      const origin = request.headers.get("origin");
      if (origin !== null) {
        const allowedOrigins = (env.MUSE_ALLOWED_ORIGINS ?? "")
          .split(",").map(value => value.trim()).filter(Boolean);
        if (!allowedOrigins.includes(origin)) return json({ error: "forbidden_origin" }, 403);
      }
      const id = museMatch[1];
      if (!await authorizedFor(env, id, bearer(request), "muse")) return json({ error: "unauthorized" }, 401);
      return handleMuseMcp(request, id, async () => {
        const snapshot = await env.GUARDIAN_DATA.get<Snapshot>(`snapshot:v1:${id}`, "json");
        if (!snapshot) return null;
        return {
          installationId: snapshot.installationId,
          capturedAt: snapshot.capturedAt,
          business: snapshot.business,
          inventory: snapshot.inventory,
          ...(snapshot.damagedProducts ? { damagedProducts: snapshot.damagedProducts } : {}),
          ...(snapshot.approvedMovements ? { approvedMovements: snapshot.approvedMovements } : {}),
          ...(snapshot.payroll ? { payroll: snapshot.payroll } : {}),
          ...(snapshot.sales ? { sales: snapshot.sales } : {}),
          dataAgeSeconds: Math.max(0, Math.floor((Date.now() - Date.parse(snapshot.capturedAt)) / 1000)),
        };
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/snapshot") {
      let value: unknown;
      try { value = await readJson(request, 32_000); }
      catch (error) { return json({ error: error instanceof Error && error.message === "payload_too_large" ? "payload_too_large" : "invalid_json" }, error instanceof Error && error.message === "payload_too_large" ? 413 : 400); }
      if (!validSnapshot(value)) return json({ error: "invalid_snapshot" }, 400);
      if (!await authorizedFor(env, value.installationId, bearer(request), "ingest"))
        return json({ error: "unauthorized" }, 401);
      const key = `snapshot:v1:${value.installationId}`;
      const prior = await env.GUARDIAN_DATA.get<Snapshot>(key, "json");
      if (prior && Date.parse(value.capturedAt) < Date.parse(prior.capturedAt))
        return json({ error: "stale_snapshot" }, 409);
      await env.GUARDIAN_DATA.put(key, JSON.stringify(value), { expirationTtl: 60 * 60 * 24 * 35 });
      return json({ accepted: true, capturedAt: value.capturedAt });
    }

    if (request.method === "GET" && url.pathname === "/v1/guardian/business-overview") {
      const id = url.searchParams.get("installationId") ?? "";
      if (!await authorizedFor(env, id, bearer(request), "muse")) return json({ error: "unauthorized" }, 401);
      const snapshot = await env.GUARDIAN_DATA.get<Snapshot>(`snapshot:v1:${id}`, "json");
      if (!snapshot) return json({ error: "not_found" }, 404);
      return json({
        capturedAt: snapshot.capturedAt,
        business: snapshot.business,
        inventory: snapshot.inventory,
        ...(snapshot.damagedProducts ? { damagedProducts: snapshot.damagedProducts } : {}),
          ...(snapshot.approvedMovements ? { approvedMovements: snapshot.approvedMovements } : {}),
          ...(snapshot.payroll ? { payroll: snapshot.payroll } : {}),
        dataAgeSeconds: Math.max(0, Math.floor((Date.now() - Date.parse(snapshot.capturedAt)) / 1000)),
      });
    }
    return json({ error: "not_found" }, 404);
  },
};
