import type { IngestBatch } from "../generated/contracts.js";
import type { ChronoTransport } from "../transport.js";

const DEFAULT_MAX_RETRIES = 3;
const BASE_DELAY_MS = 100;

export interface HttpTransportOptions {
  readonly url: string;
  readonly fetchImpl?: typeof fetch;
  readonly headers?: Record<string, string>;
  /** Maximum retry attempts on 503 responses (default: 3) */
  readonly maxRetries?: number;
}

export class HttpTransport implements ChronoTransport {
  private readonly maxRetries: number;

  constructor(private readonly options: HttpTransportOptions) {
    this.maxRetries = options.maxRetries ?? DEFAULT_MAX_RETRIES;
  }

  async connect(): Promise<void> {}

  async send(batch: IngestBatch): Promise<void> {
    const fetchImpl = this.options.fetchImpl ?? globalThis.fetch;
    if (!fetchImpl) {
      throw new Error("Fetch is not available for ChronoTrace HTTP transport");
    }

    let lastError: Error;

    for (let attempt = 0; attempt <= this.maxRetries; attempt++) {
      try {
        const response = await fetchImpl(this.options.url, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            ...this.options.headers,
          },
          body: JSON.stringify(batch),
        });

        if (!response.ok) {
          // Only retry on 503 Service Unavailable
          if (response.status === 503 && attempt < this.maxRetries) {
            const delayMs = BASE_DELAY_MS * Math.pow(2, attempt);
            await sleep(delayMs);
            continue;
          }
          // Non-503 or exhausted retries — throw
          throw new Error(`HTTP ${response.status} ${response.statusText}`);
        }
        return; // success
      } catch (error) {
        lastError = error as Error;

        // Check if it's a 503 response (thrown as Error with status property)
        const err = error as Error & { status?: number; response?: { status?: number } };
        const is503 =
          err.status === 503 ||
          (err.response?.status === 503) ||
          (error instanceof Error && error.message.includes("503"));

        if (is503 && attempt < this.maxRetries) {
          const delayMs = BASE_DELAY_MS * Math.pow(2, attempt);
          await sleep(delayMs);
          continue;
        }

        // Non-retryable error or retries exhausted
        throw lastError;
      }
    }

    throw lastError ?? new Error("HttpTransport send failed");
  }

  async close(): Promise<void> {}

  isConnected(): boolean {
    return true;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
