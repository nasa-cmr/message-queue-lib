(ns cmr.message-queue.queue.memory-queue
  "In-memory implementation of index-queue functionality"
  (:require [cmr.common.lifecycle :as lifecycle]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.config :as cfg]
            [cmr.common.services.errors :as errors]
            [cmr.message-queue.config :as config]
            [cheshire.core :as json]
            [cmr.message-queue.services.queue :as queue])
  (:import java.util.concurrent.LinkedBlockingQueue))

(def MEMORY_QUEUE_MESSAGE_CAPACITY 1000)

(defn- push-message
  "Pushes a message on the queue whether it is running or not."
  [mem-queue queue-name msg]
  (let [queue (get @(:queue-atom) queue-name)]
    (future (.put queue msg))))

(defn- push-message-with-validation
  "Validates that the queue is running, then push a message onto it. If the queue
  is not running, throws an exception."
  [mem-queue msg]
  (when-not @(:running-atom mem-queue)
    (errors/internal-error! "Queue is down!"))
  (push-message mem-queue msg))

(defn- attempt-retry
  "Retry a message on the queue if the maximum number of retries has not been exceeded"
  [queue msg resp]
  (let [repeat-count (get msg :repeat-count 0)]
    (if (> (inc repeat-count) (count (config/rabbit-mq-ttls)))
      (debug "Max retries exceeded for processing message:" (pr-str msg))
      (let [msg (assoc msg :repeat-count (inc repeat-count))]
        (debug "Message" (pr-str msg) "re-queued with response:" (pr-str (:message resp)))
        ;; We don't use any delay before re-queuing with the in-memory queues
        (.put queue msg)))))

(defn- start-consumer
  "Repeatedly pulls messages off the queue and calls a callback with each message"
  [queue-broker queue-name handler]
  (debug "Starting consumer for queue" queue-name)
  (let [queue-map-atom (:queue-map-atom queue-broker)
        queue (first (get @queue-map-atom queue-name))]
    (future
      (loop [msg (.take queue)]
        (let [action (:action msg)]
          (if (= :quit action)
            (info "Quitting consumer for queue" queue-name)
            (do (try
                  (let [resp (handler msg)]
                    (case (:status resp)
                      :ok (debug "Message" msg "processed successfully")

                      :retry (attempt-retry queue msg resp)

                      :fail
                      ;; bad data - nack it
                      (debug "Rejecting bad data:" (:message resp))))
                  (catch Exception e
                    (error "Message processing failed for message" msg "with error:"
                           (.gettMessage e))
                    (attempt-retry queue msg {:message (.gettMessage e)})))
              (recur ((.take queue))))))))
    ;; increment the listener count for the queue
    (swap! queue-map-atom (fn [queue-map]
                            (update-in queue-map [queue-name 1] inc)))))

(defn- named-queue
  "Get the queue entry [queue num-listeners] (if any) for the qiven name"
  [queue-broker queue-name]
  (let [queue-map @(:queue-map-atom queue-broker)]
    (get queue-map queue-name)))

(defn purge-queue
  "Remove all the messages on the queue"
    [broker queue-name]
    (let [[queue _](named-queue broker queue-name)]
      (.clear queue)))

(defn delete-queue
  "Remove a queue and all its messages"
    [broker queue-name]
    ;; get a reference to the queue so we can access it even after we remove it from the map
    (let [queue-map-atom (:queue-map-atom broker)
          [queue num-listeners] (named-queue broker queue-name)]
      ;; remove it from the map so no new consumers will start listening to it
      (swap! queue-map-atom (fn [queue-map] (dissoc queue-map queue-name)))
      ;; now close all the listeners - TODO fix race condition of consumer getting added
      ;; after we read num-listeners above
      (doseq [n (range 0 num-listeners)]
        (.put queue {:action :quit}))))

(defrecord MemoryQueueBroker
  [
   ;; maximum number of elements in the queue
   queue-capacity

   ;; holds a map of names to Java BlockingQueue instances
   queue-map-atom

   ;; queues that must be created on startup
   required-queues

   ;; Flag indicating running or not - this needs to be an auto since the same in-memory
   ;; broker is used for both indexer and ingest
   running-atom
   ]

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  lifecycle/Lifecycle

  (start
    [this system]
    (info "Starting memory queue")
    (when-not @(:running-atom this)
      (swap! (:queue-map-atom this) (fn [_] {}))
      (swap! (:running-atom this) (fn [_] true))

      (doseq [queue-name (:required-queues this)]
        (queue/create-queue this queue-name))
      (debug "Required queues created")
      this))

  (stop
    [this system]
    (when @(:running-atom this)
      (info "Stopping memory queue and removing all queues")
      (doseq [queue-name (keys @(:queue-map-atom this))]
        (delete-queue this queue-name))
      (swap! (:running-atom this) (fn [_] false))
      this))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  queue/Queue

  (create-queue
    [this queue-name]
    (let [queue-map-atom (:queue-map-atom this)
          ;; create the queue
          _ (info "Creating queue" queue-name)
          queue (new java.util.concurrent.LinkedBlockingQueue
                     (:queue-capacity this))]
      (swap! queue-map-atom (fn [queue-map]
                              ;; add an entry for the queue and its starting listener count (0)
                              (assoc queue-map queue-name [queue 0])))))


  (publish
    [this queue-name msg]
    (debug "publishing msg:" msg " to queue:" queue-name)
    (when-not @(:running-atom this)
      (errors/internal-error! "Queue is not running."))
    (let [queue (named-queue this queue-name)]
      (.put queue msg)))

  (subscribe
    [this queue-name handler params]
    (start-consumer this queue-name handler))

  (message-count
    [this queue-name]
    (let [[queue _] (named-queue this queue-name)]
      (.size queue)))

  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-queue-broker
  "Creates a simple in-memory queue broker"
  [required-queues]
  (->MemoryQueueBroker MEMORY_QUEUE_MESSAGE_CAPACITY (atom nil) required-queues (atom false)))

