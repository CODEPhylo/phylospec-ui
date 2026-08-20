# PhyloSpec UI

A BEAUti-shaped model builder that writes [PhyloSpec](https://github.com/CODEPhylo/phylospec)
instead of BEAST XML.

The tabs follow BEAUti's flow — load the data, set the sampling times, choose the site, clock and
tree models, set the priors, set the run length — but the output is a `.phylospec` script, and it is
checked by the reference PhyloSpec parser and type resolver as you build it.

## Running

```sh
mvn package
bin/phylospec-ui                                   # empty analysis
bin/phylospec-ui examples/Primates.nex             # with an alignment already loaded
bin/phylospec-ui --library libraries/beast28.json  # with an engine's components as well
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
  component, edit its arguments — so they share `ComponentPanel` and differ only in the role of the
  components they offer.

Everything the UI knows about components comes from the component libraries, read through
`phylospec-core`. Adding a generator to a library makes it available here with no UI code.

Structural arguments — `tree`, `taxa`, `numSites`, `qMatrix`, `siteRates`, `branchRates` — are
supplied by `ScriptWriter` from the shape of the analysis rather than being asked of the user.

## Engine component libraries

An engine implements some of PhyloSpec and adds components of its own, so `--library` loads one or
more further component libraries beside core. Nothing else changes: they are ordinary
component-library JSON, registered after core so they can refer to core types, and their namespaces
are imported so the parser resolves their names.

`libraries/beast28.json` is a hand-written sample covering two BEAST 2.8 packages — BICEPS
(`BICEPS`, `YuleSkyline`) and bModelTest. **No UI code names any of them, and the library declares
no roles.** A component reaches a tab by what it produces:

| Role | Filled by | Tab |
|---|---|---|
| `substitutionModel` | a function returning `QMatrix` | Site Model |
| `siteRates` | a distribution over a vector of rates, with no tree | Site Model |
| `clockModel` | a distribution over a vector of rates, given a tree | Clock Model |
| `treePrior` | a distribution over a `Tree` | Tree Prior |
| `treeLikelihood` | a distribution over an `Alignment` | not yet a tab — see below |
| `populationFunction` | a function returning `PopulationFunction` | nested argument |

For most of these the role is the generated type restated: only a substitution model returns a
`QMatrix`, only a tree prior is a distribution over a `Tree`. The one pair inference has to work for
is the clock and the site rates, which generate the same `Distribution<Vector<Rate>>` and are told
apart by whether the component takes a tree.

A component may still declare a `role` outright, and it wins. That is not for restating a type —
doing so could only agree with it or contradict it — but for the one thing a type cannot say: that a
component fills a slot the UI does not have yet. A StarBEAST gene tree is a `Distribution<Tree>` and
still does not belong on the Tree Prior tab.

`role` is not in the component-library schema yet; it survives loading as an additional property. It
would be worth adding, along with a way for an engine to declare *which core components it
implements*, since an engine that cannot run half of core should not offer it.

### Named intermediates

An argument built by calling a function — a coalescent's `PopulationFunction`, a bModelTest model
set — becomes a statement of its own rather than a call nested inside the one that uses it:

```
PopulationFunction populationSize = constantPopulationFunction(populationSize=theta)
Tree tree ~ Coalescent(populationSize=populationSize, taxa=taxa(primates))
```

It needs a name because it may be referred to from more than one place, and an inlined call cannot
be. `ScriptReader` reads them back by collecting the model block's assignments first, so a forward
reference works; one that nothing refers to is refused, since the tabs have nowhere to keep it.

A distribution-valued argument is left where it is written — `RelaxedClock(base=LogNormal(...))` has
no spelling as a statement.

### Vector lengths

A vector-valued argument is as long as the library's `dimension` says it is, as a literal and in the
prior it is drawn from — a Dirichlet drawing a six-element simplex is given six concentrations, and
nothing on the Dirichlet's side of the library could know that. A `dimension` written as an
expression rather than a number (`tree.numBranches`) is left alone: those arguments are wired by the
writer from the shape of the analysis.

Where no dimension is declared the length falls back to four, which is right for nucleotide
frequencies and a guess otherwise. Core declares none, and that shows: `wag`, `jtt` and `lg` take a
`Simplex baseFrequencies` of **twenty**, and get four. Adding `dimension` to those three in core
would fix it, here and in every other tool reading the library.

### bModelTest

Worth noting how it decomposes, because it is not what it looks like. bModelTest is not a
distribution over rate matrices — the matrix stays deterministic and the *model indicator* is what
gets sampled:

```
BModelSet modelSet = bModelSet(name="transitionTransversionSplit")
Integer modelIndicator ~ DiscreteUniform(lower=0, upper=size(modelSet) - 1)
QMatrix qMatrix = nucleotideModel(modelSet=modelSet, modelIndicator=modelIndicator, ...)
```

which is how LPhy models it, and is the more faithful reading: the reversible-jump move is an
operator on the indicator, not a distribution. The indicator's trace is what a bModelTest analysis
is *for*, so hiding it inside a `~` on `qMatrix` would lose the result.

An indicator is not measured, it is summed over, so its tick reads **average over** rather than
**estimate**. Nothing about an argument's type says which it is — a gamma category count is chosen,
not inferred — so a library marks one with the `indicator` widget in its `uiHints`. bModelTest has
three: the model indicator, and the two switches on `bSiteRates` that turn gamma rates and
invariable sites on and off, which are BEAUti's checkboxes promoted to random variables.

Those two switches want a `Boolean`, and core's `Bernoulli` generates a `NonNegativeInteger`. So the
sample library carries a second `Bernoulli` generating `Distribution<Boolean>`. Both are in scope at
once and the declared type of each variable is what tells them apart, which is worth knowing works:
an engine library can overload a core component rather than having to rename around it. Core gaining
a `Distribution<Boolean>` of its own would make this one unnecessary.

## Opening a script

`File > Open…` reads a `.phylospec` script back onto the tabs. `ScriptReader` is the inverse of
`ScriptWriter`: it recognises the script by the names the writer gives its structural variables —
`qMatrix`, `siteRates`, `branchRates` — and by the declared type of the tree. Everything else drawn
in the `model` block is a prior on an estimated value, and everything else assigned is a named
intermediate, read as part of the argument that refers to it.

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
— and asserts that all of them parse and type-check. `EngineLibraryTest` does the same for
`libraries/beast28.json`, and asserts on what the tabs *offer* rather than on the library's
contents, since the claim being tested is that no UI code names its components. It also puts the
full bModelTest shape — named intermediate, sampled indicator, an argument holding an expression —
through the same `write → read → write` fixpoint.

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
