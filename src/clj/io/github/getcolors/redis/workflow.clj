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

(def defaults {:provider-compute validate/default-compute-provider
               :provider-backend "local" :compute-prevent-destroy true
               :workdir ".colors"})

(defn state-output
  "Compute params recorded in the infrastructure state; nil when the state
  holds none. An unreadable backend throws — `read-state` is where the two are
  told apart, because create and delete treat them differently.

  Keywordized but otherwise UNTOUCHED: ONCE's create matrix reads `:ssh_key_id`
  with the underscore from this map, and a renamed key reads as a key this
  deployment does not own — the standard's never-adopt rule then refuses the
  deployment's own key."
  [opts]
  (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                        (tools/backend-credential-env opts))
          :params walk/keywordize-keys))

(defn read-state
  "One read of the compute state per run, shaped so a caller can tell nothing
  recorded from nothing readable: `{:params m}` where `m` may be nil, or
  `{:error message}`. Needs backend credentials only."
  [opts]
  (try {:params (state-output opts)}
       (catch Exception e {:error (ex-message e)})))

(defn lifecycle-event?
  "A real create or delete: the two events that touch a provider."
  [{:keys [event real?]}]
  (and real? (contains? #{:create :delete} event)))

(defn provider-validator
  "Compute Provider Standard §4 before the credentials. The recorded provider
  is compared with the selected one first, so a mistaken provider edit reports
  the actionable error — put it back and delete — rather than a missing token
  for the provider that was just selected; validators aggregate, which is why
  a mismatch pre-empts the secrets check rather than sitting beside it. On a
  create an unreadable backend counts as no state (a fresh clone has none) and
  the credentials are checked as usual; on a delete `adopt-state` refuses it
  after validation."
  [opts {:keys [event]} {:keys [params]}]
  (let [mismatch (validate/provider-state-errors opts params)]
    (if (seq mismatch) mismatch (validate/secret-errors opts event))))

(defn adopt-state
  "Events that run against the existing machine take its address from state
  rather than from a fresh apply. A readable state without compute params
  leaves :ip unset — a delete's cleanup step then skips itself, and rehearse
  and describe refuse below — while an unreadable backend on a delete fails
  loudly: swallowing it is how a live teardown elsewhere ended up converging
  against 192.0.2.10 (§4)."
  [opts event {:keys [params error]}]
  (if error
    (assoc opts :green/exit 1
           :green/err (str "could not read the infrastructure state for "
                           (if (= :delete event) "the delete cleanup" (name event)) ": " error "\n"
                           "fix the backend credentials and retry; a " (name event)
                           " that cannot see its state has nothing to address"))
    (merge (ssh/with-machine-key opts) params {:green/exit 0})))

(defn after-validate
  "The lifecycle transition table, once the validators have passed.

  build and dry-run only render: `with-machine-key` fills the placeholder key
  paths and nothing under `~/.ssh` or `~/.ssh/config` is read. A real create
  runs the keypair's create matrix against the one state read, then the
  provider's account-key preflight, before any template is rendered — an
  unowned key on disk or at the provider stops the run while stopping is still
  free — then the `~/.ssh/config` ownership and placement checks. A real delete
  fills the same template values (a destroy renders before it destroys) and
  adopts the machine address from the same read, fail-closed; it checks no
  key, because its cleanup runs after the destroy. Rehearse and describe need
  a machine in state and say so when there is none."
  [opts {:keys [event real?]} state]
  (cond
    (and real? (= :delete event))
    (adopt-state opts event state)

    (and real? (contains? #{:rehearse :describe} event))
    (let [opts (adopt-state opts event state)]
      (cond
        (wf/failed? opts) opts
        (not (:ip opts)) (assoc opts :green/exit 1
                                :green/err (str (name event) ": no compute in state; run create first"))
        :else opts))

    (and real? (= :create event))
    (let [opts (ssh/ensure-key! opts (fn [_] (:params state)))]
      (if (wf/failed? opts)
        opts
        (let [opts (ssh/preflight! (ssh/with-machine-key opts))
              opts (if (wf/failed? opts) opts (ssh-config/preflight! opts))]
          (if (wf/failed? opts) opts (assoc opts :green/exit 0)))))

    :else
    (assoc (ssh/with-machine-key opts) :green/exit 0)))

(defn- state-event?
  "Every real event that reads the recorded compute output up front."
  [{:keys [event real?]}]
  (and real? (contains? #{:create :delete :rehearse :describe} event)))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   ;; The state is read once, up front, on the same defaulted and overlaid
   ;; opts the validators see — the overlay is what carries the backend
   ;; credentials — and only for the events that need it. The validator and
   ;; the after-validate share the one read.
   (let [overlaid (green-cli/read-pars (merge defaults opts) env)
         context {:event (:green/event overlaid) :real? (lifecycle/real-run? overlaid)}
         state (when (state-event? context) (read-state overlaid))]
     (lifecycle/preflight
      opts {:defaults defaults :overlay green-cli/read-pars
            :validators
            [(fn [_ env _] (validate/env-errors env))
             (fn [opts _ _] (validate/state-errors opts))
             (fn [opts _ ctx]
               (when (lifecycle-event? ctx) (provider-validator opts ctx state)))
             (fn [opts _ {:keys [event real?]}]
               (when (and real? (= :delete event) (:compute-prevent-destroy opts))
                 [(str "compute destruction is protected; set "
                       (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
            :after-validate (fn [opts _ ctx] (after-validate opts ctx state))} env))))

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
