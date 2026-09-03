(ns io.github.getcolors.redis.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.redis.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))

(deftest fixture-is-valid (is (= [] (validate/state-errors (fixture)))))

(deftest optout-fixture-is-valid (is (= [] (validate/state-errors (optout)))))

(deftest machine-key-is-not-required
  ;; The standard makes absence meaningful: requiring vultr-ssh-keys would make
  ;; every conforming deployment invalid.
  (is (not-any? #(str/includes? % "vultr-ssh-keys") (validate/state-errors (fixture)))))

(deftest absent-machine-key-selects-keygen
  (is (true? (validate/keygen? (fixture))))
  (is (false? (validate/keygen? (optout)))))

(deftest the-machine-is-named-after-the-profile
  ;; Compute Name Standard: no name key required, the profile is the name, and
  ;; the optional override wins only when it is genuinely present.
  (is (= "redis-fixture" (validate/compute-name (fixture))))
  (is (= "redis-fixture" (validate/compute-name (fixture :vultr-name "REPLACE_ME"))))
  (is (= "custom" (validate/compute-name (fixture :vultr-name "custom")))))

(deftest reports-all-errors
  (let [errors (validate/state-errors
                (fixture :redis-image "redis:latest"
                         :provider-compute "digitalocean"
                         :redis-port 70000
                         :redis-backup-r2-endpoint "ftp://example"
                         :redis-backup-retention-days 0
                         :vultr-vpc-subnet "not-a-cidr"
                         :vultr-os-id "2284"))]
    (is (<= 6 (count errors)))
    (doseq [part ["digest" "vultr" "redis-port" "endpoint" "retention" "vpc-subnet" "os-id"]]
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

(deftest a-create-names-every-package-secret
  (let [errors (str/join "\n" (validate/secret-errors (fixture) :create))]
    (doseq [name ["COLORS_PAR_VULTR_API_KEY"
                  "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID"
                  "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY"]]
      (is (str/includes? errors name) name))
    ;; The Redis password is generated on the server and never supplied by
    ;; the operator; there is likewise no DNS provider to credential.
    (is (not (str/includes? errors "PASSWORD")))
    (is (not (str/includes? errors "CLOUDFLARE")))))

(deftest a-delete-asks-only-for-the-providers
  ;; Destroying a machine must not require the credentials needed to converge
  ;; one; the backup pair should not be a lock on the exit.
  (let [errors (str/join "\n" (validate/secret-errors (fixture) :delete))]
    (is (str/includes? errors "COLORS_PAR_VULTR_API_KEY"))
    (is (not (str/includes? errors "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID")))))
