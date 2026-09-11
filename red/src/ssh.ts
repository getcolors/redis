// Application SSH arguments; colors-compute owns key lifecycle.
import { resolve } from "node:path";
import type { Opts } from "red/workflow";
import { keyMode } from "colors-compute-red";

export const buildPlaceholderDir = "/home/build-placeholder/.ssh";

export function renderedOnly(opts: Opts): boolean {
  return opts["red/event"] === "build" || Boolean(opts["red/dry-run"]);
}

// In keygen mode a build or dry-run names the placeholder identity rather
// than anything under the operator's home; a real event keeps whatever the
// library recorded.
export function withMachineKey(opts: Opts): Opts {
  if (keyMode(opts).mode !== "managed") return opts;
  const path = renderedOnly(opts) ? `${buildPlaceholderDir}/${opts.profile}` : opts["ssh-private-key-path"];
  if (path === undefined || path === null) return opts;
  return { ...opts, "ssh-private-key-path": path, "ssh-public-key-path": `${path}.pub` };
}

export function identityArgs(opts: Opts): string[] {
  const path = opts["ssh-private-key-path"];
  return path ? ["-i", String(path), "-o", "IdentitiesOnly=yes"] : [];
}

export function privateKeyPath(opts: Opts): string {
  if (!opts["ssh-private-key-path"]) throw new Error("deployment SSH identity unavailable");
  return resolve(String(opts["ssh-private-key-path"]));
}
