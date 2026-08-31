(ns sfk.support
  "Shared support for the SwarmForge Kotlin constitution tools.

  Every tool in this directory runs inside one agent worktree. Gradle is always
  invoked through the worktree's own wrapper so that roles never share a build
  directory."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- diagnostics

(defn eprintln [& parts]
  (binding [*out* *err*]
    (apply println parts)))

(defn die!
  "Print a remediation-oriented message to stderr and exit non-zero.
  Agents read this text, so every message names the fix, not just the fault."
  [& lines]
  (binding [*out* *err*]
    (doseq [line lines] (println line)))
  (System/exit 1))

(defn progress
  "Long tool runs must emit progress so an agent can tell work from a hang."
  [& parts]
  (apply eprintln "..." parts)
  (flush))

;; ------------------------------------------------------------------ locations

(defn- git [& args]
  (let [{:keys [exit out]} (apply p/sh "git" args)]
    (when (zero? exit) (str/trim out))))

(defn worktree-root
  "The checkout this agent works in. Gradle runs here."
  []
  (or (git "rev-parse" "--show-toplevel")
      (die! "Not inside a git checkout."
            "Constitution tools must run from the worktree assigned to your role.")))

(defn project-root
  "The main checkout. Shared state such as installed tool binaries lives here."
  []
  (if-let [common (git "rev-parse" "--git-common-dir")]
    (let [path (fs/path common)]
      (str (fs/parent (if (fs/absolute? path) path (fs/absolutize path)))))
    (worktree-root)))

;; Resolved while this file loads, not when the function runs. `*file*` is bound
;; during load and reverts to NO_SOURCE_PATH afterwards, so reading it inside the
;; function body would answer with the caller's file, or with nothing at all.
(def ^:private support-file (fs/absolutize *file*))

(defn templates-dir
  "Where the tools keep the files they hand to Gradle and to the project.
  Beside the scripts, so a worktree that has the scripts has the templates."
  [& parts]
  (let [dir (fs/parent (fs/parent support-file))
        path (apply fs/path dir "templates" parts)]
    (when-not (fs/exists? path)
      (die! (str "Missing template " path)
            "The Kotlin constitution tools ship templates next to the scripts."
            "Re-sync the worktree scripts."))
    (str path)))

(defn state-dir
  "Per-worktree scratch for tool output. Ignored by git."
  [& parts]
  (let [dir (apply fs/path (worktree-root) ".swarmforge" "kotlin" parts)]
    (fs/create-dirs dir)
    (str dir)))

(defn tools-dir
  "Shared cache for downloaded third-party tool distributions."
  [& parts]
  (let [dir (apply fs/path (project-root) ".swarmforge" "tools" parts)]
    (fs/create-dirs dir)
    (str dir)))

;; ------------------------------------------------------------------ downloads

(defn download!
  "Fetch url into dest, once. Third-party distributions land in tools-dir so
  every worktree shares one copy. Dies with remediation text on failure."
  [url dest label]
  (fs/create-dirs (fs/parent dest))
  (progress "downloading" label "(one time)")
  (let [tmp (str dest ".part")
        {:keys [exit err]} (p/sh "curl" "-fsSL" "--retry" "2" "-o" tmp url)]
    (when-not (zero? exit)
      (fs/delete-if-exists tmp)
      (die! (str "Could not download " label " from " url)
            (str/trim (str err))
            ""
            "This tool needs network access on first use only."
            "If this environment has no network, report it to the operator with"
            "pack_dashboard_request.sh clarify. Do not substitute another tool."))
    (fs/move tmp dest {:replace-existing true})
    (str dest)))

;; --------------------------------------------------------------------- gradle

(defn gradlew []
  (let [root (worktree-root)
        wrapper (fs/path root "gradlew")]
    (when-not (fs/exists? wrapper)
      (die! (str "No Gradle wrapper at " wrapper)
            "This tool only supports Gradle projects driven by ./gradlew."
            "If the project genuinely has no wrapper, report that to the operator"
            "with pack_dashboard_request.sh clarify. Do not install Gradle."))
    (when-not (fs/executable? wrapper)
      (fs/set-posix-file-permissions wrapper "rwxr-xr-x"))
    (str wrapper)))

(defn gradle
  "Run a Gradle build. Streams output so the agent sees progress.
  Returns {:exit n :out s}. Never throws on a failed build."
  [args]
  (let [cmd (into [(gradlew)] args)
        _ (eprintln "+" (str/join " " cmd))
        out (java.io.StringWriter.)
        {:keys [exit]} (p/sh cmd {:dir (worktree-root)
                                  :out out
                                  :err out})
        text (str out)]
    (print text)
    (flush)
    {:exit exit :out text}))

(defn gradle-or-die! [args explain]
  (let [{:keys [exit out]} (gradle args)]
    (when-not (zero? exit)
      (die! ""
            (str "Gradle failed: " (str/join " " args))
            explain
            ""
            "A build that fails to configure is not a measurement."
            "Fix the build before reporting any number."))
    out))

(defn- task-types
  "Every implementation class Gradle lists under a `Type` heading in the output
  of `help --task`. Gradle prints one such heading per distinct implementation,
  so a name that resolves in several projects can yield several classes.

  Read line by line rather than with one regex over the whole text: the
  Description and Group sections are free-form project prose, and a pattern
  loose enough to span them would happily accept a class name out of a sentence."
  [text]
  (loop [[line & more] (str/split-lines text) in-type? false found []]
    (cond
      (nil? line) found
      (re-matches #"Types?\s*" line) (recur more true found)
      (str/blank? line) (recur more false found)
      in-type? (recur more true
                      (into found
                            (map second)
                            (re-seq #"\(([\w$]+(?:\.[\w$]+)+)\)" line)))
      :else (recur more false found))))

(defn task-info
  "What Gradle knows about a task name, or nil when no task carries that name.
  Returns {:types [fully.qualified.Class ...]}.

  `help --task` prints the implementing class beside the name, so asking which
  task stands behind a name costs exactly what asking whether one exists costs.
  Existence is the weaker question, and it is not the one that matters: any
  build script can register a task called `pitest`."
  [task]
  (let [out (java.io.StringWriter.)
        {:keys [exit]} (p/sh [(gradlew) "-q" "help" "--task" task]
                             {:dir (worktree-root) :out out :err out})]
    (when (zero? exit)
      {:types (task-types (str out))})))

(defn has-task?
  "True when Gradle can resolve the named task, whatever that task turns out to
  be. Enough for choosing among test task names, which belong to the build
  rather than to a plugin. Not enough to conclude that a plugin is wired and
  will do the work its name promises: use require-task! for that."
  [task]
  (some? (task-info task)))

(defn require-task!
  "Return the first candidate task that exists and belongs to the plugin, or die
  with remediation text. `type-prefix` is the plugin's package.

  A task's name proves nothing on its own. Any build script can register a task
  called `pitest` or `koverXmlReport`, and a tool that gates on the name alone
  runs whatever it finds and reports the output as a measurement - the
  project-local proxy the constitution forbids. Gradle names the implementing
  class in the same call that answers the weaker question, so this asks about
  the class instead.

  The package rather than one class name, so that a class the plugin renames
  keeps working while an unrelated task still does not.

  `extra` lines are appended to the not-wired message by callers with more to
  say about applying the plugin in this kind of project."
  [candidates plugin-id type-prefix setup & extra]
  ;; A vector of pairs, not a map: the candidate order is the caller's
  ;; preference order and has to survive.
  (let [found (vec (keep (fn [t] (when-let [info (task-info t)] [t (:types info)]))
                         candidates))
        mine? (fn [[_ types]] (some #(str/starts-with? % type-prefix) types))
        [task types] (first found)
        primary (first candidates)]
    (cond
      (some mine? found)
      (first (first (filter mine? found)))

      ;; Gradle resolved the name but told us nothing about it. Guessing either
      ;; way would be worse than saying so: accepting restores the defect this
      ;; check exists to prevent, and rejecting silently blames the project for a
      ;; change in Gradle's output.
      (and (seq found) (every? (comp empty? second) found))
      (die! (str "Gradle resolved task '" task "' but did not report its class.")
            ""
            "This tool identifies a plugin's task by its implementing class,"
            "because any build script can register a task by that name. Without"
            "the class it cannot tell the plugin's task from a look-alike, and it"
            "will not report a number it cannot attribute."
            ""
            "That most likely means the output format of this command changed:"
            (str "  ./gradlew help --task " task)
            ""
            "Run it yourself and report what it prints to the operator with"
            "pack_dashboard_request.sh clarify. This is a tool defect, not a"
            "defect in the project build.")

      ;; A name resolved, but to something else. Refusing is the whole point: the
      ;; alternative is running a stranger's task and publishing its output.
      (seq found)
      (apply die!
             (concat
              [(str "Gradle task '" task "' exists, but it is not the "
                    plugin-id " task.")
               ""
               (str "  found:    " (str/join ", " types))
               (str "  expected: a class under " type-prefix)
               ""
               "Something in this build registers a task under that name. This tool"
               "will not run it, because its output would be reported as a"
               "measurement and the constitution forbids that:"
               ""
               "  \"Do not invent project-local CRAP, DRY, mutation, or coverage"
               "   proxies.\""
               ""
               "See it for yourself:"
               (str "  ./gradlew help --task " task)
               ""
               "Either apply the real plugin, or remove the task shadowing its name."
               ""]
              extra
              [""
               "If your role does not own the build configuration, ask the operator"
               "with pack_dashboard_request.sh clarify."]))

      :else
      (apply die!
             (concat
              [(str "Gradle task '" primary "' does not exist in this project.")
               (str "The '" plugin-id "' plugin is either not applied, or applied and")
               "registering nothing in this module."
               ""
               "Add it to the module's build script:"
               ""
               setup]
              extra
              [""
               "This is a project build change. If your role does not own the build"
               "configuration, ask the operator with pack_dashboard_request.sh clarify."
               "Do not substitute an estimate for the missing tool."])))))

;; ----------------------------------------------------------------- arg helper

(defn parse-args
  "Minimal flag parser. Returns {:flags {..} :positional [..]}.
  Unknown flags are kept so wrapper-injected flags never break a tool."
  [args known-with-value]
  (loop [[a & more] args flags {} pos []]
    (cond
      (nil? a) {:flags flags :positional pos}
      (contains? known-with-value a) (recur (rest more) (assoc flags a (first more)) pos)
      (str/starts-with? a "--") (recur more (assoc flags a true) pos)
      :else (recur more flags (conj pos a)))))

(defn worker-limit
  "The constitution pins worker-limited tools to 4."
  [flags]
  (let [raw (get flags "--max-workers" (get flags "--workers" "4"))]
    (if (string? raw)
      (try (max 1 (Integer/parseInt raw)) (catch Exception _ 4))
      4)))

;; ------------------------------------------------------------------- globbing

(def any-depth
  "Glob prefix for a path that may sit at any depth, the worktree root included.

  `**/` on its own requires at least one directory segment, so a pattern like
  `**/build/reports/kover/*.xml` finds nothing at all in a single-module project
  that keeps `build` beside `settings.gradle.kts`. The tool then reports 'no
  report found' for a build that produced one. Writing `**build` drops the
  requirement but also matches `notbuild`, so it trades a silent miss for a
  silent false positive. The empty brace alternative is the only form that is
  both complete and exact.

  Use it for interior segments too: a trailing `**/mutations.xml` has the same
  blind spot as a leading one."
  "{,**/}")

(def ^:private ignored-dirs
  #{"build" ".gradle" ".git" ".idea" ".worktrees" ".swarmforge" "tmp"
    "node_modules" "DerivedData" "Pods"})

(defn under-ignored-dir?
  "True when any segment of path is generated, vendored or scratch output.
  Report globs deliberately reach into `build`, so this filter is opt-in rather
  than baked into the glob helpers."
  [root path]
  (boolean (some ignored-dirs
                 (map str (iterator-seq (.iterator (fs/relativize root path)))))))

(defn glob-sources
  "Every hand-written path matching pattern, at any depth under root.

  pattern carries no depth prefix of its own: pass `src` or
  `src/commonMain/kotlin`. Matches inside build output are dropped, which
  matters here precisely because `any-depth` reaches places a `*/`-anchored
  pattern never could."
  ([root pattern] (glob-sources root pattern nil))
  ([root pattern opts]
   (->> (fs/glob root (str any-depth pattern) opts)
        (remove #(under-ignored-dir? root %))
        (map str)
        sort
        vec)))

;; ------------------------------------------------------------- source sets

(defn source-dirs
  "Every `src` directory that Gradle modules expose, excluding build output.
  KMP projects keep source sets under <module>/src/<sourceSet>/kotlin, and a
  single-module project keeps them at the worktree root."
  []
  (glob-sources (worktree-root) "src" {:hidden false}))

(defn files-with-extension [ext]
  (->> (source-dirs)
       (mapcat (fn [dir] (fs/glob dir (str "**." ext))))
       (map str)
       sort
       vec))

;; --------------------------------------------------------- generated vs. author

(defn hand-written-sources
  "Every Kotlin and Java file a person typed, keyed by the suffix a coverage
  report can reconstruct. A JaCoCo class element carries its package and its
  source file name but never its directory, so `com/example/App.kt` is the most
  specific key available. Matching on the bare file name instead would let one
  module's hand-written Res.kt vouch for another module's generated one."
  []
  (into #{} (concat (files-with-extension "kt") (files-with-extension "java"))))

(defn- source-path
  "The hand-written file sitting at <package>/<source> under a src tree, or nil."
  [sources package source]
  (when source
    (let [suffix (str "/" (if (str/blank? (str package)) "" (str package "/")) source)]
      (some #(when (str/ends-with? % suffix) %) sources))))

(def ^:private compose-lambda-holder "ComposableSingletons$")

(defn generated-class
  "Why a coverage row is not the author's code, or nil when it is.

  Two questions, because one criterion cannot answer both:

  `:outside-source-tree` - the class names a source file that exists nowhere
  under any `src` directory. Compose Resources, KSP, kapt and BuildConfig all
  land in `build`, so this catches every generator without naming any of them.

  `:compose-lambda-holder` - the Compose compiler hoists the lambdas out of a
  composable into a synthetic `ComposableSingletons$<File>Kt` class. That class
  reports the author's own file as its source, so the first criterion cannot see
  it, and its line count is large enough to dominate a small module.

  A tool that scores these is scoring the toolchain, not the work."
  [sources {:keys [package class source]}]
  (cond
    (str/includes? (str class) compose-lambda-holder) :compose-lambda-holder
    (nil? (source-path sources package source)) :outside-source-tree
    :else nil))

(def generated-reasons
  {:compose-lambda-holder "Compose compiler lambda holder"
   :outside-source-tree "no source file under any src directory"})

(def ^:private composable-file?
  ;; Memoised: a report names one class per nested type, and they all resolve
  ;; back to the same handful of files.
  (memoize (fn [path] (str/includes? (slurp path) "@Composable"))))

(defn declares-composable?
  "True when the file's text contains the @Composable annotation.

  This is a text match on the source, not a parse, so it is reported as what it
  is: the file declares composables. It answers a question no coverage report
  can, because the kind of test that covers a composable differs from the kind
  that covers plain logic, and the XML cannot say which rows are which. Which
  test, not whether: a composable renders on the JVM under Robolectric, so these
  rows are expected to reach the same coverage as the rest."
  [sources package source]
  (boolean (some-> (source-path sources package source) composable-file?)))

;; ----------------------------------------------------------------------- xml

(defn children
  "Direct child elements whose local tag name matches, ignoring any XML
  namespace. PMD's CPD report is namespaced and JaCoCo's is not, so matching on
  the raw keyword would silently return nothing for one of them."
  [element local-name]
  (->> (:content element)
       (filter map?)
       (filter #(= local-name (some-> (:tag %) name)))))

(defn attr [element k]
  (get-in element [:attrs k]))

(defn attr-long [element k default]
  (or (some-> (attr element k) str parse-long) default))

;; ------------------------------------------------------------------- hashing

(defn sha256 [^String text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes text "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn file-hash [path]
  (if (fs/exists? path) (sha256 (slurp (str path))) "absent"))

;; ------------------------------------------------------------------ reporting

(defn rule [] (apply str (repeat 72 "-")))

(defn heading [text]
  (println)
  (println text)
  (println (rule)))
