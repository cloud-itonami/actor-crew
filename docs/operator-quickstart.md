# actor-crew — operator quickstart

Everything below was executed against commit `69185d8` on 2026-09-10. Where a
command's output is quoted, it is what that command actually printed, not what
it is supposed to print. Absolute paths are the only thing elided.

## What this repository is

`actor-crew` is the maritime crew and STCW operations actor for Cloud Itonami.
Its identity is `did:web:crew.etzhayyim.com`, and `src/crew/murakumo.kotoba`
holds the whole executable boundary: **20 cells**, each declaring the
collections it writes, the murakumo node it runs on (`reuben`), and the **7
gates** it requires before it may write anything.

The boundary is a planner, not a writer. `cell-plan` returns effects; nothing
in this repository performs them. What it decides is whether the effects exist
at all — see step 3.

It does not own etzhayyim's Charter, Council, or artificial-organism state.
`README.edn` is the machine-readable statement of that boundary.

## 0. Prerequisites

- `nbb` on `PATH`.
- `kotoba-lang/text`, which supplies `kotoba.lang.text`. Inside the west
  workspace this is the sibling checkout `orgs/kotoba-lang/text` and is found
  automatically. Fetch it with `west update --fetch smart text` if it is
  missing. Outside the workspace, pass `--text-src <path>` to every command
  below.

## 1. Run the contract suite

```
nbb scripts/run_contract_suite.cljk
```

Observed:

```
SUITE src=1 test=1 ns=crew.murakumo-test
TEXT  <workspace>/orgs/kotoba-lang/text/src
Testing crew.murakumo-test

Ran 9 tests containing 278 assertions.
0 failures, 0 errors.
RAN   tests=9 assertions=278 failures=0 errors=0
PASS
```

Exit codes:

| exit | meaning |
|---|---|
| 0 | the suite ran, at least one test executed, nothing failed |
| 1 | the suite ran and something failed or errored |
| 2 | **REFUSED** — could not measure. Never a pass |

`2` is returned when there are no test files, when `kotoba.lang.text` cannot be
located, when `nbb` will not start, when no test summary is produced, and when
a run collects zero tests. All five were provoked and observed; the zero-test
one matters most, and step 4 says why.

## 2. Do not trust `clojure -M:test`

The README used to name it. Run it today and it prints:

```
Running tests in #{"test"}

Testing user

Ran 0 tests containing 0 assertions.
0 failures, 0 errors.
```

and **exits 0**. On 2026-09-10 the sources here were renamed from `.cljc` to
`.kotoba` (commit `451028c`). Clojure's loader cannot see a `.kotoba` file, so
the JVM route now collects nothing — and reports that with the same exit code
it uses for a suite that passed. `deps.edn` is left in place for the `:lint`
alias and for the dependency floor it records; its `:test` alias no longer
measures anything.

## 3. Read a cell plan

Keep the staged tree the runner builds, then query it:

```
nbb scripts/run_contract_suite.cljk --keep
```

It prints `KEPT  <dir>`. With no attestations, every cell is blocked:

```
nbb --classpath "<dir>/src:<text-src>" -e '(require (quote [crew.murakumo :as m])) \
  (let [p (m/cell-plan :health {})] (println :status (:status p)) \
    (println :missing (count (:missing-gates p))) (println :effects (count (:effects p))))'
```

Observed:

```
:status :blocked
:missing 7
:effects 0
```

Attest all 7 gates and the same cell plans a write:

```
nbb --classpath "<dir>/src:<text-src>" -e '(require (quote [crew.murakumo :as m]))
(def attested (into {} (map (fn [g] [g "attested"])) m/common-gates))
(let [p (m/cell-plan :health {:attestations attested :request-id "req-1"})]
  (println :status (:status p)) (println :missing (count (:missing-gates p)))
  (println :effects (count (:effects p)))
  (println :collection (:collection (first (:effects p))))
  (println :op (:op (first (:effects p)))))'
```

Observed:

```
:status :ready
:missing 0
:effects 1
:collection com.etzhayyim.crew.health
:op :mst/put-record
```

That difference — 0 effects versus 1 — is the whole governance surface. A
regression that made `missing-gates` return `[]` would turn every blocked cell
ready without changing anything else about the output.

## 4. Check that the suite still bites

A suite that stopped running looks exactly like a suite that passed. Both are
silent. So before trusting a green here, break something and watch it go red:

```
# make the gate check stop reporting missing gates, then run step 1
#   (remove #(boolean (gate-value attestations %)))  ->  (remove (fn [_] true))
```

Observed exit `1`, with:

```
FAIL in (missing-gates-computes-the-diff)
FAIL in (cell-plan-blocks-when-gates-missing)
  expected: (= :blocked (:status plan))
  actual: (not (= :blocked :ready))
```

Restore the file afterwards. If a mutation like that leaves the suite green,
the suite is not measuring the invariant you thought it was.

## 5. Where things live

| path | what it is |
|---|---|
| `src/crew/murakumo.kotoba` | the actor boundary: cells, gates, plans, effects |
| `test/crew/murakumo_test.kotoba` | the contract suite, written against `cell-specs` rather than against named cells |
| `scripts/run_contract_suite.cljk` | the runner in step 1 |
| `README.edn` | machine-readable ownership boundary |
| `actor-manifest.jsonld` | the manifest the cells were generated from |
| `.well-known/did.json` | the DID document served for `crew.etzhayyim.com` |

## Known gaps

- **The sources are not claimed to compile as Kotoba.** The rename commit says
  so outright: it is the first half of a procedure, and the compiler's refusals
  are the work list. The runner sidesteps this by staging the sources under
  `.cljc` and executing them on nbb. It proves the contract suite still holds;
  it proves nothing about Kotoba conformance.
- **The executed `kotoba.lang.text` is not the one `deps.edn` pins.** The pin
  is `73bdb13a`; what runs is whatever the sibling checkout holds. The runner
  prints the directory it used on the `TEXT` line so the two can be compared.
- **`deps.edn`'s `:test` alias is dead** and is not repaired here; step 2 is
  the warning, not the fix.
