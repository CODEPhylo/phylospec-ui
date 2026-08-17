# PhyloSpec UI

A BEAUti-shaped model builder that writes [PhyloSpec](https://github.com/CODEPhylo/phylospec)
instead of BEAST XML.

The tabs follow BEAUti's flow — load the data, set the sampling times, choose the site, clock and
tree models, set the priors, set the run length — but the output is a `.phylospec` script, and it is
checked by the reference PhyloSpec parser and type resolver as you build it.

## Running

```sh
mvn package
bin/phylospec-ui                            # empty analysis
bin/phylospec-ui examples/Primates.nex      # with an alignment already loaded
```

`examples/` holds the familiar BEAST 2.8 datasets — Primates, anolis, Flu, Dengue4, RSV2, dna and
aminoacid — copied unchanged from `beast-base`. See [examples/README.md](examples/README.md) for
which of them carry sampling dates.

Requires Java 21+ (built and tested on 25) and the `org.phylospec:phylospec-core` artifact in your
local Maven repository. If it is missing, build it from the phylospec repo:

```sh
cd ../phylospec && mvn -N install && mvn -pl core/java -DskipTests install
```

The first of those installs phylospec's parent pom, which `phylospec-core` needs and which building
`core/java` alone does not provide. That build needs network access to fetch the Spotless plugin.

Verified against phylospec `5b5b9dc4` (14 Aug 2026), component library 1.4.0.

## The tabs

| Tab | What it sets | Where it lands in the script |
|---|---|---|
| Partitions | Alignment files (`+`, `−`, or drag and drop) | `data` block: `fromNexus` / `fromFasta` |
| Tip Dates | How a sampling time is read from each taxon name | `age=` or `date=` argument, via `parse(...)` |
| Site Model | Substitution model, and optional rate heterogeneity | `QMatrix qMatrix = ...`, `Vector<Rate> siteRates ~ ...` |
| Clock Model | Strict or relaxed clock | `Vector<Rate> branchRates ~ ...` |
| Tree Prior | The tree-generating process | `Tree tree ~ ...` |
| Priors | A distribution for each value marked "estimate" | the `~` statements in the `model` block |
| MCMC | Chain length, logging, seed | `mcmc` block |

Anything ticked **estimate** on a model tab becomes a random variable with a prior; anything left
unticked is written into the call as a literal. The Priors tab is derived from those ticks rather
than stored separately, so the two cannot drift apart.

## How it is put together

```
model/    Analysis, Partition, Component, Param   — what the user is building
spec/     Library, ScriptWriter, Validator        — the bridge to phylospec-core
fx/       Form, ParamRow                          — layout primitives and the one param editor
panel/    one class per tab                       — ComponentPanel serves three of them
```

Two choices keep this much smaller than BEAUti:

- **One parameter editor.** BEAUti has an `InputEditor` subclass per input type because its editors
  also own the model plumbing. Here a param is a value plus two flags (`estimate`, `include`), so
  `ParamRow` renders all of them, and the same widget serves priors, distribution-valued arguments
  (a relaxed clock's `base`) and function-valued arguments (a coalescent's `PopulationFunction`).
- **One component panel.** Site Model, Clock Model and Tree Prior are the same screen — pick a
  component, edit its arguments — so they share `ComponentPanel` and differ only in the list of
  generators they offer.

Everything the UI knows about components comes from `phylospec-core-component-library.json`, read
through `phylospec-core`. Adding a generator to that library makes it available here with no UI
code; only the curated per-tab lists in `Analysis` name components explicitly.

Structural arguments — `tree`, `taxa`, `numSites`, `qMatrix`, `siteRates`, `branchRates` — are
supplied by `ScriptWriter` from the shape of the analysis rather than being asked of the user.

## Opening a script

`File > Open…` reads a `.phylospec` script back onto the tabs. `ScriptReader` is the inverse of
`ScriptWriter`: it recognises the script by the names the writer gives its structural variables —
`qMatrix`, `siteRates`, `branchRates` — and by the declared type of the tree, and treats everything
else drawn in the `model` block as a prior on an estimated value.

The tabs express a subset of PhyloSpec, so not every valid script can be represented. A script that
falls outside it is **refused outright** rather than partly loaded: a half-loaded analysis looks
complete, and the next save would quietly discard whatever could not be mapped. The message names
what stopped it.

Alignments are referenced by path, so a script opened on another machine may point at files that are
not there. The model still loads and the paths are reported, since the counts are informational and
the engine reads the files for real.

## Validation

`Validator` runs the real `Lexer`, `Parser` and `TypeResolver` over the generated script and reports
the first problem in the status bar. `ScriptWriterTest` generates every model the tabs can express —
360 generator combinations, 89 estimate ticks, 149 prior choices and every optional argument dropped
— and asserts that all of them parse and type-check.

`ScriptReaderTest` puts the same space through `write → read → write` and asserts the two scripts are
identical. A fix point on the script is the property that matters: an `Analysis` has no equality of
its own, and the script is what the user keeps.

Note that building a `ComponentResolver` qualifies the component library's type names in place, and
a library can only be registered once — so `Library` keeps a separate, independently loaded copy for
validation, and constructs the component resolver exactly once. Sharing one copy leaks fully
qualified type names into the generated script.

## Open question: the `mcmc` block

The language spec says the set of variables allowed in the `mcmc` block is still to be decided. The
names written here are `chainLength`, `logEvery`, `logFile` and `randomSeed`. Of those,
`chainLength`, `randomSeed` and `logFile` appear in phylospec's own tests; `logEvery` does not — the
beastx tests express it as `fileLogger(logEvery=..., file=..., parameters=[...])`, which needs a
`Logger` type that the core component library does not yet define. These names type-check, but an
engine may not read them. They are set in one place, `ScriptWriter.mcmcStatements`.

## Not yet supported

- **Unlinked partitions.** Alignments all share one site model, clock model and tree, which is
  BEAUti's state immediately after import. Per-partition models are not yet expressible here.
- **Operators.** These have no PhyloSpec equivalent by design — they are machinery an engine chooses,
  not part of the model description — so there is no Operators tab.
- **Starting trees and state initialisation**, for the same reason.

## License

MIT — see [LICENSE](LICENSE).

The alignments in `examples/` are redistributed from BEAST 2.8; see
[examples/README.md](examples/README.md) for their provenance and citations.
