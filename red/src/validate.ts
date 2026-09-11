import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { backend_plan, keyMode } from "colors-compute-red";

export const profilePar = parName("profile");

export const defaultComputeProvider = "vultr";

export const required = [
  "profile", "workdir", "provider-compute", "provider-backend",
  "compute-prevent-destroy",
  "redis-image", "redis-port",
  "redis-backup-r2-bucket", "redis-backup-r2-endpoint", "redis-backup-r2-region",
  "redis-backup-oncalendar", "redis-backup-retention-days",
  "redis-backup-max-age-hours",
];

// `tag@sha256:...` pins both the human-readable release and the exact bytes.
// Docker Hub republishes the `7.2` and `7.2.16` tags whenever the base image
// is rebuilt, which is why the digest is required rather than the tag denied.
const imageRe = /^[^\s:@]+(?:\/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64}|:[^\s:@]+@sha256:[0-9a-f]{64})$/;
const urlRe = /^https:\/\/[^\s]+$/;

export function missing(value: unknown): boolean {
  return value === null || value === undefined ||
    (typeof value === "string" && value.trim() === "");
}

export function keygen(opts: Opts): boolean {
  return keyMode(opts).mode === "managed";
}

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

function positiveInt(value: unknown): boolean {
  return Number.isInteger(value) && (value as number) > 0;
}

export function managedStorage(opts: Opts): boolean {
  return opts["redis-storage-managed"] === true;
}

// The S3 endpoint of one AWS region, the only endpoint a managed bucket has.
export function awsEndpoint(region: unknown): string {
  return `https://s3.${region}.amazonaws.com`;
}

// The managed-storage contract: the package creates the bucket in the AWS
// region the state backend lives in, under the managed S3 backend, so one
// finalize proves one account's resources gone.
export function storageErrors(opts: Opts): string[] {
  if (!managedStorage(opts)) return [];
  const region = opts["s3-region"];
  const bucket = String(opts["redis-backup-r2-bucket"] ?? "");
  const errors: string[] = [];
  if (opts["provider-backend"] !== "s3") errors.push(":redis-storage-managed requires provider-backend s3");
  if (opts["s3-bucket-mode"] !== "managed") errors.push(":redis-storage-managed requires s3-bucket-mode managed");
  if (!(!missing(region) && region === opts["redis-backup-r2-region"])) {
    errors.push(":redis-backup-r2-region must equal s3-region when storage is managed");
  }
  if (!(!missing(region) && awsEndpoint(region) === opts["redis-backup-r2-endpoint"])) {
    errors.push(`:redis-backup-r2-endpoint must be ${awsEndpoint(region ?? "<s3-region>")} when storage is managed`);
  }
  if (bucket.includes(".")) errors.push(":redis-backup-r2-bucket must not contain dots when storage is managed");
  if (bucket === String(opts["s3-bucket"] ?? "")) errors.push(":redis-backup-r2-bucket must differ from s3-bucket");
  return errors;
}

// Application settings and the library backend contract.
export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of required) {
    if (missing(opts[key])) errors.push(`:${key} is required`);
  }
  if (!["s3", "r2"].includes(opts["provider-backend"])) errors.push(":provider-backend must be s3 or r2");
  if (typeof opts["compute-prevent-destroy"] !== "boolean") errors.push(":compute-prevent-destroy must be true or false");
  if (typeof opts["redis-storage-managed"] !== "boolean") errors.push(":redis-storage-managed must be true or false");
  errors.push(...storageErrors(opts));
  const image = opts["redis-image"];
  if (!missing(image) && !imageRe.test(String(image))) {
    errors.push(":redis-image must carry an explicit image tag or digest");
  }
  if (!missing(image) && !String(image).includes("@sha256:")) {
    errors.push(":redis-image must be pinned by digest (tag@sha256:...)");
  }
  const port = opts["redis-port"];
  if (!missing(port) && !(Number.isInteger(port) && port >= 1 && port <= 65535)) {
    errors.push(":redis-port must be an integer between 1 and 65535");
  }
  if (!(missing(opts["redis-backup-r2-endpoint"]) || urlRe.test(String(opts["redis-backup-r2-endpoint"])))) {
    errors.push(":redis-backup-r2-endpoint must be an https URL");
  }
  for (const key of ["redis-backup-retention-days", "redis-backup-max-age-hours"]) {
    const value = opts[key];
    if (!missing(value) && !positiveInt(value)) errors.push(`:${key} must be a positive integer`);
  }
  try {
    backend_plan(opts, `${opts.profile}/shared.tfstate`);
  } catch (error) {
    errors.push(error instanceof Error ? error.message : String(error));
  }
  return errors;
}

// What converging the machine needs, and therefore only a create: the R2 pair
// the backup sets are written with. The Redis password is deliberately absent:
// it is generated on the server, once, and never operator-supplied. With
// managed storage the pair is a storage stage output, not an operator secret,
// so a create requires nothing from the environment.
export const applicationSecrets = ["redis-backup-r2-access-key-id", "redis-backup-r2-secret-access-key"];

export function secretErrors(opts: Opts, event: string | undefined): string[] {
  const keys = event === "create" && !managedStorage(opts) ? applicationSecrets : [];
  return keys.filter((key) => missing(opts[key]))
    .map((key) => `required credential is not set: ${parName(key)}`);
}
