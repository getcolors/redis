import json

import pytest
from blue import ansible as blue_ansible
from blue.runtime import ExecResult, runtime
from conftest import aws_fixture, aws_optout, fixture
from package_redis_blue import storage, tools

CREDENTIALS = {"credentials": {"backup": {"access_key_id": "AKIA", "secret_access_key": "s"}}}


def spec_for(opts, file):
    return next(s for s in tools.ansible_specs(opts) if str(s["target"]).endswith(file))


def test_the_backup_prefix_is_namespaced_by_profile():
    # Two deployments sharing a bucket must never share a prefix.
    assert tools.set_prefix(fixture({"blue/event": "build"})) == "redis-fixture/redis"


def test_inventory_keeps_one_target_and_no_private_address():
    inv = json.loads(tools.inventory({**fixture({"blue/event": "build"}), "ip": "192.0.2.10"}))
    host = inv["all"]["children"]["redis"]["hosts"]["redis-fixture"]
    assert host["ansible_host"] == "192.0.2.10"
    assert host["ansible_user"] == "root"
    assert "vpc_ip" not in host


def test_a_build_inventory_carries_the_placeholder_only():
    inv = tools.inventory(fixture({"blue/event": "build"}))
    assert tools.PLACEHOLDER_IP in inv
    assert "10.60." not in inv


def test_ansible_renders_the_whole_tree():
    targets = [str(s["target"]) for s in tools.ansible_specs(fixture({"blue/event": "build"}))]
    for f in ["ansible.cfg", "main.yml", "cleanup.yml", "rehearsal.yml", "compose.yml",
              "r2-env.sh", "redis-backup.sh", "redis-restore-check.sh",
              "redis-smoke.sh", "redis-monitor.sh", "redis-status.sh", "inventory.json"]:
        assert any(t.endswith(f) for t in targets), f
    assert len(tools.ANSIBLE_FILES) == len(set(tools.ANSIBLE_FILES))


def test_operator_secrets_reach_the_host_as_lookups_not_values():
    # `.colors/` is generated output and the goldens are committed, so the
    # secret must never be the thing that lands on disk; the expression is.
    template = (tools.ROOT / "tools" / "ansible" / "main.yml").read_text()
    for par in ["COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID", "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY"]:
        assert f"lookup('env','{par}')" in template, par


def test_the_data_map_carries_no_operator_secret():
    data = spec_for(fixture({"blue/event": "build"}), "main.yml")["data"]
    assert data["redis-backup-set-prefix"] == "redis-fixture/redis"
    for k in ["redis-backup-r2-access-key-id", "redis-backup-r2-secret-access-key"]:
        assert data.get(k) is None, k
    # nor the managed storage output
    opts = {**aws_fixture({"blue/event": "build"}), storage.CREDENTIALS_KEY: CREDENTIALS}
    assert storage.CREDENTIALS_KEY not in spec_for(opts, "main.yml")["data"]
    assert storage.CREDENTIALS_KEY not in tools.ansible_local_data(opts)


def test_the_play_environment_carries_the_scoped_pair_only_when_managed():
    assert tools.play_env(fixture(), True) == {"ANSIBLE_HOST_KEY_CHECKING": "False"}
    assert tools.play_env(aws_optout(), True) == {"ANSIBLE_HOST_KEY_CHECKING": "False"}
    assert tools.play_env({**aws_fixture(), storage.CREDENTIALS_KEY: CREDENTIALS}, False) == {"ANSIBLE_HOST_KEY_CHECKING": "False"}
    assert tools.play_env({**aws_fixture(), storage.CREDENTIALS_KEY: CREDENTIALS}, True) == {
        "ANSIBLE_HOST_KEY_CHECKING": "False",
        "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID": "AKIA",
        "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY": "s"}
    with pytest.raises(Exception):
        tools.play_env(aws_fixture(), True)  # managed without an output is refused, never an empty variable


async def test_the_play_runner_mirrors_the_sdk_step(monkeypatch, tmp_path):
    runs = []
    recap = "PLAY RECAP\nredis-fixture : ok=3 changed=1 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0\n"

    def runner(exit, out):
        async def exec(args, cwd=None, env=None, timeout_ms=None):
            runs.append((args, env))
            return ExecResult(exit, out, "")
        return exec

    # a build renders and runs nothing
    monkeypatch.setattr(runtime, "exec", runner(0, recap))
    assert (await tools.run_play(fixture({"blue/event": "build", "workdir": str(tmp_path)}), "main.yml", True))["blue/exit"] == 0
    assert runs == []
    # a create runs the play with host-key checking off and parses the recap
    opts = fixture({"blue/event": "create", "blue/dry-run": True, "ip": "192.0.2.10", "workdir": str(tmp_path)})
    result = await tools.run_play(opts, "main.yml", True)
    assert result["blue/exit"] == 0
    assert result["ansible/recap"] == {"redis-fixture": {"ok": 3, "changed": 1, "unreachable": 0, "failed": 0,
                                                         "skipped": 0, "rescued": 0, "ignored": 0}}
    assert runs[-1] == (["ansible-playbook", "-i", "inventory.json", "main.yml"], {"ANSIBLE_HOST_KEY_CHECKING": "False"})
    # a failure carries the play's output
    monkeypatch.setattr(runtime, "exec", runner(2, "fatal: unreachable"))
    result = await tools.run_play(opts, "main.yml", True)
    assert result["blue/exit"] == 2
    assert "ansible-playbook main.yml failed: fatal: unreachable" in result["blue/err"]
    # a runtime timeout (negative exit) is a failure, never a pass
    monkeypatch.setattr(runtime, "exec", runner(-1, ""))
    result = await tools.run_play(opts, "main.yml", True)
    assert result["blue/exit"] == 1
    assert "ansible-playbook main.yml failed" in result["blue/err"]


def test_the_compose_file_publishes_on_loopback_alone():
    import re
    # Exposure is decided by what Compose publishes: one binding, loopback.
    template = (tools.ROOT / "tools" / "ansible" / "compose.yml").read_text()
    assert re.findall(r'"[^"]*:<\{ redis-port \}>:6379"', template) == ['"127.0.0.1:<{ redis-port }>:6379"']
    assert "vpc" not in template


def test_the_play_and_the_smoke_gate_know_no_private_address():
    play = (tools.ROOT / "tools" / "ansible" / "main.yml").read_text()
    smoke = (tools.ROOT / "tools" / "ansible" / "redis-smoke.sh").read_text()
    assert "redis-smoke {{ ansible_host }}" in play
    assert "vpc" not in play
    assert 'expected="127.0.0.1:$port"' in smoke
    assert "vpc" not in smoke


async def test_a_delete_without_compute_skips_the_host_entirely():
    # There is no machine to stop, and the cleanup play would only fail against
    # the placeholder address.
    assert (await tools.ansible_step({**fixture({"blue/event": "build"}), "blue/event": "delete"}))["blue/exit"] == 0


async def test_acceptance_is_skipped_outside_a_real_create():
    for event in ["build", "delete", "rehearse", "describe"]:
        assert (await tools.acceptance_step({**fixture({"blue/event": "build"}), "blue/event": event}))["blue/exit"] == 0


def test_the_tunnel_probe_never_puts_the_password_on_a_command_line():
    script = tools.redis_args(20001, True, "PING")[2]
    anon = tools.redis_args(20001, False, "PING")[2]
    assert 'REDISCLI_AUTH="$REDISCLI_AUTH"' in script
    assert "env -i" in script
    assert "REDISCLI_AUTH" not in anon
    assert "-p 20001 'PING'" in script


def test_the_tunnel_rides_the_generated_alias_and_the_configured_port():
    script = tools.tunnel_args({**fixture({"blue/event": "build"}), "redis-port": 6380}, 20001)[2]
    assert "-L 20001:127.0.0.1:6380 redis-fixture" in script
    assert "ExitOnForwardFailure=yes" in script


def test_the_public_port_probe_is_bounded():
    script = tools.closed_port_args("203.0.113.5", 6379)[2]
    assert "timeout 5" in script
    assert "/dev/tcp/203.0.113.5/6379" in script


async def test_local_play_receives_its_required_node_fields(monkeypatch):
    seen = {}

    async def fake(opts, _specs, **config):
        seen.update(config)
        return opts
    monkeypatch.setattr(tools, "ansible_with_spec", fake)
    await tools.ansible_local_step(fixture({"blue/event": "build"}))
    assert seen["extra_vars"]["ssh_hosts"] == [{"name": "redis-fixture", "ip": "192.0.2.10", "user": "root"}]


def test_compute_json_matches_green():
    assert json.loads(tools._compute_json({"region": "ams", "backups": True})) == {"backups": True, "region": "ams"}
    assert tools._compute_json({"b": [1, {"y": None, "x": []}], "a": {}}) == \
        '{\n  "a": {},\n  "b": [\n    1,\n    {\n      "x": [],\n      "y": null\n    }\n  ]\n}'


async def test_describe_reports_the_monitor_result(monkeypatch, capsys):
    async def exec(args, cwd=None, env=None, timeout_ms=None):
        return ExecResult(0, json.dumps({"healthy": False, "checked": "2026-09-11T00:00:00Z", "problems": ["stale set"]}), "")
    monkeypatch.setattr(runtime, "exec", exec)
    result = await tools.describe_step(fixture())
    assert result["blue/exit"] == 1
    assert result["redis/describe"] == {"host": "redis-fixture", "reachable": True, "healthy": False,
                                        "checked": "2026-09-11T00:00:00Z", "problems": ["stale set"]}
    assert "UNHEALTHY" in capsys.readouterr().out

    async def unreachable(args, cwd=None, env=None, timeout_ms=None):
        return ExecResult(255, "", "ssh: connect refused")
    monkeypatch.setattr(runtime, "exec", unreachable)
    result = await tools.describe_step(fixture())
    assert result["blue/exit"] == 1
    assert result["redis/describe"]["problems"] == ["unreachable or no monitor result yet"]
