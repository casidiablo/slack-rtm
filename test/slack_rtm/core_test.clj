(ns slack-rtm.core-test
  (:require [clojure.test :refer :all]
            [slack-rtm.core :refer :all]
            [clojure.core.async :refer [chan <!! >!! timeout go-loop <! >! go close!]]
            [clojure.data.json :as json]))

(defn get-api-token []
  (or (System/getenv "TOKEN")
      (->> "/.slack.clj"
           (str (System/getenv "HOME"))
           slurp
           load-string
           :slack-rtm)))

(defn get-connection []
  {:api-url "https://slack.com/api",
   :token (get-api-token)})

(deftest connection-test
  (testing "Connection"
    (let [hello-chan (chan)
          connection (connect (get-connection) :hello hello-chan)]
      (is (= "hello" (:type (<!! hello-chan))))
      (send-event (:dispatcher connection) :close)
      ;; since the connection has been closed, sending data
      ;; to the dispatcher channel is a no-op and returns false
      (<!! (timeout 1000))
      (is (= false (>!! (:dispatcher connection) :something))))))

(deftest parse-messages-chan-test
  (testing "Bad slack messages won't stop processing"
    (let [messages (atom [])
          pub-ch (chan)
          sub-ch (#'slack-rtm.core/parse-messages-chan pub-ch)]
      (go-loop []
        (when-let [val (<! sub-ch)]
          (swap! messages conj val)
          (recur)))
      (go
        (>! pub-ch {:message (json/write-str {:type "message" :a 1})})
        (>! pub-ch {:message "not json"})
        (>! pub-ch {:message (json/write-str {:type "message" :a 1})}))
      (<!! (timeout 100))
      (is (= 3 (count @messages)))
      (is (= "message" (:type (first @messages))))
      (is (= "message" (:type (last @messages))))
      (is (= "exception" (:type (second @messages))))
      (is (instance? Exception (:error (second @messages))))
      (close! pub-ch))))