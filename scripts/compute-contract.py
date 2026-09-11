#!/usr/bin/env python3
"""Check the Redis public singleton contract in a rendered library plan.

One node, one instance protected by prevent_destroy, a provider firewall
admitting 22 alone, and no VPC construct from another provider. AWS has no
VPC-less instance, so the AWS shared document owns a VPC, a subnet and a
security group; Vultr and DigitalOcean create no network at all, and the
node output's vpc_ip is null there.
"""
import json
import re
from pathlib import Path
import sys
root, provider, mode = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
shared = {p.name: json.loads(p.read_text()) for p in (root / 'shared').glob('*.tf.json')}
nodes = list((root / 'nodes').glob('*/node*.tf.json'))
assert len(nodes) == 1, 'Redis requires one node document'
node = json.loads(nodes[0].read_text())
params = node['output']['params']['value']
assert params['provider'] == provider and params['node_id'] == '0'
serialized = json.dumps([shared, node])
forbidden = {'vultr': ['digitalocean_vpc', 'vpc_uuid', 'aws_vpc'],
             'digitalocean': ['vultr_vpc', 'vpc_ids', 'aws_vpc'],
             'aws': ['vultr_vpc', 'digitalocean_vpc', 'vpc_uuid', 'vpc_ids']}[provider]
for word in forbidden:
    assert word not in serialized, word
# Vultr and DigitalOcean reference an existing account key in opt-out mode
# and render the keygen document only when the package owns the key. AWS has
# no account key ids: the library registers a key pair from the public key in
# both modes, so the document is the registration and exists either way.
if provider == 'aws':
    registration = shared['shared-keygen.tf.json']['resource']['aws_key_pair']['machine']
    assert re.fullmatch(r'[a-z0-9-]+', registration['key_name']), 'the key pair is named after the profile'
    assert registration['public_key'] == 'ssh-ed25519 PLACEHOLDER managed-by-colors', 'build must never read a key'
else:
    assert ('shared-keygen.tf.json' in shared) == (mode == 'managed')
resources = node['resource']
assert len(resources) == 1
instance = next(iter(next(iter(resources.values())).values()))
assert instance['lifecycle']['prevent_destroy'] is True
if provider == 'vultr':
    assert params['vpc_ip'] is None
    rules = shared['shared-none.tf.json']['locals']['ingress']
    assert rules and all(rule['port'] == '22' for rule in rules.values())
elif provider == 'digitalocean':
    assert params['vpc_ip'] is None
    rules = shared['shared-none.tf.json']['locals']['public_ingress']
    assert rules and all(rule['port_range'] == '22' for rule in rules.values())
else:
    assert provider == 'aws', provider
    assert 'aws_instance' in resources
    assert params['vpc_ip'] == '${aws_instance.node.private_ip}'
    policy = shared['shared.tf.json']['resource']
    assert set(policy['aws_security_group']) == {'network'}
    rules = policy['aws_vpc_security_group_ingress_rule']['ingress']['for_each']
    assert rules and all(rule['protocol'] == 'tcp' and rule['from_port'] == 22 and rule['to_port'] == 22 for rule in rules.values())
    assert 'aws_vpc_security_group_egress_rule' in policy
print('Redis public singleton contract: passed')
