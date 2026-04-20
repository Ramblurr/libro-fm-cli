#!/usr/bin/env bb
(ns libro
  "libro-fm-cli — a CLI for interacting with libro.fm."
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [libro.api :as api]
   [libro.config :as config])
  (:import
   [java.net URI URLDecoder]
   [java.util.zip ZipInputStream]))

;;; Helpers

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

(defn- authors-string [book]
  (let [a (:authors book)]
    (cond
      (string? a) a
      (coll? a)   (str/join ", " a)
      :else       "?")))

(defn- book-line [ab]
  (let [authors   (authors-string ab)
        narrators (some->> ab :audiobook_info :narrators (str/join ", "))
        duration  (some-> ab :audiobook_info :duration format-duration)]
    (str "- " (:title ab) " — " authors
         (when duration  (str " (" duration ")"))
         (when narrators (str "\n    narrated by " narrators))
         "\n    ISBN " (:isbn ab))))

(defn- add-help-to-spec [spec]
  (assoc spec :help {:alias :h :coerce :boolean :desc "Show this help"}))

(def supported-formats #{"mp3" "m4b"})

(defn- validate-output!
  [opts]
  (when (and (:json opts) (:edn opts))
    (die! "choose only one of --json or --edn" 2)))

(defn- validate-format!
  [fmt]
  (when (and fmt (not (supported-formats fmt)))
    (die! (str "unsupported format " fmt ", expected mp3 or m4b") 2)))

(defn- emit-output
  [opts data render-text]
  (validate-output! opts)
  (cond
    (:json opts) (println (json/generate-string data {:pretty true}))
    (:edn opts)  (prn data)
    :else        (render-text data)))

(defn- find-book-by-isbn [books isbn]
  (let [target (str isbn)]
    (some #(when (= target (str (:isbn %))) %) books)))

(defn- safe-target-path
  [root entry-name]
  (let [root-path   (.normalize (.toAbsolutePath (fs/path root)))
        target-path (.normalize (.toAbsolutePath (.resolve root-path entry-name)))]
    (when-not (.startsWith target-path root-path)
      (throw (ex-info "refusing to extract zip entry outside target directory"
                      {:entry entry-name :target (str target-path)})))
    target-path))

(defn- extract-zip-stream!
  [input-stream output-dir]
  (fs/create-dirs output-dir)
  (with-open [zip-stream (ZipInputStream. input-stream)]
    (loop [entry (.getNextEntry zip-stream)]
      (when entry
        (let [target (safe-target-path output-dir (.getName entry))]
          (if (.isDirectory entry)
            (fs/create-dirs target)
            (do
              (when-let [parent (.getParent target)]
                (fs/create-dirs parent))
              (with-open [out (io/output-stream (str target))]
                (io/copy zip-stream out)))))
        (.closeEntry zip-stream)
        (recur (.getNextEntry zip-stream))))))

(defn- download-part!
  [url output-dir]
  (let [{:keys [status body]} (http/get url {:as :stream :throw false})]
    (when (or (nil? status) (>= status 400))
      (throw (ex-info "download failed"
                      {:url url :status status})))
    (with-open [input body]
      (extract-zip-stream! input output-dir))))

(defn- regular-files
  [dir]
  (if (fs/exists? dir)
    (->> (file-seq (io/file dir))
         (filter #(.isFile %))
         (map #(.getPath %))
         sort
         vec)
    []))

(defn- capture-new-files
  [dir f]
  (let [before (set (regular-files dir))]
    (f)
    (->> (regular-files dir)
         (remove before)
         sort
         vec)))

(defn- parse-query-params
  [url]
  (if-let [raw-query (.getRawQuery (URI/create url))]
    (into {}
          (map (fn [pair]
                 (let [[k v] (str/split pair #"=" 2)]
                   [(URLDecoder/decode k "UTF-8")
                    (some-> v (URLDecoder/decode "UTF-8"))])))
          (str/split raw-query #"&"))
    {}))

(defn- filename-from-content-disposition
  [content-disposition]
  (some->> content-disposition
           (re-find #"filename=\"?([^\";]+)\"?")
           second))

(defn- filename-from-url
  [url default-name]
  (or (some-> (parse-query-params url)
              (get "response-content-disposition")
              filename-from-content-disposition)
      (some-> (URI/create url) .getPath fs/file-name str)
      default-name))

(defn- first-download-url
  [value]
  (cond
    (and (string? value) (re-matches #"https?://.*" value))
    value

    (map? value)
    (or (some-> value :url first-download-url)
        (some-> value :download_url first-download-url)
        (some-> value :pdf_url first-download-url)
        (some (comp first-download-url val) value))

    (sequential? value)
    (some first-download-url value)

    :else
    nil))

(defn- save-url!
  [url output-path]
  (let [{:keys [status body]} (http/get url {:as :stream :throw false})]
    (when (or (nil? status) (>= status 400))
      (throw (ex-info "download failed"
                      {:url url :status status})))
    (when-let [parent (some-> output-path fs/path fs/parent)]
      (fs/create-dirs parent))
    (with-open [input  body
                output (io/output-stream (str output-path))]
      (io/copy input output))
    (str output-path)))

(defn- pdf-extra-filenames
  [book]
  (->> book :audiobook_info :pdf_extras (map :filename) (remove str/blank?)))

(defn- download-pdf-extras!
  [token isbn book output-dir]
  (let [filenames (pdf-extra-filenames book)]
    (capture-new-files
     output-dir
     #(doseq [filename filenames]
        (let [url         (some-> (api/fetch-pdf-extra-url token isbn filename)
                                  first-download-url)
              output-path (safe-target-path output-dir filename)]
          (when-not url
            (throw (ex-info "pdf extra response missing download URL"
                            {:isbn isbn :filename filename})))
          (save-url! url output-path))))))

(defn- download-m4b!
  [token isbn book output-dir]
  (when-let [metadata (api/fetch-packaged-m4b token isbn)]
    (when-let [url (first-download-url metadata)]
      (let [default-name (str (:title book) ".m4b")
            filename     (filename-from-url url default-name)
            output-path  (safe-target-path output-dir filename)]
        {:format      "m4b"
         :audio-files [(save-url! url output-path)]}))))

(defn- download-mp3!
  [token isbn output-dir]
  (let [manifest  (api/fetch-download-manifest token isbn)
        part-urls (mapv :url (:parts manifest))]
    (when (seq part-urls)
      {:format      "mp3"
       :audio-files (capture-new-files
                     output-dir
                     #(doseq [url part-urls]
                        (download-part! url output-dir)))})))

(defn- resolve-download!
  [token isbn book output-dir requested-format]
  (let [m4b-result (delay (download-m4b! token isbn book output-dir))
        mp3-result (delay (download-mp3! token isbn output-dir))]
    (case requested-format
      "m4b" (or @m4b-result
                (die! (str "requested format m4b is not available for ISBN " isbn)))
      "mp3" (or @mp3-result
                (die! (str "requested format mp3 is not available for ISBN " isbn)))
      (or @m4b-result
          @mp3-result
          (die! (str "no downloadable format is available for ISBN " isbn))))))

(defn- inspect-m4b
  [token isbn book]
  (if-let [metadata (api/fetch-packaged-m4b token isbn)]
    (if-let [url (first-download-url metadata)]
      {:available true
       :filename  (filename-from-url url (str (:title book) ".m4b"))}
      {:available false
       :filename  nil})
    {:available false
     :filename  nil}))

(defn- inspect-mp3
  [token isbn book]
  (try
    (let [manifest   (api/fetch-download-manifest token isbn)
          parts      (:parts manifest)
          tracks     (:tracks manifest)
          size-bytes (some-> book :audiobook_info :size_bytes)]
      {:available   (boolean (seq parts))
       :part-count  (count parts)
       :track-count (count tracks)
       :size-bytes  size-bytes})
    (catch Exception _
      {:available   false
       :part-count  0
       :track-count 0
       :size-bytes  (some-> book :audiobook_info :size_bytes)})))

(defn- preferred-format
  [m4b mp3]
  (cond
    (:available m4b) "m4b"
    (:available mp3) "mp3"
    :else            nil))

(defn- book-files-info
  [token book]
  (let [isbn (str (:isbn book))
        m4b  (inspect-m4b token isbn book)
        mp3  (inspect-mp3 token isbn book)
        pdfs (vec (pdf-extra-filenames book))]
    {:isbn             isbn
     :title            (:title book)
     :preferred-format (preferred-format m4b mp3)
     :m4b              m4b
     :mp3              mp3
     :pdfs             pdfs
     :duration-seconds (some-> book :audiobook_info :duration)
     :narrators        (vec (or (some-> book :audiobook_info :narrators) []))}))

;;; Commands

(defn cmd-login
  "Authenticate with libro.fm and cache the access token."
  [{:keys [opts]}]
  (let [{:keys [username password]} (config/load-config)
        user                        (or username (prompt "libro.fm email: "))
        pass                        (or password (prompt-secret "libro.fm password: "))]
    (when (or (str/blank? user) (str/blank? pass))
      (die! "username and password are required"))
    (let [{:keys [access_token]} (api/login user pass)]
      (when-not access_token
        (die! "login failed: no access_token in response"))
      (config/save-config! {:username user :auth-token access_token})
      (emit-output opts
                   {:status      "ok"
                    :username    user
                    :config-path (config/config-path)}
                   (fn [{:keys [config-path]}]
                     (println "Logged in. Token saved to" config-path))))))

(defn cmd-list
  "List audiobooks in the user's library."
  [{:keys [opts]}]
  (let [token (ensure-token!)
        books (api/fetch-library token)]
    (emit-output opts
                 books
                 (fn [items]
                   (println (count items) "audiobook(s) in library:")
                   (doseq [ab items]
                     (println (book-line ab)))))))

(defn cmd-files
  "Show download-related file availability for one audiobook."
  [{:keys [opts]}]
  (let [{:keys [isbn]} opts]
    (when (str/blank? isbn)
      (die! "usage: libro files ISBN"))
    (let [token (ensure-token!)
          books (api/fetch-library token)
          book  (find-book-by-isbn books isbn)]
      (when-not book
        (die! (str "no book found with ISBN " isbn)))
      (let [data (book-files-info token book)]
        (emit-output opts
                     data
                     (fn [{:keys [title isbn preferred-format m4b mp3 pdfs duration-seconds narrators]}]
                       (println title)
                       (println (str "ISBN: " isbn))
                       (println)
                       (println (str "Preferred format: " (or preferred-format "none")))
                       (println (str "M4B: "
                                     (if (:available m4b)
                                       (str "available (" (:filename m4b) ")")
                                       "unavailable")))
                       (println (str "MP3: "
                                     (if (:available mp3)
                                       (str "available, " (:part-count mp3) " part(s), "
                                            (:track-count mp3) " track(s)")
                                       "unavailable")))
                       (println (str "PDF extras: "
                                     (if (seq pdfs)
                                       (str/join ", " pdfs)
                                       "none")))
                       (when duration-seconds
                         (println (str "Duration: " (format-duration duration-seconds))))
                       (when (seq narrators)
                         (println (str "Narrator"
                                       (when (> (count narrators) 1) "s")
                                       ": "
                                       (str/join ", " narrators))))))))))

(defn cmd-get
  "Download a library audiobook into the caller-supplied directory."
  [{:keys [opts]}]
  (let [{:keys [isbn dir format]} opts]
    (when (or (str/blank? isbn) (str/blank? dir))
      (die! "usage: libro get ISBN DIR"))
    (validate-format! format)
    (let [token      (ensure-token!)
          books      (api/fetch-library token)
          book       (find-book-by-isbn books isbn)
          output-dir (str (fs/path dir))]
      (when-not book
        (die! (str "no book found with ISBN " isbn)))
      (let [{:keys [format audio-files]} (resolve-download! token isbn book output-dir format)
            pdf-files                    (download-pdf-extras! token isbn book output-dir)]
        (emit-output opts
                     {:isbn        isbn
                      :title       (:title book)
                      :format      format
                      :output-dir  output-dir
                      :audio-files audio-files
                      :pdf-files   pdf-files}
                     (fn [{:keys [title output-dir format audio-files pdf-files]}]
                       (println (str "Downloaded " title " to " output-dir
                                     " as " format
                                     " (" (count audio-files) " audio file(s), "
                                     (count pdf-files) " pdf extra(s))."))))))))

(defn cmd-help
  "Show help message."
  [_]
  (println "libro - libro-fm-cli

A CLI tool for interacting with libro.fm.

Commands:
  login             Authenticate and cache the access token
  list              List audiobooks in your library
  files ISBN        Show downloadable file information for a book
  get ISBN DIR      Download an audiobook into DIR
  help              Show this help

Environment:
  LIBRO_FM_EMAIL         Account email (overrides config)
  LIBRO_FM_PASSWORD      Account password (overrides config)

Config: ~/.config/libro-fm-cli/config.edn (XDG-aware)

Use -h or --help with any command for more options."))

;;; CLI Dispatch

(def output-spec
  {:json {:coerce :boolean :desc "Output as JSON"}
   :edn  {:coerce :boolean :desc "Output as EDN"}})

(def commands
  [{:name       "login"
    :usage      "[options]"
    :args->opts []
    :desc       "Authenticate with libro.fm"
    :spec       output-spec
    :fn         cmd-login}
   {:name       "list"
    :usage      "[options]"
    :args->opts []
    :desc       "List library audiobooks"
    :spec       output-spec
    :fn         cmd-list}
   {:name       "files"
    :usage      "ISBN [options]"
    :args->opts [:isbn]
    :coerce     {:isbn :string}
    :desc       "Show downloadable file information for one audiobook"
    :spec       output-spec
    :fn         cmd-files}
   {:name       "get"
    :usage      "ISBN DIR [options]"
    :args->opts [:isbn :dir]
    :coerce     {:isbn :string :dir :string}
    :desc       "Download a library audiobook into DIR"
    :spec       (assoc output-spec
                       :format {:coerce :string
                                :desc   "Audio format: m4b or mp3. Defaults to m4b with mp3 fallback"})
    :fn         cmd-get}])

(defn- print-cmd-help [{cmd-name :name :keys [usage desc spec]}]
  (println (str "Usage: libro " cmd-name
                (when usage
                  (str " " usage))))
  (println)
  (println desc)
  (when (seq spec)
    (println)
    (println "Options:")
    (println (cli/format-opts {:spec (add-help-to-spec spec)}))))

(defn- wrap-help [{cmd-name :name cmd-fn :fn :keys [usage desc spec args->opts coerce]}]
  {:cmds       [cmd-name]
   :fn         (fn [{:keys [opts] :as m}]
                 (if (:help opts)
                   (print-cmd-help {:name  cmd-name
                                    :usage usage
                                    :desc  desc
                                    :spec  spec})
                   (cmd-fn m)))
   :args->opts args->opts
   :coerce     coerce
   :spec       (add-help-to-spec spec)})

(def dispatch-table
  (into [{:cmds [] :fn cmd-help}
         {:cmds ["help"] :fn cmd-help}]
        (map wrap-help commands)))

(defn -main [& args]
  (cli/dispatch dispatch-table args))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
