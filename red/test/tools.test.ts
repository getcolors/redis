import { afterEach, describe, expect, test } from "bun:test";
import { readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { runtime, type ExecOptions, type ExecResult } from "red/runtime";
import type { Opts } from "red/workflow";
import * as storage from "../src/storage.ts";
import * as tools from "../src/tools.ts";
import { awsFixture, awsOptout, credentials, fixture, tempWorkdir } from "./fixtures.ts";

const originalExec = runtime.exec;
const originalLog = runtime.log;
afterEach(() => {
  runtime.exec = originalExec;
  runtime.log = originalLog;
});

const resource = (path: string) => readFileSync(join(import.meta.dir, "../resources/tools", path), "utf8");

function specFor(opts: Opts, file: string) {
  return tools.ansibleSpecs(opts).find((s) => String(s.target).endsWith(file));
}

describe("tools", () => {
  test("the backup prefix is namespaced by profile", () => {
    // Two deployments sharing a bucket must never share a prefix.
    expect(tools.setPrefix(fixture({ "red/event": "build" }))).toBe("redis-fixture/redis");
  });

  test("the inventory keeps one target and no private address", () => {
    const inventory = JSON.parse(tools.inventory(fixture({ "red/event": "build", ip: "192.0.2.10" })));
    const host = inventory.all.children.redis.hosts["redis-fixture"];
    expect(host.ansible_host).toBe("192.0.2.10");
    expect(host.ansible_user).toBe("root");
    expect(host.vpc_ip).toBeUndefined();
  });

  test("a build inventory carries the placeholder only", () => {
    const inventory = tools.inventory(fixture({ "red/event": "build" }));
    expect(inventory).toContain(tools.placeholderIp);
    expect(inventory).not.toContain("10.60.");
  });

  test("the inventory is Cheshire-pretty with the identity last", () => {
    const inventory = tools.inventory({ ...fixture({ "red/event": "build" }), "ssh-private-key-path": "/home/build-placeholder/.ssh/redis-fixture" });
    expect(inventory).toBe(
      '{\n  "all" : {\n    "children" : {\n      "redis" : {\n        "hosts" : {\n          "redis-fixture" : {\n' +
      '            "ansible_host" : "192.0.2.10",\n            "ansible_user" : "root",\n' +
      '            "ansible_ssh_private_key_file" : "/home/build-placeholder/.ssh/redis-fixture"\n' +
      "          }\n        }\n      }\n    }\n  }\n}");
  });

  test("ansible renders the whole tree", () => {
    const targets = tools.ansibleSpecs(fixture({ "red/event": "build" })).map((s) => String(s.target));
    for (const file of ["ansible.cfg", "main.yml", "cleanup.yml", "rehearsal.yml", "compose.yml",
                        "r2-env.sh", "redis-backup.sh", "redis-restore-check.sh",
                        "redis-smoke.sh", "redis-monitor.sh", "redis-status.sh", "inventory.json"]) {
      expect(targets.some((t) => t.endsWith(file))).toBe(true);
    }
    expect(new Set(tools.ansibleFiles).size).toBe(tools.ansibleFiles.length);
  });

  test("operator secrets reach the host as lookups, not values", () => {
    // `.colors/` is generated output and the goldens are committed, so the
    // secret must never be the thing that lands on disk; the expression is.
    const template = resource("ansible/main.yml");
    for (const par of ["COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID", "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY"]) {
      expect(template).toContain(`lookup('env','${par}')`);
    }
  });

  test("the data map carries no operator secret", () => {
    const data = (specFor(fixture({ "red/event": "build" }), "main.yml")?.data ?? {}) as Opts;
    expect(data["redis-backup-set-prefix"]).toBe("redis-fixture/redis");
    for (const key of ["redis-backup-r2-access-key-id", "redis-backup-r2-secret-access-key"]) {
      expect(data[key]).toBeUndefined();
    }
    // Nor the managed storage output.
    const opts = awsFixture({ "red/event": "build", [storage.credentialsKey]: credentials });
    expect((specFor(opts, "main.yml")?.data ?? {})[storage.credentialsKey]).toBeUndefined();
    expect(tools.ansibleLocalData(opts)[storage.credentialsKey]).toBeUndefined();
  });

  test("the play environment carries the scoped pair only when managed", () => {
    expect(tools.playEnv(fixture(), true)).toEqual({ ANSIBLE_HOST_KEY_CHECKING: "False" });
    expect(tools.playEnv(awsOptout(), true)).toEqual({ ANSIBLE_HOST_KEY_CHECKING: "False" });
    expect(tools.playEnv(awsFixture({ [storage.credentialsKey]: credentials }), false)).toEqual({ ANSIBLE_HOST_KEY_CHECKING: "False" });
    expect(tools.playEnv(awsFixture({ [storage.credentialsKey]: credentials }), true)).toEqual({
      ANSIBLE_HOST_KEY_CHECKING: "False",
      COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID: "AKIA",
      COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY: "s",
    });
    // Managed without an output is refused, never an empty variable.
    expect(() => tools.playEnv(awsFixture(), true)).toThrow();
  });

  test("the play runner mirrors the SDK step", async () => {
    const workdir = tempWorkdir();
    const runs: Array<[string[], ExecOptions]> = [];
    const runner = (exit: number, out: string) => async (args: string[], options: ExecOptions): Promise<ExecResult> => {
      runs.push([args, options]);
      return { exit, out, err: "" };
    };
    const recap = "PLAY RECAP\nredis-fixture : ok=3 changed=1 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0\n";
    try {
      // A build renders and runs nothing.
      const built = await tools.runPlay(fixture({ "red/event": "build", workdir }), "main.yml", true, { exec: runner(0, recap) });
      expect(built["red/exit"]).toBe(0);
      expect(runs.length).toBe(0);
      // A create runs the play with host-key checking off and parses the recap.
      const created = await tools.runPlay(fixture({ "red/event": "create", "red/dry-run": true, ip: "192.0.2.10", workdir }),
        "main.yml", true, { exec: runner(0, recap) });
      expect(created["red/exit"]).toBe(0);
      expect(created["red/event"]).toBe("create");
      expect(created["ansible/recap"]).toEqual({ "redis-fixture": { ok: 3, changed: 1, unreachable: 0, failed: 0, skipped: 0, rescued: 0, ignored: 0 } });
      const [args, options] = runs.at(-1)!;
      expect(args).toEqual(["ansible-playbook", "-i", "inventory.json", "main.yml"]);
      expect(options.env).toEqual({ ANSIBLE_HOST_KEY_CHECKING: "False" });
      expect(options.cwd).toBe(join(workdir, "redis-fixture", "redis-ansible"));
      expect(options.timeoutMs).toBe(tools.playTimeoutMs);
      // A failure carries the play's output.
      const failed = await tools.runPlay(fixture({ "red/event": "create", "red/dry-run": true, ip: "192.0.2.10", workdir }),
        "main.yml", true, { exec: runner(2, "fatal: unreachable") });
      expect(failed["red/exit"]).toBe(2);
      expect(String(failed["red/err"])).toContain("ansible-playbook main.yml failed: fatal: unreachable");
      // A runtime timeout (negative exit) is a failure, never a pass.
      const timedOut = await tools.runPlay(fixture({ "red/event": "create", "red/dry-run": true, ip: "192.0.2.10", workdir }),
        "main.yml", true, { exec: runner(-1, "") });
      expect(timedOut["red/exit"]).toBe(1);
      expect(String(timedOut["red/err"])).toContain("ansible-playbook main.yml failed");
      // A managed deployment hands the scoped pair to the play.
      const managed = await tools.runPlay(awsFixture({ "red/event": "rehearse", "red/dry-run": true, workdir, [storage.credentialsKey]: credentials }),
        "rehearsal.yml", true, { exec: runner(0, recap) });
      expect(managed["red/exit"]).toBe(0);
      expect(runs.at(-1)![1].env?.COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY).toBe("s");
    } finally {
      rmSync(workdir, { recursive: true, force: true });
    }
  });

  test("the compose file publishes on loopback alone", () => {
    // Exposure is decided by what Compose publishes: one binding, loopback.
    const template = resource("ansible/compose.yml");
    const bindings = template.match(/"[^"]*:<\{ redis-port \}>:6379"/g);
    expect(bindings).toEqual(['"127.0.0.1:<{ redis-port }>:6379"']);
    expect(template).not.toContain("vpc");
  });

  test("the play and the smoke gate know no private address", () => {
    const play = resource("ansible/main.yml");
    const smoke = resource("ansible/redis-smoke.sh");
    expect(play).toContain("redis-smoke {{ ansible_host }}");
    expect(play).not.toContain("vpc");
    expect(smoke).toContain('expected="127.0.0.1:$port"');
    expect(smoke).not.toContain("vpc");
  });

  test("a delete without compute skips the host entirely", async () => {
    // There is no machine to stop, and the cleanup play would only fail against
    // the placeholder address.
    runtime.exec = async () => { throw new Error("must not run"); };
    const result = await tools.ansibleStep({ ...fixture({ "red/event": "build" }), "red/event": "delete" });
    expect(result["red/exit"]).toBe(0);
  });

  test("the cleanup play needs no storage credentials", async () => {
    // On the delete DAG the storage stage runs after the play, so the managed
    // pair has not been read; the cleanup play must not ask for it.
    const runs: Array<[string[], ExecOptions | undefined]> = [];
    const recap = "PLAY RECAP\nredis-aws-fixture : ok=2 changed=1 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0\n";
    runtime.exec = async (args: string[], options?: ExecOptions): Promise<ExecResult> => {
      runs.push([args, options]);
      return { exit: 0, out: recap, err: "" };
    };
    const deleted = await tools.ansibleStep(awsFixture({ "red/event": "delete", "red/dry-run": true, ip: "192.0.2.10", workdir: tempWorkdir() }));
    expect(deleted["red/exit"]).toBe(0);
    expect(runs.at(-1)![0]).toEqual(["ansible-playbook", "-i", "inventory.json", "cleanup.yml"]);
    expect(runs.at(-1)![1]?.env).toEqual({ ANSIBLE_HOST_KEY_CHECKING: "False" });
    const created = await tools.ansibleStep(awsFixture({ "red/event": "create", "red/dry-run": true, ip: "192.0.2.10", workdir: tempWorkdir(), [storage.credentialsKey]: credentials }));
    expect(created["red/exit"]).toBe(0);
    expect(runs.at(-1)![0]).toEqual(["ansible-playbook", "-i", "inventory.json", "main.yml"]);
    expect(runs.at(-1)![1]?.env?.COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID).toBe("AKIA");
  });

  test("acceptance is skipped outside a real create", async () => {
    runtime.exec = async () => { throw new Error("must not run"); };
    for (const event of ["build", "delete", "rehearse", "describe"]) {
      const result = await tools.acceptanceStep({ ...fixture({ "red/event": "build" }), "red/event": event });
      expect(result["red/exit"]).toBe(0);
    }
  });

  test("the tunnel probe never puts the password on a command line", () => {
    const [, , script] = tools.redisArgs(20001, true, "PING");
    const [, , anonymous] = tools.redisArgs(20001, false, "PING");
    expect(script).toContain('REDISCLI_AUTH="$REDISCLI_AUTH"');
    expect(script).toContain("env -i");
    expect(anonymous).not.toContain("REDISCLI_AUTH");
    expect(script).toContain("-p 20001 'PING'");
  });

  test("the tunnel rides the generated alias and the configured port", () => {
    const [, , script] = tools.tunnelArgs(fixture({ "red/event": "build", "redis-port": 6380 }), 20001);
    expect(script).toContain("-L 20001:127.0.0.1:6380 redis-fixture");
    expect(script).toContain("ExitOnForwardFailure=yes");
  });

  test("the public port probe is bounded", () => {
    const [, , script] = tools.closedPortArgs("203.0.113.5", 6379);
    expect(script).toContain("timeout 5");
    expect(script).toContain("/dev/tcp/203.0.113.5/6379");
  });

  test("acceptance proves the operator path and the closed port", async () => {
    // A workstation-side run, replayed: the password over ssh, the public port
    // refusing, the tunnel, the authenticated round-trip and both refusals.
    let stamp = "";
    runtime.exec = async (args: string[]): Promise<ExecResult> => {
      const script = args.at(-1) ?? "";
      if (args[0] === "ssh") return { exit: 0, out: "generated-password\n", err: "" };
      if (script.includes("/dev/tcp/")) return { exit: 1, out: "", err: "Connection timed out" };
      if (script.startsWith("ssh -f")) return { exit: 0, out: "", err: "" };
      if (script.includes("'SET'")) { stamp = /'colors:operator' '([^']+)'/.exec(script)![1]!; return { exit: 0, out: "OK\n", err: "" }; }
      if (script.includes("'GET'")) return { exit: 0, out: `${stamp}\n`, err: "" };
      if (script.includes("REDISCLI_AUTH")) return { exit: 0, out: "(error) WRONGPASS invalid username-password pair\n", err: "" };
      return { exit: 0, out: "(error) NOAUTH Authentication required.\n", err: "" };
    };
    const result = await tools.acceptanceStep(fixture({ "red/event": "create", ip: "203.0.113.5" }));
    expect(result["red/exit"]).toBe(0);
    expect(result["redis/acceptance"]).toEqual({
      tunnel: "ok", "round-trip": stamp, unauthenticated: "refused", "wrong-password": "refused", "public-port": "closed",
    });
  });

  test("acceptance fails when the public port answers", async () => {
    runtime.exec = async (args: string[]): Promise<ExecResult> => {
      if (args[0] === "ssh") return { exit: 0, out: "pw\n", err: "" };
      return { exit: 0, out: "", err: "" };
    };
    const result = await tools.acceptanceStep(fixture({ "red/event": "create", ip: "203.0.113.5" }));
    expect(result["red/exit"]).toBe(1);
    expect(String(result["red/err"])).toBe("acceptance: 203.0.113.5:6379 accepted a connection from the internet; the port must not be public");
  });

  test("describe reads the monitor result and reports it", async () => {
    const lines: string[] = [];
    runtime.log = (...args: unknown[]) => { lines.push(args.map(String).join(" ")); };
    runtime.exec = async () => ({ exit: 0, out: '{"healthy": true, "checked": "2026-09-11T00:00:00Z", "problems": []}', err: "" });
    const healthy = await tools.describeStep(fixture({ "red/event": "describe" }));
    expect(healthy["red/exit"]).toBe(0);
    expect(healthy["redis/describe"]).toEqual({ host: "redis-fixture", reachable: true, healthy: true, checked: "2026-09-11T00:00:00Z", problems: [] });
    expect(lines.at(-1)).toBe(`${"redis-fixture".padEnd(32)} ${"ok".padEnd(10)} 2026-09-11T00:00:00Z`);
    runtime.exec = async () => ({ exit: 255, out: "", err: "ssh: connect failed" });
    const unreachable = await tools.describeStep(fixture({ "red/event": "describe" }));
    expect(unreachable["red/exit"]).toBe(1);
    expect(unreachable["redis/describe"]).toEqual({ host: "redis-fixture", reachable: false, healthy: false, checked: undefined, problems: ["unreachable or no monitor result yet"] });
    expect(lines.at(-1)).toBe(`${"redis-fixture".padEnd(32)} ${"UNKNOWN".padEnd(10)}  unreachable or no monitor result yet`);
  });

  test("the local play receives its required node fields", async () => {
    let seen: unknown;
    await tools.ansibleLocalStep(fixture({ "red/event": "build" }), {
      ansibleWithSpec: async (opts, config) => { seen = config.extraVars?.ssh_hosts; return opts; },
    });
    expect(seen).toEqual([{ name: "redis-fixture", ip: "192.0.2.10", user: "root" }]);
  });

  test("compute-json accepts library mixed-key maps and sorts them", () => {
    expect(JSON.parse(tools.computeJson({ region: "ams", backups: true }, 0))).toEqual({ backups: true, region: "ams" });
    expect(tools.computeJson({ b: [1, {}], a: "x", c: [] }, 0)).toBe('{\n  "a": "x",\n  "b": [\n    1,\n    {}\n  ],\n  "c": []\n}');
  });

  test("tool dirs live under <workdir>/<profile>", () => {
    const opts = { workdir: "/work", profile: "redis-fixture" };
    expect(tools.toolDir(opts, tools.infrastructureTool)).toBe("/work/redis-fixture/redis-infrastructure");
    expect(tools.toolDir(opts, tools.ansibleLocalTool)).toBe("/work/redis-fixture/redis-ansible-local");
    expect(storage.directory(opts)).toBe("/work/redis-fixture/redis-storage");
  });
});
