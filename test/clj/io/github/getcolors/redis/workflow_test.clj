(ns io.github.getcolors.redis.workflow-test
 (:require [clojure.test :refer [deftest is]] [clojure.java.io :as io]
           [green.workflow :as engine] [io.github.getcolors.redis.workflow :as workflow]
           [io.github.getcolors.redis.tools :as tools] [io.github.getcolors.redis.compute :as compute]
           [io.github.getcolors.compute-orchestration :as orchestration]
           [io.github.getcolors.compute-inspection :as inspection]
           [io.github.getcolors.redis.validate-test :refer [fixture optout do-fixture do-optout]]))
(deftest cleanup-order-and-read-only-events
 (is (= [tools/load-infrastructure-step :redis/ansible] (workflow/wire-fn :redis/load-infrastructure {:green/event :delete})))
 (is (= [tools/ansible-local-step :redis/infrastructure] (workflow/wire-fn :redis/ssh-config {:green/event :delete})))
 (is (= [tools/infrastructure-step] (workflow/wire-fn :redis/infrastructure {:green/event :delete})))
 (doseq [event [:rehearse :describe]]
  (is (= :redis/load-infrastructure (second (workflow/wire-fn :redis/start {:green/event event}))))))
(deftest failures-refuse-application-inventory
 (with-redefs [orchestration/orchestrate (fn [& _] {:status "error"})]
  (is (= 1 (:green/exit (tools/infrastructure-step (fixture :green/event :create))))))
 (with-redefs [inspection/read-deployment (fn [& _] {:status "error"})]
  (is (= 1 (:green/exit (tools/load-infrastructure-step (fixture :green/event :delete))))))
 (is (thrown? Exception (compute/node (fixture :green/event :create)))))
(deftest inspection-preserves-connection-user-and-identity
 (let [node {:node_id "0" :provider "vultr" :name "redis-fixture" :ip "203.0.113.8" :user "ubuntu" :sudoer "ubuntu" :ssh_identity_file "/operator/key"}]
  (with-redefs [inspection/read-deployment (fn [& _] {:status "present" :cluster {:nodes [node]}})]
   (let [out (tools/load-infrastructure-step (fixture :green/event :describe))]
    (is (= "ubuntu" (:user out))) (is (= "/operator/key" (:ssh-private-key-path out)))
    (is (= "203.0.113.8" (:ip (compute/node out))))))))
(deftest destroyed-delete-stops-cleanup
 (with-redefs [inspection/read-deployment (fn [& _] {:status "destroyed"})]
  (is (:redis/already-destroyed (tools/load-infrastructure-step (fixture :green/event :delete))))
  (is (= 1 (:green/exit (tools/load-infrastructure-step (fixture :green/event :describe)))))))
(deftest all-fixtures-native-build-through-library
 (doseq [f [fixture optout do-fixture do-optout]]
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory "redis-library-build-" (make-array java.nio.file.attribute.FileAttribute 0)))
        opts (f :green/event :build :workdir (.getPath directory))]
   (try
    (let [result (engine/run workflow/workflow opts)]
     (is (not (engine/failed? result)) (:green/err result))
     (is (= "192.0.2.10" (:ip result)))
     (is (nil? (:vpc_ip (compute/node result))))
     (is (.isFile (io/file (tools/tool-dir opts tools/infrastructure-tool) "nodes/0/node-none.tf.json"))))
    (finally (doseq [file (reverse (file-seq directory))] (.delete file)))))))
