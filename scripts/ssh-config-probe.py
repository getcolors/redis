#!/usr/bin/env python3
"""Run the package-owned play against a temporary home; no cloud or operator files."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ansible', default='ansible-playbook')
    args = parser.parse_args()
    executable = shutil.which(args.ansible)
    if not executable:
        parser.error('ansible-playbook is unavailable; run under uv --with ansible-core')
    root = Path(__file__).resolve().parents[1]
    source = root / 'green/src/resources/io/github/getcolors/redis/tools/ansible-local/main.yml'
    with tempfile.TemporaryDirectory(prefix='colors-ssh-ansible-') as directory:
        work = Path(directory)
        home = work / 'home'
        home.mkdir()
        (home / '.ssh').mkdir(mode=0o700)
        unrelated = 'Host unrelated\n    User operator\n'
        config = home / '.ssh/config'
        legacy = '# BEGIN probe ANSIBLE MANAGED BLOCK\nHost probe\n    HostName 192.0.2.1\n    User obsolete\n# END probe ANSIBLE MANAGED BLOCK\n'
        config.write_text(legacy + unrelated)
        inventory = work / 'inventory.ini'
        inventory.write_text('[local]\nlocalhost ansible_connection=local\n')
        play = work / 'main.yml'
        variables = work / 'vars.json'
        payload = {'host_alias': 'probe', 'ssh_hosts': [
            {'name': 'probe', 'ip': '203.0.113.10', 'user': 'ubuntu'},
            {'name': 'probe-worker-0', 'ip': '203.0.113.11', 'user': 'root'}], 'block_state': 'present'}
        environment = {**os.environ, 'HOME': str(home), 'ANSIBLE_LOCAL_TEMP': str(work / 'ansible-tmp'), 'ANSIBLE_NOCOLOR': '1'}
        for keygen, state, changed in [(True, 'present', True), (True, 'present', False), (False, 'present', True), (False, 'absent', True), (False, 'absent', False)]:
            payload['block_state'] = state
            variables.write_text(json.dumps(payload))
            play.write_text(source.read_text().replace('<% if ssh-keygen %>true<% else %>false<% endif %>', str(keygen).lower()))
            result = subprocess.run([executable, '-i', str(inventory), str(play), '-e', '@' + str(variables)],
                                    env=environment, text=True, capture_output=True, timeout=60)
            if result.returncode:
                raise RuntimeError(result.stdout + result.stderr)
            assert ('changed=1' if changed else 'changed=0') in result.stdout, result.stdout
            value = config.read_text()
            assert unrelated in value
            assert '192.0.2.1' not in value
            assert 'User obsolete' not in value
            if state == 'present':
                assert value.count('# BEGIN probe ANSIBLE MANAGED BLOCK') == 1
                assert ('IdentityFile ~/.ssh/probe' in value) is keygen
                assert ('IdentitiesOnly yes' in value) is keygen
                assert 'Host probe-worker-0\n    HostName 203.0.113.11\n    User root' in value
            else:
                assert value == unrelated
        print('Ansible SSH config probe passed: legacy marker migration, managed/external, idempotent create/delete, unrelated stanza preserved')


if __name__ == '__main__':
    main()
