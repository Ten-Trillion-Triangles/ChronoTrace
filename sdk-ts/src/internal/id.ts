export function randomHex(size: number): string {
  const bytes = new Uint8Array(size);
  globalThis.crypto.getRandomValues(bytes);
  const alphabet = "0123456789abcdef";
  let result = "";
  for (let i = 0; i < size; i++) {
    result += alphabet[bytes[i]! & 0x0f];
  }
  return result;
}

export function newTraceId(): string {
  return randomHex(32);
}

export function newSpanId(): string {
  return randomHex(16);
}
