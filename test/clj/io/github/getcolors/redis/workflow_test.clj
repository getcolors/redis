(ns io.github.getcolors.redis.workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.redis.validate-test :refer [fixture]]
            [io.github.getcolors.redis.workflow :as workflow]))

(defn chain [event]
  (loop [step :redis/start acc []]
    (let [[_ & next] (workflow/wire-fn step {:green/event event})]
      (if (empty? next) (conj acc step) (recur (first next) (conj acc step))))))

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {})))))

(deftest build-and-dry-run-never-touch-ssh
  ;; The standard forbids reading, creating, or requiring anything under ~/.ssh
  ;; on a build or dry-run: they render from desired state alone.
  (doseq [opts [(assoc (fixture) :green/event :build)
                (assoc (fixture) :green/event :create :green/dry-run true)]]
    (let [result (workflow/start-step opts {})]
      (is (= 0 (:green/exit result)))
      (is (str/starts-with? (str (:ssh-public-key-path result)) "/home/build-placeholder")
          "a build must not name the operator's home directory"))))

(deftest real-create-requires-credentials
  (let [r (workflow/start-step (assoc (fixture) :green/event :create) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))
    (is (str/includes? (:green/err r) "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID"))
    ;; No DNS provider in this package: nothing is reachable by name.
    (is (not (str/includes? (:green/err r) "CLOUDFLARE")))))

(deftest delete-is-protected
  (let [r (workflow/start-step (assoc (fixture) :green/event :delete) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COMPUTE_PREVENT_DESTROY"))))

(deftest rehearse-and-describe-refuse-without-compute-in-state
  ;; Both run against the machine in state; with no state there is nothing to
  ;; rehearse against, and the message says what to do.
  (doseq [event [:rehearse :describe]]
    (let [r (workflow/start-step (assoc (fixture) :green/event event) {})]
      (is (= 1 (:green/exit r)) (str event))
      (is (str/includes? (:green/err r) "run create first")))))

(deftest create-converges-in-dependency-order
  ;; The ssh-config block goes before the converge: both the converge and the
  ;; acceptance ride the alias it writes.
  (is (= [:redis/start :redis/infrastructure :redis/ssh-config :redis/ansible :redis/acceptance]
         (chain :create))))

(deftest delete-removes-the-config-block-before-and-the-key-after-the-destroy
  ;; The ordering is what makes "key present ⇔ deployment exists" hold: a
  ;; failed destroy never reaches the cleanup step, and correctly leaves the
  ;; key that is still the only credential to whatever survived.
  (let [c (chain :delete)]
    (is (= [:redis/start :redis/ansible :redis/ssh-config :redis/infrastructure :redis/ssh-cleanup] c))
    (is (< (.indexOf c :redis/ssh-config) (.indexOf c :redis/infrastructure)))
    (is (< (.indexOf c :redis/infrastructure) (.indexOf c :redis/ssh-cleanup)))))

(deftest rehearse-and-describe-run-against-state
  (is (= [:redis/start :redis/rehearsal] (chain :rehearse)))
  (is (= [:redis/start :redis/describe] (chain :describe))))

(deftest every-side-effecting-step-is-dry-run-advised
  (doseq [s [:redis/infrastructure :redis/ssh-config :redis/ansible :redis/acceptance
             :redis/ssh-cleanup :redis/rehearsal :redis/describe]]
    (is (some #{s} workflow/side-effecting) (str s " must be dry-run advised"))))
