// Conformance with the workspace SSH Config Standard.
import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import * as sshConfig from "../src/ssh-config.ts";
import * as tools from "../src/tools.ts";
import * as workflow from "../src/workflow.ts";
import { fixture, optout, tempWorkdir } from "./fixtures.ts";

// ~/.ssh redirection: the preflight reads $HOME at call time, exactly so tests
// can point it at a fresh temporary home.
let savedHome: string | undefined;
let home: string;
beforeEach(() => {
  savedHome = process.env.HOME;
  home = tempWorkdir("redis-red-home-");
  process.env.HOME = home;
});
afterEach(() => {
  process.env.HOME = savedHome;
  rmSync(home, { recursive: true, force: true });
});

function write(path: string, content: string) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

describe("section 2: the alias and the identity file", () => {
  test("alias is the profile", () => {
    expect(sshConfig.hostAlias(fixture())).toBe("redis-fixture");
  });

  test("identity file keeps the tilde", () => {
    expect(sshConfig.identityFile(fixture())).toBe("~/.ssh/redis-fixture");
    expect(sshConfig.identityFile(fixture())).not.toContain(home);
  });

  test("the marker is the alias alone", () => {
    expect(sshConfig.beginMarker("redis-vultr")).toBe("# BEGIN redis-vultr ANSIBLE MANAGED BLOCK");
    expect(sshConfig.endMarker("redis-vultr")).toBe("# END redis-vultr ANSIBLE MANAGED BLOCK");
  });
});

describe("section 5: never adopt", () => {
  test("a foreign stanza is found", () => {
    expect(sshConfig.foreignStanzaLine(["Host other", "    HostName 192.0.2.1", "", "Host redis-fixture"], "redis-fixture")).toBe(4);
  });

  test("our own block is not foreign", () => {
    const alias = "redis-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, "    HostName 192.0.2.1", sshConfig.endMarker(alias)], alias,
    )).toBeUndefined();
  });

  test("a stanza after our block is still foreign", () => {
    const alias = "redis-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, sshConfig.endMarker(alias), `Host ${alias}`], alias,
    )).toBe(4);
  });

  test("a block under a retired marker is foreign", () => {
    const alias = "redis-vultr";
    expect(sshConfig.foreignStanzaLine(
      [`# BEGIN redis ${alias} ANSIBLE MANAGED BLOCK`, `Host ${alias}`, `# END redis ${alias} ANSIBLE MANAGED BLOCK`], alias,
    )).toBe(2);
  });

  test("a multi-pattern host line counts", () => {
    expect(sshConfig.foreignStanzaLine(["Host web redis-fixture db"], "redis-fixture")).toBe(1);
  });

  test("an unrelated file is left alone", () => {
    expect(sshConfig.foreignStanzaLine(["Host build", "Host redis-other"], "redis-fixture")).toBeUndefined();
  });

  test("preflight refuses rather than overwrites", () => {
    const refused = sshConfig.preflight(fixture(), {
      adoptError: () => "already declares `Host x`",
      placementError: () => undefined,
    });
    expect(refused["red/exit"]).toBe(1);
    expect(String(refused["red/err"])).toContain("already declares");
  });

  test("preflight passes a clean file", () => {
    const clean = sshConfig.preflight(fixture(), { adoptError: () => undefined, placementError: () => undefined });
    expect(clean["red/exit"]).toBeUndefined();
  });

  test("the adopt error reads the real file", () => {
    write(join(home, ".ssh", "config"), "Host other\n    HostName 192.0.2.1\nHost redis-fixture\n");
    const error = String(sshConfig.adoptError(fixture()));
    expect(error).toContain("`Host redis-fixture` at line 3");
    expect(error).toContain("this package will not overwrite it");
    expect(sshConfig.preflight(fixture())["red/exit"]).toBe(1);
  });
});

describe("section 5: placement", () => {
  test("an option above the first host is refused", () => {
    expect(sshConfig.leadingOptionLine(["ServerAliveInterval 60", "Host a"])).toBe(1);
    expect(sshConfig.leadingOptionLine(["# comment", "", "IdentitiesOnly yes", "Host a"])).toBe(3);
  });

  test("a file that opens with a host is fine", () => {
    expect(sshConfig.leadingOptionLine(["Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# lead comment", "", "Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["Match host b", "    User root"])).toBeUndefined();
  });

  test("a file of only comments is fine", () => {
    expect(sshConfig.leadingOptionLine(["# nothing here", ""])).toBeUndefined();
  });

  test("placement error mentions the recovery", () => {
    write(join(home, ".ssh", "config"), "# lead\n\n\nServerAliveInterval 60\nHost a\n");
    const error = String(sshConfig.placementError(fixture()));
    expect(error).toContain("line 4");
    expect(error).toContain("Host *");
  });
});

describe("section 6: build determinism", () => {
  test("build and dry-run never read the config", async () => {
    // A poisoned config proves nothing in the build path reads it.
    write(join(home, ".ssh", "config"), "ServerAliveInterval 60\nHost redis-fixture\n");
    for (const overrides of [{ "red/event": "build" }, { "red/event": "create", "red/dry-run": true }]) {
      const result = await workflow.startStep(fixture(overrides), {});
      expect(result["red/exit"]).toBe(0);
    }
  });

  test("the local play renders no address", () => {
    const data = tools.ansibleLocalData(fixture({ ip: "203.0.113.7" }));
    expect(data["ip-rendered"]).toBeUndefined();
    expect(data["ssh-config-identity-file"]).toBe("~/.ssh/redis-fixture");
  });

  test("the local stage renders three files", () => {
    const targets = tools.ansibleLocalSpecs(fixture()).map((s) => String(s.target));
    for (const file of ["/ansible.cfg", "/inventory.ini", "/main.yml"]) {
      expect(targets.some((t) => t.endsWith(file))).toBe(true);
    }
    expect(targets.every((t) => t.includes("redis-ansible-local"))).toBe(true);
  });
});

describe("section 3: the identity file follows keygen mode", () => {
  test("keygen mode decides the identity lines", () => {
    expect(tools.ansibleLocalData(fixture())["ssh-keygen"]).toBe(true);
    expect(tools.ansibleLocalData(optout())["ssh-keygen"]).toBe(false);
  });
});

describe("section 4: lifecycle", () => {
  const next = (step: string, event: string) => (workflow.wireFn(step, { "red/event": event }) ?? []).slice(1);

  test("create writes the block after compute and before convergence", () => {
    expect(next("redis/infrastructure", "create")).toEqual(["redis/ssh-config"]);
    expect(next("redis/ssh-config", "create")).toEqual(["redis/ansible"]);
  });

  test("delete removes the block before the destroy", () => {
    // The opposite of the keypair, which goes last. A stale block is harmless;
    // a key removed early locks the operator out of a machine that still exists.
    expect(next("redis/ansible", "delete")).toEqual(["redis/ssh-config"]);
    expect(next("redis/ssh-config", "delete")).toEqual(["redis/infrastructure"]);
    expect(next("redis/infrastructure", "delete")).toEqual([]);
  });
});
