#!/usr/bin/env nbb
(ns run-contract-suite
  "Execute this repository's contract suite and report how many tests ran.

  Why this exists. On 2026-09-10 the Clojure sources here were renamed to
  `.kotoba` (commit 451028c). The JVM route documented in the README —
  `clojure -M:test` — cannot see a `.kotoba` file, so from that commit onward
  it printed `Ran 0 tests containing 0 assertions. 0 failures, 0 errors.` and
  **exited 0**. A suite that is never executed returned the same value as a
  suite that passed. Nothing in the repository could tell the two apart.

  So this runner refuses to be silent in the same way:

    exit 0  the suite ran, at least one test executed, nothing failed
    exit 1  the suite ran and something failed or errored
    exit 2  REFUSED — could not measure (no test files, no runtime, no
            summary line, or a run that collected zero tests). Never a pass.

  How it runs them. The `.kotoba` sources are Clojure-shaped text; the rename
  commit says outright that this repository `is NOT claimed to compile as
  Kotoba`. Until the compiler accepts them, they are copied into a scratch
  directory under a `.cljc` extension and executed on nbb. The repository is
  never written to. Nothing here asserts the sources are valid Kotoba — it
  asserts only that the contract suite in them still holds.

  Host choice: new operational tooling is kbb-first, but a runner for a
  `cljs.test` suite has to live in the runtime that runs that suite. This is
  the `Node 側の検証/テストハーネス` case, which is nbb (`.cljs`).

  Usage:
    nbb scripts/run_contract_suite.cljs [--text-src <path>] [--keep]"
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [nbb.core :refer [*file*]]
            [clojure.string :as str]))

(def argv (vec (drop 2 (js->clj js/process.argv))))

(defn- arg-value [flag]
  (let [i (.indexOf argv flag)]
    (when (and (>= i 0) (< (inc i) (count argv)))
      (nth argv (inc i)))))

(def keep? (some? (some #{"--keep"} argv)))

(def repo-root
  ;; nbb is ESM, so there is no __filename. nbb.core/*file* is this script.
  (path/resolve (path/dirname (path/dirname *file*))))

(def source-exts #{".kotoba" ".cljc" ".cljs" ".clj"})

(defn- walk [dir]
  (if-not (fs/existsSync dir)
    []
    (mapcat (fn [entry]
              (let [full (path/join dir (.-name entry))]
                (if (.isDirectory entry) (walk full) [full])))
            (fs/readdirSync dir #js {:withFileTypes true}))))

(defn- sources-under [rel]
  (->> (walk (path/join repo-root rel))
       (filter #(source-exts (path/extname %)))
       vec))

(defn- ns-of
  "Read the namespace out of the file itself rather than deriving it from the
   path. A file whose ns does not match its path would otherwise be requested
   under a name nothing defines, and the run would report zero tests."
  [file]
  (let [m (re-find #"\(ns\s+([A-Za-z0-9_.*+!?<>=$&%'|-]+)"
                   (fs/readFileSync file "utf8"))]
    (second m)))

(defn- find-text-src
  "kotoba.lang.text is a git dependency in deps.edn, but the JVM route cannot
   run these files at all, so its sha is not what executes here. Resolve the
   sibling west checkout and say out loud which one was used."
  []
  (or (arg-value "--text-src")
      (some-> js/process.env.KOTOBA_TEXT_SRC)
      (let [candidate (path/resolve repo-root ".." ".." "kotoba-lang" "text" "src")]
        (when (fs/existsSync (path/join candidate "kotoba" "lang" "text.cljc"))
          candidate))))

(defn- refuse [& msg]
  (binding [*print-fn* *print-err-fn*]
    (apply println "REFUSED:" msg))
  (js/process.exit 2))

(defn- stage!
  "Copy every source into a scratch tree under .cljc. The repository is read
   only; nothing is written back into it."
  [dir files rel]
  (doseq [f files]
    (let [relative (path/relative (path/join repo-root rel) f)
          target (path/join dir rel (str/replace relative
                                                 (re-pattern (str (path/extname relative) "$"))
                                                 ".cljc"))]
      (fs/mkdirSync (path/dirname target) #js {:recursive true})
      (fs/copyFileSync f target))))

(defn -main []
  (let [src-files (sources-under "src")
        test-files (sources-under "test")]
    (when (empty? test-files)
      (refuse "no test sources under test/ — there is nothing to measure."))
    (let [test-nses (keep ns-of test-files)]
      (when (not= (count test-nses) (count test-files))
        (refuse "a test file has no (ns ...) form; it cannot be required:"
                (pr-str (vec (remove ns-of test-files)))))
      (let [text-src (find-text-src)]
        (when-not text-src
          (refuse (str "kotoba.lang.text was not found. Inside the west workspace it is "
                       "orgs/kotoba-lang/text/src — fetch it with "
                       "`west update --fetch smart text`, or pass --text-src <path>.")))
        (let [scratch (fs/mkdtempSync (path/join (os/tmpdir) "actor-crew-suite-"))]
          (try
            (stage! scratch src-files "src")
            (stage! scratch test-files "test")
            (let [classpath (str/join ":" [(path/join scratch "src")
                                           (path/join scratch "test")
                                           text-src])
                  expr (str "(require " (str/join " " (map #(str "(quote [" % "])") test-nses))
                            " (quote [cljs.test :as t])) "
                            "(t/run-tests " (str/join " " (map #(str "(quote " % ")") test-nses)) ")")
                  res (cp/spawnSync "nbb"
                                    #js ["--classpath" classpath "-e" expr]
                                    #js {:encoding "utf8"})
                  out (str (.-stdout res) (.-stderr res))]
              (println (str "SUITE src=" (count src-files)
                            " test=" (count test-files)
                            " ns=" (str/join "," test-nses)))
              (println (str "TEXT  " text-src))
              (println (str/trim out))
              (when (.-error res)
                (refuse "could not start nbb:" (.-message (.-error res))))
              (let [ran (re-find #"Ran (\d+) tests containing (\d+) assertions" out)
                    verdict (re-find #"(\d+) failures, (\d+) errors" out)]
                (when-not (and ran verdict)
                  (refuse (str "nbb produced no test summary (exit " (.-status res)
                               "). The run cannot be called a pass.")))
                (let [tests (js/parseInt (nth ran 1))
                      assertions (js/parseInt (nth ran 2))
                      failures (js/parseInt (nth verdict 1))
                      errors (js/parseInt (nth verdict 2))]
                  (println (str "RAN   tests=" tests " assertions=" assertions
                                " failures=" failures " errors=" errors))
                  (when (zero? tests)
                    (refuse (str "the run collected zero tests. This is exactly what "
                                 "`clojure -M:test` reports here, and it is not a pass.")))
                  (if (pos? (+ failures errors))
                    (do (println "FAIL") (js/process.exit 1))
                    (println "PASS")))))
            (finally
              (if keep?
                (println (str "KEPT  " scratch))
                (fs/rmSync scratch #js {:recursive true :force true})))))))))

(-main)
