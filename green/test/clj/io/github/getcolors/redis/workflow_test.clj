(ns io.github.getcolors.redis.workflow-test
 (:require [clojure.test :refer [deftest is testing]] [clojure.java.io :as io] [clojure.string :as str]
           [io.github.getcolors.redis.validate :as validate]
           [io.github.getcolors.compute-diagnostics :as diagnostics]
           [io.github.getcolors.redis.ssh-config :as ssh-config]
           [green.workflow :as engine] [io.github.getcolors.redis.workflow :as workflow]
           [io.github.getcolors.redis.tools :as tools] [io.github.getcolors.redis.compute :as compute]
           [io.github.getcolors.compute-orchestration :as orchestration]
           [io.github.getcolors.compute-inspection :as inspection]
           [io.github.getcolors.compute-managed-backend :as managed-backend]
           [io.github.getcolors.redis.storage :as storage]
           [io.github.getcolors.redis.validate-test :refer [fixture optout do-fixture do-optout aws-fixture aws-optout all-fixtures]]))
(defn- route [event opts start] (loop [step start path [step]] (let [[_ next] (workflow/wire-fn step (assoc opts :green/event event))] (if next (recur next (conj path next)) path))))
(deftest missing-tools-stop-before-any-live-work
 (let [opts (fixture :green/event :create
                     :redis-backup-r2-access-key-id "fixture-access"
                     :redis-backup-r2-secret-access-key "fixture-secret")]
  (with-redefs [diagnostics/missing-tools (fn [_ _] ["tofu"])
                validate/executable-on-path? (fn [program _] (not (= "ansible-playbook" program)))
                ssh-config/preflight! (fn [_] (throw (AssertionError. "must stop before SSH preflight")))]
   (let [r (workflow/start-step opts {"PATH" "/tools"})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "Missing: tofu."))
    (is (str/includes? (:green/err r) "required executable is not on PATH: ansible-playbook"))))
  (with-redefs [diagnostics/missing-tools (fn [& _] (throw (AssertionError. "preview must not inspect infrastructure tools")))
                validate/executable-on-path? (fn [& _] (throw (AssertionError. "preview must not inspect executables")))]
   (doseq [preview [(assoc opts :green/event :build) (assoc opts :green/dry-run true)]]
    (is (= 0 (:green/exit (workflow/start-step preview {}))))))))
(deftest cleanup-order-and-read-only-events
 (is (= [tools/load-infrastructure-step :redis/ansible] (workflow/wire-fn :redis/load-infrastructure {:green/event :delete})))
 (is (= [tools/ansible-local-step :redis/infrastructure] (workflow/wire-fn :redis/ssh-config {:green/event :delete})))
 (is (= [tools/infrastructure-step] (workflow/wire-fn :redis/infrastructure {:green/event :delete})))
 (doseq [event [:rehearse :describe]]
  (is (= :redis/load-infrastructure (second (workflow/wire-fn :redis/start {:green/event event}))))))
(deftest failures-refuse-application-inventory
 (with-redefs [orchestration/orchestrate (fn [& _] {:status "error"})]
  (is (= 1 (:green/exit (tools/infrastructure-step (fixture :green/event :create))))))
 (with-redefs [inspection/read-deployment (fn [_ env deps _] (is (map? env)) (is (contains? env "HOME")) (is (= {} deps)) {:status "error"})]
  (is (= 1 (:green/exit (tools/load-infrastructure-step (fixture :green/event :delete))))))
 (is (thrown? Exception (compute/node (fixture :green/event :create)))))
(deftest inspection-preserves-connection-user-and-identity
 (let [node {:node_id "0" :provider "vultr" :name "redis-fixture" :ip "203.0.113.8" :user "ubuntu" :sudoer "ubuntu" :ssh_identity_file "/operator/key"}]
  (with-redefs [inspection/read-deployment (fn [& _] {:status "present" :cluster {:nodes [node]}})]
   (let [out (tools/load-infrastructure-step (fixture :green/event :describe))]
    (is (= "ubuntu" (:user out))) (is (= "/operator/key" (:ssh-private-key-path out)))
    (is (= "203.0.113.8" (:ip (compute/node out))))))))
(deftest destroyed-delete-stops-cleanup
 (with-redefs [inspection/read-deployment (fn [& _] {:status "destroyed"})]
  (is (:redis/already-destroyed (tools/load-infrastructure-step (fixture :green/event :delete))))
  (is (= 1 (:green/exit (tools/load-infrastructure-step (fixture :green/event :describe)))))))
(deftest managed-storage-is-wired-between-compute-and-the-host
 (is (= [:redis/start :redis/infrastructure :redis/storage :redis/ssh-config :redis/ansible :redis/acceptance] (route :create (aws-fixture) :redis/start)))
 (is (= [:redis/start :redis/infrastructure :redis/ssh-config :redis/ansible :redis/acceptance] (route :create (aws-optout) :redis/start)))
 (is (= [:redis/start :redis/infrastructure :redis/ssh-config :redis/ansible :redis/acceptance] (route :create (fixture) :redis/start)))
 (is (= [storage/step :redis/ssh-config] (workflow/wire-fn :redis/storage (assoc (aws-fixture) :green/event :create)))))
(deftest delete-destroys-the-bucket-after-the-machine-and-the-state-bucket-last
 (is (= [:redis/start :redis/load-infrastructure :redis/ansible :redis/ssh-config :redis/infrastructure :redis/storage :redis/backend-finalize] (route :delete (aws-fixture) :redis/start)))
 (is (= [:redis/start :redis/load-infrastructure :redis/ansible :redis/ssh-config :redis/infrastructure] (route :delete (aws-optout) :redis/start)))
 (is (= [:redis/start :redis/load-infrastructure :redis/ansible :redis/ssh-config :redis/infrastructure] (route :delete (fixture) :redis/start)))
 (is (= [:redis/start :redis/load-infrastructure :redis/ansible :redis/ssh-config :redis/infrastructure :redis/backend-finalize] (route :delete (assoc (aws-optout) :s3-bucket-mode "managed") :redis/start)))
 (is (= [:redis/start :redis/load-infrastructure :redis/ansible :redis/ssh-config :redis/infrastructure :redis/storage] (route :delete (assoc (aws-fixture) :s3-bucket-mode "external") :redis/start)))
 (is (= [workflow/backend-finalize-step] (workflow/wire-fn :redis/backend-finalize (assoc (aws-fixture) :green/event :delete)))))
(deftest a-repeat-delete-continues-past-a-missing-machine
 (let [successors (fn [opts] (mapv first (workflow/next-fn :redis/load-infrastructure [:redis/ansible] opts)))]
  (testing "compute destroyed or absent: storage, then the finalizer"
   (doseq [status ["destroyed" "absent"]]
    (with-redefs [inspection/read-deployment (fn [& _] {:status status})]
     (let [r (tools/load-infrastructure-step (aws-fixture :green/event :delete))]
      (is (= 0 (:green/exit r)))
      (is (true? (:redis/already-destroyed r)))
      (is (= [:redis/storage] (successors r)))
      (is (= [:redis/backend-finalize] (successors (assoc r :redis-storage-managed false))))
      (is (= [] (successors (assoc r :redis-storage-managed false :s3-bucket-mode "external"))))))))
  (testing "an absent journal with nothing managed is still an error, not absence"
   (with-redefs [inspection/read-deployment (fn [& _] {:status "absent"})]
    (is (= 1 (:green/exit (tools/load-infrastructure-step (fixture :green/event :delete)))))
    (is (= 1 (:green/exit (tools/load-infrastructure-step (aws-optout :green/event :delete)))))))
  (testing "an unreadable managed backend routes straight to the finalizer, which decides"
   (with-redefs [inspection/read-deployment (fn [& _] {:status "error"})]
    (let [r (tools/load-infrastructure-step (aws-fixture :green/event :delete))]
     (is (= 0 (:green/exit r)))
     (is (true? (:redis/finalize-only r)))
     (is (nil? (:redis/already-destroyed r)))
     (is (= [:redis/backend-finalize] (successors r))))
    (is (= 1 (:green/exit (tools/load-infrastructure-step (aws-optout :green/event :delete)))))
    (is (= 1 (:green/exit (tools/load-infrastructure-step (aws-fixture :green/event :rehearse)))))
    (is (= 1 (:green/exit (tools/load-infrastructure-step (aws-fixture :green/event :describe)))))))
  (testing "the finalizer's outcome is the exit"
   (with-redefs [managed-backend/finalize-backend! (fn [& _] {:status "absent"})]
    (is (= 0 (:green/exit (workflow/backend-finalize-step (aws-fixture :green/event :delete))))))
   (with-redefs [managed-backend/finalize-backend! (fn [& _] {:status "refused"})]
    (is (= 1 (:green/exit (workflow/backend-finalize-step (aws-fixture :green/event :delete))))))
   (with-redefs [managed-backend/finalize-backend! (fn [& _] (throw (Exception. "live state remains")))]
    (is (= 1 (:green/exit (workflow/backend-finalize-step (aws-fixture :green/event :delete)))))))))
(deftest rehearse-reads-the-scoped-pair-back-from-storage-state
 (let [node {:node_id "0" :provider "aws" :name "redis-aws-fixture" :ip "203.0.113.8" :user "ubuntu" :sudoer "ubuntu"}
       reads (atom 0)]
  (with-redefs [inspection/read-deployment (fn [& _] {:status "present" :cluster {:nodes [node]}})
                storage/read-credentials! (fn [opts] (swap! reads inc) (assoc opts storage/credentials-key {:credentials {"backup" {"access_key_id" "k" "secret_access_key" "s"}}}))]
   (let [out (tools/load-infrastructure-step (aws-fixture :green/event :rehearse))]
    (is (= 1 @reads))
    (is (= {"COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID" "k" "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY" "s"} (storage/credential-env out))))
   (tools/load-infrastructure-step (aws-fixture :green/event :describe))
   (tools/load-infrastructure-step (aws-optout :green/event :rehearse))
   (is (= 1 @reads) "describe and an operator-owned bucket read nothing"))))
(deftest dry-runs-need-no-credential-and-render-the-storage-backend
 (doseq [[f event] [[aws-fixture :create] [aws-fixture :delete] [aws-optout :create] [aws-optout :delete]]]
  (let [r (workflow/start-step (f :green/event event :green/dry-run true :compute-prevent-destroy false) {})]
   (is (= 0 (:green/exit r)) (:green/err r))))
 (is (re-find #"COLORS_PAR_REDIS_BACKUP_R2" (:green/err (workflow/start-step (aws-optout :green/event :create) {}))) "an operator-owned bucket needs the operator's pair")
 (is (= 2 (:green/exit (workflow/start-step (aws-optout :green/event :create) {}))))
 (is (false? (:redis-storage-managed (workflow/start-step (fixture :green/event :build) {})))))
(deftest all-fixtures-native-build-through-library
 (doseq [f all-fixtures]
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory "redis-library-build-" (make-array java.nio.file.attribute.FileAttribute 0)))
        opts (f :green/event :build :workdir (.getPath directory))]
   (try
    (let [result (engine/run workflow/workflow opts)]
     (is (not (engine/failed? result)) (:green/err result))
     (is (= "192.0.2.10" (:ip result)))
     ;; AWS has no VPC-less instance, so the library gives an AWS node a
     ;; private address; nothing in this package reads it (compose, the play
     ;; and the smoke gate know only loopback). The other providers create
     ;; no network at all.
     (is (= (= "aws" (:provider-compute opts)) (some? (:vpc_ip (compute/node result)))))
     (is (= 1 (count (filter #(re-matches #"node(-none)?\.tf\.json" (.getName %)) (.listFiles (io/file (tools/tool-dir opts tools/infrastructure-tool) "nodes/0"))))))
     (is (= (storage/managed? opts) (.isFile (io/file (storage/directory opts) "main.tf"))))
     (is (= (storage/managed? opts) (.isFile (io/file (storage/directory opts) "backend.tf.json"))))
     (when (storage/managed? opts)
      (let [tf (slurp (io/file (storage/directory opts) "main.tf"))]
       (is (str/includes? tf "backup = \"redis-aws-fixture-backup\""))
       (is (str/includes? tf "prevent_destroy = true"))
       (is (not (re-find #"AKIA|secret_access_key = \"" tf))))))
    (finally (doseq [file (reverse (file-seq directory))] (.delete file)))))))
