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

(defn touch! [path]
  (fs/create-dirs (fs/parent path))
  (spit (str path) ""))

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
