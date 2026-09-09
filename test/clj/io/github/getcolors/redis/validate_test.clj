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


(deftest application-fixtures-and-backends
  (doseq [f [fixture optout do-fixture do-optout]] (is (= [] (validate/state-errors (f))))))
(deftest image-port-and-backup-policy
  (doseq [[key value] [[:redis-image "redis:latest"] [:redis-port 0] [:redis-port 65536]
                       [:redis-backup-retention-days 0] [:redis-backup-max-age-hours -1]
                       [:redis-backup-r2-endpoint "http://example.test"] [:provider-backend "local"]]]
    (is (seq (validate/state-errors (fixture key value))) (str key))))
(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "wrong"}))))
(deftest credentials-belong-to-their-lifecycle
  (is (= 2 (count (validate/secret-errors (fixture) :create))))
  (is (empty? (validate/secret-errors (fixture) :delete))))
(deftest key-mode-delegates-to-library
  (is (validate/keygen? (fixture)))
  (is (not (validate/keygen? (optout))))
  (is (not (validate/keygen? (do-optout)))))
