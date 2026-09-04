(ns io.github.getcolors.redis.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
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

(def name-rules
  "What each provider accepts as a machine name, checked here rather than
  discovered mid-apply. DigitalOcean droplet names are hostname-like; Vultr
  labels are free-form console text, held to a safe subset."
  {"digitalocean" {:re #"^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$"
                   :message "must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"}
   "vultr" {:re #"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$"
            :message "must be a safe 1-63 character name"}})

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn placeholder?
  "Whether the compute-name override is effectively absent (Compute Name
  Standard §2: presence is the only switch)."
  [v]
  (or (missing? v) (= "REPLACE_ME" (str/trim (str v)))))

(defn compute-provider [opts] (get compute-providers (:provider-compute opts)))

(defn compute-key
  "Desired state names compute keys after the provider, so the shared steps
  reach them through the selected provider rather than a fixed prefix."
  [opts suffix]
  (keyword (str (:provider-compute opts) "-" suffix)))

(defn compute-name
  "What this deployment calls its machine. The one function that answers it —
  every label, including the firewall's, derives from this and never from the
  raw override key or a second copy of the profile (§3)."
  [opts]
  (let [override (get opts (compute-key opts "name"))]
    (if (placeholder? override) (str (:profile opts)) (str/trim (str override)))))

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

(defn cidrs
  "A source list as desired state or an overlay string carries it: a YAML
  list, or one string of comma- or space-separated entries."
  [opts k]
  (let [v (get opts k) xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

;; Syntactic CIDR checks, deliberately not a resolver: an address library that
;; accepts a hostname would let a firewall source depend on DNS at apply time.
(def ^:private ipv4-re
  #"^(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$")
(def ^:private hex-group-re #"^[0-9A-Fa-f]{1,4}$")

(defn- ipv6-address? [s]
  (let [groups (fn [part] (if (str/blank? part) [] (str/split part #":" -1)))]
    (if (str/includes? s "::")
      (let [halves (str/split s #"::" -1)]
        (and (= 2 (count halves))
             (let [gs (mapcat groups halves)]
               (and (<= (count gs) 7) (every? #(re-matches hex-group-re %) gs)))))
      (let [gs (groups s)]
        (and (= 8 (count gs)) (every? #(re-matches hex-group-re %) gs))))))

(defn cidr?
  "Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
  slash, and a prefix length the address family allows."
  [s]
  (let [[address prefix & more] (str/split (str s) #"/" -1)]
    (and (nil? more) (some? prefix) (re-matches #"^\d{1,3}$" prefix)
         (let [n (Long/parseLong prefix)]
           (cond
             (re-matches ipv4-re address) (<= 0 n 32)
             (ipv6-address? address) (<= 0 n 128)
             :else false)))))

(defn source-errors
  "The network contract (§5): the selected provider's SSH sources must name at
  least one CIDR — a machine nobody can reach is not a deployment — and every
  entry must be one. Refusing beats failing at apply time."
  [opts]
  (let [ssh-key (compute-key opts "ssh-sources")]
    (concat
     (when (and (not (missing? (get opts ssh-key))) (empty? (cidrs opts ssh-key)))
       [(str ssh-key " must list at least one CIDR")])
     (for [entry (when-not (missing? (get opts ssh-key)) (cidrs opts ssh-key))
           :when (not (cidr? entry))]
       (str ssh-key " entry " (pr-str entry) " is not an IPv4 or IPv6 CIDR")))))

(defn provider-errors
  "Checks that hold only for the selected provider. Keys of the other provider
  are ignored, never refused."
  [opts]
  (let [name-key (compute-key opts "name")
        {:keys [re message]} (get name-rules (:provider-compute opts))
        name (str/trim (str (get opts name-key)))]
    (concat
     (when (and re (not (placeholder? (get opts name-key)))
                (or (> (count name) 63) (not (re-matches re name))))
       [(str name-key " " message)])
     (case (:provider-compute opts)
       "vultr"
       (when-not (or (missing? (:vultr-os-id opts)) (integer? (:vultr-os-id opts)))
         [":vultr-os-id must be Vultr's numeric operating-system id"])
       "digitalocean"
       (concat
        ;; No VPC is created: the region's default is discovered at plan time,
        ;; and a pinned UUID or a CIDR would make this package start owning one.
        (when (contains? opts :digitalocean-vpc-uuid)
          [":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"])
        (when (contains? opts :digitalocean-vpc-cidr)
          [":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]))
       nil))))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn- positive-int? [v] (and (integer? v) (pos? v)))

(defn state-errors [opts]
  (vec
   (concat
    (for [k (concat required (:required (compute-provider opts)))
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (compute-provider opts)
      [(str ":provider-compute must be one of "
            (str/join ", " (sort (keys compute-providers))))])
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
    (when (compute-provider opts)
      (concat (provider-errors opts) (source-errors opts))))))

(defn provider-state-errors
  "Provider switching is a rebuild, never an apply (§4). Every provider shares
  one state key, so a changed provider-compute on a profile whose state already
  holds compute would plan a cross-provider replacement — and a delete would
  render and destroy the *selected* provider's template against the wrong
  lifecycle. `params` is the compute stage's recorded output, or nil when the
  state holds none; its `provider` is the registry name the template that
  produced it belongs to. A recorded output without one predates this package
  recording it, which makes it the default provider's."
  [opts params]
  (let [selected (:provider-compute opts)
        recorded (some-> (:provider params) str not-empty)]
    (cond
      (nil? params) nil

      (and recorded (not= recorded selected))
      [(str "state holds a " recorded " machine; set provider-compute back to "
            recorded " and delete first")]

      (and (nil? recorded) (not= selected default-compute-provider))
      [(str "state holds a machine with no recorded provider, created before this "
            "package recorded one, which makes it a " default-compute-provider
            " machine; set provider-compute back to " default-compute-provider
            " and delete first")]

      :else nil)))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(defn provider-secrets
  "What talking to the selected provider needs, on any real event. Derived
  from the registry entry alone, never from a second list kept beside it."
  [opts]
  (:secrets (compute-provider opts)))

(def application-secrets
  "What converging the machine needs, and therefore only a create: the R2
  pair the backup sets are written with. The Redis password is deliberately
  absent — it is generated on the server, once, and never operator-supplied."
  [:redis-backup-r2-access-key-id :redis-backup-r2-secret-access-key])

(defn secret-errors
  "Credentials a real event needs. A delete tears down infrastructure and never
  converges anything, so it asks for the provider credentials only."
  [opts event]
  (let [keys (concat (provider-secrets opts)
                     (when (= :create event) application-secrets)
                     (backend-secrets opts))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

(defn tofu-env [opts slot]
  (case slot
    :provider-compute (:tofu-env (compute-provider opts) {})
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
