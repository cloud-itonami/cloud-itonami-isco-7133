(ns bldgclean.governor
  "BldgCleanCoordGovernor -- the independent safety/traceability layer
  named in this repository's README/business-model.md, gating every
  scheduling/logistics coordination proposal an advisor may make for a
  building-structure-cleaning job site. The governor never dispatches
  hardware itself, never performs building-cleaning work, and never
  allows a proposal to finalize a cleaning-execution decision
  (including a height-work go/no-go call) or override a site-safety
  officer's judgment -- this actor coordinates JOB-SITE SCHEDULING/
  LOGISTICS ONLY. Modeled on cloud-itonami-isco-7121's
  roofcoord.governor.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. site provenance         -- the job site (and, when a worker is
                                 referenced, the worker) must be
                                 independently verified/registered
                                 before any action.
    2. no-actuation            -- proposal :effect must be :propose
                                 (the governor never dispatches
                                 hardware and never performs building-
                                 cleaning work; it only gates what the
                                 advisor may coordinate).
    3. closed op-allowlist     -- :op must be one of the four
                                 coordination ops
                                 (:log-work-record,
                                 :schedule-crew-operation,
                                 :flag-safety-concern,
                                 :coordinate-supply-order). No op that
                                 directly finalizes a cleaning-
                                 execution decision (including a
                                 height-work go/no-go call) or
                                 overrides site-safety authority exists
                                 in this allowlist.
    4. site-mismatch           -- if the proposal names a site, it
                                 must be the SAME site verified for
                                 this request (defense-in-depth
                                 against a proposal quietly targeting
                                 a different, unverified site).
    5. worker basis            -- if the proposal references a worker,
                                 that worker must be a REGISTERED crew
                                 member belonging to this site (an
                                 unregistered or foreign-site worker
                                 reference is not a routine scheduling
                                 proposal).
    6. scope-exclusion         -- a proposal that attempts to finalize
                                 a cleaning-execution decision
                                 (including a height-work go/no-go
                                 call), or to override a site-safety
                                 officer's judgment, is a hard,
                                 PERMANENT block -- never overridable
                                 by human approval, regardless of
                                 confidence or stake. Detected as
                                 finalization/execution ACTION PHRASES
                                 (e.g. 'proceed with the height-work
                                 cleaning task', 'override the site
                                 safety officer's judgment') in
                                 free-text proposal fields, never as
                                 bare domain nouns ('height',
                                 'cleaning', 'chemical') -- bare-noun
                                 matching would false-trip on the
                                 default mock advisor's own routine
                                 rationale text, since this actor's
                                 entire domain is exterior building
                                 cleaning at height with chemical
                                 cleaning agents. See
                                 `bldgclean.governor-test`
                                 `default-mock-advisor-proposals-never-self-trip-scope-exclusion`.

  ESCALATION invariants (:escalate? true, ALWAYS human sign-off,
  regardless of confidence):
    7. :op :flag-safety-concern always escalates (a surfaced
                                 fall-hazard/weather-condition/
                                 equipment-condition/chemical-exposure
                                 concern always requires human review
                                 -- the governor never resolves a
                                 safety concern itself).
    8. :op :coordinate-supply-order with :cost above
                                 `supply-order-cost-threshold` always
                                 escalates.
    9. low confidence (< `confidence-floor`)."
  (:require [kotoba.lang.text :as str]
            [bldgclean.store :as store]))

(def confidence-floor 0.6)

(def supply-order-cost-threshold 10000)

(def ^:private allowed-ops
  #{:log-work-record :schedule-crew-operation :flag-safety-concern
    :coordinate-supply-order})

(def ^:private always-escalate-ops #{:flag-safety-concern})

;; Scope-exclusion is matched as finalization/execution ACTION PHRASES,
;; never as bare nouns ("height", "cleaning", "chemical") -- this
;; actor's entire domain is exterior building cleaning at height with
;; chemical cleaning agents, so bare-noun matching would false-trip on
;; the default mock advisor's own routine rationale text (e.g.
;; "proposed :coordinate-supply-order for site S-1" naming cleaning
;; chemicals or height-access equipment). See governor-test's
;; dedicated self-trip guard.
(def ^:private scope-exclusion-phrases
  ["proceed with the height-work cleaning task"
   "proceed with the building cleaning work"
   "finalize the cleaning-execution decision"
   "finalize the height-work go/no-go decision"
   "finalize the height-work-execution decision"
   "execute the cleaning work directly"
   "execute the height-work directly"
   "perform the building cleaning directly"
   "dispatch the crew to perform cleaning work"
   "override the site safety officer's judgment"
   "override the site-safety officer's judgment"
   "override the site-safety officer"
   "override the safety officer's height-work judgment"
   "bypass the site-safety officer"
   "bypass height-work sign-off"
   "bypass the chemical-exposure safety review"
   "bypass the chemical-handling safety judgment"])

(defn- scope-excluded-text [proposal]
  (str/lower (str (:rationale proposal) " " (:description proposal))))

(defn scope-exclusion-violation?
  "true if any free-text field of `proposal` contains a
  finalization/execution action phrase attempting to finalize a
  cleaning-execution decision (including a height-work go/no-go call)
  or override site-safety authority. Phrased as multi-word action
  phrases (never bare nouns) so this never false-trips on legitimate
  building-cleaning-domain vocabulary."
  [proposal]
  (let [text (scope-excluded-text proposal)]
    (boolean (some #(str/includes? text %) scope-exclusion-phrases))))

(defn- hard-violations [{:keys [request proposal]} site-record w]
  (let [{:keys [op site-id worker-id]} proposal]
    (cond-> []
      (nil? site-record)
      (conj {:rule :no-site :detail "未登録 job site"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（governor は清掃実行判断を直接実行しない）"})

      (not (contains? allowed-ops op))
      (conj {:rule :unknown-op :detail "closed op-allowlist 外の op（清掃実行・安全判断の上書きにあたる op は許可されていない）"})

      (and site-id (not= site-id (:site-id request)))
      (conj {:rule :site-mismatch :detail "proposal の site が request で検証済みの site と一致しない"})

      (and worker-id (nil? w))
      (conj {:rule :unknown-worker :detail "未登録 worker への提案は不可"})

      (and w (not= (:site-id w) (:site-id request)))
      (conj {:rule :worker-wrong-site :detail "worker が別 site 所属"})

      (scope-exclusion-violation? proposal)
      (conj {:rule :scope-exclusion-violation
             :detail "清掃実行判断（高所作業の go/no-go を含む）の確定または site-safety officer の判断の上書きにあたる提案は恒久的に禁止（human 承認でも上書き不可）"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `bldgclean.store/Store`. Pure -- never mutates
  the store, never dispatches a robot action, never performs
  building-cleaning work."
  [request context proposal store]
  (let [site-record (store/site store (:site-id request))
        w (some->> (:worker-id proposal) (store/worker store))
        hard (hard-violations {:request request :proposal proposal} site-record w)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        cost (:cost proposal)
        over-threshold? (and (= :coordinate-supply-order (:op proposal))
                              (number? cost) (> cost supply-order-cost-threshold))
        always-risky? (or (contains? always-escalate-ops (:op proposal)) over-threshold?)]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))
