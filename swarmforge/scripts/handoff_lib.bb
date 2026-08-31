#!/usr/bin/env bb

(ns handoff-lib
  (:require [babashka.fs :as fs]
            [babashka.process]
            [clojure.string :as str]))

(defn same-path? [a b]
  (try
    (= (str (fs/canonicalize a)) (str (fs/canonicalize b)))
    (catch Exception _
      (= (str a) (str b)))))

(defn git-toplevel []
  (let [out (:out (babashka.process/sh {:continue true} "git" "rev-parse" "--show-toplevel"))]
    (when-not (str/blank? out)
      (str/trim out))))

(defn state-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs"))

(defn inbox-dir []
  (fs/path (state-dir) "inbox"))

(defn roles-at? [root]
  (and root (fs/exists? (fs/path root ".swarmforge" "roles.tsv"))))

(defn git-common-dir []
  (let [out (:out (babashka.process/sh {:continue true} "git" "rev-parse" "--git-common-dir"))]
    (when-not (str/blank? out)
      (let [path (fs/path (str/trim out))]
        (str (if (fs/absolute? path) path (fs/absolutize path)))))))

(defn project-root []
  (or (let [parent (some-> (git-common-dir) fs/parent str)]
        (when (roles-at? parent) parent))
      (when (roles-at? (git-toplevel)) (git-toplevel))
      (when (roles-at? (fs/cwd)) (fs/cwd))
      (throw (ex-info "Cannot find SwarmForge project root" {:exit 1}))))

(defn roles-file []
  (fs/path (project-root) ".swarmforge" "roles.tsv"))

(defn role-rows []
  (->> (str/split-lines (slurp (str (roles-file))))
       (map #(str/split % #"\t" -1))))

(defn infer-role-from-worktree []
  (let [here (or (git-toplevel) (str (fs/cwd)))]
    (some (fn [cols]
            (let [role-name (first cols)
                  wt (when (>= (count cols) 3) (nth cols 2))]
              (when (and (not-empty role-name) (not-empty wt) (same-path? wt here))
                role-name)))
          (role-rows))))

(defn role []
  (or (not-empty (System/getenv "SWARMFORGE_ROLE"))
      (infer-role-from-worktree)
      (throw (ex-info "Set SWARMFORGE_ROLE." {:exit 1}))))

(defn role-row [role-name]
  (or (some #(when (= role-name (first %)) %) (role-rows))
      (throw (ex-info (str "Unknown role: " role-name) {:exit 1}))))

(defn role-known? [role-name]
  (boolean (some #(= role-name (first %)) (role-rows))))

(defn role-worktree-name [role-name]
  (second (role-row role-name)))

(defn role-receive-mode [role-name]
  (let [mode (nth (role-row role-name) 6 "")]
    (if (str/blank? mode) "task" mode)))

(defn role-propagation [role-name]
  (let [mode (nth (role-row role-name) 7 "")]
    (if (str/blank? mode) "forward-only" mode)))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn valid-priority? [value]
  (boolean (re-matches #"[0-9][0-9]" value)))

(defn header-field [file field]
  (let [prefix (str field ": ")]
    (some (fn [line]
            (when (str/starts-with? line prefix)
              (subs line (count prefix))))
          (take-while (complement str/blank?)
                      (str/split-lines (slurp (str file)))))))

(defn body [file]
  (let [[_ body] (str/split (slurp (str file)) #"\n\n" 2)]
    (or body "")))

(defn set-header! [file field value]
  (let [file (fs/path file)
        lines (str/split-lines (slurp (str file)))
        prefix (str field ": ")
        tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".headers."})
        result (loop [remaining lines
                      out []
                      inserted? false
                      replaced? false]
                 (if-let [line (first remaining)]
                   (cond
                     (and (not inserted?) (str/blank? line))
                     (recur (next remaining)
                            (conj (cond-> out (not replaced?) (conj (str prefix value))) line)
                            true
                            replaced?)

                     (and (not inserted?) (str/starts-with? line prefix))
                     (recur (next remaining) (conj out (str prefix value)) inserted? true)

                     :else
                     (recur (next remaining) (conj out line) inserted? replaced?))
                   (cond-> out
                     (and (not inserted?) (not replaced?)) (conj (str prefix value)))))]
    (spit (str tmp) (str (str/join "\n" result) "\n"))
    (fs/move tmp file {:replace-existing true})))

(defn print-task [file]
  (let [task-name (header-field file "task")
        task-id (header-field file "task_id")]
    (println "TASK:" (str file))
    (println "FROM:" (or (header-field file "from") "unknown"))
    (println "TYPE:" (or (header-field file "type") "unknown"))
    (println "PRIORITY:" (or (header-field file "priority") "50"))
    (when task-name
      (println "TASK_NAME:" task-name))
    (when task-id
      (println "TASK_ID:" task-id))
    (println "PAYLOAD:")
    (print (body file))))

(defn handoff-files [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".handoff")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn print-batch [batch-dir]
  (let [files (handoff-files batch-dir)]
    (when (empty? files)
      (throw (ex-info (str "AMBIGUOUS_TASK_STATE: batch contains no tasks: " batch-dir) {:exit 2})))
    (println "BATCH:" (str batch-dir))
    (println "COUNT:" (count files))
    (when-let [name (header-field (first files) "task")]
      (println "TASK_NAME:" name))
    (println "PRIORITY:" (or (header-field (first files) "priority") "50"))
    (doseq [[index file] (map-indexed vector files)]
      (println)
      (println "BATCH_ITEM:" (inc index))
      (print-task file))))

(defn archive-current-role! []
  (let [script (str (fs/path (fs/parent *file*) "pack_board.sh"))
        result (babashka.process/sh {:continue true}
                                    script "archive" "--role" (role)
                                    "--root" (str (project-root)))]
    (when-not (zero? (:exit result))
      (binding [*out* *err*]
        (print (str (:err result) (:out result)))))))

(defn announce-follow-up! []
  (if (seq (handoff-files (fs/path (inbox-dir) "new")))
    (println "MAIL_WAITING")
    (println "NO_TASK")))

(defn finish-done! []
  (try
    (archive-current-role!)
    (catch Exception e
      (binding [*out* *err*]
        (println (str "archive failed role=" (try (role) (catch Exception _ "?"))
                      " root=" (try (str (project-root)) (catch Exception _ "?"))
                      " error=" (.getMessage e)))
        (flush))))
  (announce-follow-up!))

(defn next-sequence []
  (let [dir (state-dir)
        seq-file (fs/path dir "sequence")
        lock-dir (fs/path dir "sequence.lock")]
    (fs/create-dirs dir)
    (loop []
      (when-not (try (fs/create-dir lock-dir) true (catch Exception _ false))
        (Thread/sleep 50)
        (recur)))
    (try
      (let [last-value (if (fs/exists? seq-file)
                         (str/trim (slurp (str seq-file)))
                         "0")
            last-number (if (re-matches #"[0-9]+" last-value)
                          (Long/parseLong last-value)
                          0)
            next-number (inc last-number)]
        (spit (str seq-file) (format "%06d\n" next-number))
        (format "%06d" next-number))
      (finally
        (fs/delete-tree lock-dir)))))

(defn -main [& args]
  (try
    (case (first args)
      "role" (println (role))
      "state-dir" (println (state-dir))
      "inbox-dir" (println (inbox-dir))
      "project-root" (println (project-root))
      "role-known" (System/exit (if (role-known? (second args)) 0 1))
      "role-worktree-name" (println (role-worktree-name (second args)))
      "role-receive-mode" (println (role-receive-mode (second args)))
      "role-propagation" (println (role-propagation (second args)))
      "timestamp" (println (timestamp))
      "id-timestamp" (println (id-timestamp))
      "valid-priority" (System/exit (if (valid-priority? (second args)) 0 1))
      "header-field" (if-let [value (header-field (second args) (nth args 2))]
                       (println value)
                       (System/exit 1))
      "body" (print (body (second args)))
      "set-header" (set-header! (second args) (nth args 2) (nth args 3))
      "print-task" (print-task (second args))
      "print-batch" (print-batch (second args))
      "next-sequence" (println (next-sequence))
      "finish-done" (finish-done!)
      (do
        (binding [*out* *err*]
          (println "Usage: handoff_lib.bb <command> [args...]"))
        (System/exit 2)))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit (or (:exit (ex-data e)) 1)))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
