(ns clojars.scpII
  (:require [clojure.string :as string])
  (:import (org.apache.sshd SshServer)
           (org.apache.sshd.server PublickeyAuthenticator FileSystemAware Command)
           (org.apache.sshd.server.command ScpCommand ScpCommandFactory)
           (org.apache.sshd.server.keyprovider SimpleGeneratorHostKeyProvider)
           (java.io InputStreamReader BufferedReader)))

(def pem-files "/Users/hiredman/Downloads/one.pem")

(defn preprocess-command [command]
  (loop [[x & xs] (rest (.split command " ")) accum []]
    (cond
     (not (.startsWith (.trim x) "-"))
     {:flags accum :files (string/join \space (conj xs x))}
     (= (.trim x) "--")
     (recur xs accum)
     :else
     (recur xs (conj accum x)))))

(defn process-command [{:keys [flags files]}]
  {:files files
   :flags (->> (for [f flags]
                 [(keyword (apply str (drop 1 f))) true])
               (into {}))})

(def OK 0)
(def WARNING 1)
(def ERROR 2)

(def *log*)

(defn send-ack [outs]
  (.write outs OK)
  (.flush outs))

(defn read-line [ins]
  (let [rdr (-> ins InputStreamReader. BufferedReader.)]
    (if-let [line (.readLine rdr)]
      line
      (throw (java.io.IOException. "End of stream")))))

(defn read-ack [ins can-eof?]
  (let [c (.read ins)]
    (condp = c
      -1 (when-not can-eof?
           (throw (java.io.EOFException.)))
      WARNING (.warn *log* (str "Received warning: " (read-line ins)))
      ERROR (throw (java.io.IOException.
                    (str "Received nack: " (read-line ins))))
      nil)
    c))

(defn scp-command [command]
  (binding [*log* (org.slf4j.LoggerFactory/getLogger
                   (str 'clojars.scpII))]
    (let [in (promise)
          out (promise)
          err (promise)
          callback (promise)
          thread (promise)
          env (promise)
          cmd (process-command command)
          log *log*]
      (reify
        Command
        (setInputStream [_ ins]
          (deliver in ins))
        (setOutputStream [_ outs]
          (deliver out outs))
        (setErrorStream [_ errs]
          (deliver err errs))
        (setExitCallback [_ callbacka]
          (deliver callback callbacka))
        (start [this enva]
          (deliver env enva)
          (deliver thread (doto (Thread. this (pr-str cmd)) .start)))
        (destroy [_])
        Runnable
        (run [_]
          (binding [*log* log]
            (try
              (condp #(%2 %1) (:flags cmd)
                :t (letfn [(k1 [{:keys [line dir? c] :as m}]
                             (.info *log* (str `k1 " " (pr-str m) " " (char c)))
                             (cond
                              (= -1 c) nil
                              (= (char c) \D) (k2 (assoc m
                                                    :dir? true))
                              (= (char c) \C) (k3 (assoc m
                                                    :line (str (char c)
                                                               (read-line
                                                                @in))))
                              (= (char c) \E)  (read-line @in)
                              :else (k3 m)))
                           (k2 [{:keys [line dir? c] :as m}]
                             (.info *log* (str `k2 " " (pr-str m) " " (char c)))
                             (condp #(= (int %1) (int %2)) c
                               \C (k3 (assoc m
                                        :line (str (char c) (read-line @in))))
                               \E (read-line @in)
                               :else (k3 m)))
                           (k3 [{:keys [line dir? c] :as m}]
                             (.info *log* (str `k3 " " (pr-str m) " " (char c)))
                             (if (and dir? (:r (:flags cmd)))
                               (.info *log* "DIRECTORY")
                               (.info *log* "FILE")))]
                     (send-ack @out)
                     (k1 {:c (read-ack @in true)}))))
            (.onExit @callback 0 "GO AWAY")))))))

(defn launch-ssh []
  (let [sshd (doto (SshServer/setUpDefaultServer)
               (.setPort 3333)
               (.setPublickeyAuthenticator
                (reify
                  PublickeyAuthenticator
                  (authenticate [_ username key serssion]
                    true)))
               (.setCommandFactory
                (proxy [ScpCommandFactory] []
                  (createCommand [command]
                    (println command)
                    (when-not (.startsWith (.trim command) "scp")
                      (throw
                       (IllegalArgumentException.
                        "Unknown command, does not begin with 'scp'")))
                    (scp-command (preprocess-command command)))))
               (.setKeyPairProvider
                (SimpleGeneratorHostKeyProvider. pem-files)))]
    (doto sshd .start)))


