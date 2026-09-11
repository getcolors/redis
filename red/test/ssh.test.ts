import { expect, test } from "bun:test";
import * as ssh from "../src/ssh.ts";
import { fixture, optout } from "./fixtures.ts";

test("build and external identity", () => {
  expect(ssh.withMachineKey(fixture({ "red/event": "build" }))["ssh-private-key-path"])
    .toBe("/home/build-placeholder/.ssh/redis-fixture");
  expect(ssh.withMachineKey(fixture({ "red/event": "build" }))["ssh-public-key-path"])
    .toBe("/home/build-placeholder/.ssh/redis-fixture.pub");
  expect(ssh.withMachineKey(optout())).toEqual(optout());
  expect(ssh.identityArgs(optout())).toEqual([]);
  expect(ssh.identityArgs(optout({ "ssh-private-key-path": "/operator/key" })))
    .toEqual(["-i", "/operator/key", "-o", "IdentitiesOnly=yes"]);
});

test("a real event keeps the recorded identity and refuses none", () => {
  expect(ssh.withMachineKey(fixture({ "red/event": "create" }))["ssh-private-key-path"]).toBeUndefined();
  expect(ssh.withMachineKey(fixture({ "red/event": "create", "ssh-private-key-path": "/home/op/.ssh/redis-fixture" }))["ssh-public-key-path"])
    .toBe("/home/op/.ssh/redis-fixture.pub");
  expect(() => ssh.privateKeyPath(fixture())).toThrow("deployment SSH identity unavailable");
  expect(ssh.privateKeyPath(fixture({ "ssh-private-key-path": "/operator/key" }))).toBe("/operator/key");
});
