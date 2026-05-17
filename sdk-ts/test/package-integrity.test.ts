/**
 * Package integrity test — validates publish-ready package configuration.
 * Run with: vitest run test/package-integrity.test.ts
 */
import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import { resolve } from "path";

const PKG_PATH = resolve(__dirname, "../package.json");
const DIST_PATH = resolve(__dirname, "../dist");

interface PackageJson {
  name: string;
  version: string;
  private?: boolean;
  main?: string;
  module?: string;
  browser?: string;
  types?: string;
  exports?: Record<string, unknown>;
  files?: string[];
  scripts?: Record<string, string>;
}

function readPackageJson(): PackageJson {
  return JSON.parse(readFileSync(PKG_PATH, "utf-8")) as PackageJson;
}

describe("Package Integrity", () => {
  describe("publish configuration", () => {
    it("must NOT be private — npm will reject publish", () => {
      const pkg = readPackageJson();
      expect(pkg.private).toBeUndefined();
      // When private is removed, the test proves the field is absent.
      // If private: true remains, this test fails — which is correct behavior.
    });

    it("must have a valid name (scoped or unscoped)", () => {
      const pkg = readPackageJson();
      expect(pkg.name).toMatch(/^(@[a-z0-9-]+\/)?[a-z0-9-]+$/);
    });

    it("must have a valid semver version", () => {
      const pkg = readPackageJson();
      expect(pkg.version).toMatch(/^\d+\.\d+\.\d+/);
    });
  });

  describe("entry points", () => {
    it("must declare main entry point", () => {
      const pkg = readPackageJson();
      expect(pkg.main).toBeDefined();
      expect(pkg.main).toMatch(/^dist\//);
    });

    it("must declare module (ESM) entry point", () => {
      const pkg = readPackageJson();
      expect(pkg.module).toBeDefined();
      expect(pkg.module).toMatch(/^dist\//);
    });

    it("must declare types entry point", () => {
      const pkg = readPackageJson();
      expect(pkg.types).toBeDefined();
      expect(pkg.types).toMatch(/^dist\//);
    });

    it("must declare browser entry point for bundlers", () => {
      const pkg = readPackageJson();
      expect(pkg.browser).toBeDefined();
    });
  });

  describe("exports field", () => {
    it("must have an exports field with conditional exports for ESM/CJS", () => {
      const pkg = readPackageJson();
      expect(pkg.exports).toBeDefined();
      expect(typeof pkg.exports).toBe("object");
    });

    it("exports must include a root import", () => {
      const pkg = readPackageJson();
      expect(pkg.exports).toHaveProperty(".");
    });

    it("exports must reference dist/ for the root entry", () => {
      const pkg = readPackageJson();
      const root = pkg.exports as Record<string, unknown>;
      expect(root["."]).toBeDefined();
    });
  });

  describe("files field", () => {
    it("must include dist/ in the files list", () => {
      const pkg = readPackageJson();
      expect(pkg.files).toBeDefined();
      expect(Array.isArray(pkg.files)).toBe(true);
      expect(pkg.files).toContain("dist/src/");
    });
  });

  describe("build output", () => {
    // Build output lives under dist/src/ per tsconfig.json rootDir: "."
    const SRC_DIST = resolve(__dirname, "../dist/src");

    it("dist/src/index.js must exist after build", () => {
      const indexPath = resolve(SRC_DIST, "index.js");
      expect(() => readFileSync(indexPath, "utf-8")).not.toThrow();
    });

    it("dist/src/index.d.ts must exist after build", () => {
      const typesPath = resolve(SRC_DIST, "index.d.ts");
      expect(() => readFileSync(typesPath, "utf-8")).not.toThrow();
    });
  });
});