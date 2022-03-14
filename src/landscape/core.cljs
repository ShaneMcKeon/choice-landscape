(ns landscape.core
  (:require
   [landscape.loop :as lp]
   [landscape.key]
   [landscape.view :as view]
   [landscape.sound :as sound]
   [landscape.model.timeline :as timeline]
   [landscape.model :as model :refer [STATE]]
   [landscape.settings :as settings :refer [current-settings]]
   [landscape.instruction :refer [INSTRUCTION instruction-finished]]
   [landscape.url-tweak :refer [get-url-map task-parameters-url vis-type-from-url]]
   [goog.string :refer [unescapeEntities]]
   [goog.events :as gev]
   [cljsjs.react]
   [cljsjs.react.dom]
   [sablono.core :as sab :include-macros true]
   [debux.cs.core :as d :refer-macros [clog clogn dbg dbgn dbg-last break]]
   )
  (:import [goog.events EventType KeyHandler]))

;; boilerplate
(enable-console-print!)
(set! *warn-on-infer* false) ; maybe want to be true

(defn gen-well-list []

  ;; TODO: might want to get fixed timing
  ;;       look into javascript extern (and supply run or CB) to pull from edn/file
  (let[best-side (first (shuffle [:left :right])) ; :up  -- up is too different 20211029
        prob-low (get-in  @current-settings [:prob :low] ) ; initially 20
        prob-mid (get-in  @current-settings [:prob :mid] ) ; initially 50
        prob-high (get-in @current-settings [:prob :high] ) ; originally 100, then 90 (20211104)
       prob-deval-other (get-in @current-settings [:prob :devalue-good :other]) ;likely 75 or 100 depending on settings
       n-devalue-good-trials (get-in @current-settings [:nTrials :devalue-good])
        
       ]
    (vec (concat
       ;; first set of 24*(3 lowhigh + 3 highlow + 3 lowhigh):
       ;; initially had two close wells with infreq rewards. far is all rewards
       ;; then version where all wells are equidistant but 2 are still meh
       ;; finally a version with an additional block where the good well is deval
       (->
        ; ({:left 100 :right 30 :up 20}, {... :up 30 :right 20})
        (timeline/side-probs prob-low prob-mid best-side)
        ;; 20220314 maybe no reversal!?
        (#(take (if (:reversal-block @current-settings) 2 1) %))

        ; ({:resp-each-side 24 :left 100...}, {:reps-each-side ...}
        (timeline/add-reps-key (-> @current-settings :nTrials :pairsInBlock)) ; 24*3 trials per block

        ; 20211216 - remove. now only 2 blocks before deval
        ; ({..:up 20}, {.. :up 30}, {.. :up 20})
        ; timeline/add-head-to-tail

        vec

        ; 20211216 - remove. all equal
        ; prev more trial for first block (19-16 *3 = 9 more)
        ;(assoc-in [0 :reps-per-side] 19)
        
        ;; make it look like what well drawing funcs want
        ((partial mapcat #'timeline/gen-prob-maps))
        ((partial mapv #'timeline/well-trial)))

        ;; removed 2021-12-09
        ; ;; add 4 forced trials where we cant get to the good
        ; ;; far away well. encourage exploring
        ; (filter #(not (-> % best-side :open))
        ;         (timeline/gen-wells
        ;          {:prob-low prob-high
        ;           :prob-high prob-high
        ;           :reps-each-side 2
        ;           :side-best best-side}))

       ;; 20211216 - one set to ensure everything is seen equally at the start
       ;; 3 trials total: lr, lu, ru ... but random
       (timeline/gen-wells
        {:prob-low prob-high
         :prob-high prob-high
         :reps-each-side 1
         :side-best best-side})


       ;; all wells are good:  4 reps of 6 combos
       (timeline/gen-wells
        {:prob-low prob-high
         :prob-high prob-high
         :reps-each-side (max 0 (- (-> @current-settings :nTrials :devalue) 1))
         ; 20211104 increased from 4 to 8; 8*(2 high/low swap, h=l)*(3 per perm)
         ;; 20211216 inc to 9 (+1 above)
         :side-best best-side})
       ;; 20220314 - optionally add a 4th block to devalue good
       (when (> n-devalue-good-trials 0)
         (timeline/gen-wells
          {:prob-low prob-deval-other
           :prob-high prob-deval-other
           :reps-each-side n-devalue-good-trials
           :side-best (get-in @current-settings [:prob :devalue-good :good])}))))))

(defn vis-class-to-body
  "style body based on visual type (desert/mountain)"
  []
  (let [vis-class (-> @current-settings :vis-type name)]
    (.. js/document -body (setAttribute "class" vis-class))))

(defn -main []
  (gev/listen (KeyHandler. js/document)
              (-> KeyHandler .-EventType .-KEY) ; "key", not same across browsers?
              (partial swap! STATE model/add-key-to-state))
  (add-watch STATE :renderer (fn [_ _ _ state] (lp/renderer (lp/world state))))
  (reset! STATE  @STATE) ;; why ? maybe originally to force render. but we do other updates

  (println "preloading sounds")
  (doall (sound/preload-sounds))

  ;; update settings based on url
  ;; grab any url parameters and store with what's submited to the server
  ;; :record:url and :record:settings
  (let [u (get-url-map) 
        vis (vis-type-from-url u)]
    (swap! STATE assoc-in [:record :url] u)
    (swap! settings/current-settings assoc :vis-type vis)
    (reset! settings/current-settings (task-parameters-url @settings/current-settings u))
    (swap! STATE assoc-in [:record :settings] @settings/current-settings))

  ;; view debug
  (when (:debug @settings/current-settings)
    (set! landscape.view.DEBUG true))


  (let [well-list (gen-well-list)]
    (swap! STATE assoc :well-list well-list)
    ;; update well so well in insturctions matches
    (swap! STATE assoc :wells (first well-list)))

  ;; TODO: fixed iti durations

  ; start with instructions
  ; where lp/time-update will call into model/next-step and disbatch to
  ; instruction/step (and when phase name changes, will redirect to model/step-task)
  ; phase name change handled by phase/set-phase-fresh
  (swap! STATE assoc-in [:phase :name] :instruction)

  ; run the first instructions start function
  ; BUG - this doesn't play any sound! but we moved sound captcha to second slide
  (reset! STATE ((-> INSTRUCTION first :start) @STATE))

  ;; jump to ready screen if no instructions
  (if (not (:show-instructions @settings/current-settings))
    ;; (reset! STATE (instruction-finished @STATE 0))
    (swap! STATE assoc-in [:phase :idx] (dec (count INSTRUCTION))))
  
  
  ; update background for mountain or desert 
  (vis-class-to-body) 
  ; start
  (lp/run-start STATE))

(-main)
