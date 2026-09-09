(ns io.github.getcolors.redis.tools
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [green.ansible :as ansible]
            [green.cli :as green-cli]
            [green.process :as process]
            [green.scaffold :as sc]
                        [green.workflow :as wf]
            [io.github.getcolors.redis.compute :as compute]
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-orchestration :as orchestration]
            [io.github.getcolors.compute-inspection :as inspection]
            [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.redis.ssh-config :as ssh-config]
            [io.github.getcolors.redis.validate :as validate]))

(def infrastructure-tool "redis-infrastructure")
(def ansible-tool "redis-ansible")
(def ansible-local-tool "redis-ansible-local")
(def root "io.github.getcolors.redis.tools")
(def template-opts sc/preserve-jinja-delimiters)

(defn tool-dir [opts tool] (green-cli/stage-dir opts tool {:default-profile "redis"}))
(defn template [path file] (keyword (str root "." path) file))
(defn spec [source target data] {:template source :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(def placeholder-ip "192.0.2.10")

(defn set-prefix [opts] (str (:profile opts) "/redis"))

;; ---------------------------------------------------------------- compute

(defn- compute-json [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                      (str "{\n" (str/join ",\n" (for [[key item] (sort-by key value)]
                                                       (str (padding (+ indent 2)) (json/generate-string key) ": " (compute-json item (+ indent 2)))))
                           "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))

(defn infrastructure-step [opts]
  (try
    (let [planning? (or (= :build (:green/event opts)) (:green/dry-run opts))
          result (if planning?
                   (planning/plan-deployment opts (compute/topology opts) (compute/requirements opts))
                   (orchestration/orchestrate opts (compute/topology opts) (compute/requirements opts)))]
      (when planning?
        (doseq [[stage key] (cons ["shared" (get-in result [:state_keys :shared])]
                                 (map (fn [[id key]] [(str "nodes/" (name id)) key]) (get-in result [:state_keys :nodes]))) ]
          (let [target (io/file (tool-dir opts infrastructure-tool) stage "backend.tf.json")]
            (io/make-parents target)
            (spit target (str (compute-json (:config (library/backend-plan opts key)) 0) "\n"))))
        (doseq [[stage documents] (cons ["shared" (get-in result [:documents :shared])]
                                      (map (fn [[id documents]] [(str "nodes/" id) documents]) (get-in result [:documents :nodes])))
                [filename document] documents]
          (let [target (io/file (tool-dir opts infrastructure-tool) stage filename)]
            (io/make-parents target)
            (spit target (str (compute-json document 0) "\n")))))
      (if-not (contains? #{"ready" "planned" "destroyed"} (:status result))
        (assoc opts :green/exit 1 :green/err (if (seq (:errors result)) (str/join "\n" (:errors result)) "compute lifecycle refused; inspect state ownership and configuration"))
        (cond-> (assoc opts :green/exit 0)
          (:shared result) (assoc :colors-compute/shared (:shared result))
          (:cluster result) (assoc :colors-compute/cluster (:cluster result) :ip (get-in result [:cluster :nodes 0 :ip]) :user (get-in result [:cluster :nodes 0 :user]))
          (get-in result [:key :private_key_path])
          (assoc :ssh-private-key-path (if planning? (str/replace (get-in result [:key :private_key_path]) "$HOME/.ssh" "/home/build-placeholder/.ssh") (get-in result [:key :private_key_path]))))))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute lifecycle refused; legacy monolithic state requires explicit migration"))))

(defn load-infrastructure-step [opts]
  (try
    (let [result (inspection/read-deployment opts (into {} (System/getenv)) {} (compute/requirements opts))]
      (case (:status result)
        "present" (let [node (first (get-in result [:cluster :nodes]))]
                    (cond-> (assoc opts :colors-compute/cluster (:cluster result)
                                       :colors-compute/shared (:shared result)
                                       :ip (:ip node) :user (:user node) :green/exit 0)
                      (:ssh_identity_file node) (assoc :ssh-private-key-path (:ssh_identity_file node))))
        "destroyed" (if (= :delete (:green/event opts)) (assoc opts :redis/already-destroyed true :green/exit 0)
                        (assoc opts :green/exit 1 :green/err "compute deployment is destroyed"))
        (assoc opts :green/exit 1 :green/err "compute inspection refused; existing owned state is required")))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute inspection refused; existing owned state is required"))))

;; ---------------------------------------------------------- ansible (local)

(defn ansible-local-data
  "Only what a `build` genuinely knows. The address, the user and the alias are
  run-time facts and reach the play as extra-vars instead, so the rendered
  playbook carries no IP and is identical on every workstation (SSH Config
  Standard §6)."
  [opts]
  (assoc opts
         :ssh-keygen (validate/keygen? opts)
         :ssh-config-identity-file (ssh-config/identity-file opts)))

(defn ansible-local-specs [opts]
  (let [dir (tool-dir opts ansible-local-tool) data (ansible-local-data opts)]
    [(spec (template "ansible-local" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible-local" "inventory.ini") (str dir "/inventory.ini") data)
     (spec (template "ansible-local" "main.yml") (str dir "/main.yml") data)]))

(defn ansible-local-step
  "Write or remove the `~/.ssh/config` block. The same playbook serves both
  events; `block_state` is what distinguishes them."
  [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        delete? (= :delete (:green/event opts))]
    (ansible/ansible-with-spec opts
      {:dir dir :inventory "inventory.ini"
       :playbooks {:create "main.yml" :delete "main.yml"}
       :extra-vars {:host_alias (ssh-config/host-alias opts)
                    :ssh_hosts [(select-keys (assoc (compute/node opts) :name (ssh-config/host-alias opts)) [:name :ip :user])]
                    :block_state (if delete? "absent" "present")}}
      (ansible-local-specs opts))))

;; ---------------------------------------------------------------- ansible

(defn inventory [opts]
  (let [node (compute/node opts) identity (or (:ssh-private-key-path opts) (:ssh_identity_file node))]
    (json/generate-string
     {:all {:children {:redis {:hosts {(:profile opts)
       (cond-> {:ansible_host (:ip node) :ansible_user (:user node)}
         identity (assoc :ansible_ssh_private_key_file identity))}}}}} {:pretty true})))

(defn ansible-data
  "Template values for the Ansible stage.

  Deliberately carries no operator secret. The backup pair reaches the host as
  Ansible `lookup('env', ...)` expressions written literally into main.yml,
  where `preserve-jinja-delimiters` passes them through untouched — routing
  them through this map instead would let Selmer HTML-escape the quotes and
  hand Ansible `&#39;`. The secret therefore exists only in the process that
  needs it: not in `.colors/`, not in a golden, not in this map."
  [opts]
  (assoc opts
         :ip (:ip (compute/node opts))
         :ssh-keygen (validate/keygen? opts)
         :redis-backup-set-prefix (set-prefix opts)))

(def ansible-files
  ["ansible.cfg" "main.yml" "cleanup.yml" "rehearsal.yml" "compose.yml"
   "r2-env.sh" "redis-backup.sh" "redis-restore-check.sh"
   "redis-smoke.sh" "redis-monitor.sh" "redis-status.sh"])

(defn ansible-specs [opts]
  (let [dir (tool-dir opts ansible-tool) data (ansible-data opts)]
    (conj (mapv (fn [f] (spec (template "ansible" f) (str dir "/" f) data)) ansible-files)
          (raw-spec (str dir "/inventory.json") (inventory data)))))

(defn ansible-step [opts]
  (let [dir (tool-dir opts ansible-tool)]
    (if (and (= :delete (:green/event opts)) (not (:ip opts)))
      ;; No compute in state: there is no host to stop, and the cleanup play
      ;; would only fail against the placeholder address.
      (assoc opts :green/exit 0)
      (ansible/ansible-with-spec opts
        {:dir dir :inventory "inventory.json"
         :playbooks {:create "main.yml" :delete "cleanup.yml"}
         :host-key-checking false}
        (ansible-specs opts)))))

(defn rehearsal-step
  "The recovery rehearsal: a fresh backup set, its restore into a scratch
  instance of the pinned image, the smoke key read back from the restored
  data, and only then the recovery marker. Runs the same rendered tree as the
  converge."
  [opts]
  (let [dir (tool-dir opts ansible-tool)]
    (ansible/ansible-with-spec opts
      {:dir dir :inventory "inventory.json"
       :playbooks {:create "rehearsal.yml"}
       :host-key-checking false}
      (ansible-specs opts))))

;; ------------------------------------------------------------- acceptance

(defn run-quiet
  "Run `args` with `env` overlaid, returning the result map. Nothing from the
  child is echoed; callers decide what becomes an error message, so a secret
  passed through `env` can never leak into output by default."
  [args env timeout-ms]
  (process/run-with-timeout args (if (seq env) {:extra-env env} {}) timeout-ms))

(defn redis-args
  "A redis-cli invocation against a local port with an explicit everything.
  `env -i` clears the environment and re-admits only PATH and — when `auth?` —
  the password handed over through the runner as REDISCLI_AUTH, so no
  ambient variable can alter what the probe proves and the password never
  appears on a command line. Error replies are text on stdout, not exit
  codes, so callers grep the reply."
  [port auth? & cmd]
  ["bash" "-c"
   (str "exec env -i PATH=\"$PATH\""
        (when auth? " REDISCLI_AUTH=\"$REDISCLI_AUTH\"")
        " redis-cli --no-auth-warning -h 127.0.0.1 -p " port " "
        (str/join " " (map process/posix-quote cmd)))])

(defn tunnel-args
  "An ssh tunnel through the generated `~/.ssh/config` alias — the supported
  client path, exercised end to end: the alias, the identity file, and the
  forward. `-f` returns once the forward is up; the remote `sleep` bounds its
  lifetime so nothing needs killing on the way out. The bash wrapper exists
  for the streams: the daemonized child inherits stdout/stderr, and a runner
  that waits for the pipes to close would otherwise block until the sleep
  expires — returning exactly when the tunnel dies."
  [opts port]
  ["bash" "-c"
   (str "ssh -f -o ExitOnForwardFailure=yes -o BatchMode=yes"
        " -L " port ":127.0.0.1:" (:redis-port opts) " "
        (ssh-config/host-alias opts) " sleep 45 >/dev/null 2>&1")])

(defn closed-port-args
  "A TCP connect to the machine's public address on the Redis port, bounded
  by a timeout. It must FAIL: the port is bound to loopback only and the
  firewall admits 22 alone."
  [ip port]
  ["bash" "-c" (str "timeout 5 bash -c 'exec 3<>/dev/tcp/" ip "/" port "'")])

(defn read-remote-password
  "The generated Redis password, read over SSH and held only in this process.
  Never merged into opts, never printed."
  [opts]
  (let [r (run-quiet ["ssh" "-o" "BatchMode=yes" (ssh-config/host-alias opts)
                      "cat" "/etc/redis/secrets/password"]
                     {} 20000)]
    (when (zero? (:exit r)) (str/trim (str (:out r))))))

(defn reply [r] (str/trim (str (:out r) (:err r))))

(defn acceptance-step
  "The operator-path gate, after a real create.

  The server-side gates already ran inside the playbook (the round-trip, the
  configuration, the auth negatives, the bind addresses, persistence across
  a restart, the first backup set). What is checked from here is what only
  this side can check: that an operator on this workstation reaches Redis
  through the generated SSH config and a tunnel with the generated password
  and not without it — and that the public address does not answer on the
  Redis port at all."
  [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [pw (read-remote-password opts)
          ip (:ip opts)
          public (run-quiet (closed-port-args ip (:redis-port opts)) {} 15000)]
      (cond
        (not (seq pw))
        (assoc opts :green/exit 1
               :green/err "acceptance: could not read the generated Redis password over ssh")

        (zero? (:exit public))
        (assoc opts :green/exit 1
               :green/err (str "acceptance: " ip ":" (:redis-port opts)
                               " accepted a connection from the internet; the port must not be public"))

        :else
        (loop [ports (take 3 (repeatedly #(+ 20000 (rand-int 40000))))]
          (if-let [port (first ports)]
            (let [tunnel (run-quiet (tunnel-args opts port) {} 30000)]
              (if-not (zero? (:exit tunnel))
                (recur (rest ports))
                (let [stamp (str "operator-" (System/currentTimeMillis))
                      set-r (run-quiet (redis-args port true "SET" "colors:operator" stamp)
                                       {"REDISCLI_AUTH" pw} 30000)
                      get-r (run-quiet (redis-args port true "GET" "colors:operator")
                                       {"REDISCLI_AUTH" pw} 30000)
                      anon (run-quiet (redis-args port false "PING") {} 30000)
                      wrong (run-quiet (redis-args port true "PING")
                                       {"REDISCLI_AUTH" "not-the-password"} 30000)]
                  (cond
                    (not= "OK" (reply set-r))
                    (assoc opts :green/exit 1
                           :green/err (str "acceptance: SET through the tunnel answered '"
                                           (reply set-r) "', expected OK"))

                    (not= stamp (reply get-r))
                    (assoc opts :green/exit 1
                           :green/err (str "acceptance: GET through the tunnel answered '"
                                           (reply get-r) "', expected " stamp))

                    (not (str/includes? (reply anon) "NOAUTH"))
                    (assoc opts :green/exit 1
                           :green/err (str "acceptance: an unauthenticated PING answered '"
                                           (reply anon) "' instead of NOAUTH"))

                    (or (str/includes? (reply wrong) "PONG")
                        (not (re-find #"WRONGPASS|NOAUTH" (reply wrong))))
                    (assoc opts :green/exit 1
                           :green/err (str "acceptance: a wrong password answered '"
                                           (reply wrong) "' instead of a refusal"))

                    :else
                    (assoc opts :green/exit 0
                           :redis/acceptance {:tunnel "ok" :round-trip stamp
                                              :unauthenticated "refused"
                                              :wrong-password "refused"
                                              :public-port "closed"})))))
            (assoc opts :green/exit 1
                   :green/err "acceptance: no local port could carry the ssh tunnel after three attempts")))))))

;; --------------------------------------------------------------- describe

(def monitor-file "/var/lib/colors/redis-monitor.json")

(defn describe-step
  "Read the host's last monitor result over SSH and print it. Exits non-zero
  when the host is unreachable or reports unhealthy; this is what an external
  poller runs."
  [opts]
  (let [alias (ssh-config/host-alias opts)
        r (run-quiet ["ssh" "-o" "BatchMode=yes" alias "cat" monitor-file] {} 20000)
        parsed (try (json/parse-string (str/trim (str (:out r))) true) (catch Exception _ nil))
        reachable (zero? (:exit r))
        healthy (boolean (:healthy parsed))
        problems (or (:problems parsed) (when-not reachable ["unreachable or no monitor result yet"]))]
    (println (format "%-32s %-10s %s" alias
                     (cond (not reachable) "UNKNOWN" healthy "ok" :else "UNHEALTHY")
                     (str (or (:checked parsed) "")
                          (when (seq problems) (str " " (str/join "; " problems))))))
    (assoc opts :green/exit (if (and reachable healthy) 0 1)
           :redis/describe {:host alias :reachable reachable :healthy healthy
                            :checked (:checked parsed) :problems problems})))
