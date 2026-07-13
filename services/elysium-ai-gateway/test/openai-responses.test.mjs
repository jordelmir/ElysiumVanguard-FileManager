import assert from "node:assert/strict";
import test from "node:test";

import { continueAgentTurn, normalizeAgentTurn, startAgentTurn } from "../src/openai-responses.mjs";

const config = Object.freeze({ apiKey: "test-key", model: "test-model", timeoutMs: 5_000 });

test("normalizes text and typed tool calls from Responses API output", () => {
  assert.deepEqual(normalizeAgentTurn({
    id: "resp_123",
    output_text: "I will inspect the terminal.",
    output: [{ type: "function_call", call_id: "call_123", name: "read_terminal", arguments: '{"session_id":"terminal-1"}' }]
  }), {
    responseId: "resp_123",
    text: "I will inspect the terminal.",
    toolCalls: [{
      callId: "call_123",
      name: "read_terminal",
      argumentsJson: '{"session_id":"terminal-1"}',
      requiresApproval: false
    }]
  });
});

test("starts a non-persistent Responses turn without putting credentials in the payload", async () => {
  let captured;
  const fetchImpl = async (_url, options) => {
    captured = options;
    return { ok: true, json: async () => ({ id: "resp_1", output_text: "Ready", output: [] }) };
  };

  await startAgentTurn({ config, message: "Check workspace", context: ["workspace=main"], safetyIdentifier: "device-1", fetchImpl });

  assert.equal(captured.headers.authorization, "Bearer test-key");
  const payload = JSON.parse(captured.body);
  assert.equal(payload.store, false);
  assert.equal(payload.safety_identifier, "device-1");
  assert.equal(payload.input.includes("test-key"), false);
  assert.equal(payload.tools.length, 12);
});

test("continues only from structured tool outputs", async () => {
  let payload;
  const fetchImpl = async (_url, options) => {
    payload = JSON.parse(options.body);
    return { ok: true, json: async () => ({ id: "resp_2", output_text: "Verified", output: [] }) };
  };

  await continueAgentTurn({
    config,
    previousResponseId: "resp_1",
    toolOutputs: [{ callId: "call_1", output: { status: "ok" } }],
    safetyIdentifier: "device-1",
    fetchImpl
  });

  assert.equal(payload.previous_response_id, "resp_1");
  assert.deepEqual(payload.input, [{ type: "function_call_output", call_id: "call_1", output: '{"status":"ok"}' }]);
});
