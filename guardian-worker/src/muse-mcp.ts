/**
 * Stateless Streamable HTTP MCP endpoint for Meta Muse custom connectors.
 * Every query is read-only and scoped to the installation ID in the URL.
 * Owner license events are intentionally never exposed here.
 */
type OverviewLoader = () => Promise<Record<string, unknown> | null>;

const MCP_PROTOCOL = "2025-03-26";
const MCP_SERVER = { name: "sene-guardian", version: "1.0.0" };
const READ_ONLY = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const TOOL_DESCRIPTIONS: Record<string, string> = {
  get_business_overview: "Resumen administrativo agregado de esta instalación.",
  get_inventory_summary: "Totales de inventario por categoría, unidades y productos sin existencias.",
  get_reports_summary: "Cantidad de reportes administrativos y reportes de pedidos registrados.",
  get_workers_summary: "Cantidad de perfiles de trabajadores, sin nombres ni datos personales.",
  get_attendance_summary: "Indicadores agregados de asistencia de la semana.",
  get_payroll_summary: "Totales de nómina pagada y pendiente, sin importes ni datos individuales.",
  get_damaged_products_summary: "Cantidad de registros de productos dañados, sin exponer datos personales.",
  get_product_by_code: "Consulta un producto por su código exacto en esta instalación.",
  search_products: "Busca productos por código o nombre en esta instalación.",
  list_approved_movements: "Consulta movimientos aprobados de esta instalación; ventas y movimientos de inventario se mantienen separados.",
};

function rpc(id: unknown, result: unknown): Response {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, result }), {
    status: 200,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "mcp-protocol-version": MCP_PROTOCOL },
  });
}
function rpcError(id: unknown, code: number, message: string): Response {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }), {
    status: 200,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "mcp-protocol-version": MCP_PROTOCOL },
  });
}
async function parseBody(request: Request): Promise<any> {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().includes("application/json")) throw new Error("content_type");
  const text = await request.text();
  if (text.length > 16_384) throw new Error("too_large");
  return JSON.parse(text);
}
function selectData(name: string, overview: Record<string, any>, args: Record<string, unknown> = {}): Record<string, unknown> {
  switch (name) {
    case "get_inventory_summary":
      return { inventory: overview.inventory };
    case "get_reports_summary":
      return { reports: overview.operations?.reports ?? null };
    case "get_workers_summary":
      return { employees: overview.operations?.employees ?? null };
    case "get_attendance_summary":
      return { attendance: overview.operations?.attendance ?? null };
    case "get_payroll_summary":
      return { payroll: overview.operations?.payroll ?? null };
    case "get_damaged_products_summary":
      return { damagedProducts: overview.operations?.damagedProducts ?? null };
    case "get_product_by_code": {
      const code = String(args.code ?? "").trim().toLowerCase();
      const product = (overview.products ?? []).find((p: any) => String(p.code ?? "").trim().toLowerCase() === code);
      return { product: product ?? null };
    }
    case "search_products": {
      const query = String(args.query ?? "").trim().toLowerCase();
      const products = (overview.products ?? []).filter((p: any) =>
        String(p.code ?? "").toLowerCase().includes(query) || String(p.name ?? "").toLowerCase().includes(query)).slice(0, 100);
      return { products, totalMatches: (overview.products ?? []).filter((p: any) =>
        String(p.code ?? "").toLowerCase().includes(query) || String(p.name ?? "").toLowerCase().includes(query)).length };
    }
    case "list_approved_movements": {
      const kind = String(args.type ?? "ALL");
      const movements = (overview.approvedMovements ?? []).filter((m: any) =>
        kind === "ALL" || (kind === "SALES" && m.type === "SALE") ||
        (kind === "INVENTORY" && m.type === "INVENTORY_CONSUMPTION") ||
        (kind === "CASH" && m.type === "CASH_DISBURSEMENT"));
      return { sales: movements.filter((m: any) => m.type === "SALE"),
        inventoryMovements: movements.filter((m: any) => m.type === "INVENTORY_CONSUMPTION"),
        cashMovements: movements.filter((m: any) => m.type === "CASH_DISBURSEMENT") };
    }
    default:
      return overview;
  }
}
export async function handleMuseMcp(request: Request, installationId: string, loadOverview: OverviewLoader): Promise<Response> {
  let message: any;
  try { message = await parseBody(request); }
  catch (error) {
    if (error instanceof Error && error.message === "too_large") return new Response(null, { status: 413 });
    return new Response(null, { status: 400 });
  }
  if (!message || message.jsonrpc !== "2.0" || typeof message.method !== "string")
    return rpcError(message?.id ?? null, -32600, "Invalid Request");

  if (!Object.prototype.hasOwnProperty.call(message, "id")) {
    if (message.method === "notifications/initialized" || message.method.startsWith("notifications/"))
      return new Response(null, { status: 202 });
    return new Response(null, { status: 202 });
  }

  if (message.method === "initialize") {
    return rpc(message.id, {
      protocolVersion: MCP_PROTOCOL,
      capabilities: { tools: { listChanged: false } },
      serverInfo: MCP_SERVER,
      instructions: "SENE GUARDIÁN ofrece consultas administrativas agregadas de solo lectura para una única instalación. No expone licencias, códigos comerciales, pagos, importes individuales ni acciones de escritura.",
    });
  }
  if (message.method === "ping") return rpc(message.id, {});
  if (message.method === "tools/list") {
    return rpc(message.id, {
      tools: Object.entries(TOOL_DESCRIPTIONS).map(([name, description]) => ({
        name, description,
        inputSchema: name === "get_product_by_code"
          ? { type: "object", properties: { code: { type: "string", minLength: 1, maxLength: 128 } }, required: ["code"], additionalProperties: false }
          : name === "search_products"
          ? { type: "object", properties: { query: { type: "string", minLength: 1, maxLength: 128 } }, required: ["query"], additionalProperties: false }
          : name === "list_approved_movements"
          ? { type: "object", properties: { type: { type: "string", enum: ["ALL", "SALES", "INVENTORY", "CASH"] } }, additionalProperties: false }
          : { type: "object", properties: {}, additionalProperties: false },
        annotations: READ_ONLY,
      })),
    });
  }
  if (message.method === "tools/call") {
    const params = message.params ?? {};
    const name = params.name;
    if (typeof name !== "string" || !Object.prototype.hasOwnProperty.call(TOOL_DESCRIPTIONS, name))
      return rpcError(message.id, -32602, "Unknown tool");
    const args = params.arguments && typeof params.arguments === "object" ? params.arguments as Record<string, unknown> : {};
    if (name === "get_product_by_code" && (typeof args.code !== "string" || !args.code.trim()))
      return rpcError(message.id, -32602, "A product code is required");
    if (name === "search_products" && (typeof args.query !== "string" || !args.query.trim()))
      return rpcError(message.id, -32602, "A product search query is required");
    if (name === "list_approved_movements" && args.type !== undefined && !["ALL", "SALES", "INVENTORY", "CASH"].includes(String(args.type)))
      return rpcError(message.id, -32602, "Invalid movement type");
    if (!["get_product_by_code", "search_products", "list_approved_movements"].includes(name) && Object.keys(args).length > 0)
      return rpcError(message.id, -32602, "This read-only tool accepts no arguments");
    const overview = await loadOverview();
    if (!overview) return rpc(message.id, {
      content: [{ type: "text", text: "Todavía no hay una captura administrativa disponible para esta instalación." }],
      isError: false,
    });
    if (overview.installationId !== installationId)
      return rpcError(message.id, -32603, "Installation scope mismatch");
    const { installationId: _installationId, ...safeOverview } = overview;
    const selected = selectData(name, safeOverview, args);
    return rpc(message.id, {
      content: [{ type: "text", text: JSON.stringify(selected) }],
      structuredContent: selected,
      isError: false,
    });
  }
  return rpcError(message.id, -32601, "Method not found");
}
