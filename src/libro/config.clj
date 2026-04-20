(ns libro.config
  "Config persistence for libro-fm-cli.

   Config lives at $XDG_CONFIG_HOME/libro-fm-cli/config.edn and holds
   credentials plus the cached OAuth access token."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.pprint :as pp]
   [ol.dirs :as dirs]))

(def app-name "libro-fm-cli")

(defn config-path [] (str (fs/path (dirs/config-home app-name) "config.edn")))

(defn- read-edn [path]
  (when (fs/exists? path)
    (try (edn/read-string (slurp path))
         (catch Exception _ nil))))

(defn- write-edn-secret
  "Write EDN to path with owner-only (0600) permissions. The file may
   contain credentials or an OAuth token, so it must never be world-readable."
  [path data]
  (fs/create-dirs (fs/parent path))
  (spit path (with-out-str (pp/pprint data)))
  (fs/set-posix-file-permissions path "rw-------"))

(defn load-config
  "Load the config file, merging in environment-variable overrides."
  []
  (let [env  {:username (System/getenv "LIBRO_FM_EMAIL")
              :password (System/getenv "LIBRO_FM_PASSWORD")}
        file (or (read-edn (config-path)) {})]
    (merge file (into {} (remove (comp nil? val) env)))))

(defn save-config!
  "Merge updates into the on-disk config file. Env-only values are not persisted."
  [updates]
  (let [persisted (or (read-edn (config-path)) {})
        merged    (merge persisted updates)]
    (write-edn-secret (config-path) merged)
    merged))
