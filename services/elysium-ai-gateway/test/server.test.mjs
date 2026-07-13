import assert from "node:assert/strict";
import test from "node:test";

import { createGatewayServer } from "../src/server.mjs";

const config = Object.freeze({
  apiKey: "provider-key-not-used-in-test",
  gatewayToken: "gateway-token",
  model: "test-model",
  requestsPerMinute: 10
});

async function withServer(overrides, run) {
  const server = createGatewayServer(config, {
    startAgentTurn: async () => ({ responseId: "resp_1", text: "Ready", toolCalls: [] }),
    continueAgentTurn: async () => ({ responseId: "resp_2", text: "Complete", toolCalls: [] }),
    ...overrides
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    await run(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

test("health check does not require a credential", async () => {
  await withServer({}, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/healthz`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: "ok", service: "elysium-ai-gateway", model: "test-model" });
  });
});

test("agent turn requires gateway auth and validates the safety identifier", async () => {
  await withServer({}, async (baseUrl) => {
    const unauthorized = await fetch(`${baseUrl}/v1/agent/turn`, { method: "POST", body: "{}" });
    assert.equal(unauthorized.status, 401);

    const invalid = await fetch(`${baseUrl}/v1/agent/turn`, {
      method: "POST",
      headers: { authorization: "Bearer gateway-token", "content-type": "application/json" },
      body: JSON.stringify({ message: "Hello", safetyIdentifier: "has a space" })
    });
    assert.equal(invalid.status, 400);
  });
});

test("agent turn passes only validated data into the agent", async () => {
  let received;
  await withServer({
    startAgentTurn: async (value) => {
      received = value;
      return { responseId: "resp_1", text: "Ready", toolCalls: [] };
    }
  }, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/v1/agent/turn`, {
      method: "POST",
      headers: { authorization: "Bearer gateway-token", "content-type": "application/json" },
      body: JSON.stringify({ message: "  Inspect status  ", context: ["runtime=debian"], safetyIdentifier: "honor-magic-v2" })
    });
    assert.equal(response.status, 200);
  });
  assert.equal(received.message, "Inspect status");
  assert.deepEqual(received.context, ["runtime=debian"]);
  assert.equal(received.safetyIdentifier, "honor-magic-v2");
});
