// The six fixtures green's suites read, parsed the way the CLI parses them.
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Opts } from "red/workflow";

const root = join(import.meta.dir, "../../test/fixtures");

function readFixture(file: string, overrides: Opts): Opts {
  const text = readFileSync(join(root, file), "utf8").replaceAll("WORKDIR", ".colors");
  return { ...(Bun.YAML.parse(text) as Opts), ...overrides };
}

export const fixture = (overrides: Opts = {}) => readFixture("colors.yml", overrides);
export const optout = (overrides: Opts = {}) => readFixture("optout.yml", overrides);
export const doFixture = (overrides: Opts = {}) => readFixture("colors-digitalocean.yml", overrides);
export const doOptout = (overrides: Opts = {}) => readFixture("optout-digitalocean.yml", overrides);
export const awsFixture = (overrides: Opts = {}) => readFixture("colors-aws.yml", overrides);
export const awsOptout = (overrides: Opts = {}) => readFixture("optout-aws.yml", overrides);
export const allFixtures = [fixture, optout, doFixture, doOptout, awsFixture, awsOptout];

export function tempWorkdir(prefix = "redis-red-test-"): string {
  return mkdtempSync(join(tmpdir(), prefix));
}

export const credentials = { credentials: { backup: { access_key_id: "AKIA", secret_access_key: "s" } } };
