#!/usr/bin/env bb
;; kover - coverage for Kotlin JVM and Android host tests.
;;
;; Wraps the Kover Gradle plugin so that the constitution can name one stable
;; command. Produces a JaCoCo-compatible XML report, which crap4kotlin consumes.
;;
;; Reach: commonMain (through Android host tests) and androidMain.
;; Kotlin/Native and JS targets are not supported by Kover. iosMain is not
;; covered, and that is a toolchain limit, not a defect.

(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/parent (fs/absolutize *file*))))
(require '[sfk.support :as s]
         '[clojure.string :as str]
         '[clojure.data.xml :as xml])

(def setup-snippet
  (str "plugins {\n"
       "    id(\"org.jetbrains.kotlinx.kover\") version \"0.9.8\"\n"
       "}\n"))

(def candidate-tasks ["koverXmlReport" "koverXmlReportDebug"])

(defn report-task []
  (or (first (filter s/has-task? candidate-tasks))
      (s/require-task! "koverXmlReport" "org.jetbrains.kotlinx.kover" setup-snippet)))

(defn find-reports []
  (->> (fs/glob (s/worktree-root) "**/build/reports/kover/*.xml")
       (map str)
       (sort-by #(- (fs/file-time->millis (fs/last-modified-time %))))
       vec))

(defn counter-of [element type]
  (->> (:content element)
       (filter #(and (map? %) (= :counter (:tag %))))
       (filter #(= type (get-in % [:attrs :type])))
       first))

(defn counter-totals [element type]
  (if-let [c (counter-of element type)]
    (let [missed (parse-long (or (get-in c [:attrs :missed]) "0"))
          covered (parse-long (or (get-in c [:attrs :covered]) "0"))]
      {:missed missed :covered covered :total (+ missed covered)})
    {:missed 0 :covered 0 :total 0}))

(defn pct [{:keys [covered total]}]
  (if (zero? total) 0.0 (* 100.0 (/ (double covered) total))))

(defn summarize [xml-path]
  (let [root (xml/parse-str (slurp xml-path))]
    {:path xml-path
     :name (get-in root [:attrs :name])
     :line (counter-totals root "LINE")
     :branch (counter-totals root "BRANCH")
     :instruction (counter-totals root "INSTRUCTION")
     :complexity (counter-totals root "COMPLEXITY")}))

(defn -main [& args]
  (let [{:keys [flags]} (s/parse-args args #{"--max-workers" "--workers"})
        report-only? (get flags "--report-only")]
    (when-not report-only?
      (let [task (report-task)]
        (s/progress "running" task)
        (s/gradle-or-die!
         [task "--console=plain"]
         (str "Coverage could not be collected.\n"
              "If the failure is a failing test, fix the test first: coverage over a\n"
              "red suite is not a measurement."))))
    (let [reports (find-reports)]
      (when (empty? reports)
        (s/die! "Kover produced no XML report."
                (str "Looked for **/build/reports/kover/*.xml under " (s/worktree-root))
                ""
                "Ensure the Kover plugin is applied to the module under test and that"
                "the XML report task is not disabled in the build script."))
      (s/heading "Coverage (kover)")
      (doseq [r (map summarize reports)]
        (println (format "%-46s line %6.2f%%  branch %6.2f%%"
                         (str (fs/relativize (s/worktree-root) (:path r)))
                         (pct (:line r))
                         (pct (:branch r))))
        (when (zero? (:total (:complexity r)))
          (s/eprintln "  note: no COMPLEXITY counters in this report; crap4kotlin will"
                      "derive complexity from branch counters.")))
      (println)
      (println "Scope: commonMain via Android host tests, plus androidMain.")
      (println "Not covered: iosMain, Kotlin/Native, Swift. Kover cannot reach them.")
      (println (str "Reports: " (count reports))))))

(apply -main *command-line-args*)
