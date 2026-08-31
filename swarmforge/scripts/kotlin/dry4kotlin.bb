#!/usr/bin/env bb
;; dry4kotlin - duplication detection for Kotlin and Swift.
;;
;; Wraps PMD's Copy/Paste Detector, which has first-class Kotlin and Swift
;; tokenizers. This is the only constitution tool in this fork that reaches
;; every source set, including iosMain and Swift.
;;
;; The two languages are found two different ways, and that is not tidiness left
;; undone. Gradle decides where Kotlin lives, so Kotlin is searched under `src`.
;; Nothing decides where Swift lives: Xcode keeps a KMP project's Swift in
;; `iosApp/iosApp/` and an SPM package keeps it in `Sources/`, so Swift is
;; searched across the worktree with vendored output excluded by name.
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

(defn run-cpd [pmd language files min-tokens]
  (let [out-file (fs/path (s/state-dir "dry") (str "cpd-" language ".xml"))
        ;; --file-list, not --dir. A directory makes CPD walk the tree a second
        ;; time under its own rules, which are not this tool's: it descends into
        ;; a `build` directory nested inside a source set and has no reason to
        ;; skip `Pods`. The count printed below would then describe a different
        ;; set of files than the one PMD read. Naming the files makes "Files
        ;; analysed" the analysis rather than a parallel estimate of it, and it
        ;; sidesteps --dir splitting its own value on commas.
        list-file (fs/path (s/state-dir "dry") (str "files-" language ".txt"))
        _ (spit (str list-file) (str (str/join "\n" files) "\n"))
        ;; --report-file, not stream scraping: CPD renders its report to stderr
        ;; when no report file is given, so a tool that reads stdout finds
        ;; nothing and reports zero duplication. Verified against PMD 7.26.0.
        ;;
        ;; --ignore-identifiers and --ignore-literals are deliberately not
        ;; passed: they are no-ops for the Kotlin and Swift lexers, and a flag
        ;; that silently does nothing would make this report describe an
        ;; analysis that did not happen.
        cmd [pmd "cpd"
             "--language" language
             "--minimum-tokens" (str min-tokens)
             "--format" "xml"
             "--report-file" (str out-file)
             "--file-list" (str list-file)]
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        {:keys [exit]} (p/sh cmd {:dir (s/worktree-root) :out out :err err})]
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
        ;; Not the src-anchored search Kotlin gets. Gradle lays Kotlin out, and
        ;; nothing lays Swift out: Xcode puts a KMP project's Swift in
        ;; `iosApp/iosApp/`, which no `src` directory contains. Anchoring it
        ;; found zero files on every KMP project and said so as "0 Swift" on a
        ;; line above "this is the only tool here that reaches Swift".
        swift-files (s/worktree-files-with-extension "swift")]
    ;; One guard, on the thing that matters, and before the download. A missing
    ;; `src` directory used to be its own error, which was the wrong question
    ;; twice over: it says nothing about Swift, and it fired after fetching 50MB
    ;; of PMD to analyse a worktree with nothing in it. Both search rules are
    ;; named, because "nothing found" and "looked in the wrong place" print the
    ;; same way otherwise - which is the whole defect this tool had for Swift.
    (when (and (empty? kotlin-files) (empty? swift-files))
      (s/die! (str "No .kt or .swift files found under " (s/worktree-root))
              "Kotlin is searched inside src directories at any depth."
              "Swift is searched anywhere except build, Pods, Carthage and DerivedData."
              "Run it from the worktree assigned to your role."))
    (let [pmd (pmd-binary)
          summaries
          (cond-> []
            (seq kotlin-files)
            (conj (report-language "kotlin" (run-cpd pmd "kotlin" kotlin-files min-tokens)))
            (seq swift-files)
            (conj (report-language "swift" (run-cpd pmd "swift" swift-files min-tokens))))]
      (s/heading "DRY summary (dry4kotlin)")
      (println (format "minimum-tokens: %d   PMD %s" min-tokens pmd-version))
      (doseq [x summaries]
        (println (format "  %-7s %3d block(s)  %4d duplicated line(s)"
                         (:language x) (:count x) (:lines x))))
      (println)
      ;; The count PMD was given, not a second walk of the tree that happens to
      ;; agree with it. Printed for both languages because a zero here is the
      ;; one number that can mean either "no Swift in this project" or "this
      ;; tool looked in the wrong place", and the next two lines say where.
      (println (format "Files analysed: %d Kotlin, %d Swift"
                       (count kotlin-files) (count swift-files)))
      (println "Kotlin scope: every src directory, so every Kotlin source set including iosMain.")
      (println "Swift scope : anywhere in the worktree except build, Pods, Carthage and DerivedData,")
      (println "              because Xcode keeps Swift outside src (iosApp/iosApp/, Sources/).")
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
