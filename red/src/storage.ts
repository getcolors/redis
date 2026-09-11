// The deployment-owned S3 backup bucket and its scoped credentials.
//
// With `redis-storage-managed: true` the package owns the bucket named by
// `redis-backup-r2-bucket` in its own OpenTofu stage: the bucket, its public
// access block and encryption, one IAM user scoped to that bucket, and one
// access key. The key pair is a sensitive stage output. It never enters a
// template value or a rendered file; it is read from state when a play needs
// it and handed to ansible-playbook as the same COLORS_PAR_REDIS_BACKUP_R2_*
// variables an operator would export for an external bucket, so main.yml's
// `lookup('env', ...)` expressions are unchanged. Modelled on
// neon-multi-node's storage module.
import { stageDir } from "red/cli";
import { runtime, type ExecOptions } from "red/runtime";
import { PRESERVE_JINJA_DELIMITERS, scaffold, type Spec } from "red/scaffold";
import * as tofu from "red/tofu";
import type { Opts } from "red/workflow";
import mainTf from "../resources/tools/storage/main.tf" with { type: "text" };

export const tool = "redis-storage";
export const credentialsKey = "redis/storage-credentials";
export const bucketRole = "backup";
export const credentialPrefix = "REDIS_BACKUP_R2";

export function managed(opts: Opts): boolean {
  return opts["redis-storage-managed"] === true;
}

export function directory(opts: Opts): string {
  return stageDir(opts, tool, { defaultProfile: "redis" });
}

// AWS_* variables for tofu and the AWS CLI, overlaid from the optional
// COLORS_PAR_AWS_* pars. Absent pars leave the ambient credential chain alone.
export function awsEnv(opts: Opts): Record<string, string> {
  const mapping: Array<[string, string]> = [
    ["aws-access-key-id", "AWS_ACCESS_KEY_ID"],
    ["aws-secret-access-key", "AWS_SECRET_ACCESS_KEY"],
    ["aws-session-token", "AWS_SESSION_TOKEN"],
  ];
  const env: Record<string, string> = {};
  for (const [key, variable] of mapping) {
    const value = String(opts[key] ?? "");
    if (value.length > 0) env[variable] = value;
  }
  return env;
}

export function specs(opts: Opts): Spec[] {
  const { [credentialsKey]: _credentials, ...data } = opts;
  return [{
    template: { name: "storage/main.tf", content: mainTf },
    target: `${directory(opts)}/main.tf`,
    data,
    opts: PRESERVE_JINJA_DELIMITERS,
  }];
}

async function checked(args: string[], options: ExecOptions): Promise<string> {
  const result = await runtime.exec(args, options);
  if (result.exit !== 0) throw new Error("managed storage state operation failed");
  return result.out;
}

// Refuse an existing bucket unless this stage already owns its address.
export async function ownershipPreflight(opts: Opts): Promise<void> {
  const options: ExecOptions = { cwd: directory(opts), env: awsEnv(opts) };
  await checked(["tofu", "init", "-input=false", "-no-color"], options);
  const state = await runtime.exec(["tofu", "state", "list"], options);
  const emptyState = state.exit === 1 && String(state.err ?? "").includes("No state file was found!");
  if (!(state.exit === 0 || emptyState)) throw new Error("managed storage state unavailable");
  const addresses = new Set((emptyState ? "" : state.out).split(/\r?\n/));
  const recorded: Record<string, unknown> = {};
  if (addresses.size > 0) {
    const shown = JSON.parse(await checked(["tofu", "show", "-json"], options));
    for (const resource of shown?.values?.root_module?.resources ?? []) {
      recorded[resource.address] = resource?.values?.bucket;
    }
  }
  const bucket = opts["redis-backup-r2-bucket"];
  if (bucket !== recorded[`aws_s3_bucket.application["${bucketRole}"]`]) {
    const probe = await runtime.exec(
      ["aws", "s3api", "head-bucket", "--bucket", String(bucket), "--region", String(opts["redis-backup-r2-region"])],
      options,
    );
    // 403, network failures and a successful probe all fail closed.
    if (!(probe.exit > 0 && /\(404\)|Not Found|NoSuchBucket/.test(String(probe.err ?? "")))) {
      throw new Error("managed storage refuses to adopt an existing or inaccessible bucket");
    }
  }
}

// Create, render or destroy the storage stage. Not managed: a no-op.
export async function step(opts: Opts): Promise<Opts> {
  if (!managed(opts)) return { ...opts, "red/exit": 0 };
  try {
    const documents = specs(opts);
    if (opts["red/event"] === "create") {
      scaffold(opts, documents);
      await ownershipPreflight(opts);
    }
    // The scoped pair stays in memory and in the encrypted backend state;
    // never copy it into template values or print the output object.
    return await tofu.tofuWithSpec(opts, documents, { dir: directory(opts), env: awsEnv(opts), outputKey: credentialsKey });
  } catch {
    return { ...opts, "red/exit": 1, "red/err": "managed S3 storage failed; inspect bucket ownership, state access, and AWS permissions" };
  }
}

// The COLORS_PAR_REDIS_BACKUP_R2_* pair for ansible-playbook, from the
// storage stage output. Throws when the output is missing or blank.
export function credentialEnv(opts: Opts): Record<string, string> {
  const pair = opts[credentialsKey]?.credentials?.[bucketRole] ?? {};
  const accessKeyId = String(pair.access_key_id ?? "");
  const secretAccessKey = String(pair.secret_access_key ?? "");
  if (!accessKeyId.trim() || !secretAccessKey.trim()) throw new Error("managed storage credentials unavailable");
  return {
    [`COLORS_PAR_${credentialPrefix}_ACCESS_KEY_ID`]: accessKeyId,
    [`COLORS_PAR_${credentialPrefix}_SECRET_ACCESS_KEY`]: secretAccessKey,
  };
}

// Read the scoped pair back from the storage state for a verb that runs a
// play without converging the stage (rehearse). Not managed: opts unchanged.
export async function readCredentials(opts: Opts): Promise<Opts> {
  if (!managed(opts)) return opts;
  try {
    tofu.conventionalBackendAdvice({ dir: directory, key: (o) => `${o.profile}/${tool}.tfstate` })(opts);
    scaffold({ ...opts, "red/event": "build" }, specs(opts));
    await checked(["tofu", "init", "-input=false", "-no-color"], { cwd: directory(opts), env: awsEnv(opts) });
    const result = { ...opts, [credentialsKey]: await tofu.outputs(directory(opts), awsEnv(opts)) };
    credentialEnv(result);
    return result;
  } catch {
    throw new Error("managed storage credentials unavailable; converge storage before rehearsal");
  }
}
