import { describe, expect, it } from "vitest";
import { MemoryQueue } from "../src/buffer.js";

describe("MemoryQueue", () => {
  it("drops oldest entries when configured", () => {
    const queue = new MemoryQueue<string>(20, "DROP_OLDEST");

    queue.enqueue("1234567890");
    queue.enqueue("abcdefghij");
    queue.enqueue("zzzz");

    expect(queue.drain()).toEqual(["abcdefghij", "zzzz"]);
  });

  it("throws in block caller mode", () => {
    const queue = new MemoryQueue<string>(10, "BLOCK_CALLER");

    queue.enqueue("12345");

    expect(() => queue.enqueue("6789012345")).toThrowError("ChronoTrace memory queue is full");
  });
});
