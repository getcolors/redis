(ns io.github.getcolors.redis.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.redis.ssh :as ssh]
            [io.github.getcolors.redis.ssh-config :as ssh-config]
            [io.github.getcolors.redis.tools :as tools]
            [io.github.getcolors.redis.validate :as validate]))

(def defaults {:provider-compute "vultr"
               :provider-backend "local" :compute-prevent-destroy true
               :workdir ".colors"})

(defn state-output
  "The compute stage's applied `params`, or nil when no state is readable. The
  create matrix keys on this best-effort read: an unreadable state (a fresh
  clone, a missing backend) counts as absent.

  Keywordized but otherwise UNTOUCHED: ONCE's create matrix reads `:ssh_key_id`
  with the underscore from this map, and a renamed key reads as a key this
  deployment does not own — the standard's never-adopt rule then refuses the
  deployment's own key. `:vpc-ip` is added beside `:vpc_ip`, never instead."
  [opts]
  (try (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                             (tools/backend-credential-env opts))
               :params walk/keywordize-keys tools/with-vpc-ip)
       (catch Exception _ nil)))

(defn- with-state
  "Events that run against the existing machine (delete, rehearse, describe)
  take its addresses from state rather than from a fresh apply."
  [opts]
  (merge opts (or (state-output opts) {})))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (contains? #{:create :delete} event))
               (validate/secret-errors opts event)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
          :after-validate
          ;; The machine key's create matrix and the Vultr preflight run before
          ;; any template is rendered: an unowned key on disk or at the provider
          ;; stops the run while stopping is still free. Delete fills the same
          ;; template values — a destroy renders before it destroys — but checks
          ;; nothing, because its key cleanup runs after the compute destroy.
          (fn [opts _ {:keys [event real?]}]
            (cond
              (and real? (= :delete event))
              (merge (with-state (ssh/with-machine-key opts)) {:green/exit 0})

              (and real? (contains? #{:rehearse :describe} event))
              (let [opts (with-state (ssh/with-machine-key opts))]
                (if-not (:ip opts)
                  (assoc opts :green/exit 1
                         :green/err (str (name event) ": no compute in state; run create first"))
                  (assoc opts :green/exit 0)))

              (and real? (= :create event))
              (let [opts (ssh/ensure-key! opts state-output)]
                (if (wf/failed? opts)
                  opts
                  (let [opts (ssh/preflight! (ssh/with-machine-key opts))
                        opts (if (wf/failed? opts) opts (ssh-config/preflight! opts))]
                    (if (wf/failed? opts) opts (assoc opts :green/exit 0)))))

              :else
              (assoc (ssh/with-machine-key opts) :green/exit 0)))} env)))

(defn wire-fn [step run-opts]
  (case (:green/event run-opts)
    :delete
    (case step
      :redis/start [start-step :redis/ansible]
      :redis/ansible [tools/ansible-step :redis/ssh-config]
      ;; The `~/.ssh/config` block goes before the destroy, the opposite of the
      ;; keypair below. A block that outlives its host is stale but harmless; a
      ;; key that predeceases its host locks the operator out of a machine that
      ;; still exists. Both orders are deliberate; see standards/ssh-config.md.
      :redis/ssh-config [tools/ansible-local-step :redis/infrastructure]
      :redis/infrastructure [tools/infrastructure-step :redis/ssh-cleanup]
      :redis/ssh-cleanup [ssh/cleanup-step])

    :rehearse
    (case step
      :redis/start [start-step :redis/rehearsal]
      :redis/rehearsal [tools/rehearsal-step])

    :describe
    (case step
      :redis/start [start-step :redis/describe]
      :redis/describe [tools/describe-step])

    (case step
      :redis/start [start-step :redis/infrastructure]
      ;; After compute, which is where the address first exists, and before the
      ;; stage that converges the machine — the converge and the acceptance
      ;; both ride the alias this stage writes.
      :redis/infrastructure [tools/infrastructure-step :redis/ssh-config]
      :redis/ssh-config [tools/ansible-local-step :redis/ansible]
      :redis/ansible [tools/ansible-step :redis/acceptance]
      :redis/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting
  [:redis/infrastructure :redis/ssh-config
   :redis/ansible :redis/acceptance :redis/ssh-cleanup
   :redis/rehearsal :redis/describe])

(def workflow
  (-> (wf/workflow {:start :redis/start :wire-fn wire-fn})
      (wf/advice-add :redis/infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      progress/advise
      (dry-run/advise side-effecting)))
