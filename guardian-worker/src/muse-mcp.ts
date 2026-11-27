/**
 * Stateless Streamable HTTP MCP endpoint for Meta Muse custom connectors.
 * Exposes four read-only tools scoped to the installation ID in the URL.
 * Each tool only trims the same administrative overview loaded by
 * loadOverview; no tool invents fields or reaches another installation.
 * License owner events are intentionally not exposed here.
 */
type OverviewLoader = () => Promise<Record<string, unknown> | null>;

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
function rpcText(id: unknown, text: string): Response {
  return rpc(id, { content: [{ type: "text", text }], isError: false });
}
function rpcStructured(id: unknown, value: Record<string, unknown>): Response {
  return rpc(id, {
    content: [{ type: "text", text: JSON.stringify(value) }],
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
export async function handleMuseMcp(request: Request, installationId: string, loadOverview: OverviewLoader): Promise<Response> {
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
    return rpc(message.id, {
      protocolVersion: MCP_PROTOCOL,
      capabilities: { tools: { listChanged: false } },
      serverInfo: MCP_SERVER,
      instructions: "SENE GUARDIAN ofrece consultas administrativas de solo lectura para una única instalación. No expone licencias, códigos comerciales, pagos ni acciones de escritura.",
    });
  }
  if (message.method === "ping") return rpc(message.id, {});
  if (message.method === "tools/list") {
    return rpc(message.id, { tools: TOOLS });
  }
  if (message.method === "tools/call") {
    const params = message.params ?? {};
    const toolNames = TOOLS.map((tool) => tool.name);
    if (!toolNames.includes(params.name))
      return rpcError(message.id, -32602, "Unknown tool");
    if (params.arguments && Object.keys(params.arguments).length > 0)
      return rpcError(message.id, -32602, "This read-only tool accepts no arguments");
    const overview = await loadOverview();
    if (!overview) return rpcText(message.id, "Todavía no hay una captura administrativa disponible para esta instalación.");
    // Installation identity is included only as correlation metadata, never as a selector.
    if (overview.installationId !== installationId)
      return rpcError(message.id, -32603, "Installation scope mismatch");
    const { installationId: _installationId, ...safeOverview } = overview;
    if (params.name === "get_business_overview") return rpcStructured(message.id, safeOverview);
    if (params.name === "list_workers") {
      const employees = Array.isArray((safeOverview.workers as any)?.employees)
        ? (safeOverview.workers as any).employees
        : [];
      if (employees.length === 0)
        return rpcText(message.id, "Todavía no hay trabajadores registrados para esta instalación.");
      return rpcStructured(message.id, { total: employees.length, employees });
    }
    if (params.name === "get_inventory_summary") {
      const inventory = (safeOverview.inventory as any) ?? {};
      return rpcStructured(message.id, {
        categories: inventory.categories ?? [],
        damagedProducts: (safeOverview as any).damagedProducts ?? null,
      });
    }
    if (params.name === "get_sales_summary") {
      return rpcStructured(message.id, {
        sales: { periods: (safeOverview.sales as any)?.periods ?? [] },
        approvedMovements: { periods: (safeOverview.approvedMovements as any)?.periods ?? [] },
      });
    }
    return rpcError(message.id, -32602, "Unknown tool");
  }
  return rpcError(message.id, -32601, "Method not found");
}
