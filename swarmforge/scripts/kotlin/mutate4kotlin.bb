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
;; Engine: PIT (pitest). PIT operates on JVM bytecode, so its reach is the same
;; as Kover's: commonMain through Android host tests, plus androidMain. iosMain,
;; Kotlin/Native and Swift cannot be mutated by any maintained tool, and the
;; constitution says to state that rather than estimate it.
;;
;; Two ways in, because one of them is not always available. Where the pitest
;; Gradle plugin registers a task, that task runs. On Kotlin Multiplatform it
;; registers nothing - it builds its extension and its task inside
;; plugins.withType(JavaPlugin), and a KMP module never applies the java plugin -
;; so there the tool asks Gradle once for the classpath and drives PIT's own
;; command line itself. PIT has no such limitation: it wants a classpath, not a
;; source set. Either way the number comes from PIT inside this tool, and never
;; from a task the project wrote.
;;
;; Every run is a full run, and no history file is written. PIT used to ship a
;; file-based history store: measured inside pitest-entry, 1.22.1 carries
;; DefaultHistoryFactory and ObjectOutputStreamHistory, and 1.25.9 carries
;; ErroringHistoryFactory and registers no HistoryFactory at all. The store moved
;; to a commercial plugin, so asking either way in for a history location now ends
;; the run with "History has been enabled but no history plugin has been
;; installed". Nothing here reimplements it. A mutation number partly carried over
;; from an earlier run is not one this constitution would report anyway.

(require '[babashka.classpath :as cp] '[babashka.fs :as fs])
(cp/add-classpath (str (fs/parent (fs/absolutize *file*))))
(require '[sfk.support :as s]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.data.xml :as xml]
         '[cheshire.core :as json])

(def pitest-plugin-version "1.19.0")
(def pitest-core-version "1.25.9")
;; Measured, not chosen: asking Gradle to resolve
;; org.pitest:pitest-junit5-plugin:1.+ against Maven Central lands here. PIT
;; supports JUnit 4 natively and needs this plugin only for JUnit 5.
(def pitest-junit5-plugin-version "1.2.3")

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
   "    targetClasses = setOf(\"<your.base.package>.*\")\n"
   "    excludedClasses = mutationExclusions.toSet()\n"
   "    outputFormats = setOf(\"XML\", \"HTML\")\n"
   "    timestampedReports = false\n"
   "    threads = 4\n"
   "\n"
   "    // Only when the test task calls useJUnitPlatform(). kotlin-test on a JVM\n"
   "    // module runs JUnit 4 otherwise, and PIT supports JUnit 4 natively, so\n"
   "    // setting this against a JUnit 4 suite is how a run ends in\n"
   "    // \"Minion exited abnormally\".\n"
   "    // junit5PluginVersion = \"" pitest-junit5-plugin-version "\"\n"
   "\n"
   "    // Do not set these. PIT " pitest-core-version " registers no history store -\n"
   "    // the open source build dropped the file-based one - so a history\n"
   "    // location, or the flag that fills one in for you, ends the run with\n"
   "    // \"History has been enabled but no history plugin has been installed\".\n"
   "    // enableDefaultIncrementalAnalysis = true\n"
   "    // historyInputLocation / historyOutputLocation\n"
   "}\n"))

;; Measured from gradle-pitest-plugin-1.19.0.jar: the task is
;; info.solidsoft.gradle.pitest.PitestTask. Matching the package rather than that
;; one class means a rename inside the plugin still passes.
(def task-type-prefix "info.solidsoft.gradle.pitest.")

(defn pitest-task-state
  "What the name `pitest` stands for in this build.

  :plugin   - the pitest Gradle plugin's own task. Run it.
  :absent   - no task carries the name. Normal on Kotlin Multiplatform, where
              the plugin registers nothing; the tool drives PIT itself.
  :shadowed - a task by that name exists and belongs to something else.
  :unknown  - Gradle resolved the name and would not say what it is.

  The question is the class, not the name, because any build script can register
  a task called `pitest`."
  []
  (let [types (:types (s/task-info "pitest"))]
    (cond
      (nil? types) :absent
      (some #(str/starts-with? % task-type-prefix) types) :plugin
      (empty? types) :unknown
      :else :shadowed)))

(defn refuse-shadowed-task!
  "A task named `pitest` that is not the plugin's ends the run.

  Not because the tool needs that task - it does not, it drives PIT itself - but
  because a hand-written task called `pitest` is exactly the project-local
  mutation proxy the constitution forbids, and it writes into the same report
  directory PIT does. Whoever runs `./gradlew pitest` next will read its output
  as a mutation measurement."
  [state]
  (let [types (:types (s/task-info "pitest"))]
    (if (= :unknown state)
      (s/die! "Gradle resolved task 'pitest' but did not report its class."
              ""
              "This tool identifies a task by its implementing class, because any"
              "build script can register a task by that name. Without the class it"
              "cannot tell the plugin's task from a look-alike."
              ""
              "That most likely means the output format of this command changed:"
              "  ./gradlew help --task pitest"
              ""
              "Run it yourself and report what it prints to the operator with"
              "pack_dashboard_request.sh clarify. This is a tool defect, not a"
              "defect in the project build.")
      (s/die! "Gradle task 'pitest' exists, but it is not the pitest plugin's task."
              ""
              (str "  found:    " (str/join ", " types))
              (str "  expected: a class under " task-type-prefix)
              ""
              "Something in this build registers a task under that name. This tool"
              "will not run alongside it, because its output would be read as a"
              "mutation measurement and the constitution forbids that:"
              ""
              "  \"Do not invent project-local CRAP, DRY, mutation, or coverage"
              "   proxies.\""
              ""
              "See it for yourself:"
              "  ./gradlew help --task pitest"
              ""
              "Delete that task. This tool does not need it: where the pitest Gradle"
              "plugin cannot register a task - every Kotlin Multiplatform module,"
              "because the plugin builds itself inside plugins.withType(JavaPlugin)"
              "and KMP never applies the java plugin - mutate4kotlin asks Gradle for"
              "the test classpath and drives PIT's own command line. It writes into"
              "the same build/reports/pitest directory, so leaving both in place"
              "makes it impossible to say which run a report came from."
              ""
              "If your role does not own the build configuration, ask the operator"
              "with pack_dashboard_request.sh clarify."))))

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

;; ------------------------------------------------ pit on its own command line

(def ^:private path-separator (str java.io.File/pathSeparator))
(def ^:private file-separator (str java.io.File/separator))

(defn- split-paths
  "The report joins lists of paths with the platform path separator, the one
  character a path cannot contain. PIT's own options are comma separated instead,
  so the conversion happens where the command line is built and nowhere earlier."
  [value]
  (->> (str/split (str value)
                  (re-pattern (java.util.regex.Pattern/quote path-separator)))
       (map str/trim)
       (remove str/blank?)
       vec))

(def ^:private path-list-keys
  #{:pitestClasspath :testClassesDirs :classpath :mutableCodePaths :sourceDirs})

(defn- read-dump
  "One report file as a map. Tab separated, so the script that writes it needs no
  escaping rules: a path can hold almost anything except a tab or a newline."
  [path]
  (into {}
        (for [line (str/split-lines (slurp (str path)))
              :when (str/includes? line "\t")
              :let [[k v] (str/split line #"\t" 2)
                    field (keyword k)]]
          [field (if (path-list-keys field) (split-paths v) v)])))

(defn- dump-dir []
  (str (fs/path (s/state-dir "mutation") "pitest.d")))

(defn- read-dumps [dir]
  (->> (fs/glob dir "*.tsv") (map str) sort (mapv read-dump)))

(defn dump-pitest-inputs!
  "Ask Gradle once for everything PIT needs, and return one map per JVM test task.

  The same build compiles the production and the test classes, so nothing else
  has to run before PIT does. The script is injected with --init-script and no
  project file is touched, which is what lets a role that does not own the build
  measure mutation at all.

  --no-configuration-cache because the script reads the Kotlin and Android model
  to find which compilation a test task exercises, and a project with the
  configuration cache on refuses to serialise that. A flag on this one invocation
  is not a change to the project."
  []
  (let [dir (dump-dir)]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (s/progress "asking gradle for the classpath and code paths PIT needs")
    (s/gradle-or-die!
     ["--init-script" (s/templates-dir "pitest-cli.init.gradle")
      (str "-Dmutate4kotlin.dir=" dir)
      (str "-Dmutate4kotlin.pitest.version=" pitest-core-version)
      (str "-Dmutate4kotlin.junit5.version=" pitest-junit5-plugin-version)
      "--no-configuration-cache"
      "--console=plain"
      "mutate4kotlinDumpPitest"]
     (str "Gradle could not report what PIT needs.\n"
          "This build compiles every module's production and test classes, so a\n"
          "failure here is a failure in the project, not in the measurement."))
    (read-dumps dir)))

(defn- candidate-stem
  "A file-name-safe name for one candidate run. Named after the test task rather
  than the module, because a module can have more than one JVM test task and each
  is a run of its own, with its own history."
  [dump]
  (-> (str (:testTask dump)) (str/replace ":" "_") (str/replace #"^_" "")))

(defn- compiled-test-classes
  "Every test class the module has actually compiled.

  Asked because a JVM test task can exist with nothing in it - a KMP module has
  an Android host-test compilation whether anyone wrote a test there or not - and
  PIT started against an empty suite fails, which reads like a broken build
  rather than an empty source set."
  [dump]
  (->> (:testClassesDirs dump)
       (filter fs/exists?)
       (mapcat (fn [dir]
                 (for [file (fs/glob dir "**.class")]
                   (-> (str (fs/relativize dir file))
                       (str/replace #"\.class$" "")
                       (str/replace file-separator ".")))))
       sort
       vec))

(defn- test-class-globs
  "What to hand PIT as --targetTests: one glob per package that holds compiled
  test classes.

  A glob per package rather than the class list, because a large module's list of
  test classes does not fit on a command line. Not a plain `*` either, because PIT
  would then hunt for tests through every dependency on the classpath."
  [test-classes]
  (->> test-classes
       (map (fn [fqcn]
              (let [pkg (str/join "." (butlast (str/split fqcn #"\.")))]
                (if (str/blank? pkg) fqcn (str pkg ".*")))))
       distinct
       sort
       vec))

(defn- declared-package
  "The package a source file declares, or nil when it declares none.

  The first `package` line wins, and a line inside a comment does not count: a
  regex over the whole file would happily read the word out of a licence header
  or a KDoc paragraph."
  [path]
  (some (fn [raw]
          (let [line (str/trim raw)]
            (when-not (or (str/starts-with? line "//")
                          (str/starts-with? line "/*")
                          (str/starts-with? line "*"))
              (some-> (second (re-matches #"package\s+([^\s;]+)\s*;?" line))
                      (str/replace "`" "")))))
        (str/split-lines (slurp (str path)))))

(defn- declared-packages
  "Every package the module's own hand-written Kotlin and Java files declare.

  PIT requires --targetClasses and must not be handed `*`: that would put its own
  jars and every dependency in scope. The scope comes from the source the module
  contains rather than from a base package the tool would have to be told, and
  from source rather than from class files, so that a stale class left behind in
  build output cannot widen it.

  The module's own src tree only. A Kotlin Multiplatform compilation also lists
  the source sets it depends on, and another module's code is not this run's to
  mutate. --mutableCodePaths draws the same line over the classpath; this keeps
  the two answers consistent."
  [dump]
  (let [own (str (fs/path (:projectDir dump) "src"))]
    (->> (:sourceDirs dump)
         (filter #(or (= % own) (str/starts-with? % (str own file-separator))))
         (filter fs/exists?)
         (mapcat (fn [dir] (concat (fs/glob dir "**.kt") (fs/glob dir "**.java"))))
         (keep declared-package)
         distinct
         sort
         vec)))

(defn candidates
  "Every mutation run this build makes possible, each either runnable or carrying
  the reason it is not.

  Annotated rather than dropped, because to an agent reading the output 'there is
  nothing here to mutate' and 'the tool looked in the wrong place' are the same
  silence unless the tool says which one it found."
  [dumps]
  (mapv (fn [dump]
          (let [test-classes (compiled-test-classes dump)
                packages (declared-packages dump)]
            (assoc dump
                   :stem (candidate-stem dump)
                   :testClasses test-classes
                   :packages packages
                   :skip (cond
                           (empty? (:mutableCodePaths dump))
                           "no compiled production code on its test classpath"
                           (empty? test-classes)
                           "no compiled test classes, so no mutant could be killed"
                           (empty? packages)
                           "no hand-written Kotlin or Java source in the module"))))
        dumps))

(defn run-pitest-cli!
  "Run PIT over one candidate and return the mutations.xml it wrote.

  The working directory is the module's own project directory, and that is not
  incidental. The Android plugin records Robolectric's manifest and resource
  locations in test_config.properties as paths relative to the module, and
  Robolectric resolves them against the current directory - Gradle's test task
  runs there. A PIT launched anywhere else fails every Robolectric test with
  `Failed adding asset path`, and then refuses to report at all because it
  requires a suite that passes unmutated. The symptom names neither the cause nor
  the fix, so the reason is written down here."
  [candidate exclusions workers]
  (let [stem (:stem candidate)
        report-dir (str (fs/path (:buildDir candidate) "reports" "pitest" stem))
        classpath-file (str (fs/path (s/state-dir "mutation")
                                     (str stem ".classpath.txt")))]
    ;; A stale report is worse than no report: the tool would parse it and
    ;; present an earlier run's number as this one's.
    (fs/delete-tree report-dir)
    ;; The classpath goes in a file. An Android host-test classpath runs to a
    ;; couple of hundred entries and tens of thousands of characters, and the
    ;; argument limit is not a thing to discover from a truncated command line.
    (spit classpath-file (str (str/join "\n" (:classpath candidate)) "\n"))
    (let [cmd (concat
               [(:java candidate)
                "-cp" (str/join path-separator (:pitestClasspath candidate))
                "org.pitest.mutationtest.commandline.MutationCoverageReport"
                "--reportDir" report-dir
                "--classPathFile" classpath-file
                ;; Comma separated, not path separated. Given a path-joined value
                ;; PIT reads it as one long directory name, finds nothing there
                ;; and reports "No mutations found" - a calm answer to a question
                ;; it never managed to ask.
                "--sourceDirs" (str/join "," (:sourceDirs candidate))
                "--mutableCodePaths" (str/join "," (:mutableCodePaths candidate))
                "--targetClasses" (str/join "," (map #(str % ".*") (:packages candidate)))
                "--targetTests" (str/join "," (test-class-globs (:testClasses candidate)))
                "--outputFormats" "XML,HTML"
                "--timestampedReports" "false"
                "--threads" (str workers)]
               ;; No --historyInputLocation or --historyOutputLocation. PIT 1.25.9
               ;; registers no history store, so either one ends the run in
               ;; ErroringHistoryFactory before a single mutant is generated. Every
               ;; run is therefore a full run. See the note at the top of this file.
               ;; PIT supports JUnit 4 without help and JUnit 5 through a plugin.
               ;; Which one this module runs was read off its test classpath.
               (when (= "junit5" (:testEngine candidate)) ["--testPlugin" "junit5"])
               (when (seq exclusions)
                 ["--excludedClasses" (str/join "," (map :pattern exclusions))]))]
      ;; Printed with the long classpaths counted rather than spelled out: a
      ;; command nobody can read is not a command anyone can re-run.
      (s/eprintln "+"
                  (str/join " " (map (fn [arg]
                                       (if (str/includes? arg path-separator)
                                         (str "<" (count (split-paths arg)) " entries>")
                                         arg))
                                     cmd)))
      (s/progress "mutating" (:project candidate) "through" (:testTask candidate))
      (let [{:keys [exit]} @(p/process cmd {:dir (:projectDir candidate)
                                            :out :inherit
                                            :err :inherit})]
        (when-not (zero? exit)
          (s/die! ""
                  (str "PIT failed on " (:project candidate)
                       " (" (:testTask candidate) ").")
                  ""
                  "Read its output above. The three usual causes, in order:"
                  ""
                  "  \"tests did not pass without mutation\" - the suite is red."
                  "      Fix the tests first. A mutation number measured over a red"
                  "      suite means nothing."
                  "  \"No mutations found\" - PIT saw no code to mutate. Run"
                  "      mutate4kotlin --scan and check the module's packages."
                  "  \"Minion exited abnormally\" - the engine PIT was told to use is"
                  (str "      not the one the suite runs. This module reported "
                       (:testEngine candidate) ".")
                  ""
                  "PIT ran with the module directory as its working directory, the"
                  "same as Gradle's test task, so a Robolectric resource failure here"
                  "is not a path problem in this tool.")))
      (let [xml (str (fs/path report-dir "mutations.xml"))]
        (when-not (fs/exists? xml)
          (s/die! (str "PIT reported success but wrote no " xml)
                  ""
                  "The XML report is the machine-readable one, and this tool asks for"
                  "it by name with --outputFormats XML,HTML. Its absence after a"
                  "successful run is a defect in this tool or in PIT, not something to"
                  "work around: report it with pack_dashboard_request.sh clarify."))
        xml))))

;; ---------------------------------------------------------------- pit reports

(defn find-reports []
  ;; Both depths are variable: the module may be the root project, and PIT nests
  ;; the report under a timestamp directory unless timestampedReports is off.
  (->> (fs/glob (s/worktree-root)
                (str s/any-depth "build/reports/pitest/" s/any-depth "mutations.xml"))
       (map str)
       distinct
       (sort-by #(- (fs/file-time->millis (fs/last-modified-time %))))
       vec))

(defn text-of [element tag]
  (some-> (first (s/children element tag)) :content first str str/trim))

(defn- slash-package
  "The package of a class name, in the slash form the generated-code check reads.
  That check was written against JaCoCo, which writes `com/example`; PIT writes
  `com.example`. One of the two has to convert, and the shared code is not the
  place to learn a second spelling."
  [fqcn]
  (str/join "/" (butlast (str/split (str fqcn) #"\."))))

(defn mutations [xml-path]
  (let [root (xml/parse-str (slurp xml-path))]
    (for [m (s/children root "mutation")
          :let [cls (text-of m "mutatedClass")]]
      {:status (or (s/attr m :status) "UNKNOWN")
       :detected (= "true" (str (s/attr m :detected)))
       :source (text-of m "sourceFile")
       :class cls
       :package (slash-package cls)
       :method (text-of m "mutatedMethod")
       ;; The bytecode signature, which is not the one in the source. A Kotlin
       ;; compiler plugin that rewrites a function changes its parameters, and
       ;; that is the only place the change is visible.
       :signature (text-of m "methodDescription")
       :line (text-of m "lineNumber")
       :mutator (last (str/split (or (text-of m "mutator") "unknown") #"\."))
       :description (text-of m "description")})))

;; ------------------------------------------------- code the compiler inserted

;; A second kind of generated code, and the class-level check cannot see it. The
;; Compose compiler does not only generate classes beside the author's; it
;; rewrites the body of the function the author wrote. `fun App()` compiles to
;; App(Composer, int) - the recomposition scope and the `$changed` bit mask - and
;; the prologue and epilogue that use them are attributed to the author's own
;; source lines, in the author's own class. Nothing in bytecode records who wrote
;; an instruction, so this cannot be answered in general: it takes knowing the
;; plugin that did the rewriting. Kept narrow for that reason.
(def ^:private composable-parameter "Landroidx/compose/runtime/Composer;")
(def ^:private compose-runtime-package "androidx/compose/runtime/")

(defn- removed-call
  "The method a VoidMethodCallMutator deleted, as PIT spells it, or nil."
  [description]
  (second (re-find #"removed call to ([^:\s]+)::" (str description))))

(defn compiler-plumbing
  "Why a mutant is in code the Compose compiler inserted, or nil when it is the
  author's.

  One rule, deliberately: a deleted call into the Compose runtime, from a method
  that takes a Composer. `traceEventStart`, `traceEventEnd`, `sourceInformation`,
  `skipToGroupEnd` and `updateScope` are recomposition bookkeeping. Nobody writes
  them, and no test can be asked to kill one.

  The `$changed` mask produces unkillable mutants too - `negated conditional` and
  `Replaced bitwise AND with OR`, measured on the composable's declaration line -
  and they are deliberately NOT filtered. An author's own `if` inside a composable
  reports identically, and no signal measured so far separates the two. A missing
  test wrongly reported is work for somebody; a missing test wrongly hidden is a
  lie the report tells. Those go in exclusions.txt, where a person writes the
  reason and another person can read it."
  [{:keys [mutator description signature]}]
  (when (and (str/includes? (str signature) composable-parameter)
             (= "VoidMethodCallMutator" mutator)
             (some-> (removed-call description)
                     (str/starts-with? compose-runtime-package)))
    :compose-inserted-call))

(def ^:private inserted-code-reasons
  {:compose-inserted-call "call the Compose compiler inserted"})

(defn- excluded-reason [reason]
  (or (inserted-code-reasons reason) (s/generated-reasons reason) reason))

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
  (println (format "PIT %s, pitest plugin %s, junit5 plugin %s"
                   pitest-core-version pitest-plugin-version
                   pitest-junit5-plugin-version))
  ;; Print the class, not a yes. "yes" was the whole defect: it was also the
  ;; answer for a hand-written task that happened to carry the name.
  (let [types (:types (s/task-info "pitest"))]
    (println (format "pitest task: %s"
                     (cond
                       (nil? types) "absent"
                       (some #(str/starts-with? % task-type-prefix) types)
                       (str "present, " (str/join ", " types))
                       (empty? types) "resolved, but Gradle reported no class"
                       :else (str "SHADOWED by " (str/join ", " types)
                                  " - not the plugin's task, mutation will refuse"))))
    ;; Which of the two ways in this project gets. Absent is not a fault here:
    ;; the pitest plugin registers nothing on Kotlin Multiplatform, and the tool
    ;; drives PIT itself in that case.
    (println (format "mode: %s"
                     (if (and types (some #(str/starts-with? % task-type-prefix) types))
                       "the pitest plugin's own task"
                       "PIT's command line, driven by this tool"))))
  ;; Said rather than measured, because there is nothing here to measure and the
  ;; question comes up: a run takes as long as a full run every time, and that is
  ;; the tool working as intended.
  (println (str "incremental: no. PIT " pitest-core-version " registers no history"
                " store, so every run is full."))
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
  ;; From the last run's cached answer only. Finding out what Gradle would say
  ;; today means running Gradle, and a scan that compiled the project would be a
  ;; different command than the one documented here.
  (let [dir (dump-dir)
        dumps (if (fs/exists? dir) (read-dumps dir) [])]
    (if (empty? dumps)
      (println "candidates: not known yet - a real run is what asks Gradle for them.")
      (do
        (println (format "candidates: %d, as of the last run (%s)" (count dumps) dir))
        (doseq [c (candidates dumps)]
          (println (format "    %-40s %s"
                           (str (:project c) " " (:testTask c))
                           (or (:skip c)
                               (format "%d test class(es), %s"
                                       (count (:testClasses c))
                                       (str/join " " (map #(str % ".*")
                                                          (:packages c)))))))))))
  (println)
  (println "Scan performs no build and produces no measurement.")
  (System/exit 0))

;; ------------------------------------------------------------------ the two ways

(defn run-plugin-task!
  "Run the pitest plugin's own task and return the reports it left behind.
  Reports rather than one report, because the task can run in several modules."
  [workers]
  (s/progress "running pitest with" workers "gradle worker(s)")
  (s/gradle-or-die!
   ["pitest" "--console=plain" "--max-workers" (str workers)]
   (str "Mutation testing could not complete.\n"
        "If the failure is a failing test, fix the test first: mutation\n"
        "results over a red suite mean nothing.\n"
        "If the failure is 'Minion exited abnormally', the JUnit 5 plugin\n"
        "version does not match the project's JUnit version. See the\n"
        "junit5PluginVersion line in the setup snippet.\n"
        "If the failure is 'History has been enabled but no history plugin\n"
        "has been installed', the pitest block in this project sets a history\n"
        "location, or enableDefaultIncrementalAnalysis. Remove them: PIT "
        pitest-core-version "\n"
        "registers no history store, so incremental analysis cannot be asked\n"
        "for at all."))
  (let [reports (find-reports)]
    (when (empty? reports)
      (s/die! "PIT ran but produced no mutations.xml."
              (str "Looked for build/reports/pitest/mutations.xml, timestamp"
                   " directory or not, at any depth under " (s/worktree-root))
              ""
              "Add \"XML\" to outputFormats in the pitest block. The HTML report"
              "alone is not machine readable."))
    (mapv (fn [path]
            {:label (str (fs/relativize (s/worktree-root) path)) :xml path})
          reports)))

(defn- module-matches?
  "Whether --module names this candidate. Written with or without the leading
  colon, because the operator reads the Gradle path in both forms."
  [module candidate]
  (= (str/replace (str module) #"^:" "")
     (str/replace (str (:project candidate)) #"^:" "")))

(defn run-cli!
  "Drive PIT directly, once per runnable candidate, and return the reports."
  [module exclusions workers]
  (let [all (candidates (dump-pitest-inputs!))
        chosen (if module (filterv #(module-matches? module %) all) all)]
    (when (empty? chosen)
      (s/die! (if module
                (str "No module called " module " has a JVM test task.")
                "This build has no JVM test task, so there is nothing to mutate.")
              ""
              "Modules Gradle reported:"
              (if (seq all)
                (str/join "\n" (map #(str "  " (:project %) "  " (:testTask %)) all))
                "  (none)")
              ""
              "PIT mutates JVM bytecode. A module with only iOS or Native targets"
              "cannot be measured by any maintained mutation tool, and the"
              "constitution says to report that rather than estimate it."))
    (doseq [c (filter :skip chosen)]
      (s/eprintln (format "... skipping %s (%s): %s"
                          (:project c) (:testTask c) (:skip c))))
    (let [runnable (filterv (complement :skip) chosen)]
      (when (empty? runnable)
        (s/die! "Nothing to mutate: every candidate was skipped for a reason above."
                ""
                "The usual one is a module with no tests of its own. That is a"
                "finding, not an error - but it is not a mutation measurement"
                "either, so this tool reports no number rather than reporting zero."
                ""
                "Run mutate4kotlin --scan to see the candidates and their reasons."))
      (mapv (fn [c]
              {:label (str (:project c) " " (:testTask c))
               :xml (run-pitest-cli! c exclusions workers)})
            runnable))))

;; --------------------------------------------------------------------- report

(defn report!
  "The one place a mutation number is printed, whichever way PIT was run."
  [reports {:keys [top update? exclusions]}]
  (let [sources (s/hand-written-sources)
        tagged (vec (for [r reports
                          m (mutations (:xml r))]
                      (assoc m
                             :report (:label r)
                             ;; Two questions, asked in this order because the
                             ;; cheap one settles most of it: is the whole class
                             ;; generated, and failing that, did a compiler plugin
                             ;; put this instruction inside a class that is ours.
                             :generated (or (s/generated-class sources m)
                                            (compiler-plumbing m)))))
        ;; Generated code is left out of the headline for the same reason coverage
        ;; leaves it out: nobody wrote it, so a surviving mutant in it is not a
        ;; missing test. The Compose compiler alone contributes enough of them to
        ;; move the number several points on a small module.
        ms (filterv (complement :generated) tagged)
        generated (filterv :generated tagged)
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
    (when (seq generated)
      (println (format "  %-24s %d mutant%s excluded"
                       "not hand-written" (count generated)
                       (if (= 1 (count generated)) "" "s")))
      (doseq [[reason n] (sort-by (comp - val) (frequencies (map :generated generated)))]
        (println (format "  %-24s   %3d  %s" "" n (excluded-reason reason))))
      ;; Printed because a number that leaves something out has to say so, next to
      ;; the number that does not. Anyone comparing this run to PIT's own HTML
      ;; report needs both.
      (let [every (summarize tagged)]
        (println (format "  %-24s mutation coverage %.1f%%  (%d mutant%s)"
                         "every mutant" (:mutation-coverage every) (:total every)
                         (if (= 1 (:total every)) "" "s")))))
    ;; One line per report when there is more than one, so two test tasks over
    ;; overlapping code are visible rather than silently summed.
    (when (> (count reports) 1)
      (println)
      (doseq [r reports]
        (let [own (summarize (filter #(= (:label r) (:report %)) ms))]
          (println (format "  %-40s %d mutant(s), %d killed"
                           (:label r) (:total own) (:killed own))))))
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
    (println "iosMain; they are not a substitute for this measurement.")))

;; --------------------------------------------------------------------- main

(defn -main [& args]
  (let [{:keys [flags]} (s/parse-args args #{"--top" "--max-workers" "--workers"
                                             "--module"})
        top (try (Integer/parseInt (str (get flags "--top" "25"))) (catch Exception _ 25))
        workers (s/worker-limit flags)
        scan? (boolean (get flags "--scan"))
        update? (boolean (get flags "--update-manifest"))
        module (let [m (get flags "--module")] (when (string? m) m))
        exclusions (parse-exclusions)]
    (when scan?
      (do-scan exclusions))
    (let [state (pitest-task-state)]
      (when (#{:shadowed :unknown} state)
        (refuse-shadowed-task! state))
      (when (and module (= :plugin state))
        (s/eprintln "..." "--module ignored: the pitest plugin's task decides its"
                    "own scope"))
      (fs/create-dirs (fs/path (s/worktree-root) state-dirname))
      (report! (if (= :plugin state)
                 (run-plugin-task! workers)
                 (run-cli! module exclusions workers))
               {:top top :update? update? :exclusions exclusions})
      ;; Survivors are work to do, not a broken tool.
      (System/exit 0))))

(apply -main *command-line-args*)
