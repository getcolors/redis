"""The deployment-owned S3 backup bucket and its scoped credentials.

With `redis-storage-managed: true` the package owns the bucket named by
`redis-backup-r2-bucket` in its own OpenTofu stage: the bucket, its public
access block and encryption, one IAM user scoped to that bucket, and one
access key. The key pair is a sensitive stage output. It never enters a
template value or a rendered file; it is read from state when a play needs
it and handed to ansible-playbook as the same COLORS_PAR_REDIS_BACKUP_R2_*
variables an operator would export for an external bucket, so main.yml's
`lookup('env', ...)` expressions are unchanged. Modelled on
neon-multi-node's storage module.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from blue import tofu
from blue.cli import stage_dir
from blue.runtime import runtime
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, scaffold

from .utils import clj_str as _s

TOOL = "redis-storage"
CREDENTIALS_KEY = "redis/storage-credentials"
BUCKET_ROLE = "backup"
CREDENTIAL_PREFIX = "REDIS_BACKUP_R2"
ROOT = Path(__file__).parent / "resources"


def managed(opts: dict) -> bool:
    return opts.get("redis-storage-managed") is True


def directory(opts: dict) -> str:
    return stage_dir(opts, TOOL, default_profile="redis")


def aws_env(opts: dict) -> dict[str, str]:
    """AWS_* variables for tofu and the AWS CLI, overlaid from the optional
    COLORS_PAR_AWS_* pars. Absent pars leave the ambient credential chain alone."""
    mapping = {"aws-access-key-id": "AWS_ACCESS_KEY_ID", "aws-secret-access-key": "AWS_SECRET_ACCESS_KEY",
               "aws-session-token": "AWS_SESSION_TOKEN"}
    return {variable: _s(opts.get(key)) for key, variable in mapping.items() if _s(opts.get(key))}


def specs(opts: dict) -> list[dict]:
    name = "tools/storage/main.tf"
    return [{"template": {"name": name, "content": (ROOT / name).read_text()},
             "target": f"{directory(opts)}/main.tf",
             "data": {k: v for k, v in opts.items() if k != CREDENTIALS_KEY},
             "opts": PRESERVE_JINJA_DELIMITERS}]


async def checked(args: list[str], cwd: str, env: dict[str, str]) -> str:
    result = await runtime.exec(args, cwd=cwd, env=env)
    if result.exit != 0:
        raise RuntimeError("managed storage state operation failed")
    return result.out


async def ownership_preflight(opts: dict) -> None:
    """Refuse an existing bucket unless this stage already owns its address."""
    cwd, env = directory(opts), aws_env(opts)
    await checked(["tofu", "init", "-input=false", "-no-color"], cwd, env)
    state = await runtime.exec(["tofu", "state", "list"], cwd=cwd, env=env)
    # OpenTofu 1.11 prints "No state file was found!", 1.12 "Error: No state file was found".
    empty_state = state.exit == 1 and "No state file was found" in _s(state.err)
    if not (state.exit == 0 or empty_state):
        raise RuntimeError("managed storage state unavailable")
    addresses = {line for line in ("" if empty_state else state.out).splitlines() if line.strip()}
    recorded: dict = {}
    if addresses:
        shown = json.loads(await checked(["tofu", "show", "-json"], cwd, env))
        resources = ((shown.get("values") or {}).get("root_module") or {}).get("resources") or []
        recorded = {resource.get("address"): (resource.get("values") or {}).get("bucket") for resource in resources}
    bucket = opts.get("redis-backup-r2-bucket")
    if recorded.get(f'aws_s3_bucket.application["{BUCKET_ROLE}"]') != bucket:
        probe = await runtime.exec(["aws", "s3api", "head-bucket", "--bucket", _s(bucket),
                                    "--region", _s(opts.get("redis-backup-r2-region"))], cwd=cwd, env=env)
        # 403, network failures and a successful probe all fail closed.
        if not (probe.exit > 0 and re.search(r"\(404\)|Not Found|NoSuchBucket", _s(probe.err))):
            raise RuntimeError("managed storage refuses to adopt an existing or inaccessible bucket")


async def step(opts: dict) -> dict:
    """Create, render or destroy the storage stage. Not managed: a no-op."""
    if not managed(opts):
        return {**opts, "blue/exit": 0}
    try:
        documents = specs(opts)
        if opts.get("blue/event") == "create":
            scaffold(opts, documents)
            await ownership_preflight(opts)
        # The scoped pair stays in memory and in the encrypted backend state;
        # never copy it into template values or print the output object.
        return await tofu.tofu_with_spec(opts, documents, dir=directory(opts), env=aws_env(opts),
                                         output_key=CREDENTIALS_KEY)
    except Exception:
        return {**opts, "blue/exit": 1,
                "blue/err": "managed S3 storage failed; inspect bucket ownership, state access, and AWS permissions"}


def credential_env(opts: dict) -> dict[str, str]:
    """The COLORS_PAR_REDIS_BACKUP_R2_* pair for ansible-playbook, from the
    storage stage output. Raises when the output is missing or blank."""
    credentials = ((opts.get(CREDENTIALS_KEY) or {}).get("credentials") or {}).get(BUCKET_ROLE) or {}
    access, secret = credentials.get("access_key_id"), credentials.get("secret_access_key")
    if not _s(access).strip() or not _s(secret).strip():
        raise RuntimeError("managed storage credentials unavailable")
    return {f"COLORS_PAR_{CREDENTIAL_PREFIX}_ACCESS_KEY_ID": access,
            f"COLORS_PAR_{CREDENTIAL_PREFIX}_SECRET_ACCESS_KEY": secret}


async def read_credentials(opts: dict) -> dict:
    """Read the scoped pair back from the storage state for a verb that runs a
    play without converging the stage (rehearse). Not managed: opts unchanged."""
    if not managed(opts):
        return opts
    try:
        tofu.conventional_backend_advice(dir=directory, key=lambda o: f"{_s(o.get('profile'))}/{TOOL}.tfstate")(opts)
        scaffold({**opts, "blue/event": "build"}, specs(opts))
        await checked(["tofu", "init", "-input=false", "-no-color"], directory(opts), aws_env(opts))
        result = {**opts, CREDENTIALS_KEY: await tofu.outputs(directory(opts), aws_env(opts))}
        credential_env(result)
        return result
    except Exception:
        raise RuntimeError("managed storage credentials unavailable; converge storage before rehearsal") from None
