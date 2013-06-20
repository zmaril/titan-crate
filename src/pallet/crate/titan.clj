(ns pallet.crate.titan
  "A pallet crate to install and configure titan"
  (:require
   [clojure.tools.logging :refer [debugf]]
   [clojure.pprint :refer [pprint]]
   [clojure.walk :refer [postwalk-replace]]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :as actions
    :refer [directory exec-checked-script packages remote-directory
            remote-file]]
   [pallet.api :refer [plan-fn] :as api]
   [pallet.crate :refer [assoc-settings defmethod-plan defplan get-settings
                         service-phases]]
   [pallet.crate-install :as crate-install]
   [pallet.crate.nohup]
   [pallet.crate.service
    :refer [supervisor-config supervisor-config-map] :as service]
   [pallet.utils :refer [apply-map]]
   [pallet.script.lib :refer [config-root file log-root]]
   [pallet.stevedore :refer [fragment]]
   [pallet.version-dispatch :refer [defmethod-version-plan
                                    defmulti-version-plan]]))


(def ^{:doc "Flag for recognising changes to configuration"}
  titan-config-changed-flag "titan-config")

;;; # Settings
(defn service-name
  "Return a service name for titan."
  [{:keys [instance-id] :as options}]
  (str "titan" (when instance-id (str "-" instance-id))))

(defn default-settings [options]
  {:version "0.3.1"
   :user "titan"
   :owner "titan"
   :group "titan"
   :home "/opt/titan"
   :dist-url "http://s3.thinkaurelius.com/downloads/titan/titan-%s-%s.zip"
   :backend "all"
   :config-dir (fragment (file (config-root) "titan"))
   :log-dir (fragment (file (log-root) "titan"))
   :supervisor :nohup
   :nohup {:process-name "java"}
   :service-name (service-name options)
   :variables {:listen-host  "0.0.0.0"
               :log-file     "/var/log/titan.log"
               :need-expire  true
               :expire-every 10
               :need-tcp     true
               :tcp-port     8182
               :need-udp     true}
   :config '(let [index (default {:state "ok"
                                  :ttl   3600}
                          (update-index (index)))]
              (streams
               (with :service "events per sec"
                     (rate 30 index))
               index))})

(defn url
  [{:keys [dist-url backend version] :as settings}]
  {:pre [dist-url backend version]}
  (format dist-url backend version))

(defn run-command
  "Return a script command to run riemann."
  [{:keys [home user config-dir] :as settings}]  
  (fragment ((file "bin" "titan.sh") 
             (file "config" "titan-server-rexster.xml") 
             (file "config" "titan-server-cassandra.properties"))))

;;; At the moment we just have a single implementation of settings,
;;; but this is open-coded.
(defmulti-version-plan settings-map [version settings])

(defmethod-version-plan
    settings-map {:os :linux}
    [os os-version version settings]
  (cond
   (:install-strategy settings) settings
   :else  (assoc settings
            :install-strategy ::download
            :remote-file {:url (url settings)
                          :unpack :unzip})))


(defmethod supervisor-config-map [:titan :nohup]
  [_ {:keys [run-command service-name user] :as settings} options]
  {:service-name service-name
   :run-file {:content run-command}
   :user user})

(defmethod supervisor-config-map [:titan :runit]
  [_ {:keys [run-command service-name user] :as settings} options]
  {:service-name service-name
   :run-file {:content (str "#!/bin/sh\nexec chpst -u " user " " run-command)}})

(defmethod supervisor-config-map [:titan :upstart]
  [_ {:keys [run-command service-name user
             home backend version] 
      :as settings} options]
  {:service-name service-name
   :exec run-command
   :chdir (str home "/" (format "titan-%s-%s" backend version))
   :setuid user})

(defplan settings
  "Settings for titan"
  [{:keys [user owner group dist dist-urls version instance-id]
    :as settings}
   & {:keys [instance-id] :as options}]
  (let [settings (merge (default-settings options) settings)
        settings (settings-map (:version settings) settings)
        settings (update-in settings [:run-command]
                            #(or % (run-command settings)))]
    (assoc-settings :titan settings {:instance-id instance-id})
    (supervisor-config :titan settings (or options {}))))

;;; # User
(defplan user
  "Create the titan user"
  [{:keys [instance-id] :as options}]
  (let [{:keys [user owner group home]} (get-settings :titan options)]
    (actions/group group :system true)
    (when (not= owner user)
      (actions/user owner :group group :system true))
    (actions/user
     user :group group :system true :create-home true :shell :bash)))

;;; # Install
(defmethod-plan crate-install/install ::download
  [facility instance-id]
  (let [{:keys [user owner group home remote-file] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (packages :apt ["bzip2" "unzip"] :aptitude ["bzip2" "unzip"])
    (directory home :owner owner :group group)
    (apply-map remote-directory home :owner owner :group group remote-file)))

(defplan install
  "Install titan."
  [{:keys [instance-id]}]
  (let [{:keys [install-strategy owner group log-dir] :as settings}
        (get-settings :titan {:instance-id instance-id})]
    (crate-install/install :titan instance-id)
    (when log-dir
      (directory log-dir :owner owner :group group :mode "0755"))))

;;; # Configuration
(defplan config-file
  "Helper to write config files"
  [{:keys [owner group config-dir] :as settings} filename file-source]
  (directory config-dir :owner owner :group group)
  (apply-map
   remote-file (fragment (file ~config-dir ~filename))
   :flag-on-changed titan-config-changed-flag
   :owner owner :group group
   file-source))

(defplan configure
  "Write all config files"
  [{:keys [instance-id] :as options}]
  (let [settings (get-settings :titan options)
        {:keys [config variables base-config] :as settings} settings
        config (postwalk-replace variables config)
        variables (assoc variables :config config)
        config (postwalk-replace variables base-config)]
    (debugf "configure %s %s" settings options)
    (config-file settings "titan.conf"
                 {:content (with-out-str (pprint config))})))

;;; # Run
(defplan service
  "Run the titan service."
  [& {:keys [action if-flag if-stopped instance-id]
      :or {action :manage}
      :as options}]
  (let [{:keys [supervision-options] :as settings}
        (get-settings :titan {:instance-id instance-id})]
    (service/service settings (merge supervision-options
                                     (dissoc options :instance-id)))))

(defn server-spec
  "Returns a server-spec that installs and configures titan."
  [settings & {:keys [instance-id] :as options}]
  (api/server-spec
   :phases
   (merge {:settings (plan-fn (pallet.crate.titan/settings (merge settings options)))
           :install (plan-fn
                      (user options)
                      (install options))
           :configure (plan-fn
                        (configure options)
;;                        (apply-map service :action :enable options)
                        )
           :run (plan-fn
                  (apply-map service :action :start options))}
          (service-phases :titan options service))))
