/** Dedicated read-only MCP endpoint for the single owner MUSE.
 * It is authenticated with the central owner credential, never an installation token.
 * This connector alone can read license activation/renewal requests and the
 * list of active licenses. It never sees client business data (no workers,
 * salaries, inventory or other installations' data), and it executes no action.
 */
type OwnerEventsLoader = () => Promise<unknown[] | null>;
type OwnerActiveLicensesLoader = () => Promise<{ total: number; licenses: unknown[] } | null>;
const PROTOCOL = "2025-03-26";
const READ_ONLY = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const NO_ARGS = { type: "object", properties: {}, additionalProperties: false };
const TOOLS = [
  {
    name: "list_owner_license_requests",
    description: "Consulta solicitudes pendientes de licencia nueva y renovación, incluidos folio y código. Exclusivo del MUSE del propietario.",
    inputSchema: NO_ARGS,
    annotations: READ_ONLY,
  },
  {
    name: "list_owner_active_licenses",
    description: "Consulta las licencias activas: total y lista con folio, negocio, tipo (nueva o renovación) y fechas de vigencia. Sin códigos. Solo se cuentan las licencias cuya activación o renovación completada haya sido enviada por el productor; si no hay ninguna, el total es 0. Exclusivo del MUSE del propietario.",
    inputSchema: NO_ARGS,
    annotations: READ_ONLY,
  },
];
function response(id: unknown, result: unknown): Response {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, result }), {
    status: 200, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "mcp-protocol-version": PROTOCOL }
  });
}
function error(id: unknown, code: number, message: string): Response {
  return response(id, { error: { code, message } });
}
export async function handleOwnerMuseMcp(request: Request, loadEvents: OwnerEventsLoader, loadActiveLicenses?: OwnerActiveLicensesLoader): Promise<Response> {
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
    instructions: "Conector exclusivo del MUSE del propietario asociado al WhatsApp 3325142147. Puede consultar solicitudes de licencias nuevas y renovaciones, incluidos sus códigos, y la lista de licencias activas. No comparte estos datos con MUSE de clientes, no ve datos de clientes y no ejecuta acciones."
  });
  if (message.method === "ping") return response(message.id, {});
  if (message.method === "tools/list") return response(message.id, { tools: TOOLS });
  if (message.method === "tools/call") {
    const params = message.params ?? {};
    if ((params.arguments && Object.keys(params.arguments).length) ||
        !["list_owner_license_requests", "list_owner_active_licenses"].includes(params.name))
      return error(message.id, -32602, "Unknown tool or invalid arguments");
    if (params.name === "list_owner_active_licenses") {
      if (!loadActiveLicenses) return response(message.id, { content: [{type:"text", text:"Las licencias activas no están disponibles."}], isError:true });
      const result = await loadActiveLicenses();
      if (!result) return response(message.id, { content: [{type:"text", text:"Las licencias activas no están disponibles."}], isError:true });
      return response(message.id, { content: [{type:"text", text:JSON.stringify(result)}], structuredContent: result, isError:false });
    }
    const events = await loadEvents();
    if (!events) return response(message.id, { content: [{type:"text", text:"Las solicitudes de licencia no están disponibles."}], isError:true });
    return response(message.id, { content: [{type:"text", text:JSON.stringify(events)}], structuredContent:{events}, isError:false });
  }
  return error(message.id, -32601, "Method not found");
}
