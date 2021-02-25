(ns npm-force-resolutions.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [cljs-http.client :as http]
            [cljs.core.async :as async :refer [<! >!]]
            [clojure.string :as string]
            [cognitect.transit :as t]
            [xmlhttprequest :refer [XMLHttpRequest]]
            [child_process :refer [execSync]]))

(set! js/XMLHttpRequest XMLHttpRequest)

(defn node-slurp [path]
  (let [fs (nodejs/require "fs")]
    (.readFileSync fs path "utf8")))

(defn node-spit [path data]
  (let [fs (nodejs/require "fs")]
    (.writeFileSync fs path data)))

(defn read-json [path]
  (t/read (t/reader :json) (string/replace (node-slurp path) #"\^" "\\\\^")))

(defn fetch-resolved-resolution [registry-url key version]
  (go
    (let [response (<! (http/get (str registry-url key "/" version)))
          dist (get-in response [:body :dist])]
      {key {"version" version
            "resolved" (get dist :tarball)
            "integrity" (get dist :integrity)}})))

(defn wait-all [callbacks]
  (go
    (let [merged (async/merge callbacks)]
        (loop [result {}]
          (if (= (count result) (count callbacks))
            result
            (recur (merge result (<! merged))))))))

(defn get-registry-url []
  (.trim (.toString (execSync "npm config get registry"))))

(defn find-resolutions [folder]
  (go
    (let [package-json (read-json (str folder "/package.json"))
          resolutions (get package-json "resolutions")
          registry-url (get-registry-url)
          callbacks (map
                      (fn [[k v]] (fetch-resolved-resolution registry-url k v))
                      (seq resolutions))
          [resolved-resolutions timeout_] (async/alts!
                                            [(wait-all callbacks)
                                             (async/timeout 8000)])
          ]
      (if resolved-resolutions
        resolved-resolutions
        (throw (js/Error. "Timeout trying to fetch resolutions from npm"))))))

; Source: https://stackoverflow.com/questions/1676891/mapping-a-function-on-the-values-of-a-map-in-clojure
(defn map-vals [f m]
  (apply merge (map (fn [[k v]] {k (f k v)}) m)))

(defn update-on-requires [resolutions dependency]
  (update dependency "requires"
          (fn [requires]
            (map-vals #((fn [key version]
                          (if (contains? resolutions key)
                            (get-in resolutions [key, "version"])
                            version)) %1 %2) requires))))

(defn add-dependencies [resolutions dependency]
  (let [required-dependencies (keys (get dependency "requires"))
        new-deps (select-keys resolutions required-dependencies)
        dependencies (merge new-deps
                            (get dependency "dependencies" {}))]
    (merge dependency {"dependencies" dependencies})))

(defn fix-existing-dependency [resolutions key dependency]
  (if (contains? resolutions key)
    (conj (dissoc dependency "version" "resolved" "integrity" "bundled") (get resolutions key))
    dependency))

(defn order-map [target]
  (into (sorted-map-by (fn [key1 key2]
                         (compare [key1]
                                  [key2]))) target))

(defn sort-or-remove-map [key dependency]
  (if (map? (get dependency key))
    (update dependency key order-map)
    (dissoc dependency key)))

(declare patch-all-dependencies)

(defn patch-dependency [resolutions key dependency]
  (if (contains? dependency "requires")
    (->> dependency
         (add-dependencies resolutions)
         (update-on-requires resolutions)
         (fix-existing-dependency resolutions key)
         (patch-all-dependencies resolutions)
         (sort-or-remove-map "dependencies")
         (sort-or-remove-map "requires"))
    (fix-existing-dependency resolutions key dependency)))

(defn patch-all-dependencies [resolutions package-lock]
  (update package-lock "dependencies"
          (fn [dependencies]
            (map-vals #(patch-dependency resolutions %1 %2) dependencies))))

(defn update-package-lock [folder]
  (go
    (let [package-lock (read-json (str folder "/package-lock.json"))
          resolutions (<! (find-resolutions folder))]
      (->> (patch-all-dependencies resolutions package-lock)
          (sort-or-remove-map "dependencies")))))

(defn indent-json [json]
  (let [json-format (nodejs/require "json-format")]
    (-> (.parse js/JSON json)
        (json-format (js-obj "type" "space"
                             "size" 2))
        (string/replace #"\\\\\^" "^")
        (string/replace #" +\n" ""))))

(defn main [& args]
  (go
    (let [folder (or (first args) ".")
          package-lock (<! (update-package-lock folder))
          package-lock-json (t/write (t/writer :json-verbose) package-lock)]
      (node-spit (str folder "/package-lock.json") (indent-json package-lock-json)))))

(set! *main-cli-fn* main)