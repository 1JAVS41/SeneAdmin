import { strict as assert } from "node:assert";
import { test } from "node:test";
import { handleOwnerDelivery } from "../src/owner-delivery";

test("owner delivery rejects requests when bridge secrets are absent", async () => {
  const request = new Request("https://guardian.test/v1/guardian/owner-events", { method: "POST" });
  const response = await handleOwnerDelivery(request, { GUARDIAN_DATA: {} as any, GUARDIAN_DELIVERY_ENC_KEY: "", GUARDIAN_DELIVERY_HMAC_KEY: "" });
  assert.equal(response.status, 503);
});
