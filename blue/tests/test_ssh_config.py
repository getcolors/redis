"""Conformance with the workspace SSH Config Standard."""
from pathlib import Path

import pytest
from conftest import fixture, optout
from package_redis_blue import ssh_config, tools, workflow

# §2 the alias and the identity file


def test_alias_is_the_profile():
    assert ssh_config.host_alias(fixture()) == "redis-fixture"


def test_identity_file_keeps_the_tilde():
    # An expanded home directory would make the rendered block differ per
    # workstation; OpenSSH expands the tilde itself.
    assert ssh_config.identity_file(fixture()) == "~/.ssh/redis-fixture"
    assert str(Path.home()) not in ssh_config.identity_file(fixture())


def test_the_marker_is_the_alias_alone():
    assert ssh_config.begin_marker("redis-vultr") == "# BEGIN redis-vultr ANSIBLE MANAGED BLOCK"
    assert ssh_config.end_marker("redis-vultr") == "# END redis-vultr ANSIBLE MANAGED BLOCK"


# §5 never adopt


def test_a_foreign_stanza_is_found():
    lines = ["Host other", "    HostName 192.0.2.1", "", "Host redis-fixture"]
    assert ssh_config.foreign_stanza_line(lines, "redis-fixture") == 4


def test_our_own_block_is_not_foreign():
    alias = "redis-fixture"
    lines = [ssh_config.begin_marker(alias), f"Host {alias}", "    HostName 192.0.2.1", ssh_config.end_marker(alias)]
    assert ssh_config.foreign_stanza_line(lines, alias) is None


def test_a_stanza_after_our_block_is_still_foreign():
    alias = "redis-fixture"
    lines = [ssh_config.begin_marker(alias), f"Host {alias}", ssh_config.end_marker(alias), f"Host {alias}"]
    assert ssh_config.foreign_stanza_line(lines, alias) == 4


def test_a_block_under_a_retired_marker_is_foreign():
    alias = "redis-vultr"
    lines = [f"# BEGIN redis {alias} ANSIBLE MANAGED BLOCK", f"Host {alias}", f"# END redis {alias} ANSIBLE MANAGED BLOCK"]
    assert ssh_config.foreign_stanza_line(lines, alias) == 2


def test_a_multi_pattern_host_line_counts():
    assert ssh_config.foreign_stanza_line(["Host web redis-fixture db"], "redis-fixture") == 1


def test_an_unrelated_file_is_left_alone():
    assert ssh_config.foreign_stanza_line(["Host build", "Host redis-other"], "redis-fixture") is None


def test_preflight_refuses_rather_than_overwrites(monkeypatch):
    monkeypatch.setattr(ssh_config, "adopt_error", lambda _o: "already declares `Host x`")
    monkeypatch.setattr(ssh_config, "placement_error", lambda _o: None)
    result = ssh_config.preflight(fixture())
    assert result["blue/exit"] == 1
    assert "already declares" in result["blue/err"]


def test_preflight_passes_a_clean_file(monkeypatch):
    monkeypatch.setattr(ssh_config, "adopt_error", lambda _o: None)
    monkeypatch.setattr(ssh_config, "placement_error", lambda _o: None)
    assert ssh_config.preflight(fixture()).get("blue/exit") is None


# §5 placement. The block is written with insertbefore: BOF, because
# blockinfile anchors insertbefore on the last match and has no firstmatch.


def test_an_option_above_the_first_host_is_refused():
    assert ssh_config.leading_option_line(["ServerAliveInterval 60", "Host a"]) == 1
    assert ssh_config.leading_option_line(["# comment", "", "IdentitiesOnly yes", "Host a"]) == 3


def test_a_file_that_opens_with_a_host_is_fine():
    assert ssh_config.leading_option_line(["Host a", "    User root"]) is None
    assert ssh_config.leading_option_line(["# lead comment", "", "Host a", "    User root"]) is None
    assert ssh_config.leading_option_line(["Match host b", "    User root"]) is None


def test_a_file_of_only_comments_is_fine():
    assert ssh_config.leading_option_line(["# nothing here", ""]) is None


def test_placement_error_mentions_the_recovery(monkeypatch, tmp_path):
    config = tmp_path / ".ssh" / "config"
    config.parent.mkdir()
    config.write_text("IdentitiesOnly yes\nHost a\n")
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setattr(ssh_config, "leading_option_line", lambda _lines: 4)
    error = ssh_config.placement_error(fixture())
    assert "line 4" in error
    assert "Host *" in error


# §6 build determinism


async def test_build_and_dry_run_never_read_the_config(monkeypatch):
    # The only reader is adopt_error, and it must not run on a rendered-only
    # event. Redefining it to raise proves nothing in the build path calls it.
    def boom(_o):
        raise RuntimeError("read ~/.ssh/config")
    monkeypatch.setattr(ssh_config, "adopt_error", boom)
    monkeypatch.setattr(ssh_config, "placement_error", boom)
    for opts in [fixture({"blue/event": "build"}), fixture({"blue/event": "create", "blue/dry-run": True})]:
        assert (await workflow.start_step(opts, {}))["blue/exit"] == 0


def test_the_local_play_renders_no_address():
    # Address, user and alias are run-time facts and travel as extra-vars, so
    # the rendered playbook carries none of them.
    data = tools.ansible_local_data({**fixture(), "ip": "203.0.113.7"})
    assert "ip-rendered" not in data
    assert data["ssh-config-identity-file"] == "~/.ssh/redis-fixture"


def test_the_local_stage_renders_three_files():
    targets = [str(s["target"]) for s in tools.ansible_local_specs(fixture())]
    assert any(t.endswith("/ansible.cfg") for t in targets)
    assert any(t.endswith("/inventory.ini") for t in targets)
    assert any(t.endswith("/main.yml") for t in targets)
    assert all("redis-ansible-local" in t for t in targets)


# §3 the identity file follows keygen mode


def test_keygen_mode_decides_the_identity_lines():
    assert tools.ansible_local_data(fixture())["ssh-keygen"] is True
    assert tools.ansible_local_data(optout())["ssh-keygen"] is False


# §4 lifecycle


def test_create_writes_the_block_after_compute_and_before_convergence():
    assert workflow.wire_fn("redis/infrastructure", {"blue/event": "create"})[1:] == ["redis/ssh-config"]
    assert workflow.wire_fn("redis/ssh-config", {"blue/event": "create"})[1:] == ["redis/ansible"]


def test_delete_removes_the_block_before_the_destroy():
    # The opposite of the keypair, which goes last. A stale block is harmless;
    # a key removed early locks the operator out of a machine that still exists.
    assert workflow.wire_fn("redis/ansible", {"blue/event": "delete"})[1:] == ["redis/ssh-config"]
    assert workflow.wire_fn("redis/ssh-config", {"blue/event": "delete"})[1:] == ["redis/infrastructure"]
    assert workflow.wire_fn("redis/infrastructure", {"blue/event": "delete"})[1:] == []
