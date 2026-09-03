(ns io.github.getcolors.redis.tools-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.redis.tools :as tools]
            [io.github.getcolors.redis.validate-test :refer [fixture optout]]))

(defn- spec-for [opts file]
  (some #(when (str/ends-with? (str (:target %)) file) %) (tools/ansible-specs opts)))

(deftest firewall-sources-parse
  (let [data (tools/infrastructure-data (fixture))]
    (is (= ["0.0.0.0/0" "::/0"] (tools/cidrs data :vultr-ssh-sources)))))

(deftest infrastructure-data-carries-the-ssh-mode
  (is (true? (:ssh-keygen (tools/infrastructure-data (fixture)))))
  (is (false? (:ssh-keygen (tools/infrastructure-data (optout))))))

(deftest infrastructure-data-resolves-the-compute-name
  ;; Compute Name Standard §3: every label derives from the one resolved name.
  (is (= "redis-fixture" (:compute-name (tools/infrastructure-data (fixture))))))

(deftest the-backup-prefix-is-namespaced-by-profile
  ;; Two deployments sharing a bucket must never share a prefix.
  (is (= "redis-fixture/redis" (tools/set-prefix (fixture)))))

(deftest tofu-outputs-keep-once-s-key-and-gain-a-kebab-vpc-ip
  (testing "ONCE reads :ssh_key_id with the underscore; :vpc-ip is added beside :vpc_ip"
    (let [p (tools/with-vpc-ip {:ip "1.1.1.1" :vpc_ip "10.60.0.3" :ssh_key_id "k"})]
      (is (= "k" (:ssh_key_id p)))
      (is (= "10.60.0.3" (:vpc_ip p)))
      (is (= "10.60.0.3" (:vpc-ip p))))))

(deftest inventory-keeps-one-target-with-its-vpc-address
  (let [inv (json/parse-string (tools/inventory (assoc (fixture) :ip "192.0.2.10" :vpc-ip "10.60.0.3")) true)
        host (get-in inv [:all :children :redis :hosts :redis-fixture])]
    (is (= "192.0.2.10" (:ansible_host host)))
    (is (= "10.60.0.3" (:vpc_ip host)))
    (is (= "root" (:ansible_user host)))))

(deftest a-build-inventory-carries-placeholders-only
  (let [inv (tools/inventory (fixture))]
    (is (str/includes? inv tools/placeholder-ip))
    (is (str/includes? inv tools/placeholder-vpc-ip))))

(deftest ansible-renders-the-whole-tree
  (let [targets (map #(str (:target %)) (tools/ansible-specs (fixture)))]
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
  (let [data (:data (spec-for (fixture) "main.yml"))]
    (is (= "redis-fixture/redis" (:redis-backup-set-prefix data)))
    (doseq [k [:redis-backup-r2-access-key-id :redis-backup-r2-secret-access-key]]
      (is (nil? (get data k)) (str k)))))

(deftest the-compose-template-reads-the-vpc-address-on-the-host
  ;; A run-time fact, resolved by Ansible from the inventory — never by Selmer.
  (let [template (slurp (io/resource "io/github/getcolors/redis/tools/ansible/compose.yml"))]
    (is (str/includes? template "{{ vpc_ip }}:<{ redis-port }>:6379"))
    (is (str/includes? template "127.0.0.1:<{ redis-port }>:6379"))))

(deftest a-delete-without-compute-skips-the-host-entirely
  ;; There is no machine to stop, and the cleanup play would only fail against
  ;; the placeholder address.
  (is (= 0 (:green/exit (tools/ansible-step (assoc (fixture) :green/event :delete))))))

(deftest acceptance-is-skipped-outside-a-real-create
  (doseq [event [:build :delete :rehearse :describe]]
    (is (= 0 (:green/exit (tools/acceptance-step (assoc (fixture) :green/event event)))))))

(deftest the-tunnel-probe-never-puts-the-password-on-a-command-line
  (let [[_ _ script] (tools/redis-args 20001 true "PING")
        [_ _ anon] (tools/redis-args 20001 false "PING")]
    (is (str/includes? script "REDISCLI_AUTH=\"$REDISCLI_AUTH\""))
    (is (str/includes? script "env -i"))
    (is (not (str/includes? anon "REDISCLI_AUTH")))
    (is (str/includes? script "-p 20001 'PING'"))))

(deftest the-tunnel-rides-the-generated-alias-and-the-configured-port
  (let [[_ _ script] (tools/tunnel-args (assoc (fixture) :redis-port 6380) 20001)]
    (is (str/includes? script "-L 20001:127.0.0.1:6380 redis-fixture"))
    (is (str/includes? script "ExitOnForwardFailure=yes"))))

(deftest the-public-port-probe-is-bounded
  (let [[_ _ script] (tools/closed-port-args "203.0.113.5" 6379)]
    (is (str/includes? script "timeout 5"))
    (is (str/includes? script "/dev/tcp/203.0.113.5/6379"))))
