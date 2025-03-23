(ns apple-health-analyzer.duckdb
  (:require
   ["@duckdb/duckdb-wasm" :as duckdb]
   [promesa.core :as p]))

(defonce db
  (p/let [bundle
          (duckdb/selectBundle (clj->js
                                {:eh {
                                      :mainModule "./duckdb-eh.wasm"
                                      :mainWorker "./duckdb/duckdb-browser-eh.worker.js",
                                      },
                                 }))
          _ (js/console.log "sel bundle" bundle)
          worker (js/Worker. (.-mainWorker bundle))
          _ (js/console.log "worker created " worker)
          logger (duckdb/ConsoleLogger.)
          db (duckdb/AsyncDuckDB. logger worker)
          _ (js/console.log "made db")
          _
          (.instantiate db (.-mainModule bundle) (.-pthreadWorker bundle))
          _ (js/console.log "inst")
          conn (.connect db)
          _ (js/console.log "connected?" conn)
          ]
    (js/console.log "instantiated!")
    db))

(defonce conn (-> (p/let [d db
                          conn (.connect d)]
                    (js/console.log "connected")
                    conn)
                  (p/catch (fn [e]
                             (js/console.log "error connecting" e)))))

(defn exec [q]
  (p/let [conn conn]
    (.query conn q)))

(defn query-array [q]
  (p/let [type-mapping {"Timestamp<MICROSECOND, UTC>" #(js/Date. %)
                        "Timestamp<MICROSECOND>" #(js/Date. %)}
          r (exec q)
          k->convertf (->> r
                           (.-schema)
                           (.-fields)
                           (map (fn [field]
                                  ;; (js/console.log (-> field .-type str))
                                  [(.-name field)
                                   (-> field .-type str
                                       (type-mapping identity))]))
                           (into {}))]
    (to-array
     (into [] (map #(->> %
                         (.toJSON)
                         (js->clj)
                         (into {}
                           (map (fn [[k v]]
                                  [k ((get k->convertf k) v)])))
                         (clj->js)))
           (seq r)))))

(defn pq [q]
  ;; (p/let [x (exec q)
  ;;         res
  ;;         (->> x
  ;;              (.toArray)
  ;;              (map #(.toJSON %)))]
  ;;   (apply js/console.log (cons "duckq:" res))
  ;;   res)
  (p/let [a (query-array q)]
    (apply js/console.log (cons "duckq:" a))
    a)
  )
