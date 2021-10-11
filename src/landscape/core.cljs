(ns landscape.core
  (:require
   [landscape.loop :as lp]
   [landscape.key]
   [landscape.view :as view]
   [landscape.model.timeline :as timeline]
   [landscape.model :as model :refer [STATE]]
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


(defn -main []
  (gev/listen (KeyHandler. js/document)
              (-> KeyHandler .-EventType .-KEY) ; "key", not same across browsers?
              (partial swap! STATE model/add-key-to-state))
  (add-watch STATE :renderer (fn [_ _ _ state] (lp/renderer (lp/world state))))
  (reset! STATE  @STATE) ;; why ? maybe originally to force render. but we do other updates

  ;; TODO: might want to get fixed timing
  ;;       look into javascript extern (and supply run or CB) to pull from edn/file
  (swap! STATE assoc :well-list
         (timeline/gen-wells {:prob-low 20
                      :prob-high 50
                      :reps-each-side 3
                      :side-best :left}))
  ;; TODO: fixed iti durations

  ; start with instructions
  ; where lp/time-update will call into model/next-step and disbatch to
  ; instruction/step (and when phase name changes, will redirect to model/step-task)
  ; phase name change handled by phase/set-phase-fresh
  (swap! STATE assoc-in [:phase :name] :instruction)
  ; start
  (lp/run-start STATE))

(-main)
