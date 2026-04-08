import { instrumentSource } from "./instrumentation.js";

interface ViteTransformResult {
  code: string;
  map: null;
}

interface ViteLikePlugin {
  name: string;
  enforce: "pre";
  transform(code: string, id: string): ViteTransformResult | null;
}

export function createChronoTraceVitePlugin(): ViteLikePlugin {
  return {
    name: "chronotrace-instrumentation",
    enforce: "pre",
    transform(code: string, id: string): ViteTransformResult | null {
      if (!/\.[cm]?[jt]sx?$/.test(id)) {
        return null;
      }
      return {
        code: instrumentSource(code, id),
        map: null,
      };
    },
  };
}
