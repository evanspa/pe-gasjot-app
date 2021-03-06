(ns pe-fp-app.endpoint-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clojure.java.jdbc :as j]
            [pe-fp-core.core :as fpcore]
            [pe-user-core.core :as usercore]
            [pe-jdbc-utils.core :as jcore]
            [pe-fp-app.endpoint :as endpoint]
            [pe-fp-app.core :as core]
            [pe-fp-app.lifecycle :as lifecycle]
            [pe-fp-app.config :as config]
            [pe-rest-testutils.core :as rtucore]
            [ring.mock.request :as mock]
            [pe-core-utils.core :as ucore]
            [pe-user-rest.meta :as usermeta]
            [pe-rest-utils.core :as rucore]
            [pe-rest-utils.changelog.meta :as clmeta]
            [pe-fp-rest.meta :as fpmeta]
            [pe-rest-utils.meta :as rumeta]
            [pe-fp-core.ddl :as fpddl]
            [clojurewerkz.mailer.core :refer [delivery-mode!]]))

(alter-var-root (var usercore/*smtp-server-host*) (fn [_] nil))

; We do this because otherwise the 'drop-database' call will fail because you
; cannot drop a database in postgres in there are existing open connections
; connected to it.  So by do this, we effectively convert the pooled db-spec to
; a regular, non-pooled one.
(alter-var-root (var config/pooled-db-spec) (fn [_] config/db-spec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(use-fixtures :each (fn [f]
                      (try
                        (jcore/drop-database config/db-spec-without-db config/fp-db-name)
                        (catch Exception e))
                      (jcore/create-database config/db-spec-without-db config/fp-db-name)
                      (try
                        (j/db-do-commands config/db-spec
                                          true
                                          fpddl/v6-create-postgis-extension)
                        (catch Exception e))
                      (lifecycle/init-database)
                      (f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest integration-test-1
  (testing "Multiple Logins of user and subsequent authenticated request."
    (is (nil? (usercore/load-user-by-email config/db-spec "smithka@testing.com")))
    (is (nil? (usercore/load-user-by-username config/db-spec "smithk")))
    (let [user {:user/name "Karen Smith"
                :user/email "smithka@testing.com"
                :user/password "insecure"}
          new-user-id (usercore/next-user-account-id config/db-spec)]
      (usercore/save-new-user config/db-spec
                              new-user-id
                              user)
      (is (not (nil? (usercore/load-user-by-email config/db-spec "smithka@testing.com")))))
    ;; 1st Login
    (let [user {"user/username-or-email" "smithka@testing.com"
                "user/password" "insecure"}
          req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                          (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                          usermeta/v001
                                          "UTF-8;q=1,ISO-8859-1;q=0"
                                          "json"
                                          "en-US"
                                          :post
                                          core/login-uri-template)
                  (mock/body (json/write-str user))
                  (mock/content-type (rucore/content-type rumeta/mt-type
                                                          (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                                          usermeta/v001
                                                          "json"
                                                          "UTF-8")))
          resp (endpoint/fp-app req)]
      (testing "status code" (is (= 200 (:status resp)))))

    ;; Login again (creating a 2nd auth token in the database)
    (let [user {"user/username-or-email" "smithka@testing.com"
                "user/password" "insecure"}
          req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                          (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                          usermeta/v001
                                          "UTF-8;q=1,ISO-8859-1;q=0"
                                          "json"
                                          "en-US"
                                          :post
                                          core/login-uri-template)
                  (mock/body (json/write-str user))
                  (mock/content-type (rucore/content-type rumeta/mt-type
                                                          (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                                          usermeta/v001
                                                          "json"
                                                          "UTF-8")))
          resp (endpoint/fp-app req)]
      (testing "status code" (is (= 200 (:status resp))))
      (let [hdrs (:headers resp)
            resp-body-stream (:body resp)
            user-location-str (get hdrs "location")
            resp-user-entid-str (rtucore/last-url-part user-location-str)
            pct (rucore/parse-media-type (get hdrs "Content-Type"))
            charset (get rumeta/char-sets (:charset pct))
            resp-user (rucore/read-res pct resp-body-stream charset)
            auth-token (get hdrs config/fphdr-auth-token)
            [loaded-user-entid loaded-user-ent] (usercore/load-user-by-authtoken config/db-spec
                                                                                 (Long. resp-user-entid-str)
                                                                                 auth-token)]
        (letfn [(get-changelog [modified-since expected-status-code expected-num-entities]
                  (let [changelog-uri (str config/fp-base-url
                                           config/fp-entity-uri-prefix
                                           usermeta/pathcomp-users
                                           "/"
                                           resp-user-entid-str
                                           "/"
                                           clmeta/pathcomp-changelog)
                        req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                                        (clmeta/mt-subtype-changelog config/fp-mt-subtype-prefix)
                                                        fpmeta/v001
                                                        "UTF-8;q=1,ISO-8859-1;q=0"
                                                        "json"
                                                        "en-US"
                                                        :get
                                                        changelog-uri)
                                (rtucore/header config/fphdr-if-modified-since (Long/toString (c/to-long modified-since)))
                                (rtucore/header "Authorization" (rtucore/authorization-req-hdr-val config/fp-auth-scheme
                                                                                                   config/fp-auth-scheme-param-name
                                                                                                   auth-token)))
                        resp (endpoint/fp-app req)]
                    (testing "status code" (is (= expected-status-code (:status resp))))
                    (testing "headers and body of fetched change log"
                      (when (= expected-status-code 200)
                        (let [hdrs (:headers resp)
                              resp-body-stream (:body resp)]
                          (is (= "Accept, Accept-Charset, Accept-Language" (get hdrs "Vary")))
                          (is (not (nil? resp-body-stream)))
                          (let [pct (rucore/parse-media-type (get hdrs "Content-Type"))
                                charset (get rumeta/char-sets (:charset pct))
                                changelog (rucore/read-res pct resp-body-stream charset)]
                            (is (not (nil? changelog)))
                            (is (= (count (get changelog "_embedded")) expected-num-entities))))))))]
          (get-changelog (t/now) 304 0)
          (is (not (nil? loaded-user-ent)))
          ;; Create 1st vehicle
          (is (empty? (fpcore/vehicles-for-user config/db-spec loaded-user-entid)))
          (let [vehicle {"fpvehicle/name" "300Z"
                         "fpvehicle/default-octane" 93}
                vehicles-uri (str config/fp-base-url
                                  config/fp-entity-uri-prefix
                                  usermeta/pathcomp-users
                                  "/"
                                  resp-user-entid-str
                                  "/"
                                  fpmeta/pathcomp-vehicles)
                t1-before-1st-vehicle-creation (t/now)
                req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                                (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                fpmeta/v001
                                                "UTF-8;q=1,ISO-8859-1;q=0"
                                                "json"
                                                "en-US"
                                                :post
                                                vehicles-uri)
                        (mock/body (json/write-str vehicle))
                        (mock/content-type (rucore/content-type rumeta/mt-type
                                                                (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                                fpmeta/v001
                                                                "json"
                                                                "UTF-8"))
                        (rtucore/header "Authorization" (rtucore/authorization-req-hdr-val config/fp-auth-scheme
                                                                                           config/fp-auth-scheme-param-name
                                                                                           auth-token)))
                resp (endpoint/fp-app req)
                t2-after-1st-vehicle-creation (t/now)]
            (testing "status code" (is (= 201 (:status resp))))
            (testing "headers and body of created 300Z vehicle"
              (let [hdrs (:headers resp)
                    resp-body-stream (:body resp)
                    veh-location-str (get hdrs "location")]
                (is (= "Accept, Accept-Charset, Accept-Language" (get hdrs "Vary")))
                (is (not (nil? resp-body-stream)))
                (is (not (nil? veh-location-str)))
                (let [resp-veh-entid-str (rtucore/last-url-part veh-location-str)
                      pct (rucore/parse-media-type (get hdrs "Content-Type"))
                      charset (get rumeta/char-sets (:charset pct))
                      resp-veh (rucore/read-res pct resp-body-stream charset)]
                  (is (not (nil? resp-veh-entid-str)))
                  (is (not (nil? resp-veh)))
                  (is (= "300Z" (get resp-veh "fpvehicle/name")))
                  (is (not (nil? (get resp-veh "fpvehicle/created-at"))))
                  (is (not (nil? (get resp-veh "fpvehicle/updated-at"))))
                  (is (= 93 (get resp-veh "fpvehicle/default-octane")))
                  (let [loaded-vehicles (fpcore/vehicles-for-user config/db-spec loaded-user-entid)]
                    (is (= 1 (count loaded-vehicles)))
                    (let [[[loaded-veh-300z-entid loaded-veh-300z]] loaded-vehicles]
                      (is (= (Long/parseLong resp-veh-entid-str) loaded-veh-300z-entid))
                      (is (= "300Z" (:fpvehicle/name loaded-veh-300z)))
                      (is (= 93 (:fpvehicle/default-octane loaded-veh-300z)))

                      ;; Create 2nd vehicle
                      (let [vehicle {"fpvehicle/name" "Mazda CX-9"
                                     "fpvehicle/default-octane" 87}
                            req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                                            (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                            fpmeta/v001
                                                            "UTF-8;q=1,ISO-8859-1;q=0"
                                                            "json"
                                                            "en-US"
                                                            :post
                                                            (str config/fp-base-url
                                                                 config/fp-entity-uri-prefix
                                                                 usermeta/pathcomp-users
                                                                 "/"
                                                                 resp-user-entid-str
                                                                 "/"
                                                                 fpmeta/pathcomp-vehicles))
                                    (mock/body (json/write-str vehicle))
                                    (mock/content-type (rucore/content-type rumeta/mt-type
                                                                            (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                                            fpmeta/v001
                                                                            "json"
                                                                            "UTF-8"))
                                    (rtucore/header "Authorization" (rtucore/authorization-req-hdr-val config/fp-auth-scheme
                                                                                                       config/fp-auth-scheme-param-name
                                                                                                       auth-token)))
                            resp (endpoint/fp-app req)
                            t3-after-2nd-vehicle-creation (t/now)]
                        (testing "status code" (is (= 201 (:status resp))))
                        (testing "headers and body of created mazda vehicle"
                          (let [hdrs (:headers resp)
                                resp-body-stream (:body resp)
                                veh-location-str (get hdrs "location")]
                            (is (= "Accept, Accept-Charset, Accept-Language" (get hdrs "Vary")))
                            (is (not (nil? resp-body-stream)))
                            (is (not (nil? veh-location-str)))
                            (let [resp-veh-entid-str (rtucore/last-url-part veh-location-str)
                                  pct (rucore/parse-media-type (get hdrs "Content-Type"))
                                  charset (get rumeta/char-sets (:charset pct))
                                  resp-veh (rucore/read-res pct resp-body-stream charset)]
                              (is (not (nil? resp-veh-entid-str)))
                              (is (not (nil? resp-veh)))
                              (is (= "Mazda CX-9" (get resp-veh "fpvehicle/name")))
                              (is (= 87 (get resp-veh "fpvehicle/default-octane")))
                              (let [loaded-vehicles (sort-by (fn [[_ veh]] (:fpvehicle/date-added veh))
                                                             #(compare %2 %1)
                                                             (vec (fpcore/vehicles-for-user config/db-spec loaded-user-entid)))]
                                (is (= 2 (count loaded-vehicles)))
                                (let [[[loaded-veh-mazda-entid loaded-veh-mazda] _] loaded-vehicles]
                                  (is (= (Long/parseLong resp-veh-entid-str) loaded-veh-mazda-entid))
                                  (is (= "Mazda CX-9" (:fpvehicle/name loaded-veh-mazda)))
                                  (is (= 87 (:fpvehicle/default-octane loaded-veh-mazda))))))))
                        (testing "change log"
                          (get-changelog t1-before-1st-vehicle-creation 200 2)
                          (get-changelog t2-after-1st-vehicle-creation 200 1)
                          (get-changelog t3-after-2nd-vehicle-creation 304 0))
                        (testing "change log after deletion"
                          (let [req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                                                (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                                fpmeta/v001
                                                                "UTF-8;q=1,ISO-8859-1;q=0"
                                                                "json"
                                                                "en-US"
                                                                :delete
                                                                veh-location-str)
                                        (rtucore/header "Authorization" (rtucore/authorization-req-hdr-val config/fp-auth-scheme
                                                                                                           config/fp-auth-scheme-param-name
                                                                                                           auth-token)))
                                resp (endpoint/fp-app req)
                                t4-after-2nd-vehicle-deletion (t/now)]
                            (testing "status code" (is (= 204 (:status resp))))
                            (get-changelog t3-after-2nd-vehicle-creation 200 1)
                            (get-changelog t4-after-2nd-vehicle-deletion 304 0))))))))))))
      ;; doing a light login
      (let [user {"user/username-or-email" "smithka@testing.com"
                  "user/password" "insecure"}
            req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                            (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                            usermeta/v001
                                            "UTF-8;q=1,ISO-8859-1;q=0"
                                            "json"
                                            "en-US"
                                            :post
                                            core/light-login-uri-template)
                    (mock/body (json/write-str user))
                    (mock/content-type (rucore/content-type rumeta/mt-type
                                                            (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                                            usermeta/v001
                                                            "json"
                                                            "UTF-8")))
            resp (endpoint/fp-app req)]
        (testing "status code" (is (= 204 (:status resp)))))
      )
    ))

(deftest integration-test-2
  (testing "Login of user and subsequent authenticated request."
    (is (nil? (usercore/load-user-by-email config/db-spec "smithka@testing.com")))
    (is (nil? (usercore/load-user-by-username config/db-spec "smithk")))
    (let [user {:user/name "Karen Smith"
                :user/email "smithka@testing.com"
                :user/password "insecure"}]
      (usercore/save-new-user config/db-spec
                              (usercore/next-user-account-id config/db-spec)
                              user)
      (is (not (nil? (usercore/load-user-by-email config/db-spec "smithka@testing.com")))))
    (let [user {"user/username-or-email" "smithka@testing.com"
                "user/password" "insecure"}
          req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                          (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                          usermeta/v001
                                          "UTF-8;q=1,ISO-8859-1;q=0"
                                          "json"
                                          "en-US"
                                          :post
                                          core/login-uri-template)
                  (mock/body (json/write-str user))
                  (mock/content-type (rucore/content-type rumeta/mt-type
                                                          (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                                          usermeta/v001
                                                          "json"
                                                          "UTF-8")))
          resp (endpoint/fp-app req)]
      (testing "status code" (is (= 200 (:status resp))))
      (let [hdrs (:headers resp)
            resp-body-stream (:body resp)
            user-location-str (get hdrs "location")
            resp-user-entid-str (rtucore/last-url-part user-location-str)
            pct (rucore/parse-media-type (get hdrs "Content-Type"))
            charset (get rumeta/char-sets (:charset pct))
            resp-user (rucore/read-res pct resp-body-stream charset)
            auth-token (get hdrs config/fphdr-auth-token)
            [loaded-user-entid loaded-user-ent] (usercore/load-user-by-authtoken config/db-spec
                                                                                 (Long. resp-user-entid-str)
                                                                                 auth-token)]
        (is (not (nil? loaded-user-ent)))
        ;; Create 1st vehicle
        (is (empty? (fpcore/vehicles-for-user config/db-spec loaded-user-entid)))
        (let [vehicle {"fpvehicle/name" "300Z"
                       "fpvehicle/default-octane" 93}
              vehicles-uri (str config/fp-base-url
                                config/fp-entity-uri-prefix
                                usermeta/pathcomp-users
                                "/"
                                resp-user-entid-str
                                "/"
                                fpmeta/pathcomp-vehicles)
              req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                              (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                              fpmeta/v001
                                              "UTF-8;q=1,ISO-8859-1;q=0"
                                              "json"
                                              "en-US"
                                              :post
                                              vehicles-uri)
                      (mock/body (json/write-str vehicle))
                      (mock/content-type (rucore/content-type rumeta/mt-type
                                                              (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                              fpmeta/v001
                                                              "json"
                                                              "UTF-8"))
                      (rtucore/header "Authorization" (rtucore/authorization-req-hdr-val config/fp-auth-scheme
                                                                                         config/fp-auth-scheme-param-name
                                                                                         auth-token)))
              resp (endpoint/fp-app req)]
          (testing "status code" (is (= 201 (:status resp))))
          (testing "headers and body of created 300Z vehicle"
            (let [hdrs (:headers resp)
                  resp-body-stream (:body resp)
                  veh-location-str (get hdrs "location")]
              (is (= "Accept, Accept-Charset, Accept-Language" (get hdrs "Vary")))
              (is (not (nil? resp-body-stream)))
              (is (not (nil? veh-location-str)))
              (let [resp-veh-entid-str (rtucore/last-url-part veh-location-str)
                    pct (rucore/parse-media-type (get hdrs "Content-Type"))
                    charset (get rumeta/char-sets (:charset pct))
                    resp-veh (rucore/read-res pct resp-body-stream charset)]
                (is (not (nil? resp-veh-entid-str)))
                (is (not (nil? resp-veh)))
                (is (not (nil? (get resp-veh "fpvehicle/created-at"))))
                (is (not (nil? (get resp-veh "fpvehicle/updated-at"))))
                (is (= "300Z" (get resp-veh "fpvehicle/name")))
                (is (= 93 (get resp-veh "fpvehicle/default-octane")))
                (let [loaded-vehicles (fpcore/vehicles-for-user config/db-spec loaded-user-entid)]
                  (is (= 1 (count loaded-vehicles)))
                  (let [[[loaded-veh-300z-entid loaded-veh-300z]] loaded-vehicles]
                    (is (= (Long/parseLong resp-veh-entid-str) loaded-veh-300z-entid))
                    (is (= "300Z" (:fpvehicle/name loaded-veh-300z)))
                    (is (= 93 (:fpvehicle/default-octane loaded-veh-300z)))
                    (is (not (nil? (:fpvehicle/created-at loaded-veh-300z))))
                    (is (not (nil? (:fpvehicle/updated-at loaded-veh-300z))))
                    ;; Create 2nd vehicle
                    (let [vehicle {"fpvehicle/name" "Mazda CX-9"
                                   "fpvehicle/default-octane" 87}
                          req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                                          (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                          fpmeta/v001
                                                          "UTF-8;q=1,ISO-8859-1;q=0"
                                                          "json"
                                                          "en-US"
                                                          :post
                                                          (str config/fp-base-url
                                                               config/fp-entity-uri-prefix
                                                               usermeta/pathcomp-users
                                                               "/"
                                                               resp-user-entid-str
                                                               "/"
                                                               fpmeta/pathcomp-vehicles))
                                  (mock/body (json/write-str vehicle))
                                  (mock/content-type (rucore/content-type rumeta/mt-type
                                                                          (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                                          fpmeta/v001
                                                                          "json"
                                                                          "UTF-8"))
                                  (rtucore/header "Authorization" (rtucore/authorization-req-hdr-val config/fp-auth-scheme
                                                                                                     config/fp-auth-scheme-param-name
                                                                                                     auth-token)))
                          resp (endpoint/fp-app req)]
                      (testing "status code" (is (= 201 (:status resp))))
                      (testing "headers and body of created mazda vehicle"
                        (let [hdrs (:headers resp)
                              resp-body-stream (:body resp)
                              veh-location-str (get hdrs "location")]
                          (is (= "Accept, Accept-Charset, Accept-Language" (get hdrs "Vary")))
                          (is (not (nil? resp-body-stream)))
                          (is (not (nil? veh-location-str)))
                          (let [resp-veh-entid-str (rtucore/last-url-part veh-location-str)
                                pct (rucore/parse-media-type (get hdrs "Content-Type"))
                                charset (get rumeta/char-sets (:charset pct))
                                resp-veh (rucore/read-res pct resp-body-stream charset)]
                            (is (not (nil? resp-veh-entid-str)))
                            (is (not (nil? resp-veh)))
                            (is (= "Mazda CX-9" (get resp-veh "fpvehicle/name")))
                            (is (= 87 (get resp-veh "fpvehicle/default-octane")))
                            (let [loaded-vehicles (fpcore/vehicles-for-user config/db-spec loaded-user-entid)]
                              (is (= 2 (count loaded-vehicles)))
                              (let [[[loaded-veh-mazda-entid loaded-veh-mazda] _] loaded-vehicles]
                                (is (= (Long/parseLong resp-veh-entid-str) loaded-veh-mazda-entid))
                                (is (= "Mazda CX-9" (:fpvehicle/name loaded-veh-mazda)))
                                (is (= 87 (:fpvehicle/default-octane loaded-veh-mazda)))))))))))))))))))

(deftest integration-test-3
  (testing "Successful creation of user  and vehicles."
    (is (nil? (usercore/load-user-by-email config/db-spec "smithka@testing.com")))
    (is (nil? (usercore/load-user-by-username config/db-spec "smithk")))
    (let [user {"user/name" "Karen Smith"
                "user/email" "smithka@testing.com"
                "user/username" "smithk"
                "user/password" "insecure"}
          req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                          (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                          usermeta/v001
                                          "UTF-8;q=1,ISO-8859-1;q=0"
                                          "json"
                                          "en-US"
                                          :post
                                          core/users-uri-template)
                  (rtucore/header config/fphdr-establish-session "true")
                  (mock/body (json/write-str user))
                  (mock/content-type (rucore/content-type rumeta/mt-type
                                                          (usermeta/mt-subtype-user config/fp-mt-subtype-prefix)
                                                          usermeta/v001
                                                          "json"
                                                          "UTF-8")))
          resp (endpoint/fp-app req)]
      (let [hdrs (:headers resp)
            resp-body-stream (:body resp)
            user-location-str (get hdrs "location")
            resp-user-entid-str (rtucore/last-url-part user-location-str)
            pct (rucore/parse-media-type (get hdrs "Content-Type"))
            charset (get rumeta/char-sets (:charset pct))
            resp-user (rucore/read-res pct resp-body-stream charset)
            auth-token (get hdrs config/fphdr-auth-token)
            [loaded-user-entid loaded-user-ent] (usercore/load-user-by-authtoken config/db-spec
                                                                                 (Long. resp-user-entid-str)
                                                                                 auth-token)]
        ;; Create 1st vehicle
        (is (empty? (fpcore/vehicles-for-user config/db-spec loaded-user-entid)))
        (let [vehicle {"fpvehicle/name" "300Z"
                       "fpvehicle/default-octane" 93}
              vehicles-uri (str config/fp-base-url
                                config/fp-entity-uri-prefix
                                usermeta/pathcomp-users
                                "/"
                                resp-user-entid-str
                                "/"
                                fpmeta/pathcomp-vehicles)
              req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                              (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                              fpmeta/v001
                                              "UTF-8;q=1,ISO-8859-1;q=0"
                                              "json"
                                              "en-US"
                                              :post
                                              vehicles-uri)
                      (mock/body (json/write-str vehicle))
                      (mock/content-type (rucore/content-type rumeta/mt-type
                                                              (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                              fpmeta/v001
                                                              "json"
                                                              "UTF-8"))
                      (rtucore/header "Authorization" (rtucore/authorization-req-hdr-val config/fp-auth-scheme
                                                                                         config/fp-auth-scheme-param-name
                                                                                         auth-token)))
              resp (endpoint/fp-app req)]
          (testing "status code" (is (= 201 (:status resp))))
          (testing "headers and body of created 300Z vehicle"
            (let [hdrs (:headers resp)
                  resp-body-stream (:body resp)
                  veh-location-str (get hdrs "location")]
              (is (= "Accept, Accept-Charset, Accept-Language" (get hdrs "Vary")))
              (is (not (nil? resp-body-stream)))
              (is (not (nil? veh-location-str)))
              (let [resp-veh-entid-str (rtucore/last-url-part veh-location-str)
                    pct (rucore/parse-media-type (get hdrs "Content-Type"))
                    charset (get rumeta/char-sets (:charset pct))
                    resp-veh (rucore/read-res pct resp-body-stream charset)]
                (is (not (nil? resp-veh-entid-str)))
                (is (not (nil? resp-veh)))
                (is (= "300Z" (get resp-veh "fpvehicle/name")))
                (is (= 93 (get resp-veh "fpvehicle/default-octane")))
                (let [loaded-vehicles (fpcore/vehicles-for-user config/db-spec loaded-user-entid)]
                  (is (= 1 (count loaded-vehicles)))
                  (let [[[loaded-veh-300z-entid loaded-veh-300z]] loaded-vehicles]
                    (is (= (Long/parseLong resp-veh-entid-str) loaded-veh-300z-entid))
                    (is (= "300Z" (:fpvehicle/name loaded-veh-300z)))
                    (is (= 93 (:fpvehicle/default-octane loaded-veh-300z)))

                    ;; Create 2nd vehicle
                    (let [vehicle {"fpvehicle/name" "Mazda CX-9"
                                   "fpvehicle/default-octane" 87}
                          req (-> (rtucore/req-w-std-hdrs rumeta/mt-type
                                                          (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                          fpmeta/v001
                                                          "UTF-8;q=1,ISO-8859-1;q=0"
                                                          "json"
                                                          "en-US"
                                                          :post
                                                          (str config/fp-base-url
                                                               config/fp-entity-uri-prefix
                                                               usermeta/pathcomp-users
                                                               "/"
                                                               resp-user-entid-str
                                                               "/"
                                                               fpmeta/pathcomp-vehicles))
                                  (mock/body (json/write-str vehicle))
                                  (mock/content-type (rucore/content-type rumeta/mt-type
                                                                          (fpmeta/mt-subtype-vehicle config/fp-mt-subtype-prefix)
                                                                          fpmeta/v001
                                                                          "json"
                                                                          "UTF-8"))
                                  (rtucore/header "Authorization" (rtucore/authorization-req-hdr-val config/fp-auth-scheme
                                                                                                     config/fp-auth-scheme-param-name
                                                                                                     auth-token)))
                          resp (endpoint/fp-app req)]
                      (testing "status code" (is (= 201 (:status resp))))
                      (testing "headers and body of created mazda vehicle"
                        (let [hdrs (:headers resp)
                              resp-body-stream (:body resp)
                              veh-location-str (get hdrs "location")]
                          (is (= "Accept, Accept-Charset, Accept-Language" (get hdrs "Vary")))
                          (is (not (nil? resp-body-stream)))
                          (is (not (nil? veh-location-str)))
                          (let [resp-veh-entid-str (rtucore/last-url-part veh-location-str)
                                pct (rucore/parse-media-type (get hdrs "Content-Type"))
                                charset (get rumeta/char-sets (:charset pct))
                                resp-veh (rucore/read-res pct resp-body-stream charset)]
                            (is (not (nil? resp-veh-entid-str)))
                            (is (not (nil? resp-veh)))
                            (is (= "Mazda CX-9" (get resp-veh "fpvehicle/name")))
                            (is (= 87 (get resp-veh "fpvehicle/default-octane")))
                            (let [loaded-vehicles (fpcore/vehicles-for-user config/db-spec loaded-user-entid)]
                              (is (= 2 (count loaded-vehicles)))
                              (let [[[loaded-veh-mazda-entid loaded-veh-mazda] _] loaded-vehicles]
                                (is (= (Long/parseLong resp-veh-entid-str) loaded-veh-mazda-entid))
                                (is (= "Mazda CX-9" (:fpvehicle/name loaded-veh-mazda)))
                                (is (= 87 (:fpvehicle/default-octane loaded-veh-mazda)))))))))))))))))))
