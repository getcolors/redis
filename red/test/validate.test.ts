import { describe, expect, test } from "bun:test";
import * as validate from "../src/validate.ts";
import { allFixtures, awsFixture, awsOptout, doOptout, fixture, optout } from "./fixtures.ts";

describe("validate", () => {
  test("application fixtures and backends", () => {
    for (const f of allFixtures) {
      const opts = f();
      expect(validate.stateErrors({ ...opts, "redis-storage-managed": opts["redis-storage-managed"] === true })).toEqual([]);
    }
  });

  test("image, port and backup policy", () => {
    const cases: Array<[string, unknown]> = [
      ["redis-image", "redis:latest"], ["redis-port", 0], ["redis-port", 65536],
      ["redis-backup-retention-days", 0], ["redis-backup-max-age-hours", -1],
      ["redis-backup-r2-endpoint", "http://example.test"], ["provider-backend", "local"],
    ];
    for (const [key, value] of cases) {
      expect(validate.stateErrors(fixture({ [key]: value })).length).toBeGreaterThan(0);
    }
  });

  test("profile overlay is refused", () => {
    expect(validate.envErrors({ COLORS_PAR_PROFILE: "wrong" }).length).toBe(1);
    expect(validate.envErrors({})).toEqual([]);
  });

  test("credentials belong to their lifecycle", () => {
    expect(validate.secretErrors(fixture(), "create").length).toBe(2);
    expect(validate.secretErrors(fixture(), "delete")).toEqual([]);
    // An operator-owned bucket on AWS still needs the operator's pair.
    expect(validate.secretErrors(awsOptout(), "create").length).toBe(2);
    // A managed bucket needs nothing from the environment: the pair is a
    // stage output.
    expect(validate.secretErrors(awsFixture(), "create")).toEqual([]);
    expect(validate.secretErrors(awsFixture(), "delete")).toEqual([]);
    expect(validate.secretErrors(awsFixture({ "redis-storage-managed": false }), "create").length).toBe(2);
  });

  test("managed storage has one shape", () => {
    const errors = (overrides: Record<string, unknown>) => validate.stateErrors(awsFixture(overrides));
    const some = (list: string[], pattern: RegExp) => list.some((e) => pattern.test(e));
    expect(errors({})).toEqual([]);
    const { "redis-storage-managed": _managed, ...withoutManaged } = awsFixture();
    expect(some(validate.stateErrors(withoutManaged), /must be true or false/)).toBe(true);
    expect(some(errors({ "redis-storage-managed": "yes" }), /must be true or false/)).toBe(true);
    expect(some(errors({ "provider-backend": "r2", "r2-bucket": "b", "r2-endpoint": "https://x.r2.cloudflarestorage.com" }), /provider-backend s3/)).toBe(true);
    expect(some(errors({ "s3-bucket-mode": "external" }), /s3-bucket-mode managed/)).toBe(true);
    const { "s3-bucket-mode": _mode, ...withoutMode } = awsFixture();
    expect(some(validate.stateErrors(withoutMode), /s3-bucket-mode managed/)).toBe(true);
    expect(some(errors({ "redis-backup-r2-region": "eu-west-1" }), /must equal s3-region/)).toBe(true);
    expect(some(errors({ "redis-backup-r2-endpoint": "https://s3.eu-west-1.amazonaws.com" }), /must be https:\/\/s3\.us-east-1\.amazonaws\.com/)).toBe(true);
    expect(some(errors({ "redis-backup-r2-endpoint": "https://fixture.r2.cloudflarestorage.com" }), /must be https:\/\/s3\.us-east-1\.amazonaws\.com/)).toBe(true);
    expect(some(errors({ "redis-backup-r2-bucket": "redis.backup" }), /must not contain dots/)).toBe(true);
    expect(some(errors({ "redis-backup-r2-bucket": "redis-aws-fixture-state" }), /must differ from s3-bucket/)).toBe(true);
    // None of it applies to an operator-owned bucket.
    expect(validate.stateErrors(awsOptout({ "redis-storage-managed": false, "redis-backup-r2-region": "eu-west-1" }))).toEqual([]);
    expect(validate.stateErrors(fixture({ "redis-storage-managed": false }))).toEqual([]);
  });

  test("the exact messages are green's", () => {
    const errors = validate.stateErrors(awsFixture({ "redis-storage-managed": "yes" }));
    expect(errors).toContain(":redis-storage-managed must be true or false");
    const { profile: _profile, ...withoutProfile } = fixture({ "redis-storage-managed": false });
    expect(validate.stateErrors(withoutProfile)).toContain(":profile is required");
    expect(validate.stateErrors(fixture({ "redis-storage-managed": false, "redis-image": "redis:7.2" })))
      .toContain(":redis-image must be pinned by digest (tag@sha256:...)");
    expect(validate.stateErrors(fixture({ "redis-storage-managed": false, "redis-port": "6379" })))
      .toContain(":redis-port must be an integer between 1 and 65535");
    expect(validate.stateErrors(fixture({ "redis-storage-managed": false, "redis-backup-retention-days": 0 })))
      .toContain(":redis-backup-retention-days must be a positive integer");
    expect(validate.secretErrors(fixture(), "create"))
      .toEqual(["required credential is not set: COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID",
                "required credential is not set: COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY"]);
  });

  test("key mode delegates to the library", () => {
    expect(validate.keygen(fixture())).toBe(true);
    expect(validate.keygen(awsFixture())).toBe(true);
    expect(validate.keygen(optout())).toBe(false);
    expect(validate.keygen(doOptout())).toBe(false);
    expect(validate.keygen(awsOptout())).toBe(false);
  });
});
