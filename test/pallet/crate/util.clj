(ns pallet.crate.util
  "Functions useful for testing"
  (:require
   [pallet.actions :refer [exec-checked-script]]
   [pallet.crate :refer [defplan]]
   [pallet.script.lib :as lib]))

(defplan wait-for-http-response
  "Wait for an HTTP response with a given response regex.

   Options:
   - :host          host to check (defaults to \"localhost\")
   - :port          port to check (defaults to \"8000\")
   - :url           url to check  (defaults to \"\")
   - :param         params to send with request (defaults to \"\")
   - :data          data to send with request (defaults to \"\")
   - :method        HTTP method to send the request with (defaults to \"GET\")
   - :timeout       time to wait for a response (default to 2 secsconds)
   - :standoff      time between checking HTTP status (defaults to 2 seconds)
   - :max-retries   number of times to test HTTP status before erroring (defaults to 5 tries)
   - :service-name  name of service to use in messages (defaults to port)"

  [response-regex
   & {:keys [host port url param data method
             timeout max-retries standoff service-name ]
      :or {host "localhost" port "8000" url "/" method "GET" param ""
           data "" max-retries 5 standoff 2 timeout 2
           service-name "port"}}]
  (exec-checked-script
   (format
    "Wait for %s to return a response to curl request"
    service-name response-regex)

   (group (chain-or (let x 0) true))
   (while       
       ("!" (pipe   ("curl" 
                     -s
                     --request ~method
                     ~(if (empty? data)
                        ""
                        (str "--data " data))
                     ~(if (empty? param)
                        ""
                        (str "--form " param))
                     --connect-timeout ~timeout   
                     ~(format "'%s:%s/%s'" host port url))
                    ("grep" -E ~(format "'%s'" response-regex))) )
     (let x (+ x 1))
     (if (= ~max-retries @x)
       (do
         (println
          ~(format
            "Timed out waiting for %s to return response"
            service-name)
          >&2)
         (~lib/exit 1)))
     (println
      ~(format
        "Waiting for %s to return response" service-name))
     ("sleep" ~standoff))
   ("sleep" ~standoff)))










