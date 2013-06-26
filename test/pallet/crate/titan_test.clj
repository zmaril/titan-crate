(ns pallet.crate.titan-test
  (:require
   [clojure.test :refer [deftest is]]
   [pallet.crate.titan :as titan]
   [pallet.actions :refer [package-manager]]
   [pallet.api :refer [plan-fn server-spec]]
   [pallet.build-actions :as build-actions]
   [pallet.crate.java :as java]
   [pallet.crate.network-service :refer [wait-for-port-listen wait-for-port-response]]
   [pallet.crate.util :refer [wait-for-http-response]]
   [pallet.crate.upstart :as upstart]))

(deftest invoke-test  
  (is (build-actions/build-actions 
       {}
       (titan/settings {:supervisor :upstart})
       (titan/install {})
       (titan/configure {})
       (titan/service))))

(def live-upstart-test-spec
  (server-spec
   :extends [(java/server-spec {})
             (upstart/server-spec {})
             (titan/server-spec {:supervisor :upstart
                                 :jvm-opts "-Xms512m -Xmx1G"})]
   :phases {:install 
            (plan-fn 
             (package-manager :update))
            :test-start 
            (plan-fn
             (titan/service :action :start)
             (wait-for-port-listen 8182 
                                   :standoff 5 
                                   :max-retries 5))
            :test-create-vertex 
            (plan-fn
             (wait-for-http-response
              "results"
              :port   8182 
              :method "POST"
              :url    "graphs/graph/vertices"))
            :test-together 
            (plan-fn
             (wait-for-http-response
              "totalSize\\\":2"
              :port 8182 
              :url  "graphs/graph/vertices"))}))
