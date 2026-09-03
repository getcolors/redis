{ pkgs, ... }:
{
  languages.clojure.enable = true;
  languages.opentofu.enable = true;
  # redis for the workstation-side acceptance gate (redis-cli through the tunnel).
  packages = with pkgs; [ ansible babashka curl jq openssh openssl rclone redis ];
}
