/**
 * Stateless Streamable HTTP MCP endpoint for Meta Muse custom connectors.
 * Exposes six read-only tools scoped to the installation ID in the URL.
 * Each tool only trims the same administrative overview loaded by
 * loadOverview; no tool invents fields or reaches another installation.
 * License owner events are intentionally not exposed here.
 * Only list_payrolls and get_payroll take an argument (get_payroll takes
 * the payroll period date); the other four tools accept no arguments.
 */
type OverviewLoader = () => Promise<Record<string, unknown> | null>;
type LicenseLoader = () => Promise<boolean>;

/** Exact message a client Muse returns when the installation license is not
 * active (design section 2, user decision 2026-11-27). It is returned as
 * plain text, never as an error, and as `instructions` on initialize. */
export const LICENSE_EXPIRED_MESSAGE = "tu licencia esta vencida realiza el pago para seguir usando los servicios de SENE-VENTAS";

/** Daily request quota for each client Muse (user decision 2026-11-28):
 * 30 tool calls per installation per day, to protect traffic and server
 * requests. The day runs midnight to midnight in America/Mexico_City
 * (UTC-6, fixed, no DST since 2022). The owner Muse is NOT limited:
 * this quota only applies to the client MCP endpoint. */
export const QUOTA_DAILY_LIMIT = 30;
export const QUOTA_EXCEEDED_MESSAGE = "Ya usaste tus 30 solicitudes de hoy. Se renuevan a medianoche, vuelve mañana.";
const QUOTA_TTL_SECONDS = 48 * 3600;

export function quotaDay(now = new Date()): string {
  return new Date(now.getTime() - 6 * 3600 * 1000).toISOString().slice(0, 10);
}
export function quotaKey(installationId: string, day: string): string {
  return `quota:v1:${installationId}:${day}`;
}
export type QuotaStore = {
  get(key: string): Promise<string | null>;
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>;
};
/** Check and use one daily request for an installation. Returns whether
 * the call is allowed and how many requests remain today (after this one).
 * The counter lives in KV with a 2-day TTL, so it cleans itself and at
 * most two keys exist per installation. Two calls at the exact same
 * instant can both read the same count (KV has no atomic increment), so
 * a client could rarely get one extra call; that is acceptable for a
 * per-client quota. */
export async function useQuota(store: QuotaStore, installationId: string, now = new Date()): Promise<{ allowed: boolean; remaining: number }> {
  const key = quotaKey(installationId, quotaDay(now));
  const raw = await store.get(key);
  const used = raw === null ? 0 : Number(raw);
  const usedSafe = Number.isSafeInteger(used) && used >= 0 ? used : 0;
  if (usedSafe >= QUOTA_DAILY_LIMIT) return { allowed: false, remaining: 0 };
  const next = usedSafe + 1;
  await store.put(key, String(next), { expirationTtl: QUOTA_TTL_SECONDS });
  return { allowed: true, remaining: QUOTA_DAILY_LIMIT - next };
}
export type QuotaChecker = (installationId: string) => Promise<{ allowed: boolean; remaining: number }>;
const quotaFooter = (remaining: number): string => `Solicitudes restantes hoy: ${remaining} de ${QUOTA_DAILY_LIMIT}.`;

const MCP_PROTOCOL = "2025-03-26";
const MCP_SERVER = { name: "sene-guardian", version: "1.0.0" };

const READ_ONLY = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const NO_ARGS = { type: "object", properties: {}, additionalProperties: false };

const TOOLS = [
  {
    name: "get_business_overview",
    description: "Consulta el resumen administrativo de solo lectura de la instalación conectada: estado del negocio, indicadores agregados de inventario, conteo de productos dañados, movimientos aprobados agregados, totales de nómina por periodo, ventas netas agregadas, pagos a proveedores y préstamos entregados, y la lista de trabajadores de esa instalación con nombre y sueldo individual. No incluye datos ni códigos de licencias, ni trabajadores de otra instalación.",
    inputSchema: NO_ARGS,
    annotations: READ_ONLY,
  },
  {
    name: "list_workers",
    description: "Lista de solo lectura de los trabajadores de la instalación conectada, con nombre y sueldo individual semanal de cada uno. Recorta el mismo resumen administrativo ya cargado; no consulta otra instalación ni datos de licencias.",
    inputSchema: NO_ARGS,
    annotations: READ_ONLY,
  },
  {
    name: "get_inventory_summary",
    description: "Resumen de inventario de solo lectura de la instalación conectada: conteos por categoría y conteo de productos dañados. Recorta el mismo resumen administrativo ya cargado. El resumen no contiene lista de artículos, así que no hay detalle por artículo.",
    inputSchema: NO_ARGS,
    annotations: READ_ONLY,
  },
  {
    name: "get_sales_summary",
    description: "Resumen de ventas y movimientos de solo lectura de la instalación conectada: totales de ventas por mes y movimientos aprobados agregados por mes. Recorta el mismo resumen administrativo ya cargado. El resumen no contiene movimientos individuales, así que no hay detalle por venta o movimiento.",
    inputSchema: NO_ARGS,
    annotations: READ_ONLY,
  },
  {
    name: "list_payrolls",
    description: "Lista de solo lectura de los periodos de nómina de la instalación conectada, con totales por periodo (trabajadores, nóminas, bruto, ausencias, salidas anticipadas, préstamos, consumos, neto y entregado). Recorta el mismo resumen administrativo ya cargado; no incluye sueldos individuales por periodo ni datos de otra instalación o de licencias.",
    inputSchema: NO_ARGS,
    annotations: READ_ONLY,
  },
  {
    name: "get_payroll",
    description: "Nómina de solo lectura de un periodo de la instalación conectada: totales de ese periodo (trabajadores, nóminas, bruto, ausencias, salidas anticipadas, préstamos, consumos, neto y entregado). Requiere el argumento period con la fecha del periodo (YYYY-MM-DD) tal como aparece en payroll.periods. Recorta el mismo resumen administrativo ya cargado; no incluye sueldos individuales por periodo ni datos de otra instalación o de licencias.",
    inputSchema: {
      type: "object",
      properties: {
        period: { type: "string", description: "Fecha del periodo de nómina (YYYY-MM-DD), tal como aparece en payroll.periods." },
      },
      required: ["period"],
      additionalProperties: false,
    },
    annotations: READ_ONLY,
  },
];

function rpc(id: unknown, result: unknown): Response {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, result }), {
    status: 200,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "mcp-protocol-version": MCP_PROTOCOL,
    },
  });
}
function rpcError(id: unknown, code: number, message: string): Response {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }), {
    status: 200,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "mcp-protocol-version": MCP_PROTOCOL },
  });
}
function rpcText(id: unknown, text: string, remaining?: number): Response {
  const content: { type: string; text: string }[] = [{ type: "text", text }];
  if (remaining !== undefined) content.push({ type: "text", text: quotaFooter(remaining) });
  return rpc(id, { content, isError: false });
}
function rpcStructured(id: unknown, value: Record<string, unknown>, remaining?: number): Response {
  const content: { type: string; text: string }[] = [{ type: "text", text: JSON.stringify(value) }];
  if (remaining !== undefined) content.push({ type: "text", text: quotaFooter(remaining) });
  return rpc(id, {
    content,
    structuredContent: value,
    isError: false,
  });
}
async function parseBody(request: Request): Promise<any> {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) throw new Error("content_type");
  const text = await request.text();
  if (text.length > 16_384) throw new Error("too_large");
  return JSON.parse(text);
}
export async function handleMuseMcp(request: Request, installationId: string, loadOverview: OverviewLoader, loadLicenseActive: LicenseLoader = async () => true, checkQuota?: QuotaChecker): Promise<Response> {
  let message: any;
  try { message = await parseBody(request); }
  catch (error) {
    if (error instanceof Error && error.message === "too_large") return new Response(null, { status: 413 });
    return new Response(null, { status: 400 });
  }
  if (!message || message.jsonrpc !== "2.0" || typeof message.method !== "string")
    return rpcError(message?.id ?? null, -32600, "Invalid Request");

  // MCP notifications have no id and must not produce a JSON-RPC response.
  if (!Object.prototype.hasOwnProperty.call(message, "id")) {
    if (message.method === "notifications/initialized" || message.method.startsWith("notifications/"))
      return new Response(null, { status: 202 });
    return new Response(null, { status: 202 });
  }

  if (message.method === "initialize") {
    // tools/list, ping and initialize are never blocked, but initialize
    // carries the expiry message as instructions when the license is not
    // active, so the Muse answers correctly even before calling a tool.
    const licenseActive = await loadLicenseActive();
    return rpc(message.id, {
      protocolVersion: MCP_PROTOCOL,
      capabilities: { tools: { listChanged: false } },
      serverInfo: MCP_SERVER,
      instructions: licenseActive
        ? "SENE GUARDIAN ofrece consultas administrativas de solo lectura para una única instalación. No expone licencias, códigos comerciales, pagos ni acciones de escritura. Cada instalación tiene 30 solicitudes diarias; cada consulta cuenta como una y el resultado indica cuántas quedan."
        : LICENSE_EXPIRED_MESSAGE,
    });
  }
  if (message.method === "ping") return rpc(message.id, {});
  if (message.method === "tools/list") {
    return rpc(message.id, { tools: TOOLS });
  }
  if (message.method === "tools/call") {
    // License gate (design section 2): with no active license, every tool
    // call returns the exact expiry message as plain text, not an error,
    // and no business data is served.
    if (!await loadLicenseActive()) return rpcText(message.id, LICENSE_EXPIRED_MESSAGE);
    const params = message.params ?? {};
    const toolNames = TOOLS.map((tool) => tool.name);
    if (!toolNames.includes(params.name))
      return rpcError(message.id, -32602, "Unknown tool");
    // Argument validation is per tool: only get_payroll takes an argument
    // (period, a YYYY-MM-DD date already present in the snapshot). Every
    // other tool accepts no arguments, so any argument there — including an
    // installationId that could attempt to change scope — is rejected.
    const args = params.arguments ?? {};
    if (typeof args !== "object" || args === null || Array.isArray(args))
      return rpcError(message.id, -32602, "Invalid arguments");
    if (params.name === "get_payroll") {
      const keys = Object.keys(args);
      if (keys.length !== 1 || keys[0] !== "period" || typeof args.period !== "string" ||
          !/^\d{4}-\d{2}-\d{2}$/.test(args.period) || !Number.isFinite(Date.parse(args.period)))
        return rpcError(message.id, -32602, "get_payroll requires exactly one argument: period (YYYY-MM-DD)");
    } else if (Object.keys(args).length > 0) {
      return rpcError(message.id, -32602, "This read-only tool accepts no arguments");
    }
    let remaining: number | undefined;
    if (checkQuota) {
      const quota = await checkQuota(installationId);
      if (!quota.allowed) return rpcText(message.id, QUOTA_EXCEEDED_MESSAGE);
      remaining = quota.remaining;
    }
    const overview = await loadOverview();
    if (!overview) return rpcText(message.id, "Todavía no hay una captura administrativa disponible para esta instalación.", remaining);
    // Installation identity is included only as correlation metadata, never as a selector.
    if (overview.installationId !== installationId)
      return rpcError(message.id, -32603, "Installation scope mismatch");
    const { installationId: _installationId, ...safeOverview } = overview;
    if (params.name === "get_business_overview") return rpcStructured(message.id, safeOverview, remaining);
    if (params.name === "list_workers") {
      const employees = Array.isArray((safeOverview.workers as any)?.employees)
        ? (safeOverview.workers as any).employees
        : [];
      if (employees.length === 0)
        return rpcText(message.id, "Todavía no hay trabajadores registrados para esta instalación.", remaining);
      return rpcStructured(message.id, { total: employees.length, employees }, remaining);
    }
    if (params.name === "get_inventory_summary") {
      const inventory = (safeOverview.inventory as any) ?? {};
      return rpcStructured(message.id, {
        categories: inventory.categories ?? [],
        damagedProducts: (safeOverview as any).damagedProducts ?? null,
      }, remaining);
    }
    if (params.name === "get_sales_summary") {
      return rpcStructured(message.id, {
        sales: { periods: (safeOverview.sales as any)?.periods ?? [] },
        approvedMovements: { periods: (safeOverview.approvedMovements as any)?.periods ?? [] },
      }, remaining);
    }
    if (params.name === "list_payrolls") {
      const periods = Array.isArray((safeOverview.payroll as any)?.periods)
        ? (safeOverview.payroll as any).periods
        : [];
      if (periods.length === 0)
        return rpcText(message.id, "Todavía no hay periodos de nómina registrados para esta instalación.", remaining);
      return rpcStructured(message.id, { total: periods.length, periods }, remaining);
    }
    if (params.name === "get_payroll") {
      const periods = Array.isArray((safeOverview.payroll as any)?.periods)
        ? (safeOverview.payroll as any).periods
        : [];
      const found = periods.find((p: any) => p?.period === args.period);
      if (!found)
        return rpcText(message.id, `No hay nómina registrada para el periodo ${args.period} en esta instalación.`, remaining);
      return rpcStructured(message.id, found, remaining);
    }
    return rpcError(message.id, -32602, "Unknown tool");
  }
  return rpcError(message.id, -32601, "Method not found");
}
