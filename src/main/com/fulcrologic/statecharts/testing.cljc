(ns com.fulcrologic.statecharts.testing
  "Utility functions to help with testing state charts."
  (:require
    [clojure.set :as set]
    [clojure.test :refer [is]]
    [com.fulcrologic.statecharts.algorithms.v20150901-impl :refer [configuration-problems]]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.data-model.working-memory-data-model :refer [new-flat-model]]
    [com.fulcrologic.statecharts.events :refer [new-event]]
    [com.fulcrologic.statecharts.protocols :as sp]
    [com.fulcrologic.statecharts.simple :as simple]
    [com.fulcrologic.statecharts.chart :as chart]))

(defprotocol Clearable
  (clear! [this] "Clear the recordings of the given mock"))

(defprotocol ExecutionChecks
  (ran? [execution-model fref]
    "Returns true if there was an attempt to run the function with reference `fref`")
  (ran-in-order? [execution-model frefs]
    "Returns true if there was an attempt to run every function reference `frefs` (a vector), in the order listed.
     Note, this only requires the functions be called in that relative order, and is fine if there
     are other calls to (other) things in between."))

(defn- has-element?
  "Does sequence v contain element `ele`?"
  [v ele]
  (boolean (seq (filter #(= ele %) v))))

(defrecord MockExecutionModel [data-model
                               expressions-seen
                               call-counts
                               mocks]
  Clearable
  (clear! [_]
    (reset! expressions-seen [])
    (reset! call-counts {}))
  ExecutionChecks
  (ran? [_ fref] (contains? @call-counts fref))
  (ran-in-order? [_ frefs]
    (loop [remaining @expressions-seen
           to-find   frefs]
      (let [found?       (has-element? remaining to-find)
            remainder    (drop-while #(not= to-find %) remaining)
            left-to-find (drop 1 to-find)]
        (cond
          (and found? (empty? left-to-find)) true
          (and found? (seq left-to-find) (seq remainder)) (recur remainder left-to-find)
          :else false))))
  sp/ExecutionModel
  (run-expression! [_model env expr]
    (swap! expressions-seen conj expr)
    (swap! call-counts update expr (fnil inc 0))
    (cond
      (fn? (get @mocks expr)) (let [mock (get @mocks expr)]
                                (mock (assoc env :ncalls (get @expressions-seen expr))))
      (contains? @mocks expr) (get @mocks expr))))

(defn new-mock-execution
  "Create a mock exection model. Records the expressions seen. If the expression has an
   entry in the supplied `mocks` map, then if it is a fn, it will be called with `env` which
   will contain the normal stuff along with `:ncalls` (how many times that expression has been
   called). Otherwise the value in the map is returned.

   You can use `ran?`, `ran-in-order?` and other ExecutionChecks to do verifications.
   "
  ([data-model]
   (->MockExecutionModel data-model (atom []) (atom {}) (atom {})))
  ([data-model mocks]
   (->MockExecutionModel data-model (atom []) (atom {}) (atom mocks))))

(defprotocol SendChecks
  (sent? [_ required-elements-of-send-request]
    "Checks that a send request was submitted that had at LEAST the elements of
     `required-elements-of-send-request` in it. If the value of an element in the
     required elelments is a `fn?`, then it will be treated as a predicate.")
  (cancelled? [_ session-id send-id]
    "Checks that an attempt was made to cancel the send with the given id."))

(defrecord MockEventQueue [sends-seen cancels-seen]
  Clearable
  (clear! [_]
    (reset! sends-seen [])
    (reset! cancels-seen []))
  SendChecks
  (sent? [_ req]
    (some
      (fn [send]
        (let [sent (select-keys send (keys req))]
          (= sent req)))
      @sends-seen))
  (cancelled? [_ session-id send-id]
    (has-element? @cancels-seen {:send-id    send-id
                                 :session-id session-id}))
  sp/EventQueue
  (send! [this env send-request] (swap! sends-seen conj send-request))
  (cancel! [this env session-id send-id] (swap! cancels-seen conj {:send-id    send-id
                                                                   :session-id session-id}))
  (receive-events! [this env handler opts]))

(defn new-mock-queue
  "Create an event queue that simply records what it sees. Use `sent?` and `cancelled?` to
   check these whenever you want."
  []
  (->MockEventQueue (atom []) (atom [])))

(defrecord TestingEnv [statechart env]
  sp/EventQueue
  (send! [_ e r] (sp/send! (::sc/event-queue env) e r))
  (cancel! [_ e s si] (sp/cancel! (::sc/event-queue env) e s si))
  (receive-events! [_ _ _ _])
  sp/Processor
  (start! [_ env k options] (sp/start! (::sc/processor env) env k options))
  (process-event! [_ env wm evt] (sp/process-event! (::sc/processor env) env wm evt))
  SendChecks
  (sent? [_ eles] (sent? (::sc/event-queue env) eles))
  (cancelled? [_ s si] (cancelled? (::sc/event-queue env) s si))
  ExecutionChecks
  (ran? [_ fref] (ran? (::sc/execution-model env) fref))
  (ran-in-order? [_ frefs] (ran-in-order? (::sc/execution-model env) frefs)))

(defn new-testing-env
  "Returns a new testing `env` that can be used to run events against a state chart or otherwise
   manipulate it for tests.

   `mocks` is a map from expression *value* (e.g. fn ref) to either a literal value or a `(fn [env])`, where
   `env` will be the testing env with :ncalls set to the ordinal (from 1) number of times that expression
   has been run.

   `validator` is a `(fn [machine working-memory] vector?)` that returns a list of problems with the
   state machine's configuration (if any). These problems indicate either a bug in the underlying
   implementation, or with your initial setup of working memory.

   The default data model is the flat working memory model, the default processor is the v20150901 version,
   and the validator checks things according to that same version.
   "
  [{:keys [statechart processor-factory data-model-factory validator]
    :or   {data-model-factory new-flat-model
           validator          configuration-problems}} mocks]
  (let [data-model (data-model-factory)
        mock-queue (new-mock-queue)
        exec-model (new-mock-execution data-model (or mocks {}))
        env        (simple/simple-env (cond-> {:statechart          statechart
                                               ::sc/execution-model exec-model
                                               ::sc/data-model      data-model
                                               ::sc/event-queue     mock-queue}
                                        validator (assoc :configuration-validator validator)
                                        processor-factory (assoc ::sc/processor (processor-factory))))]
    (simple/register! env ::chart statechart)
    (map->TestingEnv {:statechart statechart :env env})))

(defn configuration-for-states
  "Returns a set of states that *should* represent a valid configuration of `statechart` in the testing
   env AS LONG AS you list a valid set of leaf states. For example, if you have a top-level parallel state,
   then `states` MUST contain a valid atomic state for each sub-region.

   Another way to express this is that this function returns the union of the set of `states`
   and their proper ancestors."
  [{:keys [statechart]} states]
  (into (set/union (set states) #{})
    (mapcat (fn [sid] (chart/get-proper-ancestors statechart sid)))
    states))

(defn goto-configuration!
  "Runs the given data-ops on the data model, then sets the working memory configuration to a configuration
   that contains the given `leaf-states`. This attempts to include parent states, but you can still generate
   an invalid configuration be listing too few leaves in a parallel system.

   NOTE: You can see the full configuration by directly calling `configuration-for-states`
   on your own `leaf-states`.

   Previously recorded state history (via history nodes) is UNAFFECTED.

   Throws if the configuration validator returns a non-empty problems list."
  [{{::sc/keys [data-model working-memory-store]
     :keys     [statechart configuration-validator] :as env} :env} data-ops leaf-states]
  (let [wmem          (sp/get-working-memory working-memory-store env :test)
        configuration (configuration-for-states env leaf-states)
        vwmem         (volatile! wmem)]
    (when (seq data-ops)
      ;; WMDM expects there to be a volatile version of working memory in env
      (sp/update! data-model (assoc env ::sc/vwmem vwmem) {:ops data-ops}))
    (vswap! vwmem assoc ::sc/configuration configuration)
    (when configuration-validator
      (when-let [problems (seq (configuration-validator statechart @vwmem))]
        (throw (ex-info "Invalid configuration!" {:configuration configuration
                                                  :problems      problems}))))
    (sp/save-working-memory! working-memory-store env :test @vwmem)))

(defn start!
  "Start the machine in the testing env."
  [{{::sc/keys [working-memory-store processor] :as env} :env}]
  (let [s0 (sp/start! processor env ::chart {::sc/session-id :test})]
    (sp/save-working-memory! working-memory-store env :test s0)))

(defn run-events!
  "Run the given sequence of events (names or maps) against the testing runtime. Returns the
   updated working memory (side effects against testing runtime, so you can run this multiple times
   to progressively walk the machine)"
  [{{::sc/keys [processor working-memory-store] :as env} :env} & events]
  (doseq [e events]
    (let [wmem  (sp/get-working-memory working-memory-store env :test)
          wmem2 (sp/process-event! processor env wmem (new-event e))]
      (sp/save-working-memory! working-memory-store env :test wmem2)))
  (sp/get-working-memory working-memory-store env :test))

(defn in?
  "Check to see that the machine in the testing-env is in the given state."
  [{{::sc/keys [working-memory-store] :as env} :env} state-name]
  (let [wmem (sp/get-working-memory working-memory-store env :test)]
    (contains? (get wmem ::sc/configuration) state-name)))

(defn will-send
  "Test assertions. Find `event-name` on the sends seen, and verify it will be sent after the
   given delay-ms. Also ensures it only occurs ONCE."
  [{{::sc/keys [event-queue]} :env} event delay-ms]
  (let [{:keys [sends-seen]} event-queue
        sends       (filter #(and (= (:event %) event) %) @sends-seen)
        event-seen? (boolean (seq sends))
        has-delay?  (boolean (some #(= delay-ms (:delay %)) (vec sends)))]
    (is (= 1 (count sends)))
    (is event-seen?)
    (is has-delay?)
    true))

(defn data
  "Returns the current data of the active data model. Ensures that working memory data models will
   function properly."
  [{{::sc/keys [data-model working-memory-store] :as env} :env}]
  (sp/current-data data-model (assoc env ::sc/vwmem (volatile! (sp/get-working-memory working-memory-store env :test)))))
