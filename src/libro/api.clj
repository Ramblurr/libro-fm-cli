(ns libro.api
  "HTTP client for the libro.fm JSON API.

   Endpoints are observed from the jedwards1230/libro-client reference
   and the public mobile app. Auth is OAuth 2.0 password grant; every
   non-login request needs a Bearer token and the mobile okhttp User-Agent."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.java.io :as io]))

(def base-url "https://libro.fm")

;; libro.fm's edge (awselb/2.0) rejects requests that don't look like the
;; official mobile app. The burntcookie90/librofm-downloader reference ships
;; these values in its Dockerfile. If logins start failing with an empty-body
;; 401, bump these to match a current app build.
(def app-version "7.34.8")
(def user-agent  "okhttp/5.3.2")

(defn- base-headers []
  {"Content-Type"     "application/json"
   "User-Agent"       user-agent
   "X-LibroFm-AppVer" app-version})

(defn- headers
  ([]       (base-headers))
  ([token]  (assoc (base-headers) "Authorization" (str "Bearer " token))))

(defn- parse-body [resp]
  (let [body (:body resp)]
    (cond
      (nil? body)    {}
      (string? body) (json/parse-string body true)
      :else          (json/parse-stream (io/reader body) true))))

(defn- check-response! [{:keys [status body] :as resp} ctx]
  (when (or (nil? status) (>= status 400))
    (let [parsed (try (parse-body resp) (catch Exception _ nil))
          msg    (or (:error parsed)
                     (:error_description parsed)
                     (str "HTTP " status))]
      (throw (ex-info (str "libro.fm API error: " msg)
                      (merge ctx {:status   status
                                  :body     parsed
                                  :raw-body (if (string? body) body (str body))})))))
  resp)

(defn login
  "POST /oauth/token with password grant. Returns {:access_token ...}."
  [username password]
  (-> (http/post (str base-url "/oauth/token")
                 {:headers (headers)
                  :body    (json/generate-string
                            {:grant_type "password"
                             :username   username
                             :password   password})
                  :throw   false})
      (check-response! {:op :login :username username})
      parse-body))

(defn fetch-library-page
  "GET /api/v10/library?page=N. Returns {:page :total_pages :audiobooks [...] :tags [...]}."
  [token page]
  (-> (http/get (str base-url "/api/v10/library")
                {:headers      (headers token)
                 :query-params {"page" (str page)}
                 :throw        false})
      (check-response! {:op :library :page page})
      parse-body))

(defn fetch-library
  "Fetch all library pages and return the concatenated audiobooks vector."
  [token]
  (loop [page 1
         acc  []]
    (let [{:keys [total_pages audiobooks]} (fetch-library-page token page)]
      (if (or (>= page (or total_pages 1)) (empty? audiobooks))
        (into acc audiobooks)
        (recur (inc page) (into acc audiobooks))))))

(defn fetch-download-manifest
  "GET /api/v10/download-manifest?isbn=... Returns download parts/tracks metadata."
  [token isbn]
  (-> (http/get (str base-url "/api/v10/download-manifest")
                {:headers      (headers token)
                 :query-params {"isbn" isbn}
                 :throw        false})
      (check-response! {:op :download-manifest :isbn isbn})
      parse-body))

(defn fetch-packaged-m4b
  "GET /api/v10/audiobooks/{isbn}/packaged_m4b. Returns nil when no packaged m4b exists."
  [token isbn]
  (let [resp (http/get (str base-url "/api/v10/audiobooks/" isbn "/packaged_m4b")
                       {:headers (headers token)
                        :throw   false})]
    (when (and (:status resp) (< (:status resp) 400))
      (parse-body resp))))

(defn fetch-pdf-extra-url
  "GET /api/v10/library/{isbn}/pdf_extra_url?filename=... Returns a signed PDF URL."
  [token isbn filename]
  (-> (http/get (str base-url "/api/v10/library/" isbn "/pdf_extra_url")
                {:headers      (headers token)
                 :query-params {"filename" filename}
                 :throw        false})
      (check-response! {:op :pdf-extra-url :isbn isbn :filename filename})
      parse-body))
