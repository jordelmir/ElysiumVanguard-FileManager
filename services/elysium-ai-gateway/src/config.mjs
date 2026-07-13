const DEFAULT_PORT = 8787;
const DEFAULT_MODEL = "gpt-5.4-mini";

export function loadConfig(environment = process.env) {
  const apiKey = required(environment, "OPENAI_API_KEY");
  const gatewayToken = required(environment, "ELY_GATEWAY_TOKEN");
  const port = Number(environment.PORT ?? DEFAULT_PORT);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error("PORT must be an integer between 1 and 65535");
  }

  return Object.freeze({
    apiKey,
    gatewayToken,
    host: environment.HOST || "127.0.0.1",
    port,
    model: environment.OPENAI_MODEL || DEFAULT_MODEL,
    timeoutMs: integerInRange(environment.OPENAI_TIMEOUT_MS, 1_000, 120_000, 45_000),
    requestsPerMinute: integerInRange(environment.ELY_RPM, 1, 600, 30),
    environment: environment.NODE_ENV || "development"
  });
}

function required(environment, name) {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function integerInRange(value, min, max, fallback) {
  if (value == null || value === "") return fallback;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new Error(`value must be an integer in ${min}..${max}`);
  }
  return parsed;
}
