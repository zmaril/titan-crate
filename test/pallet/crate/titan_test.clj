(ns pallet.crate.titan-test
  (:require
   [clojure.test :refer [deftest is]]
   [pallet.crate.titan :as titan]
   [pallet.actions :refer [package-manager]]
   [pallet.algo.fsmop :refer [complete?]]
   [pallet.api :refer [plan-fn server-spec]]
   [pallet.build-actions :as build-actions]
   [pallet.crate.java :as java]
   [pallet.crate.network-service :refer [wait-for-port-listen]]
   [pallet.crate.nohup :as nohup]
   [pallet.crate.runit :as runit]
   [pallet.crate.upstart :as upstart]))

(deftest invoke-test
  ;; (is (build-actions/build-actions {}
  ;;                                  (titan/settings {:supervisor :nohup})
  ;;                                  (titan/install {})
  ;;                                  (titan/configure {})
  ;;                                  (titan/service)))
  (is (build-actions/build-actions {}
                                   (titan/settings {:supervisor :runit})
                                   (titan/install {})
                                   (titan/configure {})
                                   (titan/service)))
  ;; (is (build-actions/build-actions {}
  ;;                                  (titan/settings {:supervisor :upstart})
  ;;                                  (titan/install {})
  ;;                                  (titan/configure {})
  ;;                                  (titan/service)))
)

;; (def live-nohup-test-spec
;;    (server-spec
;;     :extends [(java/server-spec {})
;;               (titan/server-spec {:supervisor :nohup})]
;;     :phases {:install (plan-fn (package-manager :update))
;;              :test (plan-fn (wait-for-port-listen 5555))}))

(def live-runit-test-spec
  (server-spec
   :extends [(java/server-spec {})
             (runit/server-spec {})
             (titan/server-spec {:supervisor :runit})]
   :phases {:install (plan-fn (package-manager :update))
            :test (plan-fn
                   (titan/service :action :start)
                   (wait-for-port-listen 8182))}))

;; (def live-upstart-test-spec
;;   (server-spec
;;    :extends [(java/server-spec {})
;;              (upstart/server-spec {})
;;              (titan/server-spec {:supervisor :upstart})]
;;    :phases {:install (plan-fn (package-manager :update))
;;             :test (plan-fn
;;                     (titan/service :action :start)
;;                     (wait-for-port-listen 5555))}))
