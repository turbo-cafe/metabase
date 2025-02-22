(ns metabase.email.messages
  "Convenience functions for sending templated email messages.  Each function here should represent a single email.
   NOTE: we want to keep this about email formatting, so don't put heavy logic here RE: building data for emails.

  NOTE: This namespace is deprecated, all of these emails will soon be converted to System Email Notifications."
  (:require
   [buddy.core.codecs :as codecs]
   [cheshire.core :as json]
   [clojure.core.cache :as cache]
   [hiccup.core :refer [html]]
   [java-time.api :as t]
   [medley.core :as m]
   [metabase.config :as config]
   [metabase.db.query :as mdb.query]
   [metabase.driver :as driver]
   [metabase.email :as email]
   [metabase.email.result-attachment :as email.result-attachment]
   [metabase.lib.util :as lib.util]
   [metabase.models.collection :as collection]
   [metabase.models.data-permissions :as data-perms]
   [metabase.models.permissions :as perms]
   [metabase.models.user :refer [User]]
   [metabase.public-settings :as public-settings]
   [metabase.public-settings.premium-features :as premium-features]
   [metabase.pulse.core :as pulse]
   [metabase.query-processor.timezone :as qp.timezone]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [metabase.util.encryption :as encryption]
   [metabase.util.i18n :as i18n :refer [trs tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.markdown :as markdown]
   [metabase.util.urls :as urls]
   [stencil.core :as stencil]
   [stencil.loader :as stencil-loader]
   [toucan2.core :as t2])
  (:import
   (java.time LocalTime)
   (java.time.format DateTimeFormatter)))

(set! *warn-on-reflection* true)

(defn app-name-trs
  "Return the user configured application name, or Metabase translated
  via trs if a name isn't configured."
  []
  (or (public-settings/application-name)
      (trs "Metabase")))

;; Dev only -- disable template caching
(when config/is-dev?
  (alter-meta! #'stencil/render-file assoc :style/indent 1)
  (stencil-loader/set-cache (cache/ttl-cache-factory {} :ttl 0)))

(defn logo-url
  "Return the URL for the application logo. If the logo is the default, return a URL to the Metabase logo."
  []
  (let [url (public-settings/application-logo-url)]
    (cond
      (= url "app/assets/img/logo.svg") "http://static.metabase.com/email_logo.png"

      :else nil)))
      ;; NOTE: disabling whitelabeled URLs for now since some email clients don't render them correctly
      ;; We need to extract them and embed as attachments like we do in metabase.pulse.render.image-bundle
      ;; (data-uri-svg? url)               (themed-image-url url color)
      ;; :else                             url

(defn- icon-bundle
  "Bundle an icon.

  The available icons are defined in [[js-svg/icon-paths]]."
  [icon-name]
  (let [color     (pulse/primary-color)
        png-bytes (pulse/icon icon-name color)]
    (-> (pulse/make-image-bundle :attachment png-bytes)
        (pulse/image-bundle->attachment))))

(defn button-style
  "Return a CSS style string for a button with the given color."
  [color]
  (str "display: inline-block; "
       "box-sizing: border-box; "
       "padding: 0.5rem 1.375rem; "
       "font-size: 1.063rem; "
       "font-weight: bold; "
       "text-decoration: none; "
       "cursor: pointer; "
       "color: #fff; "
       "border: 1px solid " color "; "
       "background-color: " color "; "
       "border-radius: 4px;"))

;;; Various Context Helper Fns. Used to build Stencil template context

(defn common-context
  "Context that is used across multiple email templates, and that is the same for all emails"
  []
  {:applicationName           (public-settings/application-name)
   :applicationColor          (pulse/primary-color)
   :applicationLogoUrl        (logo-url)
   :buttonStyle               (button-style (pulse/primary-color))
   :colorTextLight            pulse/color-text-light
   :colorTextMedium           pulse/color-text-medium
   :colorTextDark             pulse/color-text-dark
   :siteUrl                   (public-settings/site-url)})

;;; ### Public Interface

(defn send-new-user-email!
  "Send an email to `invitied` letting them know `invitor` has invited them to join Metabase."
  [invited invitor join-url sent-from-setup?]
  (let [company      (or (public-settings/site-name) "Unknown")
        message-body (stencil/render-file "metabase/email/new_user_invite"
                                          (merge (common-context)
                                                 {:emailType     "new_user_invite"
                                                  :invitedName   (or (:first_name invited) (:email invited))
                                                  :invitorName   (or (:first_name invitor) (:email invitor))
                                                  :invitorEmail  (:email invitor)
                                                  :company       company
                                                  :joinUrl       join-url
                                                  :today         (t/format "MMM'&nbsp;'dd,'&nbsp;'yyyy" (t/zoned-date-time))
                                                  :logoHeader    true
                                                  :sentFromSetup sent-from-setup?}))]
    (email/send-message!
     {:subject      (str (trs "You''re invited to join {0}''s {1}" company (app-name-trs)))
      :recipients   [(:email invited)]
      :message-type :html
      :message      message-body})))

(defn- all-admin-recipients
  "Return a sequence of email addresses for all Admin users.

  The first recipient will be the site admin (or oldest admin if unset), which is the address that should be used in
  `mailto` links (e.g., for the new user to email with any questions)."
  []
  (concat (when-let [admin-email (public-settings/admin-email)]
            [admin-email])
          (t2/select-fn-set :email 'User, :is_superuser true, :is_active true, {:order-by [[:id :asc]]})))

(defn send-user-joined-admin-notification-email!
  "Send an email to the `invitor` (the Admin who invited `new-user`) letting them know `new-user` has joined."
  [new-user & {:keys [google-auth?]}]
  {:pre [(map? new-user)]}
  (let [recipients (all-admin-recipients)]
    (email/send-message!
     {:subject      (str (if google-auth?
                           (trs "{0} created a {1} account" (:common_name new-user) (app-name-trs))
                           (trs "{0} accepted their {1} invite" (:common_name new-user) (app-name-trs))))
      :recipients   recipients
      :message-type :html
      :message      (stencil/render-file "metabase/email/user_joined_notification"
                                         (merge (common-context)
                                                {:logoHeader        true
                                                 :joinedUserName    (or (:first_name new-user) (:email new-user))
                                                 :joinedViaSSO      google-auth?
                                                 :joinedUserEmail   (:email new-user)
                                                 :joinedDate        (t/format "EEEE, MMMM d" (t/zoned-date-time)) ; e.g. "Wednesday, July 13".
                                                 :adminEmail        (first recipients)
                                                 :joinedUserEditUrl (str (public-settings/site-url) "/admin/people")}))})))

(defn send-password-reset-email!
  "Format and send an email informing the user how to reset their password."
  [email sso-source password-reset-url is-active?]
  {:pre [(u/email? email)
         ((some-fn string? nil?) password-reset-url)]}
  (let [google-sso? (= "google" sso-source)
        message-body (stencil/render-file
                      "metabase/email/password_reset"
                      (merge (common-context)
                             {:emailType        "password_reset"
                              :google           google-sso?
                              :nonGoogleSSO     (and (not google-sso?) (some? sso-source))
                              :passwordResetUrl password-reset-url
                              :logoHeader       true
                              :isActive         is-active?
                              :adminEmail       (public-settings/admin-email)
                              :adminEmailSet    (boolean (public-settings/admin-email))}))]
    (email/send-message!
     {:subject      (trs "[{0}] Password Reset Request" (app-name-trs))
      :recipients   [email]
      :message-type :html
      :message      message-body})))

(mu/defn send-login-from-new-device-email!
  "Format and send an email informing the user that this is the first time we've seen a login from this device. Expects
  login history information as returned by `metabase.models.login-history/human-friendly-infos`."
  [{user-id :user_id, :keys [timestamp], :as login-history} :- [:map [:user_id pos-int?]]]
  (let [user-info    (or (t2/select-one ['User [:first_name :first-name] :email :locale] :id user-id)
                         (throw (ex-info (tru "User {0} does not exist" user-id)
                                         {:user-id user-id, :status-code 404})))
        user-locale  (or (:locale user-info) (i18n/site-locale))
        timestamp    (u.date/format-human-readable timestamp user-locale)
        context      (merge (common-context)
                            {:first-name (:first-name user-info)
                             :device     (:device_description login-history)
                             :location   (:location login-history)
                             :timestamp  timestamp})
        message-body (stencil/render-file "metabase/email/login_from_new_device"
                                          context)]
    (email/send-message!
     {:subject      (trs "We''ve Noticed a New {0} Login, {1}" (app-name-trs) (:first-name user-info))
      :recipients   [(:email user-info)]
      :message-type :html
      :message      message-body})))

(defn- admin-or-ee-monitoring-details-emails
  "Find emails for users that have an interest in monitoring the database.
   If oss that means admin users.
   If ee that also means users with monitoring and details permissions."
  [database-id]
  (let [monitoring (perms/application-perms-path :monitoring)
        user-ids-with-monitoring (when (premium-features/enable-advanced-permissions?)
                                   (->> {:select   [:pgm.user_id]
                                         :from     [[:permissions_group_membership :pgm]]
                                         :join     [[:permissions_group :pg] [:= :pgm.group_id :pg.id]]
                                         :where    [:and
                                                    [:exists {:select [1]
                                                              :from [[:permissions :p]]
                                                              :where [:and
                                                                      [:= :p.group_id :pg.id]
                                                                      [:= :p.object monitoring]]}]]
                                         :group-by [:pgm.user_id]}
                                        mdb.query/query
                                        (mapv :user_id)))
        user-ids (filter
                  #(data-perms/user-has-permission-for-database? % :perms/manage-database :yes database-id)
                  user-ids-with-monitoring)]
    (into
     []
     (distinct)
     (concat
      (all-admin-recipients)
      (when (seq user-ids)
        (t2/select-fn-set :email User {:where [:and
                                               [:= :is_active true]
                                               [:in :id user-ids]]}))))))

(defn send-persistent-model-error-email!
  "Format and send an email informing the user about errors in the persistent model refresh task."
  [database-id persisted-infos trigger]
  {:pre [(seq persisted-infos)]}
  (let [database (:database (first persisted-infos))
        emails (admin-or-ee-monitoring-details-emails database-id)
        timezone (some-> database qp.timezone/results-timezone-id t/zone-id)
        context {:database-name (:name database)
                 :errors
                 (for [[idx persisted-info] (m/indexed persisted-infos)
                       :let [card (:card persisted-info)
                             collection (or (:collection card)
                                            (collection/root-collection-with-ui-details nil))]]
                   {:is-not-first (not= 0 idx)
                    :error (:error persisted-info)
                    :card-id (:id card)
                    :card-name (:name card)
                    :collection-name (:name collection)
                    ;; February 1, 2022, 3:10 PM
                    :last-run-at (t/format "MMMM d, yyyy, h:mm a z" (t/zoned-date-time (:refresh_begin persisted-info) timezone))
                    :last-run-trigger trigger
                    :card-url (urls/card-url (:id card))
                    :collection-url (urls/collection-url (:id collection))
                    :caching-log-details-url (urls/tools-caching-details-url (:id persisted-info))})}
        message-body (stencil/render-file "metabase/email/persisted-model-error"
                                          (merge (common-context) context))]
    (when (seq emails)
      (email/send-message!
       {:subject      (trs "[{0}] Model cache refresh failed for {1}" (app-name-trs) (:name database))
        :recipients   (vec emails)
        :message-type :html
        :message      message-body}))))

(defn send-follow-up-email!
  "Format and send an email to the system admin following up on the installation."
  [email]
  {:pre [(u/email? email)]}
  (let [context (merge (common-context)
                       {:emailType    "notification"
                        :logoHeader   true
                        :heading      (trs "We hope you''ve been enjoying Metabase.")
                        :callToAction (trs "Would you mind taking a quick 5 minute survey to tell us how it’s going?")
                        :link         "https://metabase.com/feedback/active"})
        email {:subject      (trs "[{0}] Tell us how things are going." (app-name-trs))
               :recipients   [email]
               :message-type :html
               :message      (stencil/render-file "metabase/email/follow_up_email" context)}]
    (email/send-message! email)))

(defn send-creator-sentiment-email!
  "Format and send an email to a creator with a link to a survey. If a [[blob]] is included, it will be turned into json
  and then base64 encoded."
  [{:keys [email first_name]} blob]
  {:pre [(u/email? email)]}
  (let [encoded-info    (when blob
                          (-> blob
                              json/generate-string
                              .getBytes
                              codecs/bytes->b64-str))
        context (merge (common-context)
                       {:emailType  "notification"
                        :logoHeader true
                        :first-name first_name
                        :link       (cond-> "https://metabase.com/feedback/creator"
                                      encoded-info (str "?context=" encoded-info))}
                       (when-not (premium-features/is-hosted?)
                         {:self-hosted (public-settings/site-url)}))
        message {:subject      "Metabase would love your take on something"
                 :recipients   [email]
                 :message-type :html
                 :message      (stencil/render-file "metabase/email/creator_sentiment_email" context)}]
    (email/send-message! message)))

(defn- make-message-attachment [[content-id url]]
  {:type         :inline
   :content-id   content-id
   :content-type "image/png"
   :content      url})

(defn- pulse-link-context
  [{:keys [cards dashboard_id]}]
  (when-let [dashboard-id (or dashboard_id
                              (some :dashboard_id cards))]
    {:pulseLink (urls/dashboard-url dashboard-id)}))

(defn generate-pulse-unsubscribe-hash
  "Generates hash to allow for non-users to unsubscribe from pulses/subscriptions."
  [pulse-id email]
  (codecs/bytes->hex
   (encryption/validate-and-hash-secret-key
    (json/generate-string {:salt     (public-settings/site-uuid-for-unsubscribing-url)
                           :email    email
                           :pulse-id pulse-id}))))

(defn- pulse-context [pulse dashboard non-user-email]
  (let [dashboard-id (:id dashboard)]
    (merge (common-context)
           {:emailType                 "pulse"
            :title                     (:name dashboard)
            :titleUrl                  (pulse/dashboard-url dashboard-id (pulse/parameters pulse dashboard))
            :dashboardDescription      (markdown/process-markdown (:description dashboard) :html)
           ;; There are legacy pulses that exist without being tied to a dashboard
            :dashboardHasTabs          (when dashboard-id
                                         (boolean (seq (t2/hydrate dashboard :tabs))))
            :creator                   (-> pulse :creator :common_name)
            :sectionStyle              (pulse/style (pulse/section-style))
            :notificationText          (if (nil? non-user-email)
                                         "Manage your subscriptions"
                                         "Unsubscribe")
            :notificationManagementUrl (if (nil? non-user-email)
                                         (urls/notification-management-url)
                                         (str (urls/unsubscribe-url)
                                              "?hash=" (generate-pulse-unsubscribe-hash (:id pulse) non-user-email)
                                              "&email=" non-user-email
                                              "&pulse-id=" (:id pulse)))}
           (pulse-link-context pulse))))

(defn- part-attachments [parts]
  (filter some? (mapcat email.result-attachment/result-attachment parts)))

(defn- render-part
  [timezone part options]
  (case (:type part)
    :card
    (pulse/render-pulse-section timezone part options)

    :text
    {:content (markdown/process-markdown (:text part) :html)}

    :tab-title
    {:content (markdown/process-markdown (format "# %s\n---" (:text part)) :html)}))

(defn- render-filters
  [notification dashboard]
  (let [filters (pulse/parameters notification dashboard)
        cells   (map
                 (fn [filter]
                   [:td {:class "filter-cell"
                         :style (pulse/style {:width "50%"
                                              :padding "0px"
                                              :vertical-align "baseline"})}
                    [:table {:cellpadding "0"
                             :cellspacing "0"
                             :width "100%"
                             :height "100%"}
                     [:tr
                      [:td
                       {:style (pulse/style {:color pulse/color-text-medium
                                             :min-width "100px"
                                             :width "50%"
                                             :padding "4px 4px 4px 0"
                                             :vertical-align "baseline"})}
                       (:name filter)]
                      [:td
                       {:style (pulse/style {:color pulse/color-text-dark
                                             :min-width "100px"
                                             :width "50%"
                                             :padding "4px 16px 4px 8px"
                                             :vertical-align "baseline"})}
                       (pulse/value-string filter)]]]])
                 filters)
        rows    (partition 2 2 nil cells)]
    (html
     [:table {:style (pulse/style {:table-layout :fixed
                                   :border-collapse :collapse
                                   :cellpadding "0"
                                   :cellspacing "0"
                                   :width "100%"
                                   :font-size  "12px"
                                   :font-weight 700
                                   :margin-top "8px"})}
      (for [row rows]
        [:tr {} row])])))

(defn- render-message-body
  [notification message-type message-context timezone dashboard parts]
  (let [rendered-cards  (mapv #(render-part timezone % {:pulse/include-title? true}) parts)
        icon-name       (case message-type
                          :alert :bell
                          :pulse :dashboard)
        icon-attachment (first (map make-message-attachment (icon-bundle icon-name)))
        filters         (when dashboard
                          (render-filters notification dashboard))
        message-body    (assoc message-context :pulse (html (vec (cons :div (map :content rendered-cards))))
                               :filters filters
                               :iconCid (:content-id icon-attachment))
        attachments     (apply merge (map :attachments rendered-cards))]
    (vec (concat [{:type "text/html; charset=utf-8" :content (stencil/render-file "metabase/email/pulse" message-body)}]
                 (map make-message-attachment attachments)
                 [icon-attachment]
                 (part-attachments parts)))))

(defn- assoc-attachment-booleans [pulse results]
  (for [{{result-card-id :id} :card :as result} results
        :let [pulse-card (m/find-first #(= (:id %) result-card-id) (:cards pulse))]]
    (if result-card-id
      (update result :card merge (select-keys pulse-card [:include_csv :include_xls :format_rows :pivot_results]))
      result)))

(defn render-pulse-email
  "Take a pulse object and list of results, returns an array of attachment objects for an email"
  [timezone pulse dashboard parts non-user-email]
  (render-message-body pulse
                       :pulse
                       (pulse-context pulse dashboard non-user-email)
                       timezone
                       dashboard
                       (assoc-attachment-booleans pulse parts)))

(defn pulse->alert-condition-kwd
  "Given an `alert` return a keyword representing what kind of goal needs to be met."
  [{:keys [alert_above_goal alert_condition] :as _alert}]
  (if (= "goal" alert_condition)
    (if (true? alert_above_goal)
      :meets
      :below)
    :rows))

(defn- first-card
  "Alerts only have a single card, so the alerts API accepts a `:card` key, while pulses have `:cards`. Depending on
  whether the data comes from the alert API or pulse tasks, the card could be under `:card` or `:cards`"
  [alert]
  (or (:card alert)
      (first (:cards alert))))

(defn common-alert-context
  "Template context that is applicable to all alert templates, including alert management templates
  (e.g. the subscribed/unsubscribed emails)"
  ([alert]
   (common-alert-context alert nil))
  ([alert alert-condition-map]
   (let [{card-id :id, card-name :name} (first-card alert)]
     (merge (common-context)
            {:emailType                 "alert"
             :questionName              card-name
             :questionURL               (urls/card-url card-id)
             :sectionStyle              (pulse/section-style)}
            (when alert-condition-map
              {:alertCondition (get alert-condition-map (pulse->alert-condition-kwd alert))})))))

(defn- schedule-hour-text
  [{hour :schedule_hour}]
  (.format (LocalTime/of hour 0)
           (DateTimeFormatter/ofPattern "h a")))

(defn- schedule-day-text
  [{day :schedule_day}]
  (get {"sun" "Sunday"
        "mon" "Monday"
        "tue" "Tuesday"
        "wed" "Wednesday"
        "thu" "Thursday"
        "fri" "Friday"
        "sat" "Saturday"}
       day))

(defn- schedule-timezone
  []
  (or (driver/report-timezone) "UTC"))

(defn- alert-schedule-text
  "Returns a string that describes the run schedule of an alert (i.e. how often results are checked),
  for inclusion in the email template. Not translated, since emails in general are not currently translated."
  [channel]
  (case (keyword (:schedule_type channel))
    :hourly
    "Run hourly"

    :daily
    (format "Run daily at %s %s"
            (schedule-hour-text channel)
            (schedule-timezone))

    :weekly
    (format "Run weekly on %s at %s %s"
            (schedule-day-text channel)
            (schedule-hour-text channel)
            (schedule-timezone))))

(defn- alert-context
  "Context that is applicable only to the actual alert template (not alert management templates)"
  [alert channel non-user-email]
  (let [{card-id :id card-name :name} (first-card alert)]
    {:title                     card-name
     :titleUrl                  (urls/card-url card-id)
     :alertSchedule             (alert-schedule-text channel)
     :notificationText          (if (nil? non-user-email)
                                  "Manage your subscriptions"
                                  "Unsubscribe")
     :notificationManagementUrl (if (nil? non-user-email)
                                  (urls/notification-management-url)
                                  (str (urls/unsubscribe-url)
                                       "?hash=" (generate-pulse-unsubscribe-hash (:id alert) non-user-email)
                                       "&email=" non-user-email
                                       "&pulse-id=" (:id alert)))
     :creator                   (-> alert :creator :common_name)}))

(defn- alert-results-condition-text [goal-value]
  {:meets (format "This question has reached its goal of %s." goal-value)
   :below (format "This question has gone below its goal of %s." goal-value)})

(defn render-alert-email
  "Take a pulse object and list of results, returns an array of attachment objects for an email"
  [timezone {:keys [alert_first_only] :as alert} channel results goal-value non-user-email]
  (let [message-ctx  (merge
                      (common-alert-context alert (alert-results-condition-text goal-value))
                      (alert-context alert channel non-user-email))]
    (render-message-body alert
                         :alert
                         (assoc message-ctx :firstRunOnly? alert_first_only)
                         timezone
                         nil
                         (assoc-attachment-booleans alert results))))

(def alert-condition-text
  "A map of alert conditions to their corresponding text."
  {:meets "when this question meets its goal"
   :below "when this question goes below its goal"
   :rows  "whenever this question has any results"})

(defn- send-email!
  "Sends an email on a background thread, returning a future."
  [user subject template-path template-context]
  (future
    (try
      (email/send-email-retrying!
       {:recipients   [(:email user)]
        :message-type :html
        :subject      subject
        :message      (stencil/render-file template-path template-context)})
      (catch Exception e
        (log/errorf e "Failed to send message to '%s' with subject '%s'" (:email user) subject)))))

(defn- template-path [template-name]
  (str "metabase/email/" template-name ".mustache"))

;; Paths to the templates for all of the alerts emails
(def ^:private you-unsubscribed-template   (template-path "alert_unsubscribed"))
(def ^:private admin-unsubscribed-template (template-path "alert_admin_unsubscribed_you"))
(def ^:private added-template              (template-path "alert_you_were_added"))
(def ^:private stopped-template            (template-path "alert_stopped_working"))
(def ^:private archived-template           (template-path "alert_archived"))

(defn send-you-unsubscribed-alert-email!
  "Send an email to `who-unsubscribed` letting them know they've unsubscribed themselves from `alert`"
  [alert who-unsubscribed]
  (send-email! who-unsubscribed "You unsubscribed from an alert" you-unsubscribed-template
               (common-alert-context alert)))

(defn send-admin-unsubscribed-alert-email!
  "Send an email to `user-added` letting them know `admin` has unsubscribed them from `alert`"
  [alert user-added {:keys [first_name last_name] :as _admin}]
  (let [admin-name (format "%s %s" first_name last_name)]
    (send-email! user-added "You’ve been unsubscribed from an alert" admin-unsubscribed-template
                 (assoc (common-alert-context alert) :adminName admin-name))))

(defn send-you-were-added-alert-email!
  "Send an email to `user-added` letting them know `admin-adder` has added them to `alert`"
  [alert user-added {:keys [first_name last_name] :as _admin-adder}]
  (let [subject (format "%s %s added you to an alert" first_name last_name)]
    (send-email! user-added subject added-template (common-alert-context alert alert-condition-text))))

(def ^:private not-working-subject "One of your alerts has stopped working")

(defn send-alert-stopped-because-archived-email!
  "Email to notify users when a card associated to their alert has been archived"
  [alert user {:keys [first_name last_name] :as _archiver}]
  (let [{card-id :id card-name :name} (first-card alert)]
    (send-email! user not-working-subject archived-template {:archiveURL   (urls/archive-url)
                                                             :questionName (format "%s (#%d)" card-name card-id)
                                                             :archiverName (format "%s %s" first_name last_name)})))
(defn send-alert-stopped-because-changed-email!
  "Email to notify users when a card associated to their alert changed in a way that invalidates their alert"
  [alert user {:keys [first_name last_name] :as _archiver}]
  (let [edited-text (format "the question was edited by %s %s" first_name last_name)]
    (send-email! user not-working-subject stopped-template (assoc (common-alert-context alert) :deletionCause edited-text))))

(defn send-broken-subscription-notification!
  "Email dashboard and subscription creators information about a broken subscription due to bad parameters"
  [{:keys [dashboard-id dashboard-name pulse-creator dashboard-creator affected-users bad-parameters]}]
  (let [{:keys [siteUrl] :as context} (common-context)]
    (email/send-message!
     :subject (trs "Subscription to {0} removed" dashboard-name)
     :recipients (distinct (map :email [pulse-creator dashboard-creator]))
     :message-type :html
     :message (stencil/render-file
               "metabase/email/broken_subscription_notification.mustache"
               (merge context
                      {:dashboardName            dashboard-name
                       :badParameters            (map
                                                  (fn [{:keys [value] :as param}]
                                                    (cond-> param
                                                      (coll? value)
                                                      (update :value #(lib.util/join-strings-with-conjunction
                                                                       (i18n/tru "or")
                                                                       %))))
                                                  bad-parameters)
                       :affectedUsers            (map
                                                  (fn [{:keys [notification-type] :as m}]
                                                    (cond-> m
                                                      notification-type
                                                      (update :notification-type name)))
                                                  (into
                                                   [{:notification-type :email
                                                     :recipient         (:common_name dashboard-creator)
                                                     :role              "Dashboard Creator"}
                                                    {:notification-type :email
                                                     :recipient         (:common_name pulse-creator)
                                                     :role              "Subscription Creator"}]
                                                   (map #(assoc % :role "Subscriber") affected-users)))
                       :dashboardUrl             (format "%s/dashboard/%s" siteUrl dashboard-id)})))))
