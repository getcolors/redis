(ns io.github.getcolors.redis.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.cli :as green-cli]
            [io.github.getcolors.redis.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")
(def do-fixture-file "test/fixtures/colors-digitalocean.yml")
(def do-optout-file "test/fixtures/optout-digitalocean.yml")
(def aws-fixture-file "test/fixtures/colors-aws.yml")
(def aws-optout-file "test/fixtures/optout-aws.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))
(defn do-fixture [& {:as overrides}] (read-fixture do-fixture-file overrides))
(defn do-optout [& {:as overrides}] (read-fixture do-optout-file overrides))
(defn aws-fixture [& {:as overrides}] (read-fixture aws-fixture-file overrides))
(defn aws-optout [& {:as overrides}] (read-fixture aws-optout-file overrides))
(def all-fixtures [fixture optout do-fixture do-optout aws-fixture aws-optout])


(deftest application-fixtures-and-backends
  (doseq [f all-fixtures] (is (= [] (validate/state-errors (assoc (f) :redis-storage-managed (true? (:redis-storage-managed (f)))))) (str f))))
(deftest image-port-and-backup-policy
  (doseq [[key value] [[:redis-image "redis:latest"] [:redis-port 0] [:redis-port 65536]
                       [:redis-backup-retention-days 0] [:redis-backup-max-age-hours -1]
                       [:redis-backup-r2-endpoint "http://example.test"] [:provider-backend "local"]]]
    (is (seq (validate/state-errors (fixture key value))) (str key))))
(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "wrong"}))))
(deftest credentials-belong-to-their-lifecycle
  (is (= 2 (count (validate/secret-errors (fixture) :create))))
  (is (empty? (validate/secret-errors (fixture) :delete)))
  (testing "an operator-owned bucket on AWS still needs the operator's pair"
    (is (= 2 (count (validate/secret-errors (aws-optout) :create)))))
  (testing "a managed bucket needs nothing from the environment: the pair is a stage output"
    (is (empty? (validate/secret-errors (aws-fixture) :create)))
    (is (empty? (validate/secret-errors (aws-fixture) :delete)))
    (is (= 2 (count (validate/secret-errors (assoc (aws-fixture) :redis-storage-managed false) :create))))))

(deftest managed-storage-has-one-shape
  (let [errors #(validate/state-errors (merge (aws-fixture) %))]
    (is (= [] (errors {})))
    (is (some #(re-find #"must be true or false" %) (validate/state-errors (dissoc (aws-fixture) :redis-storage-managed))))
    (is (some #(re-find #"must be true or false" %) (errors {:redis-storage-managed "yes"})))
    (is (some #(re-find #"provider-backend s3" %) (errors {:provider-backend "r2" :r2-bucket "b" :r2-endpoint "https://x.r2.cloudflarestorage.com"})))
    (is (some #(re-find #"s3-bucket-mode managed" %) (errors {:s3-bucket-mode "external"})))
    (is (some #(re-find #"s3-bucket-mode managed" %) (validate/state-errors (dissoc (aws-fixture) :s3-bucket-mode))))
    (is (some #(re-find #"must equal s3-region" %) (errors {:redis-backup-r2-region "eu-west-1"})))
    (is (some #(re-find #"must be https://s3.us-east-1.amazonaws.com" %) (errors {:redis-backup-r2-endpoint "https://s3.eu-west-1.amazonaws.com"})))
    (is (some #(re-find #"must be https://s3.us-east-1.amazonaws.com" %) (errors {:redis-backup-r2-endpoint "https://fixture.r2.cloudflarestorage.com"})))
    (is (some #(re-find #"must not contain dots" %) (errors {:redis-backup-r2-bucket "redis.backup"})))
    (is (some #(re-find #"must differ from s3-bucket" %) (errors {:redis-backup-r2-bucket "redis-aws-fixture-state"}))))
  (testing "none of it applies to an operator-owned bucket"
    (is (= [] (validate/state-errors (assoc (aws-optout) :redis-storage-managed false :redis-backup-r2-region "eu-west-1"))))
    (is (= [] (validate/state-errors (assoc (fixture) :redis-storage-managed false))))))
(deftest key-mode-delegates-to-library
  (is (validate/keygen? (fixture)))
  (is (validate/keygen? (aws-fixture)))
  (is (not (validate/keygen? (optout))))
  (is (not (validate/keygen? (do-optout))))
  (is (not (validate/keygen? (aws-optout)))))

(deftest runtime-tools-follow-the-selected-ssh-mode
  (doseq [[opts expected] [[(fixture) "Missing: aws, ssh-keygen, tofu."]
                           [(optout) "Missing: aws, tofu."]]]
    (let [errors (validate/runtime-tool-errors opts {})]
      (is (str/includes? (first errors) expected))
      (is (= 6 (count errors)))
      (doseq [tool ["ansible-playbook" "ssh" "redis-cli" "bash" "timeout"]]
        (is (some #(str/includes? % (str "required executable is not on PATH: " tool ";")) errors))))))
