terraform {
  required_providers {
    vultr = { source = "vultr/vultr", version = "~> 2.0" }
  }
}

provider "vultr" {
  # api key comes from VULTR_API_KEY in the environment
}

locals {
  ssh_sources = ["0.0.0.0/0", "::/0"]
}

# Every label derives from one resolved name (Compute Name Standard §3), which
# is the profile unless desired state overrides it with vultr-name.
resource "vultr_firewall_group" "redis" {
  description = "redis-optout-fixture-firewall"
}

# 22 is the only open port. Convergence, recovery, and the supported client
# path — an SSH tunnel to the loopback-bound Redis — all ride it. Redis is
# published on 127.0.0.1 alone, never on the public address, so there is
# nothing else a firewall rule could gate. No VPC is attached: a single-node
# package creates no private network (Compute Provider Standard §5).
resource "vultr_firewall_rule" "ssh" {
  for_each          = toset(local.ssh_sources)
  firewall_group_id = vultr_firewall_group.redis.id
  protocol          = "tcp"
  port              = "22"
  ip_type           = strcontains(each.value, ":") ? "v6" : "v4"
  subnet            = split("/", each.value)[0]
  subnet_size       = tonumber(split("/", each.value)[1])
}

resource "vultr_instance" "redis" {
  # `label` is the console name and updates in place. There is deliberately no
  # `hostname`: Vultr implements a hostname change as an OS reinstall, so the
  # provider marks that attribute ForceNew, and editing the name would
  # destroy the instance and its disk rather than rename it.
  label             = "redis-optout-fixture"
  region            = "ams"
  plan              = "vc2-1c-2gb"
  os_id             = 2284
  firewall_group_id = vultr_firewall_group.redis.id
  # SSH keys are ids already in the account, and ForceNew: changing the key set
  # destroys and recreates the instance instead of re-authorizing it. Rotation
  # is a rebuild, never an edit on a machine whose disk you intend to keep.
  ssh_key_ids = ["00000000-0000-4000-8000-000000000000"]
  # Wait for ssh before starting Ansible.
  connection {
    type = "ssh"
    user = "root"
    host = self.main_ip
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle { prevent_destroy = true }
}

# The Compute Provider Standard's contract (§4): `provider` is the registry
# name this template belongs to, which is what makes a provider switch
# decidable; `ssh_key_id` is the SSH Keypair Standard's ownership record.
output "params" {
  value = {
    provider = "vultr"
    ip       = vultr_instance.redis.main_ip
    user     = "root"
    sudoer   = "root"
    name     = "redis-optout-fixture"
  }
}
