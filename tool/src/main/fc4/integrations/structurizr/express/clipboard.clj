(ns fc4.integrations.structurizr.express.clipboard
  (:require [clojure.core.async :as ca :refer [<! chan go-loop offer! poll! timeout]]
            [fc4.integrations.structurizr.express.edit
             :as ed
             :refer [probably-diagram-yaml? process-file]])
  (:import [java.awt Toolkit]
           [java.awt.datatransfer Clipboard DataFlavor StringSelection])
  (:refer-clojure :exclude [slurp spit]))

; Suppress the Java icon from popping up and grabbing focus on MacOS.
; Found in a comment to this answer: https://stackoverflow.com/a/17544259/7012
(System/setProperty "apple.awt.UIElement" "true")

;; Based on code found at https://gist.github.com/Folcon/1167903
;; This was a simple var at one point but that broke CI builds, which run on
;; headless machines; therefore this needs to be a function and it shouldn’t be
;; called during CI test runs.
(defn clipboard ^Clipboard
  []
  (.getSystemClipboard (Toolkit/getDefaultToolkit)))

;; based on code found at https://gist.github.com/Folcon/1167903
(def string-flavor (DataFlavor/stringFlavor))

;; based on code found at https://gist.github.com/Folcon/1167903
(defn slurp []
  (try
    ;; We’re gonna check twice for the contents being a string because of
    ;; possible race conditions.
    (when (.isDataFlavorAvailable (clipboard) string-flavor)
      (let [transferable (.getContents (clipboard) nil)]
        (when (.isDataFlavorSupported transferable string-flavor)
          (.getTransferData transferable string-flavor))))
    (catch java.lang.NullPointerException e nil)))

(defn spit
  [s]
  {:pre [(string? s)]}
  (.setContents (clipboard) (StringSelection. s) nil))

(defn pcb
  "Process Clipboard — process the contents of the clipboard and write the results back to the
  clipboard. If the contents of the clipboard are not a FC4 diagram, a RuntimeException is
  thrown."
  []
  (let [contents (slurp)]
    (if (probably-diagram-yaml? contents)
      (-> contents
          process-file
          ::ed/str-processed
          spit)
      (throw (RuntimeException. "Not a FC4 diagram.")))))

(def ^:private current-local-time-format
  (java.text.SimpleDateFormat. "HH:mm:ss"))

(defn ^:private current-local-time-str []
  (.format current-local-time-format (java.util.Date.)))

(defn ^:private err-name
  [e]
  (-> e class .getSimpleName))

(defn try-process
  "Accepts a Structurizr Express diagram as a YAML string, attempts to process
  it, and if successful, returns the result. If an error occurs, prints some
  debugging info to stdout and then returns nil. If no error occurs, prints a
  success message to stdout. Why the side effects? Because I needed to put them
  somewhere, and wcb is already hard to read even when it is solely concerned
  with control flow. So this function is responsible for messaging the outcome
  to the user."
  [contents]
  (try
    (let [{main       ::ed/main-processed
           str-result ::ed/str-processed} (process-file contents)
          {:keys [:type :scope]} main]
      (println (current-local-time-str) "-> processed" type "for" scope "with great success!")
      (flush)
      str-result)
    (catch Exception err
      ; This takes pains to print the name of the error, even though it’s almost
      ; certainly included in the string version of the error, because some of
      ; the SnakeYAML exception classes seem to have a bug in their toString
      ; implementations wherein the results don’t include the (simple) name of
      ; the class.
      (println "\nUnfortunately, a" (err-name err) "error has occurred:\n\n" err
               "\n\nIf you can spare a moment, please paste the above error"
               "into a new issue at"
               "https://github.com/FundingCircle/fc4/issues/new — thanks!\n")
      (flush)
      nil)))

(def ^:private stop-chan (chan 1))

(defn wcb
  "Start a background routine that watches the clipboard for changes. If the
  changed content is a FC4 diagram in YAML, processes it and writes the
  result back to the clipboard.
  
  Stop the routine by calling stop.
  
  Returns a channel that will block until the routine exits, at which point nil
  will be emitted to the channel, closing it. This may be useful if a caller
  wishes to block while this routine is running."
  []
  ;; Just in case stop was accidentally called twice, in which case there’d be a superfluous value
  ;; in the channel, we’ll remove a value from the channel just before we get started.
  (poll! stop-chan)

  (go-loop [prior-contents nil]
    (let [contents (slurp)
          process? (and (not= contents prior-contents)
                        (probably-diagram-yaml? contents))]
      (when process?
        (when-let [processed (try-process contents)]
          (spit processed)))
      (if (poll! stop-chan)
        (do (println "Stopped!")
            (flush)
            nil)
        (let [contents (slurp)]
          (<! (timeout 1000))
          (recur contents))))))

(defn stop
  "Stop the goroutine started by wcb."
  []
  (offer! stop-chan true))