(ns io.github.getcolors.redis.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def required
  "Every key desired state must carry.

  Two deliberate absences. `vultr-ssh-keys` selects opt-out mode by being
  present (SSH Keypair Standard), so requiring it would make every conforming
  keygen deployment invalid. `vultr-name` is the Compute Name Standard's
  optional override: a fresh colors.yml that omits it is complete and names
  the machine after the profile. There is likewise no `provider-dns`: nothing
  in this package is reachable by name — the firewall opens 22 only and the
  client path is an SSH tunnel — so a DNS provider would be a key with
  nothing to configure."
  [:profile :workdir :provider-compute :provider-backend
   :compute-prevent-destroy
   :redis-image :redis-port
   :redis-backup-r2-bucket :redis-backup-r2-endpoint :redis-backup-r2-region
   :redis-backup-oncalendar :redis-backup-retention-days
   :redis-backup-max-age-hours
   :vultr-region :vultr-plan :vultr-os-id :vultr-vpc-subnet
   :vultr-ssh-sources
   :r2-bucket :r2-endpoint])

;; `tag@sha256:...` pins both the human-readable release and the exact bytes.
;; Docker Hub republishes the `7.2` and `7.2.16` tags whenever the base image
;; is rebuilt, which is why the digest is required rather than the tag denied.
(def image-re #"^[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64}|:[^\s:@]+@sha256:[0-9a-f]{64})$")
(def url-re #"^https://[^\s]+$")
(def cidr-v4-re #"^(\d{1,3}\.){3}\d{1,3}/\d{1,2}$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn placeholder?
  "Whether the compute-name override is effectively absent (Compute Name
  Standard §2: presence is the only switch)."
  [v]
  (or (missing? v) (= "REPLACE_ME" (str/trim (str v)))))

(defn compute-name
  "What this deployment calls its machine. The one function that answers it —
  every label, including the firewall's and the VPC's, derives from this and
  never from the raw override key or a second copy of the profile (§3)."
  [opts]
  (let [override (:vultr-name opts)]
    (if (placeholder? override) (str (:profile opts)) (str/trim (str override)))))

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn- positive-int? [v] (and (integer? v) (pos? v)))

(defn state-errors [opts]
  (vec
   (concat
    (for [k required :when (missing? (get opts k))] (str k " is required"))
    (when-not (= "vultr" (:provider-compute opts))
      [":provider-compute must be vultr"])
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
    (when-not (or (missing? (:vultr-vpc-subnet opts))
                  (re-matches cidr-v4-re (str (:vultr-vpc-subnet opts))))
      [":vultr-vpc-subnet must be an IPv4 CIDR such as 10.60.0.0/24"])
    (when-not (or (missing? (:vultr-os-id opts)) (integer? (:vultr-os-id opts)))
      [":vultr-os-id must be Vultr's numeric operating-system id"]))))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(def provider-secrets
  "What talking to the provider needs, on any real event."
  [:vultr-api-key])

(def application-secrets
  "What converging the machine needs, and therefore only a create: the R2
  pair the backup sets are written with. The Redis password is deliberately
  absent — it is generated on the server, once, and never operator-supplied."
  [:redis-backup-r2-access-key-id :redis-backup-r2-secret-access-key])

(defn secret-errors
  "Credentials a real event needs. A delete tears down infrastructure and never
  converges anything, so it asks for the provider credentials only."
  [opts event]
  (let [keys (concat provider-secrets
                     (when (= :create event) application-secrets)
                     (backend-secrets opts))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

(defn tofu-env [opts slot]
  (case slot
    :provider-compute {:vultr-api-key "VULTR_API_KEY"}
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
