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
  (->> (fs/glob (s/worktree-root) (str s/any-depth "build/reports/kover/*.xml"))
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

(defn add-totals [a b]
  {:missed (+ (:missed a) (:missed b))
   :covered (+ (:covered a) (:covered b))
   :total (+ (:total a) (:total b))})

(def zero-totals {:missed 0 :covered 0 :total 0})

(defn sum-totals [rows type]
  (reduce add-totals zero-totals (map #(counter-totals (:element %) type) rows)))

(defn- totals [rows]
  {:line (sum-totals rows "LINE")
   :branch (sum-totals rows "BRANCH")
   :classes (count rows)})

(defn classes-of
  "Every class in the report, tagged with why it is or is not the author's work.
  Coverage is summed from these rows rather than read off the report's own
  top-level counter, because that counter cannot be asked to leave anything out."
  [xml-path sources]
  (let [root (xml/parse-str (slurp xml-path))]
    (for [pkg (s/children root "package")
          cls (s/children pkg "class")
          :let [row {:package (get-in pkg [:attrs :name])
                     :class (get-in cls [:attrs :name])
                     :source (get-in cls [:attrs :sourcefilename])}]]
      (assoc row
             :element cls
             :generated (s/generated-class sources row)
             :composable? (s/declares-composable? sources (:package row) (:source row))))))

(defn summarize
  "Three questions, so three sets of numbers.

  Generated code is excluded from the headline because nobody wrote it and no
  test can reasonably cover it. The blended figure stays in the output so the
  exclusion is visible rather than quietly applied: on a Compose module the two
  differ by several times over.

  Within the author's own code, a file that declares @Composable is separated
  out. UI is covered by UI tests, which this toolchain does not run, so folding
  it in with the logic hides whichever of the two is worse."
  [xml-path sources]
  (let [rows (vec (classes-of xml-path sources))
        authored (remove :generated rows)
        generated (filter :generated rows)]
    {:path xml-path
     :authored (totals authored)
     :logic (totals (remove :composable? authored))
     :ui (totals (filter :composable? authored))
     :generated (assoc (totals generated)
                       :by-reason (frequencies (map :generated generated)))
     :all (totals rows)
     :complexity (sum-totals rows "COMPLEXITY")}))

(defn- tier-line [label {:keys [line branch]}]
  (println (format "  %-24s line %6.2f%%  branch %6.2f%%   (%d line%s)"
                   label (pct line) (pct branch)
                   (:total line) (if (= 1 (:total line)) "" "s"))))

(defn- report-lines [r]
  (println (str (fs/relativize (s/worktree-root) (:path r))))
  (tier-line "your code" (:authored r))
  ;; The split is only informative when both halves exist. A plain Kotlin
  ;; module with no Compose in it should read the way it always did.
  (when (and (pos? (:classes (:ui r))) (pos? (:classes (:logic r))))
    (tier-line "  plain Kotlin" (:logic r))
    (tier-line "  declares @Composable" (:ui r)))
  (when (pos? (:classes (:generated r)))
    (let [g (:generated r)]
      (println (format "  %-24s %d class%s, %d line%s excluded"
                       "generated" (:classes g) (if (= 1 (:classes g)) "" "es")
                       (:total (:line g)) (if (= 1 (:total (:line g))) "" "s")))
      (doseq [[reason n] (sort-by (comp - val) (:by-reason g))]
        (println (format "  %-24s   %3d  %s" "" n (s/generated-reasons reason reason))))
      ;; Printed because a number that excludes something has to say so, next to
      ;; the number that does not. Anyone comparing this run to an earlier one,
      ;; or to a CI gate configured against Kover's own total, needs both.
      (tier-line "every class" (:all r)))))

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
                (str "Looked for build/reports/kover/*.xml at any depth under "
                     (s/worktree-root))
                ""
                "Ensure the Kover plugin is applied to the module under test and that"
                "the XML report task is not disabled in the build script."))
      (s/heading "Coverage (kover)")
      (let [sources (s/hand-written-sources)
            summaries (map #(summarize % sources) reports)]
        (doseq [r summaries]
          (report-lines r)
          (when (zero? (:total (:complexity r)))
            (s/eprintln "  note: no COMPLEXITY counters in this report; crap4kotlin will"
                        "derive complexity from branch counters.")))
        (println)
        (println "'your code' is the headline: it counts only classes whose source file")
        (println "exists under a src directory, and never the lambda holders the Compose")
        (println "compiler synthesises. Generated code is code nobody wrote, and a")
        (println "percentage that includes it measures the toolchain, not the work.")
        (println)
        (println "Files that declare @Composable are reported apart because covering")
        (println "them needs a UI test, which no tool in this constitution runs. A low")
        (println "number there is a scope statement, not a licence to leave it untested.")
        (println)
        (println "Scope: commonMain via Android host tests, plus androidMain.")
        (println "Not covered: iosMain, Kotlin/Native, Swift. Kover cannot reach them.")
        (println (str "Reports: " (count reports)))))))

(apply -main *command-line-args*)
