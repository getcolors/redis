"""Validation over desired state, the port of io.github.getcolors.redis.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon: the colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from colors_compute import backend_plan
from colors_compute.ssh import _mode as ssh_mode

from .utils import clj_str as _s

profile_par = par_name("profile")

DEFAULT_COMPUTE_PROVIDER = "vultr"

required = [
    "profile", "workdir", "provider-compute", "provider-backend",
    "compute-prevent-destroy",
    "redis-image", "redis-port",
    "redis-backup-r2-bucket", "redis-backup-r2-endpoint", "redis-backup-r2-region",
    "redis-backup-oncalendar", "redis-backup-retention-days",
    "redis-backup-max-age-hours",
]

# `tag@sha256:...` pins both the human-readable release and the exact bytes.
# Docker Hub republishes the `7.2` and `7.2.16` tags whenever the base image
# is rebuilt, which is why the digest is required rather than the tag denied.
image_re = re.compile(r"[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64}|:[^\s:@]+@sha256:[0-9a-f]{64})")
url_re = re.compile(r"https://[^\s]+")


def missing(value) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def keygen(opts: dict) -> bool:
    return ssh_mode(opts)["mode"] == "managed"


def env_errors(env: dict) -> list[str]:
    if _s(env.get(profile_par)):
        return [f"{profile_par} is set; profile must come from colors.yml only"]
    return []


def _positive_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def managed_storage(opts: dict) -> bool:
    return opts.get("redis-storage-managed") is True


def aws_endpoint(region) -> str:
    """The S3 endpoint of one AWS region, the only endpoint a managed bucket has."""
    return f"https://s3.{_s(region)}.amazonaws.com"


def storage_errors(opts: dict) -> list[str]:
    """The managed-storage contract: the package creates the bucket in the AWS
    region the state backend lives in, under the managed S3 backend, so one
    finalize proves one account's resources gone."""
    if not managed_storage(opts):
        return []
    region = opts.get("s3-region")
    bucket = _s(opts.get("redis-backup-r2-bucket"))
    errors: list[str] = []
    if opts.get("provider-backend") != "s3":
        errors.append(":redis-storage-managed requires provider-backend s3")
    if opts.get("s3-bucket-mode") != "managed":
        errors.append(":redis-storage-managed requires s3-bucket-mode managed")
    if not (not missing(region) and region == opts.get("redis-backup-r2-region")):
        errors.append(":redis-backup-r2-region must equal s3-region when storage is managed")
    if not (not missing(region) and aws_endpoint(region) == opts.get("redis-backup-r2-endpoint")):
        errors.append(f":redis-backup-r2-endpoint must be {aws_endpoint(region if region is not None else '<s3-region>')} when storage is managed")
    if "." in bucket:
        errors.append(":redis-backup-r2-bucket must not contain dots when storage is managed")
    if bucket == _s(opts.get("s3-bucket")):
        errors.append(":redis-backup-r2-bucket must differ from s3-bucket")
    return errors


def state_errors(opts: dict) -> list[str]:
    """Application settings and the library backend contract."""
    errors: list[str] = []
    errors += [f":{k} is required" for k in required if missing(opts.get(k))]
    if opts.get("provider-backend") not in ("s3", "r2"):
        errors.append(":provider-backend must be s3 or r2")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append(":compute-prevent-destroy must be true or false")
    if not isinstance(opts.get("redis-storage-managed"), bool):
        errors.append(":redis-storage-managed must be true or false")
    errors += storage_errors(opts)
    image = opts.get("redis-image")
    if not missing(image) and not image_re.fullmatch(_s(image)):
        errors.append(":redis-image must carry an explicit image tag or digest")
    if not missing(image) and "@sha256:" not in _s(image):
        errors.append(":redis-image must be pinned by digest (tag@sha256:...)")
    port = opts.get("redis-port")
    if not missing(port) and not (isinstance(port, int) and not isinstance(port, bool) and 1 <= port <= 65535):
        errors.append(":redis-port must be an integer between 1 and 65535")
    if not (missing(opts.get("redis-backup-r2-endpoint"))
            or url_re.fullmatch(_s(opts.get("redis-backup-r2-endpoint")))):
        errors.append(":redis-backup-r2-endpoint must be an https URL")
    for k in ["redis-backup-retention-days", "redis-backup-max-age-hours"]:
        v = opts.get(k)
        if not missing(v) and not _positive_int(v):
            errors.append(f":{k} must be a positive integer")
    try:
        backend_plan(opts, f"{_s(opts.get('profile'))}/shared.tfstate")
    except Exception as e:
        errors.append(str(e))
    return errors


# What converging the machine needs, and therefore only a create: the R2 pair
# the backup sets are written with. The Redis password is deliberately absent:
# it is generated on the server, once, and never operator-supplied. With
# managed storage the pair is a storage stage output, not an operator secret,
# so a create requires nothing from the environment.
application_secrets = ["redis-backup-r2-access-key-id", "redis-backup-r2-secret-access-key"]


def secret_errors(opts: dict, event: str) -> list[str]:
    keys = application_secrets if event == "create" and not managed_storage(opts) else []
    return [f"required credential is not set: {par_name(key)}" for key in keys if missing(opts.get(key))]
