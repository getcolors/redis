(ns io.github.getcolors.redis.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.cli :as green-cli]
            [io.github.getcolors.redis.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")
(def do-fixture-file "test/fixtures/colors-digitalocean.yml")
(def do-optout-file "test/fixtures/optout-digitalocean.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))
(defn do-fixture [& {:as overrides}] (read-fixture do-fixture-file overrides))
(defn do-optout [& {:as overrides}] (read-fixture do-optout-file overrides))

(deftest every-fixture-is-valid
  (doseq [f [fixture optout do-fixture do-optout]]
    (is (= [] (validate/state-errors (f))))))

;; --- the spec handed to ONCE

(deftest the-spec-carries-this-packages-registry-sources-and-default
  ;; The operations are ONCE's; this is the data they run over. A registry,
  ;; sources map or default that drifts fails here, literally.
  (is (= #{"digitalocean" "vultr"} (set (keys (:registry validate/spec)))))
  (is (= validate/compute-providers (:registry validate/spec)))
  (is (= {:required [:digitalocean-region :digitalocean-size :digitalocean-image
                     :digitalocean-ssh-sources]
          :secrets [:do-token]
          :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}
         (get-in validate/spec [:registry "digitalocean"])))
  (is (= {:required [:vultr-region :vultr-plan :vultr-os-id :vultr-ssh-sources]
          :secrets [:vultr-api-key]
          :tofu-env {:vultr-api-key "VULTR_API_KEY"}}
         (get-in validate/spec [:registry "vultr"])))
  ;; No HTTP list: nothing in this package speaks HTTP, so only SSH is a source.
  (is (= {:non-empty ["ssh-sources"] :may-be-empty []} (:sources validate/spec)))
  (is (= "vultr" (:default validate/spec)))
  (is (= validate/default-compute-provider (:default validate/spec)))
  (is (not (contains? validate/spec :name-rules)) "the name rules are ONCE's"))

(deftest machine-key-is-not-required
  ;; The standard makes absence meaningful: requiring <provider>-ssh-keys
  ;; would make every conforming deployment invalid.
  (is (not-any? #(str/includes? % "vultr-ssh-keys") (validate/state-errors (fixture))))
  (is (not-any? #(str/includes? % "digitalocean-ssh-keys") (validate/state-errors (do-fixture)))))

(deftest absent-machine-key-selects-keygen
  (is (true? (validate/keygen? (fixture))))
  (is (false? (validate/keygen? (optout))))
  (is (true? (validate/keygen? (do-fixture))))
  (is (false? (validate/keygen? (do-optout)))))

(deftest the-machine-is-named-after-the-profile
  ;; Compute Name Standard: no name key required, the profile is the name, and
  ;; the optional override wins only when it is genuinely present — read
  ;; through the selected provider, never a fixed prefix.
  (is (= "redis-fixture" (validate/compute-name (fixture))))
  (is (= "redis-fixture" (validate/compute-name (fixture :vultr-name "REPLACE_ME"))))
  (is (= "custom" (validate/compute-name (fixture :vultr-name "custom"))))
  (is (= "redis-digitalocean-fixture" (validate/compute-name (do-fixture))))
  (is (= "custom" (validate/compute-name (do-fixture :digitalocean-name "custom"))))
  (is (= "redis-digitalocean-fixture" (validate/compute-name (do-fixture :vultr-name "custom")))
      "the other provider's name key is ignored"))

;; --- the registry (Compute Provider Standard §2)

(deftest an-unadvertised-provider-is-refused-with-the-sorted-list
  (let [errors (validate/state-errors (fixture :provider-compute "hcloud"))]
    (is (some #(= % ":provider-compute must be one of digitalocean, vultr") errors))))

(deftest required-keys-follow-the-selected-provider
  (testing "the selected provider's keys are required"
    (let [errors (validate/state-errors (dissoc (fixture) :vultr-plan :vultr-region))]
      (is (some #(str/includes? % ":vultr-plan is required") errors))
      (is (some #(str/includes? % ":vultr-region is required") errors)))
    (let [errors (validate/state-errors (dissoc (do-fixture) :digitalocean-size :digitalocean-ssh-sources))]
      (is (some #(str/includes? % ":digitalocean-size is required") errors))
      (is (some #(str/includes? % ":digitalocean-ssh-sources is required") errors))))
  (testing "the unselected provider's keys are neither required nor refused"
    (is (not-any? #(str/includes? % "digitalocean") (validate/state-errors (fixture))))
    (is (= [] (validate/state-errors (fixture :digitalocean-region "ams3" :digitalocean-size "s-1vcpu-2gb"))))
    (is (= [] (validate/state-errors (do-fixture :vultr-os-id "not-a-number" :vultr-vpc-subnet "x"))))))

(deftest no-vpc-key-exists-any-more
  ;; The VPC binding is gone: a single-node package creates no private network,
  ;; and the old key is neither required nor validated.
  (is (not-any? #(str/includes? % "vpc") (validate/state-errors (dissoc (fixture) :vultr-vpc-subnet))))
  (is (= [] (validate/state-errors (fixture :vultr-vpc-subnet "not-a-cidr")))))

;; --- the network contract (§5)

(deftest ssh-sources-must-be-cidrs
  ;; The wiring of ONCE's network contract over this package's `spec`: the
  ;; selected provider's SSH list is the one that must not be empty, and its
  ;; entries are the ones checked. The CIDR grammar itself is ONCE's matrix.
  (doseq [[f k] [[fixture :vultr-ssh-sources] [do-fixture :digitalocean-ssh-sources]]]
    (testing (str k)
      (is (some #(str/includes? % "must list at least one CIDR") (validate/state-errors (f k []))))
      (is (some #(str/includes? % "is required") (validate/state-errors (f k ""))) "a blank value is a missing key")
      (is (some #(str/includes? % "is not an IPv4 or IPv6 CIDR") (validate/state-errors (f k ["example.com"]))))
      (is (= [] (validate/state-errors (f k "203.0.113.0/24, 2001:db8::/32"))) "an overlay string parses"))))

(deftest reports-all-errors
  (let [errors (validate/state-errors
                (fixture :redis-image "redis:latest"
                         :redis-port 70000
                         :redis-backup-r2-endpoint "ftp://example"
                         :redis-backup-retention-days 0
                         :vultr-ssh-sources ["nope"]
                         :vultr-os-id "2284"))]
    (is (<= 6 (count errors)))
    (doseq [part ["digest" "redis-port" "endpoint" "retention" "ssh-sources" "os-id"]]
      (is (some #(str/includes? % part) errors) part))))

(deftest the-image-may-not-float
  ;; Docker Hub republishes 7.2 and 7.2.16 whenever the base image is rebuilt,
  ;; so a tag alone does not pin bytes: the digest is required.
  (is (some #(str/includes? % "digest")
            (validate/state-errors (fixture :redis-image "docker.io/library/redis:7.2.16"))))
  (is (= [] (validate/state-errors
             (fixture :redis-image (str "docker.io/library/redis:7.2.16@sha256:"
                                        (apply str (repeat 64 "a"))))))))

(deftest missing-keys-are-all-reported
  (let [errors (validate/state-errors (dissoc (fixture) :redis-backup-r2-bucket :vultr-plan))]
    (is (some #(str/includes? % "redis-backup-r2-bucket") errors))
    (is (some #(str/includes? % "vultr-plan") errors))))

(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "other"})))
  (is (nil? (validate/env-errors {}))))

;; --- secrets and the OpenTofu environment derive from the registry entry

(deftest a-create-names-every-package-secret
  (let [errors (str/join "\n" (validate/secret-errors (fixture) :create))]
    (doseq [name ["COLORS_PAR_VULTR_API_KEY"
                  "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID"
                  "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY"]]
      (is (str/includes? errors name) name))
    (is (not (str/includes? errors "DO_TOKEN")))
    ;; The Redis password is generated on the server and never supplied by
    ;; the operator; there is likewise no DNS provider to credential.
    (is (not (str/includes? errors "PASSWORD")))
    (is (not (str/includes? errors "CLOUDFLARE"))))
  (let [errors (str/join "\n" (validate/secret-errors (do-fixture) :create))]
    (is (str/includes? errors "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? errors "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID"))
    (is (not (str/includes? errors "VULTR_API_KEY")))))

(deftest a-delete-asks-only-for-the-providers
  ;; Destroying a machine must not require the credentials needed to converge
  ;; one; the backup pair should not be a lock on the exit.
  (let [errors (str/join "\n" (validate/secret-errors (fixture) :delete))]
    (is (str/includes? errors "COLORS_PAR_VULTR_API_KEY"))
    (is (not (str/includes? errors "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID"))))
  (let [errors (str/join "\n" (validate/secret-errors (do-fixture) :delete))]
    (is (str/includes? errors "COLORS_PAR_DO_TOKEN"))
    (is (not (str/includes? errors "VULTR_API_KEY")))))

(deftest tofu-env-follows-the-selected-provider
  (is (= {:vultr-api-key "VULTR_API_KEY"} (validate/tofu-env (fixture) :provider-compute)))
  (is (= {:do-token "DIGITALOCEAN_TOKEN"} (validate/tofu-env (do-fixture) :provider-compute)))
  (is (= {} (validate/tofu-env (fixture :provider-compute "hcloud") :provider-compute)))
  (is (= {:r2-access-key-id "AWS_ACCESS_KEY_ID" :r2-secret-access-key "AWS_SECRET_ACCESS_KEY"}
         (validate/tofu-env (fixture :provider-backend "r2") :provider-backend))))
