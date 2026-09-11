import { afterEach, describe, expect, test } from "bun:test";
import { existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { runtime, type ExecOptions, type ExecResult } from "red/runtime";
import { PRESERVE_JINJA_DELIMITERS } from "red/scaffold";
import * as storage from "../src/storage.ts";
import { awsFixture, awsOptout, credentials, fixture, tempWorkdir } from "./fixtures.ts";

const originalExec = runtime.exec;
afterEach(() => { runtime.exec = originalExec; });

// A tofu and aws CLI that answer the way a fresh account would: no state, no
// bucket, an apply that mints the pair.
function cli(overrides: Partial<Record<string, ExecResult>> = {}) {
  const calls: Array<[string[], ExecOptions]> = [];
  runtime.exec = async (args: string[], options: ExecOptions = {}): Promise<ExecResult> => {
    calls.push([args, options]);
    const key = args.slice(0, 3).join(" ");
    if (overrides[key]) return overrides[key]!;
    if (key === "tofu state list") return { exit: 1, out: "", err: "Error: No state file was found!" };
    if (key === "tofu show -json") return { exit: 0, out: '{"values":{"root_module":{"resources":[]}}}', err: "" };
    if (key === "aws s3api head-bucket") return { exit: 254, out: "", err: "An error occurred (404) when calling the HeadBucket operation: Not Found" };
    if (key === "tofu output -json") return { exit: 0, out: '{"credentials":{"sensitive":true,"value":{"backup":{"access_key_id":"AKIA","secret_access_key":"s"}}}}', err: "" };
    return { exit: 0, out: "", err: "" };
  };
  return calls;
}

describe("storage", () => {
  test("the managed gate is the one key", () => {
    expect(storage.managed(awsFixture())).toBe(true);
    expect(storage.managed(awsOptout())).toBe(false);
    expect(storage.managed(fixture())).toBe(false);
    expect(storage.managed(fixture({ "redis-storage-managed": "true" }))).toBe(false);
  });

  test("the stage renders one template and no credential", () => {
    const opts = awsFixture({ "red/event": "build", [storage.credentialsKey]: credentials });
    const specs = storage.specs(opts);
    expect(specs.length).toBe(1);
    const [spec] = specs;
    expect(String(spec!.target).endsWith("/redis-aws-fixture/redis-storage/main.tf")).toBe(true);
    expect(spec!.template?.name).toBe("storage/main.tf");
    expect((spec!.data ?? {})[storage.credentialsKey]).toBeUndefined();
    expect(spec!.opts).toBe(PRESERVE_JINJA_DELIMITERS);
  });

  test("the credential env carries the operator names", () => {
    expect(storage.credentialEnv({ [storage.credentialsKey]: { credentials: { backup: { access_key_id: "fixture-id", secret_access_key: "fixture-secret" } } } }))
      .toEqual({ COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID: "fixture-id", COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY: "fixture-secret" });
    // A missing or blank pair is refused, never an empty variable.
    expect(() => storage.credentialEnv({})).toThrow("managed storage credentials unavailable");
    expect(() => storage.credentialEnv({ [storage.credentialsKey]: { credentials: { backup: { access_key_id: "", secret_access_key: "s" } } } })).toThrow();
  });

  test("aws env overlays only what is set", () => {
    expect(storage.awsEnv(awsFixture())).toEqual({});
    expect(storage.awsEnv(awsFixture({ "aws-access-key-id": "AKIA", "aws-secret-access-key": "s", "aws-session-token": "t" })))
      .toEqual({ AWS_ACCESS_KEY_ID: "AKIA", AWS_SECRET_ACCESS_KEY: "s", AWS_SESSION_TOKEN: "t" });
    expect(storage.awsEnv({ "aws-access-key-id": "AKIA", "aws-secret-access-key": "" })).toEqual({ AWS_ACCESS_KEY_ID: "AKIA" });
  });

  test("the step is a no-op unless managed", async () => {
    runtime.exec = async () => { throw new Error("must not run"); };
    expect((await storage.step(awsOptout({ "red/event": "create" })))["red/exit"]).toBe(0);
    expect(await storage.readCredentials(awsOptout())).toEqual(awsOptout());
  });

  test("a create runs the preflight then tofu with the aws environment", async () => {
    const workdir = tempWorkdir();
    try {
      const calls = cli();
      const opts = awsFixture({ "red/event": "create", "aws-access-key-id": "AKIA", "aws-secret-access-key": "s", workdir });
      const result = await storage.step(opts);
      expect(result["red/exit"]).toBe(0);
      expect(calls.map(([args]) => args.slice(0, 2).join(" "))).toEqual([
        "tofu init", "tofu state", "tofu show", "aws s3api", "tofu init", "tofu apply", "tofu output",
      ]);
      for (const [, options] of calls) {
        expect(options.env).toEqual({ AWS_ACCESS_KEY_ID: "AKIA", AWS_SECRET_ACCESS_KEY: "s" });
        expect(options.cwd).toBe(join(workdir, "redis-aws-fixture", "redis-storage"));
      }
      expect(result[storage.credentialsKey]).toEqual({ credentials: { backup: { access_key_id: "AKIA", secret_access_key: "s" } } });
      expect(existsSync(join(workdir, "redis-aws-fixture", "redis-storage", "main.tf"))).toBe(true);
    } finally {
      rmSync(workdir, { recursive: true, force: true });
    }
  });

  test("a delete runs no preflight and removes the rendered stage", async () => {
    const workdir = tempWorkdir();
    try {
      const calls = cli();
      const result = await storage.step(awsFixture({ "red/event": "delete", workdir }));
      expect(result["red/exit"]).toBe(0);
      expect(calls.map(([args]) => args.slice(0, 2).join(" "))).toEqual(["tofu init", "tofu destroy"]);
      expect(existsSync(join(workdir, "redis-aws-fixture", "redis-storage", "main.tf"))).toBe(false);
    } finally {
      rmSync(workdir, { recursive: true, force: true });
    }
  });

  test("an owned address skips the bucket probe", async () => {
    const workdir = tempWorkdir();
    try {
      const calls = cli({
        "tofu state list": { exit: 0, out: 'aws_s3_bucket.application["backup"]\n', err: "" },
        "tofu show -json": { exit: 0, out: '{"values":{"root_module":{"resources":[{"address":"aws_s3_bucket.application[\\"backup\\"]","values":{"bucket":"redis-aws-fixture-backup"}}]}}}', err: "" },
      });
      expect((await storage.step(awsFixture({ "red/event": "create", workdir })))["red/exit"]).toBe(0);
      expect(calls.some(([args]) => args[0] === "aws")).toBe(false);
    } finally {
      rmSync(workdir, { recursive: true, force: true });
    }
  });

  test("a refused preflight is an error with no credential in the message", async () => {
    const workdir = tempWorkdir();
    try {
      // An existing bucket the stage does not own, a 403, and a broken state
      // all fail closed.
      for (const overrides of [
        { "aws s3api head-bucket": { exit: 0, out: "", err: "" } },
        { "aws s3api head-bucket": { exit: 254, out: "", err: "An error occurred (403) when calling the HeadBucket operation: Forbidden AKIAEXAMPLE" } },
        { "tofu state list": { exit: 1, out: "", err: "AKIAEXAMPLE leaked" } },
      ]) {
        cli(overrides);
        const r = await storage.step(awsFixture({ "red/event": "create", workdir }));
        expect(r["red/exit"]).toBe(1);
        expect(String(r["red/err"])).not.toContain("AKIA");
        expect(r["red/err"]).toBe("managed S3 storage failed; inspect bucket ownership, state access, and AWS permissions");
      }
    } finally {
      rmSync(workdir, { recursive: true, force: true });
    }
  });

  test("read-credentials reads the pair back and refuses a missing output", async () => {
    const workdir = tempWorkdir();
    try {
      const calls = cli();
      const read = await storage.readCredentials(awsFixture({ "red/event": "rehearse", workdir }));
      expect(storage.credentialEnv(read).COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID).toBe("AKIA");
      expect(calls.map(([args]) => args.slice(0, 2).join(" "))).toEqual(["tofu init", "tofu output"]);
      expect(existsSync(join(workdir, "redis-aws-fixture", "redis-storage", "backend.tf.json"))).toBe(true);
      cli({ "tofu output -json": { exit: 0, out: '{"credentials":{"sensitive":true,"value":{}}}', err: "" } });
      await expect(storage.readCredentials(awsFixture({ "red/event": "rehearse", workdir })))
        .rejects.toThrow(/converge storage before rehearsal/);
    } finally {
      rmSync(workdir, { recursive: true, force: true });
    }
  });
});
