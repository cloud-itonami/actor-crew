# actor-crew — maritime crew and STCW operations actor

`actor-crew` coordinates seafarer registries, STCW certificate expiry,
safe-manning evidence, manifests, and crew-change schedules. Its canonical
repository is `cloud-itonami/actor-crew`.

This is a commercial maritime-operations actor for Cloud Itonami. It does not
own etzhayyim's Charter, Council, or artificial-organism state. Existing
etzhayyim DID, AT Protocol namespace, Radicle identity, and former GitHub URL
remain compatibility identities; the move does not mint a new actor.

## What is in here

`src/crew/murakumo.kotoba` is the whole executable boundary: 20 cells, each
declaring the collections it writes, the murakumo node it runs on, and the 7
gates it requires before it may write anything. `cell-plan` returns effects
without performing them — a cell whose gates are unattested plans zero effects.
`README.edn` states the ownership boundary in machine-readable form.

## Running the contract suite

```
kbb --backend sci scripts/run_contract_suite.cljk
```

`docs/operator-quickstart.md` walks this end to end, including how to read a
cell plan and how to confirm the suite still bites.

**`kbb -M:test` no longer measures anything here.** The sources were
renamed from `.cljc` to `.kotoba` on 2026-09-10; Clojure's loader cannot see a
`.kotoba` file, so that command collects zero tests and exits 0 — the same exit
code it would give for a suite that passed. Use the runner above, which refuses
to report a pass for a run that collected no tests.
