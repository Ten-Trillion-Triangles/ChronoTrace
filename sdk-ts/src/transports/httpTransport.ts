import type { IngestBatch } from "../generated/contracts.js";
import type { ChronoTransport } from "../transport.js";

export interface HttpTransportOptions {
  readonly url: string;
  readonly fetchImpl?: typeof fetch;
  readonly headers?: Record<string, string>;
}

export class HttpTransport implements ChronoTransport {
  constructor(private readonly options: HttpTransportOptions) {}

  async connect(): Promise<void> {}

  async send(batch: IngestBatch): Promise<void> {
    const fetchImpl = this.options.fetchImpl ?? globalThis.fetch;
    if (!fetchImpl) {
      throw new Error("Fetch is not available for ChronoTrace HTTP transport");
    }

    await fetchImpl(this.options.url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...this.options.headers
      },
      body: JSON.stringify(batch)
    });
  }

  async close(): Promise<void> {}

  isConnected(): boolean {
    return true;
  }
}
