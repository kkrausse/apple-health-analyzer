(ns apple-health-analyzer.parse-worker
  (:require
   ;; [kev.duckdb :refer [db conn exec]]
   ["fflate" :as fflate]
   [promesa.core :as p]
   [clojure.string :as str]
   [net.cgrand.xforms :as x]
   ["apache-arrow" :as apache-arrow]
   ))

(defn klog [& args]
  (apply js/console.log (clj->js args)))

(defn unzipf [zipf fname]
  (let [arraybufp (p/deferred)]
    (p/let [fab (.arrayBuffer zipf)
            ziparray (js/Uint8Array. fab)
            uzr (fflate/Unzip. (fn [stream]
                                 (when (str/includes? (.. stream -name)
                                                      fname)
                                   (doto stream
                                     (aset "ondata"
                                           (fn reading-it [err dat final]
                                             (if err
                                               (do (klog "error unzip" err)
                                                   (p/reject! arraybufp err))
                                               (p/resolve! arraybufp dat))
                                             ))
                                     (.start))
                                   (klog "attach ondata to stream" stream))))
            _ (doto uzr
                (.register fflate/UnzipInflate)
                )
            _ (.push uzr ziparray)]
      arraybufp)))

(def str-chunk-size (* 1024 1024 10))

(defn arrayb->str-chunks [arrayb]
  (let [chunk-size str-chunk-size]
    {:chunk-count (js/Math.floor (/ (.-length arrayb) chunk-size))
     :chunks
     (->> (repeat nil)
          (map-indexed (fn [i _]
                         (let [p (* i chunk-size)
                               p' (* (inc i) chunk-size)]
                           (when (< p (.-length arrayb))
                             (fflate/strFromU8 (.subarray arrayb
                                                          p
                                                          p'))))))
          (take-while some?))}))

(def health-schema
  [[:type (apache-arrow/Utf8.)]
   [:line_num (apache-arrow/Utf8.)]
   [:a_device (apache-arrow/Utf8.)]
   [:a_creationDate (apache-arrow/Utf8.)]
   [:a_sourceVersion (apache-arrow/Utf8.)]
   [:a_endDate (apache-arrow/Utf8.)]
   [:a_value (apache-arrow/Utf8.)]
   [:a_startDate (apache-arrow/Utf8.)]
   [:a_sourceName (apache-arrow/Utf8.)]
   [:a_type (apache-arrow/Utf8.)]
   [:a_workoutActivityType (apache-arrow/Utf8.)]
   [:a_totalDistance (apache-arrow/Utf8.)]
   [:a_duration (apache-arrow/Utf8.)]
   ])

(defn transpose-rows [ks coll]
  (->> coll
       (reduce (fn [xms x]
                 (mapv
                  (fn [k v]
                    (conj v (get x k)))
                  ks xms))
               (mapv
                (fn [k] [])
                ks))
       (map vector ks)
       (into {})
       ))

(def convert-status (atom {}))

(defn chunks->arrow [& {:keys [chunks on-table]}]
  (->> chunks
       (map #(-> %
                 (str "<end>")
                 (str/split-lines)))
       (cons [""])
       (transduce
        (comp
         (x/partition 2 1)
         (mapcat (fn [[a b]]
                   (let [t (last a)
                         h (first b)]
                     (assoc b 0
                            (str (->> t
                                      (re-matches #"(.*)<end>")
                                      (second))
                                 h)))))
         (map-indexed vector)
         (keep (fn [[idx line]]
                 (let [[thing record attrs]
                       (re-matches #"^\s+<(Record|Workout) (.+)>\s*$" line)]
                   (some->>  attrs
                             (re-seq #"(\S+)=\"([^\\\"]+)\"")
                             (into {:type record
                                    :line_num idx}
                               (map (fn [[_ k v]]
                                      [(keyword (str "a_" k)) v])))))))
         (map-indexed
          (fn [i x]
            (when (zero? (mod i 10000))
              ;; (klog "did " i)
              )
            x))
         (filter (every-pred :a_startDate :a_endDate :a_sourceName
                            ;; (comp #(str/starts-with? % "2024") :a_startDate)
                             ))
         (partition-all 10000)
         (map
          (fn [rows]
            (let [{:keys [schema rows]} {:schema health-schema :rows rows}]
              (let [trows (transpose-rows (mapv first schema)
                                          rows)]
                (apache-arrow/Table.
                 (->> schema
                      (map (fn [[cname ctype]]
                             [(name cname)
                              (apache-arrow/vectorFromArray
                               (get trows cname)
                               ctype)]))
                      (into {})
                      (clj->js)))))))
         )
        (completing
         (fn [t nt]
           (when on-table
             (on-table (apache-arrow/tableToIPC nt)))
           #_
           (if t
             (.concat t nt)
             nt)
           ))
        nil

        ) ;;xform
       #_
       ((fn [table]
          (klog "inerting!!" table)
          table
          (p/let [conn conn
                  _ (exec "drop table if exists health;")]
            ^js
            (.insertArrowTable conn
                               table
                               (clj->js {:name "health"
                                         :create true})))))
       (time)
       ))

(defn ^:export init []
  (js/self.addEventListener "message"
    (fn [^js e]
      (let [method (.. e -data -method)
            handle (.. e -data -handle)
            stream-msg (fn [x]
                         (js/postMessage
                          (clj->js
                           {:handle handle
                            :method method
                            :done   false
                            :r      x})))]

        (->  (try (case method
                    "loadf-stream"
                    (p/let [zipf (.. e -data -file)
                            _ (stream-msg {:status "unzipping export.xml"})
                            zipb (unzipf zipf "export.xml")
                            {:keys [chunk-count chunks]} (arrayb->str-chunks zipb)
                            inserted? (->> chunks
                                           (map-indexed (fn [i x]
                                                          ;; NOTE: lazy seq makes this suitable for calculating status
                                                          (stream-msg {:status (str "parsed file chunk " (inc i) " of " chunk-count
                                                                                    ". (" (js/Math.floor (/ (.-length zipb) (* 1024 1024))) "MB total)")})
                                                          x))
                                           (chunks->arrow :on-table
                                                          (fn [ipc-table]
                                                            (stream-msg
                                                             {:ipc-table ipc-table}))
                                                          :chunks))]
                      (klog "unzipped")
                      nil)
                    "convert-status"
                    @convert-status

                    #_#_
                    "duck-exec"
                    (p/let [x (exec (.. e -data -q))]
                      (->> x
                           (.toArray)
                           (map #(.toJSON %))))
                    )
                  (catch js/Error e
                    (p/rejected e)))
             (p/finally
               (fn [r e]
                 (klog "posting response")
                 (js/postMessage
                  (clj->js
                   {:handle handle
                    :method method
                    :done   true
                    :e      e
                    :r      r}))))))))


  (klog "worker loaded")
  )
