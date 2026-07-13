/**
 * Development-only loopback gateway used to validate the Android approval
 * cycle without an OpenAI credential or outbound provider request.
 */
import { createServer } from "node:http";

const token = process.env.ELY_GATEWAY_TOKEN || "test-gateway-token-for-local-verification";
const port = Number(process.env.PORT || 8787);

function send(response, status, body) {
  const encoded = JSON.stringify(body);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(encoded),
    "cache-control": "no-store"
  });
  response.end(encoded);
}

createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/healthz") {
    send(response, 200, { status: "ok", service: "elysium-ai-gateway-mock", model: "mock-local" });
    return;
  }
  if (request.headers.authorization !== `Bearer ${token}`) {
    send(response, 401, { error: "unauthorized" });
    return;
  }
  if (request.method === "POST" && request.url === "/v1/agent/turn") {
    send(response, 200, {
      responseId: "resp_local_mock",
      text: "I prepared a bounded process inspection for your review.",
      toolCalls: [{
        callId: "call_local_inspect",
        name: "inspect_processes",
        argumentsJson: '{"workspace_id":"app","include_command_lines":false}',
        requiresApproval: false
      }]
    });
    return;
  }
  if (request.method === "POST" && request.url === "/v1/agent/continue") {
    for await (const _chunk of request) { /* consume bounded Android test payload */ }
    send(response, 200, {
      responseId: "resp_local_mock_done",
      text: "Process inspection completed locally and returned through the approved tool channel.",
      toolCalls: []
    });
    return;
  }
  send(response, 404, { error: "route not found" });
}).listen(port, "127.0.0.1", () => {
  console.info(`mock-agent-gateway listening on 127.0.0.1:${port}`);
});
