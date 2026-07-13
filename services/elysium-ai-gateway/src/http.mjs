const MAX_JSON_BYTES = 256 * 1024;

export async function readJson(request, maxBytes = MAX_JSON_BYTES) {
  const declared = Number(request.headers["content-length"] || 0);
  if (!Number.isFinite(declared) || declared < 0 || declared > maxBytes) {
    throw new HttpError(413, "request body exceeds the allowed limit");
  }
  const chunks = [];
  let received = 0;
  for await (const chunk of request) {
    received += chunk.length;
    if (received > maxBytes) throw new HttpError(413, "request body exceeds the allowed limit");
    chunks.push(chunk);
  }
  if (received === 0) throw new HttpError(400, "request body is required");
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new HttpError(400, "request body must be valid JSON");
  }
}

export function sendJson(response, status, body, extraHeaders = {}) {
  const encoded = JSON.stringify(body);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(encoded),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    ...extraHeaders
  });
  response.end(encoded);
}

export class HttpError extends Error {
  constructor(status, message, headers = {}) {
    super(message);
    this.status = status;
    this.headers = headers;
  }
}
