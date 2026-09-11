"""The steps and every template spec, the port of io.github.getcolors.redis.tools."""

from __future__ import annotations

import json
import math
import os
import random
import re
import time
from decimal import Decimal
from pathlib import Path

from blue.ansible import ansible_with_spec, parse_recap
from blue.cli import stage_dir
from blue.process import posix_quote
from blue.runtime import runtime
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, content_spec, scaffold
from colors_compute import backend_plan, plan_deployment
from colors_compute.inspection import read_deployment
from colors_compute.orchestration import orchestrate

from . import compute, ssh_config, storage, validate
from .utils import clj_str as _s

infrastructure_tool = "redis-infrastructure"
ansible_tool = "redis-ansible"
ansible_local_tool = "redis-ansible-local"
ROOT = Path(__file__).parent / "resources"
template_opts = PRESERVE_JINJA_DELIMITERS


def tool_dir(opts: dict, tool: str) -> str:
    return stage_dir(opts, tool, default_profile="redis")


def template(path: str, file: str) -> dict:
    name = f"tools/{path}/{file}"
    return {"name": name, "content": (ROOT / name).read_text()}


def spec(source: dict, target: str, data: dict) -> dict:
    return {"template": source, "target": target, "data": data, "opts": template_opts}


def raw_spec(target: str, content: str) -> dict:
    return content_spec(target, content)


PLACEHOLDER_IP = "192.0.2.10"


def set_prefix(opts: dict) -> str:
    return f"{_s(opts.get('profile'))}/redis"


def environment(opts: dict) -> dict[str, str]:
    """The process environment for the library and for tofu: the ambient one,
    with the optional COLORS_PAR_AWS_* pars overlaid onto AWS_* the way
    neon-multi-node does, so an AWS deployment can carry its own credentials in
    .envrc.private without an operator-level AWS profile."""
    return {**os.environ, **storage.aws_env(opts)}


# ------------------------------------------------------------------- json


def _java_double(value: float) -> str:
    """`Double.toString`, which is what Cheshire writes for a double."""
    if math.isnan(value):
        return "NaN"
    if math.isinf(value):
        return "Infinity" if value > 0 else "-Infinity"
    if value == 0:
        return "-0.0" if math.copysign(1.0, value) < 0 else "0.0"
    sign = "-" if value < 0 else ""
    digits, exponent = Decimal(repr(abs(value))).as_tuple()[1:]
    text = "".join(str(d) for d in digits).rstrip("0") or "0"
    point = len(digits) + exponent
    if 1e-3 <= abs(value) < 1e7:
        if point <= 0:
            return f"{sign}0.{'0' * (-point)}{text}"
        if point >= len(text):
            return f"{sign}{text}{'0' * (point - len(text))}.0"
        return f"{sign}{text[:point]}.{text[point:]}"
    mantissa = text[0] + "." + (text[1:] or "0")
    return f"{sign}{mantissa}E{point - 1}"


def _json_scalar(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return _java_double(value)
    return json.dumps(str(value), ensure_ascii=False)


def _pretty(value, indent: int = 0) -> str:
    """Cheshire's pretty JSON, byte for byte, in insertion order."""
    if isinstance(value, (list, tuple)):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(_pretty(item, indent) for item in value) + " ]"
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad = " " * (indent + 2)
        body = ",\n".join(f"{pad}{json.dumps(str(k), ensure_ascii=False)} : {_pretty(v, indent + 2)}"
                          for k, v in value.items())
        return "{\n" + body + "\n" + " " * indent + "}"
    return _json_scalar(value)


def _compute_json(value, indent: int = 0) -> str:
    """Green's compute document JSON: sorted keys, two-space indent, no space
    before the colon."""
    if isinstance(value, dict):
        if not value:
            return "{}"
        pad = " " * (indent + 2)
        body = ",\n".join(f"{pad}{json.dumps(str(k), ensure_ascii=False)}: {_compute_json(v, indent + 2)}"
                          for k, v in sorted(value.items(), key=lambda item: str(item[0])))
        return "{\n" + body + "\n" + " " * indent + "}"
    if isinstance(value, (list, tuple)):
        if not value:
            return "[]"
        pad = " " * (indent + 2)
        return "[\n" + ",\n".join(f"{pad}{_compute_json(item, indent + 2)}" for item in value) + "\n" + " " * indent + "]"
    return _json_scalar(value)


# ---------------------------------------------------------------- compute


async def infrastructure_step(opts: dict) -> dict:
    try:
        planning = opts.get("blue/event") == "build" or bool(opts.get("blue/dry-run"))
        if planning:
            result = plan_deployment(opts, compute.topology(opts), compute.requirements(opts))
            stages = [("shared", result["state_keys"]["shared"]),
                      *[(f"nodes/{node_id}", key) for node_id, key in result["state_keys"]["nodes"].items()]]
            for stage, key in stages:
                target = Path(tool_dir(opts, infrastructure_tool)) / stage / "backend.tf.json"
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(_compute_json(backend_plan(opts, key)["config"]) + "\n")
            documents = [("shared", result["documents"]["shared"]),
                         *[(f"nodes/{node_id}", docs) for node_id, docs in result["documents"]["nodes"].items()]]
            for stage, docs in documents:
                for filename, document in docs.items():
                    target = Path(tool_dir(opts, infrastructure_tool)) / stage / filename
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text(_compute_json(document) + "\n")
        else:
            result = await orchestrate(opts, compute.topology(opts), compute.requirements(opts), environment(opts))
        if result.get("status") not in ("ready", "planned", "destroyed"):
            errors = result.get("errors") or []
            return {**opts, "blue/exit": 1,
                    "blue/err": "\n".join(errors) if errors else "compute lifecycle refused; inspect state ownership and configuration"}
        output = {**opts, "blue/exit": 0}
        if result.get("shared"):
            output["colors-compute/shared"] = result["shared"]
        if result.get("cluster"):
            node = result["cluster"]["nodes"][0]
            output.update({"colors-compute/cluster": result["cluster"], "ip": node.get("ip"), "user": node.get("user")})
        path = (result.get("key") or {}).get("private_key_path")
        if path:
            output["ssh-private-key-path"] = path.replace("$HOME/.ssh", "/home/build-placeholder/.ssh") if planning else path
        return output
    except Exception:
        return {**opts, "blue/exit": 1, "blue/err": "compute lifecycle refused; legacy monolithic state requires explicit migration"}


def managed_backend(opts: dict) -> bool:
    return opts.get("s3-bucket-mode") == "managed"


async def load_infrastructure_step(opts: dict) -> dict:
    """Inspect the recorded deployment before any verb that needs the host.

    A delete has two routes past a missing machine, so a repeat delete exits 0
    instead of demanding state that is gone. Compute destroyed or absent:
    `redis/already-destroyed` skips the host and the compute destroy, and the
    workflow continues with the managed storage stage and the managed backend
    finalizer when the deployment has them. Inspection error under a managed
    backend: the bucket itself may already be finalized, which reads as an
    unreadable state, so `redis/finalize-only` routes straight to the
    finalizer, which proves absence or owned retirement before it succeeds.
    Everywhere else an unreadable state stays an error: a failed read never
    means absence."""
    refused = "compute inspection refused; existing owned state is required"
    try:
        delete = opts.get("blue/event") == "delete"
        result = await read_deployment(opts, environment(opts), {}, compute.requirements(opts))
        status = result.get("status")
        if status == "present":
            node = (result.get("cluster") or {}).get("nodes", [{}])[0]
            ready = {**opts, "colors-compute/cluster": result.get("cluster"), "colors-compute/shared": result.get("shared"),
                     "ip": node.get("ip"), "user": node.get("user"), "blue/exit": 0}
            if node.get("ssh_identity_file"):
                ready["ssh-private-key-path"] = node["ssh_identity_file"]
            if opts.get("blue/event") == "rehearse" and storage.managed(opts):
                return await storage.read_credentials(ready)
            return ready
        if status == "destroyed":
            if delete:
                return {**opts, "redis/already-destroyed": True, "blue/exit": 0}
            return {**opts, "blue/exit": 1, "blue/err": "compute deployment is destroyed"}
        if status == "absent":
            if delete and (storage.managed(opts) or managed_backend(opts)):
                return {**opts, "redis/already-destroyed": True, "blue/exit": 0}
            return {**opts, "blue/exit": 1, "blue/err": refused}
        if delete and managed_backend(opts):
            return {**opts, "redis/finalize-only": True, "blue/exit": 0}
        return {**opts, "blue/exit": 1, "blue/err": refused}
    except Exception as e:
        return {**opts, "blue/exit": 1, "blue/err": str(e) or refused}


# ---------------------------------------------------------- ansible (local)


def ansible_local_data(opts: dict) -> dict:
    """Only what a `build` genuinely knows. The address, the user and the alias
    are run-time facts and reach the play as extra-vars instead, so the
    rendered playbook carries no IP and is identical on every workstation (SSH
    Config Standard §6)."""
    return {**{k: v for k, v in opts.items() if k != storage.CREDENTIALS_KEY},
            "ssh-keygen": validate.keygen(opts),
            "ssh-config-identity-file": ssh_config.identity_file(opts)}


def ansible_local_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_local_tool)
    data = ansible_local_data(opts)
    return [spec(template("ansible-local", name), f"{dir}/{name}", data)
            for name in ["ansible.cfg", "inventory.ini", "main.yml"]]


async def ansible_local_step(opts: dict) -> dict:
    """Write or remove the `~/.ssh/config` block. The same playbook serves both
    events; `block_state` is what distinguishes them."""
    dir = tool_dir(opts, ansible_local_tool)
    delete = opts.get("blue/event") == "delete"
    node = compute.node(opts)
    return await ansible_with_spec(
        opts, ansible_local_specs(opts),
        dir=dir, inventory="inventory.ini",
        playbooks={"create": "main.yml", "delete": "main.yml"},
        extra_vars={"host_alias": ssh_config.host_alias(opts),
                    "ssh_hosts": [{"name": ssh_config.host_alias(opts), "ip": node.get("ip"), "user": node.get("user")}],
                    "block_state": "absent" if delete else "present"})


# ---------------------------------------------------------------- ansible


def inventory(opts: dict) -> str:
    node = compute.node(opts)
    identity = opts.get("ssh-private-key-path") or node.get("ssh_identity_file")
    host = {"ansible_host": node.get("ip"), "ansible_user": node.get("user")}
    if identity:
        host["ansible_ssh_private_key_file"] = identity
    return _pretty({"all": {"children": {"redis": {"hosts": {opts.get("profile"): host}}}}})


def ansible_data(opts: dict) -> dict:
    """Template values for the Ansible stage.

    Deliberately carries no operator secret. The backup pair reaches the host
    as Ansible `lookup('env', ...)` expressions written literally into
    main.yml, where `preserve-jinja-delimiters` passes them through untouched;
    routing them through this map instead would let the renderer HTML-escape
    the quotes and hand Ansible `&#39;`. The secret therefore exists only in
    the process that needs it: not in `.colors/`, not in a golden, not in this
    map. The managed storage output is dropped for the same reason."""
    return {**{k: v for k, v in opts.items() if k != storage.CREDENTIALS_KEY},
            "ip": compute.node(opts).get("ip"),
            "ssh-keygen": validate.keygen(opts),
            "redis-backup-set-prefix": set_prefix(opts)}


ANSIBLE_FILES = [
    "ansible.cfg", "main.yml", "cleanup.yml", "rehearsal.yml", "compose.yml",
    "r2-env.sh", "redis-backup.sh", "redis-restore-check.sh",
    "redis-smoke.sh", "redis-monitor.sh", "redis-status.sh",
]


def ansible_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_tool)
    data = ansible_data(opts)
    return [*[spec(template("ansible", name), f"{dir}/{name}", data) for name in ANSIBLE_FILES],
            raw_spec(f"{dir}/inventory.json", inventory(data))]


PLAY_TIMEOUT_MS = 7200000


def play_env(opts: dict, credentials: bool) -> dict[str, str]:
    """What ansible-playbook runs with beyond the ambient environment: host-key
    checking off, as the SDK step does for hosts whose keys change on every
    create, and with managed storage the scoped backup pair under the same
    COLORS_PAR_REDIS_BACKUP_R2_* names an operator exports for an external
    bucket. main.yml's `lookup('env', ...)` expressions therefore hold for both."""
    env = {"ANSIBLE_HOST_KEY_CHECKING": "False"}
    if credentials and storage.managed(opts):
        env.update(storage.credential_env(opts))
    return env


async def run_play(opts: dict, playbook: str, credentials: bool) -> dict:
    """Scaffold the Ansible tree and run `playbook` in it. Mirrors
    `blue.ansible.ansible_with_spec`, which cannot take an environment: build
    renders and stops; delete renders, runs, then removes the rendered tree;
    every other event renders and runs. The PLAY RECAP lands under
    `ansible/recap` and a failure carries the play's output, as the SDK step's
    does."""
    specs = ansible_specs(opts)
    event = opts.get("blue/event")
    if event == "build":
        return scaffold(opts, specs)
    rendered = {**scaffold({**opts, "blue/event": "create"}, specs), "blue/event": event}
    result = await runtime.exec(["ansible-playbook", "-i", "inventory.json", playbook],
                                cwd=tool_dir(opts, ansible_tool), env=play_env(opts, credentials),
                                timeout_ms=PLAY_TIMEOUT_MS)
    exit = result.exit if result.exit is not None else 1
    if exit > 0:
        return {**rendered, "blue/exit": exit,
                "blue/err": f"ansible-playbook {playbook} failed: {result.out or result.err or '(no output)'}"}
    if event == "delete":
        return scaffold({**rendered, "blue/exit": 0, "ansible/recap": parse_recap(result.out)}, specs)
    return {**rendered, "blue/exit": 0, "ansible/recap": parse_recap(result.out)}


async def ansible_step(opts: dict) -> dict:
    if opts.get("blue/event") == "delete" and not opts.get("ip"):
        # No compute in state: there is no host to stop, and the cleanup play
        # would only fail against the placeholder address.
        return {**opts, "blue/exit": 0}
    return await run_play(opts, "cleanup.yml" if opts.get("blue/event") == "delete" else "main.yml", True)


async def rehearsal_step(opts: dict) -> dict:
    """The recovery rehearsal: a fresh backup set, its restore into a scratch
    instance of the pinned image, the smoke key read back from the restored
    data, and only then the recovery marker. Runs the same rendered tree as
    the converge, with the scoped pair when storage is managed."""
    return await run_play(opts, "rehearsal.yml", True)


# ------------------------------------------------------------- acceptance


async def run_quiet(args: list[str], env: dict[str, str], timeout_ms: int):
    """Run `args` with `env` overlaid, returning the result. Nothing from the
    child is echoed; callers decide what becomes an error message, so a secret
    passed through `env` can never leak into output by default."""
    return await runtime.exec(args, env=env if env else None, timeout_ms=timeout_ms)


def redis_args(port: int, auth: bool, *cmd: str) -> list[str]:
    """A redis-cli invocation against a local port with an explicit everything.
    `env -i` clears the environment and re-admits only PATH and, when `auth`,
    the password handed over through the runner as REDISCLI_AUTH, so no
    ambient variable can alter what the probe proves and the password never
    appears on a command line. Error replies are text on stdout, not exit
    codes, so callers grep the reply."""
    return ["bash", "-c",
            'exec env -i PATH="$PATH"'
            + (' REDISCLI_AUTH="$REDISCLI_AUTH"' if auth else "")
            + f" redis-cli --no-auth-warning -h 127.0.0.1 -p {port} "
            + " ".join(posix_quote(c) for c in cmd)]


def tunnel_args(opts: dict, port: int) -> list[str]:
    """An ssh tunnel through the generated `~/.ssh/config` alias, the supported
    client path, exercised end to end: the alias, the identity file, and the
    forward. `-f` returns once the forward is up; the remote `sleep` bounds its
    lifetime so nothing needs killing on the way out. The bash wrapper exists
    for the streams: the daemonized child inherits stdout/stderr, and a runner
    that waits for the pipes to close would otherwise block until the sleep
    expires, returning exactly when the tunnel dies."""
    return ["bash", "-c",
            "ssh -f -o ExitOnForwardFailure=yes -o BatchMode=yes"
            f" -L {port}:127.0.0.1:{_s(opts.get('redis-port'))} "
            f"{ssh_config.host_alias(opts)} sleep 45 >/dev/null 2>&1"]


def closed_port_args(ip: str, port) -> list[str]:
    """A TCP connect to the machine's public address on the Redis port, bounded
    by a timeout. It must FAIL: the port is bound to loopback only and the
    firewall admits 22 alone."""
    return ["bash", "-c", f"timeout 5 bash -c 'exec 3<>/dev/tcp/{_s(ip)}/{_s(port)}'"]


async def read_remote_password(opts: dict) -> str | None:
    """The generated Redis password, read over SSH and held only in this
    process. Never merged into opts, never printed."""
    result = await run_quiet(["ssh", "-o", "BatchMode=yes", ssh_config.host_alias(opts),
                              "cat", "/etc/redis/secrets/password"], {}, 20000)
    if result.exit == 0:
        return _s(result.out).strip()
    return None


def reply(result) -> str:
    return (_s(result.out) + _s(result.err)).strip()


async def acceptance_step(opts: dict) -> dict:
    """The operator-path gate, after a real create.

    The server-side gates already ran inside the playbook (the round-trip, the
    configuration, the auth negatives, the bind addresses, persistence across
    a restart, the first backup set). What is checked from here is what only
    this side can check: that an operator on this workstation reaches Redis
    through the generated SSH config and a tunnel with the generated password
    and not without it, and that the public address does not answer on the
    Redis port at all."""
    if opts.get("blue/event") != "create":
        return {**opts, "blue/exit": 0}
    password = await read_remote_password(opts)
    ip = opts.get("ip")
    public = await run_quiet(closed_port_args(ip, opts.get("redis-port")), {}, 15000)
    if not password:
        return {**opts, "blue/exit": 1,
                "blue/err": "acceptance: could not read the generated Redis password over ssh"}
    if public.exit == 0:
        return {**opts, "blue/exit": 1,
                "blue/err": f"acceptance: {ip}:{_s(opts.get('redis-port'))}"
                            " accepted a connection from the internet; the port must not be public"}
    for _attempt in range(3):
        port = 20000 + random.randrange(40000)
        tunnel = await run_quiet(tunnel_args(opts, port), {}, 30000)
        if tunnel.exit != 0:
            continue
        stamp = f"operator-{int(time.time() * 1000)}"
        set_r = await run_quiet(redis_args(port, True, "SET", "colors:operator", stamp), {"REDISCLI_AUTH": password}, 30000)
        get_r = await run_quiet(redis_args(port, True, "GET", "colors:operator"), {"REDISCLI_AUTH": password}, 30000)
        anon = await run_quiet(redis_args(port, False, "PING"), {}, 30000)
        wrong = await run_quiet(redis_args(port, True, "PING"), {"REDISCLI_AUTH": "not-the-password"}, 30000)
        if reply(set_r) != "OK":
            return {**opts, "blue/exit": 1,
                    "blue/err": f"acceptance: SET through the tunnel answered '{reply(set_r)}', expected OK"}
        if reply(get_r) != stamp:
            return {**opts, "blue/exit": 1,
                    "blue/err": f"acceptance: GET through the tunnel answered '{reply(get_r)}', expected {stamp}"}
        if "NOAUTH" not in reply(anon):
            return {**opts, "blue/exit": 1,
                    "blue/err": f"acceptance: an unauthenticated PING answered '{reply(anon)}' instead of NOAUTH"}
        if "PONG" in reply(wrong) or not re.search(r"WRONGPASS|NOAUTH", reply(wrong)):
            return {**opts, "blue/exit": 1,
                    "blue/err": f"acceptance: a wrong password answered '{reply(wrong)}' instead of a refusal"}
        return {**opts, "blue/exit": 0,
                "redis/acceptance": {"tunnel": "ok", "round-trip": stamp,
                                     "unauthenticated": "refused",
                                     "wrong-password": "refused",
                                     "public-port": "closed"}}
    return {**opts, "blue/exit": 1,
            "blue/err": "acceptance: no local port could carry the ssh tunnel after three attempts"}


# --------------------------------------------------------------- describe


MONITOR_FILE = "/var/lib/colors/redis-monitor.json"


async def describe_step(opts: dict) -> dict:
    """Read the host's last monitor result over SSH and print it. Exits
    non-zero when the host is unreachable or reports unhealthy; this is what
    an external poller runs."""
    alias = ssh_config.host_alias(opts)
    result = await run_quiet(["ssh", "-o", "BatchMode=yes", alias, "cat", MONITOR_FILE], {}, 20000)
    try:
        parsed = json.loads(_s(result.out).strip())
    except Exception:
        parsed = None
    parsed = parsed if isinstance(parsed, dict) else {}
    reachable = result.exit == 0
    healthy = bool(parsed.get("healthy"))
    problems = parsed.get("problems")
    if problems is None and not reachable:
        problems = ["unreachable or no monitor result yet"]
    status = "UNKNOWN" if not reachable else ("ok" if healthy else "UNHEALTHY")
    text = _s(parsed.get("checked") or "") + (" " + "; ".join(str(p) for p in problems) if problems else "")
    print(f"{alias:<32} {status:<10} {text}")
    return {**opts, "blue/exit": 0 if reachable and healthy else 1,
            "redis/describe": {"host": alias, "reachable": reachable, "healthy": healthy,
                               "checked": parsed.get("checked"), "problems": problems}}
