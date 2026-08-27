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

;; ------------------------------------------------------------- source sets

(def ^:private ignored-dirs
  #{"build" ".gradle" ".git" ".idea" ".worktrees" ".swarmforge" "tmp"
    "node_modules" "DerivedData" "Pods"})

(defn source-dirs
  "Every `src` directory that Gradle modules expose, excluding build output.
  KMP projects keep source sets under <module>/src/<sourceSet>/kotlin."
  []
  (let [root (worktree-root)]
    (->> (fs/glob root "**/src" {:hidden false})
         (remove (fn [p]
                   (some ignored-dirs
                         (map str (iterator-seq (.iterator (fs/relativize root p)))))))
         (map str)
         sort
         vec)))

(defn files-with-extension [ext]
  (->> (source-dirs)
       (mapcat (fn [dir] (fs/glob dir (str "**." ext))))
       (map str)
       sort
       vec))

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
