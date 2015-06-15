# slack-rtm [![ci-status](https://travis-ci.org/casidiablo/slack-rtm.svg?branch=master)](https://travis-ci.org/casidiablo/slack-rtm)

A Clojure library to interact with the [Slack][1] [Real Time Messaging API][2].
It's powered by [`clj-slack`][3] and `core.async`.

## Usage

Include `[slack-rtm "0.1.0"]` in your dependencies. Get a
[Slack token][4] (it can be a bot token too).  Then:

```clojure
(use 'slack-rtm.core)

;; connect to the Real Time Messaging API
(def rtm-conn (connect "your-token"))

;; rtm-conn is a map with publications and channels that
;; allows you to receive and send data to and from Slack.

;; :events-publication allows you to listen for slack events
(def events-publication (:events-publication rtm-conn))

;; let's listen for events of type :pong
(def pong-receiver #(println "got this:" %))
(sub-to-event events-publication :pong pong-receiver)

;; send events to Slack by getting the dispatcher channel
(def dispatcher (:dispatcher rtm-conn))
(send-event dispatcher {:type "ping"})

;; at this point pong-receiver should have been called with a pong response

```

The map returned by `connect` has four items:

- `:start` is map containing the response from the Slack API
  [rtm.start](https://api.slack.com/methods/rtm.start)
  method, which contains data about the current state of the team:

- `:events-publication` is a `core.async` [publication][5] that you can
 use to subscribe to the different kind of [slack event types][6]. You
 can use `core.async`'s `sub` method using as topic the string version
 of the event type (e.g. `"message"`, `"im_open"`, etc.). Or better yet,
 use the `sub-to-event` function that allows you to subscribe both a
 `core.async` channel or an unary function; it also allows you to
 subscribe using keywords (e.g. `:message`, `:im_open`, etc.).

- `:dispatcher` is a `core.async` channel you can use to send events to
slack. You can use `core.async` primitive methods (`>!!`, `>!`, `put!`),
or better yet use `send-event` which automatically adds an `:id` to
the map if none is present.

- `:websocket-publication` is a `core.async` publication that allows
you to subscribe to raw WebSocket callbacks. It support the following
topics: `:on-connect`, `:on-receive`, `:on-binary`, `:on-close`, `:on-error`.
Refer to [stylefruits/gniazdo][7] for information on these.

### Hook subscriptions before connecting

Using `(connect "token")` will connect right away, which means you can
miss events (like the `hello` event) by the time you subscribe. You can
subscribe to any event before the connection has been performed by
specifying a list of `:topics channel-or-funciton` pairs to `connect`
like this:

```clojure
(connect "token"
         :hello #(prn %)
         :on-close (fn [status reason] (prn status reason)))
```

## License

Distributed under the WTFPL.


  [1]: http://slack.com
  [2]: https://api.slack.com/rtm
  [3]: https://github.com/julienXX/clj-slack
  [4]: https://api.slack.com/tokens
  [5]: https://clojure.github.io/core.async/#clojure.core.async/pub
  [6]: https://api.slack.com/events
  [7]: https://github.com/stylefruits/gniazdo
