#!/usr/bin/env bb
;; aps-kotlin - the Acceptance Pipeline Specification adapter for Kotlin.
;;
;; Three jobs, one tool, because they must agree on names to work at all:
;;
;;   generate    JSON IR -> executable Kotlin acceptance entry points + metadata
;;   acceptance  run those entry points through Gradle
;;   worker      the persistent runner adapter gherkin-mutator drives
;;
;; The entry points it writes carry no step logic and no example values. They
;; name a scenario and an example row and hand over to the runtime, which loads
;; the IR at run time. That is what lets gherkin-mutator mutate an example value
;; and re-run the same compiled tests without regenerating anything.
;;
;; This is NOT mutate4kotlin. mutate4kotlin mutates production bytecode to test
;; the unit tests. gherkin-mutator, which this tool serves, mutates example
;; values in a specification to test the acceptance tests.
;;
;; Deliberately not Cucumber. A Cucumber runner parses the .feature file at run
;; time, and the specification forbids that for generated entry points: the IR is
;; the single source the whole pipeline agrees on, and a second parser would be a
;; second opinion. Cucumber remains the right tool for the separate UI-level
;; acceptance tier, which is not mutated.

(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/parent (fs/absolutize *file*))))
(require '[sfk.support :as s]
         '[babashka.process :as p]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[cheshire.core :as json])

(import '[java.util.concurrent TimeUnit])

(def default-package "acceptance")
(def generated-package "acceptance.generated")

;; JUnit Platform 1.x by default: it runs on Java 8+ and its bundled vintage
;; engine executes the JUnit 4 tests that kotlin.test produces on Android unit
;; tests. A project that has moved to Jupiter 6 gets the 6.x launcher instead,
;; because a 1.x launcher cannot run a 6.x engine.
;;
;; Both claims are checked against the jars, not against a release note, because
;; both are what a bump could quietly take away: 1.14.4 still shades in
;; VintageTestEngine and JUnit 4's own JUnitCore, and its ConsoleLauncher is
;; still class file major 52, which is the Java 8 floor.
(def launcher-1x "1.14.4")
(def launcher-6x "6.1.3")

(def ^:private path-separator (System/getProperty "path.separator"))

(defn die-usage!
  "Exit 2. The specification reserves that code for command-line misuse, and
  gherkin-mutator distinguishes it from a real failure."
  [& lines]
  (binding [*out* *err*]
    (doseq [line lines] (println line)))
  (System/exit 2))

;; ------------------------------------------------------------------ naming
;;
;; Every name below must be a pure function of the IR. Generated output has to
;; be byte-identical for a fixed IR, or the implementation hash changes on every
;; run and differential mutation never reuses anything.

(defn- pascal [word]
  (if (str/blank? word) word (str (str/upper-case (subs word 0 1)) (subs word 1))))

(defn class-name
  "Feature name -> Kotlin class name."
  [feature-name]
  (let [words (->> (str/split (or feature-name "") #"[^A-Za-z0-9]+")
                   (remove str/blank?)
                   (map pascal))
        joined (apply str words)
        safe (cond
               (str/blank? joined) "Unnamed"
               (Character/isDigit (first joined)) (str "Feature" joined)
               :else joined)]
    (str safe "AcceptanceTest")))

(defn method-name
  "One Kotlin function per scenario execution. Underscores only: names with
  spaces are legal on the JVM but not in a dexed Android test, and the same
  generated file should stay usable if the acceptance tier moves onto a device."
  [scenario-index scenario-name example-index]
  (let [cleaned (-> (or scenario-name "")
                    (str/replace #"[^A-Za-z0-9]+" "_")
                    (str/replace #"^_+" "")
                    (str/replace #"_+$" ""))
        cleaned (if (str/blank? cleaned) "scenario" cleaned)
        cleaned (subs cleaned 0 (min 70 (count cleaned)))]
    (format "scenario_%02d_%s_example_%d" (inc scenario-index) cleaned (inc example-index))))

(defn metadata-slug
  "Strict mapping from the specification: lowercase, every run of non-alphanumeric
  characters becomes one hyphen, trim hyphens, append .json."
  [feature-path]
  (let [slug (-> (str/lower-case (str feature-path))
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+" "")
                 (str/replace #"-+$" ""))]
    (str (if (str/blank? slug) "feature" slug) ".json")))

(defn kotlin-string
  "Escape for a Kotlin double-quoted literal. The dollar sign matters: an
  unescaped one turns a scenario name into a template expression."
  [text]
  (-> (str text)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "$" "\\$")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn shell-quote
  "Quote a path for a command line the reader is meant to paste. Kotlin projects
  have feature files with spaces in their names, and an unquoted one turns a
  copyable command into two broken arguments."
  [path]
  (let [text (str path)]
    (if (re-find #"[^A-Za-z0-9._/@:+-]" text)
      (str "'" (str/replace text "'" "'\\''") "'")
      text)))

(defn relative [path]
  (let [root (s/worktree-root)]
    (try
      (let [rel (str (fs/relativize root (fs/absolutize path)))]
        (if (str/starts-with? rel "..") (str (fs/absolutize path)) rel))
      (catch Exception _ (str path)))))

;; --------------------------------------------------------------------- the IR

(defn read-ir [path]
  (when-not (fs/exists? path)
    (s/die! (str "No feature IR at " path)
            "Produce it first:"
            (str "  bb gherkin-parser <feature-file> " path)))
  (let [parsed (try (json/parse-string (slurp (str path)) true)
                    (catch Exception e
                      (s/die! (str "Cannot parse " path " as JSON.")
                              (.getMessage e)
                              ""
                              "The IR is written by gherkin-parser. Re-run the parser rather"
                              "than editing the IR by hand.")))]
    (when-not (map? parsed)
      (s/die! (str path " does not hold a JSON object.")))
    (when-not (string? (:name parsed))
      (s/die! (str path " has no string 'name'.")
              "A feature IR without a feature name is not usable. Re-run gherkin-parser."))
    (when-not (sequential? (:scenarios parsed))
      (s/die! (str path " has no 'scenarios' array.")))
    parsed))

(defn executions
  "Expand the IR the way the runtime will: one execution per example row, one
  execution for a scenario with no examples."
  [ir]
  (vec
   (for [[scenario-index scenario] (map-indexed vector (:scenarios ir))
         :let [rows (:examples scenario)
               count-of (max 1 (count rows))]
         example-index (range count-of)]
     {:scenario-index scenario-index
      :scenario-name (or (:name scenario) "")
      :example-index example-index
      :mutable (boolean (seq rows))
      :method (method-name scenario-index (:name scenario) example-index)})))

;; ------------------------------------------------------- feature file lookup

(defn feature-files []
  (let [root (s/worktree-root)]
    ;; `**.feature` carries no leading slash, so unlike `**/segment` it does
    ;; match at the worktree root. The exclusion list is the shared one.
    (->> (fs/glob root "**.feature")
         (remove #(s/under-ignored-dir? root %))
         (map str)
         sort
         vec)))

(defn- declared-feature-name [path]
  (some->> (str/split-lines (slurp path))
           (map str/trim)
           (some (fn [line] (when (str/starts-with? line "Feature:") line)))
           (#(str/trim (subs % (count "Feature:"))))))

(defn find-feature-path
  "The metadata must record the source feature path, but the specification's
  generator command takes only the IR. So the feature is located by matching the
  IR's feature name against the Feature: line of each .feature file. --feature
  overrides the search."
  [ir given]
  (if (string? given)
    (do (when-not (fs/exists? given)
          (s/die! (str "--feature " given " does not exist.")))
        (relative given))
    (let [wanted (:name ir)
          matches (filter #(= wanted (declared-feature-name %)) (feature-files))]
      (case (count matches)
        1 (relative (first matches))
        0 (s/die! (str "No .feature file in this worktree declares 'Feature: " wanted "'.")
                  ""
                  "The metadata must record which feature the generated tests came"
                  "from, and guessing that would make the mutator's identity checks"
                  "meaningless. Name it explicitly:"
                  (str "  aps-kotlin generate <ir> <out-dir> --feature <path>"))
        (s/die! (str (count matches) " .feature files declare 'Feature: " wanted "':")
                (str "  " (str/join "\n  " (map relative matches)))
                ""
                "Two features with the same name cannot share generated metadata."
                "Rename one, or select with --feature <path>.")))))

;; ------------------------------------------------------------------- generate

(defn render-entry-points
  [{:keys [package feature-name feature-path ir-path class-of executions]}]
  (str
   "// Generated by aps-kotlin. DO NOT EDIT.\n"
   "//\n"
   "// Feature : " feature-name "\n"
   "// Source  : " feature-path "\n"
   "// IR      : " ir-path "\n"
   "//\n"
   "// Regenerate with:\n"
   "//   aps-kotlin generate " (shell-quote ir-path) " <this directory>\n"
   "//\n"
   "// Each function below is one scenario execution. No step logic and no\n"
   "// example value appears here on purpose: gherkin-mutator changes values in\n"
   "// the IR and re-runs this same compiled file, so behaviour lives in\n"
   "// " package ".ApsRuntime and " package ".ApsStepHandlers instead.\n"
   "\n"
   "package " generated-package "\n"
   "\n"
   "import " package ".ApsRuntime\n"
   "import kotlin.test.Test\n"
   "\n"
   "class " class-of " {\n"
   "\n"
   "    private val featureIr = ApsRuntime.irPath(\"" (kotlin-string ir-path) "\")\n"
   (apply str
          (for [e executions]
            (str "\n"
                 "    @Test\n"
                 "    fun " (:method e) "() =\n"
                 "        ApsRuntime.execute(\n"
                 "            featureIr,\n"
                 "            scenarioIndex = " (:scenario-index e) ",\n"
                 "            exampleIndex = " (:example-index e) ",\n"
                 "            scenarioName = \"" (kotlin-string (:scenario-name e)) "\"\n"
                 "        )\n")))
   "}\n"))

(defn implementation-hash
  "sha256 over the generated acceptance entry points and nothing else. The
  runtime, the handlers, the application and the metadata itself are excluded,
  because the mutator uses this hash to decide whether recorded results still
  apply to the generated tests."
  [files]
  (s/sha256
   (apply str
          (for [f (sort-by relative files)]
            (str (relative f) "\n" (s/file-hash f) "\n")))))

(defn owner-of
  "Which feature a generated file was written for, read back from its banner."
  [path]
  (when (fs/exists? path)
    (some->> (str/split-lines (slurp (str path)))
             (take 12)
             (some (fn [line] (when (str/starts-with? line "// Source  : ") line)))
             (#(str/trim (subs % (count "// Source  : ")))))))

(defn do-generate [positional flags]
  (when-not (= 2 (count positional))
    (die-usage! "usage: aps-kotlin generate <json-ir> <generated-test-output> [--feature <path>]"
                ""
                "Exactly two positional arguments, as the specification requires."))
  (let [[ir-arg out-arg] positional
        package (or (when (string? (get flags "--package")) (get flags "--package")) default-package)
        ir (read-ir ir-arg)
        ir-path (relative ir-arg)
        feature-path (find-feature-path ir (get flags "--feature"))
        class-of (class-name (:name ir))
        execs (executions ir)
        out-dir (str (fs/absolutize out-arg))
        target (str (fs/path out-dir (str class-of ".kt")))]

    (when (empty? execs)
      (s/die! (str ir-path " has no scenarios, so there is nothing to execute.")
              "An empty acceptance suite reports success without asserting anything."
              "Write a scenario, or stop treating this feature as acceptance-tested."))

    (let [existing (owner-of target)]
      (when (and existing (not= existing feature-path))
        (s/die! (str "Refusing to overwrite " (relative target))
                (str "It was generated for " existing ", not " feature-path ".")
                ""
                "Two features whose names collapse to the same Kotlin class name"
                (str "('" class-of "') would overwrite each other's tests. Rename one feature."))))

    (fs/create-dirs out-dir)
    (fs/create-dirs (fs/path out-dir "metadata"))
    (spit target (render-entry-points {:package package
                                       :feature-name (:name ir)
                                       :feature-path feature-path
                                       :ir-path ir-path
                                       :class-of class-of
                                       :executions execs}))

    (let [generated [target]
          metadata-path (str (fs/path out-dir "metadata" (metadata-slug feature-path)))
          metadata (array-map
                    :schema_version 1
                    :feature_path feature-path
                    :ir_path ir-path
                    :implementation_hash (str "sha256:" (implementation-hash generated))
                    :hash_scope "generated_files"
                    :generated_files (mapv relative generated))]
      (spit metadata-path (str (json/generate-string metadata {:pretty true}) "\n"))

      (s/heading "Acceptance entry points (aps-kotlin generate)")
      (println (format "Feature   : %s" (:name ir)))
      (println (format "Source    : %s" feature-path))
      (println (format "IR        : %s" ir-path))
      (println (format "Class     : %s.%s" generated-package class-of))
      (println (format "Executions: %d across %d scenario(s)"
                       (count execs) (count (:scenarios ir))))
      (let [immutable (remove :mutable execs)]
        (when (seq immutable)
          (println (format "            %d execution(s) have no Examples table and cannot be mutated:"
                           (count immutable)))
          (doseq [e immutable]
            (println (format "              %s" (:scenario-name e))))))
      (println (format "Written   : %s" (relative target)))
      (println (format "Metadata  : %s" (relative metadata-path)))
      (println (format "Hash      : %s" (:implementation_hash metadata)))
      (println)
      (println "Step behaviour comes from the runtime and the project handlers. A step")
      (println "with no handler fails; that is the point.")
      (System/exit 0))))

;; ------------------------------------------------------------------- scaffold

(def scaffold-files ["ApsJson.kt" "ApsRuntime.kt" "ApsStepHandlers.kt"])

(def ^:private test-source-set-preference
  "The JVM test source sets, best first. Two spell the Android host tests because
  the name depends on which Android plugin the module applies, and no module has
  both: `androidHostTest` under `com.android.kotlin.multiplatform.library`, and
  `androidUnitTest` under `com.android.library` or `com.android.application`. AGP
  publishes no Kotlin Multiplatform variant of the application plugin, so the
  older name is not the older toolchain - a current build that produces the APK
  still uses it."
  ["androidHostTest" "androidUnitTest" "jvmTest" "test"])

(defn test-source-root
  "The best JVM test source set that exists in this worktree, or nil.

  A query, so that `scan` can report the absence instead of exiting on it. The
  Android host tests come first because that is the source set an Android and iOS
  KMP module has, and the runtime reads files from disk, which `commonTest` cannot
  do without an expect/actual pair."
  ([] (test-source-root nil))
  ([given]
   (if (string? given)
     (str (fs/absolutize given))
     ;; `*/src/...` would anchor the search to exactly one level down, which
     ;; misses a single-module project whose source sets sit at the worktree root
     ;; and misses a module nested under a grouping directory.
     (let [root (s/worktree-root)
           existing (for [set-name test-source-set-preference
                          dir (s/glob-sources root (str "src/" set-name "/kotlin"))]
                      {:rank (.indexOf test-source-set-preference set-name) :dir (str dir)})]
       (:dir (first (sort-by (juxt :rank :dir) existing)))))))

(defn find-test-source-root
  "Where the runtime belongs, or a refusal naming the two candidates.

  The source set must already exist. There is deliberately no fallback that
  invents one: which of the two Android spellings Gradle compiles is decided by a
  plugin this tool would have to ask Gradle about, and choosing wrong fails
  silently. Gradle compiles nothing in the directory, so the scaffold succeeds,
  the generated entrypoints land beside it, the acceptance suite never runs, and
  `gherkin-mutator` is handed a --generated-dir of files nobody compiles - a
  pipeline that is green because it measured nothing. Refusing costs one message."
  [given]
  (or (test-source-root given)
      (let [root (s/worktree-root)
            module (->> (s/glob-sources root "src/commonMain/kotlin")
                        (map #(str (fs/relativize root (fs/parent (fs/parent (fs/parent %))))))
                        sort
                        first)
            suggest (fn [set-name plugins]
                      (format "  aps-kotlin scaffold --dir %s/src/%s/kotlin   # %s"
                              (or module "<module>") set-name plugins))]
        (s/die! "No JVM test source set exists in this worktree."
                (str "Looked for src/{" (str/join "," test-source-set-preference)
                     "}/kotlin at any depth")
                ""
                "Which of the two Android names Gradle compiles depends on the"
                "module's Android plugin, and a directory carrying the wrong one is"
                "ignored silently rather than reported. Read the module's build"
                "script, create that source set, or name it here:"
                ""
                (suggest "androidHostTest" "com.android.kotlin.multiplatform.library")
                (suggest "androidUnitTest" "com.android.library, com.android.application")))))

(defn do-scaffold [flags]
  (let [package (or (when (string? (get flags "--package")) (get flags "--package")) default-package)
        force? (boolean (get flags "--force"))
        source-root (find-test-source-root (get flags "--dir"))
        target-dir (str (fs/path source-root (str/replace package "." "/")))]
    (fs/create-dirs target-dir)
    (s/heading "Acceptance scaffold (aps-kotlin scaffold)")
    (println (format "Package: %s" package))
    (println (format "Into   : %s" (relative target-dir)))
    (println)
    (doseq [name-of scaffold-files]
      (let [source (s/templates-dir name-of)
            target (fs/path target-dir name-of)
            body (str/replace (slurp source) "package acceptance" (str "package " package))]
        (cond
          (and (fs/exists? target) (not force?))
          (println (format "  kept      %s (already present)" name-of))

          :else
          (do (spit (str target) body)
              (println (format "  %-9s %s" (if force? "replaced" "written") name-of))))))
    (println)
    (println "ApsJson.kt and ApsRuntime.kt implement the specification's runtime")
    (println "contract. ApsStepHandlers.kt is yours: it is the only file where step")
    (println "text meets application behaviour, and scaffold never rewrites it")
    (println "unless you pass --force.")
    (println)
    (println "Next:")
    (println "  bb gherkin-parser <feature> build/acceptance/<feature>.json")
    (println (format "  aps-kotlin generate build/acceptance/<feature>.json %s"
                     (shell-quote (relative (fs/path target-dir "generated")))))
    (println "  aps-kotlin acceptance")
    (System/exit 0)))

;; ------------------------------------------------------------------ classpath

(defn classpath-file [] (str (fs/path (s/state-dir "aps") "classpath.tsv")))

(defn dump-classpath!
  "One Gradle invocation, then none. The worker launches test JVMs directly
  afterwards, so the constitution's rule against concurrent Gradle runs holds
  even at --workers 4."
  []
  (let [out-dir (fs/path (s/state-dir "aps") "classpath.d")
        init (s/templates-dir "aps-classpath.init.gradle")]
    (fs/delete-tree (str out-dir))
    (fs/create-dirs out-dir)
    (s/progress "asking Gradle for the test runtime classpath (once)")
    (s/gradle-or-die!
     ["--init-script" (str init)
      (str "-Daps.classpath.dir=" out-dir)
      ;; The reporting task reads the project model at execution time, which the
      ;; configuration cache forbids. Disabling it for this one invocation is a
      ;; command-line flag, not a project change.
      "--no-configuration-cache"
      "--console=plain"
      "apsDumpTestClasspath"]
     (str "Could not read the test classpath.\n"
          "If the failure is a compile error in the tests, fix it first: the\n"
          "acceptance runner cannot run tests that do not build.\n"
          "If Gradle cannot be used here at all, hand the worker a classpath\n"
          "file instead: aps-kotlin worker --classpath-file <file>"))
    (let [merged (->> (fs/glob out-dir "*.tsv")
                      (mapcat (comp str/split-lines slurp str))
                      (remove str/blank?)
                      sort
                      distinct)]
      (when (empty? merged)
        (s/die! "Gradle reported no Test tasks in this project."
                "The acceptance tests need a JVM test task to run in."
                ""
                "In a KMP module with Android and iOS targets that is normally"
                "testDebugUnitTest. Confirm with: ./gradlew tasks --all | grep -i test"))
      (spit (classpath-file) (str (str/join "\n" merged) "\n"))
      (classpath-file))))

(defn read-classpath [path]
  (->> (str/split-lines (slurp (str path)))
       (remove str/blank?)
       (map (fn [line]
              (let [[task dirs entries] (str/split line #"\t" 3)]
                {:task task
                 :test-dirs (remove str/blank? (str/split (or dirs "") (re-pattern path-separator)))
                 :entries (remove str/blank? (str/split (or entries "") (re-pattern path-separator)))})))
       (remove #(empty? (:entries %)))
       vec))

(def ^:private task-preference
  "Tie-break only, and only when no compiled output holds the generated class.
  `testAndroidHostTest` is the host test task the multiplatform Android library
  plugin registers, and it belongs here for the same reason `androidHostTest`
  belongs in the source-set list: without it the task this project actually runs
  ranks below tasks it does not have."
  ["testAndroidHostTest" "testDebugUnitTest" "test" "jvmTest" "testReleaseUnitTest"])

(defn choose-classpath
  "Prefer the test task whose compiled output actually contains the generated
  class. Guessing by task name would silently measure the wrong source set."
  [candidates fqcn]
  (let [class-file (str (str/replace fqcn "." "/") ".class")
        holds-class? (fn [c] (some #(fs/exists? (fs/path % class-file)) (:test-dirs c)))
        with-class (filter holds-class? candidates)
        rank (fn [c] (let [n (last (str/split (or (:task c) "") #":"))
                           i (.indexOf task-preference n)]
                       (if (neg? i) (count task-preference) i)))]
    (if (seq with-class)
      (first (sort-by (juxt rank :task) with-class))
      (do (s/eprintln (str "warning: no compiled " class-file " found in any test output directory;"))
          (s/eprintln "         falling back to task-name preference. Compile the tests first.")
          (first (sort-by (juxt rank :task) candidates))))))

(defn launcher-version [entries]
  (if (some #(re-find #"junit-(jupiter-api|platform-commons)-6\." (str %)) entries)
    launcher-6x
    launcher-1x))

(defn launcher-jar [version]
  (let [jar (fs/path (s/tools-dir) (str "junit-platform-console-standalone-" version ".jar"))]
    (when-not (fs/exists? jar)
      (s/download! (str "https://repo1.maven.org/maven2/org/junit/platform/"
                        "junit-platform-console-standalone/" version
                        "/junit-platform-console-standalone-" version ".jar")
                   jar
                   (str "JUnit Platform console launcher " version)))
    (str jar)))

;; --------------------------------------------------------------------- worker

(defn parse-duration
  "The job's timeout arrives as a Go-style duration string."
  [raw default-ms]
  (let [text (str/trim (str (or raw "")))]
    (if-let [[_ number unit] (re-matches #"(?i)(\d+(?:\.\d+)?)\s*(ms|s|m|h)?" text)]
      (let [value (Double/parseDouble number)
            factor (case (str/lower-case (or unit "s"))
                     "ms" 1 "s" 1000 "m" 60000 "h" 3600000)]
        (long (* value factor)))
      default-ms)))

(defn- summary-count [output pattern]
  (some-> (re-find pattern output) second parse-long))

(defn classify
  "Map a launcher run onto the specification's three outcomes.
  A run where no test executed is an infrastructure error, never a survivor:
  reporting 'the tests passed' when no test ran would turn every mutation into a
  false pass and make the whole measurement worthless."
  [exit output]
  (let [finished? (str/includes? output "Test run finished")
        found (summary-count output #"(\d+)\s+tests found")
        succeeded (summary-count output #"(\d+)\s+tests successful")
        failed (summary-count output #"(\d+)\s+tests failed")
        containers-failed (summary-count output #"(\d+)\s+containers failed")]
    (cond
      (not finished?)
      {:outcome "infrastructure_error"
       :error (str "The JUnit launcher produced no run summary (exit " exit "). "
                   "The tests did not run.")}

      (or (nil? found) (zero? found))
      {:outcome "infrastructure_error"
       :error (str "The launcher found no tests to run (exit " exit "). "
                   "A mutation nothing exercises is not a surviving mutation.")}

      (or (pos? (or failed 0)) (pos? (or containers-failed 0)))
      {:outcome "test_failure" :error ""}

      (zero? (or succeeded 0))
      {:outcome "infrastructure_error"
       :error "The launcher reported neither successes nor failures."}

      :else
      {:outcome "test_success" :error ""})))

(defn- truncate [text limit]
  (let [text (str text)]
    (if (<= (count text) limit)
      text
      (str (subs text 0 limit) "\n... (" (- (count text) limit) " more characters)"))))

(defn- kill! [proc]
  (p/destroy-tree proc)
  (when-not (.waitFor (:proc proc) 2 TimeUnit/SECONDS)
    (.destroyForcibly (:proc proc))
    (.waitFor (:proc proc))))

(defn run-tests
  "One test JVM for one mutated IR. No Gradle: the classpath was resolved once
  at startup."
  [ctx feature-json timeout-ms]
  (let [cmd (into ["java"
                   (str "-D" "aps.feature.json=" feature-json)
                   "-jar" (:launcher ctx)
                   "execute"
                   "--class-path" (:classpath ctx)
                   "--select-class" (:fqcn ctx)
                   "--details=summary"
                   "--disable-ansi-colors"]
                  (:extra-launcher-args ctx))
        started (System/nanoTime)
        proc (p/process cmd {:dir (s/worktree-root)
                             :out :string
                             :err :string
                             :extra-env {"APS_FEATURE_JSON" feature-json}})
        finished? (.waitFor (:proc proc) timeout-ms TimeUnit/MILLISECONDS)]
    (if-not finished?
      (do (kill! proc)
          {:duration (- (System/nanoTime) started)
           :output ""
           :outcome "infrastructure_error"
           :error (str "The test JVM did not finish within " timeout-ms "ms and was killed. "
                       "A run that could not be evaluated is an error, not a verdict.")})
      (let [{:keys [exit out err]} @proc
            output (str out (when-not (str/blank? err) (str "\n" err)))
            {:keys [outcome error]} (classify exit output)]
        {:duration (- (System/nanoTime) started)
         :output (truncate output 20000)
         :outcome outcome
         :error (if (str/blank? error) "" (str error "\n" (truncate output 4000)))}))))

(defn worker-context
  "Everything a job needs, resolved once. Failures here are remembered and
  answered per job rather than thrown, so the mutator gets the remediation text
  instead of a dead pipe."
  [flags first-feature-json]
  (try
    (let [package (or (when (string? (get flags "--package")) (get flags "--package")) default-package)
          fqcn (or (when (string? (get flags "--class")) (get flags "--class"))
                   (let [ir (json/parse-string (slurp first-feature-json) true)]
                     (str generated-package "." (class-name (:name ir)))))
          cp-path (or (when (string? (get flags "--classpath-file")) (get flags "--classpath-file"))
                      (let [cached (classpath-file)]
                        (if (and (fs/exists? cached) (not (get flags "--refresh")))
                          cached
                          (dump-classpath!))))
          candidates (read-classpath cp-path)
          chosen (choose-classpath candidates fqcn)
          entries (concat (:test-dirs chosen) (:entries chosen))
          version (launcher-version entries)]
      (s/eprintln (str "aps-kotlin worker: class " fqcn))
      (s/eprintln (str "aps-kotlin worker: test task " (:task chosen)))
      (s/eprintln (str "aps-kotlin worker: launcher " version
                       ", " (count entries) " classpath entries"))
      {:package package
       :fqcn fqcn
       :classpath (str/join path-separator entries)
       :launcher (launcher-jar version)
       :extra-launcher-args []})
    (catch Exception e
      {:broken (str "aps-kotlin worker could not start: " (.getMessage e))})))

(defn respond! [payload]
  ;; Standard output carries the protocol and nothing else. Every diagnostic in
  ;; this file goes to stderr for exactly this reason.
  (println (json/generate-string payload))
  (flush))

(defn do-worker [flags]
  (let [default-timeout (parse-duration (get flags "--timeout") 120000)
        context (atom nil)]
    (s/eprintln "aps-kotlin worker: ready")
    (doseq [line (line-seq (io/reader *in*))]
      (when-not (str/blank? line)
        (let [job (try (json/parse-string line true)
                       (catch Exception e {::bad (.getMessage e)}))]
          (if (::bad job)
            (respond! {:id "" :outcome "infrastructure_error" :output ""
                       :error (str "Unparseable job line: " (::bad job))
                       :duration 0})
            (let [id (str (:id job))
                  raw-feature (str (:feature_json job))
                  feature-json (str (if (fs/absolute? raw-feature)
                                      raw-feature
                                      (fs/path (s/worktree-root) raw-feature)))]
              (cond
                (str/blank? raw-feature)
                (respond! {:id id :outcome "infrastructure_error" :output ""
                           :error "Job has no feature_json." :duration 0})

                (not (fs/exists? feature-json))
                (respond! {:id id :outcome "infrastructure_error" :output ""
                           :error (str "Mutated IR " feature-json " does not exist.")
                           :duration 0})

                :else
                (do
                  (when (nil? @context)
                    (reset! context (worker-context flags feature-json)))
                  (if-let [broken (:broken @context)]
                    (respond! {:id id :outcome "infrastructure_error" :output ""
                               :error broken :duration 0})
                    (let [result (run-tests @context feature-json
                                            (parse-duration (:timeout job) default-timeout))]
                      (s/eprintln (format "aps-kotlin worker: %s %s in %dms"
                                          id (:outcome result)
                                          (quot (:duration result) 1000000)))
                      (respond! {:id id
                                 :outcome (:outcome result)
                                 :output (:output result)
                                 :error (:error result)
                                 :duration (:duration result)}))))))))))
    (s/eprintln "aps-kotlin worker: input closed, exiting")
    (System/exit 0)))

;; ----------------------------------------------------------------- acceptance

(defn test-task []
  (or (first (filter s/has-task? task-preference))
      (s/die! "No JVM test task found."
              (str "Looked for: " (str/join ", " task-preference))
              ""
              "The acceptance tests are ordinary JVM unit tests. In a KMP module"
              "with Android and iOS targets the task is normally testDebugUnitTest."
              "Confirm with: ./gradlew tasks --all")))

(defn do-acceptance [flags]
  (let [task (test-task)
        filter-of (or (when (string? (get flags "--tests")) (get flags "--tests"))
                      (str generated-package ".*"))]
    (s/progress "running acceptance tests via" task)
    (let [{:keys [exit]} (s/gradle [task "--tests" filter-of "--console=plain"])]
      (s/heading "Acceptance run (aps-kotlin acceptance)")
      (println (format "Task  : %s" task))
      (println (format "Filter: %s" filter-of))
      (println (format "Result: %s" (if (zero? exit) "all executions passed" "FAILURES")))
      (println)
      (println "This is the Gherkin specification executing against the application.")
      (println "A green run here is not evidence the specification is strong; that is")
      (println "what gherkin-mutator measures.")
      (System/exit (if (zero? exit) 0 1)))))

;; ---------------------------------------------------------------- generated-dir

(defn do-generated-dir
  "Print the directory holding the generated acceptance entry points, and nothing
  else. The gherkin-mutator wrapper calls this to fill in --generated-dir, whose
  specified default (<work-dir>/generated) is never where a Kotlin test source set
  puts them. One line on stdout so a shell can capture it."
  [flags]
  (let [package (or (when (string? (get flags "--package")) (get flags "--package")) default-package)
        source-root (find-test-source-root (get flags "--dir"))]
    (println (str (fs/path source-root (str/replace package "." "/") "generated")))
    (System/exit 0)))

;; ----------------------------------------------------------------------- scan

(defn do-scan [flags]
  (let [package (or (when (string? (get flags "--package")) (get flags "--package")) default-package)
        ;; The query, not the refusal: scan reports what is missing and exits 0,
        ;; so an absent source set is a line of output rather than the end of it.
        source-root (test-source-root (get flags "--dir"))
        target-dir (when source-root (fs/path source-root (str/replace package "." "/")))
        generated-dir (when target-dir (fs/path target-dir "generated"))
        features (feature-files)]
    (s/heading "Acceptance setup (aps-kotlin scan)")
    (println (format "Test source set : %s" (if source-root (relative source-root) "NOT FOUND")))
    ;; Named here rather than left to scaffold, because scan is where a role is
    ;; told to start and the two spellings are the one thing it cannot work out
    ;; on its own. androidHostTest under the multiplatform Android library plugin,
    ;; androidUnitTest under com.android.library or com.android.application.
    (when-not source-root
      (println (format "  looked for src/{%s}/kotlin at any depth"
                       (str/join "," test-source-set-preference)))
      (println "  create the one the module's Android plugin compiles, or pass --dir"))
    (when target-dir
      (doseq [name-of scaffold-files]
        (println (format "  %-20s %s" name-of
                         (if (fs/exists? (fs/path target-dir name-of)) "present" "MISSING")))))
    (println (format "Generated tests : %s"
                     (if (and generated-dir (fs/exists? generated-dir))
                       (str (count (fs/glob generated-dir "*.kt")) " file(s) in "
                            (relative generated-dir))
                       "none yet")))
    (println (format "Feature files   : %d" (count features)))
    (doseq [f features]
      (println (format "  %s   (%s)" (relative f) (or (declared-feature-name f) "no Feature: line"))))
    (println (format "Classpath cache : %s"
                     (if (fs/exists? (classpath-file))
                       (str (count (read-classpath (classpath-file))) " test task(s) recorded")
                       "absent, the worker will ask Gradle once")))
    (println (format "Launcher jars   : %s"
                     (let [jars (fs/glob (s/tools-dir) "junit-platform-console-standalone-*.jar")]
                       (if (seq jars) (str/join ", " (map (comp str fs/file-name) jars))
                           "none, downloaded on first worker run"))))
    (println)
    (println "Acceptance mutation, once the above is in place:")
    (println (format "  bb gherkin-mutator --feature %s \\"
                     (if (seq features) (shell-quote (relative (first features))) "<feature>")))
    (println (format "      --generated-dir %s \\"
                     (if generated-dir (shell-quote (relative generated-dir)) "<generated dir>")))
    (println "      --runner-worker \"aps-kotlin worker\" --workers 4 --level hard")
    (println)
    (println "--generated-dir must point at the generated tests that are actually")
    (println "compiled. The mutator reads the implementation hash from the metadata")
    (println "beside them, and a hash describing files nobody runs would let it reuse")
    (println "results that no longer apply.")
    (println)
    (println "Scan performs no build and produces no measurement.")
    (System/exit 0)))

;; ----------------------------------------------------------------------- main

(def ^:private flags-with-values
  #{"--feature" "--package" "--dir" "--class" "--classpath-file" "--tests"
    "--timeout" "--max-workers" "--workers" "--level" "--generated-dir"})

(defn -main [& args]
  (let [{:keys [flags positional]} (s/parse-args args flags-with-values)
        [command & rest-positional] positional]
    (case (str command)
      "generate" (do-generate (vec rest-positional) flags)
      "scaffold" (do-scaffold flags)
      "acceptance" (do-acceptance flags)
      "worker" (do-worker flags)
      "scan" (do-scan flags)
      "generated-dir" (do-generated-dir flags)
      (die-usage!
       "usage: aps-kotlin <command> [options]"
       ""
       "  scaffold                        write the acceptance runtime and handler template"
       "  generate <json-ir> <out-dir>    write acceptance entry points and metadata"
       "  acceptance                      run the generated acceptance tests through Gradle"
       "  worker                          persistent runner adapter for gherkin-mutator"
       "  scan                            report what is wired and what is missing"
       "  generated-dir                   print where the generated entry points live"
       ""
       "Start with: aps-kotlin scan"))))

(apply -main *command-line-args*)
