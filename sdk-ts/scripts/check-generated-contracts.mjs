import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const target = resolve("src/generated/contracts.ts");
const content = readFileSync(target, "utf8");

if (!content.includes("GENERATED FROM chronotrace-contract")) {
  throw new Error(
    "sdk-ts/src/generated/contracts.ts is missing the generated-contract banner.",
  );
}

if (!content.includes("export interface IngestBatch")) {
  throw new Error(
    "sdk-ts/src/generated/contracts.ts does not contain the expected canonical contract types.",
  );
}

console.log("Generated contract placeholder is present.");

