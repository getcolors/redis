import { readPars } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, failed, workflow, type NextPair, type Opts, type WireDecl } from "red/workflow";
import { finalize_backend } from "colors-compute-red";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as storage from "./storage.ts";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "provider-compute": validate.defaultComputeProvider,
  "provider-backend": "r2",
  "compute-prevent-destroy": true,
  "redis-storage-managed": false,
  workdir: ".colors",
};

export async function startStep(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
): Promise<Opts> {
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_current, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      (current, _environment, { event, real }) => (real ? validate.secretErrors(current, event) : []),
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? ["compute destruction is protected; set COLORS_PAR_COMPUTE_PREVENT_DESTROY=false to delete"]
          : [],
    ],
    afterValidate: (current, _environment, { event, real }) =>
      real && event === "create"
        ? sshConfig.preflight(current)
        : { ...(real ? current : ssh.withMachineKey(current)), "red/exit": 0 },
  }, env);
}

export interface FinalizeDeps {
  finalizeBackend?: (opts: Opts, environment: Record<string, string | undefined>) => Promise<Record<string, any>>;
}

// Delete the managed S3 state bucket after everything in it has been
// destroyed. The library proves the bucket holds nothing but retired state
// before it removes anything; a refusal is an error, never a skipped step.
export async function backendFinalizeStep(opts: Opts, deps: FinalizeDeps = {}): Promise<Opts> {
  try {
    const result = await (deps.finalizeBackend ?? finalize_backend)(opts, tools.environment(opts));
    return ["destroyed", "absent", "skipped"].includes(result.status)
      ? { ...opts, "red/exit": 0 }
      : { ...opts, "red/exit": 1, "red/err": "managed backend finalization refused" };
  } catch {
    return { ...opts, "red/exit": 1, "red/err": "managed backend finalization refused; live or unowned state remains" };
  }
}

// The DAG. Create: compute, then the managed bucket the host will write to,
// then the alias, the converge and the acceptance. Delete is the reverse with
// one deliberate exception: the bucket outlives the machine the way the
// keypair does, so the last backup timer run never fails against a missing
// bucket, and the managed state bucket goes last of all.
export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  const managedStorage = storage.managed(runOpts);
  const managedBackend = tools.managedBackend(runOpts);
  switch (runOpts["red/event"]) {
    case "delete": {
      const graph: Record<string, WireDecl> = {
        "redis/start": [startStep, "redis/load-infrastructure"],
        "redis/load-infrastructure": [tools.loadInfrastructureStep, "redis/ansible"],
        "redis/ansible": [tools.ansibleStep, "redis/ssh-config"],
        "redis/ssh-config": [tools.ansibleLocalStep, "redis/infrastructure"],
        "redis/infrastructure": managedStorage
          ? [tools.infrastructureStep, "redis/storage"]
          : managedBackend
            ? [tools.infrastructureStep, "redis/backend-finalize"]
            : [tools.infrastructureStep],
        "redis/storage": managedBackend ? [storage.step, "redis/backend-finalize"] : [storage.step],
        "redis/backend-finalize": [backendFinalizeStep],
      };
      return graph[step];
    }
    case "rehearse": {
      const graph: Record<string, WireDecl> = {
        "redis/start": [startStep, "redis/load-infrastructure"],
        "redis/load-infrastructure": [tools.loadInfrastructureStep, "redis/rehearsal"],
        "redis/rehearsal": [tools.rehearsalStep],
      };
      return graph[step];
    }
    case "describe": {
      const graph: Record<string, WireDecl> = {
        "redis/start": [startStep, "redis/load-infrastructure"],
        "redis/load-infrastructure": [tools.loadInfrastructureStep, "redis/describe"],
        "redis/describe": [tools.describeStep],
      };
      return graph[step];
    }
    default: {
      const graph: Record<string, WireDecl> = {
        "redis/start": [startStep, "redis/infrastructure"],
        "redis/infrastructure": [tools.infrastructureStep, managedStorage ? "redis/storage" : "redis/ssh-config"],
        "redis/storage": [storage.step, "redis/ssh-config"],
        "redis/ssh-config": [tools.ansibleLocalStep, "redis/ansible"],
        "redis/ansible": [tools.ansibleStep, "redis/acceptance"],
        "redis/acceptance": [tools.acceptanceStep],
      };
      return graph[step];
    }
  }
}

// Successors, with the two repeat-delete routes out of the inspection step:
// a finalized backend goes straight to the finalizer; a destroyed or absent
// machine skips the host and the compute destroy and continues with whatever
// managed stages the deployment has, or stops when it has none.
export function nextFn(step: string, successors: string[] | null, opts: Opts): NextPair[] {
  if (failed(opts)) return [];
  if (step === "redis/load-infrastructure" && opts["redis/finalize-only"]) return [["redis/backend-finalize", opts]];
  if (step === "redis/load-infrastructure" && opts["redis/already-destroyed"]) {
    if (storage.managed(opts)) return [["redis/storage", opts]];
    if (tools.managedBackend(opts)) return [["redis/backend-finalize", opts]];
    return [];
  }
  return (successors ?? []).map((next) => [next, opts] as const);
}

export const storageBackendAdvice = tofu.conventionalBackendAdvice({
  dir: storage.directory,
  key: (opts) => `${opts.profile}/${storage.tool}.tfstate`,
});

export const sideEffecting = [
  "redis/load-infrastructure", "redis/infrastructure", "redis/storage", "redis/ssh-config",
  "redis/ansible", "redis/acceptance", "redis/rehearsal", "redis/describe",
  "redis/backend-finalize",
];

function create() {
  let wf = workflow({ start: "redis/start", wireFn, nextFn });
  wf = adviceAdd(wf, "redis/storage", "before", "redis.workflow/storage-backend", storageBackendAdvice);
  return dryRun.advise(progress.advise(wf), sideEffecting);
}

export const redisWorkflow = create();
