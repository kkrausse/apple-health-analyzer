(ns apple-health-analyzer.main
 (:require
   [promesa.core :as p]
   [reagent.core :as rg]
   [clojure.core.async :as a]
   ["react-dom/client" :as react-client]
   ["fflate" :as fflate]
   ["@duckdb/duckdb-wasm" :as duckdb]
   [clojure.string :as str]
   [apple-health-analyzer.duckdb :as kduck]
   ["react" :as react]
   [net.cgrand.xforms :as x]))

(def main-table-name "health")
(def main-parquet-file-name "health.parquet")

(defonce state (rg/atom {:uploaded-files nil}))

(defn klog [& args]
  (apply js/console.log (clj->js args)))

(defn load-file! [files]
  (swap! state assoc :uploaded-files files))

(defn u8file-write [fname uint8]
  (p/let [browser-fname fname
          root (js/navigator.storage.getDirectory)
          fh (.. root (getFileHandle browser-fname (clj->js {:create true})))
          w (.createWritable fh)
          _ (.write w uint8)
          _ (.close w)
          ]
    fh))

(defn local-file-exists? [fname]
  (p/catch
      (p/let [root (js/navigator.storage.getDirectory)
              fh (.. root (getFileHandle fname))]
        true)
      (fn [e]
        false)))

(defn u8file-read [fname]
  (p/let [browser-fname fname
          root (js/navigator.storage.getDirectory)
          fh (.. root (getFileHandle browser-fname))
          f  (.getFile fh)
          ab (.arrayBuffer f)]
    (js/Uint8Array. ab)))

(defn local-f-save! [file-name table-name]
  (p/let [fname (str (gensym "tmp_buf") ".parquet")
          skey file-name
          db kduck/db
          conn kduck/conn
          _ (.send conn (str "COPY (SELECT * FROM " table-name ") TO '" fname "' (FORMAT parquet)"))
          uarr ^js (.copyFileToBuffer db fname)
          ;; this matter? idk
          _ ^js (.dropFiles db)]
    (u8file-write skey uarr)
    (klog "wrote to storage!")))

(defn local-file-load! [fname table-name]
  (p/let [db kduck/db
          buf (u8file-read fname)
          _ ^js (.registerFileBuffer db fname buf)
         ; _ (kduck/pq (str "CREATE TABLE " table-name " AS select * from read_parquet('" fname "')"))
          ]
    (klog "loaded file to table" fname table-name)
    nil))

(defn local-f->link [f]
  (p/let [b (u8file-read f)]
    (js/URL.createObjectURL (js/Blob. (clj->js [b])))))

(def demo-file-name "demo.parquet")

(defn demo-file-load! []
  (p/let [r (js/fetch (str "./" demo-file-name))
          ab (.arrayBuffer r)
          db kduck/db
          _  ^js (.registerFileBuffer db demo-file-name (js/Uint8Array. ab))
          ]
    true))

(def init-donep? (rg/atom (p/deferred)))

(defn into-defferred! [dp p]
  (p/finally p
             (fn [r e]
               (if e
                 (p/reject! dp e)
                 (p/resolve! dp r)))))

(defn ->locking-fn
  "
  returns a locking function, that takes a zero arg fn returning a promise, ensures
  only one promise is outstanding at any given time.
  if a call happens while promise is outstanding, it will be delayed till after it finishes.
  if a subsequent call comes while one is waiting, the waiting one will be resolved
  immediately with null, and the subsequent call will be waiting.
  "
  []
  (let [lock (atom nil)]
    (fn do-call [f]
      (let [{:keys [wp wjob]}
            (swap! lock
                   (fn [{:keys [locked? wp wjob]}]
                     (if locked?
                       (do
                         (when wp
                           (p/resolve! wp nil))
                         {:locked? true
                          :wp (p/deferred)
                          :wjob f})
                       {:locked? true})))]
        (or wp
            (p/finally (f)
                       (fn [r e]
                         (let [{:keys [wp wjob] :as x}
                               (:old
                                (swap! lock
                                       (fn [{:keys [wp wjob] :as x}]
                                         ;; ok swap isn't blocking like i thought..
                                         ;; this happens before lock is reset

                                         {:locked? false
                                          :old x})))]
                           (when wp
                             (into-defferred!
                              wp
                              (do-call wjob))) ))))))))

(def fetch-data-lock (->locking-fn))

(def agg-selector-ref (rg/cursor state [:agg-selector]))

(def agg-selector-current (rg/reaction
                           (some #(when (:selected? %)
                                    %)
                                 @agg-selector-ref)))

(defn human-time [d]
  (subs
   (.. d (toISOString))
   0 19))

(defn agg-selector-parse! []
  (p/let [identf (juxt :a_sourceName :a_type)
          to-str #(str "type: '" (:a_type %)
                       "', source: '" (:a_sourceName %)
                       "', "
                       (:nrecords %))
          _ @init-donep?
          options (kduck/query-array "
select a_sourceName, a_type, count(*) nrecords
from record
group by a_sourceName, a_type
order by nrecords desc
")]
    (swap! agg-selector-ref
           (fn [current-values]
             (let [opts
                   (->> options
                        (map #(js->clj % :keywordize-keys true))
                        (mapv (fn [{:as d :strs [a_sourceName a_type]}]
                                (let [s (to-str d)]
                                  {:selected?
                                   (some (fn [x]
                                           (and (= (:text x)
                                                   s)
                                                (-> x :selected?)))
                                         current-values)
                                   :text s
                                   :data d}))))]
               (if (some :selected? opts)
                 opts
                 (assoc-in opts [0 :selected?] true)))))))

(comment

  (js/Date.)
  )

(defn fetch-data [{:as args :keys [agg-type a_sourceName a_type]
                   [tstart tend] :time-slice}]
  (fetch-data-lock
   (fn []
     (p/let [_ @init-donep?
             _ (klog "getting data")
             data
             (case agg-type
               :mile
               (kduck/query-array
                (str
                 "
with workout_dist_source as (
-- to query the best source for a_type dist
    select
      distinct on (w.line_num)
      w.line_num
      ,r.a_sourceName
      ,count(*) as record_count
    FROM record r
    JOIN workout w
        ON r.a_startDate >= w.a_startDate
        AND r.a_endDate <= w.a_endDate
    where r.a_type = 'HKQuantityTypeIdentifierDistanceWalkingRunning'
    group by w.line_num, r.a_sourceName
    order by w.line_num, record_count desc
)
,distance_records AS (
    SELECT
        r.a_startDate,
        r.a_endDate,
        r.a_value,
        SUM(r.a_value::numeric) OVER (
            partition by w.line_num
            ORDER BY r.a_startDate
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS cumulative_distance,
        w.line_num wline_num
    FROM record r
    JOIN workout w
        ON r.a_startDate >= w.a_startDate
        AND r.a_endDate <= w.a_endDate
    join workout_dist_source wds on  wds.line_num = w.line_num
    WHERE r.a_type = 'HKQuantityTypeIdentifierDistanceWalkingRunning'
        and r.a_sourceName = wds.a_sourceName
        -- TODO fix this not be hardcoded
         --and r.a_sourceName like '%Apple%Watch%'
         and w.a_startDate >= epoch_ms(" tstart ") and  w.a_startDate <= epoch_ms(" tend ")
)
,mile_segments AS (
    SELECT
        m.mile,
        min(dr.a_startDate) AS mile_startt,
        max(dr.a_endDate) AS mile_endt,
        dr.wline_num,
        max(dr.cumulative_distance) mile_end
    FROM (SELECT unnest(generate_series(0, 5::int)) as mile) m
    JOIN distance_records dr
        ON dr.cumulative_distance >= m.mile
        and dr.cumulative_distance < (m.mile + 1)
    GROUP BY m.mile, dr.wline_num
    ORDER BY dr.wline_num, m.mile
)
-- aggregate per mile
select
  'mile' agg_type
  ,w.line_num as workout_id
  ,w.a_startDate as workout_start
  ,ms.mile
  ,ms.mile_startt as start
  ,ms.mile_endt as end
  ,r.a_type
  ,avg(r.a_value::float) as value
from workout w
inner join mile_segments ms on ms.wline_num = w.line_num
inner join record r on r.a_startDate >= ms.mile_startt and r.a_startDate <= ms.mile_endt
where r.a_type = '" a_type "'
and r.a_sourceName like '" a_sourceName "'
and r.a_startDate >= epoch_ms(" tstart ") and  r.a_startDate <= epoch_ms(" tend ")
group by workout_id
  ,workout_start
  ,ms.mile
  ,ms.mile_startt
  ,ms.mile_endt
,r.a_type
order by start
") )
               :workout
               (kduck/query-array (str "
select
  'workout' agg_type
  ,w.line_num workout_id
  ,w.a_startDate as workout_start
  ,w.a_startDate as start
  ,w.a_endDate as end
  ,r.a_type
  ,avg(r.a_value::float) as value
from workout w
inner join record r on r.a_startDate >= w.a_startDate and r.a_startDate <= w.a_endDate
where r.a_type = '" a_type "'
and r.a_sourceName like '" a_sourceName "'
group by
  workout_id
  ,workout_start
  ,start
  ,\"end\"
  ,r.a_type
order by start
"))
               :record
               (kduck/query-array (str "
select
  'record' agg_type
  ,w.line_num workout_id
  ,w.a_startDate as workout_start
  ,r.a_startDate as start
  ,r.a_endDate as end
  ,r.a_type
  ,r.a_value::float as value
from record r
join workout w on r.a_startDate >= w.a_startDate and r.a_startDate <= w.a_endDate
where r.a_type = '" a_type "'
and r.a_sourceName like '" a_sourceName "'
and r.a_startDate >= epoch_ms(" tstart ") and  r.a_startDate <= epoch_ms(" tend ")
order by start
"))
               )
             ]
       (klog "got data")
       {:data data
        :onTooltip (fn [^js d]
                     (case (.-agg_type d)
                       "workout" (str "Workout " (human-time (.-start d)) " avg:" (.toFixed (.-value d) 2))
                       "mile" (str  " Mile " (.-mile d) " avg:" (.toFixed (.-value d) 2) " time: " (human-time (.-start d)))
                       "record" (str "Value: " (.toFixed (.-value d) 2) " time: " (human-time (.-start d)))
                       "idk")
                     )}))))

(defn data-minmax-v [data & {:keys [xform]
                             :or {xform identity}}]
  (->> data
       (into [] (comp
                 (map #(.-value %))
                 (x/transjuxt {:maxv x/max
                               :minv x/min})))
       first))

(defn setup-db-from-health-parquet! [fname]
  (p/let [_ (kduck/pq "drop table if exists health")
          _ (kduck/pq "drop table if exists workout")
          _ (kduck/pq "drop table if exists record")
          _ (kduck/pq (str "CREATE TABLE health AS select * from read_parquet('" fname "')"))
          _ (kduck/pq "
create table workout as
select
  a_sourceVersion,
  a_workoutActivityType,
  a_type,
  a_sourceName,
  a_device,
  a_value,
  substring(a_creationDate, 0, 20)::timestamp as a_creationDate,
  type,
  substring(a_endDate, 0, 20)::timestamp as a_endDate,
  line_num,
  substring(a_startDate, 0, 20)::timestamp as a_startDate,
  a_duration,
  a_totalDistance
  from health
  where type = 'Workout';

create table record as
select
  a_sourceVersion,
  a_workoutActivityType,
  a_type,
  a_sourceName,
  a_device,
  a_value,
  substring(a_creationDate, 0, 20)::timestamp as a_creationDate,
  type,
  substring(a_endDate, 0, 20)::timestamp as a_endDate,
  line_num,
  substring(a_startDate, 0, 20)::timestamp as a_startDate,
  a_duration,
  a_totalDistance
  from health
  where type != 'Workout'
;
")
          ]
    (p/resolve! @init-donep? true)
    )
  )

(defn chan->promise [c]
  (let [p (p/deferred)]
    (a/go
      (p/resolve! p (a/<! c)))
    p))

(def worker-jobs (atom {}))

(defn worker-start! []
  (swap! state update
         :worker
         (fn [w]
           (when w
             (.terminate w))
           (doto (js/Worker. "./js/loader.js")
             (aset "onmessage"
                   (fn [e]
                     (let [handle (.. e -data -handle)]
                       (swap! state update-in [:worker-jobs]
                              (fn [jobs]
                                (when-let [rh (get jobs handle)]
                                  (rh e))
                                (if (.. e -data -done)
                                  (dissoc jobs handle)
                                  jobs))))
                     #_
                     (klog "reponse from worker" e))))))
  (:worker @state))

(defn worker-stream! [method args]
  (let [c (a/chan 100)
        handle (str (random-uuid))]
    (swap! state assoc-in [:worker-jobs handle]
           (fn [resp]
             (a/go
               (a/>! c (.. resp -data))
               (if (.. resp -data -done)
                 (a/close! c)))))
    (-> @state
        :worker
        (.. (postMessage
             (clj->js
              (merge args
                     {:method method
                      :handle handle})))))
    c))

(defn worker-send-message! [method args]
  (let [p (p/deferred)
        handle (str (random-uuid))]
    (swap! state assoc-in [:worker-jobs handle]
           (fn [resp]
             (if (.. resp -data -e)
               (p/reject! p (.. resp -data -e))
               (p/resolve! p (.. resp -data -r)))))
    (-> @state
        :worker
        (.. (postMessage
             (clj->js
              (merge args
                     {:method method
                      :handle handle})))))
    p))


(def status-ref (rg/cursor state [:status]))

(defn status-set! [& {:keys [msg]}]
  (reset! status-ref msg)
  (p/let [_ (p/delay 6000)]
    (swap! status-ref
           (fn [s]
             (if (not= s msg)
               s
               "")))))

(defn parse-file! [file table-name]
  (let [wc (worker-stream! "loadf-stream"
                           {:file file})]
    (p/let [_ (kduck/exec (str "drop table if exists " table-name))
            r
            (chan->promise
             (a/reduce (fn [started? item]
                         (when (some-> item .-r (aget "status"))
                           (status-set! :msg (.. item -r -status)))
                         (when-let [ipc-table ^js (some-> item (.-r) (aget "ipc-table"))]
                           (p/catch
                               (p/let [conn kduck/conn
                                       _
                                       ^js
                                       (.insertArrowFromIPCStream
                                        conn
                                        ipc-table
                                        (clj->js {:name table-name
                                                  :create (not started?)}))])
                               (fn [e]
                                 (klog "error reading chunk!" e))))
                         (or started? (some-> item (.-r) (aget "ipc-table") boolean)))
                       false
                       wc))]
      (status-set! :msg (str "finished loading " (.-name file) " into table " table-name)))))

;; HACK for development to redefine worker
(defonce worker13
  (worker-start!))

(comment

  (p/let [r (kduck/exec "select min(a_startDate) earliest, max(a_endDate) latest from workout")]
    (klog r))
  (kduck/pq "select min(a_startDate) mindate, max(a_endDate) maxdate from workout")

  (-> @state :uploaded-files first (.-name))

  (parse-file! (-> @state :uploaded-files first)
               "health2")

  (kduck/pq "describe tables")

  (kduck/pq "
select a_value, a_creationDate, a_startDate, a_endDate
from record
join workout on

where a_type = 'HKQuantityTypeIdentifierHeartRate'
and workout_id = 3104639
order by a_startDate desc
limit 30
")







  (kduck/pq "SELECT STRPTIME('2024-11-16 14:42:35 -0800', '%Y-%m-%d %H:%M:%S %z') AS ts;")

  (kduck/pq "select a_value::decimal(10, 2) val, line_num from record
where a_type = 'HKQuantityTypeIdentifierHeartRate'
limit 10
")

  (kduck/pq "describe tables")

  (kduck/pq "select * from health limit 5")

  (kduck/pq "create table demo as select * from health order by line_")
  (local-f-save! )

  (p/let [x
          (kduck/pq "
select a_sourceName, a_type, count(*) nrecords
from record
group by a_sourceName, a_type
order by nrecords desc
")]
    (def testx (first x))
    (klog "got thing" (first x)))


  (agg-selector-parse!)

  (let [[x1 x2]
        (-> @state
            :graph
            :domain)]
    (- x2 x1))


  (-> @state :graph :prev-data keys)




  (apply str (repeat 5 "."))

  (mod 6 5)
  )

(defn loading-ind []
  (rg/with-let [i (rg/atom 0)]
    (rg/track! (fn []
                 (p/let [_ (p/delay (+ 600 (* 50 @i)))]
                   (swap! i inc))))
    [:span (apply str "loading" (repeat (mod @i 4) "."))]))

(defn dropdown
  "A reusable dropdown component.
   Parameters:
   - options: a vector of options to display in the dropdown
   - on-change: callback function to call when selection changes
   - selected: atom holding the current selection (optional)"
  [& {:keys [opts-atom]}]
  [:select
   {:value (or (some #(when (:selected? %)
                        (:text %))
                     @opts-atom)
               "none")
    :on-change #(let [new-value (-> % .-target .-value)]
                  (swap! opts-atom (fn [opts]
                                     (mapv
                                      (fn [x]
                                        (assoc x :selected?
                                               (= new-value (:text x))))
                                      opts))))}
   (for [{:keys [text]} @opts-atom]
     ^{:key text}
     [:option {:value text} text])])

;; call
;; "select min(a_startDate) mindate, max(a_endDate) maxdate from workout"
;; containerselector #id-for-div
(def demo-loaded?-ref (rg/cursor state [:demo-loaded?]))

(+ (.getTime (js/Date.)) (.getTime (js/Date.)))

;; want to split timespace into segments
;; but then like every segment should be partitioned into 2, and increment by 1 partition
(defn graph-1 [{:keys [cref gid width height]}]
  (rg/with-let [base-args {:width  width
                           :height height}
                init-result (rg/cursor state [:graph :d3-args])
                range-ref (rg/cursor state [:graph :domain])
                agg-type-ref (rg/cursor state [:graph :agg-type])
                prev-data-ref (rg/cursor state [:graph :prev-data])
                loading? (rg/atom true)
                do-draw (rg/reaction
                         (let [{:keys [newX x y] :as initr} @init-result
                               agg-sel                      @agg-selector-current]
                           (reset! loading? true)
                           (when initr
                             (p/let [[start end] (js->clj (.domain newX))
                                     time-window (- end start)
                                     time-per-pixel (/ time-window width)
                                     ;; _ (klog "dew" (/ time-window (* 1000 60 60)))
                                     agg-type     (cond
                                                    (> (* 5 60 60 1000) time-window)  :record
                                                    (> (* 72 60 60 1000) time-window) :mile
                                                    :else                     :workout)
                                     data-args {:agg-type     agg-type
                                                :time-slice (when-let [slice-size ({:record (* 1000 60 60 10)
                                                                                    :mile (* 1000 60 60 72)}
                                                                                   agg-type)]
                                                              [(-> start
                                                                   (.getTime)
                                                                   (+ (.getTime end))
                                                                   (/ 2)
                                                                   (/ slice-size)
                                                                   (js/Math.floor)
                                                                   (* slice-size))
                                                               (-> start
                                                                   (.getTime)
                                                                   (+ (.getTime end))
                                                                   (/ 2)
                                                                   (/ slice-size)
                                                                   (js/Math.floor)
                                                                   (inc)
                                                                   (* slice-size))])
                                                :a_sourceName (-> agg-sel :data :a_sourceName)
                                                :a_type       (-> agg-sel :data :a_type)}
                                     data-resp (or (get @prev-data-ref  data-args)
                                                   (fetch-data data-args))
                                     _ (when data-resp
                                         (reset! prev-data-ref
                                                 {data-args (assoc data-resp
                                                                   :fromCache true)}))
                                     minmaxvs (data-minmax-v (:data data-resp)
                                                             :xform (filter #(<= start (.-start %) end)))
                                     ]
                               (when data-resp
                                 (js/drawBars
                                  (clj->js
                                   (merge base-args
                                          initr
                                          minmaxvs
                                          data-resp
                                          {:drawLines (= agg-type :record)}
                                          ))))
                               (reset! loading? false)))))]

    @do-draw
    (react/useEffect
     (fn []
       (p/let [_ @init-donep?
               _ (agg-selector-parse!)
               minmax (kduck/query-array "select min(a_startDate) mindate, max(a_endDate) maxdate from workout")
               [{:strs [mindate maxdate] :as x}] (js->clj minmax)
               _ (klog "minmax" x (pr-str x) mindate maxdate)
               initr (js/initGraph
                      (clj->js
                       (merge base-args
                              {:initMinDate       mindate
                               :initMaxDate       maxdate
                               :containerSelector (str "#" gid)
                               :onZoom            (fn [x]
                                                    (let [{:strs [newX] :as x} (js->clj x)]
                                                      (swap! init-result assoc :newX newX)
                                                      (reset! range-ref
                                                              (js->clj (.domain newX)))))})))]
         (reset! init-result (js->clj initr :keywordize-keys true))
         )
       (fn []))
     #js [(.-current cref) gid])
    [:div
     (when-not @init-result
       "init ")
     (when @loading?
       [loading-ind])])
  )

(defn graph []
  (let [cref   (react/useRef)
        gid    "graph-container"
        width  (* 0.9 (.-innerWidth js/window))
        height (* 0.5 (.-innerHeight js/window))
        ]
    [:div
     [:div {:ref    cref
            :id     gid
            :height height
            :width "100%"
            :position "relative"
            }]
     [:div [graph-1 {:cref cref
                     :gid gid
                     :width  width
                     :height height
                     }]]]))


(defn init-script [& {:keys [force-demo?]}]
  (p/let [main-exists? (if force-demo? false (local-file-exists? main-parquet-file-name))
          _ (if main-exists?
              (do
                (status-set! :msg "found previous file. loading that.")
                (reset! demo-loaded?-ref false)
                (local-file-load! main-parquet-file-name main-table-name))
              (do
                (status-set! :msg "no file uploaded. loading demo data.")
                (reset! demo-loaded?-ref true)
                (demo-file-load!)))
          _ (status-set! :msg "file loaded, reading data")
          _ (setup-db-from-health-parquet! (if main-exists?
                                             main-parquet-file-name
                                             demo-file-name))]
    true))

(defn main-page []
  (rg/with-let [table-dl-link (rg/atom "")
                _ (init-script)]
    [:<>
     [:div "select apple health 'export.zip' file:"]
     [:input {:accept ".zip"
              :type "file"
              :on-change (fn [e]
                           (load-file! (.. e -target -files))
                           (p/let [_ (parse-file! (-> @state :uploaded-files first) main-table-name)
                                   _ (status-set! :msg "saving to local storage")
                                   _ (local-f-save! main-parquet-file-name main-table-name)
                                   _ (status-set! :msg (str "saved at " main-parquet-file-name ". creating link & loading data."))
                                   link (local-f->link main-parquet-file-name)]
                             (reset! table-dl-link link)))}]
     (if @demo-loaded?-ref
       [:div "using demo data. select apple health `export.zip` file to use your own data"]
       [:div "using local health data. "
        [:a {:href "#"
             :on-click (fn [x]
                         (reset! init-donep? (p/deferred))
                         (status-set! :msg "loading demo")
                         (init-script :force-demo? true)
                         (klog "should reload other thing"))}
         "load demo instead"]])

     ;; (when-let [f (-> @state :uploaded-files first)]
     ;;   [:button {:on-click (fn [e]
     ;;                         (p/let [_ (parse-file! (-> @state :uploaded-files first) main-table-name)
     ;;                                 _ (status-set! :msg "saving to local storage")
     ;;                                 _ (local-f-save! main-parquet-file-name main-table-name)
     ;;                                 _ (status-set! "saved at " main-parquet-file-name)
     ;;                                 link (local-f->link main-parquet-file-name)]
     ;;                           (reset! table-dl-link link))
     ;;                         )}
     ;;    (str "parse '" (.-name f) "'")])
     [:div {:style {:max-width "80ch"}} @status-ref]
     (when (not-empty @table-dl-link)
       [:div (str "download link:")
        [:a {:href @table-dl-link
             :download "health.parquet"}
         "download!"]])
     [:br]
     [:br]
     [:br]
     [dropdown :opts-atom agg-selector-ref]
     [graph]
     ]))

(defonce rroot (react-client/createRoot (js/document.querySelector "#app")))

(rg/set-default-compiler! (rg/create-compiler {:function-components true}))


(defn ^:dev/after-load render []
  (.render
   rroot
   (rg/as-element [main-page])))

(defn ^:export init []
  (render))
