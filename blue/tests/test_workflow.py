import re
from pathlib import Path

from blue.workflow import failed, run
from conftest import ALL_FIXTURES, aws_fixture, aws_optout, fixture
from package_redis_blue import compute, storage, tools, workflow


def route(event, opts, start):
    step, path = start, [start]
    while True:
        decl = workflow.wire_fn(step, {**opts, "blue/event": event})
        if len(decl) < 2:
            return path
        step = decl[1]
        path.append(step)


def test_cleanup_order_and_read_only_events():
    assert workflow.wire_fn("redis/load-infrastructure", {"blue/event": "delete"}) == [tools.load_infrastructure_step, "redis/ansible"]
    assert workflow.wire_fn("redis/ssh-config", {"blue/event": "delete"}) == [tools.ansible_local_step, "redis/infrastructure"]
    assert workflow.wire_fn("redis/infrastructure", {"blue/event": "delete"}) == [tools.infrastructure_step]
    for event in ["rehearse", "describe"]:
        assert workflow.wire_fn("redis/start", {"blue/event": event})[1] == "redis/load-infrastructure"


async def test_failures_refuse_application_inventory(monkeypatch):
    async def orchestrate(*_a, **_k):
        return {"status": "error"}
    monkeypatch.setattr(tools, "orchestrate", orchestrate)
    assert (await tools.infrastructure_step(fixture({"blue/event": "create"})))["blue/exit"] == 1

    async def read(_o, env, deps, _r):
        assert isinstance(env, dict) and "HOME" in env
        assert deps == {}
        return {"status": "error"}
    monkeypatch.setattr(tools, "read_deployment", read)
    assert (await tools.load_infrastructure_step(fixture({"blue/event": "delete"})))["blue/exit"] == 1
    try:
        compute.node(fixture({"blue/event": "create"}))
        raise AssertionError("a real create without a compute result must refuse the placeholder")
    except RuntimeError:
        pass


async def test_inspection_preserves_connection_user_and_identity(monkeypatch):
    node = {"node_id": "0", "provider": "vultr", "name": "redis-fixture", "ip": "203.0.113.8", "user": "ubuntu",
            "sudoer": "ubuntu", "ssh_identity_file": "/operator/key"}

    async def read(*_a, **_k):
        return {"status": "present", "cluster": {"nodes": [node]}}
    monkeypatch.setattr(tools, "read_deployment", read)
    out = await tools.load_infrastructure_step(fixture({"blue/event": "describe"}))
    assert out["user"] == "ubuntu"
    assert out["ssh-private-key-path"] == "/operator/key"
    assert compute.node(out)["ip"] == "203.0.113.8"


async def test_destroyed_delete_stops_cleanup(monkeypatch):
    async def read(*_a, **_k):
        return {"status": "destroyed"}
    monkeypatch.setattr(tools, "read_deployment", read)
    assert (await tools.load_infrastructure_step(fixture({"blue/event": "delete"})))["redis/already-destroyed"]
    assert (await tools.load_infrastructure_step(fixture({"blue/event": "describe"})))["blue/exit"] == 1


def test_managed_storage_is_wired_between_compute_and_the_host():
    assert route("create", aws_fixture(), "redis/start") == ["redis/start", "redis/infrastructure", "redis/storage", "redis/ssh-config", "redis/ansible", "redis/acceptance"]
    assert route("create", aws_optout(), "redis/start") == ["redis/start", "redis/infrastructure", "redis/ssh-config", "redis/ansible", "redis/acceptance"]
    assert route("create", fixture(), "redis/start") == ["redis/start", "redis/infrastructure", "redis/ssh-config", "redis/ansible", "redis/acceptance"]
    assert workflow.wire_fn("redis/storage", {**aws_fixture(), "blue/event": "create"}) == [storage.step, "redis/ssh-config"]


def test_delete_destroys_the_bucket_after_the_machine_and_the_state_bucket_last():
    assert route("delete", aws_fixture(), "redis/start") == ["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure", "redis/storage", "redis/backend-finalize"]
    assert route("delete", aws_optout(), "redis/start") == ["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure"]
    assert route("delete", fixture(), "redis/start") == ["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure"]
    assert route("delete", {**aws_optout(), "s3-bucket-mode": "managed"}, "redis/start") == ["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure", "redis/backend-finalize"]
    assert route("delete", {**aws_fixture(), "s3-bucket-mode": "external"}, "redis/start") == ["redis/start", "redis/load-infrastructure", "redis/ansible", "redis/ssh-config", "redis/infrastructure", "redis/storage"]
    assert workflow.wire_fn("redis/backend-finalize", {**aws_fixture(), "blue/event": "delete"}) == [workflow.backend_finalize_step]


async def test_a_repeat_delete_continues_past_a_missing_machine(monkeypatch):
    def successors(opts):
        return [pair[0] for pair in workflow.next_fn("redis/load-infrastructure", ["redis/ansible"], opts)]

    # compute destroyed or absent: storage, then the finalizer
    for status in ["destroyed", "absent"]:
        async def read(*_a, **_k):
            return {"status": status}
        monkeypatch.setattr(tools, "read_deployment", read)
        r = await tools.load_infrastructure_step(aws_fixture({"blue/event": "delete"}))
        assert r["blue/exit"] == 0
        assert r["redis/already-destroyed"] is True
        assert successors(r) == ["redis/storage"]
        assert successors({**r, "redis-storage-managed": False}) == ["redis/backend-finalize"]
        assert successors({**r, "redis-storage-managed": False, "s3-bucket-mode": "external"}) == []

    # an absent journal with nothing managed is still an error, not absence
    async def absent(*_a, **_k):
        return {"status": "absent"}
    monkeypatch.setattr(tools, "read_deployment", absent)
    assert (await tools.load_infrastructure_step(fixture({"blue/event": "delete"})))["blue/exit"] == 1
    assert (await tools.load_infrastructure_step(aws_optout({"blue/event": "delete"})))["blue/exit"] == 1

    # an unreadable managed backend routes straight to the finalizer, which decides
    async def error(*_a, **_k):
        return {"status": "error"}
    monkeypatch.setattr(tools, "read_deployment", error)
    r = await tools.load_infrastructure_step(aws_fixture({"blue/event": "delete"}))
    assert r["blue/exit"] == 0
    assert r["redis/finalize-only"] is True
    assert "redis/already-destroyed" not in r
    assert successors(r) == ["redis/backend-finalize"]
    assert (await tools.load_infrastructure_step(aws_optout({"blue/event": "delete"})))["blue/exit"] == 1
    assert (await tools.load_infrastructure_step(aws_fixture({"blue/event": "rehearse"})))["blue/exit"] == 1
    assert (await tools.load_infrastructure_step(aws_fixture({"blue/event": "describe"})))["blue/exit"] == 1

    # the finalizer's outcome is the exit
    async def finalize(status):
        async def f(*_a, **_k):
            return {"status": status}
        return f
    monkeypatch.setattr(workflow, "finalize_backend", await finalize("absent"))
    assert (await workflow.backend_finalize_step(aws_fixture({"blue/event": "delete"})))["blue/exit"] == 0
    monkeypatch.setattr(workflow, "finalize_backend", await finalize("refused"))
    assert (await workflow.backend_finalize_step(aws_fixture({"blue/event": "delete"})))["blue/exit"] == 1

    async def boom(*_a, **_k):
        raise RuntimeError("live state remains")
    monkeypatch.setattr(workflow, "finalize_backend", boom)
    assert (await workflow.backend_finalize_step(aws_fixture({"blue/event": "delete"})))["blue/exit"] == 1


async def test_rehearse_reads_the_scoped_pair_back_from_storage_state(monkeypatch):
    node = {"node_id": "0", "provider": "aws", "name": "redis-aws-fixture", "ip": "203.0.113.8", "user": "ubuntu", "sudoer": "ubuntu"}
    reads = []

    async def read(*_a, **_k):
        return {"status": "present", "cluster": {"nodes": [node]}}

    async def credentials(opts):
        reads.append(1)
        return {**opts, storage.CREDENTIALS_KEY: {"credentials": {"backup": {"access_key_id": "k", "secret_access_key": "s"}}}}
    monkeypatch.setattr(tools, "read_deployment", read)
    monkeypatch.setattr(storage, "read_credentials", credentials)
    out = await tools.load_infrastructure_step(aws_fixture({"blue/event": "rehearse"}))
    assert len(reads) == 1
    assert storage.credential_env(out) == {"COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID": "k", "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY": "s"}
    await tools.load_infrastructure_step(aws_fixture({"blue/event": "describe"}))
    await tools.load_infrastructure_step(aws_optout({"blue/event": "rehearse"}))
    assert len(reads) == 1, "describe and an operator-owned bucket read nothing"


async def test_dry_runs_need_no_credential_and_render_the_storage_backend():
    for f, event in [(aws_fixture, "create"), (aws_fixture, "delete"), (aws_optout, "create"), (aws_optout, "delete")]:
        r = await workflow.start_step(f({"blue/event": event, "blue/dry-run": True, "compute-prevent-destroy": False}), {})
        assert r["blue/exit"] == 0, r.get("blue/err")
    r = await workflow.start_step(aws_optout({"blue/event": "create"}), {})
    assert re.search("COLORS_PAR_REDIS_BACKUP_R2", r["blue/err"]), "an operator-owned bucket needs the operator's pair"
    assert r["blue/exit"] == 2
    assert (await workflow.start_step(fixture({"blue/event": "build"}), {}))["redis-storage-managed"] is False


async def test_delete_is_protected():
    r = await workflow.start_step(fixture({"blue/event": "delete"}), {})
    assert r["blue/exit"] == 2
    assert "COLORS_PAR_COMPUTE_PREVENT_DESTROY=false" in r["blue/err"]


async def test_all_fixtures_native_build_through_library(tmp_path):
    for f in ALL_FIXTURES:
        directory = tmp_path / f.__name__
        opts = f({"blue/event": "build", "workdir": str(directory)})
        result = await run(workflow.redis_workflow, opts)
        assert not failed(result), result.get("blue/err")
        assert result["ip"] == "192.0.2.10"
        # AWS has no VPC-less instance, so the library gives an AWS node a
        # private address; nothing in this package reads it (compose, the play
        # and the smoke gate know only loopback). The other providers create
        # no network at all.
        assert (opts["provider-compute"] == "aws") == (compute.node(result).get("vpc_ip") is not None)
        nodes = Path(tools.tool_dir(opts, tools.infrastructure_tool)) / "nodes" / "0"
        assert len([p for p in nodes.iterdir() if re.fullmatch(r"node(-none)?\.tf\.json", p.name)]) == 1
        assert storage.managed(opts) == (Path(storage.directory(opts)) / "main.tf").is_file()
        assert storage.managed(opts) == (Path(storage.directory(opts)) / "backend.tf.json").is_file()
        if storage.managed(opts):
            tf = (Path(storage.directory(opts)) / "main.tf").read_text()
            assert 'backup = "redis-aws-fixture-backup"' in tf
            assert "prevent_destroy = true" in tf
            assert not re.search(r'AKIA|secret_access_key = "', tf)
