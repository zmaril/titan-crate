;;; Pallet project configuration file

(require
 '[pallet.crate.titan-test
   :refer [live-upstart-test-spec]]
 '[pallet.crates.test-nodes :refer [node-specs]])

(defproject titan-crate
  :provider node-specs                  
  :groups [(group-spec "titan-upstart-test"
                       :count 2
                       :extends [with-automated-admin-user
                                 live-upstart-test-spec]
                       :roles #{:live-test :default :upstart})])
