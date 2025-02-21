(ns metabase.api.channel-test
  (:require
   [clojure.test :refer :all]
   [metabase.channel.core :as channel]
   [metabase.channel.http-test :as channel.http-test]
   [metabase.notification.test-util :as notification.tu]
   [metabase.public-settings.premium-features :as premium-features]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

#_{:clj-kondo/ignore [:metabase/validate-deftest]}
(use-fixtures :once (fn [& _args] (channel/find-and-load-metabase-channels!)))

(set! *warn-on-reflection* true)

(def default-test-channel notification.tu/default-can-connect-channel)

(deftest CRU-channel-test
  (mt/with-model-cleanup [:model/Channel]
    (let [channel (testing "can create a channel"
                    (mt/user-http-request :crowberto :post 200 "channel"
                                          default-test-channel))]
      (testing "can get the channel"
        (is (=? default-test-channel
                (mt/user-http-request :crowberto :get 200 (str "channel/" (:id channel))))))

      (testing "can update channel name"
        (mt/user-http-request :crowberto :put 200 (str "channel/" (:id channel))
                              {:name "New Name"})
        (is (= "New Name" (t2/select-one-fn :name :model/Channel (:id channel)))))

      (testing "can't update channel details if fail to connect"
        (mt/user-http-request :crowberto :put 400 (str "channel/" (:id channel))
                              {:details {:return-type  "return-value"
                                         :return-value false}})
        (is (= {:return-type "return-value"
                :return-value true}
               (t2/select-one-fn :details :model/Channel (:id channel)))))

      (testing "can update channel details if connection is successful"
        (mt/user-http-request :crowberto :put 200 (str "channel/" (:id channel))
                              {:details {:return-type  "return-value"
                                         :return-value true
                                         :new-data     true}})
        (is (= {:return-type "return-value"
                :return-value true
                :new-data     true}
               (t2/select-one-fn :details :model/Channel (:id channel)))))

      (testing "can update channel description"
        (mt/user-http-request :crowberto :put 200 (str "channel/" (:id channel))
                              {:description "New description"})
        (is (= "New description" (t2/select-one-fn :description :model/Channel (:id channel)))))

      (testing "can disable a channel"
        (mt/user-http-request :crowberto :put 200 (str "channel/" (:id channel))
                              {:active false})
        (is (= false (t2/select-one-fn :active :model/Channel (:id channel))))))))

(deftest create-channel-with-existing-name-error-test
  (mt/with-temp [:model/Channel _chn default-test-channel]
    (is (= {:errors {:name "Channel with that name already exists"}}
           (mt/user-http-request :crowberto :post 409 "channel" default-test-channel)))))

(def ns-keyword->str #(str (.-sym %)))

(deftest list-channels-test
  (mt/with-temp [:model/Channel chn-1 default-test-channel
                 :model/Channel chn-2 (assoc default-test-channel
                                             :active false
                                             :name "Channel 2")]
    (testing "return active channels only"
      (is (= [(update chn-1 :type ns-keyword->str)]
             (mt/user-http-request :crowberto :get 200 "channel"))))

    (testing "return all if include_inactive is true"
      (is (= (map #(update % :type ns-keyword->str) [chn-1 (assoc chn-2 :name "Channel 2")])
             (mt/user-http-request :crowberto :get 200 "channel" {:include_inactive true}))))))

(deftest create-channel-error-handling-test
  (testing "returns text error message if the channel return falsy value"
    (is (= "Unable to connect channel"
           (mt/user-http-request :crowberto :post 400 "channel"
                                 (assoc default-test-channel :details {:return-type  "return-value"
                                                                       :return-value false})))))
  (testing "returns field-specific error message if the channel returns one"
    (is (= {:errors {:email "Invalid email"}}
           (mt/user-http-request :crowberto :post 400 "channel"
                                 (assoc default-test-channel :details {:return-type  "return-value"
                                                                       :return-value {:errors {:email "Invalid email"}}})))))

  (testing "returns field-specific error message if the channel throws one"
    (is (= {:errors {:email "Invalid email"}}
           (mt/user-http-request :crowberto :post 400 "channel"
                                 (assoc default-test-channel :details {:return-type  "throw"
                                                                       :return-value {:errors {:email "Invalid email"}}})))))

  (testing "error if channel details include undefined key"
    (channel.http-test/with-server [url [channel.http-test/get-200]]
      (is (= {:errors {:xyz ["disallowed key"]}}
             (mt/user-http-request :crowberto :post 400 "channel"
                                   (assoc default-test-channel
                                          :type        "channel/http"
                                          :details     {:url         (str url (:path channel.http-test/get-200))
                                                        :method      "get"
                                                        :auth-method "none"
                                                        :xyz         "alo"})))))))

(deftest ensure-channel-is-namespaced-test
  (testing "POST /api/channel return 400 if channel type is not namespaced"
    (is (=? {:errors {:type "Must be a namespaced channel. E.g: channel/http"}}
            (mt/user-http-request :crowberto :post 400 "channel"
                                  (assoc default-test-channel :type "metabase-test"))))

    (is (=? {:errors {:type "Must be a namespaced channel. E.g: channel/http"}}
            (mt/user-http-request :crowberto :post 400 "channel"
                                  (assoc default-test-channel :type "metabase/metabase-test")))))
  (testing "PUT /api/channel return 400 if channel type is not namespaced"
    (mt/with-temp [:model/Channel chn-1 default-test-channel]
      (is (=? {:errors {:type "nullable Must be a namespaced channel. E.g: channel/http"}}
              (mt/user-http-request :crowberto :put 400 (str "channel/" (:id chn-1))
                                    (assoc chn-1 :type "metabase-test"))))

      (is (=? {:errors {:type "nullable Must be a namespaced channel. E.g: channel/http"}}
              (mt/user-http-request :crowberto :put 400 (str "channel/" (:id chn-1))
                                    (assoc chn-1 :type "metabase/metabase-test")))))))

(deftest test-channel-connection-test
  (testing "return 200 if channel connects successfully"
    (is (= {:ok true}
           (mt/user-http-request :crowberto :post 200 "channel/test"
                                 (assoc default-test-channel :details {:return-type  "return-value"
                                                                       :return-value true})))))

  (testing "returns text error message if the channel return falsy value"
    (is (= "Unable to connect channel"
           (mt/user-http-request :crowberto :post 400 "channel/test"
                                 (assoc default-test-channel :details {:return-type  "return-value"
                                                                       :return-value false})))))
  (testing "returns field-specific error message if the channel returns one"
    (is (= {:errors {:email "Invalid email"}}
           (mt/user-http-request :crowberto :post 400 "channel/test"
                                 (assoc default-test-channel :details {:return-type  "return-value"
                                                                       :return-value {:errors {:email "Invalid email"}}})))))

  (testing "returns field-specific error message if the channel throws one"
    (is (= {:errors {:email "Invalid email"}}
           (mt/user-http-request :crowberto :post 400 "channel/test"
                                 (assoc default-test-channel :details {:return-type  "throw"
                                                                       :return-value {:errors {:email "Invalid email"}}}))))))

(deftest channel-audit-log-test
  (testing "audit log for channel apis"
    (mt/with-premium-features #{:audit-app}
      (mt/with-model-cleanup [:model/Channel]
        (with-redefs [premium-features/enable-cache-granular-controls? (constantly true)]
          (let [id (:id (mt/user-http-request :crowberto :post 200 "channel" default-test-channel))]
            (testing "POST /api/channel"
              (is (= {:details  {:description "Test channel description"
                                 :id          id
                                 :name        "Test channel"
                                 :type        notification.tu/test-channel-type
                                 :active      true}
                      :model    "Channel"
                      :model_id id
                      :topic    :channel-create
                      :user_id  (mt/user->id :crowberto)}
                     (mt/latest-audit-log-entry :channel-create))))

            (testing "PUT /api/channel/:id"
              (mt/user-http-request :crowberto :put 200 (str "channel/" id) (assoc default-test-channel :name "Updated Name"))
              (is (= {:details  {:new {:name "Updated Name"} :previous {:name "Test channel"}}
                      :model    "Channel"
                      :model_id id
                      :topic    :channel-update
                      :user_id  (mt/user->id :crowberto)}
                     (mt/latest-audit-log-entry :channel-update))))))))))
