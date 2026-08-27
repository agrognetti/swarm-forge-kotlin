#!/usr/bin/env bb
;; dry4kotlin - duplication detection for Kotlin and Swift.
;;
;; Wraps PMD's Copy/Paste Detector, which has first-class Kotlin and Swift
;; tokenizers. This is the only constitution tool in this fork that reaches
;; every source set, including iosMain and Swift.
;;
;; CPD is a token-level detector, so it sees through reformatting, comments and
;; whitespace. It does NOT see through renaming: PMD's --ignore-identifiers is
;; implemented for Java but not for the Kotlin or Swift lexers, verified against
;; PMD 7.26.0. Copy-paste is found; copy-paste-then-rename is not. The threshold
;; below is lower than PMD's own default of 100 to partly compensate.

(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/parent (fs/absolutize *file*))))
(require '[sfk.support :as s]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.data.xml :as xml])

(def pmd-version "7.26.0")

(def pmd-url
  (str "https://github.com/pmd/pmd/releases/download/pmd_releases/"
       pmd-version "/pmd-dist-" pmd-version "-bin.zip"))

(defn pmd-binary []
  (let [home (fs/path (s/tools-dir) (str "pmd-bin-" pmd-version))
        exe (fs/path home "bin" "pmd")]
    (when-not (fs/exists? exe)
      (let [zip (fs/path (s/tools-dir) (str "pmd-" pmd-version ".zip"))]
        (s/download! pmd-url zip (str "PMD " pmd-version " (~50MB)"))
        (s/progress "unpacking PMD")
        (let [{:keys [exit err]} (p/sh "unzip" "-q" "-o" (str zip) "-d" (str (s/tools-dir)))]
          (when-not (zero? exit)
            (s/die! (str "Could not unpack " zip) (str/trim (str err)))))
        (fs/delete-if-exists zip)))
    (when-not (fs/exists? exe)
      (s/die! (str "PMD unpacked but " exe " is missing.")
              (str "Delete " home " and run dry4kotlin again to reinstall.")))
    (when-not (fs/executable? exe)
      (fs/set-posix-file-permissions exe "rwxr-xr-x"))
    (str exe)))

;; PMD 7 exit codes: 0 clean, 4 duplications found, 5 recoverable file errors,
;; 1 usage or fatal error. Only 1 means the run itself failed. Relying on exit
;; codes instead of --no-fail-on-* keeps this tool working across PMD 7 point
;; releases, which have moved those flags around.
(def ran-ok #{0 4 5})

(defn run-cpd [pmd language dirs min-tokens]
  (let [out-file (fs/path (s/state-dir "dry") (str "cpd-" language ".xml"))
        ;; --report-file, not stream scraping: CPD renders its report to stderr
        ;; when no report file is given, so a tool that reads stdout finds
        ;; nothing and reports zero duplication. Verified against PMD 7.26.0.
        ;;
        ;; --ignore-identifiers and --ignore-literals are deliberately not
        ;; passed: they are no-ops for the Kotlin and Swift lexers, and a flag
        ;; that silently does nothing would make this report describe an
        ;; analysis that did not happen.
        cmd (concat [pmd "cpd"
                     "--language" language
                     "--minimum-tokens" (str min-tokens)
                     "--format" "xml"
                     "--report-file" (str out-file)]
                    (mapcat (fn [d] ["--dir" d]) dirs))
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        {:keys [exit]} (p/sh (vec cmd) {:dir (s/worktree-root) :out out :err err})]
    (when-not (contains? ran-ok exit)
      (s/die! (str "PMD CPD failed for language " language " (exit " exit ").")
              (str "Command: " (str/join " " cmd))
              ""
              (str/trim (str out))
              (str/trim (str err))
              ""
              "Fix the invocation or report it. Do not estimate duplication."))
    (when-not (fs/exists? out-file)
      (s/die! (str "PMD CPD reported success but wrote no report to " out-file)
              (str "Command: " (str/join " " cmd))))
    {:exit exit :path (str out-file)}))

(defn duplications
  "Parse a CPD XML report. The document is namespaced, so children matches on
  local tag names."
  [path]
  (when (and (fs/exists? path) (pos? (fs/size path)))
    (let [root (xml/parse-str (slurp path))]
      (for [d (s/children root "duplication")]
        {:lines (s/attr-long d :lines 0)
         :tokens (s/attr-long d :tokens 0)
         :files (for [f (s/children d "file")]
                  {:path (s/attr f :path)
                   :line (s/attr f :line)})}))))

(defn relative [path]
  (try (str (fs/relativize (s/worktree-root) path)) (catch Exception _ path)))

(defn report-language [language result]
  (let [dups (vec (duplications (:path result)))]
    (s/heading (str "Duplication: " language))
    (if (empty? dups)
      (println "No duplicate blocks found.")
      (do
        (println (format "%d duplicate block(s), %d duplicated lines total."
                         (count dups) (reduce + (map :lines dups))))
        (println)
        (doseq [d (sort-by (comp - :lines) dups)]
          (println (format "%d lines / %d tokens:" (:lines d) (:tokens d)))
          (doseq [f (:files d)]
            (println (format "    %s:%s" (relative (:path f)) (:line f)))))))
    {:language language :count (count dups) :lines (reduce + (map :lines dups))
     :report (:path result)}))

(defn -main [& args]
  (let [{:keys [flags]} (s/parse-args args #{"--min-tokens" "--max-workers" "--workers"})
        min-tokens (try (Integer/parseInt (str (get flags "--min-tokens" "50")))
                        (catch Exception _ 50))
        kotlin-files (s/files-with-extension "kt")
        swift-files (s/files-with-extension "swift")
        dirs (s/source-dirs)]
    (when (empty? dirs)
      (s/die! (str "No source directories found under " (s/worktree-root))
              "dry4kotlin looks for **/src directories in the worktree."
              "Run it from the worktree assigned to your role."))
    (let [pmd (pmd-binary)
          summaries
          (cond-> []
            (seq kotlin-files)
            (conj (report-language "kotlin" (run-cpd pmd "kotlin" dirs min-tokens)))
            (seq swift-files)
            (conj (report-language "swift" (run-cpd pmd "swift" dirs min-tokens))))]
      (when (empty? summaries)
        (s/die! "No .kt or .swift files found in any src directory."
                (str "Searched: " (str/join ", " (map relative dirs)))))
      (s/heading "DRY summary (dry4kotlin)")
      (println (format "minimum-tokens: %d   PMD %s" min-tokens pmd-version))
      (doseq [x summaries]
        (println (format "  %-7s %3d block(s)  %4d duplicated line(s)"
                         (:language x) (:count x) (:lines x))))
      (println)
      (println (format "Files analysed: %d Kotlin, %d Swift"
                       (count kotlin-files) (count swift-files)))
      (println "Scope: every Kotlin source set and every Swift source set.")
      (println "This is the only constitution tool here that reaches iosMain and Swift.")
      (println)
      (println "Comparison is verbatim token matching. PMD's --ignore-identifiers")
      (println "is not implemented for the Kotlin or Swift lexers, so a duplicated")
      (println "block whose variables were renamed is NOT reported. A clean run")
      (println "means no verbatim duplication, not an absence of duplication.")
      (doseq [x summaries]
        (println (str "Report: " (:report x))))
      ;; A duplicate block is a finding to judge, not a build failure, so this
      ;; exits zero. The agent decides what to extract.
      (System/exit 0))))

(apply -main *command-line-args*)
