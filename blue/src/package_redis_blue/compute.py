"""Redis requests one public host; colors-compute owns provider operations."""

from __future__ import annotations

from colors_compute import collect, expand, plan_deployment, source_cidrs


def topology(_opts: dict) -> list[dict]:
    return [{"role": None, "count": 1}]


def requirements(opts: dict) -> dict:
    return {"single_host": True, "private": False,
            "legacy_state_keys": [f"{opts.get('profile')}/redis-infrastructure.tfstate"],
            "security": {"egress": "all", "private_filter": False,
                         "ingress": [{"id": "ssh", "protocol": "tcp", "from_port": 22, "to_port": 22,
                                      "sources": source_cidrs(opts, "ssh-sources", "redis-ssh-sources")}]}}


def node(opts: dict) -> dict:
    cluster = opts.get("colors-compute/cluster")
    if not cluster and (opts.get("blue/event") == "build" or opts.get("blue/dry-run")):
        cluster = plan_deployment(opts, topology(opts), requirements(opts))["cluster"]
    if not cluster:
        raise RuntimeError("compute result unavailable; refusing placeholder inventory")
    requests = [{**request, "provider": opts.get("provider-compute")} for request in expand(topology(opts))]
    return collect(requests, cluster["nodes"], "0")["nodes"][0]
