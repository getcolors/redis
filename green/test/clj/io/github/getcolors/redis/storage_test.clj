(ns io.github.getcolors.redis.storage-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [io.github.getcolors.redis.storage :as storage]
            [io.github.getcolors.redis.validate-test :refer [fixture aws-fixture aws-optout]]))

(deftest the-managed-gate-is-the-one-key
  (is (storage/managed? (aws-fixture)))
  (is (not (storage/managed? (aws-optout))))
  (is (not (storage/managed? (fixture))))
  (is (not (storage/managed? (assoc (fixture) :redis-storage-managed "true")))))

(deftest the-stage-renders-one-template-and-no-credential
  (let [opts (assoc (aws-fixture) :green/event :build storage/credentials-key {:credentials {"backup" {"access_key_id" "AKIA" "secret_access_key" "s"}}})
        [spec :as specs] (storage/specs opts)]
    (is (= 1 (count specs)))
    (is (str/ends-with? (str (:target spec)) "/redis-aws-fixture/redis-storage/main.tf"))
    (is (= :io.github.getcolors.redis.tools.storage/main.tf (:template spec)))
    (is (nil? (get (:data spec) storage/credentials-key)))
    (is (= sc/preserve-jinja-delimiters (:opts spec)))))

(deftest the-credential-env-carries-the-operator-names
  (let [env (storage/credential-env {storage/credentials-key {:credentials {"backup" {"access_key_id" "fixture-id" "secret_access_key" "fixture-secret"}}}})]
    (is (= {"COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID" "fixture-id"
            "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY" "fixture-secret"} env)))
  (testing "keyword keys from a parsed output work too"
    (is (= "k" (get (storage/credential-env {storage/credentials-key {:credentials {:backup {:access_key_id "k" :secret_access_key "s"}}}})
                    "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID"))))
  (testing "a missing or blank pair is refused, never an empty variable"
    (is (thrown? Exception (storage/credential-env {})))
    (is (thrown? Exception (storage/credential-env {storage/credentials-key {:credentials {"backup" {"access_key_id" "" "secret_access_key" "s"}}}})))))

(deftest aws-env-overlays-only-what-is-set
  (is (= {} (storage/aws-env (aws-fixture))))
  (is (= {"AWS_ACCESS_KEY_ID" "AKIA" "AWS_SECRET_ACCESS_KEY" "s" "AWS_SESSION_TOKEN" "t"}
         (storage/aws-env (assoc (aws-fixture) :aws-access-key-id "AKIA" :aws-secret-access-key "s" :aws-session-token "t"))))
  (is (= {"AWS_ACCESS_KEY_ID" "AKIA"} (storage/aws-env {:aws-access-key-id "AKIA" :aws-secret-access-key ""}))))

(deftest the-step-is-a-no-op-unless-managed
  (let [opts (assoc (aws-optout) :green/event :create)]
    (with-redefs [tofu/tofu-with-spec (fn [& _] (throw (Exception. "must not run")))]
      (is (= 0 (:green/exit (storage/step opts))))))
  (is (= (aws-optout) (storage/read-credentials! (aws-optout)))))

(deftest an-empty-state-is-recognised-across-opentofu-releases
  (testing "OpenTofu 1.11.5 (the neon-multi-node-aws build)"
    (is (storage/empty-state? {:exit 1 :err "No state file was found!\nState management commands require a state file. Run this command\n"})))
  (testing "OpenTofu 1.12.5 (the redis-aws build), no bang and an Error: prefix"
    (is (storage/empty-state? {:exit 1 :err "\nError: No state file was found\n\nState management commands require a state file. Run this command in a\n"})))
  (testing "anything else fails closed: a failed read never means absence"
    (is (not (storage/empty-state? {:exit 0 :err "" :out ""})))
    (is (not (storage/empty-state? {:exit 1 :err "Error: error loading the remote state: AccessDenied\n"})))
    (is (not (storage/empty-state? {:exit 2 :err "No state file was found!"})))))

(defn- temp-workdir []
  (str (java.nio.file.Files/createTempDirectory "redis-test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest a-create-runs-the-preflight-then-tofu-with-the-aws-environment
  (let [calls (atom [])
        opts (aws-fixture :green/event :create :aws-access-key-id "AKIA" :aws-secret-access-key "s" :workdir (temp-workdir))]
    (with-redefs [storage/ownership-preflight! (fn [_] (swap! calls conj :preflight))
                  tofu/tofu-with-spec (fn [opts specs config]
                                        (swap! calls conj :tofu)
                                        (is (= 1 (count specs)))
                                        (is (= {"AWS_ACCESS_KEY_ID" "AKIA" "AWS_SECRET_ACCESS_KEY" "s"} (:env config)))
                                        (is (= storage/credentials-key (:output-key config)))
                                        (assoc opts :green/exit 0))]
      (is (= 0 (:green/exit (storage/step opts))))
      (is (= [:preflight :tofu] @calls))))
  (testing "a delete runs no preflight"
    (let [calls (atom [])]
      (with-redefs [storage/ownership-preflight! (fn [_] (swap! calls conj :preflight))
                    tofu/tofu-with-spec (fn [opts _ _] (swap! calls conj :tofu) (assoc opts :green/exit 0))]
        (is (= 0 (:green/exit (storage/step (aws-fixture :green/event :delete :workdir (temp-workdir))))))
        (is (= [:tofu] @calls)))))
  (testing "a refused preflight is an error with no credential in the message"
    (with-redefs [storage/ownership-preflight! (fn [_] (throw (ex-info "AKIAEXAMPLE leaked" {})))]
      (let [r (storage/step (aws-fixture :green/event :create :workdir (temp-workdir)))]
        (is (= 1 (:green/exit r)))
        (is (not (str/includes? (:green/err r) "AKIA")))))))

(deftest read-credentials-refuses-a-missing-output
  (with-redefs-fn {#'tofu/outputs (fn [& _] {:credentials {}})
                   #'storage/checked (fn [& _] "")}
    (fn []
      (is (thrown-with-msg? Exception #"converge storage before rehearsal"
                            (storage/read-credentials! (aws-fixture :green/event :rehearse :workdir (temp-workdir))))))))
