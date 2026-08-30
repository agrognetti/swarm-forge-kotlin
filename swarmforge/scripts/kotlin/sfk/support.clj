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

(defn has-task?
  "True when Gradle can resolve the named task. Used to give a precise
  'plugin not applied' message instead of a raw Gradle stack trace."
  [task]
  (let [{:keys [exit]} (p/sh [(gradlew) "-q" "help" "--task" task]
                             {:dir (worktree-root) :out nil :err nil})]
    (zero? exit)))

(defn require-task! [task plugin-id setup]
  (when-not (has-task? task)
    (die! (str "Gradle task '" task "' does not exist in this project.")
          (str "The '" plugin-id "' plugin is not applied.")
          ""
          "Add it to the module's build script:"
          ""
          setup
          ""
          "This is a project build change. If your role does not own the build"
          "configuration, ask the operator with pack_dashboard_request.sh clarify."
          "Do not substitute an estimate for the missing tool.")))

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
