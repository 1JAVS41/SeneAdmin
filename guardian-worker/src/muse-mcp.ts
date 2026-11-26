/**
 * Stateless Streamable HTTP MCP endpoint for Meta Muse custom connectors.
 * Exposes one read-only tool scoped to the installation ID in the URL.
 * License owner events are intentionally not exposed here.
 */
type OverviewLoader = () => Promise<Record<string, unknown> | null>;

const MCP_PROTOCOL = "2025-03-26";
const MCP_SERVER = { name: "sene-guardian", version: "1.0.0" };

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
    return rpc(message.id, {
      tools: [{
        name: "get_business_overview",
        description: "Consulta el resumen administrativo de solo lectura de la instalación conectada: estado del negocio, indicadores agregados de inventario y conteo de productos dañados. No incluye datos ni códigos de licencias.",
        inputSchema: { type: "object", properties: {}, additionalProperties: false },
        annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
      }],
    });
  }
  if (message.method === "tools/call") {
    const params = message.params ?? {};
    if (params.name !== "get_business_overview")
      return rpcError(message.id, -32602, "Unknown tool");
    if (params.arguments && Object.keys(params.arguments).length > 0)
      return rpcError(message.id, -32602, "This read-only tool accepts no arguments");
    const overview = await loadOverview();
    if (!overview) return rpc(message.id, {
      content: [{ type: "text", text: "Todavía no hay una captura administrativa disponible para esta instalación." }],
      isError: false,
    });
    // Installation identity is included only as correlation metadata, never as a selector.
    if (overview.installationId !== installationId)
      return rpcError(message.id, -32603, "Installation scope mismatch");
    const { installationId: _installationId, ...safeOverview } = overview;
    return rpc(message.id, {
      content: [{ type: "text", text: JSON.stringify(safeOverview) }],
      structuredContent: safeOverview,
      isError: false,
    });
  }
  return rpcError(message.id, -32601, "Method not found");
}
