(ns libro-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [libro]
   [libro.api :as api]
   [libro.config :as config]))

(deftest namespaces-load
  (testing "entry namespace loads"
    (is (some? (find-ns 'libro))))
  (testing "api namespace loads"
    (is (some? (find-ns 'libro.api))))
  (testing "config namespace loads"
    (is (some? (find-ns 'libro.config)))))

(deftest config-paths-include-app-name
  (is (re-find #"libro-fm-cli" (config/config-path)))
  (is (re-find #"libro-fm-cli" (config/state-path))))

(deftest api-constants
  (is (= "https://libro.fm" api/base-url))
  (is (string? api/user-agent)))
