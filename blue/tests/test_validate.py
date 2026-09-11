import re

from conftest import ALL_FIXTURES, aws_fixture, aws_optout, do_optout, fixture, optout
from package_redis_blue import validate


def test_application_fixtures_and_backends():
    for f in ALL_FIXTURES:
        opts = f()
        assert validate.state_errors({**opts, "redis-storage-managed": opts.get("redis-storage-managed") is True}) == [], f.__name__


def test_image_port_and_backup_policy():
    for key, value in [("redis-image", "redis:latest"), ("redis-port", 0), ("redis-port", 65536),
                       ("redis-backup-retention-days", 0), ("redis-backup-max-age-hours", -1),
                       ("redis-backup-r2-endpoint", "http://example.test"), ("provider-backend", "local")]:
        assert validate.state_errors(fixture({key: value})), key


def test_the_messages_carry_the_green_shape():
    errors = validate.state_errors(fixture({"redis-image": "redis:latest", "redis-port": 0,
                                            "redis-backup-retention-days": 0, "profile": ""}))
    assert ":profile is required" in errors
    assert ":redis-image must be pinned by digest (tag@sha256:...)" in errors
    assert ":redis-port must be an integer between 1 and 65535" in errors
    assert ":redis-backup-retention-days must be a positive integer" in errors


def test_profile_overlay_is_refused():
    assert validate.env_errors({"COLORS_PAR_PROFILE": "wrong"})
    assert not validate.env_errors({})


def test_credentials_belong_to_their_lifecycle():
    assert len(validate.secret_errors(fixture(), "create")) == 2
    assert validate.secret_errors(fixture(), "delete") == []
    # an operator-owned bucket on AWS still needs the operator's pair
    assert len(validate.secret_errors(aws_optout(), "create")) == 2
    # a managed bucket needs nothing from the environment: the pair is a stage output
    assert validate.secret_errors(aws_fixture(), "create") == []
    assert validate.secret_errors(aws_fixture(), "delete") == []
    assert len(validate.secret_errors(aws_fixture({"redis-storage-managed": False}), "create")) == 2
    assert "required credential is not set: COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID" in validate.secret_errors(fixture(), "create")


def test_managed_storage_has_one_shape():
    def errors(overrides):
        return validate.state_errors({**aws_fixture(), **overrides})

    def found(pattern, es):
        return any(re.search(pattern, e) for e in es)

    assert errors({}) == []
    without = {k: v for k, v in aws_fixture().items() if k != "redis-storage-managed"}
    assert found("must be true or false", validate.state_errors(without))
    assert found("must be true or false", errors({"redis-storage-managed": "yes"}))
    assert found("provider-backend s3", errors({"provider-backend": "r2", "r2-bucket": "b",
                                               "r2-endpoint": "https://x.r2.cloudflarestorage.com"}))
    assert found("s3-bucket-mode managed", errors({"s3-bucket-mode": "external"}))
    assert found("s3-bucket-mode managed", validate.state_errors({k: v for k, v in aws_fixture().items() if k != "s3-bucket-mode"}))
    assert found("must equal s3-region", errors({"redis-backup-r2-region": "eu-west-1"}))
    assert found(r"must be https://s3.us-east-1.amazonaws.com", errors({"redis-backup-r2-endpoint": "https://s3.eu-west-1.amazonaws.com"}))
    assert found(r"must be https://s3.us-east-1.amazonaws.com", errors({"redis-backup-r2-endpoint": "https://fixture.r2.cloudflarestorage.com"}))
    assert found("must not contain dots", errors({"redis-backup-r2-bucket": "redis.backup"}))
    assert found("must differ from s3-bucket", errors({"redis-backup-r2-bucket": "redis-aws-fixture-state"}))
    # none of it applies to an operator-owned bucket
    assert validate.state_errors({**aws_optout(), "redis-storage-managed": False, "redis-backup-r2-region": "eu-west-1"}) == []
    assert validate.state_errors({**fixture(), "redis-storage-managed": False}) == []


def test_key_mode_delegates_to_library():
    assert validate.keygen(fixture())
    assert validate.keygen(aws_fixture())
    assert not validate.keygen(optout())
    assert not validate.keygen(do_optout())
    assert not validate.keygen(aws_optout())
