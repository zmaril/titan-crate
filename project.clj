(defproject zmaril/titan-crate "0.1.0-SNAPSHOT"
  :description "Crate for titan installation"
  :url "http://github.com/zmaril/titan-crate"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [clj-yaml "0.4.0"]
                 [com.palletops/pallet "0.8.0-RC.1"]]
  :resource {:resource-paths ["doc-src"]
             :target-path "target/classes/pallet_crate/titan_crate/"
             :includes [#"doc-src/USAGE.*"]}
  :prep-tasks ["resource" "crate-doc"])
