import test from "node:test";
import assert from "node:assert/strict";
import { LICENSE_OWNER_WHATSAPP, resolveWhatsAppRecipient } from "../src/whatsapp-routing";

test("license requests always go exclusively to the owner, ignoring client number", () => {
  const route = resolveWhatsAppRecipient({ kind: "license_request", clientWhatsApp: "5512345678" });
  assert.deepEqual(route, { allowed: true, recipient: "3325142147", channel: "owner" });
  assert.equal(route.allowed && route.recipient, LICENSE_OWNER_WHATSAPP);
});

test("business information goes only to the configured client WhatsApp", () => {
  const route = resolveWhatsAppRecipient({ kind: "business_information", clientWhatsApp: "+52 33 1234 5678" });
  assert.deepEqual(route, { allowed: true, recipient: "523312345678", channel: "client" });
  assert.notEqual(route.allowed && route.recipient, LICENSE_OWNER_WHATSAPP);
});

test("business information is not redirected to the owner when client number is missing", () => {
  assert.deepEqual(resolveWhatsAppRecipient({ kind: "business_information" }), {
    allowed: false, reason: "client_whatsapp_not_configured"
  });
});

test("invalid client WhatsApp is rejected without fallback", () => {
  assert.deepEqual(resolveWhatsAppRecipient({ kind: "business_information", clientWhatsApp: "abc" }), {
    allowed: false, reason: "invalid_client_whatsapp"
  });
});
