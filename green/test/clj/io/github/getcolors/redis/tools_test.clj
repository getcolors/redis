(ns io.github.getcolors.redis.tools-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.redis.tools :as tools]
            [io.github.getcolors.redis.validate :as validate]
            [io.github.getcolors.redis.storage :as storage]
            [io.github.getcolors.redis.validate-test :refer [fixture optout do-fixture do-optout aws-fixture aws-optout]]))

(defn- spec-for [opts file]
  (some #(when (str/ends-with? (str (:target %)) file) %) (tools/ansible-specs opts)))

(deftest the-backup-prefix-is-namespaced-by-profile
  ;; Two deployments sharing a bucket must never share a prefix.
  (is (= "redis-fixture/redis" (tools/set-prefix (fixture :green/event :build)))))

(deftest inventory-keeps-one-target-and-no-private-address
  (let [inv (json/parse-string (tools/inventory (assoc (fixture :green/event :build) :ip "192.0.2.10")) true)
        host (get-in inv [:all :children :redis :hosts :redis-fixture])]
    (is (= "192.0.2.10" (:ansible_host host)))
    (is (= "root" (:ansible_user host)))
    (is (nil? (:vpc_ip host)))))

(deftest a-build-inventory-carries-the-placeholder-only
  (let [inv (tools/inventory (fixture :green/event :build))]
    (is (str/includes? inv tools/placeholder-ip))
    (is (not (str/includes? inv "10.60.")))))

(deftest ansible-renders-the-whole-tree
  (let [targets (map #(str (:target %)) (tools/ansible-specs (fixture :green/event :build)))]
    (doseq [f ["ansible.cfg" "main.yml" "cleanup.yml" "rehearsal.yml" "compose.yml"
               "r2-env.sh" "redis-backup.sh" "redis-restore-check.sh"
               "redis-smoke.sh" "redis-monitor.sh" "redis-status.sh" "inventory.json"]]
      (is (some #(str/ends-with? % f) targets) f))
    (is (= (count tools/ansible-files) (count (distinct tools/ansible-files))))))

(deftest operator-secrets-reach-the-host-as-lookups-not-values
  ;; `.colors/` is generated output and the goldens are committed, so the
  ;; secret must never be the thing that lands on disk — the expression is.
  (let [template (slurp (io/resource "io/github/getcolors/redis/tools/ansible/main.yml"))]
    (doseq [par ["COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID"
                 "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY"]]
      (is (str/includes? template (str "lookup('env','" par "')")) par))))

(deftest the-data-map-carries-no-operator-secret
  (let [data (:data (spec-for (fixture :green/event :build) "main.yml"))]
    (is (= "redis-fixture/redis" (:redis-backup-set-prefix data)))
    (doseq [k [:redis-backup-r2-access-key-id :redis-backup-r2-secret-access-key]]
      (is (nil? (get data k)) (str k))))
  (testing "nor the managed storage output"
    (let [opts (assoc (aws-fixture :green/event :build) storage/credentials-key {:credentials {"backup" {"access_key_id" "AKIA" "secret_access_key" "s"}}})]
      (is (nil? (get (:data (spec-for opts "main.yml")) storage/credentials-key)))
      (is (nil? (get (tools/ansible-local-data opts) storage/credentials-key))))))

(deftest the-play-environment-carries-the-scoped-pair-only-when-managed
  (let [credentials {:credentials {"backup" {"access_key_id" "AKIA" "secret_access_key" "s"}}}]
    (is (= {"ANSIBLE_HOST_KEY_CHECKING" "False"} (tools/play-env (fixture) true)))
    (is (= {"ANSIBLE_HOST_KEY_CHECKING" "False"} (tools/play-env (aws-optout) true)))
    (is (= {"ANSIBLE_HOST_KEY_CHECKING" "False"} (tools/play-env (assoc (aws-fixture) storage/credentials-key credentials) false)))
    (is (= {"ANSIBLE_HOST_KEY_CHECKING" "False"
            "COLORS_PAR_REDIS_BACKUP_R2_ACCESS_KEY_ID" "AKIA"
            "COLORS_PAR_REDIS_BACKUP_R2_SECRET_ACCESS_KEY" "s"}
           (tools/play-env (assoc (aws-fixture) storage/credentials-key credentials) true)))
    (is (thrown? Exception (tools/play-env (aws-fixture) true)) "managed without an output is refused, never an empty variable")))

(defn- temp-workdir []
  (str (java.nio.file.Files/createTempDirectory "redis-test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest the-play-runner-mirrors-the-sdk-step
  (let [runs (atom [])
        workdir (temp-workdir)
        runner (fn [exit out] (fn [args opts _] (swap! runs conj [args (:extra-env opts)]) {:exit exit :out out :err ""}))
        recap "PLAY RECAP\nredis-fixture : ok=3 changed=1 unreachable=0 failed=0 skipped=0 rescued=0 ignored=0\n"]
    (testing "a build renders and runs nothing"
      (with-redefs [green.process/run-with-timeout (runner 0 recap)]
        (is (= 0 (:green/exit (tools/run-play (fixture :green/event :build :workdir workdir) "main.yml" true))))
        (is (empty? @runs))))
    (testing "a create runs the play with host-key checking off and parses the recap"
      (with-redefs [green.process/run-with-timeout (runner 0 recap)]
        (let [r (tools/run-play (fixture :green/event :create :green/dry-run true :ip "192.0.2.10" :workdir workdir) "main.yml" true)]
          (is (= 0 (:green/exit r)))
          (is (= {"redis-fixture" {:ok 3 :changed 1 :unreachable 0 :failed 0 :skipped 0 :rescued 0 :ignored 0}} (:ansible/recap r)))
          (is (= [["ansible-playbook" "-i" "inventory.json" "main.yml"] {"ANSIBLE_HOST_KEY_CHECKING" "False"}] (last @runs))))))
    (testing "a failure carries the play's output"
      (with-redefs [green.process/run-with-timeout (runner 2 "fatal: unreachable")]
        (let [r (tools/run-play (fixture :green/event :create :green/dry-run true :ip "192.0.2.10" :workdir workdir) "main.yml" true)]
          (is (= 2 (:green/exit r)))
          (is (str/includes? (:green/err r) "ansible-playbook main.yml failed: fatal: unreachable")))))))

(deftest the-compose-file-publishes-on-loopback-alone
  ;; Exposure is decided by what Compose publishes: one binding, loopback.
  (let [template (slurp (io/resource "io/github/getcolors/redis/tools/ansible/compose.yml"))
        bindings (re-seq #"\"[^\"]*:<\{ redis-port \}>:6379\"" template)]
    (is (= ["\"127.0.0.1:<{ redis-port }>:6379\""] bindings))
    (is (not (str/includes? template "vpc")))))

(deftest the-play-and-the-smoke-gate-know-no-private-address
  (let [play (slurp (io/resource "io/github/getcolors/redis/tools/ansible/main.yml"))
        smoke (slurp (io/resource "io/github/getcolors/redis/tools/ansible/redis-smoke.sh"))]
    (is (str/includes? play "redis-smoke {{ ansible_host }}"))
    (is (not (str/includes? play "vpc")))
    (is (str/includes? smoke "expected=\"127.0.0.1:$port\""))
    (is (not (str/includes? smoke "vpc")))))

(deftest a-delete-without-compute-skips-the-host-entirely
  ;; There is no machine to stop, and the cleanup play would only fail against
  ;; the placeholder address.
  (is (= 0 (:green/exit (tools/ansible-step (assoc (fixture :green/event :build) :green/event :delete))))))

(deftest acceptance-is-skipped-outside-a-real-create
  (doseq [event [:build :delete :rehearse :describe]]
    (is (= 0 (:green/exit (tools/acceptance-step (assoc (fixture :green/event :build) :green/event event)))))))

(deftest the-tunnel-probe-never-puts-the-password-on-a-command-line
  (let [[_ _ script] (tools/redis-args 20001 true "PING")
        [_ _ anon] (tools/redis-args 20001 false "PING")]
    (is (str/includes? script "REDISCLI_AUTH=\"$REDISCLI_AUTH\""))
    (is (str/includes? script "env -i"))
    (is (not (str/includes? anon "REDISCLI_AUTH")))
    (is (str/includes? script "-p 20001 'PING'"))))

(deftest the-tunnel-rides-the-generated-alias-and-the-configured-port
  (let [[_ _ script] (tools/tunnel-args (assoc (fixture :green/event :build) :redis-port 6380) 20001)]
    (is (str/includes? script "-L 20001:127.0.0.1:6380 redis-fixture"))
    (is (str/includes? script "ExitOnForwardFailure=yes"))))

(deftest the-public-port-probe-is-bounded
  (let [[_ _ script] (tools/closed-port-args "203.0.113.5" 6379)]
    (is (str/includes? script "timeout 5"))
    (is (str/includes? script "/dev/tcp/203.0.113.5/6379"))))

(deftest local-play-receives-its-required-node-fields
  (with-redefs [green.ansible/ansible-with-spec
                (fn [opts config _]
                  (is (= [{:name "redis-fixture" :ip "192.0.2.10" :user "root"}]
                         (get-in config [:extra-vars :ssh_hosts]))) opts)]
    (tools/ansible-local-step (fixture :green/event :build))))

(deftest compute-json-accepts-library-mixed-key-maps
  (is (= {"backups" true "region" "ams"}
         (cheshire.core/parse-string
          (#'io.github.getcolors.redis.tools/compute-json {:region "ams" "backups" true} 0)))))

(deftest the-password-is-read-as-root-whoever-the-alias-logs-in-as
  ;; root on the Vultr and DigitalOcean images, ubuntu on the AWS AMI: the
  ;; plain read serves the first, the passwordless-sudo fallback the second.
  (let [seen (atom nil)]
    (with-redefs [tools/run-quiet (fn [args _ _] (reset! seen args) {:exit 0 :out "generated\n" :err ""})]
      (is (= "generated" (tools/read-remote-password (aws-fixture))))
      (let [[ssh _ _ alias command] @seen]
        (is (= "ssh" ssh))
        (is (= "redis-aws-fixture" alias))
        (is (str/starts-with? command "cat /etc/redis/secrets/password"))
        (is (str/includes? command "|| sudo -n cat /etc/redis/secrets/password"))))
    (testing "a failed read is nil, never a partial reply"
      (with-redefs [tools/run-quiet (fn [& _] {:exit 1 :out "" :err "Permission denied"})]
        (is (nil? (tools/read-remote-password (aws-fixture))))))))
