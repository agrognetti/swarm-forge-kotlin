#!/usr/bin/env bb
;; mutate4kotlin - code mutation testing for Kotlin.
;;
;; Mutates production bytecode and reports which mutants the test suite failed
;; to kill. A surviving mutant is a test-suite defect: the code changed and no
;; test noticed.
;;
;; This is NOT gherkin-mutator. gherkin-mutator mutates example values in an
;; acceptance specification to test the acceptance tests. This tool mutates
;; production code to test the unit tests. Both are required; neither replaces
;; the other.
;;
;; Engine: PIT (pitest) through the Gradle plugin. PIT operates on JVM
;; bytecode, so its reach is the same as Kover's: commonMain through Android
;; host tests, plus androidMain. iosMain, Kotlin/Native and Swift cannot be
;; mutated by any maintained tool, and the constitution says to state that
;; rather than estimate it.
;;
;; Differential runs use PIT's own incremental analysis rather than a
;; reimplementation. The history file lives in .mutate4kotlin/ so it is tracked
;; by git and therefore survives a handoff into another agent's worktree.

(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/parent (fs/absolutize *file*))))
(require '[sfk.support :as s]
         '[clojure.string :as str]
         '[clojure.data.xml :as xml]
         '[cheshire.core :as json])

(def pitest-plugin-version "1.19.0")
(def pitest-core-version "1.25.9")

(def state-dirname ".mutate4kotlin")

(defn state-file [name]
  (str (fs/path (s/worktree-root) state-dirname name)))

(def manifest-path (delay (state-file "manifest.json")))
(def exclusions-path (delay (state-file "exclusions.txt")))

;; --------------------------------------------------------------- setup advice

(def setup-snippet
  (str
   "// build.gradle.kts of the module whose code should be mutated\n"
   "plugins {\n"
   "    id(\"info.solidsoft.pitest\") version \"" pitest-plugin-version "\"\n"
   "}\n"
   "\n"
   "val mutationExclusions = rootProject.file(\"" state-dirname "/exclusions.txt\")\n"
   "    .takeIf { it.exists() }\n"
   "    ?.readLines()\n"
   "    ?.map { it.substringBefore(\"#\").trim() }\n"
   "    ?.filter { it.isNotEmpty() }\n"
   "    ?: emptyList()\n"
   "\n"
   "pitest {\n"
   "    pitestVersion = \"" pitest-core-version "\"\n"
   "    junit5PluginVersion = \"1.2.3\"        // required for kotlin.test on JUnit 5\n"
   "    targetClasses = setOf(\"<your.base.package>.*\")\n"
   "    excludedClasses = mutationExclusions.toSet()\n"
   "    outputFormats = setOf(\"XML\", \"HTML\")\n"
   "    timestampedReports = false\n"
   "    threads = 4\n"
   "\n"
   "    // Differential runs. Tracked location, so the history survives a\n"
   "    // handoff into another agent's worktree.\n"
   "    enableDefaultIncrementalAnalysis = false\n"
   "    historyInputLocation = rootProject.file(\"" state-dirname "/history.txt\")\n"
   "    historyOutputLocation = rootProject.file(\"" state-dirname "/history.txt\")\n"
   "}\n"))

(def kmp-note
  ["This project is Kotlin Multiplatform with Android and iOS targets, so the"
   "wiring needs one extra decision that the plugin cannot make for you."
   ""
   "PIT mutates JVM bytecode and needs a JVM test task. gradle-pitest-plugin"
   "defaults mainSourceSets to sourceSets.main and testSourceSets to"
   "sourceSets.test, which a KMP module does not have. Point them at the"
   "compilation that produces JVM classfiles - normally the Android unit-test"
   "compilation - using mainSourceSets and testSourceSets."
   ""
   "There is no maintained Android-specific pitest plugin. The plugin that used"
   "to fill that role is gone, so this wiring is project-specific by necessity."
   ""
   "If your role does not own the build configuration, do not guess. Ask with:"
   "  pack_dashboard_request.sh clarify"])

(defn require-pitest! []
  (when-not (s/has-task? "pitest")
    (apply s/die!
           (concat
            ["Gradle task 'pitest' does not exist in this project."
             "The 'info.solidsoft.pitest' plugin is not applied."
             ""
             "Add this to the module's build script:"
             ""
             setup-snippet]
            kmp-note
            [""
             "Do not substitute an estimate for the missing tool, and do not"
             "write a project-local mutation proxy."]))))

;; ------------------------------------------------------------------ exclusions

(defn parse-exclusions
  "Each line is a pitest class glob followed by '# reason'. The constitution
  requires a reason for every exclusion, so a line without one is an error
  here rather than a silently accepted suppression."
  []
  (let [path @exclusions-path]
    (if-not (fs/exists? path)
      []
      (let [lines (str/split-lines (slurp path))]
        (vec
         (for [[n raw] (map-indexed vector lines)
               :let [line (str/trim raw)]
               :when (and (seq line) (not (str/starts-with? line "#")))]
           (let [[pattern reason] (str/split line #"#" 2)
                 pattern (str/trim pattern)
                 reason (some-> reason str/trim not-empty)]
             (when-not reason
               (s/die! (str "Exclusion without a reason at " path ":" (inc n))
                       (str "  " line)
                       ""
                       "Every mutation exclusion must carry a one-line reason:"
                       "  com.example.generated.*   # generated code, no author to hold responsible"
                       ""
                       "An exclusion with no reason is an unexplained hole in the"
                       "mutation gate. Add the reason or remove the line."))
             {:pattern pattern :reason reason})))))))

;; -------------------------------------------------------------------- manifest

(defn read-manifest []
  (let [path @manifest-path]
    (if (fs/exists? path)
      (try (json/parse-string (slurp path) true)
           (catch Exception _
             (s/die! (str "Cannot parse " path)
                     "The mutation manifest is corrupt. Delete it and re-run with"
                     "--update-manifest to rebuild it from a full run.")))
      {:version 1 :classes {}})))

(defn write-manifest! [manifest]
  (let [path @manifest-path]
    (fs/create-dirs (fs/parent path))
    ;; No timestamps on purpose: this file is tracked, and a timestamp would
    ;; dirty it on every run and pollute every handoff commit.
    (spit path (str (json/generate-string manifest {:pretty true}) "\n"))
    path))

;; ---------------------------------------------------------------- pit reports

(defn find-reports []
  (->> (fs/glob (s/worktree-root) "**/build/reports/pitest/**/mutations.xml")
       (concat (fs/glob (s/worktree-root) "**/build/reports/pitest/mutations.xml"))
       (map str)
       distinct
       (sort-by #(- (fs/file-time->millis (fs/last-modified-time %))))
       vec))

(defn text-of [element tag]
  (some-> (first (s/children element tag)) :content first str str/trim))

(defn mutations [xml-path]
  (let [root (xml/parse-str (slurp xml-path))]
    (for [m (s/children root "mutation")]
      {:status (or (s/attr m :status) "UNKNOWN")
       :detected (= "true" (str (s/attr m :detected)))
       :source (text-of m "sourceFile")
       :class (text-of m "mutatedClass")
       :method (text-of m "mutatedMethod")
       :line (text-of m "lineNumber")
       :mutator (last (str/split (or (text-of m "mutator") "unknown") #"\."))
       :description (text-of m "description")})))

;; PIT counts these statuses as detected. NO_COVERAGE means the mutated line
;; never ran at all, which is the most actionable finding of the three.
(def killed-statuses #{"KILLED" "TIMED_OUT" "MEMORY_ERROR" "RUN_ERROR"})
(def survived-statuses #{"SURVIVED" "NO_COVERAGE"})

(defn short-class [fqcn]
  (last (str/split (or fqcn "?") #"\.")))

(defn summarize [ms]
  (let [total (count ms)
        killed (count (filter #(killed-statuses (:status %)) ms))
        survived (count (filter #(= "SURVIVED" (:status %)) ms))
        no-cov (count (filter #(= "NO_COVERAGE" (:status %)) ms))
        non-viable (count (filter #(= "NON_VIABLE" (:status %)) ms))
        covered (- total no-cov)]
    {:total total :killed killed :survived survived :no-coverage no-cov
     :non-viable non-viable
     ;; Two different questions, so both are reported and both are labelled.
     :mutation-coverage (if (zero? total) 0.0 (* 100.0 (/ (double killed) total)))
     :test-strength (if (zero? covered) 0.0 (* 100.0 (/ (double killed) covered)))}))

(defn per-class [ms]
  (->> ms
       (group-by :class)
       (map (fn [[cls group]]
              [cls {:survived (count (filter #(= "SURVIVED" (:status %)) group))
                    :noCoverage (count (filter #(= "NO_COVERAGE" (:status %)) group))
                    :killed (count (filter #(killed-statuses (:status %)) group))}]))
       (into {})))

;; ----------------------------------------------------------------- reporting

(defn print-survivors [ms top]
  (let [survivors (->> ms
                       (filter #(survived-statuses (:status %)))
                       (sort-by (juxt (fn [m] (if (= "NO_COVERAGE" (:status m)) 0 1))
                                      :class
                                      #(or (some-> (:line %) parse-long) 0))))]
    (if (empty? survivors)
      (println "No surviving mutants. Every mutation was detected by a test.")
      (do
        (println (format "%d surviving mutant(s). Each one is a test the suite is missing."
                         (count survivors)))
        (println)
        (doseq [m (take top survivors)]
          (println (format "  %-12s %s.%s (%s:%s)"
                           (:status m) (short-class (:class m)) (:method m)
                           (:source m) (:line m)))
          (println (format "        %s [%s]" (:description m) (:mutator m))))
        (when (> (count survivors) top)
          (println)
          (println (format "... %d more. Use --top %d to see them all."
                           (- (count survivors) top) (count survivors))))))
    survivors))

(defn print-regressions [current previous]
  (let [regressed (for [[cls now] current
                        :let [before (get previous (keyword cls))]
                        :when before
                        :let [now-bad (+ (:survived now) (:noCoverage now))
                              was-bad (+ (or (:survived before) 0)
                                         (or (:noCoverage before) 0))]
                        :when (> now-bad was-bad)]
                    [cls was-bad now-bad])]
    (when (seq regressed)
      (println)
      (println "Regressions since the recorded manifest:")
      (doseq [[cls was now] (sort regressed)]
        (println (format "  %s: %d -> %d unkilled mutant(s)" (short-class cls) was now))))
    (count regressed)))

;; --------------------------------------------------------------------- scan

(defn do-scan [exclusions]
  (s/heading "Mutation setup (mutate4kotlin --scan)")
  (println (format "pitest plugin %s, PIT %s" pitest-plugin-version pitest-core-version))
  (println (format "pitest task present: %s" (if (s/has-task? "pitest") "yes" "NO")))
  (println (format "history file: %s (%s)"
                   (state-file "history.txt")
                   (if (fs/exists? (state-file "history.txt"))
                     "present, differential run" "absent, first run is full")))
  (println (format "exclusions: %d" (count exclusions)))
  (doseq [e exclusions]
    (println (format "    %-40s %s" (:pattern e) (:reason e))))
  (let [manifest (read-manifest)
        classes (:classes manifest)]
    (println (format "manifest: %d class(es) recorded at %s"
                     (count classes) @manifest-path))
    (let [bad (for [[cls v] classes
                    :let [n (+ (or (:survived v) 0) (or (:noCoverage v) 0))]
                    :when (pos? n)]
                [cls n])]
      (when (seq bad)
        (println "Classes with unkilled mutants at last record:")
        (doseq [[cls n] (sort-by (comp - second) bad)]
          (println (format "    %-50s %d" (name cls) n))))))
  (println)
  (println "Scan performs no build and produces no measurement.")
  (System/exit 0))

;; --------------------------------------------------------------------- main

(defn -main [& args]
  (let [{:keys [flags]} (s/parse-args args #{"--top" "--max-workers" "--workers"})
        top (try (Integer/parseInt (str (get flags "--top" "25"))) (catch Exception _ 25))
        workers (s/worker-limit flags)
        scan? (boolean (get flags "--scan"))
        update? (boolean (get flags "--update-manifest"))
        exclusions (parse-exclusions)]
    (when scan?
      (do-scan exclusions))
    (require-pitest!)
    (fs/create-dirs (fs/path (s/worktree-root) state-dirname))
    (s/progress "running pitest with" workers "gradle worker(s)")
    (s/gradle-or-die!
     ["pitest" "--console=plain" "--max-workers" (str workers)]
     (str "Mutation testing could not complete.\n"
          "If the failure is a failing test, fix the test first: mutation\n"
          "results over a red suite mean nothing.\n"
          "If the failure is 'Minion exited abnormally', the JUnit 5 plugin\n"
          "version does not match the project's JUnit version. See the\n"
          "junit5PluginVersion line in the setup snippet."))
    (let [reports (find-reports)]
      (when (empty? reports)
        (s/die! "PIT ran but produced no mutations.xml."
                (str "Looked for **/build/reports/pitest/**/mutations.xml under "
                     (s/worktree-root))
                ""
                "Add \"XML\" to outputFormats in the pitest block. The HTML report"
                "alone is not machine readable."))
      (let [ms (vec (mapcat mutations reports))
            stats (summarize ms)
            current (per-class ms)
            manifest (read-manifest)]
        (s/heading "Mutation testing (mutate4kotlin)")
        (println (format "PIT %s   mutants: %d   reports: %d"
                         pitest-core-version (:total stats) (count reports)))
        (println (format "killed %d   survived %d   no-coverage %d   non-viable %d"
                         (:killed stats) (:survived stats)
                         (:no-coverage stats) (:non-viable stats)))
        (println (format "mutation coverage %.1f%%  (killed / all mutants)"
                         (:mutation-coverage stats)))
        (println (format "test strength     %.1f%%  (killed / mutants on covered lines)"
                         (:test-strength stats)))
        (when (seq exclusions)
          (println (format "exclusions in effect: %d (see %s)"
                           (count exclusions) @exclusions-path)))
        (println)
        (print-survivors ms top)
        (print-regressions current (:classes manifest))
        (when update?
          (let [path (write-manifest! (assoc manifest :classes current))]
            (println)
            (println (str "Manifest updated: " path))
            (println "Commit it with your work so the next role inherits the record.")))
        (println)
        (println "Scope: commonMain via Android host tests, plus androidMain.")
        (println "Not mutated: iosMain, Kotlin/Native, Swift. PIT works on JVM")
        (println "bytecode and no maintained tool mutates Kotlin/Native.")
        (println "Property-based tests are the adversarial check that does reach")
        (println "iosMain; they are not a substitute for this measurement.")
        ;; Survivors are work to do, not a broken tool.
        (System/exit 0)))))

(apply -main *command-line-args*)
