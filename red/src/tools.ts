import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import * as ansible from "red/ansible";
import { stageDir } from "red/cli";
import { posixQuote } from "red/process";
import { runtime, type ExecOptions, type ExecResult } from "red/runtime";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, scaffold, type Spec, type Template } from "red/scaffold";
import type { Opts } from "red/workflow";
import { backend_plan, orchestrate, plan_deployment, read_deployment } from "colors-compute-red";
import * as compute from "./compute.ts";
import * as sshConfig from "./ssh-config.ts";
import * as storage from "./storage.ts";
import * as validate from "./validate.ts";

import ansibleLocalCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import ansibleLocalInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import ansibleLocalMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import ansibleCfg from "../resources/tools/ansible/ansible.cfg" with { type: "text" };
import ansibleMain from "../resources/tools/ansible/main.yml" with { type: "text" };
import ansibleCleanup from "../resources/tools/ansible/cleanup.yml" with { type: "text" };
import ansibleRehearsal from "../resources/tools/ansible/rehearsal.yml" with { type: "text" };
import ansibleCompose from "../resources/tools/ansible/compose.yml" with { type: "text" };
import ansibleR2Env from "../resources/tools/ansible/r2-env.sh" with { type: "text" };
import ansibleBackup from "../resources/tools/ansible/redis-backup.sh" with { type: "text" };
import ansibleRestoreCheck from "../resources/tools/ansible/redis-restore-check.sh" with { type: "text" };
import ansibleSmoke from "../resources/tools/ansible/redis-smoke.sh" with { type: "text" };
import ansibleMonitor from "../resources/tools/ansible/redis-monitor.sh" with { type: "text" };
import ansibleStatus from "../resources/tools/ansible/redis-status.sh" with { type: "text" };

type Map = Record<string, any>;

export const infrastructureTool = "redis-infrastructure";
export const ansibleTool = "redis-ansible";
export const ansibleLocalTool = "redis-ansible-local";
export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "redis" });
}

const template = (name: string, content: string): Template => ({ name, content });

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

export const placeholderIp = "192.0.2.10";

export function setPrefix(opts: Opts): string {
  return `${opts.profile}/redis`;
}

// The process environment for the library and for tofu: the ambient one,
// with the optional COLORS_PAR_AWS_* pars overlaid onto AWS_* the way
// neon-multi-node does, so an AWS deployment can carry its own credentials in
// .envrc.private without an operator-level AWS profile.
export function environment(opts: Opts): Record<string, string | undefined> {
  return { ...process.env, ...storage.awsEnv(opts) };
}

// ---------------------------------------------------------------- compute

// The library's documents, keys sorted, two-space indentation, one value per
// line: green's byte-level contract for the compute stage.
export function computeJson(value: unknown, indent: number): string {
  const padding = (n: number) => " ".repeat(n);
  if (Array.isArray(value)) {
    if (value.length === 0) return "[]";
    return `[\n${value.map((item) => padding(indent + 2) + computeJson(item, indent + 2)).join(",\n")}\n${padding(indent)}]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value as Map).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    if (entries.length === 0) return "{}";
    return `{\n${entries
      .map(([key, item]) => `${padding(indent + 2)}${JSON.stringify(key)}: ${computeJson(item, indent + 2)}`)
      .join(",\n")}\n${padding(indent)}}`;
  }
  return JSON.stringify(value);
}

function writeDocument(target: string, document: unknown): void {
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, `${computeJson(document, 0)}\n`);
}

// The library calls a step makes, injectable so tests can replay an outcome
// without a provider. The shapes are the library's result maps.
export interface InfrastructureDeps {
  orchestrate?: (opts: Opts, topology: Map[], requirements: Map, environment: Map) => Promise<Map>;
}

export async function infrastructureStep(opts: Opts, deps: InfrastructureDeps = {}): Promise<Opts> {
  try {
    const planning = opts["red/event"] === "build" || Boolean(opts["red/dry-run"]);
    const result: Map = planning
      ? plan_deployment(opts, compute.topology(opts), compute.requirements(opts))
      : await (deps.orchestrate ?? orchestrate)(opts, compute.topology(opts), compute.requirements(opts), environment(opts));
    if (planning) {
      const root = toolDir(opts, infrastructureTool);
      const keys: Array<[string, string]> = [
        ["shared", result.state_keys.shared],
        ...Object.entries(result.state_keys.nodes as Record<string, string>).map(([id, key]) => [`nodes/${id}`, key] as [string, string]),
      ];
      for (const [stage, key] of keys) {
        writeDocument(join(root, stage, "backend.tf.json"), backend_plan(opts, key).config);
      }
      const stacks: Array<[string, Map]> = [
        ["shared", result.documents.shared],
        ...Object.entries(result.documents.nodes as Record<string, Map>).map(([id, documents]) => [`nodes/${id}`, documents] as [string, Map]),
      ];
      for (const [stage, documents] of stacks) {
        for (const [filename, document] of Object.entries(documents)) {
          writeDocument(join(root, stage, filename), document);
        }
      }
    }
    if (!["ready", "planned", "destroyed"].includes(result.status)) {
      return {
        ...opts, "red/exit": 1,
        "red/err": result.errors?.length ? result.errors.join("\n") : "compute lifecycle refused; inspect state ownership and configuration",
      };
    }
    const privateKeyPath = result.key?.private_key_path;
    return {
      ...opts, "red/exit": 0,
      ...(result.shared ? { "colors-compute/shared": result.shared } : {}),
      ...(result.cluster ? {
        "colors-compute/cluster": result.cluster,
        ip: result.cluster.nodes?.[0]?.ip,
        user: result.cluster.nodes?.[0]?.user,
      } : {}),
      ...(privateKeyPath ? {
        "ssh-private-key-path": planning
          ? String(privateKeyPath).replaceAll("$HOME/.ssh", "/home/build-placeholder/.ssh")
          : privateKeyPath,
      } : {}),
    };
  } catch {
    return { ...opts, "red/exit": 1, "red/err": "compute lifecycle refused; legacy monolithic state requires explicit migration" };
  }
}

export function managedBackend(opts: Opts): boolean {
  return opts["s3-bucket-mode"] === "managed";
}

export interface LoadDeps {
  readDeployment?: (opts: Opts, environment: Map, dependencies: Map, requirements: Map) => Promise<Map>;
  readCredentials?: (opts: Opts) => Promise<Opts>;
}

// Inspect the recorded deployment before any verb that needs the host.
//
// A delete has two routes past a missing machine, so a repeat delete exits 0
// instead of demanding state that is gone. Compute destroyed or absent:
// `redis/already-destroyed` skips the host and the compute destroy, and the
// workflow continues with the managed storage stage and the managed backend
// finalizer when the deployment has them. Inspection error under a managed
// backend: the bucket itself may already be finalized, which reads as an
// unreadable state, so `redis/finalize-only` routes straight to the
// finalizer, which proves absence or owned retirement before it succeeds.
// Everywhere else an unreadable state stays an error: a failed read never
// means absence.
export async function loadInfrastructureStep(opts: Opts, deps: LoadDeps = {}): Promise<Opts> {
  try {
    const isDelete = opts["red/event"] === "delete";
    const result: Map = await (deps.readDeployment ?? read_deployment)(opts, environment(opts), {}, compute.requirements(opts));
    switch (result.status) {
      case "present": {
        const node = result.cluster?.nodes?.[0] ?? {};
        const ready: Opts = {
          ...opts,
          "colors-compute/cluster": result.cluster,
          "colors-compute/shared": result.shared,
          ip: node.ip, user: node.user, "red/exit": 0,
          ...(node.ssh_identity_file ? { "ssh-private-key-path": node.ssh_identity_file } : {}),
        };
        return opts["red/event"] === "rehearse" && storage.managed(opts)
          ? await (deps.readCredentials ?? storage.readCredentials)(ready)
          : ready;
      }
      case "destroyed":
        return isDelete
          ? { ...opts, "redis/already-destroyed": true, "red/exit": 0 }
          : { ...opts, "red/exit": 1, "red/err": "compute deployment is destroyed" };
      case "absent":
        return isDelete && (storage.managed(opts) || managedBackend(opts))
          ? { ...opts, "redis/already-destroyed": true, "red/exit": 0 }
          : { ...opts, "red/exit": 1, "red/err": "compute inspection refused; existing owned state is required" };
      default:
        return isDelete && managedBackend(opts)
          ? { ...opts, "redis/finalize-only": true, "red/exit": 0 }
          : { ...opts, "red/exit": 1, "red/err": "compute inspection refused; existing owned state is required" };
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    return { ...opts, "red/exit": 1, "red/err": message || "compute inspection refused; existing owned state is required" };
  }
}

// ---------------------------------------------------------- ansible (local)

// Only what a `build` genuinely knows. The address, the user and the alias are
// run-time facts and reach the play as extra-vars instead, so the rendered
// playbook carries no IP and is identical on every workstation (SSH Config
// Standard section 6).
export function ansibleLocalData(opts: Opts): Opts {
  const { [storage.credentialsKey]: _credentials, ...rest } = opts;
  return {
    ...rest,
    "ssh-keygen": validate.keygen(opts),
    "ssh-config-identity-file": sshConfig.identityFile(opts),
  };
}

export function ansibleLocalSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleLocalTool);
  const data = ansibleLocalData(opts);
  return [
    spec(template("ansible-local/ansible.cfg", ansibleLocalCfg), `${dir}/ansible.cfg`, data),
    spec(template("ansible-local/inventory.ini", ansibleLocalInventory), `${dir}/inventory.ini`, data),
    spec(template("ansible-local/main.yml", ansibleLocalMain), `${dir}/main.yml`, data),
  ];
}

export interface LocalDeps {
  ansibleWithSpec?: typeof ansible.ansibleWithSpec;
}

// Write or remove the `~/.ssh/config` block. The same playbook serves both
// events; `block_state` is what distinguishes them.
export async function ansibleLocalStep(opts: Opts, deps: LocalDeps = {}): Promise<Opts> {
  const dir = toolDir(opts, ansibleLocalTool);
  const isDelete = opts["red/event"] === "delete";
  const node = compute.node(opts);
  return (deps.ansibleWithSpec ?? ansible.ansibleWithSpec)(opts, {
    dir,
    inventory: "inventory.ini",
    playbooks: { create: "main.yml", delete: "main.yml" },
    extraVars: {
      host_alias: sshConfig.hostAlias(opts),
      ssh_hosts: [{ name: sshConfig.hostAlias(opts), ip: node.ip, user: node.user }],
      block_state: isDelete ? "absent" : "present",
    },
  }, ansibleLocalSpecs(opts));
}

// ---------------------------------------------------------------- ansible

// Cheshire's pretty layout, which is green's byte-level contract for the
// inventory: spaces around colons, insertion order kept.
export function pretty(value: unknown, indent = 0): string {
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return `[ ${value.map((item) => pretty(item, indent)).join(", ")} ]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value as Map);
    if (entries.length === 0) return "{ }";
    const pad = " ".repeat(indent + 2);
    return `{\n${entries
      .map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${pretty(nested, indent + 2)}`)
      .join(",\n")}\n${" ".repeat(indent)}}`;
  }
  return JSON.stringify(value ?? null);
}

export function inventory(opts: Opts): string {
  const node = compute.node(opts);
  const identity = opts["ssh-private-key-path"] ?? node.ssh_identity_file;
  return pretty({
    all: {
      children: {
        redis: {
          hosts: {
            [String(opts.profile)]: {
              ansible_host: node.ip,
              ansible_user: node.user,
              ...(identity !== undefined && identity !== null ? { ansible_ssh_private_key_file: identity } : {}),
            },
          },
        },
      },
    },
  });
}

// Template values for the Ansible stage.
//
// Deliberately carries no operator secret. The backup pair reaches the host as
// Ansible `lookup('env', ...)` expressions written literally into main.yml,
// where the preserved Jinja delimiters pass them through untouched; routing
// them through this map instead would let the renderer HTML-escape the quotes
// and hand Ansible `&#39;`. The secret therefore exists only in the process
// that needs it: not in `.colors/`, not in a golden, not in this map. The
// managed storage output is dropped for the same reason.
export function ansibleData(opts: Opts): Opts {
  const { [storage.credentialsKey]: _credentials, ...rest } = opts;
  return {
    ...rest,
    ip: compute.node(opts).ip,
    "ssh-keygen": validate.keygen(opts),
    "redis-backup-set-prefix": setPrefix(opts),
  };
}

export const ansibleFiles = [
  "ansible.cfg", "main.yml", "cleanup.yml", "rehearsal.yml", "compose.yml",
  "r2-env.sh", "redis-backup.sh", "redis-restore-check.sh",
  "redis-smoke.sh", "redis-monitor.sh", "redis-status.sh",
];

const ansibleContent: Record<string, string> = {
  "ansible.cfg": ansibleCfg,
  "main.yml": ansibleMain,
  "cleanup.yml": ansibleCleanup,
  "rehearsal.yml": ansibleRehearsal,
  "compose.yml": ansibleCompose,
  "r2-env.sh": ansibleR2Env,
  "redis-backup.sh": ansibleBackup,
  "redis-restore-check.sh": ansibleRestoreCheck,
  "redis-smoke.sh": ansibleSmoke,
  "redis-monitor.sh": ansibleMonitor,
  "redis-status.sh": ansibleStatus,
};

export function ansibleSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleTool);
  const data = ansibleData(opts);
  return [
    ...ansibleFiles.map((file) => spec(template(`ansible/${file}`, ansibleContent[file]!), `${dir}/${file}`, data)),
    rawSpec(`${dir}/inventory.json`, inventory(data)),
  ];
}

export const playTimeoutMs = 7200000;

// What ansible-playbook runs with beyond the ambient environment: host-key
// checking off, as the SDK step does for hosts whose keys change on every
// create, and with managed storage the scoped backup pair under the same
// COLORS_PAR_REDIS_BACKUP_R2_* names an operator exports for an external
// bucket. main.yml's `lookup('env', ...)` expressions therefore hold for both.
export function playEnv(opts: Opts, credentials: boolean): Record<string, string> {
  return {
    ANSIBLE_HOST_KEY_CHECKING: "False",
    ...(credentials && storage.managed(opts) ? storage.credentialEnv(opts) : {}),
  };
}

export interface PlayDeps {
  exec?: (args: string[], options: ExecOptions) => Promise<ExecResult>;
}

// Scaffold the Ansible tree and run `playbook` in it. Mirrors the SDK's
// `ansibleWithSpec`, which cannot take an environment: build renders and
// stops; delete renders, runs, then removes the rendered tree; every other
// event renders and runs. The PLAY RECAP lands under `ansible/recap` and a
// failure carries the play's output, as the SDK step's does.
export async function runPlay(opts: Opts, playbook: string, credentials: boolean, deps: PlayDeps = {}): Promise<Opts> {
  const specs = ansibleSpecs(opts);
  const event = opts["red/event"];
  if (event === "build") return scaffold(opts, specs);
  const rendered = { ...scaffold({ ...opts, "red/event": "create" }, specs), "red/event": event };
  const result = await (deps.exec ?? runtime.exec)(
    ["ansible-playbook", "-i", "inventory.json", playbook],
    { cwd: toolDir(opts, ansibleTool), env: playEnv(opts, credentials), timeoutMs: playTimeoutMs },
  );
  // A runtime timeout reports a negative exit; anything but 0 is a failure.
  const raw = result.exit;
  const exit = typeof raw !== "number" ? 1 : raw === 0 ? 0 : raw > 0 ? raw : 1;
  if (exit > 0) {
    return {
      ...rendered, "red/exit": exit,
      "red/err": `ansible-playbook ${playbook} failed: ${result.out || result.err || "(no output)"}`,
    };
  }
  const succeeded = { ...rendered, "red/exit": 0, "ansible/recap": ansible.parseRecap(result.out) };
  return event === "delete" ? scaffold(succeeded, specs) : succeeded;
}

export async function ansibleStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] === "delete" && !opts.ip) {
    // No compute in state: there is no host to stop, and the cleanup play
    // would only fail against the placeholder address.
    return { ...opts, "red/exit": 0 };
  }
  return runPlay(opts, opts["red/event"] === "delete" ? "cleanup.yml" : "main.yml", true);
}

// The recovery rehearsal: a fresh backup set, its restore into a scratch
// instance of the pinned image, the smoke key read back from the restored
// data, and only then the recovery marker. Runs the same rendered tree as the
// converge, with the scoped pair when storage is managed.
export async function rehearsalStep(opts: Opts): Promise<Opts> {
  return runPlay(opts, "rehearsal.yml", true);
}

// ------------------------------------------------------------- acceptance

// Run `args` with `env` overlaid, returning the result map. Nothing from the
// child is echoed; callers decide what becomes an error message, so a secret
// passed through `env` can never leak into output by default.
export async function runQuiet(args: string[], env: Record<string, string>, timeoutMs: number): Promise<ExecResult> {
  return runtime.exec(args, { ...(Object.keys(env).length > 0 ? { env } : {}), timeoutMs });
}

// A redis-cli invocation against a local port with an explicit everything.
// `env -i` clears the environment and re-admits only PATH and, when `auth`,
// the password handed over through the runner as REDISCLI_AUTH, so no
// ambient variable can alter what the probe proves and the password never
// appears on a command line. Error replies are text on stdout, not exit
// codes, so callers grep the reply.
export function redisArgs(port: number, auth: boolean, ...cmd: string[]): string[] {
  return ["bash", "-c",
    'exec env -i PATH="$PATH"' +
    (auth ? ' REDISCLI_AUTH="$REDISCLI_AUTH"' : "") +
    ` redis-cli --no-auth-warning -h 127.0.0.1 -p ${port} ` +
    cmd.map(posixQuote).join(" ")];
}

// An ssh tunnel through the generated `~/.ssh/config` alias, the supported
// client path, exercised end to end: the alias, the identity file, and the
// forward. `-f` returns once the forward is up; the remote `sleep` bounds its
// lifetime so nothing needs killing on the way out. The bash wrapper exists
// for the streams: the daemonized child inherits stdout/stderr, and a runner
// that waits for the pipes to close would otherwise block until the sleep
// expires, returning exactly when the tunnel dies.
export function tunnelArgs(opts: Opts, port: number): string[] {
  return ["bash", "-c",
    "ssh -f -o ExitOnForwardFailure=yes -o BatchMode=yes" +
    ` -L ${port}:127.0.0.1:${opts["redis-port"]} ` +
    `${sshConfig.hostAlias(opts)} sleep 45 >/dev/null 2>&1`];
}

// A TCP connect to the machine's public address on the Redis port, bounded
// by a timeout. It must FAIL: the port is bound to loopback only and the
// firewall admits 22 alone.
export function closedPortArgs(ip: unknown, port: unknown): string[] {
  return ["bash", "-c", `timeout 5 bash -c 'exec 3<>/dev/tcp/${ip}/${port}'`];
}

export const passwordFile = "/etc/redis/secrets/password";
export const remotePasswordCommand = `cat ${passwordFile} 2>/dev/null || sudo -n cat ${passwordFile}`;

// The generated Redis password, read over SSH and held only in this process.
// Never merged into opts, never printed.
export async function readRemotePassword(opts: Opts): Promise<string | undefined> {
  // root on the Vultr and DigitalOcean images, ubuntu on the AWS AMI: the
  // plain read serves the first, the passwordless-sudo fallback the second.
  const result = await runQuiet(["ssh", "-o", "BatchMode=yes", sshConfig.hostAlias(opts),
    remotePasswordCommand], {}, 20000);
  return result.exit === 0 ? String(result.out ?? "").trim() : undefined;
}

export function reply(result: ExecResult): string {
  return (String(result.out ?? "") + String(result.err ?? "")).trim();
}

// The operator-path gate, after a real create.
//
// The server-side gates already ran inside the playbook (the round-trip, the
// configuration, the auth negatives, the bind addresses, persistence across
// a restart, the first backup set). What is checked from here is what only
// this side can check: that an operator on this workstation reaches Redis
// through the generated SSH config and a tunnel with the generated password
// and not without it, and that the public address does not answer on the
// Redis port at all.
export async function acceptanceStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] !== "create") return { ...opts, "red/exit": 0 };
  const password = await readRemotePassword(opts);
  const ip = opts.ip;
  const port = opts["redis-port"];
  const publicProbe = await runQuiet(closedPortArgs(ip, port), {}, 15000);
  if (!password) {
    return { ...opts, "red/exit": 1, "red/err": "acceptance: could not read the generated Redis password over ssh" };
  }
  if (publicProbe.exit === 0) {
    return { ...opts, "red/exit": 1,
      "red/err": `acceptance: ${ip}:${port} accepted a connection from the internet; the port must not be public` };
  }
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const local = 20000 + Math.floor(Math.random() * 40000);
    const tunnel = await runQuiet(tunnelArgs(opts, local), {}, 30000);
    if (tunnel.exit !== 0) continue;
    const stamp = `operator-${Date.now()}`;
    const set = await runQuiet(redisArgs(local, true, "SET", "colors:operator", stamp), { REDISCLI_AUTH: password }, 30000);
    const get = await runQuiet(redisArgs(local, true, "GET", "colors:operator"), { REDISCLI_AUTH: password }, 30000);
    const anonymous = await runQuiet(redisArgs(local, false, "PING"), {}, 30000);
    const wrong = await runQuiet(redisArgs(local, true, "PING"), { REDISCLI_AUTH: "not-the-password" }, 30000);
    if (reply(set) !== "OK") {
      return { ...opts, "red/exit": 1,
        "red/err": `acceptance: SET through the tunnel answered '${reply(set)}', expected OK` };
    }
    if (reply(get) !== stamp) {
      return { ...opts, "red/exit": 1,
        "red/err": `acceptance: GET through the tunnel answered '${reply(get)}', expected ${stamp}` };
    }
    if (!reply(anonymous).includes("NOAUTH")) {
      return { ...opts, "red/exit": 1,
        "red/err": `acceptance: an unauthenticated PING answered '${reply(anonymous)}' instead of NOAUTH` };
    }
    if (reply(wrong).includes("PONG") || !/WRONGPASS|NOAUTH/.test(reply(wrong))) {
      return { ...opts, "red/exit": 1,
        "red/err": `acceptance: a wrong password answered '${reply(wrong)}' instead of a refusal` };
    }
    return { ...opts, "red/exit": 0,
      "redis/acceptance": { tunnel: "ok", "round-trip": stamp, unauthenticated: "refused",
        "wrong-password": "refused", "public-port": "closed" } };
  }
  return { ...opts, "red/exit": 1,
    "red/err": "acceptance: no local port could carry the ssh tunnel after three attempts" };
}

// --------------------------------------------------------------- describe

export const monitorFile = "/var/lib/colors/redis-monitor.json";

// Read the host's last monitor result over SSH and print it. Exits non-zero
// when the host is unreachable or reports unhealthy; this is what an external
// poller runs.
export async function describeStep(opts: Opts): Promise<Opts> {
  const alias = sshConfig.hostAlias(opts);
  const result = await runQuiet(["ssh", "-o", "BatchMode=yes", alias, "cat", monitorFile], {}, 20000);
  let parsed: Map | undefined;
  try {
    parsed = JSON.parse(String(result.out ?? "").trim());
  } catch {
    parsed = undefined;
  }
  const reachable = result.exit === 0;
  const healthy = Boolean(parsed?.healthy);
  const problems: unknown[] | undefined = parsed?.problems ?? (reachable ? undefined : ["unreachable or no monitor result yet"]);
  const status = !reachable ? "UNKNOWN" : healthy ? "ok" : "UNHEALTHY";
  const detail = String(parsed?.checked ?? "") + (problems?.length ? ` ${problems.join("; ")}` : "");
  runtime.log(`${alias.padEnd(32)} ${status.padEnd(10)} ${detail}`);
  return {
    ...opts,
    "red/exit": reachable && healthy ? 0 : 1,
    "redis/describe": { host: alias, reachable, healthy, checked: parsed?.checked, problems },
  };
}
