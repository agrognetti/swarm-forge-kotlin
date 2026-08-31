(ns swarmforge.kotlin-support-test
  "Tests for the glob layer the Kotlin constitution tools share.

  Every one of these guards the same defect: a `**/`-anchored pattern requires
  at least one directory segment, so a single-module project that keeps `build`
  and `src` beside `settings.gradle.kts` matched nothing. Five of the six tools
  reported 'nothing found' for a project that had everything, and two of them
  did it without raising an error at all."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def kotlin-scripts-dir (str (fs/path repo-root "swarmforge" "scripts" "kotlin")))

(cp/add-classpath kotlin-scripts-dir)
(require '[sfk.support :as s])

;; ------------------------------------------------------------------- fixtures

(defn write! [path content]
  (fs/create-dirs (fs/parent path))
  (spit (str path) content))

(defn touch! [path]
  (write! path ""))

(defn- relative-set [root paths]
  (set (map #(str (fs/relativize root %)) paths)))

(defn with-tree
  "Build a throwaway tree from a seq of relative file paths and hand its root to
  body-fn. Directories are created for every parent, so a path ending in a
  directory name should be given a child."
  [paths body-fn]
  (let [root (fs/create-temp-dir {:prefix "swarmforge-kotlin-glob."})]
    (try
      (doseq [p paths] (touch! (fs/path root p)))
      (body-fn root)
      (finally (fs/delete-tree root)))))

;; -------------------------------------------------------------- glob semantics

(deftest any-depth-finds-reports-at-the-root-and-in-modules
  (with-tree ["build/reports/kover/report.xml"
              "mod/build/reports/kover/report.xml"
              "group/mod/build/reports/kover/report.xml"]
    (fn [root]
      (let [found (relative-set
                   root
                   (fs/glob root (str s/any-depth "build/reports/kover/*.xml")))]
        (testing "the root-level report is the one the old `**/` pattern missed"
          (is (contains? found "build/reports/kover/report.xml")))
        (testing "module reports keep working"
          (is (contains? found "mod/build/reports/kover/report.xml"))
          (is (contains? found "group/mod/build/reports/kover/report.xml")))
        (is (= 3 (count found)))))))

(deftest any-depth-does-not-match-a-similarly-named-sibling
  ;; `**build/...` would also satisfy the root case, and would quietly pick up
  ;; an unrelated directory. This is why the empty brace alternative is used.
  (with-tree ["build/reports/kover/report.xml"
              "notbuild/reports/kover/report.xml"
              "mod/notbuild/reports/kover/report.xml"]
    (fn [root]
      (is (= #{"build/reports/kover/report.xml"}
             (relative-set
              root
              (fs/glob root (str s/any-depth "build/reports/kover/*.xml"))))))))

(deftest any-depth-applies-to-interior-segments-too
  ;; PIT nests mutations.xml under a timestamp directory unless the build turns
  ;; timestampedReports off, and the module may be the root project. All four
  ;; combinations are legitimate; the old pair of patterns found only two.
  (with-tree ["build/reports/pitest/mutations.xml"
              "build/reports/pitest/202501010000/mutations.xml"
              "mod/build/reports/pitest/mutations.xml"
              "mod/build/reports/pitest/202501010000/mutations.xml"]
    (fn [root]
      (is (= 4 (count (fs/glob root (str s/any-depth "build/reports/pitest/"
                                         s/any-depth "mutations.xml"))))))))

;; ------------------------------------------------------------- source globbing

(deftest glob-sources-finds-source-sets-at-every-depth
  (with-tree ["src/commonMain/kotlin/App.kt"
              "mod/src/commonMain/kotlin/App.kt"
              "group/mod/src/commonMain/kotlin/App.kt"]
    (fn [root]
      (is (= #{"src/commonMain/kotlin"
               "mod/src/commonMain/kotlin"
               "group/mod/src/commonMain/kotlin"}
             (relative-set root (s/glob-sources root "src/commonMain/kotlin")))))))

(deftest glob-sources-skips-generated-and-vendored-output
  ;; Reaching the root also reaches into build output, which a `*/`-anchored
  ;; pattern never could. Compose Resources and Kover both generate source
  ;; trees under build/, and measuring generated code is not measuring the code.
  (with-tree ["src/commonMain/kotlin/App.kt"
              "build/generated/src/commonMain/kotlin/Res.kt"
              "mod/build/generated/src/commonMain/kotlin/Res.kt"
              ".gradle/src/commonMain/kotlin/Junk.kt"
              "node_modules/pkg/src/commonMain/kotlin/Junk.kt"]
    (fn [root]
      (is (= #{"src/commonMain/kotlin"}
             (relative-set root (s/glob-sources root "src/commonMain/kotlin")))))))

(deftest under-ignored-dir?-looks-at-every-segment-not-just-the-first
  (with-tree ["mod/build/generated/App.kt" "mod/src/App.kt"]
    (fn [root]
      (is (s/under-ignored-dir? root (fs/path root "mod/build/generated/App.kt")))
      (is (not (s/under-ignored-dir? root (fs/path root "mod/src/App.kt")))))))

;; ----------------------------------------------------- end to end through git

(defn- init-repo! [root]
  (doseq [args [["git" "init" "-q"]
                ["git" "config" "user.email" "test@example.com"]
                ["git" "config" "user.name" "Test User"]]]
    (apply sh/sh (concat args [:dir (str root)]))))

(defn- source-dirs-in
  "source-dirs resolves the worktree through git in the process working
  directory, so it can only be exercised by running bb inside the fixture."
  [root]
  (let [{:keys [exit out err]}
        (sh/sh "bb" "--classpath" kotlin-scripts-dir
               "-e" "(require '[sfk.support :as s]) (prn (s/source-dirs))"
               :dir (str root)
               :env (merge (into {} (System/getenv))
                           {"GIT_CONFIG_NOSYSTEM" "1"}))]
    (when-not (zero? exit)
      (throw (ex-info (str "bb failed: " err) {:exit exit})))
    (read-string out)))

(deftest source-dirs-finds-a-root-level-src-in-a-single-module-project
  ;; The layout that broke detekt and dry4kotlin silently: no subproject, so
  ;; `**/src` matched nothing, so both tools concluded there was no code.
  (with-tree ["settings.gradle.kts" "src/commonMain/kotlin/App.kt"]
    (fn [root]
      (init-repo! root)
      (let [dirs (map #(str (fs/relativize (fs/real-path root) (fs/real-path %)))
                      (source-dirs-in root))]
        (is (= ["src"] dirs))))))

(deftest source-dirs-still-finds-module-src-and-still-skips-build
  (with-tree ["settings.gradle.kts"
              "shared/src/commonMain/kotlin/App.kt"
              "shared/build/generated/src/commonMain/kotlin/Res.kt"]
    (fn [root]
      (init-repo! root)
      (let [dirs (map #(str (fs/relativize (fs/real-path root) (fs/real-path %)))
                      (source-dirs-in root))]
        (is (= ["shared/src"] dirs))))))

;; ------------------------------------------------- generated code vs. the author

(defn- sources-in
  "Absolute paths, the shape hand-written-sources returns."
  [root & relatives]
  (set (map #(str (fs/path root %)) relatives)))

(deftest generated-class-keeps-code-that-exists-under-src
  (with-tree ["shared/src/commonMain/kotlin/com/example/Greeting.kt"]
    (fn [root]
      (let [sources (sources-in root "shared/src/commonMain/kotlin/com/example/Greeting.kt")]
        (is (nil? (s/generated-class sources {:package "com/example"
                                              :class "com/example/Greeting"
                                              :source "Greeting.kt"})))))))

(deftest generated-class-flags-code-that-exists-only-under-build
  ;; The measured defect: Compose Resources put 46 of 56 lines into the report of
  ;; a module whose author wrote 10, and coverage read 12.5% instead of 70%.
  ;; Nothing here names Compose: the criterion is that no `src` tree has the file.
  (with-tree ["shared/src/commonMain/kotlin/com/example/Greeting.kt"
              "shared/build/generated/compose/resourceGenerator/kotlin/poc/generated/resources/Res.kt"]
    (fn [root]
      (let [sources (sources-in root "shared/src/commonMain/kotlin/com/example/Greeting.kt")]
        (is (= :outside-source-tree
               (s/generated-class sources {:package "poc/generated/resources"
                                           :class "poc/generated/resources/Res"
                                           :source "Res.kt"})))))))

(deftest generated-class-flags-the-compose-lambda-holder
  ;; This one reports the author's own file as its source, so the src-tree
  ;; criterion cannot see it. It carried 19 of the 46 excluded lines.
  (with-tree ["shared/src/commonMain/kotlin/com/example/App.kt"]
    (fn [root]
      (let [sources (sources-in root "shared/src/commonMain/kotlin/com/example/App.kt")]
        (is (= :compose-lambda-holder
               (s/generated-class sources {:package "com/example"
                                           :class "com/example/ComposableSingletons$AppKt"
                                           :source "App.kt"})))))))

(deftest generated-class-matches-on-the-package-not-the-bare-file-name
  ;; A hand-written Res.kt in one module must not vouch for a generated Res.kt in
  ;; another. Matching bare file names would have let it.
  (with-tree ["app/src/commonMain/kotlin/com/example/Res.kt"]
    (fn [root]
      (let [sources (sources-in root "app/src/commonMain/kotlin/com/example/Res.kt")]
        (is (nil? (s/generated-class sources {:package "com/example"
                                              :class "com/example/Res"
                                              :source "Res.kt"})))
        (is (= :outside-source-tree
               (s/generated-class sources {:package "poc/generated/resources"
                                           :class "poc/generated/resources/Res"
                                           :source "Res.kt"})))))))

(deftest generated-class-flags-a-class-with-no-source-file-name-at-all
  (with-tree ["shared/src/commonMain/kotlin/com/example/Greeting.kt"]
    (fn [root]
      (is (= :outside-source-tree
             (s/generated-class (sources-in root "shared/src/commonMain/kotlin/com/example/Greeting.kt")
                                {:package "com/example" :class "com/example/Mystery"}))))))

(deftest declares-composable?-reads-the-file-it-resolved
  (with-tree ["shared/src/commonMain/kotlin/com/example/App.kt"
              "shared/src/commonMain/kotlin/com/example/Greeting.kt"]
    (fn [root]
      (write! (fs/path root "shared/src/commonMain/kotlin/com/example/App.kt")
              "@Composable\nfun App() {}\n")
      (let [sources (sources-in root
                                "shared/src/commonMain/kotlin/com/example/App.kt"
                                "shared/src/commonMain/kotlin/com/example/Greeting.kt")]
        (is (s/declares-composable? sources "com/example" "App.kt"))
        (is (not (s/declares-composable? sources "com/example" "Greeting.kt")))
        (testing "a file that does not resolve is not a composable file"
          (is (not (s/declares-composable? sources "poc/generated/resources" "Res.kt"))))))))

;; -------------------------------------------------- kover, end to end through bb

(def ^:private jacoco-report
  "A minimal Kover/JaCoCo report shaped like the one measured on a real Compose
  module: the author's two lines are covered, and a generated class carries eight
  uncovered ones. Blended that is 2/10 = 20%; the author's code is 100%.

  The BRANCH counters are here because that is what a real Kover report has:
  it emits no COMPLEXITY counter at all, so crap4kotlin derives complexity from
  branches and marks every row `[cc derived]`."
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<report name=\"shared\">\n"
       "  <package name=\"com/example\">\n"
       "    <class name=\"com/example/Greeting\" sourcefilename=\"Greeting.kt\">\n"
       "      <method name=\"greet\" line=\"4\">\n"
       "        <counter type=\"BRANCH\" missed=\"0\" covered=\"0\"/>\n"
       "        <counter type=\"LINE\" missed=\"0\" covered=\"2\"/>\n"
       "      </method>\n"
       "      <counter type=\"LINE\" missed=\"0\" covered=\"2\"/>\n"
       "    </class>\n"
       "  </package>\n"
       "  <package name=\"poc/generated/resources\">\n"
       "    <class name=\"poc/generated/resources/Res\" sourcefilename=\"Res.kt\">\n"
       "      <method name=\"getUri\" line=\"9\">\n"
       "        <counter type=\"BRANCH\" missed=\"0\" covered=\"0\"/>\n"
       "        <counter type=\"LINE\" missed=\"8\" covered=\"0\"/>\n"
       "      </method>\n"
       "      <counter type=\"LINE\" missed=\"8\" covered=\"0\"/>\n"
       "    </class>\n"
       "  </package>\n"
       "  <counter type=\"LINE\" missed=\"8\" covered=\"2\"/>\n"
       "</report>\n"))

(defn- run-tool
  "Run a Kotlin tool inside a fixture. Tools resolve the worktree through git in
  the process working directory, so they can only be exercised as a subprocess."
  [root tool & args]
  (apply sh/sh "bb" (str (fs/path kotlin-scripts-dir tool)) (concat args [:dir (str root)])))

(deftest kover-reports-the-authors-coverage-not-the-generators
  (with-tree ["settings.gradle.kts"
              "shared/src/commonMain/kotlin/com/example/Greeting.kt"
              "shared/build/generated/compose/resourceGenerator/kotlin/poc/generated/resources/Res.kt"]
    (fn [root]
      (init-repo! root)
      (write! (fs/path root "shared/build/reports/kover/report.xml") jacoco-report)
      (let [{:keys [exit out]} (run-tool root "kover.bb" "--report-only")]
        (is (zero? exit))
        (testing "the headline is the author's code"
          (is (str/includes? out "your code                line 100.00%")))
        (testing "the generated class is named as excluded, not silently dropped"
          (is (str/includes? out "1 class, 8 lines excluded"))
          (is (str/includes? out "no source file under any src directory")))
        (testing "the number that includes it is still printed"
          (is (str/includes? out "every class              line  20.00%")))))))

(deftest kover-leaves-a-project-without-generated-code-alone
  ;; The exclusion must be invisible where there is nothing to exclude: a plain
  ;; Kotlin module should not grow a breakdown it has no use for.
  (with-tree ["settings.gradle.kts" "src/commonMain/kotlin/com/example/Greeting.kt"]
    (fn [root]
      (init-repo! root)
      (write! (fs/path root "build/reports/kover/report.xml")
              (str/replace jacoco-report
                           #"(?s)  <package name=\"poc.*?</package>\n" ""))
      (let [{:keys [exit out]} (run-tool root "kover.bb" "--report-only")]
        (is (zero? exit))
        (is (str/includes? out "your code                line 100.00%"))
        (is (not (str/includes? out "excluded")))
        (is (not (str/includes? out "every class")))
        (testing "and no Compose breakdown either"
          (is (not (str/includes? out "@Composable   line"))))))))

(def ^:private composable-report
  "A composable file that is fully covered beside plain logic that is half
  covered. Measured on a real module: a Robolectric compose test in the Android
  host test source set took the @Composable file from 0 of 3 lines to 3 of 3."
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<report name=\"shared\">\n"
       "  <package name=\"com/example\">\n"
       "    <class name=\"com/example/AppKt\" sourcefilename=\"App.kt\">\n"
       "      <counter type=\"LINE\" missed=\"0\" covered=\"3\"/>\n"
       "    </class>\n"
       "    <class name=\"com/example/Greeting\" sourcefilename=\"Greeting.kt\">\n"
       "      <counter type=\"LINE\" missed=\"1\" covered=\"1\"/>\n"
       "    </class>\n"
       "  </package>\n"
       "</report>\n"))

(deftest kover-counts-a-covered-composable-as-covered
  ;; The split exists so neither half hides behind the other, not to excuse the
  ;; UI half. A composable renders on the JVM under Robolectric, so its tier is
  ;; an ordinary coverage figure and must move when tests are written for it.
  (with-tree ["settings.gradle.kts"
              "shared/src/commonMain/kotlin/com/example/Greeting.kt"]
    (fn [root]
      (init-repo! root)
      (write! (fs/path root "shared/src/commonMain/kotlin/com/example/App.kt")
              "@Composable\nfun App() {}\n")
      (write! (fs/path root "shared/build/reports/kover/report.xml") composable-report)
      (let [{:keys [exit out]} (run-tool root "kover.bb" "--report-only")]
        (is (zero? exit))
        (is (str/includes? out "your code                line  80.00%"))
        (is (str/includes? out "plain Kotlin           line  50.00%"))
        (is (str/includes? out "declares @Composable   line 100.00%"))
        (testing "and the output does not tell the reader it cannot be covered"
          (is (not (str/includes? out "no tool in this constitution runs"))))))))

(deftest crap4kotlin-ranks-the-authors-methods-and-says-what-it-hid
  ;; Before this fix the risk list was 20 rows on this project, 14 of them
  ;; Compose Resources accessors. An agent told to attack the top of the list
  ;; would have started with code it must not edit.
  (with-tree ["settings.gradle.kts"
              "shared/src/commonMain/kotlin/com/example/Greeting.kt"
              "shared/build/generated/compose/resourceGenerator/kotlin/poc/generated/resources/Res.kt"]
    (fn [root]
      (init-repo! root)
      (write! (fs/path root "shared/build/reports/kover/report.xml") jacoco-report)
      (let [{:keys [exit out]} (run-tool root "crap4kotlin.bb")]
        (is (zero? exit))
        (is (str/includes? out "Methods scored: 1   generated hidden: 1"))
        (is (str/includes? out "1  no source file under any src directory"))
        (is (str/includes? out "Greeting.greet"))
        (testing "the generated accessor is off the list"
          (is (not (str/includes? out "Res.getUri"))))
        (testing "and --include-generated puts it back"
          (let [{:keys [out]} (run-tool root "crap4kotlin.bb" "--include-generated")]
            (is (str/includes? out "Res.getUri"))
            (is (str/includes? out "Methods scored: 2"))))))))

(deftest crap4kotlin-says-so-instead-of-printing-an-empty-table
  ;; A module that is nothing but generated code is a real case. The old code
  ;; printed an empty table and then `Highest CRAP: nu at ?.null`, and exited 0.
  (with-tree ["settings.gradle.kts"
              "shared/build/generated/compose/resourceGenerator/kotlin/poc/generated/resources/Res.kt"]
    (fn [root]
      (init-repo! root)
      (write! (fs/path root "shared/build/reports/kover/report.xml")
              (str/replace jacoco-report
                           #"(?s)  <package name=\"com/example\".*?</package>\n" ""))
      (let [{:keys [exit out]} (run-tool root "crap4kotlin.bb")]
        (is (zero? exit))
        (is (str/includes? out "No hand-written method scores at or above the threshold"))
        (is (str/includes? out "--include-generated"))))))

;; --------------------------------------- a task's name is not its identity

(defn- fake-gradlew!
  "A stand-in wrapper that answers `help --task` and nothing else, so the task
  identity check can be exercised without a real Gradle build.

  The block it prints was copied from Gradle 9.1.0 on a real module: a parser
  tested against invented text proves nothing about the text it has to read.
  `types` is the seq of classes to report, `[]` to resolve the task while
  printing no Type section, and nil to answer that no such task exists.

  Every other invocation fails, so a tool that runs a task it did not verify
  shows up in the output instead of passing quietly. `run-ok?` opts out for the
  one case that needs it: reaching the reporting code with a report the test
  wrote itself, which is the only way to exercise it without a real PIT run."
  ([root task types] (fake-gradlew! root task types false))
  ([root task types run-ok?]
   (let [path (fs/path root "gradlew")
         not-found (str "echo \"> Task '$want' not found in root project"
                        " 'fixture' and its subprojects.\" >&2\nexit 1\n")
         body (if (nil? types)
                not-found
                (str "cat <<'SFKEOF'\n"
                     "Detailed task information for " task "\n\n"
                     "Path\n"
                     "     :shared:" task "\n\n"
                     (when (seq types)
                       (str "Type\n"
                            (str/join (for [t types]
                                        (str "     " (last (str/split t #"\."))
                                             " (" t ")\n")))
                            "\n"))
                     "Options\n"
                     "     --rerun     Causes the task to be re-run even if up-to-date.\n\n"
                     ;; Prose ending in a parenthesised class name. A pattern loose
                     ;; enough to span sections would read this as the task's type.
                     "Description\n"
                     "     Runs it (org.example.NotAType)\n\n"
                     "Group\n"
                     "     verification\n"
                     "SFKEOF\n"
                     "exit 0\n"))]
     (write! path
             (str "#!/bin/sh\n"
                  "want=\n"
                  "for a in \"$@\"; do\n"
                  "  if [ \"$flag\" = 1 ]; then want=$a; flag=; fi\n"
                  "  if [ \"$a\" = --task ]; then flag=1; fi\n"
                  "  if [ \"$a\" = \"" task "\" ]; then asked=1; fi\n"
                  "done\n"
                  (when run-ok?
                    (str "if [ -z \"$want\" ] && [ \"$asked\" = 1 ]; then exit 0; fi\n"))
                  "if [ \"$want\" != \"" task "\" ]; then\n"
                  not-found
                  "fi\n"
                  body))
     (fs/set-posix-file-permissions path "rwxr-xr-x"))))

(def ^:private pitest-task "info.solidsoft.gradle.pitest.PitestTask")

(deftest mutate4kotlin-refuses-a-look-alike-pitest-task
  ;; The defect this replaces: the gate was `has-task? "pitest"`, which any
  ;; JavaExec of that name satisfied. mutate4kotlin then ran it and printed its
  ;; output as a mutation measurement - the project-local proxy the constitution
  ;; forbids, produced without a single warning. Measured on the real project:
  ;; `help --task pitest` exits 0 there for a hand-written task.
  (with-tree ["settings.gradle.kts"]
    (fn [root]
      (init-repo! root)
      (fake-gradlew! root "pitest" ["org.gradle.api.tasks.JavaExec"])
      (let [{:keys [exit err]} (run-tool root "mutate4kotlin.bb")]
        (is (not (zero? exit)))
        (testing "it names what it found and what it wanted"
          (is (str/includes? err "it is not the pitest plugin's task"))
          (is (str/includes? err "found:    org.gradle.api.tasks.JavaExec"))
          (is (str/includes? err "expected: a class under info.solidsoft.gradle.pitest.")))
        (testing "it quotes the rule rather than just refusing"
          (is (str/includes? err "Do not invent project-local")))
        (testing "and it says the task is not needed, so deleting it is the fix"
          (is (str/includes? err "Delete that task. This tool does not need it")))
        (testing "and it stops before running anything"
          (is (not (str/includes? err "Mutation testing could not complete")))
          (is (not (str/includes? err "asking gradle for the classpath"))))))))

(deftest mutate4kotlin-runs-the-plugins-own-pitest-task
  ;; The gate has to open for the real task, or the fix is just a new false
  ;; negative. The fake fails every invocation that is not `help --task`, so
  ;; reaching the run failure is the proof that the identity check passed.
  (with-tree ["settings.gradle.kts"]
    (fn [root]
      (init-repo! root)
      (fake-gradlew! root "pitest" [pitest-task])
      (let [{:keys [exit err]} (run-tool root "mutate4kotlin.bb")]
        (is (not (zero? exit)))
        (is (str/includes? err "Mutation testing could not complete"))
        (is (not (str/includes? err "is not the info.solidsoft.pitest task")))))))

(deftest scan-names-the-class-behind-the-task-instead-of-answering-yes
  (with-tree ["settings.gradle.kts"]
    (fn [root]
      (init-repo! root)
      (testing "the plugin's own task"
        (fake-gradlew! root "pitest" [pitest-task])
        (let [{:keys [exit out]} (run-tool root "mutate4kotlin.bb" "--scan")]
          (is (zero? exit))
          (is (str/includes? out (str "pitest task: present, " pitest-task)))
          (testing "and prose in the Description is not mistaken for a type"
            (is (not (str/includes? out "NotAType"))))))
      (testing "a look-alike is called one, in the scan that performs no build"
        (fake-gradlew! root "pitest" ["org.gradle.api.tasks.JavaExec"])
        (let [{:keys [exit out]} (run-tool root "mutate4kotlin.bb" "--scan")]
          (is (zero? exit))
          (is (str/includes? out "SHADOWED by org.gradle.api.tasks.JavaExec"))))
      (testing "and an absent task is still reported as absent"
        (fake-gradlew! root "pitest" nil)
        (let [{:keys [exit out]} (run-tool root "mutate4kotlin.bb" "--scan")]
          (is (zero? exit))
          (is (str/includes? out "pitest task: absent")))))))

(deftest no-pitest-task-is-the-command-line-path-not-a-refusal
  ;; Verified in gradle-pitest-plugin-1.19.0.jar: apply() wires the extension and
  ;; the task inside plugins.withType(JavaPlugin). KMP never applies the java
  ;; plugin, so the plugin registers nothing there - which means "add the plugin"
  ;; was advice that could not work, and refusing was refusing the normal case.
  ;; PIT needs a classpath, not a source set, so the tool asks Gradle for one and
  ;; drives PIT itself.
  (with-tree ["settings.gradle.kts"]
    (fn [root]
      (init-repo! root)
      (fake-gradlew! root "pitest" nil)
      (let [{:keys [exit err]} (run-tool root "mutate4kotlin.bb")]
        (testing "it goes to Gradle for the classpath instead of giving up"
          (is (str/includes? err "asking gradle for the classpath and code paths")))
        (testing "and it does not send anyone to change a build script"
          (is (not (str/includes? err "does not exist in this project")))
          (is (not (str/includes? err "Add it to the module's build script"))))
        (testing "the fake wrapper fails that build, so the run still ends here"
          (is (not (zero? exit)))
          (is (str/includes? err "failure here is a failure in the project")))))))

(defn- dump-tsv
  "One report file shaped the way the init script writes it: key and value
  separated by a tab, and lists of paths joined with the platform path separator."
  [pairs]
  (str (str/join "\n"
                 (for [[k v] pairs]
                   (str k "\t" (if (coll? v)
                                 (str/join java.io.File/pathSeparator (map str v))
                                 (str v)))))
       "\n"))

(deftest scan-lists-the-candidate-runs-and-why-one-of-them-cannot-run
  ;; Measured on the real project: :shared compiles four test classes and
  ;; :androidApp has a JVM test task with nothing in it. PIT started against an
  ;; empty suite fails, and that failure reads like a broken build rather than an
  ;; empty source set, so the tool says which it is before running anything.
  (with-tree ["settings.gradle.kts"
              "androidApp/src/main/kotlin/com/example/MainActivity.kt"]
    (fn [root]
      (init-repo! root)
      (fake-gradlew! root "pitest" nil)
      ;; A commented-out package line above the real one. A regex over the whole
      ;; file would read the first `package` word it saw, wherever it sat.
      (write! (fs/path root "shared/src/commonMain/kotlin/com/example/Greeting.kt")
              "// package com.example.commented.out\npackage com.example\n")
      (touch! (fs/path root "shared/build/classes/kotlin/android/hostTest"
                       "com/example/GreetingTest.class"))
      ;; The Android module's test class directory exists and is empty, which is
      ;; exactly the state a module with no tests of its own is in.
      (fs/create-dirs (fs/path root "androidApp/build/intermediates/javac/classes"))
      (let [dump (fs/path root ".swarmforge" "kotlin" "mutation" "pitest.d")]
        (write! (fs/path dump "shared.0.tsv")
                (dump-tsv [["project" ":shared"]
                           ["projectDir" (fs/path root "shared")]
                           ["buildDir" (fs/path root "shared/build")]
                           ["testEngine" "junit4"]
                           ["testTask" ":shared:testAndroidHostTest"]
                           ["testClassesDirs"
                            [(fs/path root "shared/build/classes/kotlin/android/hostTest")]]
                           ["mutableCodePaths" [(fs/path root "shared/build/classes.jar")]]
                           ["sourceDirs" [(fs/path root "shared/src/commonMain/kotlin")]]]))
        (write! (fs/path dump "androidApp.0.tsv")
                (dump-tsv [["project" ":androidApp"]
                           ["projectDir" (fs/path root "androidApp")]
                           ["buildDir" (fs/path root "androidApp/build")]
                           ["testEngine" "junit4"]
                           ["testTask" ":androidApp:testDebugUnitTest"]
                           ["testClassesDirs"
                            [(fs/path root "androidApp/build/intermediates/javac/classes")]]
                           ["mutableCodePaths"
                            [(fs/path root "androidApp/build/classes.jar")]]
                           ["sourceDirs" [(fs/path root "androidApp/src")]]])))
      (let [{:keys [exit out]} (run-tool root "mutate4kotlin.bb" "--scan")]
        (is (zero? exit))
        (testing "an absent plugin task is reported as the command-line path"
          (is (str/includes? out "pitest task: absent"))
          (is (str/includes? out "mode: PIT's command line")))
        (testing "the runnable candidate names its test count and its scope"
          (is (str/includes? out ":shared :shared:testAndroidHostTest"))
          (is (str/includes? out "1 test class(es), com.example.*")))
        (testing "and the package from the commented-out line is not in scope"
          (is (not (str/includes? out "com.example.commented.out"))))
        (testing "the module with no tests says so instead of being dropped"
          (is (str/includes? out ":androidApp :androidApp:testDebugUnitTest"))
          (is (str/includes? out "no compiled test classes")))
        (testing "and the scan still runs no build of its own"
          (is (str/includes? out "Scan performs no build")))))))

(def ^:private pitest-report
  "A PIT report shaped like the one measured on a real Compose module: one mutant
  in the author's own class, one in the lambda holder the Compose compiler hoists
  out of a composable, two in generated Compose Resources code, and two inside a
  composable the author wrote - one on a call the Compose compiler inserted, one
  on the author's own `if`. Blended that is 1 of 6 killed; the author's code is 1
  of 2.

  The last two carry a methodDescription taking a Composer, because that is what
  the Compose compiler does to a composable's signature and the only place the
  rewriting is visible. They are the pair that matters: the first must be
  excluded, and the second must not, or the tool hides a missing test."
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<mutations partial=\"false\">\n"
       "<mutation detected=\"true\" status=\"KILLED\" numberOfTestsRun=\"2\">\n"
       "<sourceFile>Greeting.kt</sourceFile>\n"
       "<mutatedClass>com.example.Greeting</mutatedClass>\n"
       "<mutatedMethod>greet</mutatedMethod>\n"
       "<lineNumber>5</lineNumber>\n"
       "<mutator>org.pitest.mutationtest.engine.gregor.mutators.ReturnValsMutator</mutator>\n"
       "<description>replaced return value with null</description>\n"
       "</mutation>\n"
       "<mutation detected=\"false\" status=\"SURVIVED\" numberOfTestsRun=\"1\">\n"
       "<sourceFile>App.kt</sourceFile>\n"
       "<mutatedClass>com.example.ComposableSingletons$AppKt</mutatedClass>\n"
       "<mutatedMethod>getLambda-1</mutatedMethod>\n"
       "<lineNumber>12</lineNumber>\n"
       "<mutator>org.pitest.mutationtest.engine.gregor.mutators.ReturnValsMutator</mutator>\n"
       "<description>replaced return value with null</description>\n"
       "</mutation>\n"
       "<mutation detected=\"false\" status=\"NO_COVERAGE\" numberOfTestsRun=\"0\">\n"
       "<sourceFile>Res.kt</sourceFile>\n"
       "<mutatedClass>poc.generated.resources.Res</mutatedClass>\n"
       "<mutatedMethod>getUri</mutatedMethod>\n"
       "<lineNumber>9</lineNumber>\n"
       "<mutator>org.pitest.mutationtest.engine.gregor.mutators.ReturnValsMutator</mutator>\n"
       "<description>replaced return value with null</description>\n"
       "</mutation>\n"
       "<mutation detected=\"false\" status=\"NO_COVERAGE\" numberOfTestsRun=\"0\">\n"
       "<sourceFile>Res.kt</sourceFile>\n"
       "<mutatedClass>poc.generated.resources.Res</mutatedClass>\n"
       "<mutatedMethod>getPath</mutatedMethod>\n"
       "<lineNumber>14</lineNumber>\n"
       "<mutator>org.pitest.mutationtest.engine.gregor.mutators.ReturnValsMutator</mutator>\n"
       "<description>replaced return value with null</description>\n"
       "</mutation>\n"
       "<mutation detected=\"false\" status=\"NO_COVERAGE\" numberOfTestsRun=\"0\">\n"
       "<sourceFile>App.kt</sourceFile>\n"
       "<mutatedClass>com.example.AppKt</mutatedClass>\n"
       "<mutatedMethod>App</mutatedMethod>\n"
       "<methodDescription>(Landroidx/compose/runtime/Composer;I)V</methodDescription>\n"
       "<lineNumber>2</lineNumber>\n"
       "<mutator>org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator</mutator>\n"
       "<description>removed call to androidx/compose/runtime/ComposerKt::traceEventStart</description>\n"
       "</mutation>\n"
       "<mutation detected=\"false\" status=\"SURVIVED\" numberOfTestsRun=\"1\">\n"
       "<sourceFile>App.kt</sourceFile>\n"
       "<mutatedClass>com.example.AppKt</mutatedClass>\n"
       "<mutatedMethod>App</mutatedMethod>\n"
       "<methodDescription>(Landroidx/compose/runtime/Composer;I)V</methodDescription>\n"
       "<lineNumber>3</lineNumber>\n"
       "<mutator>org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator</mutator>\n"
       "<description>negated conditional</description>\n"
       "</mutation>\n"
       "</mutations>\n"))

(deftest mutate4kotlin-reports-the-authors-mutants-not-the-generators
  ;; The same policy coverage already applies. Without it, three of this report's
  ;; four mutants are survivors nobody wrote and no test can be asked to kill,
  ;; and they would be presented at the top of the list as missing tests.
  (with-tree ["settings.gradle.kts"
              "shared/src/commonMain/kotlin/com/example/Greeting.kt"
              "shared/build/generated/compose/resourceGenerator/kotlin/poc/generated/resources/Res.kt"]
    (fn [root]
      (init-repo! root)
      (write! (fs/path root "shared/src/commonMain/kotlin/com/example/App.kt")
              "@Composable\nfun App(on: Boolean) {\n    if (on) Text(\"hi\")\n}\n")
      (fake-gradlew! root "pitest" [pitest-task] true)
      (write! (fs/path root "shared/build/reports/pitest/mutations.xml") pitest-report)
      (let [{:keys [exit out]} (run-tool root "mutate4kotlin.bb")]
        (is (zero? exit))
        (testing "the headline counts the author's mutants"
          (is (str/includes? out "mutants: 2"))
          (is (str/includes? out "mutation coverage 50.0%")))
        (testing "the excluded ones are named by reason, not silently dropped"
          (is (str/includes? out "4 mutants excluded"))
          (is (str/includes? out "Compose compiler lambda holder"))
          (is (str/includes? out "no source file under any src directory"))
          (is (str/includes? out "call the Compose compiler inserted")))
        (testing "the number that includes them is still printed"
          (is (str/includes? out "every mutant"))
          (is (str/includes? out "mutation coverage 16.7%  (6 mutants)")))
        (testing "no generated mutant is presented as a missing test"
          (is (not (str/includes? out "ComposableSingletons")))
          (is (not (str/includes? out "traceEventStart"))))
        ;; The half of the policy that is easy to lose. The author's own `if` sits
        ;; inside a composable, in a method the Compose compiler rewrote, one line
        ;; away from an excluded mutant - and it is a missing test, so it has to be
        ;; reported. A filter that took the whole method would read as a cleaner
        ;; report and would be hiding work.
        (testing "but the author's own conditional inside a composable is"
          (is (str/includes? out "1 surviving mutant(s)"))
          (is (str/includes? out "negated conditional")))))))

(deftest a-task-whose-class-gradle-will-not-print-is-a-tool-defect
  ;; The third state. Accepting would restore the defect; blaming the project
  ;; would send an agent to change a build script that is fine.
  (with-tree ["settings.gradle.kts"]
    (fn [root]
      (init-repo! root)
      (fake-gradlew! root "pitest" [])
      (let [{:keys [exit err]} (run-tool root "mutate4kotlin.bb")]
        (is (not (zero? exit)))
        (is (str/includes? err "did not report its class"))
        (is (str/includes? err "tool defect, not a"))))))

(deftest the-weaker-question-still-answers-yes-to-a-look-alike
  ;; This pins down why the gate moved off has-task?, so the two questions cannot
  ;; be confused again. has-task? is still right for what aps-kotlin asks it -
  ;; does the build have a task by this name - and it was always wrong as a claim
  ;; about a plugin, which is what mutate4kotlin used it for.
  (with-tree ["settings.gradle.kts"]
    (fn [root]
      (init-repo! root)
      (fake-gradlew! root "pitest" ["org.gradle.api.tasks.JavaExec"])
      (let [{:keys [out]} (sh/sh "bb" "-cp" kotlin-scripts-dir "-e"
                                 (str "(require '[sfk.support :as s])"
                                      "(prn [(s/has-task? \"pitest\")"
                                      "      (:types (s/task-info \"pitest\"))])")
                                 :dir (str root))]
        (testing "existence: yes, which is exactly how the defect got through"
          (is (str/includes? out "true")))
        (testing "identity: a class that belongs to Gradle, not to the plugin"
          (is (str/includes? out "org.gradle.api.tasks.JavaExec"))
          (is (not (str/includes? out "info.solidsoft"))))))))

(deftest kover-refuses-a-look-alike-report-task
  ;; Same gate, same reason: a hand-written koverXmlReport could write the XML
  ;; that kover and crap4kotlin both read, and neither would know.
  (with-tree ["settings.gradle.kts"]
    (fn [root]
      (init-repo! root)
      (fake-gradlew! root "koverXmlReport" ["org.gradle.api.DefaultTask"])
      (let [{:keys [exit err]} (run-tool root "kover.bb")]
        (is (not (zero? exit)))
        (is (str/includes? err "is not the org.jetbrains.kotlinx.kover task"))
        (is (str/includes? err "expected: a class under kotlinx.kover."))))))

;; ------------------------------------------------------------- regression gate

(deftest no-kotlin-tool-globs-with-a-depth-blind-prefix
  ;; A guard rather than a behaviour test: it fails the moment any tool
  ;; reintroduces the pattern, including tools that do not exist yet.
  (let [sources (->> (fs/glob kotlin-scripts-dir "**{.bb,.clj}")
                     (map (juxt #(str (fs/relativize kotlin-scripts-dir %))
                                #(slurp (str %)))))
        offenders (for [[name text] sources
                        [line-no line] (map-indexed vector (str/split-lines text))
                        :when (and (str/includes? line "fs/glob")
                                   (re-find #"\"\*\*/" line))]
                    (str name ":" (inc line-no)))]
    (is (empty? offenders)
        (str "These globs skip the worktree root. Prefix the pattern with "
             "sfk.support/any-depth instead: " (str/join ", " offenders)))))
