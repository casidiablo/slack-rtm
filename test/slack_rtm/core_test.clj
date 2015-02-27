(ns slack-rtm.core-test
  (:require [clojure.test :refer :all]
            [slack-rtm.core :refer :all]
            [clojure.core.async :refer [chan <!! >!! timeout]]))

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
