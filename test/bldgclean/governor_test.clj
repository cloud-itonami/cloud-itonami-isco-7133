(ns bldgclean.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [bldgclean.store :as store]
            [bldgclean.advisor :as advisor]
            [bldgclean.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-site! st {:site-id "S-1" :name "Riverside Tower Facade Wash" :address "42 Elm St"})
    (store/register-worker! st {:worker-id "W-1" :site-id "S-1" :name "Kobo Cleaner" :role :crew-lead})
    st))

(def ^:private req {:site-id "S-1"})

(defn- log-op []
  {:op :log-work-record :effect :propose :site-id "S-1" :worker-id "W-1"
   :task "wash north facade windows floors 1-10" :confidence 0.9 :stake :low
   :rationale "proposed log-work-record for site S-1"})

(defn- schedule-op []
  {:op :schedule-crew-operation :effect :propose :site-id "S-1" :worker-id "W-1"
   :task "rig descent equipment for east facade" :confidence 0.9 :stake :low
   :rationale "proposed schedule-crew-operation for site S-1"})

(defn- safety-op []
  {:op :flag-safety-concern :effect :propose :site-id "S-1" :worker-id "W-1"
   :concern-type :fall-hazard :severity :high :confidence 0.9 :stake :low
   :rationale "proposed flag-safety-concern for site S-1"})

(defn- supply-op [cost]
  {:op :coordinate-supply-order :effect :propose :site-id "S-1"
   :materials "cleaning solution and descent rigging" :cost cost :confidence 0.9 :stake :low
   :rationale "proposed coordinate-supply-order for site S-1"})

(deftest ok-log-work-record-for-registered-site-and-worker
  (let [st (fresh-store)
        v (governor/check req {} (log-op) st)]
    (is (:ok? v))))

(deftest ok-schedule-crew-operation-for-registered-worker
  (let [st (fresh-store)
        v (governor/check req {} (schedule-op) st)]
    (is (:ok? v))))

(deftest ok-supply-order-at-or-below-cost-threshold
  (testing "the supply-order cost threshold is inclusive of no-escalation"
    (let [st (fresh-store)
          v (governor/check req {} (supply-op governor/supply-order-cost-threshold) st)]
      (is (:ok? v))
      (is (not (:escalate? v))))))

(deftest hard-on-unregistered-site
  (let [st (fresh-store)
        v (governor/check {:site-id "S-ghost"} {} (assoc (log-op) :site-id "S-ghost") st)]
    (is (:hard? v))
    (is (some #(= :no-site (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-unknown-op
  (testing "closed op-allowlist enforced -- no op finalizes a cleaning-execution decision (including a height-work go/no-go call) or overrides safety authority"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op) :op :finalize-cleaning-execution) st)]
      (is (:hard? v))
      (is (some #(= :unknown-op (:rule %)) (:violations v))))))

(deftest hard-on-site-mismatch
  (let [st (fresh-store)]
    (store/register-site! st {:site-id "S-2" :name "Other Site" :address "9 Oak Ave"})
    (let [v (governor/check req {} (assoc (log-op) :site-id "S-2") st)]
      (is (:hard? v))
      (is (some #(= :site-mismatch (:rule %)) (:violations v))))))

(deftest hard-on-unknown-worker
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :worker-id "W-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-worker (:rule %)) (:violations v)))))

(deftest hard-on-worker-wrong-site
  (let [st (fresh-store)]
    (store/register-site! st {:site-id "S-2" :name "Other Site" :address "9 Oak Ave"})
    (store/register-worker! st {:worker-id "W-2" :site-id "S-2" :name "Other Worker" :role :crew})
    (let [v (governor/check req {} (assoc (log-op) :worker-id "W-2") st)]
      (is (:hard? v))
      (is (some #(= :worker-wrong-site (:rule %)) (:violations v))))))

(deftest hard-and-permanent-on-scope-exclusion-finalize-cleaning-execution
  (testing "a proposal to directly finalize a cleaning-execution decision is a hard, permanent block"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op)
                                          :confidence 0.99
                                          :rationale "proceed with the height-work cleaning task now, skip further review")
                            st)]
      (is (:hard? v))
      (is (not (:escalate? v)) "hard violations are never merely escalated -- no human-approval override path")
      (is (some #(= :scope-exclusion-violation (:rule %)) (:violations v))))))

(deftest hard-and-permanent-on-scope-exclusion-height-work-go-no-go
  (testing "a proposal to directly finalize a height-work go/no-go decision is a hard, permanent block"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (log-op)
                                          :confidence 0.99
                                          :rationale "finalize the height-work go/no-go decision and dispatch the crew")
                            st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (some #(= :scope-exclusion-violation (:rule %)) (:violations v))))))

(deftest hard-and-permanent-on-scope-exclusion-override-safety-officer
  (testing "a proposal to override a site-safety officer's judgment is a hard, permanent block"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (safety-op)
                                          :confidence 0.99
                                          :rationale "override the site safety officer's judgment and proceed anyway")
                            st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (some #(= :scope-exclusion-violation (:rule %)) (:violations v))))))

(deftest hard-and-permanent-on-scope-exclusion-bypass-chemical-safety-review
  (testing "a proposal to bypass the chemical-exposure safety review is a hard, permanent block"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (supply-op 500)
                                          :confidence 0.99
                                          :rationale "bypass the chemical-exposure safety review and issue the order")
                            st)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (some #(= :scope-exclusion-violation (:rule %)) (:violations v))))))

(deftest scope-exclusion-not-triggered-by-bare-domain-nouns
  (testing "bare nouns like 'height'/'cleaning'/'chemical' are ordinary domain vocabulary, not finalization/override actions"
    (let [proposal {:rationale "proposed schedule-crew-operation for building facade cleaning at height"
                     :description "crew fully trained on fall-protection equipment and chemical cleaning-solution handling for this facade"}]
      (is (not (governor/scope-exclusion-violation? proposal))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the mock advisor's own default rationale text, across every allowlisted op, never trips the scope-exclusion guard"
    (let [st (fresh-store)
          adv (advisor/mock-advisor)
          requests [{:site-id "S-1" :op :log-work-record :worker-id "W-1" :task "wash north facade windows"}
                    {:site-id "S-1" :op :schedule-crew-operation :worker-id "W-1" :task "rig descent equipment for east facade"}
                    {:site-id "S-1" :op :flag-safety-concern :worker-id "W-1"
                     :concern-type :fall-hazard :severity :high
                     :description "loose scaffold plank near the building edge at height"}
                    {:site-id "S-1" :op :flag-safety-concern :worker-id "W-1"
                     :concern-type :chemical-exposure :severity :medium
                     :description "strong cleaning-solution odor near the loading dock"}
                    {:site-id "S-1" :op :coordinate-supply-order :materials "cleaning solution and descent rigging"
                     :cost 2500}]]
      (doseq [request requests]
        (let [proposal (advisor/-advise adv st request)]
          (is (not (governor/scope-exclusion-violation? proposal))
              (str "self-tripped on default rationale for " (:op request) ": " (pr-str proposal))))))))

(deftest always-escalates-flag-safety-concern-even-at-high-confidence
  (testing "a surfaced fall-hazard/weather-condition/equipment-condition/chemical-exposure concern always requires human review"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (safety-op) :confidence 0.99) st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest always-escalates-supply-order-above-cost-threshold-even-at-high-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (supply-op (+ 1 governor/supply-order-cost-threshold)) :confidence 0.99) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (log-op) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
