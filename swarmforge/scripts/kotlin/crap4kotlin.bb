#!/usr/bin/env bb
;; crap4kotlin - Change Risk Anti-Patterns metric for Kotlin.
;;
;;     CRAP(m) = CC(m)^2 * (1 - coverage(m))^3 + CC(m)
;;
;; A method that is complex AND poorly covered scores very high. A complex
;; method that is fully covered scores exactly CC. The result is a prioritized
;; risk list, not a pass/fail threshold.
;;
;; Input is the Kover XML report, which is JaCoCo-compatible and carries both
;; per-method cyclomatic complexity and per-method line coverage. Reach is
;; therefore identical to kover: commonMain via Android host tests, plus
;; androidMain. iosMain and Swift are out of reach.

(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/parent (fs/absolutize *file*))))
(require '[sfk.support :as s]
         '[clojure.string :as str]
         '[clojure.data.xml :as xml])

;; Members the Kotlin compiler generates. Their complexity belongs to the
;; compiler, not to the author, so scoring them only adds noise.
(def generated-members
  #{"<clinit>" "equals" "hashCode" "toString" "copy" "copy$default"
    "writeToParcel" "describeContents" "values" "valueOf" "entries"})

(defn generated-member? [name]
  (or (contains? generated-members name)
      (re-matches #"component\d+" name)
      (str/starts-with? name "access$")
      (str/includes? name "$lambda")
      (str/includes? name "$default")
      (str/includes? name "$annotations")
      (str/ends-with? name "$kotlin_stdlib")))

(defn children [element tag]
  (->> (:content element)
       (filter #(and (map? %) (= tag (:tag %))))))

(defn counter [element type]
  (when-let [c (first (filter #(= type (get-in % [:attrs :type]))
                              (children element :counter)))]
    (let [missed (parse-long (or (get-in c [:attrs :missed]) "0"))
          covered (parse-long (or (get-in c [:attrs :covered]) "0"))]
      {:missed missed :covered covered :total (+ missed covered)})))

(defn complexity-of
  "JaCoCo's COMPLEXITY counter is cyclomatic complexity. When a report omits it,
  fall back to branches/2 + 1 and mark the row so the number is not mistaken
  for a direct measurement."
  [method]
  (if-let [c (counter method "COMPLEXITY")]
    (when (pos? (:total c)) {:cc (:total c) :derived? false})
    (when-let [b (counter method "BRANCH")]
      {:cc (inc (quot (:total b) 2)) :derived? true})))

(defn coverage-of [method]
  (let [line (counter method "LINE")
        inst (counter method "INSTRUCTION")
        c (or (when (and line (pos? (:total line))) line)
              (when (and inst (pos? (:total inst))) inst))]
    (if c (/ (double (:covered c)) (:total c)) 0.0)))

(defn crap [cc coverage]
  (+ (* cc cc (Math/pow (- 1.0 coverage) 3)) cc))

(defn methods-of [xml-path]
  (let [root (xml/parse-str (slurp xml-path))]
    (for [pkg (children root :package)
          cls (children pkg :class)
          mth (children cls :method)
          :let [mname (get-in mth [:attrs :name])
                cinfo (complexity-of mth)]
          :when cinfo]
      (let [cov (coverage-of mth)]
        {:package (get-in pkg [:attrs :name])
         :class (get-in cls [:attrs :name])
         :source (get-in cls [:attrs :sourcefilename])
         :method mname
         :line (get-in mth [:attrs :line])
         :generated? (generated-member? mname)
         :cc (:cc cinfo)
         :derived? (:derived? cinfo)
         :coverage cov
         :crap (crap (:cc cinfo) cov)}))))

(defn find-reports []
  (->> (fs/glob (s/worktree-root) "**/build/reports/kover/*.xml")
       (map str)
       sort
       vec))

(defn short-class [row]
  (let [c (:class row)]
    (last (str/split (or c "?") #"/"))))

(defn print-rows [rows]
  (println (format "%9s %5s %9s  %s" "CRAP" "CC" "COVERAGE" "METHOD"))
  (println (s/rule))
  (doseq [r rows]
    (println (format "%9.2f %5d %8.1f%%  %s.%s%s%s"
                     (:crap r) (:cc r) (* 100.0 (:coverage r))
                     (short-class r) (:method r)
                     (if (:line r) (str " (" (:source r) ":" (:line r) ")") "")
                     (if (:derived? r) " [cc derived]" "")))))

(defn -main [& args]
  (let [{:keys [flags]} (s/parse-args args #{"--top" "--threshold" "--max-workers" "--workers"})
        top (try (Integer/parseInt (str (get flags "--top" "25"))) (catch Exception _ 25))
        threshold (try (Double/parseDouble (str (get flags "--threshold" "0")))
                       (catch Exception _ 0.0))
        include-generated? (boolean (get flags "--include-generated"))
        reports (find-reports)]
    (when (empty? reports)
      (s/die! "No Kover XML report found."
              "crap4kotlin reads coverage produced by kover; it does not run tests itself."
              ""
              "Run this first:"
              "  kover"
              ""
              "Then run crap4kotlin again."))
    (let [all (vec (mapcat methods-of reports))
          filtered (if include-generated? all (remove :generated? all))
          hidden (- (count all) (count filtered))
          ranked (->> filtered
                      (filter #(>= (:crap %) threshold))
                      (sort-by (juxt (comp - :crap) :class :method))
                      vec)]
      (when (empty? all)
        (s/die! "The Kover report contains no methods with complexity data."
                "This usually means no tests ran, so no classes were instrumented."
                "Run the module's tests, then kover, then crap4kotlin."))
      (s/heading "CRAP risk list (crap4kotlin)")
      (println "CRAP = CC^2 * (1 - coverage)^3 + CC")
      (println (format "Methods scored: %d   compiler-generated hidden: %d   reports: %d"
                       (count filtered) hidden (count reports)))
      (println)
      (print-rows (take top ranked))
      (when (> (count ranked) top)
        (println)
        (println (format "... %d more at or above the threshold. Use --top %d to see them all."
                         (- (count ranked) top) (count ranked))))
      (println)
      (let [worst (first ranked)]
        (println (format "Highest CRAP: %.2f at %s.%s"
                         (:crap worst) (short-class worst) (:method worst))))
      (println "Scope: commonMain via Android host tests, plus androidMain.")
      (println "Not scored: iosMain, Kotlin/Native, Swift. Kover cannot reach them."))))

(apply -main *command-line-args*)
