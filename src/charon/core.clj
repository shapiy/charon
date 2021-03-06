(ns charon.core
  (:require [charon.export :as export]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.tools.logging :as log]
            [charon.utils :as utils])
  (:gen-class)
  (:import (org.slf4j LoggerFactory Logger)
           (ch.qos.logback.classic Level)))

(def run export/run)

(def not-blank (complement string/blank?))

(def cli-options
  [["-o" "--output OUTPUT" "Output directory"
    :missing "Output directory (--output) is required"]
   [nil "--space-url SPACE_URL" "Confluence space URL"
    :id :space-url
    :parse-fn utils/url-decode]
   [nil "--page-url PAGE_URL" "Confluence page URL"
    :id :page-url
    :parse-fn utils/url-decode]
   ["-u" "--username USERNAME" "Username"
    :validate-fn not-blank
    :validate-msg "Username cannot be blank"]
   ["-p" "--password PASSWORD" "Password"
    :validate-fn not-blank
    :validate-msg "Password cannot be blank"]
   ["-d" "--debug"]
   ["-h" "--help"]])

(defn- enrich-opts
  "Enrich options map with derived data.

  - Check if either `space-url` or `page-url` is provided.
  - Extract Confluence URL, `confluence-url`.
  - Extract Confluence space key, `space`.
  - Extract Confluence page name, `page`.

  Return `nil` when data cannot be parsed.

  Example:
  ```
  (enrich-opts {:space-url \"https://wiki.xandr.com/display/sdk/Home\"})
  =>
  {:space-url \"https://wiki.xandr.com/display/sdk/Home\",
   :confluence-url \"https://wiki.xandr.com\",
   :space \"sdk\"}
  ```"
  [{:keys [space-url page-url] :as m}]
  (let [content-url (or space-url page-url)]
    (cond
      (every? identity [space-url page-url])
      {:exit-message "Should provide only one of --space-url or --page-url"}

      (every? nil? [space-url page-url])
      {:exit-message "Should provide at least --space-url or --page-url"}

      (not (string/includes? content-url "/display/"))
      {:exit-message "Unknown content URL format"}

      ;; TODO: Rename page to page-title and space to space-key for disambiguation.
      :else (let [[confluence-url path] (string/split content-url #"/display/" 2)
                  [space page & _] (string/split path #"/" 3)
                  res (assoc m :confluence-url confluence-url :space space)]
              (cond
                (and page-url (not page)) {:exit-message "Unknown page URL format"}
                (and page-url page) {:options (assoc res :page page)}
                :else {:options res})))))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with a error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) {:exit-message summary :ok? true}
      errors {:exit-message (string/join \newline errors)}
      :else (enrich-opts options))))

(defn exit [status msg]
  (let [level (if (= status 0) :info :error)]
    (log/log level msg)
    (System/exit status)))

(defn enable-debug []
  (.setLevel
    (LoggerFactory/getLogger (Logger/ROOT_LOGGER_NAME)) Level/DEBUG))

(defn -main
  [& args]
  (log/infof "Running with arguments: %s" args)
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (try
        (when (:debug options)
          (enable-debug))
        (time (run options))
        (log/info "Done")
        (catch Exception e
          (if (:debug options)
            (throw e)
            (exit 2 (format "Failed: %s" (.getMessage e)))))))))
