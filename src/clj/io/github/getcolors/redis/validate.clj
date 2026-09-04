(ns io.github.getcolors.redis.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.once.compute :as compute]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def compute-providers
  "provider-compute -> what that choice implies (Compute Provider Standard §2).

  `:required` are the non-secret keys that provider's template interpolates,
  `:secrets` the credentials it needs through COLORS_PAR_*, and `:tofu-env` the
  subset OpenTofu reads from the process environment itself. Keeping the three
  together is what stops a provider being validated against one set of keys and
  run with another. The keys of this map are the advertised providers; a
  provider without a template directory and a golden is not advertised.

  Two keys the templates read are deliberately not required. `<provider>-name`
  is an optional override of the profile (Compute Name Standard), and
  `<provider>-ssh-keys` is meaningful by its absence (SSH Keypair Standard).
  There is no `<provider>-http-sources`: nothing in this package speaks HTTP,
  the firewall opens 22 alone, and the standard allows a package with no
  public HTTP. Keys of the unselected provider are accepted and ignored, so
  one colors.yml stays portable between providers."
  {"digitalocean"
   {:required [:digitalocean-region :digitalocean-size :digitalocean-image
               :digitalocean-ssh-sources]
    :secrets [:do-token]
    :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}
   "vultr"
   {:required [:vultr-region :vultr-plan :vultr-os-id :vultr-ssh-sources]
    :secrets [:vultr-api-key]
    :tofu-env {:vultr-api-key "VULTR_API_KEY"}}})

(def default-compute-provider
  "The provider a deployment created before this package recorded one in its
  compute output must be running: the only one it ever offered."
  "vultr")

(def spec
  "How this package describes itself to ONCE's `compute`, the Compute Provider
  Standard's operations over a package-owned registry. The registry and the
  default are the data above; `:sources` names the firewall lists the
  templates read — SSH must list at least one CIDR, and there is no HTTP list
  at all, so `:may-be-empty` is empty. The name rules are ONCE's."
  {:registry compute-providers
   :default default-compute-provider
   :sources {:non-empty ["ssh-sources"] :may-be-empty []}})

(def required
  "Every key desired state must carry whichever provider is selected. The
  provider-scoped keys come from `compute-providers`. There is no
  `provider-dns`: nothing in this package is reachable by name — the firewall
  opens 22 only and the client path is an SSH tunnel — so a DNS provider would
  be a key with nothing to configure."
  [:profile :workdir :provider-compute :provider-backend
   :compute-prevent-destroy
   :redis-image :redis-port
   :redis-backup-r2-bucket :redis-backup-r2-endpoint :redis-backup-r2-region
   :redis-backup-oncalendar :redis-backup-retention-days
   :redis-backup-max-age-hours
   :r2-bucket :r2-endpoint])

;; `tag@sha256:...` pins both the human-readable release and the exact bytes.
;; Docker Hub republishes the `7.2` and `7.2.16` tags whenever the base image
;; is rebuilt, which is why the digest is required rather than the tag denied.
(def image-re #"^[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64}|:[^\s:@]+@sha256:[0-9a-f]{64})$")
(def url-re #"^https://[^\s]+$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(def compute-key
  "`:<provider>-<suffix>`: desired state names compute keys after the
  provider, so the shared steps reach them through the selected provider
  rather than a fixed prefix. ONCE's; named here so `tools` reads the same."
  compute/key)

(def compute-name
  "What this deployment calls its machine: `<provider>-name` when present,
  else the profile (Compute Name Standard). ONCE's; every label, including
  the firewall's, derives from this one answer and never from the raw
  override key or a second copy of the profile (§3)."
  compute/name)

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

(def cidrs
  "A source list as desired state or an overlay string carries it. ONCE's, so
  the validator and the templates can never disagree about what an entry is."
  compute/cidrs)

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn- positive-int? [v] (and (integer? v) (pos? v)))

(defn state-errors
  "Every problem with desired state at once: the missing keys (this package's
  and the selected provider's), the package's own checks, then the Compute
  Provider Standard's — selection, the network contract and the provider
  rules — which are ONCE's over `spec`."
  [opts]
  (vec
   (concat
    (for [k (concat required (compute/required-keys spec opts))
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (contains? #{"local" "s3" "r2"} (:provider-backend opts))
      [":provider-backend must be local, s3, or r2"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (let [v (:redis-image opts)]
      (when (and (not (missing? v)) (not (re-matches image-re (str v))))
        [":redis-image must carry an explicit image tag or digest"]))
    (let [v (:redis-image opts)]
      (when (and (not (missing? v)) (not (str/includes? (str v) "@sha256:")))
        [":redis-image must be pinned by digest (tag@sha256:...)"]))
    (let [v (:redis-port opts)]
      (when (and (not (missing? v)) (not (and (integer? v) (<= 1 v 65535))))
        [":redis-port must be an integer between 1 and 65535"]))
    (when-not (or (missing? (:redis-backup-r2-endpoint opts))
                  (re-matches url-re (str (:redis-backup-r2-endpoint opts))))
      [":redis-backup-r2-endpoint must be an https URL"])
    (for [k [:redis-backup-retention-days :redis-backup-max-age-hours]
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (positive-int? v)))]
      (str k " must be a positive integer"))
    (compute/state-errors spec opts))))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(def application-secrets
  "What converging the machine needs, and therefore only a create: the R2
  pair the backup sets are written with. The Redis password is deliberately
  absent — it is generated on the server, once, and never operator-supplied."
  [:redis-backup-r2-access-key-id :redis-backup-r2-secret-access-key])

(defn secret-errors
  "Credentials a real event needs. The provider's come from the registry entry
  alone, through ONCE, never from a second list kept beside it. A delete tears
  down infrastructure and never converges anything, so it asks for the
  provider credentials only."
  [opts event]
  (let [keys (concat (compute/secrets spec opts)
                     (when (= :create event) application-secrets)
                     (backend-secrets opts))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

(defn tofu-env [opts slot]
  (case slot
    :provider-compute (compute/tofu-env spec opts)
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
