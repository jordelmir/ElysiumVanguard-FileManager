/** In-memory fixed-window limiter. Replace with Redis when horizontally scaled. */
export class FixedWindowRateLimiter {
  constructor({ limit, windowMs = 60_000, now = () => Date.now() }) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.now = now;
    this.windows = new Map();
  }

  consume(subject) {
    const now = this.now();
    const current = this.windows.get(subject);
    if (!current || now >= current.resetAt) {
      this.windows.set(subject, { count: 1, resetAt: now + this.windowMs });
      return { allowed: true, retryAfterMs: 0 };
    }
    if (current.count >= this.limit) {
      return { allowed: false, retryAfterMs: Math.max(0, current.resetAt - now) };
    }
    current.count += 1;
    return { allowed: true, retryAfterMs: 0 };
  }
}
