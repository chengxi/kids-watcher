import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["**/*.test.{ts,mjs}"],
    // .claude holds git worktrees: full copies of this repository whose
    // packages are unbuilt, so collecting their tests fails on imports
    // that resolve here. A fresh clone has no .claude, so CI never saw it.
    exclude: ["**/node_modules/**", "**/dist/**", "**/.claude/**"],
  },
});
