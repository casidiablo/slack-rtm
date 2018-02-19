(ns slack-rtm.core
  (:require [clj-slack.rtm :as rtm]
            [clj-slack.core :refer [slack-request]]
            [clojure.core.async :as async
             :refer [chan pub sub go >! <! go-loop close! unsub unsub-all]]
            [clojure.data.json :as json]
            [clojure.test]
            [gniazdo.core :as ws]))

 ;; private utility methods

(defn- loop-fn-until-nil
  "Loop infinitely reading from the provided channel (creates an
  unbuffered one if none provided), applying f to each item. Stops if
  the channel provides nil. Returns the channel."
  ([f] (loop-fn-until-nil f (chan)))
  ([f ch]
     (go-loop []
       (when-let [val (<! ch)]
         (do
           (try
             (f val)
             (catch Exception e
               (.printStackTrace e)
               (println "Failed to call fn on " val (.getMessage e))))
           (recur))))
     ch))

(defn- ws-connect
  "Connects to the provided WebSocket URL and forward all listener
  events to the provided channel."
  [url callback-ch]
  (ws/connect
   url
   :on-connect #(go (>! callback-ch {:type :on-connect
                                     :session %}))

   :on-receive #(go (>! callback-ch {:type :on-receive
                                     :message %}))

   :on-binary #(go (>! callback-ch {:type :on-binary
                                    :payload %1
                                    :offset %2
                                    :len %3}))

   :on-close #(go (>! callback-ch {:type :on-close
                                   :status-code %1
                                   :reason %2}))

   :on-error #(go (>! callback-ch {:type :on-error
                                   :throwable %}))))

(defn- spin-dispatcher-channel
  "Creates a channel that will forward a JSON version of its values to
  the provided WebSocket client. If the message received is :close, it
  will close the client and the channel."
  [client]
  (let [dispatcher-ch (chan)]
    (loop-fn-until-nil
     #(if (= :close %)
        ;; close connection and channel
        (do
          (close! dispatcher-ch)
          (ws/close client))
        ;; send the message as a JSON string
        (ws/send-msg client (json/write-str %)))
     dispatcher-ch)))

(defn- parse-messages-chan
  "Creates a channel that maps to JSON the messages of ch"
  [ch]
  (async/map
   #(try
      (-> % :message
          (json/read-str :key-fn clojure.core/keyword))
      (catch Throwable e
        {:type "exception" :error e}))
   [ch]))

(defn- apply-if
  "If x matches predicate p, apply f and return. Otherwise return x as-is."
  [x p f]
  (if (p x) (f x) x))

(declare sub-to-event)
(defn- sub-initial-subscribers
  "Subscribes the channels in initial-subs map to either
  websocket-publication (for WebSocket events) or
  events-publication (for slack events)"
  [websocket-publication events-publication initial-subs]
  (let [ws-topics [:on-connect :on-receive :on-binary :on-close :on-error]
        ws-subs (select-keys initial-subs ws-topics)
        non-ws-topics (remove (set ws-topics) (keys initial-subs))
        non-ws-subs (select-keys initial-subs non-ws-topics)]
    (doall (for [[topic subscriber] ws-subs]
             (let [subscriber (apply-if subscriber clojure.test/function? loop-fn-until-nil)]
               (sub websocket-publication topic subscriber))))
    (doall (for [[topic subscriber] non-ws-subs]
             (sub-to-event events-publication topic subscriber)))))

(defn- build-connection-map [token-or-map]
  (if (string? token-or-map)
    {:api-url "https://slack.com/api"
     :token token-or-map}
    token-or-map))

(defn- internal-connect
  "Connects to a Real Time Messaging session via WebSockets using the provided connection,
  which can be an API token or a map like this: {:api-url
  \"https://slack.com/api\" :token token-or-map}.

  Returns a map containing these properties:

  - :events-publication a publication object you can subscribe to in order to
  listen for slack events

  - :dispatcher can be used to send events/messages to slack

  - :websocket-publication a publication object you can subscribe to in order to
  get raw callbacks from the websocket client

  - :start the response from the Slack API rtm.start method, which contains data
  about the current state of the team: https://api.slack.com/methods/rtm.start

  initial-subs can be provided as :topic ch-or-fn.

  topic can be a websocket listener event (that will be subscribed
  to :websocket-publication):

  :on-connect :on-receive :on-binary :on-close :on-error

  as well as a slack RTM event type (e.g. \"im_open\", :message, etc.)
  which will be subscribed to :events-publication. Topics can be strings
  or keywords.

  ch-or-fn is the channel to subscribe or a function to invoke for each
  event produced by the publication"
  [conn-type connection & {:as initial-subs}]
  (let [connection-map (build-connection-map connection)
        ;; create a publication of websocket raw callbacks
        callback-ch (chan)
        websocket-publication (pub callback-ch :type)
        ;; subscribe a channel to the :on-receive callbacks
        ;; and create a publication of parsed slack events
        incoming-msg-ch (sub websocket-publication :on-receive (chan))
        events-publication (pub (parse-messages-chan incoming-msg-ch)
                                #(or (:type %) (if-not (:ok %) "error")))
        ;; subscribe initial subscribers
        _ (sub-initial-subscribers websocket-publication events-publication initial-subs)
        ;; save the response from rtm/start to pass back to caller
        start (if (= conn-type :start-url) (rtm/start connection-map) (rtm/connect connection-map))
        _ (when (start :error) (throw (new RuntimeException (str "Failed to start connection: " start))))
        ;; connect to the RTM API via websocket session and
        ;; get a channel that can be used to send data to slack
        dispatcher (-> start
                       :url
                       (ws-connect callback-ch)
                       spin-dispatcher-channel)]
    {:start start
     :websocket-publication websocket-publication
     :events-publication events-publication
     :dispatcher dispatcher}))

(defn connect [connection & initial-subs]
  (apply internal-connect :connect-url connection initial-subs))

(defn start [connection & initial-subs]
  (apply internal-connect :start-url connection initial-subs))

(defn send-event
  "Sends a RTM event to slack. Send :close to close the connection."
  [dispatcher event]
  (if (keyword? event)
    ;; if the event is a :keyword, just send it
    (go (>! dispatcher event))
    ;; otherwise make sure it has an :id key
    (go (>! dispatcher
            ;; add a random id if none provided
            (update-in event [:id] #(or % (rand-int Integer/MAX_VALUE)))))))

(defn sub-to-event
  "Subscribe to slack events with type type. If channel was specified
  use it to subscribe. Otherwise create an unbuffered channel. An unary
  function can be supplied instead of a channel, in which case it will
  be called for every value received from the subscription."

  ([publication type]
     (sub-to-event publication type (chan)))

  ([publication type ch-or-fn]
     (let [ ;; this allows to use keywords as topics
           type (apply-if type keyword? name)
           taker (apply-if ch-or-fn clojure.test/function? loop-fn-until-nil)]
       (sub publication type taker)
       taker)))

(defn unsub-from-event
  "Unsubscribe a channel from the provided event type."
  [publication type ch]
  (unsub publication type ch))
