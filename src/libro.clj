#!/usr/bin/env bb
(ns libro
  "libro-fm-cli — a babashka CLI for interacting with libro.fm."
  (:require
   [babashka.cli :as cli]
   [cheshire.core :as json]
   [clojure.string :as str]
   [libro.api :as api]
   [libro.config :as config]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- die!
  ([msg] (die! msg 1))
  ([msg code]
   (binding [*out* *err*] (println msg))
   (System/exit code)))

(defn- prompt
  "Read a single line from stdin after printing prompt-text to stderr."
  [prompt-text]
  (binding [*out* *err*] (print prompt-text) (flush))
  (read-line))

(defn- prompt-secret
  "Read a password from the console without echoing. Falls back to stdin if no
   console is available (e.g. inside a pipe)."
  [prompt-text]
  (if-let [console (System/console)]
    (do (binding [*out* *err*] (print prompt-text) (flush))
        (String. (.readPassword console)))
    (prompt prompt-text)))

(defn- ensure-token!
  "Return a valid auth token, logging in if needed. Mutates on-disk config on login."
  []
  (let [{:keys [auth-token username password]} (config/load-config)]
    (if auth-token
      auth-token
      (let [user                   (or username (prompt "libro.fm email: "))
            pass                   (or password (prompt-secret "libro.fm password: "))
            _                      (when (or (str/blank? user) (str/blank? pass))
                                     (die! "username and password are required"))
            {:keys [access_token]} (api/login user pass)]
        (when-not access_token
          (die! "login failed: no access_token in response"))
        (config/save-config! {:username user :auth-token access_token})
        access_token))))

(defn- format-duration
  "Pretty-print duration in seconds as `Hh Mm` (e.g. 7h 42m)."
  [seconds]
  (when (and seconds (pos? seconds))
    (let [h (quot seconds 3600)
          m (quot (mod seconds 3600) 60)]
      (format "%dh %dm" h m))))

(defn- book-line [ab]
  (let [authors   (let [a (:authors ab)]
                    (cond (string? a) a
                          (coll? a)   (str/join ", " a)
                          :else       "?"))
        narrators (some->> ab :audiobook_info :narrators (str/join ", "))
        duration  (some-> ab :audiobook_info :duration format-duration)]
    (str "- " (:title ab) " — " authors
         (when duration  (str " (" duration ")"))
         (when narrators (str "\n    narrated by " narrators))
         "\n    ISBN " (:isbn ab))))

;;; ---------------------------------------------------------------------------
;;; Commands
;;; ---------------------------------------------------------------------------

(defn cmd-login
  "Authenticate with libro.fm and cache the access token."
  [_]
  (let [{:keys [username password]} (config/load-config)
        user                        (or username (prompt "libro.fm email: "))
        pass                        (or password (prompt-secret "libro.fm password: "))]
    (when (or (str/blank? user) (str/blank? pass))
      (die! "username and password are required"))
    (let [{:keys [access_token]} (api/login user pass)]
      (when-not access_token
        (die! "login failed: no access_token in response"))
      (config/save-config! {:username user :auth-token access_token})
      (println "Logged in. Token saved to" (config/config-path)))))

(defn cmd-list
  "List audiobooks in the user's library."
  [{:keys [opts]}]
  (let [token (ensure-token!)
        books (api/fetch-library token)]
    (cond
      (:json opts) (println (json/generate-string books {:pretty true}))
      (:edn opts)  (prn books)
      :else        (do (println (count books) "audiobook(s) in library:")
                       (doseq [ab books]
                         (println (book-line ab)))))))

(defn cmd-check
  "List audiobooks in the library that have not yet been downloaded locally."
  [{:keys [opts]}]
  (let [token      (ensure-token!)
        books      (api/fetch-library token)
        downloaded (-> (config/load-state) :downloaded keys set)
        new-books  (remove #(downloaded (:isbn %)) books)]
    (cond
      (:json opts) (println (json/generate-string new-books {:pretty true}))
      (:edn opts)  (prn new-books)
      :else        (do (println (count new-books) "new audiobook(s):")
                       (doseq [ab new-books]
                         (println (book-line ab)))))))

(defn cmd-help
  "Show help message."
  [_]
  (println "libro - libro-fm-cli

A babashka CLI for interacting with libro.fm.

Commands:
  login             Authenticate and cache the access token
  list              List audiobooks in your library
  check             List audiobooks not yet downloaded locally
  help              Show this help

Environment:
  LIBRO_FM_EMAIL         Account email (overrides config)
  LIBRO_FM_PASSWORD      Account password (overrides config)
  LIBRO_FM_DOWNLOAD_DIR  Download destination (overrides config)

Config: ~/.config/libro-fm-cli/config.edn (XDG-aware)
State:  ~/.local/state/libro-fm-cli/state.edn (XDG-aware)

Use -h or --help with any command for more options."))

;;; ---------------------------------------------------------------------------
;;; CLI Dispatch
;;; ---------------------------------------------------------------------------

(def output-spec
  {:json {:coerce :boolean :desc "Output as JSON"}
   :edn  {:coerce :boolean :desc "Output as EDN"}})

(def commands
  [{:name "login" :fn cmd-login :desc "Authenticate with libro.fm" :spec {}}
   {:name "list" :fn cmd-list :desc "List library audiobooks" :spec output-spec}
   {:name "check" :fn cmd-check :desc "List undownloaded books" :spec output-spec}])

(defn- print-cmd-help [{cmd-name :name :keys [desc spec]}]
  (println (str "libro " cmd-name " - " desc))
  (when (seq spec)
    (println "\nOptions:")
    (doseq [[k {:keys [alias desc]}] (sort-by key spec)]
      (println (format "  --%-12s %s%s"
                       (name k)
                       (if alias (str "(-" (name alias) ") ") "")
                       (or desc ""))))))

(defn- wrap-help [{cmd-name :name cmd-fn :fn :keys [desc spec]}]
  (let [spec-with-help (assoc spec :help {:alias :h :coerce :boolean :desc "Show this help"})]
    {:cmds [cmd-name]
     :fn   (fn [{:keys [opts] :as m}]
             (if (:help opts)
               (print-cmd-help {:name cmd-name :desc desc :spec spec})
               (cmd-fn m)))
     :spec spec-with-help}))

(def dispatch-table
  (into [{:cmds [] :fn cmd-help}
         {:cmds ["help"] :fn cmd-help}]
        (map wrap-help commands)))

(defn -main [& args]
  (cli/dispatch dispatch-table args))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
