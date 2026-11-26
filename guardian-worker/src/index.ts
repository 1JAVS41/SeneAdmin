export interface Env {
  GUARDIAN_DATA: KVNamespace;
  INGEST_TOKEN: string;
  MUSE_READ_TOKEN: string;
}

type Snapshot = {
  schema: 1;
  installationId: string;
  capturedAt: string;
  business: { status: "active" | "unknown" };
  inventory: {
    categories: Array<{
      category: "INVENTARIO_MAESTRO" | "INVENTARIO_SENE";
      itemCount: number;
      unitsInStock: number;
      lowStockCount: number;
      outOfStockCount: number;
      updatedAt: string;
    }>;
  };
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

function authorized(request: Request, token: string): boolean {
  const supplied = request.headers.get("authorization") ?? "";
  return supplied.startsWith("Bearer ") && supplied.slice(7) === token && token.length >= 32;
}

function validSnapshot(value: unknown): value is Snapshot {
  if (!value || typeof value !== "object") return false;
  const v = value as Partial<Snapshot>;
  if (v.schema !== 1 || typeof v.installationId !== "string" ||
      !/^[a-f0-9-]{16,64}$/i.test(v.installationId) ||
      typeof v.capturedAt !== "string" || !Number.isFinite(Date.parse(v.capturedAt)) ||
      !v.business || !Array.isArray(v.inventory?.categories)) return false;
  return v.inventory.categories.length <= 2 && v.inventory.categories.every(c =>
    (c.category === "INVENTARIO_MAESTRO" || c.category === "INVENTARIO_SENE") &&
    Number.isInteger(c.itemCount) && c.itemCount >= 0 &&
    Number.isInteger(c.unitsInStock) && c.unitsInStock >= 0 &&
    Number.isInteger(c.lowStockCount) && c.lowStockCount >= 0 &&
    Number.isInteger(c.outOfStockCount) && c.outOfStockCount >= 0 &&
    typeof c.updatedAt === "string" && Number.isFinite(Date.parse(c.updatedAt)));
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/v1/health") {
      return json({ service: "SENE GUARDIAN", status: "online", api: 1 });
    }
    if (request.method === "POST" && url.pathname === "/v1/snapshot") {
      if (!authorized(request, env.INGEST_TOKEN)) return json({ error: "unauthorized" }, 401);
      const raw = await request.text();
      if (raw.length > 32_000) return json({ error: "payload_too_large" }, 413);
      let value: unknown;
      try { value = JSON.parse(raw); } catch { return json({ error: "invalid_json" }, 400); }
      if (!validSnapshot(value)) return json({ error: "invalid_snapshot" }, 400);
      const key = `snapshot:v1:${value.installationId}`;
      const prior = await env.GUARDIAN_DATA.get<Snapshot>(key, "json");
      if (prior && Date.parse(value.capturedAt) < Date.parse(prior.capturedAt))
        return json({ error: "stale_snapshot" }, 409);
      await env.GUARDIAN_DATA.put(key, JSON.stringify(value), { expirationTtl: 60 * 60 * 24 * 35 });
      return json({ accepted: true, capturedAt: value.capturedAt });
    }
    if (request.method === "GET" && url.pathname === "/v1/guardian/business-overview") {
      if (!authorized(request, env.MUSE_READ_TOKEN)) return json({ error: "unauthorized" }, 401);
      const installationId = url.searchParams.get("installationId") ?? "";
      if (!/^[a-f0-9-]{16,64}$/i.test(installationId)) return json({ error: "invalid_installation" }, 400);
      const snapshot = await env.GUARDIAN_DATA.get<Snapshot>(`snapshot:v1:${installationId}`, "json");
      if (!snapshot) return json({ error: "not_found" }, 404);
      return json({
        capturedAt: snapshot.capturedAt,
        business: snapshot.business,
        inventory: snapshot.inventory,
        dataAgeSeconds: Math.max(0, Math.floor((Date.now() - Date.parse(snapshot.capturedAt)) / 1000)),
      });
    }
    return json({ error: "not_found" }, 404);
  },
};
