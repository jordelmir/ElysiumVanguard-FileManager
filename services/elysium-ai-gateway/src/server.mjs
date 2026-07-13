import { createHash, randomUUID } from "node:crypto";
import { createServer as createHttpServer } from "node:http";
import { fileURLToPath } from "node:url";

import { isAuthorized } from "./auth.mjs";
import { loadConfig } from "./config.mjs";
import { HttpError, readJson, sendJson } from "./http.mjs";
import { continueAgentTurn, startAgentTurn, UpstreamError } from "./openai-responses.mjs";
import { FixedWindowRateLimiter } from "./rate-limit.mjs";

const MAX_MESSAGE_CHARS = 12_000;
const MAX_CONTEXT_ITEMS = 16;
const MAX_CONTEXT_CHARS = 8_000;
const MAX_TOOL_OUTPUTS = 16;
const MAX_TOOL_OUTPUT_BYTES = 32 * 1024;
const SAFETY_IDENTIFIER = /^[A-Za-z0-9._-]{1,128}$/;
const RESPONSE_IDENTIFIER = /^[A-Za-z0-9._-]{1,256}$/;
const CALL_IDENTIFIER = /^[A-Za-z0-9._-]{1,256}$/;

/**
 * The only component permitted to use OPENAI_API_KEY. Android only receives a
 * separately scoped ELY_GATEWAY_TOKEN and executes user-approved typed tools.
 */
export function createGatewayServer(config, dependencies = {}) {
  const limiter = dependencies.limiter ?? new FixedWindowRateLimiter({ limit: config.requestsPerMinute });
  const agent = {
    start: dependencies.startAgentTurn ?? startAgentTurn,
    continue: dependencies.continueAgentTurn ?? continueAgentTurn
  };
  const audit = dependencies.audit ?? (() => {});

  return createHttpServer(async (request, response) => {
    const startedAt = Date.now();
    const requestId = randomUUID();
    const route = request.url?.split("?")[0] ?? "";
    let status = 500;
    let toolCount = 0;

    try {
      if (request.method === "GET" && route === "/healthz") {
        status = 200;
        sendJson(response, status, {
          status: "ok",
          service: "elysium-ai-gateway",
          model: config.model
        }, requestHeaders(requestId));
        return;
      }

      if (request.method !== "POST" || (route !== "/v1/agent/turn" && route !== "/v1/agent/continue")) {
        throw new HttpError(404, "route not found");
      }

      const authorization = firstHeader(request.headers.authorization);
      if (!isAuthorized(authorization, config.gatewayToken)) {
        throw new HttpError(401, "unauthorized");
      }

      const rate = limiter.consume(subjectFor(authorization));
      if (!rate.allowed) {
        throw new HttpError(429, "rate limit exceeded", {
          "retry-after": String(Math.max(1, Math.ceil(rate.retryAfterMs / 1_000)))
        });
      }

      const body = await readJson(request);
      const safetyIdentifier = requireSafetyIdentifier(body.safetyIdentifier);
      const result = route === "/v1/agent/turn"
        ? await agent.start({
          config,
          message: requireMessage(body.message),
          context: requireContext(body.context),
          safetyIdentifier
        })
        : await agent.continue({
          config,
          previousResponseId: requireIdentifier(body.previousResponseId, "previousResponseId", RESPONSE_IDENTIFIER),
          toolOutputs: requireToolOutputs(body.toolOutputs),
          safetyIdentifier
        });

      toolCount = Array.isArray(result.toolCalls) ? result.toolCalls.length : 0;
      status = 200;
      sendJson(response, status, result, requestHeaders(requestId));
    } catch (error) {
      const normalized = normalizeError(error);
      status = normalized.status;
      sendJson(response, status, {
        error: normalized.message,
        requestId
      }, { ...requestHeaders(requestId), ...normalized.headers });
    } finally {
      audit({
        requestId,
        route,
        method: request.method,
        status,
        durationMs: Date.now() - startedAt,
        toolCount
      });
    }
  });
}

function requestHeaders(requestId) {
  return { "x-request-id": requestId };
}

function firstHeader(value) {
  return Array.isArray(value) ? value[0] : value;
}

function subjectFor(authorization) {
  return createHash("sha256").update(authorization ?? "").digest("hex").slice(0, 24);
}

function requireMessage(value) {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > MAX_MESSAGE_CHARS) {
    throw new HttpError(400, `message must be a non-empty string up to ${MAX_MESSAGE_CHARS} characters`);
  }
  return value.trim();
}

function requireContext(value) {
  if (value == null) return [];
  if (!Array.isArray(value) || value.length > MAX_CONTEXT_ITEMS || value.some((item) => typeof item !== "string" || item.length > MAX_CONTEXT_CHARS)) {
    throw new HttpError(400, "context must be an array of bounded strings");
  }
  return value;
}

function requireSafetyIdentifier(value) {
  return requireIdentifier(value, "safetyIdentifier", SAFETY_IDENTIFIER);
}

function requireIdentifier(value, field, expression) {
  if (typeof value !== "string" || !expression.test(value)) {
    throw new HttpError(400, `${field} has an invalid format`);
  }
  return value;
}

function requireToolOutputs(value) {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_TOOL_OUTPUTS) {
    throw new HttpError(400, "toolOutputs must contain one to sixteen results");
  }
  return value.map((item) => {
    if (!item || typeof item !== "object") {
      throw new HttpError(400, "each tool output must be an object");
    }
    const callId = requireIdentifier(item.callId, "toolOutputs.callId", CALL_IDENTIFIER);
    if (!(typeof item.output === "string" || (item.output && typeof item.output === "object"))) {
      throw new HttpError(400, "toolOutputs.output must be a string or object");
    }
    if (Buffer.byteLength(JSON.stringify(item.output)) > MAX_TOOL_OUTPUT_BYTES) {
      throw new HttpError(413, "tool output exceeds the allowed limit");
    }
    return { callId, output: item.output };
  });
}

function normalizeError(error) {
  if (error instanceof HttpError) {
    return { status: error.status, message: error.message, headers: error.headers ?? {} };
  }
  if (error instanceof UpstreamError) {
    return {
      status: error.status === 401 || error.status === 403 ? 502 : 503,
      message: "AI provider is temporarily unavailable",
      headers: {}
    };
  }
  if (error?.name === "TimeoutError" || error?.name === "AbortError") {
    return { status: 504, message: "AI provider timed out", headers: {} };
  }
  return { status: 500, message: "internal gateway error", headers: {} };
}

export function startGateway(config = loadConfig()) {
  const server = createGatewayServer(config, {
    audit: (event) => console.info(JSON.stringify({ event: "gateway_request", ...event }))
  });
  server.listen(config.port, config.host, () => {
    console.info(`elysium-ai-gateway listening on ${config.host}:${config.port}`);
  });
  return server;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  startGateway();
}
