export function randomHex(size: number): string {
  const alphabet = "0123456789abcdef";
  let result = "";
  for (let index = 0; index < size; index += 1) {
    result += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return result;
}

export function newTraceId(): string {
  return randomHex(32);
}

export function newSpanId(): string {
  return randomHex(16);
}
