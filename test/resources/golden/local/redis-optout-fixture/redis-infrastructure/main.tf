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
  vpc_block   = split("/", "10.60.0.0/24")[0]
  vpc_prefix  = tonumber(split("/", "10.60.0.0/24")[1])
}

# Every label derives from one resolved name (Compute Name Standard §3), which
# is the profile unless desired state overrides it with vultr-name.
resource "vultr_firewall_group" "redis" {
  description = "redis-optout-fixture-firewall"
}

# 22 is the only open port. Convergence, recovery, and the supported client
# path — an SSH tunnel to the loopback-bound Redis — all ride it. Redis is
# published on 127.0.0.1 and on the VPC address, never on the public one, so
# there is nothing else a firewall rule could gate. A Vultr firewall group
# filters the private interface too, so a future VPC peer needs a rule here
# for its /32 as well as the binding.
resource "vultr_firewall_rule" "ssh" {
  for_each          = toset(local.ssh_sources)
  firewall_group_id = vultr_firewall_group.redis.id
  protocol          = "tcp"
  port              = "22"
  ip_type           = strcontains(each.value, ":") ? "v6" : "v4"
  subnet            = split("/", each.value)[0]
  subnet_size       = tonumber(split("/", each.value)[1])
}

# The private network Redis is published on beside loopback. `vultr_vpc`, not
# `vultr_vpc2`: Vultr has retired the VPC 2.0 API while the provider still
# ships the resource and its documentation.
resource "vultr_vpc" "redis" {
  region         = "ams"
  description    = "redis-optout-fixture"
  v4_subnet      = local.vpc_block
  v4_subnet_mask = local.vpc_prefix
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
  vpc_ids           = [vultr_vpc.redis.id]
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

# The SSH Keypair Standard's contract: ownership is the resource id recorded
# in state and surfaced as `params.ssh_key_id`. `vpc_ip` is the address Redis
# publishes on beside loopback.
output "params" {
  value = {
    ip     = vultr_instance.redis.main_ip
    vpc_ip = vultr_instance.redis.internal_ip
    user   = "root"
    sudoer = "root"
    name   = "redis-optout-fixture"
  }
}
