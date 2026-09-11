(ns io.github.getcolors.redis.storage
  "The deployment-owned S3 backup bucket and its scoped credentials.

  With `redis-storage-managed: true` the package owns the bucket named by
  `redis-backup-r2-bucket` in its own OpenTofu stage: the bucket, its public
  access block and encryption, one IAM user scoped to that bucket, and one
  access key. The key pair is a sensitive stage output. It never enters a
  template value or a rendered file; it is read from state when a play needs
  it and handed to ansible-playbook as the same COLORS_PAR_REDIS_BACKUP_R2_*
  variables an operator would export for an external bucket, so main.yml's
  `lookup('env', ...)` expressions are unchanged. Modelled on
  neon-multi-node's storage namespace."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.cli :as cli]
            [green.process :as process]
            [green.scaffold :as scaffold]
            [green.tofu :as tofu]))

(def tool "redis-storage")
(def credentials-key :redis/storage-credentials)
(def bucket-role "backup")
(def credential-prefix "REDIS_BACKUP_R2")

(defn managed? [opts] (true? (:redis-storage-managed opts)))

(defn directory [opts] (cli/stage-dir opts tool {:default-profile "redis"}))

(defn aws-env
  "AWS_* variables for tofu and the AWS CLI, overlaid from the optional
  COLORS_PAR_AWS_* pars. Absent pars leave the ambient credential chain alone."
  [opts]
  (into {} (keep (fn [[key variable]] (when-let [value (not-empty (str (get opts key)))] [variable value])))
        {:aws-access-key-id "AWS_ACCESS_KEY_ID" :aws-secret-access-key "AWS_SECRET_ACCESS_KEY"
         :aws-session-token "AWS_SESSION_TOKEN"}))

(defn specs [opts]
  [{:template :io.github.getcolors.redis.tools.storage/main.tf
    :target (str (directory opts) "/main.tf")
    :data (dissoc opts credentials-key)
    :opts scaffold/preserve-jinja-delimiters}])

(defn- checked [args options]
  (let [result (process/run args options)]
    (when-not (zero? (:exit result))
      (throw (ex-info "managed storage state operation failed" {})))
    (:out result)))

(defn ownership-preflight!
  "Refuse an existing bucket unless this stage already owns its address."
  [opts]
  (let [options {:dir (directory opts) :extra-env (aws-env opts)}]
    (checked ["tofu" "init" "-input=false" "-no-color"] options)
    (let [state (process/run ["tofu" "state" "list"] options)
          empty-state? (and (= 1 (:exit state)) (str/includes? (str (:err state)) "No state file was found!"))
          _ (when-not (or (zero? (:exit state)) empty-state?)
              (throw (ex-info "managed storage state unavailable" {})))
          addresses (set (str/split-lines (if empty-state? "" (:out state))))
          recorded (if (empty? addresses) {}
                       (into {} (map (juxt :address #(get-in % [:values :bucket])))
                             (get-in (json/parse-string (checked ["tofu" "show" "-json"] options) true) [:values :root_module :resources])))
          bucket (:redis-backup-r2-bucket opts)]
      (when-not (= bucket (get recorded (str "aws_s3_bucket.application[\"" bucket-role "\"]")))
        (let [result (process/run ["aws" "s3api" "head-bucket" "--bucket" bucket "--region" (:redis-backup-r2-region opts)] options)]
          ;; 403, network failures and a successful probe all fail closed.
          (when-not (and (pos? (:exit result)) (re-find #"\(404\)|Not Found|NoSuchBucket" (str (:err result))))
            (throw (ex-info "managed storage refuses to adopt an existing or inaccessible bucket" {}))))))))

(defn step
  "Create, render or destroy the storage stage. Not managed: a no-op."
  [opts]
  (if-not (managed? opts) (assoc opts :green/exit 0)
    (try
      (let [documents (specs opts)]
        (when (= :create (:green/event opts))
          (scaffold/scaffold opts documents)
          (ownership-preflight! opts))
        ;; The scoped pair stays in memory and in the encrypted backend state;
        ;; never copy it into template values or print the output object.
        (tofu/tofu-with-spec opts documents
          {:dir (directory opts) :env (aws-env opts) :output-key credentials-key}))
      (catch Exception _ (assoc opts :green/exit 1 :green/err "managed S3 storage failed; inspect bucket ownership, state access, and AWS permissions")))))

(defn credential-env
  "The COLORS_PAR_REDIS_BACKUP_R2_* pair for ansible-playbook, from the
  storage stage output. Throws when the output is missing or blank."
  [opts]
  ;; green.tofu keywords output names only; JSON object values keep string keys.
  (let [{:keys [access_key_id secret_access_key]}
        (get (walk/keywordize-keys (get-in opts [credentials-key :credentials])) (keyword bucket-role))]
    (when (or (str/blank? access_key_id) (str/blank? secret_access_key))
      (throw (ex-info "managed storage credentials unavailable" {})))
    {(str "COLORS_PAR_" credential-prefix "_ACCESS_KEY_ID") access_key_id
     (str "COLORS_PAR_" credential-prefix "_SECRET_ACCESS_KEY") secret_access_key}))

(defn read-credentials!
  "Read the scoped pair back from the storage state for a verb that runs a
  play without converging the stage (rehearse). Not managed: opts unchanged."
  [opts]
  (if-not (managed? opts) opts
    (try
      ((tofu/conventional-backend-advice {:dir-fn directory :key-fn #(str (:profile %) "/" tool ".tfstate")}) opts)
      (scaffold/scaffold (assoc opts :green/event :build) (specs opts))
      (checked ["tofu" "init" "-input=false" "-no-color"] {:dir (directory opts) :extra-env (aws-env opts)})
      (let [result (assoc opts credentials-key (tofu/outputs (directory opts) (aws-env opts)))]
        (credential-env result)
        result)
      (catch Exception _ (throw (ex-info "managed storage credentials unavailable; converge storage before rehearsal" {}))))))
