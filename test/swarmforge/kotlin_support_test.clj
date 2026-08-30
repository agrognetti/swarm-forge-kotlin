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
