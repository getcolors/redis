(ns io.github.getcolors.redis.workflow
  (:require [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.compute-managed-backend :as managed-backend]
            [io.github.getcolors.redis.ssh :as ssh]
            [io.github.getcolors.redis.ssh-config :as ssh-config]
            [io.github.getcolors.redis.storage :as storage]
            [io.github.getcolors.redis.tools :as tools]
            [io.github.getcolors.redis.validate :as validate]))
(def defaults {:provider-compute validate/default-compute-provider
               :provider-backend "r2" :compute-prevent-destroy true
               :redis-storage-managed false :workdir ".colors"})
(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators [(fn [_ env _] (validate/env-errors env))
                       (fn [opts _ _] (validate/state-errors opts))
                       (fn [opts _ {:keys [event real?]}] (when real? (validate/secret-errors opts event)))
                       (fn [opts _ {:keys [event real?]}]
                         (when (and real? (= :delete event) (:compute-prevent-destroy opts))
                           ["compute destruction is protected; set COLORS_PAR_COMPUTE_PREVENT_DESTROY=false to delete"]))]
          :after-validate (fn [opts _ {:keys [event real?]}]
                            (if (and real? (= :create event)) (ssh-config/preflight! opts)
                                (assoc (if real? opts (ssh/with-machine-key opts)) :green/exit 0)))} env)))

(defn backend-finalize-step
  "Delete the managed S3 state bucket after everything in it has been
  destroyed. The library proves the bucket holds nothing but retired state
  before it removes anything; a refusal is an error, never a skipped step."
  [opts]
  (try
    (let [result (managed-backend/finalize-backend! opts (tools/environment opts))]
      (if (contains? #{"destroyed" "absent" "skipped"} (:status result))
        (assoc opts :green/exit 0)
        (assoc opts :green/exit 1 :green/err "managed backend finalization refused")))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "managed backend finalization refused; live or unowned state remains"))))

(defn wire-fn
  "The DAG. Create: compute, then the managed bucket the host will write to,
  then the alias, the converge and the acceptance. Delete is the reverse with
  one deliberate exception: the bucket outlives the machine the way the
  keypair does, so the last backup timer run never fails against a missing
  bucket, and the managed state bucket goes last of all."
  [step opts]
  (let [managed-storage? (storage/managed? opts)
        managed-backend? (tools/managed-backend? opts)]
    (case (:green/event opts)
      :delete (case step
                :redis/start [start-step :redis/load-infrastructure]
                :redis/load-infrastructure [tools/load-infrastructure-step :redis/ansible]
                :redis/ansible [tools/ansible-step :redis/ssh-config]
                :redis/ssh-config [tools/ansible-local-step :redis/infrastructure]
                :redis/infrastructure (cond managed-storage? [tools/infrastructure-step :redis/storage]
                                            managed-backend? [tools/infrastructure-step :redis/backend-finalize]
                                            :else [tools/infrastructure-step])
                :redis/storage (cond-> [storage/step] managed-backend? (conj :redis/backend-finalize))
                :redis/backend-finalize [backend-finalize-step])
      :rehearse (case step
                  :redis/start [start-step :redis/load-infrastructure]
                  :redis/load-infrastructure [tools/load-infrastructure-step :redis/rehearsal]
                  :redis/rehearsal [tools/rehearsal-step])
      :describe (case step
                  :redis/start [start-step :redis/load-infrastructure]
                  :redis/load-infrastructure [tools/load-infrastructure-step :redis/describe]
                  :redis/describe [tools/describe-step])
      (case step
        :redis/start [start-step :redis/infrastructure]
        :redis/infrastructure [tools/infrastructure-step (if managed-storage? :redis/storage :redis/ssh-config)]
        :redis/storage [storage/step :redis/ssh-config]
        :redis/ssh-config [tools/ansible-local-step :redis/ansible]
        :redis/ansible [tools/ansible-step :redis/acceptance]
        :redis/acceptance [tools/acceptance-step]))))

(defn next-fn
  "Successors, with the two repeat-delete routes out of the inspection step:
  a finalized backend goes straight to the finalizer; a destroyed or absent
  machine skips the host and the compute destroy and continues with whatever
  managed stages the deployment has, or stops when it has none."
  [step successors opts]
  (cond
    (wf/failed? opts) []
    (and (= step :redis/load-infrastructure) (:redis/finalize-only opts)) [[:redis/backend-finalize opts]]
    (and (= step :redis/load-infrastructure) (:redis/already-destroyed opts))
    (cond (storage/managed? opts) [[:redis/storage opts]]
          (tools/managed-backend? opts) [[:redis/backend-finalize opts]]
          :else [])
    :else (mapv #(vector % opts) successors)))

(def storage-backend-advice
  (tofu/conventional-backend-advice
   {:dir-fn storage/directory
    :key-fn #(str (:profile %) "/" storage/tool ".tfstate")}))

(def side-effecting [:redis/load-infrastructure :redis/infrastructure :redis/storage :redis/ssh-config
                    :redis/ansible :redis/acceptance :redis/rehearsal :redis/describe
                    :redis/backend-finalize])
(def workflow
  (-> (wf/workflow {:start :redis/start :wire-fn wire-fn :next-fn next-fn})
      (wf/advice-add :redis/storage :before ::storage-backend storage-backend-advice)
      progress/advise
      (dry-run/advise side-effecting)))
