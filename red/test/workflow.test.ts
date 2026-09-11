import { afterEach, describe, expect, test } from "bun:test";
import { existsSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { runtime } from "red/runtime";
import { failed, run, type Opts } from "red/workflow";
import * as compute from "../src/compute.ts";
import * as storage from "../src/storage.ts";
import * as tools from "../src/tools.ts";
import * as workflow from "../src/workflow.ts";
import { allFixtures, awsFixture, awsOptout, fixture, tempWorkdir } from "./fixtures.ts";

const originalLog = runtime.log;
afterEach(() => { runtime.log = originalLog; });

function route(event: string, opts: Opts, start: string): string[] {
  const path = [start];
  let step = start;
  for (;;) {
    const next = workflow.wireFn(step, { ...opts, "red/event": event })?.[1];
    if (!next) return path;
    path.push(next);
    step = next;
  }
}

describe("workflow", () => {
  test("cleanup order and read-only events", () => {
    expect(workflow.wireFn("redis/load-infrastructure", { "red/event": "delete" })).toEqual([tools.loadInfrastructureStep, "redis/ansible"]);
    expect(workflow.wireFn("redis/ssh-config", { "red/event": "delete" })).toEqual([tools.ansibleLocalStep, "redis/infrastructure"]);
    expect(workflow.wireFn("redis/infrastructure", { "red/event": "delete" })).toEqual([tools.infrastructureStep]);
    for (const event of ["rehearse", "describe"]) {
      expect(workflow.wireFn("redis/start", { "red/event": event })?.[1]).toBe("redis/load-infrastructure");
    }
    expect(workflow.wireFn("redis/rehearsal", { "red/event": "rehearse" })).toEqual([tools.rehearsalStep]);
    expect(workflow.wireFn("redis/describe", { "red/event": "describe" })).toEqual([tools.describeStep]);
  });

  test("failures refuse application inventory", async () => {
    const created = await tools.infrastructureStep(fixture({ "red/event": "create" }), { orchestrate: async () => ({ status: "error" }) });
    expect(created["red/exit"]).toBe(1);
    const loaded = await tools.loadInfrastructureStep(fixture({ "red/event": "delete" }), {
      readDeployment: async (_opts, env, deps, _requirements) => {
        expect(typeof env).toBe("object");
        expect(Object.hasOwn(env as object, "HOME")).toBe(true);
        expect(deps).toEqual({});
        return { status: "error" };
      },
    });
    expect(loaded["red/exit"]).toBe(1);
    expect(() => compute.node(fixture({ "red/event": "create" }))).toThrow("compute result unavailable");
  });

  test("inspection preserves connection user and identity", async () => {
    const node = { node_id: "0", provider: "vultr", name: "redis-fixture", ip: "203.0.113.8", user: "ubuntu", sudoer: "ubuntu", ssh_identity_file: "/operator/key" };
    const out = await tools.loadInfrastructureStep(fixture({ "red/event": "describe" }), {
      readDeployment: async () => ({ status: "present", cluster: { nodes: [node] } }),
    });
    expect(out.user).toBe("ubuntu");
    expect(out["ssh-private-key-path"]).toBe("/operator/key");
    expect(compute.node(out).ip).toBe("203.0.113.8");
  });

  test("a destroyed delete stops cleanup", async () => {
    const readDeployment = async () => ({ status: "destroyed" });
    expect((await tools.loadInfrastructureStep(fixture({ "red/event": "delete" }), { readDeployment }))["redis/already-destroyed"]).toBe(true);
    const described = await tools.loadInfrastructureStep(fixture({ "red/event": "describe" }), { readDeployment });
    expect(described["red/exit"]).toBe(1);
    expect(described["red/err"]).toBe("compute deployment is destroyed");
  });

  test("managed storage is wired between compute and the host", () => {
    expect(route("create", awsFixture(), "redis/start")).toEqual(["redis/start", "redis/infrastructure", "redis/storage", "redis/ssh-config", "redis/ansible", "redis/acceptance"]);
    expect(route("create", awsOptout(), "redis/start")).toEqual(["redis/start", "redis/infrastructure", "redis/ssh-config", "redis/ansible", "redis/acceptance"]);
    expect(route("create", fixture(), "redis/start")).toEqual(["redis/start", "redis/infrastructure", "redis/ssh-config", "redis/ansible", "redis/acceptance"]);
    expect(workflow.wireFn("redis/storage", awsFixture({ "red/event": "create" }))).toEqual([storage.step, "redis/ssh-config"]);
  });

  test("delete destroys the bucket after the machine and the state bucket last", () => {
    expect(route("delete", awsFixture(), "redis/start")).toEqual(["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure", "redis/storage", "redis/backend-finalize"]);
    expect(route("delete", awsOptout(), "redis/start")).toEqual(["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure"]);
    expect(route("delete", fixture(), "redis/start")).toEqual(["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure"]);
    expect(route("delete", awsOptout({ "s3-bucket-mode": "managed" }), "redis/start")).toEqual(["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure", "redis/backend-finalize"]);
    expect(route("delete", awsFixture({ "s3-bucket-mode": "external" }), "redis/start")).toEqual(["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure", "redis/storage"]);
    expect(workflow.wireFn("redis/backend-finalize", awsFixture({ "red/event": "delete" }))).toEqual([workflow.backendFinalizeStep]);
  });

  test("a repeat delete continues past a missing machine", async () => {
    const successors = (opts: Opts) => workflow.nextFn("redis/load-infrastructure", ["redis/ansible"], opts).map(([step]) => step);
    // Compute destroyed or absent: storage, then the finalizer.
    for (const status of ["destroyed", "absent"]) {
      const r = await tools.loadInfrastructureStep(awsFixture({ "red/event": "delete" }), { readDeployment: async () => ({ status }) });
      expect(r["red/exit"]).toBe(0);
      expect(r["redis/already-destroyed"]).toBe(true);
      expect(successors(r)).toEqual(["redis/storage"]);
      expect(successors({ ...r, "redis-storage-managed": false })).toEqual(["redis/backend-finalize"]);
      expect(successors({ ...r, "redis-storage-managed": false, "s3-bucket-mode": "external" })).toEqual([]);
    }
    // An absent journal with nothing managed is still an error, not absence.
    const absent = async () => ({ status: "absent" });
    expect((await tools.loadInfrastructureStep(fixture({ "red/event": "delete" }), { readDeployment: absent }))["red/exit"]).toBe(1);
    expect((await tools.loadInfrastructureStep(awsOptout({ "red/event": "delete" }), { readDeployment: absent }))["red/exit"]).toBe(1);
    // An unreadable managed backend routes straight to the finalizer, which decides.
    const error = async () => ({ status: "error" });
    const r = await tools.loadInfrastructureStep(awsFixture({ "red/event": "delete" }), { readDeployment: error });
    expect(r["red/exit"]).toBe(0);
    expect(r["redis/finalize-only"]).toBe(true);
    expect(r["redis/already-destroyed"]).toBeUndefined();
    expect(successors(r)).toEqual(["redis/backend-finalize"]);
    expect((await tools.loadInfrastructureStep(awsOptout({ "red/event": "delete" }), { readDeployment: error }))["red/exit"]).toBe(1);
    expect((await tools.loadInfrastructureStep(awsFixture({ "red/event": "rehearse" }), { readDeployment: error }))["red/exit"]).toBe(1);
    expect((await tools.loadInfrastructureStep(awsFixture({ "red/event": "describe" }), { readDeployment: error }))["red/exit"]).toBe(1);
    // A failed step routes nowhere.
    expect(successors({ ...r, "red/exit": 1 })).toEqual([]);
    // The finalizer's outcome is the exit.
    expect((await workflow.backendFinalizeStep(awsFixture({ "red/event": "delete" }), { finalizeBackend: async () => ({ status: "absent" }) }))["red/exit"]).toBe(0);
    expect((await workflow.backendFinalizeStep(awsFixture({ "red/event": "delete" }), { finalizeBackend: async () => ({ status: "refused" }) }))["red/exit"]).toBe(1);
    const thrown = await workflow.backendFinalizeStep(awsFixture({ "red/event": "delete" }), { finalizeBackend: async () => { throw new Error("live state remains"); } });
    expect(thrown["red/exit"]).toBe(1);
    expect(thrown["red/err"]).toBe("managed backend finalization refused; live or unowned state remains");
  });

  test("rehearse reads the scoped pair back from storage state", async () => {
    const node = { node_id: "0", provider: "aws", name: "redis-aws-fixture", ip: "203.0.113.8", user: "ubuntu", sudoer: "ubuntu" };
    let reads = 0;
    const deps = {
      readDeployment: async () => ({ status: "present", cluster: { nodes: [node] } }),
      readCredentials: async (opts: Opts) => { reads += 1; return { ...opts, [storage.credentialsKey]: { credentials: { backup: { access_key_id: "k", secret_access_key: "s" } } } }; },
    };
    const out = await tools.loadInfrastructureStep(awsFixture({ "red/event": "rehearse" }), deps);
    expect(reads).toBe(1);
    expect(storage.credentialEnv(out)).toEqual({ COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID: "k", COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY: "s" });
    await tools.loadInfrastructureStep(awsFixture({ "red/event": "describe" }), deps);
    await tools.loadInfrastructureStep(awsOptout({ "red/event": "rehearse" }), deps);
    // Describe and an operator-owned bucket read nothing.
    expect(reads).toBe(1);
  });

  test("dry-runs need no credential and render the storage backend", async () => {
    for (const [f, event] of [[awsFixture, "create"], [awsFixture, "delete"], [awsOptout, "create"], [awsOptout, "delete"]] as const) {
      const r = await workflow.startStep(f({ "red/event": event, "red/dry-run": true, "compute-prevent-destroy": false }), {});
      expect(r["red/exit"]).toBe(0);
    }
    // An operator-owned bucket needs the operator's pair.
    const refused = await workflow.startStep(awsOptout({ "red/event": "create" }), {});
    expect(String(refused["red/err"])).toMatch(/COLORS_PAR_REDIS_BACKUP_R2/);
    expect(refused["red/exit"]).toBe(2);
    expect((await workflow.startStep(fixture({ "red/event": "build" }), {}))["redis-storage-managed"]).toBe(false);
  });

  test("delete is protected and a real create wants the pair", async () => {
    const guarded = await workflow.startStep(fixture({ "red/event": "delete" }), {});
    expect(guarded["red/exit"]).toBe(2);
    expect(String(guarded["red/err"])).toContain("COLORS_PAR_COMPUTE_PREVENT_DESTROY=false");
    const overlaid = await workflow.startStep(fixture({ "red/event": "create" }), {
      COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID: "id", COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY: "secret",
    });
    expect(overlaid["red/exit"] ?? 0).toBe(0);
    expect(overlaid["redis-backup-r2-secret-access-key"]).toBe("secret");
    const profiled = await workflow.startStep(fixture({ "red/event": "build" }), { COLORS_PAR_PROFILE: "wrong" });
    expect(profiled["red/exit"]).toBe(2);
    expect(String(profiled["red/err"])).toContain("COLORS_PAR_PROFILE");
  });

  test("all fixtures natively build through the library", async () => {
    runtime.log = () => {};
    for (const f of allFixtures) {
      const directory = tempWorkdir("redis-library-build-");
      const opts = f({ "red/event": "build", workdir: directory });
      try {
        const result = await run(workflow.redisWorkflow, opts);
        expect(failed(result)).toBe(false);
        expect(result.ip).toBe("192.0.2.10");
        // AWS has no VPC-less instance, so the library gives an AWS node a
        // private address; nothing in this package reads it. The other
        // providers create no network at all.
        expect(compute.node(result).vpc_ip != null).toBe(opts["provider-compute"] === "aws");
        const nodeDir = join(tools.toolDir(opts, tools.infrastructureTool), "nodes/0");
        expect(readdirSync(nodeDir).filter((name) => /^node(-none)?\.tf\.json$/.test(name)).length).toBe(1);
        expect(existsSync(join(storage.directory(opts), "main.tf"))).toBe(storage.managed(opts));
        expect(existsSync(join(storage.directory(opts), "backend.tf.json"))).toBe(storage.managed(opts));
        if (storage.managed(opts)) {
          const tf = readFileSync(join(storage.directory(opts), "main.tf"), "utf8");
          expect(tf).toContain('backup = "redis-aws-fixture-backup"');
          expect(tf).toContain("prevent_destroy = true");
          expect(/AKIA|secret_access_key = "/.test(tf)).toBe(false);
        }
      } finally {
        rmSync(directory, { recursive: true, force: true });
      }
    }
  });

  test("dry-run verbs walk every graph without a side effect", async () => {
    runtime.log = () => {};
    runtime.exec = async () => { throw new Error("must not run"); };
    try {
      for (const event of ["create", "delete", "rehearse", "describe"]) {
        const directory = tempWorkdir("redis-dry-run-");
        try {
          const result = await run(workflow.redisWorkflow, awsFixture({ "red/event": event, "red/dry-run": true, "compute-prevent-destroy": false, workdir: directory }));
          expect(result["red/exit"]).toBe(0);
          expect(result["ssh-private-key-path"]).toBe("/home/build-placeholder/.ssh/redis-aws-fixture");
        } finally {
          rmSync(directory, { recursive: true, force: true });
        }
      }
    } finally {
      runtime.exec = runtimeExec;
    }
  });
});

const runtimeExec = runtime.exec;
