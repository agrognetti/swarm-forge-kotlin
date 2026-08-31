#!/usr/bin/env bb
;; detekt - static analysis and complexity signals for Kotlin.
;;
;; Runs the standalone detekt CLI rather than the Gradle plugin, for two
;; reasons that matter to an agent:
;;
;;   1. It needs no change to the project's build scripts, so a role that does
;;      not own the build can still measure.
;;   2. It analyses source text, so it reaches every Kotlin source set,
;;      including iosMain, which Kover and Pitest cannot see.
;;
;; Without --classpath detekt runs without type resolution, so rules that need
;; type information are skipped. That is a property of the run, not a defect,
;; and it is reported below.

(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/parent (fs/absolutize *file*))))
(require '[sfk.support :as s]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.data.xml :as xml])

;; Pinned to the last stable release on purpose. detekt 2.x has been in alpha
;; since 2025-09 and an agent must not measure against a moving alpha.
(def detekt-version "1.23.8")

(def detekt-url
  (str "https://github.com/detekt/detekt/releases/download/v" detekt-version
       "/detekt-cli-" detekt-version "-all.jar"))

;; detekt exit codes: 0 no issues, 2 issues found, 1 unexpected error,
;; 3 invalid configuration.
(def ran-ok #{0 2})

(defn detekt-jar []
  (let [jar (fs/path (s/tools-dir) (str "detekt-cli-" detekt-version "-all.jar"))]
    (when-not (fs/exists? jar)
      (s/download! detekt-url jar (str "detekt CLI " detekt-version)))
    (str jar)))

(def config-candidates
  ["detekt.yml"
   "detekt-config.yml"
   "config/detekt/detekt.yml"
   "config/detekt/config.yml"
   "gradle/detekt.yml"])

(defn project-config []
  (first (for [c config-candidates
               :let [path (fs/path (s/worktree-root) c)]
               :when (fs/exists? path)]
           (str path))))

;; detekt's defaults are wrong about this toolchain in two measurable ways, and
;; neither can be expressed on the command line - detekt takes per-rule options
;; only through a config file. So one ships with the tool, and it always applies.
;;
;; It corrects, it does not quieten. `@Composable` functions are PascalCase
;; because the Compose API guidelines require it and Android Studio's own lint
;; reports the camelCase form detekt asks for, so a rule that asks for camelCase
;; there is wrong rather than strict. And `androidHostTest` appears in none of
;; the fifteen test-source-set exclusion lists detekt ships, because detekt
;; 1.23.8 predates the plugin that named it - measured with two identical files,
;; one in `androidHostTest` and one in `androidUnitTest`, scoring two findings
;; and none. Everything else measured on a real Compose project is still
;; reported; the file lists what and why.
(defn baseline-config []
  (s/templates-dir "detekt-kmp.yml"))

(defn config-chain
  "The config files to apply, in the order detekt applies them: the fork's KMP
  baseline first, then the project's own if it has one.

  Later files override earlier ones, so a project that disagrees with the
  baseline overrides it by writing its own `detekt.yml` - and the baseline can
  never override the project. The baseline is not optional, for the same reason
  the tools refuse a hand-written `pitest` task: which corrections apply is not
  a per-run decision for the role running the tool."
  [given]
  (let [chain (into [(baseline-config)] (when (string? given) [given]))]
    ;; A comma in a path would be read as the separator between two configs, so
    ;; detekt would look for files that do not exist and report on rules nobody
    ;; chose. Measured: detekt refuses a repeated `--config` ("Can only specify
    ;; option --config once"), so the comma form is the only one available and
    ;; the hazard cannot be designed away - it can only be named before it
    ;; produces a report that looks legitimate.
    (when-let [bad (seq (filter #(str/includes? % ",") chain))]
      (s/die! "A detekt config path contains a comma:"
              (str/join "\n" bad)
              ""
              "detekt separates its config files with commas and accepts the"
              "option only once, so such a path cannot be passed at all."
              "Rename the directory, or move the worktree somewhere without a comma."))
    chain))

(defn run-detekt [jar inputs configs]
  (let [xml-out (fs/path (s/state-dir "detekt") "detekt.xml")
        cmd (concat ["java" "-jar" jar
                     "--input" (str/join "," inputs)
                     "--report" (str "xml:" xml-out)
                     "--parallel"]
                    ;; Comma-separated, because that is the only way detekt
                    ;; takes more than one config: it refuses a repeated
                    ;; --config with "Can only specify option --config once".
                    ;; config-chain has already rejected a path with a comma in
                    ;; it, which this form would otherwise mis-split.
                    ;;
                    ;; --build-upon-default-config so these files are read as
                    ;; corrections to the defaults rather than as a replacement
                    ;; for them. Without it, every rule absent from the two
                    ;; files switches off and detekt reports almost nothing - a
                    ;; clean report meaning no analysis, which is the failure
                    ;; mode this fork keeps finding.
                    ["--config" (str/join "," configs)
                     "--build-upon-default-config"])
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        {:keys [exit]} (p/sh (vec cmd) {:dir (s/worktree-root) :out out :err err})]
    (when-not (contains? ran-ok exit)
      (s/die! (str "detekt failed (exit " exit ").")
              (str "Command: " (str/join " " cmd))
              ""
              (str/trim (str out))
              (str/trim (str err))
              ""
              (if (= 3 exit)
                (str "Exit 3 means one of these configurations is invalid: "
                     (str/join ", " configs)
                     ". Fix the config; do not delete it.")
                "Fix the invocation or report it. Do not estimate static-analysis findings.")))
    {:exit exit :xml (str xml-out) :log (str err)}))

(defn findings
  "detekt's XML report is Checkstyle format: file elements holding error
  elements, with the rule id in the source attribute."
  [xml-path]
  (when (and (fs/exists? xml-path) (pos? (fs/size xml-path)))
    (let [root (xml/parse-str (slurp xml-path))]
      (for [file (s/children root "file")
            err (s/children file "error")]
        {:file (s/attr file :name)
         :line (s/attr err :line)
         :severity (s/attr err :severity)
         :rule (last (str/split (or (s/attr err :source) "unknown") #"\."))
         :message (s/attr err :message)}))))

(defn relative [path]
  (try (str (fs/relativize (s/worktree-root) path)) (catch Exception _ (str path))))

(defn source-set-of
  "KMP keeps source sets at <module>/src/<sourceSet>/kotlin. Grouping findings
  by source set tells an agent whether an issue is in shared code or in a
  platform leaf."
  [path]
  (let [parts (str/split (relative path) #"/")
        idx (.indexOf (vec parts) "src")]
    (if (and (>= idx 0) (< (inc idx) (count parts)))
      (nth parts (inc idx))
      "unknown")))

(defn tally [items key-fn]
  (->> items (group-by key-fn) (map (fn [[k v]] [k (count v)])) (sort-by (comp - second))))

(defn -main [& args]
  (let [{:keys [flags]} (s/parse-args args #{"--top" "--config" "--max-workers" "--workers"})
        top (try (Integer/parseInt (str (get flags "--top" "15"))) (catch Exception _ 15))
        inputs (s/source-dirs)
        kotlin-files (s/files-with-extension "kt")]
    (when (empty? kotlin-files)
      (s/die! "No .kt files found in any src directory."
              (str "Searched under " (s/worktree-root))
              "Run this from the worktree assigned to your role."))
    (let [given (let [g (get flags "--config")]
                  (if (string? g) g (project-config)))
          configs (config-chain given)
          jar (detekt-jar)
          _ (s/progress "running detekt over" (count kotlin-files) "Kotlin files")
          result (run-detekt jar inputs configs)
          items (vec (findings (:xml result)))]
      (s/heading "Static analysis (detekt)")
      ;; Every config that shaped this report, in the order it was applied. The
      ;; baseline is named rather than assumed, because a finding that is absent
      ;; because a config removed it and a finding that is absent because the
      ;; code is clean read the same way in a count.
      (println (format "detekt %s   files: %d" detekt-version (count kotlin-files)))
      (println (format "Config: defaults + %s" (str/join " + " (map relative configs))))
      (when-not given
        (println "        (no project detekt.yml; write one to override the baseline)"))
      (println (format "Findings: %d" (count items)))
      (when (seq items)
        (println)
        (println "By rule:")
        (doseq [[rule n] (take top (tally items :rule))]
          (println (format "  %5d  %s" n rule)))
        (println)
        (println "By source set:")
        (doseq [[ss n] (tally items (comp source-set-of :file))]
          (println (format "  %5d  %s" n ss)))
        (println)
        ;; One example per rule. Listing raw findings lets a single noisy rule
        ;; such as MagicNumber crowd out every other kind of problem.
        (println "One example per rule:")
        (doseq [[rule group] (->> (group-by :rule items)
                                  (sort-by (comp - count second))
                                  (take top))
                :let [f (first (sort-by (juxt :file :line) group))]]
          (println (format "  %s  (%d occurrence%s)"
                           rule (count group) (if (= 1 (count group)) "" "s")))
          (println (format "      %s:%s  %s"
                           (relative (:file f)) (:line f) (:message f)))))
      (println)
      (println "Scope: every Kotlin source set in the worktree, iosMain included.")
      (println "Type resolution: off (no --classpath), so type-dependent rules were skipped.")
      (println "Not analysed: Swift. Use dry4kotlin for the Swift duplication pass.")
      (println (str "Report: " (:xml result)))
      ;; Findings are a list to judge, not a build failure.
      (System/exit 0))))

(apply -main *command-line-args*)
