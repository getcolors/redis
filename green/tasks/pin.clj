(ns pin (:require [clojure.java.shell :as sh] [clojure.string :as str]))
;; One SHA, two payloads today and three tomorrow. Every payload is born
;; unpinned (no invented SHAs) and `bb pin` stamps or re-stamps it after a
;; clean, pushed HEAD. Each site recognises exactly two forms, its unpinned
;; birth shape and its pinned shape, and the run fails loudly when a payload
;; matches neither. Modelled on neon/green/tasks/pin.clj: one site per colour.
(defn git [& args] (let [{:keys [exit out]} (apply sh/sh "git" args)] (when (zero? exit) (str/trim out))))

(defn stamp-green [s sha]
  (when (re-find #"\(def \^:private redis-sha (?:nil|\"[0-9a-f]{40}\")\)" s)
    (str/replace-first s #"\(def \^:private redis-sha (?:nil|\"[0-9a-f]{40}\")\)"
                       (str "(def ^:private redis-sha \"" sha "\")"))))

(defn stamp-red [s sha]
  (let [pinned (str "\"package-redis-red\": \"github:getcolors/redis#" sha "\",")]
    (cond (str/includes? s "\"package-redis-red\": null,")
          (str/replace-first s "\"package-redis-red\": null," pinned)
          (re-find #"\"package-redis-red\": \"github:getcolors/redis#[0-9a-f]{40}\"," s)
          (str/replace-first s #"\"package-redis-red\": \"github:getcolors/redis#[0-9a-f]{40}\"," pinned))))

;; The blue payload's PEP 723 block. The blue SDK pin is the one colors-compute
;; expects at its own pin, and this package does not depend on ONCE, so there
;; is no override-dependencies block: the two git requirements already agree.
(def blue-unpinned-meta "# dependencies = []\n# ///")
(defn blue-pinned-meta [sha]
  (str "# dependencies = [\"package-redis-blue\", \"blue\", \"colors-compute-blue @ git+https://github.com/getcolors/colors-compute.git@ae28ea74962bb1897fa6365c143c1d43ac1fe095#subdirectory=blue\"]\n"
       "#\n"
       "# [tool.uv.sources]\n"
       "# package-redis-blue = { git = \"https://github.com/getcolors/redis.git\", rev = \"" sha "\", subdirectory = \"blue\" }\n"
       "# blue = { git = \"https://github.com/getcolors/blue.git\", rev = \"290f313ead5ca162875c33a049c880da017eae09\" }\n"
       "# ///"))
(defn stamp-blue [s sha]
  ;; First stamp is structural: the metadata block gains its git sources and the
  ;; UNPINNED paragraph collapses to a pinned-state note. Re-pinning is a SHA swap.
  (cond (str/includes? s blue-unpinned-meta)
        (-> s
            (str/replace-first blue-unpinned-meta (blue-pinned-meta sha))
            (str/replace-first #"(?s)# UNPINNED:.*?REDIS_LIB_ROOT=/path/to/redis\n"
                               "# Stamped by `bb pin`. REDIS_LIB_ROOT=/path/to/redis still overrides the\n# pin with a working tree.\n"))
        (re-find #"redis\.git\", rev = \"[0-9a-f]{40}\"" s)
        (str/replace-first s #"redis\.git\", rev = \"[0-9a-f]{40}\""
                           (str "redis.git\", rev = \"" sha "\""))))

(def sites
  [{:path "../skills/package-redis-green/green" :stamp stamp-green}
   {:path "../skills/package-redis-red/red" :stamp stamp-red}
   {:path "../skills/package-redis-blue/blue" :stamp stamp-blue}])

(let [dirty (git "status" "--porcelain") sha (git "rev-parse" "HEAD") remotes (git "branch" "-r" "--contains" sha)]
  (cond (seq dirty) (do (binding [*out* *err*] (println "redis working tree is dirty; commit before pinning")) (System/exit 2))
        (not (str/includes? (str remotes) "origin/")) (do (binding [*out* *err*] (println "redis HEAD is not pushed")) (System/exit 2))
        :else (let [errors (atom [])]
                (doseq [{:keys [path stamp]} sites]
                  (let [s (slurp path) n (stamp s sha)]
                    (if n (spit path n) (swap! errors conj (str "could not locate a pin form in " path)))))
                (if (seq @errors)
                  (do (binding [*out* *err*] (println (str/join "\n" @errors))) (System/exit 2))
                  (println "pinned" (count sites) "launcher(s) to" (subs sha 0 7))))))
