#!/usr/bin/env python3
"""Check the Redis public singleton contract in a rendered library plan."""
import json
from pathlib import Path
import sys
root, provider, mode = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
shared = {p.name: json.loads(p.read_text()) for p in (root / 'shared').glob('*.tf.json')}
nodes = list((root / 'nodes').glob('*/*.tf.json'))
assert len(nodes) == 1, 'Redis requires one node document'
node = json.loads(nodes[0].read_text())
params = node['output']['params']['value']
assert params['provider'] == provider and params['node_id'] == '0'
assert params['vpc_ip'] is None
serialized = json.dumps([shared, node])
for forbidden in ['vultr_vpc', 'digitalocean_vpc', 'vpc_uuid', 'vpc_ids']:
    assert forbidden not in serialized, forbidden
assert ('shared-keygen.tf.json' in shared) == (mode == 'managed')
resources = node['resource']
assert len(resources) == 1
instance = next(iter(next(iter(resources.values())).values()))
assert instance['lifecycle']['prevent_destroy'] is True
policy = shared['shared-none.tf.json']
if provider == 'vultr':
    rules = policy['locals']['ingress']
    assert rules and all(rule['port'] == '22' for rule in rules.values())
else:
    rules = policy['locals']['public_ingress']
    assert rules and all(rule['port_range'] == '22' for rule in rules.values())
print('Redis public singleton contract: passed')
