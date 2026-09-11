(ns io.github.getcolors.redis.ssh-test
 (:require [clojure.test :refer [deftest is]] [io.github.getcolors.redis.ssh :as ssh]
           [io.github.getcolors.redis.validate-test :refer [fixture optout]]))
(deftest build-and-external-identity
 (is (= "/home/build-placeholder/.ssh/redis-fixture" (:ssh-private-key-path (ssh/with-machine-key (fixture :green/event :build)))))
 (is (= (optout) (ssh/with-machine-key (optout))))
 (is (= [] (ssh/identity-args (optout))))
 (is (= ["-i" "/operator/key" "-o" "IdentitiesOnly=yes"] (ssh/identity-args (optout :ssh-private-key-path "/operator/key")))))
