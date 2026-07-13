import { timingSafeEqual } from "node:crypto";

/** Constant-time comparison for the gateway credential, never the OpenAI key. */
export function isAuthorized(header, gatewayToken) {
  const expected = Buffer.from(`Bearer ${gatewayToken}`);
  const received = Buffer.from(header || "");
  return received.length === expected.length && timingSafeEqual(received, expected);
}
