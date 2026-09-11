// Redis requests one public host; colors-compute owns provider operations.
import type { Opts } from "red/workflow";
import { collect, expand, plan_deployment, source_cidrs } from "colors-compute-red";

type Map = Record<string, any>;

export function topology(_opts: Opts): Map[] {
  return [{ role: null, count: 1 }];
}

export function requirements(opts: Opts): Map {
  return {
    single_host: true,
    private: false,
    legacy_state_keys: [`${opts.profile}/redis-infrastructure.tfstate`],
    security: {
      egress: "all",
      private_filter: false,
      ingress: [{
        id: "ssh", protocol: "tcp", from_port: 22, to_port: 22,
        sources: source_cidrs(opts, "ssh-sources", "redis-ssh-sources"),
      }],
    },
  };
}

// The one node of the deployment: the recorded cluster when a real event has
// produced one, the library's planning result on build and dry-run, and a
// refusal otherwise. A placeholder inventory must never reach a real host.
export function node(opts: Opts): Map {
  const planning = opts["red/event"] === "build" || Boolean(opts["red/dry-run"]);
  const cluster = opts["colors-compute/cluster"] ??
    (planning ? plan_deployment(opts, topology(opts), requirements(opts)).cluster : undefined);
  if (!cluster) throw new Error("compute result unavailable; refusing placeholder inventory");
  const requests = expand(topology(opts)).map((declared) => ({ ...declared, provider: opts["provider-compute"] }));
  return collect(requests, cluster.nodes, "0").nodes[0]!;
}
