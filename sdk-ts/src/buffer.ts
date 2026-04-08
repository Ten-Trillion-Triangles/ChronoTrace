import { estimateBytes } from "./internal/size.js";
import type { OverflowStrategy } from "./config.js";

export class MemoryQueue<T> {
  private readonly items: Array<{ value: T; size: number }> = [];
  private usedBytes = 0;

  constructor(
    private readonly maxBytes: number,
    private readonly overflowStrategy: OverflowStrategy,
  ) {}

  enqueue(value: T): void {
    const size = typeof value === "string" ? value.length : estimateBytes(value);
    if (size > this.maxBytes) {
      throw new Error("ChronoTrace event exceeds queue capacity");
    }

    while (this.usedBytes + size > this.maxBytes) {
      if (this.overflowStrategy === "DROP_NEWEST") {
        return;
      }
      if (this.overflowStrategy === "BLOCK_CALLER") {
        throw new Error("ChronoTrace memory queue is full");
      }
      const removed = this.items.shift();
      if (!removed) {
        break;
      }
      this.usedBytes -= removed.size;
    }

    this.items.push({ value, size });
    this.usedBytes += size;
  }

  drain(): T[] {
    const values = this.items.map((item) => item.value);
    this.items.length = 0;
    this.usedBytes = 0;
    return values;
  }
}

export class RingBuffer<T> {
  private readonly queue: MemoryQueue<T>;

  constructor(maxEntries: number, overflowStrategy: "DROP_OLDEST" | "DROP_NEWEST") {
    this.queue = new MemoryQueue<T>(maxEntries * 1024, overflowStrategy);
  }

  push(value: T): void {
    this.queue.enqueue(value);
  }

  drain(): T[] {
    return this.queue.drain();
  }
}
