from conftest import fixture, optout
from package_redis_blue import ssh


def test_build_and_external_identity():
    assert ssh.with_machine_key(fixture({"blue/event": "build"}))["ssh-private-key-path"] == "/home/build-placeholder/.ssh/redis-fixture"
    assert ssh.with_machine_key(optout()) == optout()
    assert ssh.identity_args(optout()) == []
    assert ssh.identity_args(optout({"ssh-private-key-path": "/operator/key"})) == ["-i", "/operator/key", "-o", "IdentitiesOnly=yes"]
