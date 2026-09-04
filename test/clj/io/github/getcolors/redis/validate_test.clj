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

(deftest names-are-checked-against-the-selected-provider
  (is (seq (validate/state-errors (do-fixture :digitalocean-name "Not_A_Hostname"))))
  (is (= [] (validate/state-errors (do-fixture :digitalocean-name "redis-a.b"))))
  (is (seq (validate/state-errors (fixture :vultr-name "has space"))))
  (is (= [] (validate/state-errors (fixture :vultr-name "Redis_1")))))

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

(deftest provider-specific-checks-run-only-when-selected
  (is (some #(str/includes? % "os-id") (validate/state-errors (fixture :vultr-os-id "2284"))))
  (is (= [] (validate/state-errors (do-fixture :vultr-os-id "2284"))))
  (testing "DigitalOcean must not own a VPC"
    (let [errors (validate/state-errors (do-fixture :digitalocean-vpc-uuid "u" :digitalocean-vpc-cidr "10.0.0.0/16"))]
      (is (some #(str/includes? % "digitalocean-vpc-uuid must be absent") errors))
      (is (some #(str/includes? % "digitalocean-vpc-cidr must be absent") errors)))
    (is (= [] (validate/state-errors (fixture :digitalocean-vpc-uuid "u"))) "ignored on Vultr")))

(deftest no-vpc-key-exists-any-more
  ;; The VPC binding is gone: a single-node package creates no private network,
  ;; and the old key is neither required nor validated.
  (is (not-any? #(str/includes? % "vpc") (validate/state-errors (dissoc (fixture) :vultr-vpc-subnet))))
  (is (= [] (validate/state-errors (fixture :vultr-vpc-subnet "not-a-cidr")))))

;; --- the network contract (§5)

(deftest ssh-sources-must-be-cidrs
  (doseq [[f k] [[fixture :vultr-ssh-sources] [do-fixture :digitalocean-ssh-sources]]]
    (testing (str k)
      (is (some #(str/includes? % "must list at least one CIDR") (validate/state-errors (f k []))))
      (is (some #(str/includes? % "is required") (validate/state-errors (f k ""))) "a blank value is a missing key")
      (let [errors (validate/state-errors (f k ["0.0.0.0/0" "example.com" "10.0.0.0/33" "::/129"]))]
        (is (= 3 (count (filter #(str/includes? % "is not an IPv4 or IPv6 CIDR") errors))) (pr-str errors)))
      (is (= [] (validate/state-errors (f k "203.0.113.0/24, 2001:db8::/32"))) "an overlay string parses")
      (is (= [] (validate/state-errors (f k ["0.0.0.0/0" "::/0" "203.0.113.7/32"])))))))

(deftest cidr-syntax
  (doseq [ok ["0.0.0.0/0" "::/0" "203.0.113.7/32" "2001:db8::1/128" "fe80::/10" "10.0.0.0/8"]]
    (is (validate/cidr? ok) ok))
  (doseq [bad ["203.0.113.7" "256.0.0.0/8" "10.0.0.0/33" "example.com/24" "::/129" "1:2:3:4:5:6:7:8:9/64" "" "/24"]]
    (is (not (validate/cidr? bad)) bad)))

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

;; --- provider switching is a rebuild, never an apply (§4)

(deftest provider-state-errors-decide-a-switch
  (testing "no state: nothing to compare"
    (is (nil? (validate/provider-state-errors (fixture) nil)))
    (is (nil? (validate/provider-state-errors (do-fixture) nil))))
  (testing "the recorded provider matches"
    (is (nil? (validate/provider-state-errors (fixture) {:provider "vultr" :ip "203.0.113.9"})))
    (is (nil? (validate/provider-state-errors (do-fixture) {:provider "digitalocean" :ip "203.0.113.9"}))))
  (testing "the recorded provider differs"
    (is (= ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
           (validate/provider-state-errors (fixture) {:provider "digitalocean" :ip "203.0.113.9"})))
    (is (= ["state holds a vultr machine; set provider-compute back to vultr and delete first"]
           (validate/provider-state-errors (do-fixture) {:provider "vultr" :ip "203.0.113.9"}))))
  (testing "a legacy state without a provider is the default provider's"
    (is (nil? (validate/provider-state-errors (fixture) {:ip "203.0.113.9"})))
    (let [[e] (validate/provider-state-errors (do-fixture) {:ip "203.0.113.9"})]
      (is (str/includes? e "no recorded provider"))
      (is (str/includes? e "set provider-compute back to vultr and delete first")))))
