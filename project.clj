(defproject slack-rtm "0.1.7"
  :description "Slack Real Time Messaging API for Clojure"
  :url "http://github.com/casidiablo/slack-rtm"
  :license {:name "WTFPL"
            :url "http://www.wtfpl.net/"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.julienxx/clj-slack "0.5.5"]
                 [org.clojure/data.json "0.2.6"]
                 [stylefruits/gniazdo "1.0.0"]
                 [org.clojure/core.async "0.3.442"]]
  :deploy-repositories [["clojars"
                         {:url "https://clojars.org/repo/"
                          :username :env/clojars_username
                          :password :env/clojars_password}]])
