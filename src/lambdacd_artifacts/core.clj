(ns lambdacd-artifacts.core
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [ring.util.response :as response]
            [compojure.route :refer :all]
            [compojure.core :refer :all])
  (:import (java.nio.file Paths)))

(defn- artifacts-root-dir [ctx]
  (let [home-dir (:home-dir (:config ctx))
        root-dir (io/file home-dir "lambdacd-artifacts")]
    (.mkdirs root-dir)
    root-dir))

(defn- file-to-build-id [f]
  (read-string (.getName f)))

(defn- find-latest-artifact [artifacts-root step-id path]
  (let [build-directories      (sort-by file-to-build-id (.listFiles (io/file artifacts-root)))
        latest-build-directory (last (filter #(.exists (io/file % step-id path)) build-directories))]
    (io/file latest-build-directory step-id)))

(defn root-path [artifacts-root build-number step-id path]
  (if (= build-number "latest")
    (find-latest-artifact artifacts-root step-id path)
    (io/file artifacts-root (str build-number) step-id)))

(defn file-result [artifacts-root build-number step-id path]
  (let [root          (root-path artifacts-root build-number step-id path)
        file-response (response/file-response path {:root (str root)})]
    (if file-response
      file-response
      (response/not-found (str "could not find " path " for build number " build-number " and step-id " step-id)))))

(defn artifact-handler-for [pipeline]
  (let [artifacts-root (artifacts-root-dir (:context pipeline))]
    (routes
      (GET "/:buildnumber/:stepid/*" [buildnumber stepid *]
        (file-result artifacts-root buildnumber stepid *)))))

(defn relative-path [base-dir file]
  (let [base-path (Paths/get (.toURI base-dir))
        file-path (Paths/get (.toURI file))]
    (.relativize base-path file-path)))

(defn- file-matches [base-dir regex-or-string]
  (if (string? regex-or-string)
    (fn [file]
      (= regex-or-string (str (relative-path base-dir file))))
    (fn [file]
      (re-matches regex-or-string (str (relative-path base-dir file))))))

(defn- find-files-matching [regex-or-string dir]
  (filter (file-matches dir regex-or-string)
          (file-seq dir)))

(defn format-step-id [step-id]
  (s/join "-" step-id))

(defn- copy-file [{step-id :step-id build-number :build-number} artifacts-root working-directory input-file]
  (let [file-name      (.getName input-file)
        output-parent  (butlast (s/split (str (relative-path (io/file working-directory) input-file)) #"/"))
        output-parts   (concat [(str build-number) (format-step-id step-id)] output-parent [file-name])
        output-file    (apply io/file artifacts-root output-parts)]
    (io/make-parents output-file)
    (io/copy input-file output-file)
    output-file))

(defn file-details [{{artifacts-path :artifacts-path-context} :config} artifacts-root-dir output-file]
    {:label (.getName output-file)
     :href  (str artifacts-path "/" (relative-path (io/file artifacts-root-dir) output-file))})

(defn publish-artifacts [args ctx cwd patterns]
  (let [working-dir    (io/file cwd)
        artifacts-root (artifacts-root-dir ctx)
        output-files   (doall (->> patterns
                                   (map #(find-files-matching % working-dir))
                                   (flatten)
                                   (filter #(not (.isDirectory %)))
                                   (sort)
                                   (map #(copy-file ctx artifacts-root working-dir %1))
                                   (flatten)))
        file-details   (map #(file-details ctx artifacts-root %) output-files)]
    {:status  :success
     :details [{:label   "Artifacts"
                :details file-details}]}))
