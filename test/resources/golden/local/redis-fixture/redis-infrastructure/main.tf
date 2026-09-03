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

# The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable. Never reference a
# literal key id here in keygen mode.
resource "vultr_ssh_key" "machine" {
  name    = "redis-fixture"
  ssh_key = trimspace(file("/home/build-placeholder/.ssh/redis-fixture.pub"))
}

# Every label derives from one resolved name (Compute Name Standard §3), which
# is the profile unless desired state overrides it with vultr-name.
resource "vultr_firewall_group" "redis" {
  description = "redis-fixture-firewall"
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
  description    = "redis-fixture"
  v4_subnet      = local.vpc_block
  v4_subnet_mask = local.vpc_prefix
}

resource "vultr_instance" "redis" {
  # `label` is the console name and updates in place. There is deliberately no
  # `hostname`: Vultr implements a hostname change as an OS reinstall, so the
  # provider marks that attribute ForceNew, and editing the name would
  # destroy the instance and its disk rather than rename it.
  label             = "redis-fixture"
  region            = "ams"
  plan              = "vc2-1c-2gb"
  os_id             = 2284
  firewall_group_id = vultr_firewall_group.redis.id
  vpc_ids           = [vultr_vpc.redis.id]
  # SSH keys are ids already in the account, and ForceNew: changing the key set
  # destroys and recreates the instance instead of re-authorizing it. Rotation
  # is a rebuild, never an edit on a machine whose disk you intend to keep.
  ssh_key_ids = [vultr_ssh_key.machine.id]
  # Wait for ssh before starting Ansible.
  connection {
    type = "ssh"
    user = "root"
    host = self.main_ip
    private_key = file("/home/build-placeholder/.ssh/redis-fixture")
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
    name   = "redis-fixture"
    ssh_key_id = vultr_ssh_key.machine.id
  }
}
