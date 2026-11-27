/**
 * Política central de destinatarios WhatsApp para SENE GUARDIÁN.
 * No envía mensajes: únicamente resuelve el destino permitido.
 */
export const LICENSE_OWNER_WHATSAPP = "3325142147";

export type WhatsAppDelivery =
  | { kind: "license_request"; clientWhatsApp?: string | null }
  | { kind: "business_information"; clientWhatsApp?: string | null };

export type WhatsAppRoute =
  | { allowed: true; recipient: string; channel: "owner" | "client" }
  | { allowed: false; reason: "client_whatsapp_not_configured" | "invalid_client_whatsapp" };

/**
 * Solicitudes/códigos de licencia nunca usan el número del cliente.
 * La información administrativa nunca recae en el número del propietario.
 */
export function resolveWhatsAppRecipient(delivery: WhatsAppDelivery): WhatsAppRoute {
  if (delivery.kind === "license_request") {
    return { allowed: true, recipient: LICENSE_OWNER_WHATSAPP, channel: "owner" };
  }

  const value = (delivery.clientWhatsApp ?? "").replace(/[\s()+-]/g, "");
  if (!value) return { allowed: false, reason: "client_whatsapp_not_configured" };
  if (!/^\d{10,15}$/.test(value)) return { allowed: false, reason: "invalid_client_whatsapp" };
  return { allowed: true, recipient: value, channel: "client" };
}
