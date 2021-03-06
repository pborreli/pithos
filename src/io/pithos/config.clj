(ns io.pithos.config
  (:require [clj-yaml.core         :refer [parse-string]]
            [clojure.tools.logging :refer [error info debug]]
            [io.pithos.util        :refer [to-bytes]]))

(def default-logging
  {:use "io.pithos.logging/start-logging"
   :pattern "%p [%d] %t - %c - %m%n"
   :external false
   :console true
   :files  []
   :level  "info"
   :overrides {:io.pithos "debug"}})

(def default-keystore
  {:use "io.pithos.keystore/map->MapKeystore"})

(def default-bucketstore
  {:use "io.pithos.bucket/cassandra-bucket-store"})

(def default-metastore
  {:use "io.pithos.meta/cassandra-meta-store"})

(def default-blobstore
  {:use "io.pithos.blob/cassandra-blob-store"
   :max-chunk        "512k"
   :max-block-chunks 2048})

(def default-service
  {:host "127.0.0.1"
   :port 8080})

(def default-options
  {:service-uri "s3.amazonaws.com"
   :reporting true
   :server-side-encryption true
   :multipart-upload true
   :masterkey-provisioning true
   :masterkey-access true})

(defn find-ns-var
  [s]
  (try
    (let [n (namespace (symbol s))]
      (require (symbol n))
      (find-var (symbol s)))
    (catch Exception _
      nil)))

(defn instantiate
  "Find a symbol pointing to a function of a single argument and
   call it"
  [class config]
  (if-let [f (find-ns-var class)]
    (f config)
    (throw (ex-info (str "no such namespace: " class) {}))))

(defn get-instance
  [{:keys [use] :as config} target]
  (debug "building " target " with " use)
  (instantiate (-> use name symbol) config))

(defn load-path
  "Try to find a pathname, on the command line, in
   system properties or the environment and load it."
  [path]
  (-> (or path
          (System/getProperty "pithos.configuration")
          (System/getenv "PITHOS_CONFIGURATION")
          "/etc/pithos/pithos.yaml")
      slurp 
      parse-string))

(defn get-storage-classes
  [storage-classes]
  (->> (for [[storage-class blobstore] storage-classes
             :let [blobstore (-> (merge default-blobstore blobstore)
                                 (update-in [:max-chunk] to-bytes :max-chunk))]]
         [storage-class (get-instance blobstore :blobstore)])
       (reduce merge {})))

(defn get-region-stores
  [regions]
  (->> (for [[region {:keys [metastore storage-classes]}] regions
             :let [metastore (merge default-metastore metastore)]]
         [(name region)
          {:metastore       (get-instance metastore :metastore)
           :storage-classes (get-storage-classes storage-classes)}])
       (reduce merge {})))

(defn init
  [path quiet?]
  (try
    (when-not quiet?
      (println "starting with configuration: " path))
    (-> (load-path path)
        (update-in [:logging] (partial merge default-logging))
        (update-in [:logging] get-instance :logging)
        (update-in [:service] (partial merge default-service))
        (update-in [:options] (partial merge default-options))
        (update-in [:keystore] (partial merge default-keystore))
        (update-in [:keystore] get-instance :keystore)
        (update-in [:bucketstore] (partial merge default-bucketstore))
        (update-in [:bucketstore] get-instance :bucketstore)
        (update-in [:regions] get-region-stores))
    (catch Exception e
      (when-not quiet?
        (println "invalid or incomplete configuration: " (str e)))
      (error e "invalid or incomplete configuration")
      (System/exit 1))))
