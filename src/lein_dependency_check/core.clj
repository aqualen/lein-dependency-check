(ns lein-dependency-check.core
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]])
  (:import (org.owasp.dependencycheck Engine)
           (org.owasp.dependencycheck.utils Settings Settings$KEYS)
           (org.owasp.dependencycheck.data.nvdcve CveDB)
           (org.owasp.dependencycheck.reporting ReportGenerator ReportGenerator$Format)
		   (org.apache.log4j PropertyConfigurator)))

(defonce SOURCE_DIR       "src")
(defonce LOG_CONF_FILE    "log4j.properties")

(defn reconfigure-log4j
  "Reconfigures log4j from a log4j.properties file"
  []
  (let [config-file (io/file SOURCE_DIR LOG_CONF_FILE)]
    (when (.exists config-file)
	   (prn "Reconfiguring log4j")
	   (PropertyConfigurator/configure (.getPath config-file)))))

(defn- target-files
  "Selects the files to be scanned"
  [project-classpath]
  (->> project-classpath
       (filter (partial re-find #"\.jar$"))
       (map io/file)))

(defn- scan-files
  "Scans the specified files and returns the engine used to scan"
  [files {:keys [properties-file suppression-file]}]
  (let [settings (Settings.)
        _ (when (.exists (io/as-file suppression-file))
            (.setString settings Settings$KEYS/SUPPRESSION_FILE suppression-file))
        _ (when properties-file
            (.mergeProperties settings properties-file))
        engine (Engine. settings)]
    (prn "Scanning" (count files) "file(s)...")
    (doseq [file files]
      (prn "Scanning file" (.getCanonicalPath file))
      (.scan engine file))
    (prn "Done.")

    engine))

(defn- analyze-files
  "Analyzes the files scanned by the specified engine and returns the engine"
  [engine]
  (prn "Analyzing dependencies...")
  (.analyzeDependencies engine)
  (prn "Done.")

  engine)


(defn- write-report
  [engine report-name output-format output-directory]
  (doseq [format output-format]
    (.writeReports engine report-name output-directory format))
  engine)

(defn- handle-vulnerabilities [engine {:keys [log throw]}]
  (.close engine)
  (when-let [vulnerable-dependencies (->> (.getDependencies engine)
                                          (filter #((complement empty?) (.getVulnerabilities %)))
                                          (map (fn [dep] {:dependency dep
                                                          :vulnerabilities (.getVulnerabilities dep)}))
                                          seq)]
    (when log
      (doall (map #(prn "Vulnerable Dependency:" (.toString %)) vulnerable-dependencies)))
    (when throw
      (throw (ex-info "Vulnerable Dependencies!" {:vulnerable vulnerable-dependencies}))))
  engine)


(defn main
  "Scans the JAR files found on the class path and creates a vulnerability report."
  [project-classpath project-name config]
  (reconfigure-log4j)
  (let [{:keys [output-format output-directory]} config
        output-format (mapv name output-format)
        output-target (io/file output-directory)]
    (-> project-classpath
        target-files
        (scan-files config)
        analyze-files
        (write-report project-name output-format output-target)
        (handle-vulnerabilities config))))
