terraform {
  required_providers {
    vultr = { source = "vultr/vultr", version = "~> 2.0" }
  }
}

provider "vultr" {
  # api key comes from VULTR_API_KEY in the environment
}

locals {
  ssh_sources = <{ ssh-sources-hcl|safe }>
}

<% if ssh-keygen %># The machine keypair this deployment generated and owns (SSH Keypair
# Standard): the account resource is named after the profile and lives in this
# stack's state, which is what makes its ownership decidable. Never reference a
# literal key id here in keygen mode.
resource "vultr_ssh_key" "machine" {
  name    = "<{ profile }>"
  ssh_key = trimspace(file("<{ ssh-public-key-path }>"))
}

<% endif %># Every label derives from one resolved name (Compute Name Standard §3), which
# is the profile unless desired state overrides it with vultr-name.
resource "vultr_firewall_group" "redis" {
  description = "<{ compute-name }>-firewall"
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
  label             = "<{ compute-name }>"
  region            = "<{ vultr-region }>"
  plan              = "<{ vultr-plan }>"
  os_id             = <{ vultr-os-id }>
  firewall_group_id = vultr_firewall_group.redis.id
  # SSH keys are ids already in the account, and ForceNew: changing the key set
  # destroys and recreates the instance instead of re-authorizing it. Rotation
  # is a rebuild, never an edit on a machine whose disk you intend to keep.
<% if ssh-keygen %>  ssh_key_ids = [vultr_ssh_key.machine.id]
<% else %>  ssh_key_ids = ["<{ vultr-ssh-keys }>"]
<% endif %>  # Wait for ssh before starting Ansible.
  connection {
    type = "ssh"
    user = "root"
    host = self.main_ip
<% if ssh-keygen %>    private_key = file("<{ ssh-private-key-path }>")
<% endif %>  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle { prevent_destroy = <{ compute-prevent-destroy }> }
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
    name     = "<{ compute-name }>"
<% if ssh-keygen %>    ssh_key_id = vultr_ssh_key.machine.id
<% endif %>  }
}
