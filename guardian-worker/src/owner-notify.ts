/**
 * Owner notification (push, no polling): when a license event is stored,
 * send ONE email to the owner Muse mailbox via Brevo (API v3). The email
 * is only a knock on the door: it never carries the activation code, the
 * phone number or the holder name, and the owner Muse verifies it with
 * its own read-only owner tools before telling the owner anything.
 *
 * Rules (design diseno-aviso-guardian-2026-11-27.md, sections 3-4):
 * - Only activation.requested / renewal.requested / activation.completed /
 *   renewal.completed / transfer.completed notify. license.synced is silent.
 * - One email per event: KV key notify:v1:{event_id} is checked first and
 *   written (TTL 7 days) only after a successful send.
 * - Best-effort: this function NEVER throws. A Brevo failure, a timeout
 *   (5 s) or missing secrets only logs a warning and returns false; the
 *   license event is already stored and the delivery response is untouched.
 * - If any of GUARDIAN_NOTIFY_BREVO_KEY / GUARDIAN_NOTIFY_FROM /
 *   GUARDIAN_NOTIFY_TO is missing or empty, nothing is sent.
 */
export interface OwnerNotifyEnv {
  GUARDIAN_DATA: KVNamespace;
  GUARDIAN_NOTIFY_BREVO_KEY?: string;
  GUARDIAN_NOTIFY_FROM?: string;
  GUARDIAN_NOTIFY_TO?: string;
}

/** The license-event fields validated by validEvent() in owner-delivery.ts.
 * Only these fields are used. The activation code, whatsapp and holder_name
 * are deliberately NOT part of this type: they must never reach the email.
 * request_type, amount_cents and currency are optional: a device transfer
 * (transfer.completed) carries none of them, and its valid_until is null
 * when the business has not activated yet. */
export type OwnerNotifyEvent = {
  event_id: string;
  event_type: string;
  folio: string;
  business_name: string;
  request_type?: "activation" | "renewal";
  amount_cents?: number;
  currency?: string;
  created_at: string;
  expires_at?: string;
  valid_from?: string;
  valid_until?: string | null;
};

const BREVO_SEND_URL = "https://api.brevo.com/v3/smtp/email";
const NOTIFY_KEY_PREFIX = "notify:v1:";
const NOTIFY_TTL_SECONDS = 60 * 60 * 24 * 7; // 7 days
const NOTIFY_TIMEOUT_MS = 5000;

const NOTIFIABLE_EVENT_TYPES = [
  "activation.requested",
  "renewal.requested",
  "activation.completed",
  "renewal.completed",
  "transfer.completed",
];

function isCompletedEvent(eventType: string): boolean {
  return eventType === "activation.completed" || eventType === "renewal.completed";
}

function typeLabel(requestType: "activation" | "renewal" | undefined): string {
  return requestType === "renewal" ? "renovación" : "nueva";
}

function amountLabel(event: OwnerNotifyEvent): string {
  return `$${((event.amount_cents ?? 0) / 100).toFixed(2)} ${event.currency ?? "MXN"}`;
}

function buildEmail(event: OwnerNotifyEvent): { subject: string; textContent: string } {
  if (event.event_type === "transfer.completed") {
    // A device transfer: no code, no phone number, no holder name and no
    // installation ids ever reach the email — only folio, business and
    // the validity date (or "sin fecha" when not activated yet).
    return {
      subject: `[SENE GUARDIÁN] Cambio de dispositivo (${event.folio})`,
      textContent: [
        "Se cambió el dispositivo vinculado a la licencia.",
        "",
        `Folio: ${event.folio}`,
        `Negocio: ${event.business_name}`,
        `Vence: ${event.valid_until ?? "sin fecha"}`,
        "",
        "Verifica con tus herramientas de propietario antes de avisar.",
      ].join("\n"),
    };
  }
  const type = typeLabel(event.request_type);
  if (isCompletedEvent(event.event_type)) {
    return {
      subject: `[SENE GUARDIÁN] Licencia activa (${event.folio})`,
      textContent: [
        "La licencia quedó activa.",
        "",
        `Folio: ${event.folio}`,
        `Negocio: ${event.business_name}`,
        `Tipo: ${type}`,
        `Vigente desde: ${event.valid_from ?? ""}`,
        `Vence: ${event.valid_until ?? ""}`,
        "",
        "Verifica con tus herramientas de propietario antes de avisar.",
      ].join("\n"),
    };
  }
  return {
    subject: `[SENE GUARDIÁN] Solicitud nueva de licencia (${event.folio})`,
    textContent: [
      "Llegó una solicitud nueva de licencia.",
      "",
      `Folio: ${event.folio}`,
      `Negocio: ${event.business_name}`,
      `Tipo: ${type}`,
      `Monto: ${amountLabel(event)}`,
      `La solicitud vence: ${event.expires_at ?? ""}`,
      "",
      "Verifica con tus herramientas de propietario antes de avisar.",
    ].join("\n"),
  };
}

/**
 * Send the owner notification email for one stored license event.
 * Returns true only when Brevo accepted the email (2xx) and the
 * idempotency key was written. Never throws.
 */
export async function notifyOwnerMuse(env: OwnerNotifyEnv, event: OwnerNotifyEvent): Promise<boolean> {
  try {
    if (!NOTIFIABLE_EVENT_TYPES.includes(event.event_type)) return false;
    const brevoKey = env.GUARDIAN_NOTIFY_BREVO_KEY ?? "";
    const from = env.GUARDIAN_NOTIFY_FROM ?? "";
    const to = env.GUARDIAN_NOTIFY_TO ?? "";
    if (!brevoKey || !from || !to) {
      console.warn("owner notify skipped: notification secrets are not configured");
      return false;
    }
    const notifyKey = NOTIFY_KEY_PREFIX + event.event_id;
    if (await env.GUARDIAN_DATA.get(notifyKey)) return false;
    const { subject, textContent } = buildEmail(event);
    const response = await fetch(BREVO_SEND_URL, {
      method: "POST",
      headers: {
        "api-key": brevoKey,
        "content-type": "application/json",
        accept: "application/json",
      },
      body: JSON.stringify({
        sender: { email: from },
        to: [{ email: to }],
        subject,
        textContent,
      }),
      signal: AbortSignal.timeout(NOTIFY_TIMEOUT_MS),
    });
    if (!response.ok) {
      console.warn(`owner notify failed: Brevo returned HTTP ${response.status}`);
      return false;
    }
    await env.GUARDIAN_DATA.put(notifyKey, "sent", { expirationTtl: NOTIFY_TTL_SECONDS });
    return true;
  } catch (error) {
    console.warn("owner notify failed:", error instanceof Error ? error.message : String(error));
    return false;
  }
}
