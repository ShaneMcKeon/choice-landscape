(ns landscape.model.phase
  (:require [landscape.model.avatar :as avatar]
            [landscape.settings :as settings]
            [landscape.key :as key]
            [landscape.http :as http]
            [landscape.sound :as sound]
            [landscape.model.wells :as wells]
            ;; [debux.cs.core :as d :refer-macros [clog clogn dbg dbgn dbg-last break]]
            ))


;; 20211007 current phases
;; :instruction :chose :waiting :feedback :iti
(defn set-phase-fresh [pname time-cur]
  {:name pname :start-at time-cur
   :scored nil :hit nil :picked nil :sound-at nil :iti-dur nil})

(defn phase-done-or-next-trial
  "reset to next trial (chose) or to done (:done :chose :forum)"
  [{:keys [trial time-cur well-list] :as state}]
  (let [where (get-in state [:record :settings :where])
        next (cond
               (< trial (count well-list)) :chose  ; next trail
               ;; :survey okay forced, but w/:iti->:survey, not responsive to keys
               (contains? #{:mri} where) :done     ; TODO: :survey (buttonbox Qs)
               (contains? #{:online} where) :forum ; freeform text w/full keyboard
               :else :forum)]
    (set-phase-fresh next time-cur)))

(defn send-identity
  "POST json of :record but return input state so we can use in pipeline"
  [{:keys [record] :as state}]
  (do (http/send-resp record)
      state))

;; ":record" has per trial vector of useful state info
;; [{:trial #
;;   :chose-time # :waiting-time # :feedback-time #
;;   :picked #  :score ?
;;   :$side-$info .... # wells/wide-info info for each well
;; }]
;; TODO: this doesn't need to return state. can just update :record
(defn phone-home
  "update :record for sending state
  send state to server right before feedback"
  [{:keys [trial wells phase] :as state}
  {:keys [start-at] :as next-phase}
   ;; NB. not pulling in name so we can use name function
   ]
  (let [time-key (str (name (:name next-phase)) "-time")     ;chose-time waiting-time feedback-time
        trial0 (dec trial)
        state-time (assoc-in state [:record :events trial0 time-key]  start-at)
        ;; NB. about to change to :feedback when :waiting, so use cur not next
        picked (get phase :picked)]
    (case (:name next-phase)

      ;; iti is start of trial and task (prev will be :instruction)
      :iti
      (-> state-time
          (assoc-in [:record :events trial0 :trial] trial))

      :chose
      (-> state-time
          (update-in [:record :events trial0] #(merge % (wells/wide-info wells))))

      :waiting
      (-> state-time
          (assoc-in [:record :events trial0 :picked] picked)
          ;; add picked and avoided
          (update-in [:record :events trial0]
                     #(merge % (wells/wide-info-picked wells picked))))

      :feedback
      (-> (assoc-in state-time [:record :events trial0 :score] (get phase :scored))
          (assoc-in [:record :events trial0 :all-keys]
                    (-> state :key :all-pushes))
          (send-identity))

      ;;  TODO!
      ;; NB. :done is it's own state. using :forum for text based questions
      ;;     survey would work w/ buttonbox (20220331)
      :survey                       ; finished survey about to be done
      (-> state-time (println "TODO:  SHOULD PHONE HOME ABOUT DONE. also upload survey results"))
      ;; if no match, default to doing nothing
      state-time
      )))

;; TODO: well-list update also done by 
;; wells/wells-update-which-open. not sure which is a better place
(defn update-next-trial-on-iti
  "when iti, update to the next well info and trial"
  [{:keys [trial well-list] :as state} next-name ]
  (let [ntrials (count well-list)
        trial (max 1 (inc trial))       ; if we are updating, its at iti and moving to next trial
        trial0 (dec (min trial ntrials))]
    (if (= next-name :iti)
      (-> state
          ;; (assoc state :wells (get (dec trial) well-list))
          (assoc :trial trial))
      state)))

(defn clear-key-before-chose
  "key pushes during other states linger into :chose
  clear all keys when we are leaving iti/entering chose"
  [{:keys [phase] :as state} next]
  (if (= next :chose) ;; (= (:name phase) :iti)
     (assoc state :key (key/key-state-fresh))
    state))

(defn phase-update
  "update :phase of STATE when phase critera meet (called by model/step-task
  and by instruction on last instruction).
  looks in phase for :picked & :hit, avatar location in state
  when updated calls phone-home to update :record (and maybe http/send)

  :chose -> :waiting when :picked not nil
  :waiting -> :feedback when :hit not nil
  :feedback -> :iti when avatar is home
  :iti -> :chose after duration
  "
  [{:keys [phase time-cur trial] :as state}]
  (let [pname (get phase :name)
        trial0 (dec trial)
        hit (get phase :hit)
        picked (get phase :picked)
        time-since (- time-cur (:start-at phase))
        iti-dur (get-in state [:well-list trial0 :iti-dur] settings/ITIDUR)
        phase-next (cond
                     ;; as soon as we pick, switch to waiting
                     (and (= pname :chose) (some? picked))
                     (assoc phase :name :waiting :start-at time-cur)

                     ;; or a choice was not made quick enough
                     (and (= pname :chose)
                          (>= time-since (get-in @settings/current-settings [:times :choice-timeout]))
                          (:enforce-timeout @settings/current-settings))
                     (assoc phase :name :timeout
                            :start-at time-cur
                            :sound-at (sound/timeout-snd time-cur nil))

                     ;; as soon as we hit, switch to feedback (sound)
                     (and (= pname :waiting) (some? hit))
                     (assoc phase :name :feedback :sound-at nil :start-at time-cur)

                     ;; move onto iti or start the task: instruction -> iti
                     ;; might be comming from feedback, timeout, or instructions
                     (or (and (= pname :feedback) (avatar/avatar-home? state))
                         (= pname :instruction)
                         (and (= pname :timeout)
                              (>= time-since (get-in @settings/current-settings [:times :timeout-dur]))))
                     (assoc phase
                            :name :iti
                            :start-at time-cur
                            :iti-dur iti-dur)
                     
                     ;; restart at chose when iti is over
                     (and (= pname :iti)
                          (>= time-since (:iti-dur phase)))
                     (phase-done-or-next-trial state)

                     ;; no change if none needed
                     :else phase)
        ]
    ;; push current phase onto stack of events (historical record)
    ;; update phase
    (if (not= (:name phase) (:name phase-next))
      (-> state
          (update-next-trial-on-iti (:name phase-next))
          (clear-key-before-chose (:name phase-next))
          (phone-home phase-next)
          (assoc :phase phase-next))
      state)))

