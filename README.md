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
bin/phylospec-ui --engine engines/beast2-2.8.0-beta4.json  # and with what that engine can run
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

Verified against phylospec `18f87260` (21 Aug 2026), component library 1.4.0.

## The tabs

| Tab | What it sets | Where it lands in the script |
|---|---|---|
| Partitions | Alignment files (`+`, `−`, or drag and drop), and what each one shares | `data` block: `fromNexus` / `fromFasta` |
| Tip Dates & Species | How a sampling time, and a species, are read from each taxon name | `age=`, `date=` or `speciesName=` argument, via `parse(...)` |
| Site Model | Substitution model, and optional rate heterogeneity | `QMatrix qMatrix = ...`, `Vector<Rate> siteRates ~ ...` |
| Clock Model | Strict or relaxed clock | `Vector<Rate> branchRates ~ ...` |
| Likelihood | The distribution the alignments are drawn from | the `observed as` statement |
| Tree Prior | The tree-generating process | `Tree tree ~ ...` |
| Priors | A distribution for each value marked "estimate" | the `~` statements in the `model` block |
| MCMC | Chain length, logging, seed | `mcmc` block |

Anything ticked **estimate** on a model tab becomes a random variable with a prior; anything left
unticked is written into the call as a literal. The Priors tab is derived from those ticks rather
than stored separately, so the two cannot drift apart.

## How it is put together

```
model/    Analysis, Partition, SiteModel, TreeModel, Component, Param
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

## The likelihood decides the other tabs

The distribution the alignments are drawn from is a choice like any other, not something the writer
hardcodes. Everything on the Site Model, Clock Model and Tree Prior tabs exists to supply one of its
arguments, so **the choice of likelihood decides which of those tabs apply**:

| Likelihood | Site Model | Clock Model |
|---|---|---|
| `PhyloCTMC` | substitution model and site rates | strict or relaxed clock |
| `SNAPP` | — | — |

Choosing SNAPP removes both tabs and clears their models, since a script that kept a rate matrix no
likelihood asks for would be wrong rather than merely untidy. Each chooser is separately
conditional: a likelihood taking `siteRates` but no `qMatrix` keeps half the Site Model tab. A tab
that comes back is given a component again, so the script never sits in a half-built state.

Only likelihoods that could be observed as the loaded data are offered. An observation is invariant
in PhyloSpec — the resolver refuses `Alignment<Real>` observed as `Alignment<Character>` — so
`PhyloBM` and `PhyloOU` are hidden once a nucleotide alignment is loaded, rather than offered as a
choice that cannot validate. With nothing loaded, all of them show.

The observed statement is typed from the likelihood, so it reads `Alignment<Character>` rather than
`Alignment`, and `PhyloBM` would read `Alignment<Real>`.

The SNAPP entry in the sample library is taken from SNAPP's own inputs rather than guessed:
`mutationRateU`, `mutationRateV`, and `theta` **or** `coalescenceRate` — which are `XOR` inputs in
BEAST, so they are two signatures here, told apart on the way back in by which of the two the call
names. Its engine-only inputs, the ascertainment counts and the diagnostics, are left out. One name
had to change: BEAST spells an input `non-polymorphic`, which is not a legal identifier, and that is
worth remembering when libraries start being generated from package sources.

Two limitations run the other way.

`fromNexus` and `fromFasta` always produce `Alignment<Character>` whatever the file says, so a
likelihood cannot ask for the *kind* of discrete data it needs. SNAPP wants biallelic SNPs and has
to settle for `Alignment<Character>`; declaring it over an `Alignment<Binary>` makes it unobservable
from any loader PhyloSpec has.

And SNAPP's `theta` is one value per node — `Vector<PositiveReal; num=tree.numNodes>` — which is a
length no fixed number can express, so it gets the four-element fallback and the resolver accepts
it — re-checked on `21cba006`, where a twelve-taxon alignment with a four-element `theta` draws no
complaint on either channel, because `num=tree.numNodes` is a type property rather than a
`constraint` and nothing compares the two. That is the same gap as the vector lengths above, seen
from the other side: an advisory length lets a wrong-length vector through.

## Unlinked partitions

An analysis starts with every partition sharing one site model, one clock model and one tree, which
is BEAUti's state immediately after import. The last four columns of the Partitions tab say what
each partition shares, and Link and Unlink over the selected rows change it, one thing at a time.

Unlinking copies what was already chosen rather than starting from a default, since it is nearly
always the prelude to changing one thing about one partition.

### Trees have two levels of sharing, site models have one

Two partitions can have separate trees drawn from *one* prior, and then the prior's parameters are
estimated once across both:

```
PositiveReal populationSize ~ LogNormal(logMean=0.0, logSd=1.0)
Tree gene1Tree ~ Coalescent(populationSize=populationSize, taxa=taxa(gene1))
Tree gene2Tree ~ Coalescent(populationSize=populationSize, taxa=taxa(gene2))
```

That is the multi-locus coalescent, and BEAUti cannot express it without editing the XML: unlinking
a tree there gives it a separate prior with separate parameters. Both are available here, as the
Tree and Tree prior axes: unlink the tree to get the script above, unlink the prior as well to get
independent population sizes.

Nothing analogous applies to a site model, so it is one axis. Two rate matrices sharing a `kappa`
would only be different matrices if something else about them differed, and nothing does.

Branch rates turn out to belong to a clock model and a tree together: one clock shared by partitions
on different trees still draws a vector per tree, because the length of the vector is the tree's.

### Naming, and how the grouping is read back

With everything linked the variables are the plain `qMatrix`, `siteRates`, `branchRates` and `tree`
the writer has always used, so a linked analysis produces exactly the script it produced before any
of this existed, byte for byte. Unlinking qualifies them by the first partition using the group:
`gene1QMatrix`, `gene2BranchRates`, `gene1Tree`. Relinking restores the plain ones.

`ScriptReader` does not trust those names, because a script it did not write will not follow them.
It reads the grouping off the structure instead: partitions whose observations name the same
`qMatrix` share a site model, partitions naming the same tree share a tree, and a rate matrix is
recognised by its declared type rather than by being called `qMatrix`. So a script that calls its
tree `t1` loads and saves unchanged.

Sharing is recovered the same way. Two statements alike but for `taxa` are one distribution applied
to two sets of taxa, so they are read back as one component, and the single `populationSize` above
survives the round trip as a single one. Two branch-rate vectors alike but for `tree` are likewise
one clock.

## StarBEAST, and what is missing

A multispecies coalescent draws each gene tree within one species tree, with the population sizes
estimated across loci. Four things make that up, and three of them are here:

- **The gene trees** are unlinked trees sharing one prior, which is the two-level sharing above used
  for what it is for: one `MultispeciesCoalescent`, so one species tree and one population size.
- **The species tree** is not a tree any partition is drawn on, so it is not one of the analysis's
  trees at all. It is an estimated `Tree` argument of the coalescent, and the machinery that gives a
  prior to any estimated value writes `Tree speciesTree ~ Yule(...)` out for it.
- **The mapping from taxon to species** is the loader's `speciesName` parser, set on the Tip Dates
  and Species tab beside the sampling times. It has been in core since March.

The fourth is missing from core. The species tree has to be told which taxa it spans, and nothing in
core derives that set: `taxa(alignment)` gives the individuals, and `species(taxon)` is a `String`
for one taxon rather than the set. Two overloads of functions that already exist would close it, and
`libraries/beast28.json` carries both as placeholders so the rest can be built and tested:

```
Tree speciesTree ~ Yule(birthRate=1, taxa=species(taxa=taxa(alignments=[gene1, gene2])))
```

`taxa(alignments=[...])` takes the taxa of several loci together, which matters when a gene does not
sample every species and the species tree still spans the union. With complete sampling
`species(taxa=taxa(alignment=gene1))` says the same thing. `species(taxa=...)` reduces a taxon set to
one taxon per species, which is the half that cannot be worked around at all.

### The types do the narrowing

`libraries/beast28.json` declares two types, `SpeciesTaxa extends Taxa` and
`SpeciesTree extends Tree`, and a `SpeciesYule` drawn over the first to produce the second. That is
what makes the choosers offer one thing each: the coalescent's `speciesTree` is a `SpeciesTree`, so
the only distribution over it is `SpeciesYule`, whose taxa are a `SpeciesTaxa`, so the only thing
that can supply them is `species`.

Without it, every step offers the wrong answer beside the right one. Core's `Yule` takes any `Taxa`
and returns any `Tree`, so a species tree could be drawn over the individuals of one alignment, and
a gene tree could be passed to the coalescent as the species tree. Both type-check. With the types
in place both are refused, by the resolver as well as by the menus, and `extends` is enough to say
so: a `SpeciesTree` still works wherever a `Tree` is wanted, such as a clock model, while a plain
tree is not accepted as a species tree.

This belongs in core rather than here, as a `Taxa` and a tree parameterised by whether their tips
are species or individuals. See CODEPhylo/phylospec#75.

Nothing above is typed. Setting one up, from an empty window:

| Tab | What to do |
|---|---|
| Partitions | Add the alignments |
| Tip Dates & Species | Tick "Read a species from each taxon name", and set the delimiter and part |
| Partitions | Select every row, choose **Tree**, click **Unlink** |
| Tree Prior | Choose `MultispeciesCoalescent` |
| Priors | Under `speciesTree ~ SpeciesYule`, its taxa are already `species`; pick which alignments they come from |

The species tree needs no tab of its own. It is an estimated `Tree` argument, so it appears on the
Priors tab like any other estimated value, and its `Yule` is chosen there.

A taxon set is a component to choose rather than a value to type, so the species set is assembled
from the same choosers everything else uses. Underneath it, an argument that names an alignment is a
list of the loaded partitions, and one that names several is a tick per partition, all ticked to
begin with. That is the only thing the UI knows here that the library did not tell it: that an
`Alignment` argument refers to a loaded partition, which is what the Partitions tab knows too.

Those two are placeholders, not engine components, and the point is to delete them. `StarBeastTest`
asserts core does *not* provide them, so the day it does, the test fails and says to remove ours
rather than leave them shadowing core's, which is what happened with `Bernoulli`. This is
CODEPhylo/phylospec#75, and it is narrower than what that issue asks for.

`libraries/beast28.json` carries a `MultispeciesCoalescent` to demonstrate this. It reaches the Tree
Prior tab by generating a `Distribution<Tree>`, with no UI code naming it, and `StarBeastTest` puts
the whole shape through `write -> read -> write`.

## Engine component libraries

An engine implements some of PhyloSpec and adds components of its own, so `--library` loads one or
more further component libraries beside core. Nothing else changes: they are ordinary
component-library JSON, registered after core so they can refer to core types, and their namespaces
are imported so the parser resolves their names.

`libraries/beast28.json` is a hand-written sample covering BEAST 2.8 packages — BICEPS (`BICEPS`,
`YuleSkyline`), bModelTest, and a SNAPP likelihood. **No UI code names any of them, and the library declares
no roles.** A component reaches a tab by what it produces:

| Role | Filled by | Tab |
|---|---|---|
| `substitutionModel` | a function returning `QMatrix` | Site Model |
| `siteRates` | a distribution over a vector of rates, with no tree | Site Model |
| `clockModel` | a distribution over a vector of rates, given a tree | Clock Model |
| `treePrior` | a distribution over a `Tree` | Tree Prior |
| `treeLikelihood` | a distribution over an `Alignment` | Likelihood |
| `populationFunction` | a function returning `PopulationFunction` | nested argument |

For most of these the role is the generated type restated: only a substitution model returns a
`QMatrix`, only a tree prior is a distribution over a `Tree`. The one pair inference has to work for
is the clock and the site rates, which generate the same `Distribution<Vector<Rate>>` and are told
apart by whether the component takes a tree.

A component may still declare a `role` outright, and it wins. That is not for restating a type —
doing so could only agree with it or contradict it — but for the one thing a type cannot say: that a
component fills a slot the UI does not have yet. A StarBEAST gene tree is a `Distribution<Tree>` and
still does not belong on the Tree Prior tab.

`role` is not in the component-library schema yet; it survives loading as an additional property,
and it would be worth adding.

The other half, a way for an engine to declare *which core components it implements*, is a separate
document from a component library: an **engine specification**, added in phylospec PR #61 and read
here with `--engine`. See [What the engine can run](#what-the-engine-can-run) below.

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

Two spellings say this: the schema's `dimension` field on the argument, and the type language's
`num` property — `Simplex<;num=6>` — which is already how *generated* types say it. Both are read
here, the property winning. Neither is enforced by the type resolver — re-checked on `21cba006`,
having been checked on `256f2e40` and `5b5b9dc4` before it — so a tool that ignores them writes a
wrong-length vector that still type-checks. `ValidatorTest` asserts that gap rather than the fix, so
it fails when the resolver starts enforcing declared lengths, which is when this UI can stop sizing
vectors itself. That is CODEPhylo/phylospec#74, still open.

What the resolver *does* check is a component's `constraints`: a comparison relating two arguments,
such as `PhyloCTMC`'s `tree.numBranches == branchRates.num`. Seven core components carry them. See
[Validation](#validation) for where those land, which is not where a script's refusals do.

Of the two, the type property is the one being kept: core is moving the requirement into the
component metadata, as `Simplex<;num=20>` on the argument and `QMatrix<;numRows=20,numCols=20>` on
the result, with the BEAST X tile's `requireSize` checks generated from the same source rather than
stated separately.

This UI is ready for that. Running the whole suite against a core library patched to the intended
shape — `wag`, `jtt`, `lg` at twenty, `gy94` at sixty-one, the nucleotide models at four — leaves
every test passing: the properties are read, the literals and Dirichlet concentrations come out at
the right length and sum to one, role inference is unaffected by properties on the result type, and
the scripts validate and round-trip.

Core's second `gtr`, added in #76, is the live example. It takes the six relative rates as one
`Simplex relativeRates` rather than as six arguments, and declares no length for it, so this UI
writes four and draws them from a four-concentration Dirichlet. The script type-checks, because
nothing enforces a declared length and there is no declared length to enforce. `Simplex<;num=6>` on
that argument would fix it here and in every other tool reading the library.

The remaining case a fixed number cannot express is a length that depends on the data — an `mk`
model has as many frequencies as the alignment has states. `Simplex<;num=numStates(alignment)>`
would say it, and needs a `numStates` function that core does not yet have.

A flat simplex has to sum to one: BEAST's tiling accepts a sum within 1e-6 and PhyloSpec's own
`Simplex` asks for 1e-10, so a size that does not divide one exactly cannot just repeat a rounded
element. The last element absorbs the rounding instead, which keeps 1/4 and 1/20 exact.

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

Those two switches want a `Boolean`. Core's `Bernoulli` used to generate a `NonNegativeInteger`, so
the sample library carried a second one generating `Distribution<Boolean>` beside it, and the
declared type of each variable told them apart. Core gained exactly that in phylospec #76, on
21 Aug 2026, so the overload here has been removed rather than left to shadow core's.

## What the engine can run

A component library says what a component *is*. An engine specification says which of them an engine
*implements*, and an engine implements only a subset: `engines/beast2-2.8.0-beta4.json`, generated
from `integrations/beast3` by phylospec's own `CreateEngineSpecification`, covers 51 of core's 92
components. Missing from it are `PhyloBM` and `PhyloOU`, `SkylineCoalescent`, `lg`, `gy94`, `mk`,
`fromFasta` and `fromCSV`, among others. Without the specification the UI offers all of them, and the
user finds out at run time.

`--engine <file>` loads one, and it may be given more than once: a BEAST 2 package is an engine in
its own right, a model can need several, so a component is supported if *any* loaded engine
implements it. With no specification loaded nothing is claimed and everything is offered, which is
what this UI did before.

A component nothing implements is shown **disabled rather than hidden**, greyed, with the reason and
the specification's own `installationInstructions` and `installationWebsite` in the tooltip. Hiding
it would leave a user wondering why the model they read about is absent, and would make someone
else's script unreadable here. What the tabs offer does not change; only how a choice is drawn.

`canBeStochastic` says per argument whether an engine can sample it, which is better than what this
UI did before: match the head of the argument's type against a hardcoded list of continuous types.
Only a declared no is taken as a no, since an engine having no opinion is not the same as refusing.

### Matching a component to an entry

The specification records a generator's name and its argument names. It does not record types,
deliberately. Matching is therefore: everything the library's generator asks for must be something
the entry offers, and everything the entry marks `required` must be something the generator declares.

Not equality of argument lists, because the two documents disagree in three ways that mean nothing.
**Order**: core declares `PhyloCTMC` as `tree, qMatrix, siteRates, branchRates`, the generator emits
`tree, qMatrix, branchRates, siteRates`, and a call names its arguments anyway. **Optionality**: core
says an optional argument by declaring a second overload without it, while an entry says it with
`required: false`, so BEAST 2's one `Yule` with an optional `rootAge` is both of core's `Yule`
signatures at once. **Absence**: an argument the library declares and the entry never mentions,
like the `siteQMatrices` of core's second `PhyloCTMC`, means this is not the component the engine
implements.

Two overloads that differ only in a type collapse onto one entry, which is
[CODEPhylo/phylospec#73](https://github.com/CODEPhylo/phylospec/issues/73) seen in real generated
output rather than in the abstract. Core's two `Coalescent` signatures both take `populationSize`
and `taxa`, differing only in `PositiveReal` against `PopulationFunction`, so one entry matches both
and nothing here can tell which BEAST 2 implements. Both are offered: refusing a model an engine can
run is the worse of the two errors. The same collapse shows up in `exp`, which the specification
lists twice with disagreeing `canBeStochastic`, and a yes from either is taken as a yes.

The specification is committed here because none is published yet. They are written to a git-ignored
`generated` folder, so this one was generated locally at phylospec `18f87260` and copied in. When
they ship in a repository this copy should go, and until then it goes stale whenever core moves:
core's second `gtr` arrived in #76, BEAST 2 implements it, and against the copy generated before
that this UI greyed out a model the engine can run. `EngineSupportTest` checks both `gtr` overloads
so that a stale copy fails there rather than in the tabs.

Not covered yet: the Partitions tab still offers FASTA files, and BEAST 2 does not implement
`fromFasta`, so that is a script the engine cannot run and the UI does not yet say so.

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
the first problem in the status bar.

The resolver says what it thinks on two channels, and they mean different things. It **throws** what
it refuses — that is a `TypeError`, and the script is wrong. It **reports** what it merely doubts on
an event listener, which is where a contradicted `constraint` lands: the resolver could not rule the
script out but the types say the relation does not hold, which is usually a wrong-length vector.
`Validator.check` returns the two apart, and the status bar shows a refusal ahead of a doubt and
does not colour a doubt as an error, since an engine may well run the script anyway.

Subscribing to that second channel is worth doing rather than obvious: a UI that ignores it tells
the user a script is valid while the resolver is saying it probably is not. It caught one on the way
in — a test building two partitions with *different* taxon sets, which share one tree and so cannot
both be observed under it. `ScriptWriterTest` generates every model the tabs can express —
396 generator combinations, 89 estimate ticks, 149 prior choices and every optional argument dropped
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

- **Operators.** These have no PhyloSpec equivalent by design — they are machinery an engine chooses,
  not part of the model description — so there is no Operators tab.
- **Starting trees and state initialisation**, for the same reason.

## License

MIT — see [LICENSE](LICENSE).

The alignments in `examples/` are redistributed from BEAST 2.8; see
[examples/README.md](examples/README.md) for their provenance and citations.
