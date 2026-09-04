terraform {
  required_providers {
    digitalocean = { source = "digitalocean/digitalocean", version = "~> 2.0" }
  }
}

provider "digitalocean" {
  # token comes from DIGITALOCEAN_TOKEN in the environment
}

locals {
  ssh_sources = ["0.0.0.0/0", "::/0"]
}

# The region's account-default VPC, discovered at plan time. This package
# creates no VPC and pins no UUID: the droplet joins whatever `default-<region>`
# is, and the validator refuses digitalocean-vpc-uuid and digitalocean-vpc-cidr
# so desired state cannot quietly start owning one.
data "digitalocean_vpc" "default" {
  name = "default-ams3"
}

resource "digitalocean_droplet" "redis" {
  # `name` is the console label and updates in place; cloud-init also sets the
  # guest hostname from it at creation, and a later rename never revisits that,
  # so a changed name takes effect on the next create rather than repairing a
  # running host. `region`, `image` and `vpc_uuid` are ForceNew: editing any of
  # them destroys the droplet and its disk. `size` alone resizes in place.
  name     = "redis-digitalocean-optout"
  region   = "ams3"
  size     = "s-1vcpu-2gb"
  image    = "ubuntu-24-04-x64"
  vpc_uuid = data.digitalocean_vpc.default.id
  # SSH keys are ids or fingerprints already in the account, and ForceNew:
  # changing the key set destroys and recreates the droplet instead of
  # re-authorizing it. Rotation is a rebuild, never an edit on a machine whose
  # disk you intend to keep.
  ssh_keys = ["00000000"]
  # Wait for ssh before starting Ansible.
  connection {
    type = "ssh"
    user = "root"
    host = self.ipv4_address
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle { prevent_destroy = true }
}

# The provider firewall is the load-bearing layer: 22 from the SSH sources and
# nothing else. Convergence, recovery, and the supported client path — an SSH
# tunnel to the loopback-bound Redis — all ride it. Redis is published on
# 127.0.0.1 alone, never on the public address, so there is nothing else a
# rule could gate; Ansible manages no ufw for this port.
resource "digitalocean_firewall" "redis" {
  name        = "redis-digitalocean-optout-firewall"
  droplet_ids = [digitalocean_droplet.redis.id]
  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = local.ssh_sources
  }
  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
  lifecycle { prevent_destroy = true }
}

# The Compute Provider Standard's contract (§4): `provider` is the registry
# name this template belongs to, which is what makes a provider switch
# decidable; `ssh_key_id` is the SSH Keypair Standard's ownership record.
output "params" {
  value = {
    provider = "digitalocean"
    ip       = digitalocean_droplet.redis.ipv4_address
    user     = "root"
    sudoer   = "root"
    name     = "redis-digitalocean-optout"
  }
}
