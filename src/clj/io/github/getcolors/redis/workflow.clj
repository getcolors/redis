(ns io.github.getcolors.redis.workflow
  (:require [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.workflow :as wf]
            [io.github.getcolors.redis.ssh :as ssh]
            [io.github.getcolors.redis.ssh-config :as ssh-config]
            [io.github.getcolors.redis.tools :as tools]
            [io.github.getcolors.redis.validate :as validate]))
(def defaults {:provider-compute validate/default-compute-provider
               :provider-backend "r2" :compute-prevent-destroy true :workdir ".colors"})
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
(defn wire-fn [step opts]
  (case (:green/event opts)
    :delete (case step
              :redis/start [start-step :redis/load-infrastructure]
              :redis/load-infrastructure [tools/load-infrastructure-step :redis/ansible]
              :redis/ansible [tools/ansible-step :redis/ssh-config]
              :redis/ssh-config [tools/ansible-local-step :redis/infrastructure]
              :redis/infrastructure [tools/infrastructure-step])
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
      :redis/infrastructure [tools/infrastructure-step :redis/ssh-config]
      :redis/ssh-config [tools/ansible-local-step :redis/ansible]
      :redis/ansible [tools/ansible-step :redis/acceptance]
      :redis/acceptance [tools/acceptance-step])))
(def side-effecting [:redis/load-infrastructure :redis/infrastructure :redis/ssh-config
                    :redis/ansible :redis/acceptance :redis/rehearsal :redis/describe])
(def workflow
  (-> (wf/workflow {:start :redis/start :wire-fn wire-fn
                    :next-fn (fn [_ successors opts]
                               (if (or (wf/failed? opts) (:redis/already-destroyed opts)) []
                                   (mapv #(vector % opts) successors)))})
      progress/advise
      (dry-run/advise side-effecting)))
