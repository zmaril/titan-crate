(ns pallet.crate.titan
  "A pallet crate to install and configure titan"
  (:require
   [clojure.tools.logging :refer [debugf]]
   [clojure.pprint :refer [pprint]]
   [clojure.walk :refer [postwalk-replace]]
   [clj-yaml.core :as yaml]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :as actions
    :refer [directory exec-checked-script packages remote-directory
            remote-file]]
   [pallet.node :refer [primary-ip private-ip]]
   [pallet.api :refer [plan-fn] :as api]
   [pallet.crate :refer [assoc-settings defmethod-plan defplan get-settings
                         service-phases target-nodes target-node]]
   [pallet.crate-install :as crate-install]
   [pallet.crate.nohup]
   [pallet.crate.cassandra :as cassie]
   [pallet.crate.service
    :refer [supervisor-config supervisor-config-map] :as service]
   [pallet.utils :refer [apply-map deep-merge]]
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

(defn backend-name [{:keys [config]}]
  (condp = (:storage.backend config)
    "local"             "berkeleyje"
    "berkeleyje"        "berkeleyje"
    "cassandra"         "cassandra"
    "embeddedcassandra" "cassandra"
    "hbase"             "hbase"
    "all"))

(defn titan-server-dir [{:keys [home version] :as settings}]
 (str home "/" (format "titan-%s-%s" (backend-name settings) version)))

(defn config-dir []
 (fragment (file (config-root) "titan")))

(defn default-settings [options]
  {:version "0.3.1"   
   ;;TODO:put in all defaults here
   :titan-config 
   {:storage.backend        "embeddedcassandra"
    :storage.directory      "/opt/titan/titandb"
    :storage.cassandra-config-dir  "file:///etc/titan/cassandra.yaml"
    :storage.read-only      false
    :storage.batch-loading  false
    :storage.buffer-size    1024
    :storage.write-attempts 5
    :storage.read-attempts  3
    :storage.attempt-wait   250 
    :ids.block-size         10000
    :ids.flush              true
    :ids.renew-timeout      60000
    :ids.renew-percentage   0.3}
   :cassandra-config
   {:authenticator "org.apache.cassandra.auth.AllowAllAuthenticator"
    :authority "org.apache.cassandra.auth.AllowAllAuthority"
    :cluster_name "titan-cluster"
    :column_index_size_in_kb 64
    :commitlog_directory "/opt/titan/cassandra/commitlog"
    :commitlog_sync "periodic"
    :commitlog_sync_period_in_ms 10000
    :commitlog_total_space_in_mb 4096
    :compaction_preheat_key_cache true
    :concurrent_reads 32
    :concurrent_writes 32
    :data_file_directories ["/opt/titan/cassandra/data"]
    :dynamic_snitch_badness_threshold 0.1
    :dynamic_snitch_reset_interval_in_ms 600000
    :dynamic_snitch_update_interval_in_ms 100
    :encryption_options {:internode_encryption "none"
                         :keystore "conf/.keystore"
                         :keystore_password "cassandra"
                         :truststore "conf/.truststore"
                         :truststore_password "cassandra"}
    :endpoint_snitch "org.apache.cassandra.locator.SimpleSnitch"
    :flush_largest_memtables_at 0.75
    :hinted_handoff_enabled true
    :in_memory_compaction_limit_in_mb 64
    :incremental_backups false
    :index_interval 128
    :initial_token nil
    :key_cache_save_period 14440
    :key_cache_size_in_mb nil
    :max_hint_window_in_ms 3600000
    :memtable_flush_queue_size 4
    :memtable_total_space_in_mb 2048
    :multithreaded_compaction false
    :partitioner "org.apache.cassandra.dht.RandomPartitioner"
    :reduce_cache_capacity_to 0.6
    :reduce_cache_sizes_at 0.85
    :request_scheduler "org.apache.cassandra.scheduler.NoScheduler"    
    :rpc_keepalive true
    :rpc_port 9160
    :rpc_server_type "sync"
    :row_cache_provider "SerializingCacheProvider"
    :row_cache_save_period 0
    :saved_caches_directory "/opt/titan/cassandra/saved_caches"
    :seed_provider [{:class_name "org.apache.cassandra.locator.SimpleSeedProvider"
                     :parameters [{:seeds "127.0.0.1"}]}]
    :snapshot_before_compaction false
    :storage_port 7000
    :ssl_storage_port 7001
    :thrift_framed_transport_size_in_mb 15
    :thrift_max_message_length_in_mb 16}
   :user "titan"
   :owner "titan"
   :group "titan"
   :max-seeds 1
   :home "/opt/titan"
   :dist-url "http://s3.thinkaurelius.com/downloads/titan/titan-%s-%s.zip"
   :config-dir (config-dir)
   :log-dir (fragment (file (log-root) "titan"))
   :supervisor :upstart
   :jvm-opts ""
   :service-name (service-name options)
   :variables {:listen-host  "0.0.0.0"
               :log-file     "/opt/titan/log/titan.log"
               :need-expire  true
               :expire-every 10
               :need-tcp     true
               :tcp-port     8182
               :need-udp     true}})

(defn url
  [{:keys [dist-url version] :as settings}]
  {:pre [dist-url version]}
  (format dist-url (backend-name settings) 
          version))

(defn run-command
  "Return a script command to run titan."
  [{:keys [home user config-dir] :as settings}]  
  (fragment ((file "bin" "titan.sh") 
             (file "config" "titan-server-rexster.xml") 
             (file ~config-dir "titan.properties"))))

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

(defmethod supervisor-config-map [:titan :upstart]
  [_ {:keys [run-command service-name user
             home backend version jvm-opts] 
      :as settings} options]
  {:service-name service-name
   :exec run-command
   :chdir (titan-server-dir settings)
   :env (str "JAVA_OPTIONS=" "\"" jvm-opts "\"")
   :setuid user})

(defplan settings
  "Settings for titan"
  [{:keys [user owner group dist dist-urls version instance-id]
    :as settings}
   & {:keys [instance-id] :as options}]
  (let [settings (deep-merge (default-settings options) settings)
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
   :no-versioning true
   :owner owner :group group
   file-source))

(defn map-to-conf [m]
  (->> m
       (map (fn [[k v]] (str (name k) "=" v)))
       (clojure.string/join "\n")))

(defn computed-settings
  "Update settings with cluster specific values."
  [{:keys [max-seeds cassandra-config]
    :as settings}]
  (let [ip (or (private-ip (target-node)) (primary-ip (target-node)))
        algo (or (:rpc-address-algo settings) (cassie/rpc-address-algo))
        defaults {:rpc_address (cassie/node-rpc-address algo (target-node))
                  :listen_address ip
                  :endpoint_snitch (cassie/endpoint-snitch)
                  :initial_token (cassie/initial-token cassie/tokens nil)}
        merge-function (fn [m] 
                         (assoc-in (merge defaults m)
                                   [:seed_provider 0 :parameters 0 :seeds]
                                   (cassie/seeds-list max-seeds false)))]
    (debugf "computed-settings defaults %s" defaults)
    (update-in settings [:cassandra-config] merge-function)))

(defplan configure
  "Write all config files"
  [{:keys [instance-id] :as options}]
  (let [{:keys [titan-config cassandra-config] :as settings}
        (computed-settings (get-settings :titan options))]
    (debugf "configure %s %s" settings options)

    (debugf "configure titan.properties")
    (config-file settings "titan.properties"
                 {:content (with-out-str (print (map-to-conf titan-config)))})

    (debugf "configure cassandra.yaml")
    (config-file settings "cassandra.yaml" 
                 {:content (yaml/generate-string 
                            cassandra-config
                            :dumper-options {:flow-style :block})})))

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
   :default-phases [:settings :install :configure]
   :phases
   (merge {:settings (plan-fn 
                      (pallet.crate.titan/settings 
                       (merge settings options)))
           :install (plan-fn
                     (user options)
                     (install options))
           :configure (plan-fn
                       (configure options))
           :run (plan-fn
                 (apply-map service :action :start options))}
          (service-phases :titan options service))))
