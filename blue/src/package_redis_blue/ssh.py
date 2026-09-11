"""Application SSH arguments; colors-compute owns key lifecycle."""

from __future__ import annotations

import os

from colors_compute.ssh import _mode as ssh_mode

BUILD_PLACEHOLDER_DIR = "/home/build-placeholder/.ssh"


def rendered_only(opts: dict) -> bool:
    return opts.get("blue/event") == "build" or bool(opts.get("blue/dry-run"))


def with_machine_key(opts: dict) -> dict:
    if ssh_mode(opts)["mode"] != "managed":
        return opts
    path = f"{BUILD_PLACEHOLDER_DIR}/{opts.get('profile')}" if rendered_only(opts) else opts.get("ssh-private-key-path")
    if not path:
        return opts
    return {**opts, "ssh-private-key-path": path, "ssh-public-key-path": f"{path}.pub"}


def identity_args(opts: dict) -> list[str]:
    path = opts.get("ssh-private-key-path")
    return ["-i", path, "-o", "IdentitiesOnly=yes"] if path else []


def private_key_path(opts: dict) -> str:
    if not opts.get("ssh-private-key-path"):
        raise RuntimeError("deployment SSH identity unavailable")
    return os.path.abspath(str(opts["ssh-private-key-path"]))
