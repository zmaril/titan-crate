;;; Pallet project configuration file

(require
 '[pallet.crate.titan-test
   :refer [;;live-nohup-test-spec
           live-runit-test-spec
;;           live-upstart-test-spec
           ]]
 '[pallet.crates.test-nodes :refer [node-specs]])

(defproject titan-crate
  :provider node-specs                  ; supported pallet nodes
  :groups [;; (group-spec "titan-live-test"
           ;;             :extends [with-automated-admin-user
           ;;                       live-nohup-test-spec]
           ;;             :roles #{:live-test :default :nohup})
           (group-spec "titan-runit-test"
                       :extends [with-automated-admin-user
                                 live-runit-test-spec]
                       :roles #{:live-test :default :runit})
           ;; (group-spec "titan-upstart-test"
           ;;             :extends [with-automated-admin-user
           ;;                       live-upstart-test-spec]
           ;;             :roles #{:live-test :default :upstart})
           ])
