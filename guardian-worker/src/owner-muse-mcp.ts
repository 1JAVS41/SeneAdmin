/** Dedicated read-only MCP endpoint for the single owner MUSE.
 * It is authenticated with the central owner credential, never an installation token.
 * This connector alone can read license activation/renewal requests.
 */
type OwnerEventsLoader = () => Promise<unknown[] | null>;
type OwnerInventoryLoader = () => Promise<Record<string, unknown> | null>;
const PROTOCOL = "2025-03-26";
function response(id: unknown, result: unknown): Response {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, result }), {
    status: 200, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "mcp-protocol-version": PROTOCOL }
  });
}
function error(id: unknown, code: number, message: string): Response {
  return response(id, { error: { code, message } });
}
export async function handleOwnerMuseMcp(request: Request, loadEvents: OwnerEventsLoader, loadInventory: OwnerInventoryLoader): Promise<Response> {
  let message: any;
  try {
    if (!(request.headers.get("content-type") ?? "").toLowerCase().includes("application/json")) return new Response(null, {status:400});
    const raw = await request.text();
    if (raw.length > 16_384) return new Response(null, {status:413});
    message = JSON.parse(raw);
  } catch { return new Response(null, {status:400}); }
  if (!message || message.jsonrpc !== "2.0" || typeof message.method !== "string") return error(message?.id ?? null, -32600, "Invalid Request");
  if (!Object.prototype.hasOwnProperty.call(message, "id")) return new Response(null, {status:202});
  if (message.method === "initialize") return response(message.id, {
    protocolVersion: PROTOCOL, capabilities: { tools: { listChanged: false } },
    serverInfo: { name: "sene-guardian-owner", version: "1.0.0" },
    instructions: "Conector exclusivo del MUSE del propietario asociado al WhatsApp 3325142147. Puede consultar el inventario central de licencias de todos los negocios y las solicitudes de activación/renovación con sus códigos. No comparte estos datos con MUSE de clientes y no ejecuta acciones."
  });
  if (message.method === "ping") return response(message.id, {});
  if (message.method === "tools/list") return response(message.id, { tools: [
  {
    name: "list_owner_license_inventory",
    description: "Consulta el registro central de todos los negocios: licencias activas, vencidas y bloqueadas, fechas de vencimiento, renovaciones y estado de solicitudes. Exclusivo del MUSE propietario.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  },
  {
    name: "list_owner_license_requests",
    description: "Consulta solicitudes pendientes de licencia nueva y renovación, incluidos folio y código. Exclusivo del MUSE del propietario.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  }
] });
  if (message.method === "tools/call") {
    const params = message.params ?? {};
    if ((params.name !== "list_owner_license_requests" && params.name !== "list_owner_license_inventory") ||
        (params.arguments && Object.keys(params.arguments).length))
      return error(message.id, -32602, "Unknown tool or invalid arguments");
    if (params.name === "list_owner_license_inventory") {
      const inventory = await loadInventory();
      if (!inventory) return response(message.id, { content: [{type:"text", text:"El inventario central de licencias no está disponible."}], isError:true });
      return response(message.id, { content: [{type:"text", text:JSON.stringify(inventory)}], structuredContent:inventory, isError:false });
    }
    const events = await loadEvents();
    if (!events) return response(message.id, { content: [{type:"text", text:"Las solicitudes de licencia no están disponibles."}], isError:true });
    return response(message.id, { content: [{type:"text", text:JSON.stringify(events)}], structuredContent:{events}, isError:false });
  }
  return error(message.id, -32601, "Method not found");
}
