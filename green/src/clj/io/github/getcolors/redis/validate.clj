(ns io.github.getcolors.redis.validate
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.compute-diagnostics :as diagnostics]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.compute :as render]))

(def profile-par (green-cli/par-name :profile))

(def default-compute-provider "vultr")

(def required
  [:profile :workdir :provider-compute :provider-backend
   :compute-prevent-destroy
   :redis-image :redis-port
   :redis-backup-r2-bucket :redis-backup-r2-endpoint :redis-backup-r2-region
   :redis-backup-oncalendar :redis-backup-retention-days
   :redis-backup-max-age-hours])

;; `tag@sha256:...` pins both the human-readable release and the exact bytes.
;; Docker Hub republishes the `7.2` and `7.2.16` tags whenever the base image
;; is rebuilt, which is why the digest is required rather than the tag denied.
(def image-re #"^[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|@sha256:[0-9a-f]{64}|:[^\s:@]+@sha256:[0-9a-f]{64})$")
(def url-re #"^https://[^\s]+$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn keygen? [opts] (= "managed" (:mode (ssh/mode opts))))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn executable-on-path? [program paths]
  (boolean (some (fn [path]
                   (let [file (io/file (if (str/blank? path) "." path) program)]
                     (and (.isFile file) (.canExecute file)))) paths)))

(defn runtime-tool-errors [opts env]
  ;; Check before acquiring remote ownership or generating the machine key.
  (let [paths (when (contains? env "PATH") (str/split (get env "PATH") #":" -1))
        missing (diagnostics/missing-tools opts env)]
    (concat
     (when (seq missing) (:errors (diagnostics/result (diagnostics/failure "missing-tool" missing))))
     (for [program ["ansible-playbook" "ssh" "redis-cli" "bash" "timeout"]
          :when (not (executable-on-path? program paths))]
      (str "required executable is not on PATH: " program "; load the deployment toolchain first")))))

(defn- positive-int? [v] (and (integer? v) (pos? v)))

(defn managed-storage? [opts] (true? (:redis-storage-managed opts)))

(defn aws-endpoint
  "The S3 endpoint of one AWS region, the only endpoint a managed bucket has."
  [region]
  (str "https://s3." region ".amazonaws.com"))

(defn storage-errors
  "The managed-storage contract: the package creates the bucket in the AWS
  region the state backend lives in, under the managed S3 backend, so one
  finalize proves one account's resources gone."
  [opts]
  (when (managed-storage? opts)
    (let [region (:s3-region opts) bucket (str (:redis-backup-r2-bucket opts))]
      (vec (concat
            (when-not (= "s3" (:provider-backend opts))
              [":redis-storage-managed requires provider-backend s3"])
            (when-not (= "managed" (:s3-bucket-mode opts))
              [":redis-storage-managed requires s3-bucket-mode managed"])
            (when-not (and (not (missing? region)) (= region (:redis-backup-r2-region opts)))
              [":redis-backup-r2-region must equal s3-region when storage is managed"])
            (when-not (and (not (missing? region)) (= (aws-endpoint region) (:redis-backup-r2-endpoint opts)))
              [(str ":redis-backup-r2-endpoint must be " (aws-endpoint (or region "<s3-region>")) " when storage is managed")])
            (when (str/includes? bucket ".")
              [":redis-backup-r2-bucket must not contain dots when storage is managed"])
            (when (= bucket (str (:s3-bucket opts)))
              [":redis-backup-r2-bucket must differ from s3-bucket"]))))))

(defn state-errors
  "Application settings and the library backend contract."
  [opts]
  (vec
   (concat
    (for [k required
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (contains? #{"s3" "r2"} (:provider-backend opts))
      [":provider-backend must be s3 or r2"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when-not (boolean? (:redis-storage-managed opts))
      [":redis-storage-managed must be true or false"])
    (storage-errors opts)
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
    (try (render/backend-plan opts (str (:profile opts) "/shared.tfstate")) []
         (catch Exception e [(ex-message e)])))))

(def application-secrets
  "What converging the machine needs, and therefore only a create: the R2
  pair the backup sets are written with. The Redis password is deliberately
  absent — it is generated on the server, once, and never operator-supplied.
  With managed storage the pair is a storage stage output, not an operator
  secret, so a create requires nothing from the environment."
  [:redis-backup-r2-access-key-id :redis-backup-r2-secret-access-key])

(defn secret-errors [opts event]
  (for [key (when (and (= :create event) (not (managed-storage? opts))) application-secrets)
        :when (missing? (get opts key))]
    (str "required credential is not set: " (green-cli/par-name key))))
