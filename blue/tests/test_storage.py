import pytest
from blue import tofu
from conftest import aws_fixture, aws_optout, fixture
from blue.scaffold import PRESERVE_JINJA_DELIMITERS
from package_redis_blue import storage

CREDENTIALS = {"credentials": {"backup": {"access_key_id": "AKIA", "secret_access_key": "s"}}}


def test_the_managed_gate_is_the_one_key():
    assert storage.managed(aws_fixture())
    assert not storage.managed(aws_optout())
    assert not storage.managed(fixture())
    assert not storage.managed({**fixture(), "redis-storage-managed": "true"})


def test_the_stage_renders_one_template_and_no_credential():
    opts = {**aws_fixture(), "blue/event": "build", storage.CREDENTIALS_KEY: CREDENTIALS}
    specs = storage.specs(opts)
    assert len(specs) == 1
    assert str(specs[0]["target"]).endswith("/redis-aws-fixture/redis-storage/main.tf")
    assert specs[0]["template"]["name"] == "tools/storage/main.tf"
    assert storage.CREDENTIALS_KEY not in specs[0]["data"]
    assert specs[0]["opts"] == PRESERVE_JINJA_DELIMITERS


def test_the_credential_env_carries_the_operator_names():
    env = storage.credential_env({storage.CREDENTIALS_KEY: {"credentials": {"backup": {"access_key_id": "fixture-id", "secret_access_key": "fixture-secret"}}}})
    assert env == {"COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID": "fixture-id",
                   "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY": "fixture-secret"}
    # a missing or blank pair is refused, never an empty variable
    with pytest.raises(Exception):
        storage.credential_env({})
    with pytest.raises(Exception):
        storage.credential_env({storage.CREDENTIALS_KEY: {"credentials": {"backup": {"access_key_id": "", "secret_access_key": "s"}}}})


def test_aws_env_overlays_only_what_is_set():
    assert storage.aws_env(aws_fixture()) == {}
    assert storage.aws_env({**aws_fixture(), "aws-access-key-id": "AKIA", "aws-secret-access-key": "s", "aws-session-token": "t"}) == \
        {"AWS_ACCESS_KEY_ID": "AKIA", "AWS_SECRET_ACCESS_KEY": "s", "AWS_SESSION_TOKEN": "t"}
    assert storage.aws_env({"aws-access-key-id": "AKIA", "aws-secret-access-key": ""}) == {"AWS_ACCESS_KEY_ID": "AKIA"}


async def test_the_step_is_a_no_op_unless_managed(monkeypatch):
    async def must_not_run(*_a, **_k):
        raise RuntimeError("must not run")
    monkeypatch.setattr(tofu, "tofu_with_spec", must_not_run)
    assert (await storage.step({**aws_optout(), "blue/event": "create"}))["blue/exit"] == 0
    assert await storage.read_credentials(aws_optout()) == aws_optout()


async def test_a_create_runs_the_preflight_then_tofu_with_the_aws_environment(monkeypatch, tmp_path):
    calls = []
    opts = {**aws_fixture(), "blue/event": "create", "aws-access-key-id": "AKIA", "aws-secret-access-key": "s",
            "workdir": str(tmp_path / "a")}

    async def preflight(_o):
        calls.append("preflight")

    async def run(o, specs, **config):
        calls.append("tofu")
        assert len(specs) == 1
        assert config["env"] == {"AWS_ACCESS_KEY_ID": "AKIA", "AWS_SECRET_ACCESS_KEY": "s"}
        assert config["output_key"] == storage.CREDENTIALS_KEY
        return {**o, "blue/exit": 0}
    monkeypatch.setattr(storage, "ownership_preflight", preflight)
    monkeypatch.setattr(tofu, "tofu_with_spec", run)
    assert (await storage.step(opts))["blue/exit"] == 0
    assert calls == ["preflight", "tofu"]

    # a delete runs no preflight
    calls.clear()

    async def run_only(o, _specs, **_config):
        calls.append("tofu")
        return {**o, "blue/exit": 0}
    monkeypatch.setattr(tofu, "tofu_with_spec", run_only)
    assert (await storage.step({**aws_fixture(), "blue/event": "delete", "workdir": str(tmp_path / "b")}))["blue/exit"] == 0
    assert calls == ["tofu"]

    # a refused preflight is an error with no credential in the message
    async def leak(_o):
        raise RuntimeError("AKIAEXAMPLE leaked")
    monkeypatch.setattr(storage, "ownership_preflight", leak)
    result = await storage.step({**aws_fixture(), "blue/event": "create", "workdir": str(tmp_path / "c")})
    assert result["blue/exit"] == 1
    assert "AKIA" not in result["blue/err"]


async def test_read_credentials_refuses_a_missing_output(monkeypatch, tmp_path):
    async def outputs(*_a, **_k):
        return {"credentials": {}}

    async def checked(*_a, **_k):
        return ""
    monkeypatch.setattr(tofu, "outputs", outputs)
    monkeypatch.setattr(storage, "checked", checked)
    with pytest.raises(Exception, match="converge storage before rehearsal"):
        await storage.read_credentials({**aws_fixture(), "blue/event": "rehearse", "workdir": str(tmp_path)})


async def test_ownership_preflight_refuses_an_unowned_existing_bucket(monkeypatch, tmp_path):
    from blue.runtime import ExecResult, runtime
    commands = []

    async def exec(args, cwd=None, env=None, timeout_ms=None):
        commands.append(args)
        if args[:2] == ["tofu", "init"]:
            return ExecResult(0, "", "")
        if args[:3] == ["tofu", "state", "list"]:
            return ExecResult(1, "", "No state file was found!")
        if args[:3] == ["aws", "s3api", "head-bucket"]:
            return ExecResult(0, "", "")
        raise AssertionError(args)
    monkeypatch.setattr(runtime, "exec", exec)
    with pytest.raises(Exception, match="refuses to adopt"):
        await storage.ownership_preflight({**aws_fixture(), "workdir": str(tmp_path)})
    assert not any(a[:2] == ["tofu", "show"] for a in commands), "an empty state needs no show"

    async def missing(args, cwd=None, env=None, timeout_ms=None):
        if args[:3] == ["aws", "s3api", "head-bucket"]:
            return ExecResult(254, "", "An error occurred (404) when calling the HeadBucket operation: Not Found")
        return await exec(args, cwd, env, timeout_ms)
    monkeypatch.setattr(runtime, "exec", missing)
    await storage.ownership_preflight({**aws_fixture(), "workdir": str(tmp_path)})
