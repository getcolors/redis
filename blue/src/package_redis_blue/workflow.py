"""The graph, the port of io.github.getcolors.redis.workflow."""

from __future__ import annotations

from blue import dry_run, progress, tofu
from blue.cli import read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, failed, workflow
from colors_compute.managed_backend import finalize_backend

from . import ssh, ssh_config, storage, tools, validate
from .utils import clj_str as _s

DEFAULTS = {"provider-compute": validate.DEFAULT_COMPUTE_PROVIDER,
            "provider-backend": "r2", "compute-prevent-destroy": True,
            "redis-storage-managed": False, "workdir": ".colors"}


async def start_step(original: dict, env: dict | None = None) -> dict:
    def after(opts, _env, context):
        if context["real"] and context["event"] == "create":
            return ssh_config.preflight(opts)
        return {**(opts if context["real"] else ssh.with_machine_key(opts)), "blue/exit": 0}

    return await preflight(
        original, defaults=DEFAULTS, overlay=read_pars, env=env,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            lambda o, _e, c: validate.secret_errors(o, c["event"]) if c["real"] else [],
            lambda o, _e, c: (["compute destruction is protected; set COLORS_PAR_COMPUTE_PREVENT_DESTROY=false to delete"]
                              if c["real"] and c["event"] == "delete" and o.get("compute-prevent-destroy") else []),
        ],
        after_validate=after)


async def backend_finalize_step(opts: dict) -> dict:
    """Delete the managed S3 state bucket after everything in it has been
    destroyed. The library proves the bucket holds nothing but retired state
    before it removes anything; a refusal is an error, never a skipped step."""
    try:
        result = await finalize_backend(opts, tools.environment(opts))
        if result.get("status") in ("destroyed", "absent", "skipped"):
            return {**opts, "blue/exit": 0}
        return {**opts, "blue/exit": 1, "blue/err": "managed backend finalization refused"}
    except Exception:
        return {**opts, "blue/exit": 1, "blue/err": "managed backend finalization refused; live or unowned state remains"}


def wire_fn(step: str, opts: dict):
    """The DAG. Create: compute, then the managed bucket the host will write
    to, then the alias, the converge and the acceptance. Delete is the reverse
    with one deliberate exception: the bucket outlives the machine the way the
    keypair does, so the last backup timer run never fails against a missing
    bucket, and the managed state bucket goes last of all."""
    managed_storage = storage.managed(opts)
    managed_backend = tools.managed_backend(opts)
    event = opts.get("blue/event")
    if event == "delete":
        if step == "redis/infrastructure":
            if managed_storage:
                return [tools.infrastructure_step, "redis/storage"]
            if managed_backend:
                return [tools.infrastructure_step, "redis/backend-finalize"]
            return [tools.infrastructure_step]
        if step == "redis/storage":
            return [storage.step, "redis/backend-finalize"] if managed_backend else [storage.step]
        return {
            "redis/start": [start_step, "redis/load-infrastructure"],
            "redis/load-infrastructure": [tools.load_infrastructure_step, "redis/ansible"],
            "redis/ansible": [tools.ansible_step, "redis/ssh-config"],
            "redis/ssh-config": [tools.ansible_local_step, "redis/infrastructure"],
            "redis/backend-finalize": [backend_finalize_step],
        }.get(step)
    if event == "rehearse":
        return {
            "redis/start": [start_step, "redis/load-infrastructure"],
            "redis/load-infrastructure": [tools.load_infrastructure_step, "redis/rehearsal"],
            "redis/rehearsal": [tools.rehearsal_step],
        }.get(step)
    if event == "describe":
        return {
            "redis/start": [start_step, "redis/load-infrastructure"],
            "redis/load-infrastructure": [tools.load_infrastructure_step, "redis/describe"],
            "redis/describe": [tools.describe_step],
        }.get(step)
    return {
        "redis/start": [start_step, "redis/infrastructure"],
        "redis/infrastructure": [tools.infrastructure_step, "redis/storage" if managed_storage else "redis/ssh-config"],
        "redis/storage": [storage.step, "redis/ssh-config"],
        "redis/ssh-config": [tools.ansible_local_step, "redis/ansible"],
        "redis/ansible": [tools.ansible_step, "redis/acceptance"],
        "redis/acceptance": [tools.acceptance_step],
    }.get(step)


def next_fn(step: str, successors, opts: dict):
    """Successors, with the two repeat-delete routes out of the inspection
    step: a finalized backend goes straight to the finalizer; a destroyed or
    absent machine skips the host and the compute destroy and continues with
    whatever managed stages the deployment has, or stops when it has none."""
    if failed(opts):
        return []
    if step == "redis/load-infrastructure" and opts.get("redis/finalize-only"):
        return [("redis/backend-finalize", opts)]
    if step == "redis/load-infrastructure" and opts.get("redis/already-destroyed"):
        if storage.managed(opts):
            return [("redis/storage", opts)]
        if tools.managed_backend(opts):
            return [("redis/backend-finalize", opts)]
        return []
    return [(s, opts) for s in (successors or [])]


storage_backend_advice = tofu.conventional_backend_advice(
    dir=storage.directory,
    key=lambda o: f"{_s(o.get('profile'))}/{storage.TOOL}.tfstate")

side_effecting = ["redis/load-infrastructure", "redis/infrastructure", "redis/storage", "redis/ssh-config",
                  "redis/ansible", "redis/acceptance", "redis/rehearsal", "redis/describe",
                  "redis/backend-finalize"]


def create_workflow():
    wf = workflow(start="redis/start", wire_fn=wire_fn, next_fn=next_fn)
    wf = advice_add(wf, "redis/storage", "before", "package_redis_blue.workflow/storage-backend", storage_backend_advice)
    return dry_run.advise(progress.advise(wf), side_effecting)


redis_workflow = create_workflow()
