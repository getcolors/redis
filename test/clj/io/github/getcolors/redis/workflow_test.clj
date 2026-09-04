(ns io.github.getcolors.redis.workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.redis.validate-test :refer [fixture do-fixture]]
            [io.github.getcolors.redis.workflow :as workflow]))

(defn chain [event]
  (loop [step :redis/start acc []]
    (let [[_ & next] (workflow/wire-fn step {:green/event event})]
      (if (empty? next) (conj acc step) (recur (first next) (conj acc step))))))

;; The compute state as `start-step` reads it: nil is a readable state holding
;; no compute, a map is a recorded `params`, and a throw is a backend that
;; cannot be read.
(defn- start [opts state]
  (with-redefs [workflow/state-output (fn [_] state)]
    (workflow/start-step opts {})))

(defn- start-unreadable [opts]
  ;; The shape `green.tofu/outputs` throws: an ex-info carrying `:dir`. Only
  ;; that is an unreadable backend; anything else propagates as a defect.
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "tofu output failed: no backend" {:dir "x"})))]
    (workflow/start-step opts {})))

(def credentials
  {:vultr-api-key "v" :do-token "d"
   :redis-backup-r2-access-key-id "b" :redis-backup-r2-secret-access-key "s"})

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {}))))
  (is (= 0 (:green/exit (workflow/start-step (assoc (do-fixture) :green/event :build) {})))))

(deftest build-and-dry-run-never-touch-ssh-or-state
  ;; The standard forbids reading, creating, or requiring anything under ~/.ssh
  ;; on a build or dry-run: they render from desired state alone. Nor do they
  ;; read the backend: a throwing state read proves nothing on these paths
  ;; reaches it.
  (doseq [opts [(assoc (fixture) :green/event :build)
                (assoc (fixture) :green/event :create :green/dry-run true)
                (assoc (do-fixture) :green/event :delete :green/dry-run true)]]
    (let [result (start-unreadable opts)]
      (is (= 0 (:green/exit result)))
      (is (str/starts-with? (str (:ssh-public-key-path result)) "/home/build-placeholder")
          "a build must not name the operator's home directory"))))

(deftest real-create-requires-credentials
  (let [r (start (assoc (fixture) :green/event :create) nil)]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))
    (is (str/includes? (:green/err r) "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID"))
    ;; No DNS provider in this package: nothing is reachable by name.
    (is (not (str/includes? (:green/err r) "CLOUDFLARE")))))

(deftest real-create-and-delete-require-the-selected-providers-credentials
  (testing "create on DigitalOcean"
    (let [r (start (assoc (do-fixture) :green/event :create) nil)]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))
      (is (not (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY")))))
  (testing "delete on DigitalOcean"
    (let [r (start (assoc (do-fixture) :green/event :delete :compute-prevent-destroy false) nil)]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))
      (is (not (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY")))))
  (testing "delete on Vultr"
    (let [r (start (assoc (fixture) :green/event :delete :compute-prevent-destroy false) nil)]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))
      (is (not (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))))))

(deftest delete-is-protected
  (let [r (start (assoc (fixture) :green/event :delete) nil)]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COMPUTE_PREVENT_DESTROY"))))

;; --- provider switching is a rebuild, never an apply

(deftest a-provider-switch-is-refused-on-create-and-delete
  (doseq [event [:create :delete]]
    (testing (str "Vultr selected, DigitalOcean recorded, on " (name event))
      (let [r (start (assoc (fixture) :green/event event :compute-prevent-destroy false)
                     {:provider "digitalocean" :ip "203.0.113.9"})]
        (is (= 2 (:green/exit r)))
        (is (str/includes? (:green/err r)
                           "state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"))
        ;; The validator order is the thing under test: the actionable error,
        ;; not a missing token for the provider that was just selected.
        (is (not (str/includes? (:green/err r) "required credential is not set")))))
    (testing (str "DigitalOcean selected, Vultr recorded, on " (name event))
      (let [r (start (assoc (do-fixture) :green/event event :compute-prevent-destroy false)
                     {:provider "vultr" :ip "203.0.113.9"})]
        (is (= 2 (:green/exit r)))
        (is (str/includes? (:green/err r) "state holds a vultr machine; set provider-compute back to vultr"))
        (is (not (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN")))))))

(deftest legacy-state-accepts-only-the-default-provider
  (doseq [event [:create :delete]]
    (let [r (start (assoc (fixture) :green/event event :compute-prevent-destroy false)
                   {:ip "203.0.113.9"})]
      (is (not (str/includes? (:green/err r) "state holds")) (name event))
      (is (str/includes? (:green/err r) "required credential is not set") (name event)))
    (let [r (start (assoc (do-fixture) :green/event event :compute-prevent-destroy false)
                   {:ip "203.0.113.9"})]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "no recorded provider") (name event))
      (is (str/includes? (:green/err r) "set provider-compute back to vultr and delete first"))
      (is (not (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))))))

(deftest a-matching-provider-passes-to-the-credentials
  (let [r (start (assoc (fixture) :green/event :create) {:provider "vultr" :ip "203.0.113.9"})]
    (is (= 2 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "state holds")))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))))

(deftest an-unreadable-backend-counts-as-no-state-on-create
  ;; A fresh clone has no readable state and must still be able to create.
  (let [r (start-unreadable (assoc (fixture) :green/event :create))]
    (is (= 2 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "could not read")))
    (is (not (str/includes? (:green/err r) "state holds")))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))))

(deftest a-real-create-on-a-fresh-work-directory-reports-the-credentials-not-a-crash
  ;; A fresh clone has no `.colors/` yet, so the real state reader runs
  ;; `tofu output` in a stage directory that does not exist and the SDK's
  ;; shell raises a raw IOException. That is an unreadable state on a create
  ;; — no state at all — and the run must reach the credentials check, not
  ;; die on the exception. No stub: the real `state-output` runs.
  (let [workdir (str (java.nio.file.Files/createTempDirectory
                      "redis-fresh" (into-array java.nio.file.attribute.FileAttribute []))
                     "/.colors")
        r (workflow/start-step (assoc (fixture) :green/event :create :workdir workdir) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))
    (is (not (str/includes? (:green/err r) "could not read")))))

(deftest an-unreadable-backend-fails-a-real-delete-closed
  ;; Swallowing it is how a teardown ends up converging against 192.0.2.10.
  (let [r (start-unreadable (merge (fixture) credentials
                                   {:green/event :delete :compute-prevent-destroy false}))]
    (is (= 1 (:green/exit r)))
    (is (str/includes? (:green/err r) "could not read the infrastructure state for the delete cleanup"))
    (is (str/includes? (:green/err r) "no backend"))))

(deftest a-real-delete-adopts-the-recorded-address
  (let [r (start (merge (fixture) credentials {:green/event :delete :compute-prevent-destroy false})
                 {:provider "vultr" :ip "203.0.113.9" :user "root" :ssh_key_id "k"})]
    (is (= 0 (:green/exit r)))
    (is (= "203.0.113.9" (:ip r)))
    (is (= "k" (:ssh_key_id r)) "ONCE's key is adopted as written"))
  ;; A readable state without compute leaves the address unset, and the
  ;; cleanup step skips itself.
  (let [r (start (merge (fixture) credentials {:green/event :delete :compute-prevent-destroy false})
                 nil)]
    (is (= 0 (:green/exit r)))
    (is (nil? (:ip r)))))

(deftest rehearse-and-describe-run-against-state-and-say-when-there-is-none
  ;; Both run against the machine in state; with no state there is nothing to
  ;; rehearse against, and the message says what to do. An unreadable backend
  ;; is not "no state": it is reported as such, so nobody runs a create to fix
  ;; a bad credential.
  (doseq [event [:rehearse :describe]]
    (let [r (start (assoc (fixture) :green/event event) nil)]
      (is (= 1 (:green/exit r)) (str event))
      (is (str/includes? (:green/err r) "run create first")))
    (let [r (start (assoc (fixture) :green/event event) {:provider "vultr" :ip "203.0.113.9"})]
      (is (= 0 (:green/exit r)) (str event))
      (is (= "203.0.113.9" (:ip r))))
    (let [r (start-unreadable (assoc (fixture) :green/event event))]
      (is (= 1 (:green/exit r)) (str event))
      (is (str/includes? (:green/err r) (str "could not read the infrastructure state for " (name event))))
      (is (not (str/includes? (:green/err r) "run create first"))))))

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
