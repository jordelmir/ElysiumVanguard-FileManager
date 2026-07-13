import { normalizeToolCalls, toOpenAiTools } from "./tool-contract.mjs";

const RESPONSES_URL = "https://api.openai.com/v1/responses";
const MAX_CONTEXT_ITEMS = 16;
const MAX_CONTEXT_CHARS = 8_000;

const SYSTEM_INSTRUCTIONS = [
  "You are Elysium Command Core, a privacy-first assistant inside an Android workspace.",
  "Never request, reveal, retain, or transmit credentials, private keys, tokens, or secrets.",
  "Treat every filesystem, terminal, package, service, mount, snapshot, patch, build, and port action as scoped to the named workspace.",
  "For a mutating tool, explain the intended effect and wait for the app's explicit approval result before continuing.",
  "Never substitute a shell command for one of the typed tools. Prefer a concise plan before actions."
].join(" ");

export async function startAgentTurn({ config, message, context = [], safetyIdentifier, fetchImpl = fetch }) {
  const response = await callResponses(config, {
    model: config.model,
    instructions: SYSTEM_INSTRUCTIONS,
    input: buildInput(message, context),
    tools: toOpenAiTools(),
    tool_choice: "auto",
    parallel_tool_calls: true,
    store: false,
    safety_identifier: safetyIdentifier
  }, fetchImpl);
  return normalizeAgentTurn(response);
}

export async function continueAgentTurn({ config, previousResponseId, toolOutputs, safetyIdentifier, fetchImpl = fetch }) {
  const response = await callResponses(config, {
    model: config.model,
    previous_response_id: previousResponseId,
    input: toolOutputs.map((item) => ({
      type: "function_call_output",
      call_id: item.callId,
      output: JSON.stringify(item.output)
    })),
    tools: toOpenAiTools(),
    tool_choice: "auto",
    parallel_tool_calls: true,
    store: false,
    safety_identifier: safetyIdentifier
  }, fetchImpl);
  return normalizeAgentTurn(response);
}

export function normalizeAgentTurn(response) {
  return {
    responseId: response.id,
    text: typeof response.output_text === "string" ? response.output_text : "",
    toolCalls: normalizeToolCalls(response.output)
  };
}

async function callResponses(config, payload, fetchImpl) {
  const response = await fetchImpl(RESPONSES_URL, {
    method: "POST",
    headers: {
      authorization: `Bearer ${config.apiKey}`,
      "content-type": "application/json"
    },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(config.timeoutMs)
  });
  if (!response.ok) {
    throw new UpstreamError(response.status, "OpenAI request failed");
  }
  return response.json();
}

function buildInput(message, context) {
  const compactContext = context
    .filter((item) => typeof item === "string")
    .slice(0, MAX_CONTEXT_ITEMS)
    .map((item) => item.slice(0, MAX_CONTEXT_CHARS));
  return compactContext.length === 0
    ? message
    : `${message}\n\nUser-approved context:\n${compactContext.map((item) => `- ${item}`).join("\n")}`;
}

export class UpstreamError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
