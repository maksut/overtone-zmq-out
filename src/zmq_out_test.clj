(ns zmq-out
  (:import [java.nio ByteBuffer ByteOrder]
           [java.io InputStream File])
  (:require [clojure.core.async :as a :refer [<!! >!! <! >!]]
            [zeromq.zmq :as zmq]
            [overtone.core :as o]
            [overtone.config.log :as log]
            [overtone.sc.buffer :as buf]
            [overtone.studio.scope :as s]
            [overtone.sc.machinery.server.connection :as c]
            [overtone.osc :as osc]
            [overtone.sc.machinery.server.osc-validator :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; patching overtone ;;;;;;;;;;;;;;;;;;;;;;;;;
(defn msg->float-array [^bytes buf]
  (let [data-size (alength buf)
        result (float-array (/ data-size 4))]
    (-> (ByteBuffer/wrap buf)
        (.order (ByteOrder/nativeOrder))
        (.asFloatBuffer)
        (.get result)); TODO: array copy here can be avoided
    result))

; In theory zeromq can use IPC but java library used here (jeromq) doesn't support it fully
; (def socket-address "ipc://overtone/zmqout/testing")
; So we use tcp socket. It's a loopback so should be faster than regular sockets.
; Don't know how it fares against IPC.
(def socket-address "tcp://127.0.0.1:5555")

(defn buffer-listener [chan]
  "Starts a zmq subscriber. And waits on chan.
  When received a truthy, it starts polling the socket for 50 milliseconds.
  If a message is recieved, pushes it to the queue. Otherwise pushes a false."
  (a/go
    (let [context (zmq/zcontext)
          poller (zmq/poller context 1)]
      (with-open [subscriber (doto (zmq/socket context :sub)
                               (zmq/connect socket-address)
                               (zmq/subscribe ""))]
        (zmq/register poller subscriber :pollin)
        ;; wait for main thread to begin polling
        (while (<! chan)
          (zmq/poll poller 50) ;; poll for 50 milliseconds
          ;; either timed out or we have the buffer
          (>! chan (if (zmq/check-poller poller 0 :pollin)
                     (msg->float-array (zmq/receive subscriber zmq/dont-wait))
                     false)))))))

(defonce buffer-chan (atom (a/chan)))

(defn buffer-data [buf]
  ;; notify buffer-listener to begin polling
  (>!! @buffer-chan true)
  ;; notify the server to stream the buffer 
  (o/snd "/cmd" "zmqOut" "streamBuffer" 0 (o/buffer-id buf))
  ;; wait for buffer-listener to receive the buffer
  (<!! @buffer-chan))

(alter-var-root #'overtone.sc.buffer/buffer-data (constantly buffer-data))
(alter-var-root #'overtone.studio.scope/ensure-internal-server! (constantly #()))

;; hack the validation so it passes cmd "zmqOut"
(alter-var-root
 #'overtone.sc.machinery.server.osc-validator/OSC-TYPE-SIGNATURES
 (constantly (assoc v/OSC-TYPE-SIGNATURES "/cmd" [:anything*])))

;;
;; zmq sockets refuse to live inside Runtime.exec so using the newer ProcessBuilder.
;; Internet says Runtime.exec might hold the process depending on how stdout/err buffers are consumed.
;; That *might* be why but I don't know what to believe.
;;

;; exact copy from overtone
(defn- sc-log-external
  "Pull audio server log data from a pipe and store for later printing."
  [^InputStream stream read-buf]
  (while (pos? (.available stream))
    (let [n   (min (count read-buf) (.available stream))
          _   (.read stream read-buf 0 n)
          msg (String. ^"[B" read-buf 0 n)
          error? (re-find #"World_OpenUDP" msg)]
      (swap! c/external-server-log* conj msg)
      (if error?
        (log/error msg)
        (log/info msg)))))

;; modified to use ProcessBuilder instead of Runtime.exec
(defn external-booter
  "Boot thread to start the external audio server process and hook up to
  STDOUT for log messages."
  ([cmd] (external-booter cmd "."))
  ([cmd ^java.lang.String working-dir]
   (log/info "Booting external audio server with cmd: " (seq cmd) ", and working directory: " working-dir)
   (let [working-dir  (File. working-dir)
         proc-builder (new ProcessBuilder cmd)
         proc-builder (.directory proc-builder working-dir)
         proc         (.start proc-builder)
         in-stream    (.getInputStream proc)
         err-stream   (.getErrorStream proc)
         read-buf     (make-array Byte/TYPE 256)]
     (while (not (= :disconnected @c/connection-status*))
       (sc-log-external in-stream read-buf)
       (sc-log-external err-stream read-buf)
       (Thread/sleep 250))
     (.destroy proc))))

(alter-var-root #'overtone.sc.machinery.server.connection/external-booter (constantly external-booter))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; end of patch ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro nanotime [expr]
  `(let [start# (System/nanoTime)
         ret# ~expr]
     (- (System/nanoTime) start#)))

(defmacro nanotime-avg [expr sample-size]
  `(let [samples# (take ~sample-size (repeatedly #(nanotime ~expr)))
         avg# (int (/ (apply + samples#) ~sample-size))]
     avg#))

(comment
  (o/boot-external-server)

  ;; start a PUB socket on server
  (o/snd "/cmd" "zmqOut" "start" 0 socket-address)

  ;; start a zmq subscriber socket
  (buffer-listener @buffer-chan)

  ;; creates a test buffer
  (def b (o/buffer (* 44100 4.0) 1));; 4 seconds 1 channel buffer

  (o/defsynth buf-test []
    (let [sig (o/sin-osc:ar 440)]
      (o/record-buf:ar sig b)
      (o/out 0 sig)))

  (def b-test (buf-test))
  (o/kill b-test)

  (o/buffer-read b)
  (buffer-data b)

  ;; time differences between buffer-read and new buffer-data
  (let [sample-size 10
        read-avg (nanotime-avg (o/buffer-read b) sample-size)
        data-avg (nanotime-avg (buffer-data b) sample-size)]
    {:buffer-read read-avg
     :buffer-data data-avg
     :ratio (double (/ read-avg data-avg))})

  ;; in my pc buffer-data is about 150x faster
  ;; {:buffer-read 1829317170,
  ;;  :buffer-data 12135280,
  ;;  :ratio 150.7437133712613}

  ;; test pscope
  (s/pscope)
  (o/demo (o/pulse:ar (o/exp-rand 30 500)))
  (o/stop)
)
