(ns metabase.pulse.send
  "Code related to sending Pulses (Alerts or Dashboard Subscriptions)."
  (:require
   [metabase.api.common :as api]
   [metabase.channel.core :as channel]
   [metabase.events :as events]
   [metabase.models.dashboard :as dashboard :refer [Dashboard]]
   [metabase.models.dashboard-card :as dashboard-card]
   [metabase.models.database :refer [Database]]
   [metabase.models.interface :as mi]
   [metabase.models.params.shared :as shared.params]
   [metabase.models.pulse :as models.pulse :refer [Pulse]]
   [metabase.models.serialization :as serdes]
   [metabase.models.task-history :as task-history]
   [metabase.pulse.parameters :as pulse-params]
   [metabase.pulse.util :as pu]
   [metabase.query-processor.timezone :as qp.timezone]
   [metabase.server.middleware.session :as mw.session]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [metabase.util.retry :as retry]
   [metabase.util.ui-logic :as ui-logic]
   [metabase.util.urls :as urls]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- merge-default-values
  "For the specific case of Dashboard Subscriptions we should use `:default` parameter values as the actual `:value` for
  the parameter if none is specified. Normally the FE client will take `:default` and pass it in as `:value` if it
  wants to use it (see #20503 for more details) but this obviously isn't an option for Dashboard Subscriptions... so
  go thru `parameters` and change `:default` to `:value` unless a `:value` is explicitly specified."
  [parameters]
  (for [{default-value :default, :as parameter} parameters]
    (merge
     (when default-value
       {:value default-value})
     (dissoc parameter :default))))

(defn virtual-card-of-type?
  "Check if dashcard is a virtual with type `ttype`, if `true` returns the dashcard, else returns `nil`.

  There are currently 4 types of virtual card: \"text\", \"action\", \"link\", \"placeholder\"."
  [dashcard ttype]
  (when (= ttype (get-in dashcard [:visualization_settings :virtual_card :display]))
    dashcard))

(defn- link-card-entity->url
  [{:keys [db_id id model] :as _entity}]
  (case model
    "card"       (urls/card-url id)
    "dataset"    (urls/card-url id)
    "collection" (urls/collection-url id)
    "dashboard"  (urls/dashboard-url id)
    "database"   (urls/database-url id)
    "table"      (urls/table-url db_id id)))

(defn- link-card->text-part
  [{:keys [entity url] :as _link-card}]
  (let [url-link-card? (some? url)]
    {:text (str (format
                 "### [%s](%s)"
                 (if url-link-card? url (:name entity))
                 (if url-link-card? url (link-card-entity->url entity)))
                (when-let [description (if url-link-card? nil (:description entity))]
                  (format "\n%s" description)))
     :type :text}))

(defn- dashcard-link-card->part
  "Convert a dashcard that is a link card to pulse part.

  This function should be executed under pulse's creator permissions."
  [dashcard]
  (assert api/*current-user-id* "Makes sure you wrapped this with a `with-current-user`.")
  (let [link-card (get-in dashcard [:visualization_settings :link])]
    (cond
      (some? (:url link-card))
      (link-card->text-part link-card)

      ;; if link card link to an entity, update the setting because
      ;; the info in viz-settings might be out-of-date
      (some? (:entity link-card))
      (let [{:keys [model id]} (:entity link-card)
            instance           (t2/select-one
                                (serdes/link-card-model->toucan-model model)
                                (dashboard-card/link-card-info-query-for-model model id))]
        (when (mi/can-read? instance)
          (link-card->text-part (assoc link-card :entity instance)))))))

(defn- escape-heading-markdown
  [dashcard]
  (if (= "heading" (get-in dashcard [:visualization_settings :virtual_card :display]))
    ;; If there's no heading text, the heading is empty, so we return nil.
    (when (get-in dashcard [:visualization_settings :text])
      (update-in dashcard [:visualization_settings :text]
                 #(str "## " (shared.params/escape-chars % shared.params/escaped-chars-regex))))
    dashcard))

(defn- dashcard->part
  "Given a dashcard returns its part based on its type.

  The result will follow the pulse's creator permissions."
  [dashcard pulse dashboard]
  (assert api/*current-user-id* "Makes sure you wrapped this with a `with-current-user`.")
  (cond
    (:card_id dashcard)
    (let [parameters (merge-default-values (pulse-params/parameters pulse dashboard))]
      (pu/execute-dashboard-subscription-card dashcard parameters))

    ;; iframes
    (virtual-card-of-type? dashcard "iframe")
    nil

    ;; actions
    (virtual-card-of-type? dashcard "action")
    nil

    ;; link cards
    (virtual-card-of-type? dashcard "link")
    (dashcard-link-card->part dashcard)

    ;; placeholder cards aren't displayed
    (virtual-card-of-type? dashcard "placeholder")
    nil

    ;; text cards have existed for a while and I'm not sure if all existing text cards
    ;; will have virtual_card.display = "text", so assume everything else is a text card
    :else
    (let [parameters (merge-default-values (pulse-params/parameters pulse dashboard))]
      (some-> dashcard
              (pulse-params/process-virtual-dashcard parameters)
              escape-heading-markdown
              :visualization_settings
              (assoc :type :text)))))

(defn- dashcards->part
  [dashcards pulse dashboard]
  (let [ordered-dashcards (sort dashboard-card/dashcard-comparator dashcards)]
    (doall (for [dashcard ordered-dashcards
                 :let     [part (dashcard->part dashcard pulse dashboard)]
                 :when    (some? part)]
             part))))

(defn- tab->part
  [{:keys [name]}]
  {:text name
   :type :tab-title})

(defn- render-tabs?
  "Check if a dashboard has more than 1 tab, and thus needs them to be rendered.
  We don't need to render the tab title if only 1 exists (issue #45123)."
  [dashboard-or-id]
  (< 1 (t2/count :model/DashboardTab :dashboard_id (u/the-id dashboard-or-id))))

(defn- execute-dashboard
  "Fetch all the dashcards in a dashboard for a Pulse, and execute non-text cards.

  The generated parts will follow the pulse's creator permissions."
  [{:keys [skip_if_empty] pulse-creator-id :creator_id :as pulse} dashboard & {:as _options}]
  (let [dashboard-id (u/the-id dashboard)]
    (mw.session/with-current-user pulse-creator-id
      (let [parts (if (render-tabs? dashboard)
                    (let [tabs               (t2/hydrate (t2/select :model/DashboardTab :dashboard_id dashboard-id) :tab-cards)
                          tabs-with-cards    (filter #(seq (:cards %)) tabs)
                          should-render-tab? (< 1 (count tabs-with-cards))]
                      (doall (flatten (for [{:keys [cards] :as tab} tabs-with-cards]
                                        (concat
                                         (when should-render-tab?
                                           [(tab->part tab)])
                                         (dashcards->part cards pulse dashboard))))))
                    (dashcards->part (t2/select :model/DashboardCard :dashboard_id dashboard-id) pulse dashboard))]
        (if skip_if_empty
          ;; Remove cards that have no results when empty results aren't wanted
          (remove (fn [{part-type :type :as part}]
                    (and
                     (= part-type :card)
                     (zero? (get-in part [:result :row_count] 0))))
                  parts)
          parts)))))

(defn- database-id [card]
  (or (:database_id card)
      (get-in card [:dataset_query :database])))

(mu/defn defaulted-timezone :- :string
  "Returns the timezone ID for the given `card`. Either the report timezone (if applicable) or the JVM timezone."
  [card :- (ms/InstanceOf :model/Card)]
  (or (some->> card database-id (t2/select-one Database :id) qp.timezone/results-timezone-id)
      (qp.timezone/system-timezone-id)))

(defn- are-all-parts-empty?
  "Do none of the cards have any results?"
  [results]
  (every? pu/is-card-empty? results))

(defn- goal-met? [{:keys [alert_above_goal], :as pulse} [first-result]]
  (let [goal-comparison      (if alert_above_goal >= <)
        goal-val             (ui-logic/find-goal-value first-result)
        comparison-col-rowfn (ui-logic/make-goal-comparison-rowfn (:card first-result)
                                                                  (get-in first-result [:result :data]))]

    (when-not (and goal-val comparison-col-rowfn)
      (throw (ex-info (tru "Unable to compare results to goal for alert.")
                      {:pulse  pulse
                       :result first-result})))
    (boolean
     (some (fn [row]
             (goal-comparison (comparison-col-rowfn row) goal-val))
           (get-in first-result [:result :data :rows])))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Creating Notifications To Send                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- alert-or-pulse [pulse]
  (if (:dashboard_id pulse)
    :pulse
    :alert))

(defmulti ^:private should-send-notification?
  "Returns true if given the pulse type and resultset a new notification (pulse or alert) should be sent"
  (fn [pulse _parts] (alert-or-pulse pulse)))

(defmethod should-send-notification? :alert
  [{:keys [alert_condition] :as alert} parts]
  (cond
    (= "rows" alert_condition)
    (not (are-all-parts-empty? parts))

    (= "goal" alert_condition)
    (goal-met? alert parts)

    :else
    (let [^String error-text (tru "Unrecognized alert with condition ''{0}''" alert_condition)]
      (throw (IllegalArgumentException. error-text)))))

(defmethod should-send-notification? :pulse
  [pulse parts]
  (if (:skip_if_empty pulse)
    (not (are-all-parts-empty? parts))
    true))

(defn- get-notification-info
  [pulse parts pulse-channel]
  (let [alert? (nil? (:dashboard_id pulse))]
    (merge {:payload_type  (if alert?
                             :notification/alert
                             :notification/dashboard-subscription)
            :payload       (if alert? (first parts) parts)
            :pulse         pulse
            :pulse-channel pulse-channel}
           (if alert?
             {:card  (t2/select-one :model/Card (-> parts first :card :id))}
             {:dashboard (t2/select-one :model/Dashboard (:dashboard_id pulse))}))))

(defn- channel-recipients
  [pulse-channel]
  (case (keyword (:channel_type pulse-channel))
    :slack
    [(get-in pulse-channel [:details :channel])]
    :email
    (for [recipient (:recipients pulse-channel)]
      (if-not (:id recipient)
        {:kind :external-email
         :email (:email recipient)}
        {:kind :user
         :user recipient}))
    :http
    []
    (do
      (log/warnf "Unknown channel type %s" (:channel_type pulse-channel))
      [])))

(defn- should-retry-sending?
  [exception channel-type]
  (not (and (= :channel/slack channel-type)
            (contains? (:errors (ex-data exception)) :slack-token))))

(defn- format-channel
  [{:keys [type id]}]
  (if id
    (str (name type) " " id)
    (name type)))

(defn- send-retrying!
  [pulse-id channel message]
  (try
    (let [;; once we upgraded to retry 2.x, we can use (.. retry getMetrics getNumberOfTotalCalls) instead of tracking
          ;; this manually
          retry-config (retry/retry-configuration)
          retry-errors (volatile! [])
          retry-report (fn []
                         {:attempted_retries (count @retry-errors)
                          :retry_errors       @retry-errors})
          send!        (fn []
                         (try
                           (channel/send! channel message)
                           (catch Exception e
                             (vswap! retry-errors conj e)
                             ;; Token errors have already been logged and we should not retry.
                             (when (should-retry-sending? e (:type channel))
                               (log/warnf e "[Pulse %d] Failed to send to channel %s , retrying..." pulse-id (format-channel channel))
                               (throw e)))))]
      (task-history/with-task-history {:task            "channel-send"
                                       :on-success-info (fn [update-map _result]
                                                          (cond-> update-map
                                                            (seq @retry-errors)
                                                            (update :task_details merge (retry-report))))
                                       :on-fail-info    (fn [update-map _result]
                                                          (update update-map :task_details #(merge % (retry-report))))
                                       :task_details    {:retry_config retry-config
                                                         :channel_type (:type channel)
                                                         :channel_id   (:id channel)
                                                         :pulse_id     pulse-id}}
        ((retry/decorate send! (retry/random-exponential-backoff-retry (str (random-uuid)) retry-config)))
        (log/debugf "[Pulse %d] Sent to channel %s with %d retries" pulse-id (format-channel channel) (count @retry-errors))))
    (catch Throwable e
      (log/errorf e "[Pulse %d] Error sending notification!" pulse-id))))

(defn- execute-pulse
  [{:keys [cards] pulse-id :id :as pulse} dashboard]
  (if dashboard
    ;; send the dashboard
    (execute-dashboard pulse dashboard)
    ;; send the cards instead
    (for [card cards
          ;; Pulse ID may be `nil` if the Pulse isn't saved yet
          :let [part (pu/execute-card pulse (u/the-id card) :pulse-id pulse-id)]
          ;; some cards may return empty part, e.g. if the card has been archived
          :when part]
      part)))

(defn- pc->channel
  "Given a pulse channel, return the channel object.

  Only supports HTTP channels for now, returns a map with type key for slack and email"
  [{channel-type :channel_type :as pulse-channel}]
  (if (= :http (keyword channel-type))
    (t2/select-one :model/Channel :id (:channel_id pulse-channel))
    {:type (keyword "channel" (name channel-type))}))

(defn- send-pulse!*
  [{:keys [channels channel-ids] pulse-id :id :as pulse} dashboard]
  (let [parts                  (execute-pulse pulse dashboard)
        ;; `channel-ids` is the set of channels to send to now, so only send to those. Note the whole set of channels
        channels               (if (seq channel-ids)
                                 (filter #((set channel-ids) (:id %)) channels)
                                 channels)]
    (if (should-send-notification? pulse parts)
      (let [event-type (if (= :pulse (alert-or-pulse pulse))
                         :event/subscription-send
                         :event/alert-send)]
        (events/publish-event! event-type {:id      (:id pulse)
                                           :user-id (:creator_id pulse)
                                           :object  {:recipients (map :recipients (:channels pulse))
                                                     :filters    (:parameters pulse)}})
        (u/prog1 (doseq [pulse-channel channels]
                   (try
                     (let [channel  (pc->channel pulse-channel)
                           messages (channel/render-notification (:type channel)
                                                                 (get-notification-info pulse parts pulse-channel)
                                                                 nil
                                                                 (channel-recipients pulse-channel))]
                       (log/debugf "[Pulse %d] Rendered %d messages for channel %s"
                                   pulse-id
                                   (count messages)
                                   (format-channel channel))
                       (doseq [message messages]
                         (log/debugf "[Pulse %d] Sending to channel %s"
                                     pulse-id
                                     (:channel_type pulse-channel))
                         (send-retrying! pulse-id channel message)))
                     (catch Exception e
                       (log/errorf e "[Pulse %d] Error sending to %s channel" (:id pulse) (:channel_type pulse-channel)))))
          (when (:alert_first_only pulse)
            (t2/delete! Pulse :id pulse-id))))
      (log/infof "Skipping sending %s %d" (alert-or-pulse pulse) (:id pulse)))))

(defn send-pulse!
  "Execute and Send a `Pulse`, optionally specifying the specific `PulseChannels`.  This includes running each
   `PulseCard`, formatting the content, and sending the content to any specified destination.

  `channel-ids` is the set of channel IDs to send to *now* -- this may be a subset of the full set of channels for
  the Pulse.

   Example:

    (send-pulse! pulse)                    ; Send to all Channels
    (send-pulse! pulse :channel-ids [312]) ; Send only to Channel with :id = 312"
  [{:keys [dashboard_id], :as pulse} & {:keys [channel-ids]}]
  {:pre [(map? pulse) (integer? (:creator_id pulse))]}
  (let [dashboard (t2/select-one Dashboard :id dashboard_id)
        pulse     (-> (mi/instance Pulse pulse)
                      ;; This is usually already done by this step, in the `send-pulses` task which uses `retrieve-pulse`
                      ;; to fetch the Pulse.
                      models.pulse/hydrate-notification
                      (merge (when channel-ids {:channel-ids channel-ids})))]
    (when (not (:archived dashboard))
      (send-pulse!* pulse dashboard))))
