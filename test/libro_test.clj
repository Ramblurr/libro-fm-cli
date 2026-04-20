(ns libro-test
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [libro]
   [libro.api :as api]
   [libro.config :as config]))

(import
 '[java.io ByteArrayInputStream ByteArrayOutputStream]
 '[java.util.zip ZipEntry ZipOutputStream])

(defn- make-zip-bytes
  [entries]
  (with-open [baos (ByteArrayOutputStream.)
              zos  (ZipOutputStream. baos)]
    (doseq [[path content] entries]
      (.putNextEntry zos (ZipEntry. path))
      (.write zos (.getBytes content "UTF-8"))
      (.closeEntry zos))
    (.finish zos)
    (.toByteArray baos)))

(defn- read-json-safe [s]
  (try
    (json/parse-string s true)
    (catch Exception _ ::invalid-json)))

(defn- read-edn-safe [s]
  (try
    (edn/read-string s)
    (catch Exception _ ::invalid-edn)))

(defn- slurp-safe [path]
  (if (fs/exists? path)
    (slurp (str path))
    ::missing))

(defn- json-response
  [data]
  {:status 200
   :body   (json/generate-string data)})

(defn- binary-response
  [bytes]
  {:status 200
   :body   (ByteArrayInputStream. bytes)})

(defn- die-as-ex-info
  ([msg]
   (throw (ex-info msg {:code 1})))
  ([msg code]
   (throw (ex-info msg {:code code}))))

(defn- ex-info-with-message?
  [re f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo ex
      (boolean (re-find re (.getMessage ex))))))

(deftest namespaces-load
  (testing "entry namespace loads"
    (is (some? (find-ns 'libro))))
  (testing "api namespace loads"
    (is (some? (find-ns 'libro.api))))
  (testing "config namespace loads"
    (is (some? (find-ns 'libro.config)))))

(deftest config-paths-include-app-name
  (is (re-find #"libro-fm-cli" (config/config-path)))
  (is (string? (config/config-path))))

(deftest api-constants
  (is (= "https://libro.fm" api/base-url))
  (is (string? api/user-agent)))

(deftest commands-include-get-and-machine-readable-output
  (is (= #{"files" "get" "list" "login"}
         (set (map :name libro/commands))))
  (doseq [{:keys [name spec]} libro/commands]
    (is (contains? spec :json) (str name " should accept --json"))
    (is (contains? spec :edn) (str name " should accept --edn")))
  (is (= {:coerce :string
          :desc   "Audio format: m4b or mp3. Defaults to m4b with mp3 fallback"}
         (get-in (some #(when (= "get" (:name %)) %) libro/commands)
                 [:spec :format])))
  (is (= :string
         (get-in (some #(when (= "files" (:name %)) %) libro/commands)
                 [:coerce :isbn]))))

(deftest help-text-reflects-current-command-set
  (let [out (with-out-str (libro/-main "help"))]
    (is (.contains out "get"))
    (is (.contains out "files ISBN"))
    (is (not (.contains out "check")))
    (is (not (.contains out "LIBRO_FM_DOWNLOAD_DIR")))
    (is (not (.contains out "State:")))))

(deftest login-can-emit-json
  (with-redefs [config/load-config  (fn [] {:username "reader@example.com"
                                            :password "secret"})
                api/login           (fn [username password]
                                      (is (= "reader@example.com" username))
                                      (is (= "secret" password))
                                      {:access_token "token-123"})
                config/save-config! identity
                config/config-path  (fn [] "/tmp/libro-config.edn")]
    (let [out  (with-out-str (libro/-main "login" "--json"))
          data (read-json-safe out)]
      (is (map? data))
      (is (= {:status      "ok"
              :username    "reader@example.com"
              :config-path "/tmp/libro-config.edn"}
             data)))))

(deftest get-defaults-to-m4b-and-downloads-pdf-extras
  (let [tmp-dir    (fs/create-temp-dir {:prefix "libro-get-test-"})
        output-dir (str (fs/path tmp-dir "book-output"))
        m4b-url    "https://downloads.example.test/book.m4b?response-content-disposition=attachment%3B+filename%3D%22Test+Book.m4b%22"
        pdf-url    "https://downloads.example.test/booklet.pdf"
        mp3-part   (make-zip-bytes {"track01.mp3" "audio-1"})
        m4b-bytes  (.getBytes "m4b-bytes" "UTF-8")
        pdf-bytes  (.getBytes "pdf-bytes" "UTF-8")]
    (try
      (with-redefs [libro/ensure-token! (fn [] "token-123")
                    api/fetch-library   (fn [_]
                                          [{:isbn           123
                                            :title          "Test Book"
                                            :authors        ["Test Author"]
                                            :audiobook_info {:pdf_extras [{:filename "booklet.pdf"}]}}])
                    http/get            (fn [url opts]
                                          (cond
                                            (= "https://libro.fm/api/v10/audiobooks/123/packaged_m4b" url)
                                            (json-response {:url m4b-url})

                                            (= "https://libro.fm/api/v10/library/123/pdf_extra_url" url)
                                            (json-response {:pdf_url pdf-url})

                                            (= "https://libro.fm/api/v10/download-manifest" url)
                                            (do
                                              (is (= {"isbn" "123"} (:query-params opts)))
                                              (json-response {:parts  [{:url "https://downloads.example.test/part-1.zip"}]
                                                              :tracks []}))

                                            (= m4b-url url)
                                            (binary-response m4b-bytes)

                                            (= pdf-url url)
                                            (binary-response pdf-bytes)

                                            (= "https://downloads.example.test/part-1.zip" url)
                                            (binary-response mp3-part)

                                            :else
                                            (throw (ex-info "unexpected url" {:url url :opts opts}))))]
        (let [out  (with-out-str (libro/-main "get" "123" output-dir "--edn"))
              data (read-edn-safe out)]
          (is (= "m4b-bytes"
                 (slurp-safe (fs/path output-dir "Test Book.m4b"))))
          (is (= "pdf-bytes"
                 (slurp-safe (fs/path output-dir "booklet.pdf"))))
          (is (= ::missing
                 (slurp-safe (fs/path output-dir "track01.mp3"))))
          (is (map? data))
          (is (= {:isbn        "123"
                  :title       "Test Book"
                  :format      "m4b"
                  :output-dir  output-dir
                  :audio-files [(str (fs/path output-dir "Test Book.m4b"))]
                  :pdf-files   [(str (fs/path output-dir "booklet.pdf"))]}
                 data))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest get-falls-back-to-mp3-and-downloads-pdf-extras-when-m4b-unavailable
  (let [tmp-dir    (fs/create-temp-dir {:prefix "libro-get-test-"})
        output-dir (str (fs/path tmp-dir "book-output"))
        zip-part   (make-zip-bytes {"track01.mp3" "audio-1"})
        pdf-url    "https://downloads.example.test/booklet.pdf"
        pdf-bytes  (.getBytes "pdf-bytes" "UTF-8")]
    (try
      (with-redefs [libro/ensure-token! (fn [] "token-123")
                    api/fetch-library   (fn [_]
                                          [{:isbn           123
                                            :title          "Test Book"
                                            :authors        ["Test Author"]
                                            :audiobook_info {:pdf_extras [{:filename "booklet.pdf"}]}}])
                    http/get            (fn [url opts]
                                          (cond
                                            (= "https://libro.fm/api/v10/audiobooks/123/packaged_m4b" url)
                                            {:status 404 :body ""}

                                            (= "https://libro.fm/api/v10/download-manifest" url)
                                            (do
                                              (is (= {"isbn" "123"} (:query-params opts)))
                                              (json-response {:parts  [{:url "https://downloads.example.test/part-1.zip"}]
                                                              :tracks []}))

                                            (= "https://libro.fm/api/v10/library/123/pdf_extra_url" url)
                                            (json-response {:pdf_url pdf-url})

                                            (= "https://downloads.example.test/part-1.zip" url)
                                            (binary-response zip-part)

                                            (= pdf-url url)
                                            (binary-response pdf-bytes)

                                            :else
                                            (throw (ex-info "unexpected url" {:url url :opts opts}))))]
        (let [out  (with-out-str (libro/-main "get" "123" output-dir "--edn"))
              data (read-edn-safe out)]
          (is (= "audio-1"
                 (slurp-safe (fs/path output-dir "track01.mp3"))))
          (is (= "pdf-bytes"
                 (slurp-safe (fs/path output-dir "booklet.pdf"))))
          (is (map? data))
          (is (= {:isbn        "123"
                  :title       "Test Book"
                  :format      "mp3"
                  :output-dir  output-dir
                  :audio-files [(str (fs/path output-dir "track01.mp3"))]
                  :pdf-files   [(str (fs/path output-dir "booklet.pdf"))]}
                 data))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest get-errors-when-explicit-m4b-is-unavailable
  (let [tmp-dir    (fs/create-temp-dir {:prefix "libro-get-test-"})
        output-dir (str (fs/path tmp-dir "book-output"))
        zip-part   (make-zip-bytes {"track01.mp3" "audio-1"})]
    (try
      (with-redefs [libro/die!          die-as-ex-info
                    libro/ensure-token! (fn [] "token-123")
                    api/fetch-library   (fn [_]
                                          [{:isbn 123 :title "Test Book" :authors ["Test Author"]}])
                    http/get            (fn [url _]
                                          (cond
                                            (= "https://libro.fm/api/v10/audiobooks/123/packaged_m4b" url)
                                            {:status 404 :body ""}

                                            (= "https://libro.fm/api/v10/download-manifest" url)
                                            (json-response {:parts  [{:url "https://downloads.example.test/part-1.zip"}]
                                                            :tracks []})

                                            (= "https://downloads.example.test/part-1.zip" url)
                                            (binary-response zip-part)

                                            :else
                                            (throw (ex-info "unexpected url" {:url url}))))]
        (is (ex-info-with-message?
             #"requested format m4b is not available"
             #(libro/-main "get" "123" output-dir "--format" "m4b"))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest get-errors-when-explicit-mp3-is-unavailable
  (let [tmp-dir    (fs/create-temp-dir {:prefix "libro-get-test-"})
        output-dir (str (fs/path tmp-dir "book-output"))
        m4b-url    "https://downloads.example.test/book.m4b?response-content-disposition=attachment%3B+filename%3D%22Test+Book.m4b%22"
        m4b-bytes  (.getBytes "m4b-bytes" "UTF-8")]
    (try
      (with-redefs [libro/die!          die-as-ex-info
                    libro/ensure-token! (fn [] "token-123")
                    api/fetch-library   (fn [_]
                                          [{:isbn 123 :title "Test Book" :authors ["Test Author"]}])
                    http/get            (fn [url _]
                                          (cond
                                            (= "https://libro.fm/api/v10/audiobooks/123/packaged_m4b" url)
                                            (json-response {:url m4b-url})

                                            (= "https://libro.fm/api/v10/download-manifest" url)
                                            (json-response {:parts [] :tracks []})

                                            (= m4b-url url)
                                            (binary-response m4b-bytes)

                                            :else
                                            (throw (ex-info "unexpected url" {:url url}))))]
        (is (ex-info-with-message?
             #"requested format mp3 is not available"
             #(libro/-main "get" "123" output-dir "--format" "mp3"))))
      (finally
        (fs/delete-tree tmp-dir)))))

(deftest files-reports-m4b-mp3-and-pdf-availability
  (with-redefs [libro/ensure-token! (fn [] "token-123")
                api/fetch-library   (fn [_]
                                      [{:isbn           123
                                        :title          "Test Book"
                                        :authors        ["Test Author"]
                                        :audiobook_info {:duration    13220
                                                         :track_count 16
                                                         :size_bytes  105403775
                                                         :narrators   ["Dean Atta"]
                                                         :pdf_extras  [{:filename "booklet.pdf"}]}}])
                http/get            (fn [url opts]
                                      (cond
                                        (= "https://libro.fm/api/v10/audiobooks/123/packaged_m4b" url)
                                        (json-response {:url "https://downloads.example.test/book.m4b?response-content-disposition=attachment%3B+filename%3D%22Test+Book.m4b%22"})

                                        (= "https://libro.fm/api/v10/download-manifest" url)
                                        (do
                                          (is (= {"isbn" "123"} (:query-params opts)))
                                          (json-response {:parts  [{:url "https://downloads.example.test/part-1.zip"}
                                                                   {:url "https://downloads.example.test/part-2.zip"}]
                                                          :tracks [{:number 1 :chapter_title "One"}
                                                                   {:number 2 :chapter_title "Two"}]}))

                                        :else
                                        (throw (ex-info "unexpected url" {:url url :opts opts}))))]
    (let [out  (with-out-str (libro/-main "files" "123" "--edn"))
          data (read-edn-safe out)]
      (is (= {:isbn             "123"
              :title            "Test Book"
              :preferred-format "m4b"
              :m4b              {:available true
                                 :filename  "Test Book.m4b"}
              :mp3              {:available   true
                                 :part-count  2
                                 :track-count 2
                                 :size-bytes  105403775}
              :pdfs             ["booklet.pdf"]
              :duration-seconds 13220
              :narrators        ["Dean Atta"]}
             data)))))

(deftest files-reports-mp3-preferred-when-m4b-is-unavailable
  (with-redefs [libro/ensure-token! (fn [] "token-123")
                api/fetch-library   (fn [_]
                                      [{:isbn           123
                                        :title          "Test Book"
                                        :authors        ["Test Author"]
                                        :audiobook_info {:duration    13220
                                                         :track_count 16
                                                         :size_bytes  105403775
                                                         :narrators   ["Dean Atta"]
                                                         :pdf_extras  []}}])
                http/get            (fn [url opts]
                                      (cond
                                        (= "https://libro.fm/api/v10/audiobooks/123/packaged_m4b" url)
                                        {:status 404 :body ""}

                                        (= "https://libro.fm/api/v10/download-manifest" url)
                                        (do
                                          (is (= {"isbn" "123"} (:query-params opts)))
                                          (json-response {:parts  [{:url "https://downloads.example.test/part-1.zip"}]
                                                          :tracks [{:number 1 :chapter_title "One"}]}))

                                        :else
                                        (throw (ex-info "unexpected url" {:url url :opts opts}))))]
    (let [out  (with-out-str (libro/-main "files" "123" "--edn"))
          data (read-edn-safe out)]
      (is (= {:isbn             "123"
              :title            "Test Book"
              :preferred-format "mp3"
              :m4b              {:available false
                                 :filename  nil}
              :mp3              {:available   true
                                 :part-count  1
                                 :track-count 1
                                 :size-bytes  105403775}
              :pdfs             []
              :duration-seconds 13220
              :narrators        ["Dean Atta"]}
             data)))))
