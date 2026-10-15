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

Original core/UI baseline: phylospec `b8e96901` (26 Aug 2026), component library 1.4.0.
The package workflows below use the newer local AGT/backend branches, not that original commit.

### Running the bounded acceptance suite

For the four-package milestone, use **`mvn -o -Pacceptance verify`**, not a plain `mvn package`.
The ordinary UI build intentionally skips tests that require external adapters or a display;
its success alone is not evidence that these package workflows work.

The `acceptance` profile enables the existing four-package delivery gate, package integration
tests, JavaFX controls, and the PopFunc ancestral-selection, PopFunc-paper L86, Phylonco L86,
cats, primates and dated-clock reference checks. It starts each test class in a separate JVM
because JavaFX cannot restart after `Platform.exit()`. There is no new test framework or model implementation.

Prerequisites are JDK 25+, a working desktop/display, the compatible locally installed PhyloSpec
core, all four already-built adapter bundles, and the reference fixtures described below.
The default paths assume sibling directories `phylospec`, `phylospec-ui`, `beast-phylonco`,
`RelaxClockAveraging` and `CalibratedCPP`. L86/cats/primates/dated fixtures live in their adapter's `target`
directory and must be prepared again after cleaning that adapter; their sections below give the
preparation commands. The simple PopFunc fixture is the existing example in the PhyloSpec checkout.
The selected PopFunc-paper fixture is separate: prepare it as described under **PopFunc-paper L86
composition** below. Its default path is `../phylospec/target/popfunc-l86-checked/analysis.phylospec`;
override `phylospec.popfuncL86Analysis` when using another output directory.
The primates fixture is also required: `phylospec.primatesAnalysis` must point to the prepared
`CalibratedCPP/phylospec-adapter/target/primates-reference/analysis.phylospec`, or an explicitly
selected equivalent location. An empty property must fail the delivery gate, even though JUnit
disables the individual workflow when its path property is empty.

From this UI repository, select JDK 25 first. On macOS, use the JDK lookup below instead of
a machine-specific installation directory; on other platforms set `JAVA_HOME` to your JDK 25:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o -Pacceptance verify
```

The profile supplies all package/reference properties listed in `pom.xml`. For another directory
layout, override `-Dphylospec.workspace=/path/to/sibling/checkouts` or any individual path, for
example `-Dphylospec.popfuncAdapter=/path/to/compatible/bundle`. No personal absolute path is
stored in the POM. `-o` requires all Maven dependencies to be available locally. This command does
not clone repositories, install or rebuild native packages, run their entire backend suites, commit,
or push. It verifies the supplied artifacts, not that those artifacts were rebuilt from current source.

Do not add `-Dtest=...`, `-DskipTests`, `-Dmaven.test.skip=true` or disable required checks when
reporting complete acceptance. Selecting tests remains useful for diagnosis, but is not a full
acceptance result. Inspect both the Maven exit status and the summary: the current suite must have
**zero failures, errors and skipped tests**. The delivery gate reports missing bundle directories,
empty bundles, missing reference scripts/JARs, and disabled required test flags as failures rather
than accepting an incomplete environment. Missing data files or incompatible dependencies are
also errors in the workflow tests, not reasons to fall back to synthetic data or skip a package.

Acceptance reports are in `target/acceptance-reports`, separate from the ordinary build's
`target/surefire-reports`, so a subsequent UI-only build does not replace acceptance evidence
with skipped-package results. Exported scripts, console logs and trace/tree/state
files remain in the per-workflow result directories documented below. Preserve evidence before
`mvn clean`. A passing run covers the bounded references in the following table, not every native
model, every paper configuration, convergence, or the known broader backend failures.

Before adding the selected paper L86 target, verification on 14 October 2026 passed
**227/227 with zero skips** using `mvn -o -Pacceptance verify`;
`mvn -o package` still succeeds with **120 executed and 66 skipped** checks. Four deliberate
negative gate runs (missing cats fixture, missing PopFunc bundle, `phylospec.fxTests=false`,
and `phylospec.deliveryTests=false`) all fail with the expected diagnostic and no silent skip.
Those negative runs selected only `PackageDeliveryTest`; they are checks of the guard, not full
acceptance runs. No original model implementation was modified for this verification.
After the selected L86 implementation, the same complete `verify` command passes **234/234,
zero failures/errors/skips**. The added seven cases cover L86 import/export, sharing and JavaFX
execution, plus preservation of narrowed scalar types. Ordinary-build counts above are historical,
not a rerun of that build after this change.

After adding primates, a targeted check exposed an omitted prerequisite: running
`-Dtest=PackageDeliveryTest,PrimatesWorkflowTest -Dphylospec.primatesAnalysis=` with the acceptance
profile succeeded with four passes and three skips. Primates is now in the gate's required-reference
list. Repeating that command fails with `Empty property: phylospec.primatesAnalysis`; a separate
`PackageDeliveryTest` run with a nonexistent primates path fails with `Missing reference analysis`.
These are intentional negative checks, not unresolved model failures. The subsequent complete
`mvn -o -Pacceptance verify` run on 14 October 2026 passed **248/248, zero failures/errors/skips**.

After adding data-loader capability checks on 15 October 2026, the complete acceptance command
passed **262/262, zero failures/errors/skips** (2 min 46 sec). The added 14 checks cover actual-call
engine support, import/export refusal without partial model changes, unrestricted editing without
engine claims, and the JavaFX import error dialog. The focused non-display selection also passed
59 tests, and the dialog test passed separately. These results use the existing compatible adapter
bundles and reference fixtures; they do not prove a clean rebuild of those artifacts or resolve the
native backend limitations documented below.
It used the existing bundles and fixtures; no native/backend full-suite rerun is implied.

### Rebuilding the acceptance environment

To check that acceptance does not depend on old `target` directories or installed first-party
JARs, run the existing workflows from fresh source snapshots:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
bash bin/verify-packages
```

This macOS/desktop workflow needs JDK 25, Maven, Git, rsync, shasum, tar and the six sibling checkouts:
`phylospec`, `phylospec-ui`, `PopFunc`, `beast-phylonco`, `RelaxClockAveraging`, and `CalibratedCPP`.
It checks the selected JDK before copying/building. When `JAVA_HOME` is set, its `bin` directory
is put first on the script's `PATH`, so Maven and standalone Java launchers use the same JDK.
Optional positional arguments select another sibling directory and dependency cache:

```bash
bash bin/verify-packages /path/to/checkouts /path/to/maven/repository
```

The script copies tracked and non-ignored untracked source files, including current local edits,
but excludes every `target` directory. It creates a separate Maven repository seeded with cached
third-party dependencies, excluding PhyloSpec and the four packages' own artifacts. All Maven
commands run offline against that repository. Missing cached dependencies fail the build; this is
not a bootstrap from an empty machine or a fresh upstream checkout.

The bounded run uses the Java likelihood implementation, not BEAGLE. The script appends
`-Djava.only=true` to its own `JAVA_TOOL_OPTIONS`, preserving other existing options, so independent
Runner JVMs inherit it too. A Maven `-Djava.only=true` alone would not reach all those child processes.
Do not inject test-selection or skip flags through the environment or local Maven configuration.

The build order is core/AGT/BEAST, PopFunc, Phylonco, Relax, CalibratedCPP, then UI. Relax must be
installed before the CPP reference checks that use its branch rates. Native package code is compiled
unchanged, without running the native packages' entire test suites. The script runs the core/AGT/BEAST
tests, selected adapter/reference tests, regenerates the L86/cats/primates/dated fixtures, and finally
runs the complete UI acceptance profile. A final report check requires a nonempty report for every
`*Test.java` class and rejects skipped/failed tests or a missing four-case delivery gate. It does not weaken model constraints or replace
the existing Java tests with another framework.

Phylonco uses a `${revision}` parent version. Installing its raw POM leaves that placeholder
unresolved for downstream modules. The script invokes the cached Maven Flatten Plugin 1.6.0 in
`resolveCiFriendliesOnly` mode to generate installation POMs in the temporary copy. Neither the
original POM nor native Java model code is edited. A future independently published Phylonco
artifact needs equivalent version-resolved metadata from its own release build.

The printed `/private/tmp/phylospec-rebuild.*` directory is retained on success or failure:

- `sources/`: the six copied working trees and their newly built results;
- `maven-repository/`: the isolated dependency/install repository;
- `evidence/`: source revisions, dirty-state records, file hashes, third-party hashes, exact commands
  with working directories, build/test logs, `results.tsv`, and built JAR hashes;
- `sources/phylospec-ui/target/acceptance-reports/`: the fresh UI JUnit XML reports.

Existing checkouts, their `target` directories and the original Maven repository are not changed.
The JavaFX native-library cache is kept in `javafx-cache/` under the new temporary directory; UI
test forks have a 240-second timeout, while their existing individual Runner timeouts remain
unchanged. JavaFX still needs a real desktop and permission to access it; a sandbox denial
is a failed UI run, not permission to skip those tests. Preserve the output directory if it is needed
as evidence. Passing establishes rebuildability of the recorded working-tree snapshots and bounded
functional paths, not every native model, all paper analyses, convergence or bit-identical JARs.

An earlier verification on 15 October 2026, before the subsequent P1/U2 revisions, rebuilt the six
working-tree snapshots and passed the complete UI
suite: **37 test classes, 262 tests, zero failures/errors/skips**, using only the newly built bundles
and reference fixtures. Core reported 323 tests (321 passed, two existing converter tests skipped);
AGT passed 77 and BEAST3 passed 351. The bounded package selections passed PopFunc 20 + 4,
Phylonco 25 + 23, Relax 5 + 6, and CPP 41 + 10 unit/workflow + packaged/reference tests.
The standalone PopFunc-paper L86 preparation passed 36 reference/update checks and a 30-step chain.
The initial isolated build exposed the Phylonco POM issue above; a sandboxed UI attempt then failed
to write the JavaFX cache. The complete desktop-permitted rerun passed without disabling tests.
After that run, the report check was strengthened to require every test class; it was separately
validated against those complete reports and synthetic incomplete/failing reports. This was a
report-validation change only, not another backend build or MCMC run.

### Source and dependency handoff

The verified baseline is the **working-tree snapshot**, not just a branch or commit. On 15 October
2026 the following source checkouts supplied the successful rebuild. Counts exclude `target`;
they describe all local changes, not a proposed commit grouping or a minimal model dependency set.

| Source repository | Branch / HEAD | Required modules | Modified / untracked files |
| --- | --- | --- | --- |
| `CODEPhylo/phylospec` | `haoyuan/mixture-alignment` / `08f5e3d2` | parent, core/java, integrations/beast3/{processor,java,popfunc} | 19 / 24 |
| `CODEPhylo/phylospec-ui` | `haoyuan/agt-ui-integration` / `7828624` | UI, tests, examples, build script | 21 / 26 |
| `LinguaPhylo/PopFunc` | `codex/phylospec-adapter` / `b0eb1e4` | parent, popfunc-beast | 0 / 0 |
| `bioDS/beast-phylonco` | `haoyuan/phylospec-adapter` / `e2b4fa1` | parent, phylonco-beast, phylonco-phylospec | 10 / 10 |
| `BEAST2-Dev/RelaxClockAveraging` | `haoyuan/phylospec-adapter` / `1c1c33a` | parent, mixture-beast, phylospec-adapter | 8 / 10 |
| `BEAST2-Dev/CalibratedCPP` | `haoyuan/phylospec-adapter` / `1f1e4f5` | parent, calibratedcpp-beast, phylospec-adapter | 2 / 61 |

These are locally observed Git identities, not confirmation that those branches or changes are
available remotely. In particular, CPP's entire 61-file adapter is untracked. PopFunc's authoritative
adapter is `phylospec/integrations/beast3/popfunc`, **not** the old constant-only `PopFunc/popfunc-phylospec`
prototype. Do not replace it with that prototype when preparing a collaborator's environment.

To prepare a source/dependency inventory without compiling or rerunning MCMC:

```bash
bash bin/verify-packages --prepare
```

This uses the same optional workspace/cache arguments as the full command. It prints **PREPARED
ONLY**, not PASS. New evidence includes `sources.tsv` (full commit, branch, origin, archive),
`<repo>.untracked.files` (NUL-delimited), and `<repo>.source.tar.gz` with `source-archives.sha256`.
The archives contain the copied inputs **before** any build, without `.git` or `target`; inspect
their contents before sharing. They are local artifacts, not automatic publication or Git commits.
They preserve source inputs, not Git history; the verifier still expects six Git checkouts. Extracting
the archives alone is not a replacement for that prerequisite or a complete runnable release.
`git diff HEAD` alone omits untracked contents. Also, core schema generation overwrites 13 generated
Java files in the build copy; use these pre-build archives, not the later `sources/` directory, as
the original input. The original checkouts are not modified by that generation.

Dependency handoff includes these inputs:

| Input | Recorded version / provenance | Action |
| --- | --- | --- |
| BEAST runtime | `io.github.compevol:{beast3,beast-base,beast-pkgmgmt,beast-fx}:2.8.0-beta7` | Keep this version; the local `2.8.0-SNAPSHOT` checkout is not a substitute. Cache records identify `central`. |
| Other model dependencies | `beast-labs:2.1.0-beta2`, `substmodels:1.3.2`, `beast-orc:1.3.1`, `beagle:1.0.0` | Cache records identify `central`; retain hashes and the POM-declared dependency graph. |
| Phylonco ancestor POMs | `io.github.linguaphylo:linguaphylo:pom:1.6.1`, then `io.github.linguaphylo:lphybeast-root:pom:1.3.0` | Locally installed prerequisites, not rebuilt by the six-repository script; obtain/install as below. |
| Phylonco vendored binaries | `phylonco-beast/lib/`: BEAST 2.7.8, BEASTlabs 2.0.2, NestedBD 1.9.6, MutableAlignment 0.0.2, Nashorn | Preserve the tracked files and companion source/version files. `phylonco-vendored.sha256` inventories all JARs, including source JARs. |

`third-party.sha256` and `cache-origins.tsv` describe the **seed cache**, not a minimal lockfile.
The successful run's seed contained 1,963 JAR/POM files, including unused versions. `local-install`
means Maven recorded an install, not that the artifact cannot exist elsewhere; a repository ID is
not a fresh network verification. Runtime bundles include copied dependencies as well as newly
compiled code. Do not call every JAR in `built-jars.sha256` a freshly compiled project artifact.
Review dependency licenses/source provenance before redistributing binaries; the root repository
license alone does not identify every vendored JAR's license. Nashorn's precise upstream provenance
and per-binary notices remain a packaging review item. No public binary release is certified here.

The two ancestor POM URLs on Maven Central returned HTTP 404 on 15 October 2026. Their exact source
files **were** fetched successfully from these pinned public commits and matched the cached bytes:

- [linguaPhylo POM at dd3fc67](https://github.com/LinguaPhylo/linguaPhylo/blob/dd3fc6723d8a4bece920e380a13fb9bafa5e87ce/pom.xml),
  SHA-256 `5b197367baffeb2f6817bd29be2526ff73492a4f53a08dff35d8bb120288def1`.
- [LPhyBeast POM at d9fc1ca](https://github.com/LinguaPhylo/LPhyBeast/blob/d9fc1ca3c28c3085b116d88c18b2f081218a5a7f/pom.xml),
  SHA-256 `7507f4f2fb7e194d872c004701014014f4045501c39c11ad8fe18190a67d54c7`.

A collaborator can save those exact POMs, verify their checksums, and install only the parent
projects, in order, into the cache that will seed `verify-packages`:

```bash
# Replace the three paths with the downloaded files and the collaborator's dependency cache.
mvn -N -f /path/to/linguaphylo.pom -Dmaven.repo.local=/path/to/dependency-cache install
mvn -N -f /path/to/lphybeast-root.pom -Dmaven.repo.local=/path/to/dependency-cache install
```

`-N` does not build LPhy or LPhyBeast's child modules. The six-repository script now fails early
with the missing POM path if these prerequisites are absent, and records their hashes in
`required-parents.sha256`. The Flatten Plugin step is still needed for Phylonco's own installation
POMs. Ordinary third-party dependencies/plugins and platform-specific JavaFX binaries must also be
prepared; copying two POMs does not constitute an empty-cache bootstrap.
Both parent installations were actually tested in a temporary Maven repository seeded without
those two POMs; the installed bytes matched the hashes above. Other cached plugins/dependencies
were retained. This prerequisite check did not rerun any model test or MCMC chain.

At that earlier inventory-only stage, only UI `README.md` and `bin/verify-packages` had changed
relative to the recorded build. The later P1/P2/P3/U1/U2 checks below record subsequent changes;
the old build must not be presented as verification of them. Publishing a reproducible revision
set still requires deciding
where the uncommitted adapter sources will be made available. No push, PR, or release was performed.

### P1 pre-commit verification (15 October 2026)

P1 is a proposed source group, not a commit: PhyloSpec HEAD `08f5e3d2` plus the 26 selected
core/BEAST changes, including the PopFunc `TileLibrary` compatibility change for `TreeIntervals`.
It excludes the other pending PopFunc changes (P2), cross-package examples (P3), and their tests.
This subset was built in an isolated source copy and Maven repository, without reusing installed
PhyloSpec/PopFunc artifacts or existing `target` outputs. Unchanged PopFunc sources were rebuilt
from local HEAD `b0eb1e4`; native tests were not run. Schema generation affected only the copy.

The initial existing suites passed: core 323 cases (321 passed, two existing converter skips), processor
77, BEAST3 351, and the HEAD-version PopFunc adapter 51, with zero failures/errors. These 51
PopFunc cases are not the pending P2 suite: they do not rerun or resolve its three known
Cons–Exp–Cons failures. The P1 check also does not validate P2/P3, other adapters or UI again.

**Initial result: P1 was blocked by mixed scalar/index binding.** A separate temporary `LiveVectorCompositionProbeTest`
ran six checks: four passed and two failed. Semantic validation accepted all three combinations;
an indexed-element-only vector built correctly, but combining an indexed element with a literal
or an independently sampled scalar failed during tiling. For example:

```phylospec
Vector<PositiveReal> r ~ IID(base=LogNormal(logMean=0.0,logSd=1.0),num=2)
Simplex p ~ Dirichlet(concentration=[1.0,r[1]])
```

`LiveVectorTile` reused `ListVectorTile`'s exact element `TypeToken` intersection. Ordinary
positive scalar candidates expose `RealScalarParam<PositiveReal>`, whereas `VectorIndexTile`
exposes `RealScalar<PositiveReal>`; the intersection loses their compatible scalar representation.
This was a PhyloSpec binding-composition gap, not a native model defect or invalid public semantic
type. That initial diagnostic ran only in the temporary copy, without production or native-model
changes.

**Follow-up: the mixed-binding blocker is fixed and P1 has been reverified.** `LiveVectorTile`
now selects candidates compatible with `RealScalar<D>` for the same exact domain `D`, rather than
requiring identical implementation classes. It still uses the existing list application and
shared-input checks. It does not widen signed or probability domains into strictly positive ones,
copy live values, or create additional state, priors or operators.

`LiveVectorTileTest` now has 22 passing cases (16 added), including both element orders, shared
and loop-indexed views, long arrays, invalid domains, and vector-only/scalar-only/joint proposals
with acceptance and rejection. The proposal tests evaluate the posterior before checking child
caches against fresh native distributions, and repeat the proposal after acceptance/restoration.

A fresh HEAD + P1 source snapshot and isolated Maven repository passed core **323** cases
(321 passed, the same two converter skips), processor **77**, BEAST3 **367**, and the HEAD-version
PopFunc adapter **51**, with zero failures/errors. Native PopFunc was rebuilt unchanged with its
tests skipped. The source snapshot and logs were retained; verification did not modify the working
source files. These results close this P1 blocker only: pending P2 failures, P3, other adapters and
UI suites were not rerun. No commit, push, native model change or public type relaxation was made.

### P2 verification (15 October 2026)

P2 was checked as HEAD + the verified P1 group + the pending PopFunc module sources and its own
examples. P3 cross-package examples and UI behavior were not changed or reverified. Core (323
cases, two existing converter skips), processor (77), and BEAST3 (367) passed in a fresh source
copy and isolated Maven repository. Native PopFunc was rebuilt from unchanged local HEAD
`b0eb1e4`, with native tests skipped; no previously installed PhyloSpec/PopFunc artifacts were used.

The initial P2 unit run reproduced **73 cases: 70 passed, three failed**. The failures are not
incorrect argument bindings:

| Retained check | Diagnosis |
| --- | --- |
| Cons–Exp–Cons inverse intensity, growth rates 0.2 and 1e-12 | Native `getInverseIntensity` returns zero unconditionally. Both cases first fail at time 1.99999999, before reaching later forward-intensity assertions. |
| Cons–Exp–Cons live population size | Native ancestral size is cached only during initialization. Changing NC from 1000 to 1500 leaves ancestral size at 201.8965179946554 instead of 302.84477699198305. This direct Java mutation bypasses the adapter's existing constant-only restriction; it is not a supported PhyloSpec estimation workflow. |

A separate Java diagnostic constructed only native objects, without Tiles or the adapter subclass.
It reproduced both inverse failures and the stale cache. It also checked forward intensity
independently: at growth rate 1e-12 and time 6, the native result is 0.005999911513119514 versus the
stable analytical value 0.006000000000008 (tolerance 1e-14). This additional precision issue was
masked by the original test's earlier inverse assertion. Of five direct-native checks, one passed
and four failed. No native formula, cache, inverse function or test tolerance was changed.

One adapter defect was fixed: overflowed positive floating-point literals could reach native NC/r
inputs as positive infinity. `ValidatedConsExpCons` now rejects non-finite or non-positive
`recentPopulationSize` and `growthRate` before calling the native initializer, in addition to its
existing age check. Valid calculations still delegate to the native implementation. The component
JSON, generated binding types, priors and operators were not changed.

Regression tests add two DSL overflow rejections, four genuinely deterministic-input rejections
(distinct from expressions depending on sampled inputs), and two packaged CLI overflow rejections.
The latter require prompt nonzero exit, the parameter-specific message and no MCMC trace output.
The overflow tests failed against the old guard and passed after the fix. The focused 29-case
unit/workflow selection passed. Original native diagnostic assertions remain enabled and unchanged;
only comments were added to clarify their scope.

The final unrestricted unit run is **79 cases: 76 passed, the same three failed**, with no errors
or skips; normal `verify` stops before packaging. A separate explicit selection excluding only
the two diagnostic methods (three cases) passed **76 unit/workflow cases and six packaged
integration cases**. This is not an all-green P2 suite or a scientific-correctness certification.
The passing fixed-population short chain does not exercise the missing inverse: the current
Coalescent Tile initializes its tree with a separate constant population function.

To repeat the supported-path selection, run from the PhyloSpec checkout after building the matching
core/BEAST artifacts and unchanged native PopFunc:

```sh
mvn -o -f integrations/beast3/popfunc/pom.xml -Djava.only=true \
  '-Dtest=*Test,!PopFuncEquivalenceTest#generatedConsExpConsMatchesAnalyticalBoundaries,!PopFuncEquivalenceTest#generatedConsExpConsReadsLivePopulationSize' \
  verify
```

Remove `-Dtest` to include the retained native diagnostics. Use `*Test`, not `*`: the latter also
selects `PackagedAdapterIT` in Surefire before the bundle and Failsafe property exist. One temporary
verification command made that selection error (six setup errors), then was corrected and rerun
successfully. Full-run, selected-run and native diagnostic evidence were retained separately.
No tests were disabled in source or POM configuration. No commit or push was performed.

### P3 cross-package verification (15 October 2026)

P3 is limited to the two source-launched regressions in PhyloSpec's
`integrations/beast3/examples`, using the current P1/P2 sources and compatible package adapters.
It does not add more model variants or resolve P2's retained native diagnostics.

| Example | Scope | Executed result |
| --- | --- | --- |
| `popfunc-l86/L86Analysis.java` | PopFunc + Phylonco; pinned PopFunc-paper XML, 32 taxa × 598 sites, four demographic models and ancestral selection | Exact taxon/GT16 data checks; 36 reference checks (eight indicator settings and acceptance/restoration of 14 state variables); 30-step chain passed. |
| `cats/CatsAnalysis.java` | CalibratedCPP + RelaxClockAveraging; `cats-half_uniform_mrca_not_conditioned_b3.xml`, 19 taxa × 30141 sites, four calibrations and 36 continuous branch rates | Exact taxon/sequence checks, matched parameter/CPP/calibration/likelihood/posterior densities and branch rates; 100 rejection/restoration checks (78 valid proposals); 200-step chain passed. |

The example-only fixes avoid interpolating filesystem paths into PhyloSpec strings: Cats uses
`cats.nex` relative to its script, and L86 passes its output prefix to the existing runner rather
than inserting an absolute prefix into the script. Both now pass with spaces, a quotation mark
and literal `${missing}` in their output directory. L86's update checks select the population
model that actually uses each demographic parameter, instead of checking an inactive model's
prior alone. Cats checks the restored cached posterior before forced recalculation and verifies
finite trace values, posterior sums, final sample/tree and state size. Native models, public
types, priors and operator configuration were not changed.

The current working sources were copied without existing `target` outputs and the first-party
artifacts rebuilt into an isolated Maven repository (JDK 25, BEAST 2.8.0-beta7). Core **323** cases
(two existing skips), processor **77**, and BEAST integration **367** passed. Package native/adapter
builds succeeded with tests explicitly skipped; that does **not** constitute a full package test
pass. The example launchers were then run from the revised source against those rebuilt bundles.
All 36 same-named JARs shared by the PopFunc and Phylonco bundles are byte-identical; the BEAST
base/pkgmgmt and PhyloSpec core/beast3 JARs also match the CPP bundle. Cats adds only the two Relax
package JARs to the CPP runtime. This verifies these selected artifacts, not arbitrary SNAPSHOT
mixing: Phylonco's native POM still declares system-scope BEAST 2.7.8, which is not in this bundle.
Headless UI regressions also passed with zero skips: `MultiPackageTest` **4**, `CalibratedClockTest`
**8**, and `PopFuncL86Test` **5**. They exercise model import/export and child-JVM execution, not
JavaFX controls. A one-character GT16 mutation retaining 32 × 598 dimensions was rejected by the
new data assertion; an existing output directory was rejected without overwriting it.

The L86 command is in the section below. To run the additional Cats regression from the PhyloSpec
checkout, with compatible sibling bundles already rebuilt:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
CPP_REPO="$(cd ../CalibratedCPP && pwd)"
CLOCK_REPO="$(cd ../RelaxClockAveraging && pwd)"
CPP_ADAPTER="$CPP_REPO/phylospec-adapter/target/calibratedcpp-phylospec-adapter-0.0.1-SNAPSHOT"
CLOCK_JARS="$CLOCK_REPO/phylospec-adapter/target/phylospec-adapter-0.1.0.jar:$CLOCK_REPO/mixture-beast/target/mixture-beast-0.1.0.jar"
CATS_RESULTS="$(mktemp -d /private/tmp/cats-p3.XXXXXX)/run"
java -Dbeast.debug=true -cp "$CPP_ADAPTER/*:$CLOCK_JARS" \
  integrations/beast3/examples/cats/CatsAnalysis.java \
  "$CPP_REPO/calibratedcpp-beast/src/test/resources/calibratedcpp/examples/cats/xml/cats-half_uniform_mrca_not_conditioned_b3.xml" \
  "$CATS_RESULTS"
```

These standalone checks are not automatically selected by Maven `test` or the UI acceptance
profile. `CatsWorkflowTest` uses the separate `CatsReferenceIT` fixture and its logging interval;
do not substitute this standalone output blindly. Full package suites, full UI/JavaFX acceptance,
BEAGLE and long chains were not rerun. Passing short chains do not establish convergence,
historical-engine equivalence or coverage of every paper configuration. No commit or push was made.

### U1 UI source-group verification (15 October 2026)

The proposed UI groups remain separate: **U1** contains 14 production files and nine related test
files (23 total); **U2** contains the acceptance profile, ignore rules, package/form tests and six
new example/data files (22 total); **U3** contains this README and `bin/verify-packages` (two files).
These are candidate commit boundaries, not additional branches or completed commits.

U1 was checked in an independent **UI HEAD `7828624` + exactly those 23 files** source copy,
without U2/U3 changes or existing UI `target` files. The HEAD POM, test fixtures and resources
suffice: U1 does not require U2 test helpers or its new examples. Its dependency is the compatible
PhyloSpec core artifact rebuilt and recorded during P3, including the verified P1 semantics; it
does not claim compatibility with an arbitrary older `0.0.1-SNAPSHOT` from a different checkout.

The production group keeps `Analysis`/`Param`/`Component`, shared vectors and partition rates,
`ScriptReader`/`ScriptWriter`, metadata checks and their controls together. References are links
to owned parameters, not extra draws; the writer declares dependencies once and refuses invalid
or dangling links. Loader checks run before adding a partition and before exporting it. This
avoids changing model ownership or silently exporting a loader the selected engine cannot run.

The initial `clean test` completed **174 reported cases: 145 passed, 29 skipped**, with no
failures/errors. The skips were existing package/display-conditional tests, including the one
U1 `LoaderFormTest`; this default command alone was not a complete U1 verification. Explicitly
selecting the nine U1 classes and seven existing core UI regressions, with JavaFX enabled, then
passed **146 cases, zero failures/errors/skips**: 102 U1 cases and 44 existing regressions.
The latter include script reading/writing, unlinked partitions and StarBEAST UI structure, not
an actual StarBEAST backend run.

The first JavaFX attempt failed before exercising controls because its native cache was not
writable in the restricted environment. A writable temporary cache removed that error, but
desktop screen access was also restricted and that process was stopped. The final run used the
same source and dependencies with desktop access and a temporary cache; the actual loader dialog,
rejection message and retention of supported files passed. No UI workaround or test exclusion
was added for these environment restrictions.

To repeat the selected checks (JDK 25 and an available desktop; run from the UI checkout):

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
UI_TESTS=EngineLibraryTest,EngineSupportTest,PackageMetadataTest,VectorEditingTest,LoaderSupportTest,ParameterReferenceTest,PartitionTest,SharedVectorTest,LoaderFormTest
UI_TESTS="$UI_TESTS,CommandLineTest,ScriptReaderTest,ScriptWriterTest,StarBeastTest,TypeParameterTest,UnlinkedPartitionsTest,ValidatorTest"
FX_CACHE="$(mktemp -d /private/tmp/phylospec-ui-fx.XXXXXX)"
mvn -o -Dtest="$UI_TESTS" -Dphylospec.fxTests=true -DreuseForks=false \
  -DforkedProcessTimeoutInSeconds=60 -Djavafx.cachedir="$FX_CACHE" test
```

The audited run also set `-Dmaven.repo.local` to the isolated P3 repository. On another machine,
first install the matching core artifact and cache the Maven dependencies. Do not enable
`-Pacceptance` for the U1-only snapshot: that profile belongs to U2. Running these checks in the
full working tree is useful regression coverage but does not replace checking the U1-only subset.

No new production/test fix was needed. Source hashes confirmed the 23 U1 files were unchanged;
only this U3 verification record was added. U2 package workflows/forms, U3 rebuild behavior and
the retained native-model failures were not reverified. No native model changes, staging, commit
or push were performed.

### U2 examples, forms and acceptance verification (15 October 2026)

U2 was checked as **UI HEAD `7828624` + the 23 verified U1 files + the 22 U2 files**, without
U3's README/script changes or any existing UI `target` directory. Its six example/data files
use repository-relative input paths; the calibrated examples/data are already in HEAD. The
acceptance profile and ignore exceptions needed no changes. This checks the proposed source
group, not just the larger working directory.

Five U2 test files were strengthened, with no production UI or native model changes:

- `PrimatesWorkflowTest` now sends the actual form-edited analysis through its existing packaged
  Runner path, rather than running only a separately imported example. Both paths check the
  saved/reopened model, trace columns/row widths and final `STATE_10` tree.
- `GenotypeFormTest` and `ReadCountFormTest` now check the corresponding `Param.isEstimated()`
  after each checkbox click. Previously a click-twice check could pass even if the model had
  not changed. A small test-only row lookup reuses the existing form labels/layout.
- `GenotypeErrorTest` checks samples 0 through 30, required density columns, nonempty saved
  state and `STATE_30`; it continues to distinguish genotype strings from numeric columns.
  Its shared Runner helper and the invalid-input helper in `ReadCountTest` now terminate a
  live child in `finally`, including when the test thread is interrupted.

The dependency bundles came from the isolated P3 source rebuild, not old desktop `target` files.
Package source hashes still matched that recorded rebuild. Reference fixtures were regenerated
by **14 passing adapter cases**: `DatedTreeWorkflowTest` (1), `L86ReferenceIT` (11),
`CatsReferenceIT` (1) and `PrimatesReferenceIT` (1), all with zero skips. The current P3
`L86Analysis` launcher also passed its 32-taxon/598-site PopFunc + Phylonco comparison and 30-step
run. These fixtures have different purposes: the Phylonco skyline uses all 86 taxa, while Cats
UI uses the `CatsReferenceIT` fixture with per-step logging, not the standalone Cats launcher.

The full `-Pacceptance clean verify` then passed **37 test classes, 262 cases, zero failures,
errors or skips**. This includes all **14 U2 classes / 59 cases**, actual JavaFX controls,
U1 regressions and the four `PackageDeliveryTest` cases. Each delivery case exports/reopens
its example and starts a separate JVM with only its adapter bundle, without the UI/Maven test
classpath. All four produced finite 30-step traces, final trees and saved states. Cross-package
PopFunc/Phylonco and CPP/Relax workflows also passed; the primates short chains use 10 steps.
Report names were compared with all 37 `*Test.java` classes, so missing reports or skipped
classes could not be counted as successful execution.

Two separate negative configuration checks intentionally returned Maven failure: a nonexistent
PopFunc bundle and `phylospec.fxTests=false`. Both failed in `PackageDeliveryTest` with the expected
specific diagnostic and zero skips. These are successful guard checks, not unresolved model
failures. The normal acceptance run was performed afterwards from a clean UI build directory.

Repeat the full run from the UI checkout with JDK 25, desktop access and the compatible rebuilt
bundles/reference fixtures described above. Use `-Dphylospec.workspace` for a separate workspace,
`-Dmaven.repo.local` for its isolated cache and `-Dphylospec.popfuncL86Analysis` if that fixture is
outside the profile's default location. The tested invocation was:

```sh
JAVA_TOOL_OPTIONS=-Djava.only=true mvn -o -Pacceptance \
  -Dmaven.repo.local="$PACKAGE_CACHE" -Dphylospec.workspace="$PACKAGE_WORKSPACE" \
  -Dphylospec.popfuncL86Analysis="$POP_L86_ANALYSIS" \
  -Djavafx.cachedir="$FX_CACHE" -DforkedProcessTimeoutInSeconds=240 clean verify
```

Set those four path variables to the prepared cache, workspace, L86 script and writable JavaFX
cache before running; do not select only a subset of tests for the full gate. The test JVMs use
separate forks because JavaFX cannot restart after shutdown. JavaFX native-access/unnamed-module
warnings were present, but controls actually executed. Cats took about 104 seconds across its
four cases and passed without increasing its child timeout or weakening assertions.

This bounded acceptance does not rerun or resolve the documented broader native-model failures,
test BEAGLE, establish MCMC convergence, or cover every published analysis. U3's full rebuild
script was not executed in this step. No staging, commit or push was performed.

### U3 documentation and rebuild verification (15 October 2026)

The revised `bash bin/verify-packages` was run end to end with its default sibling workspace and
default dependency-cache input, JDK **25.0.3** and Maven **3.9.16**, with desktop access. All **14
stages passed** in a new temporary directory. It did not reuse U2's built bundles: all first-party
artifacts were excluded from the seed cache and rebuilt from the current working-tree snapshots.
U1/U2 input hashes matched the sources previously verified in their separate groups.

Only this README and the existing rebuild script changed in U3. The script now checks the JDK
early, aligns Java launchers with `JAVA_HOME`, normalises workspace/cache paths, propagates
`java.only=true` to child JVMs and uses a temporary JavaFX cache with a bounded UI fork timeout.
The report gate also rejects an unexpected number of suites. Build order, model implementations,
adapter test selections and individual Runner timeouts were not changed.

| Freshly executed scope | Result |
| --- | --- |
| Core | 323 reported: 321 passed, two existing deprecated LPhy/Rev converter skips |
| AGT processor | 77 passed, zero skips |
| BEAST integration | 367 passed, zero skips |
| Selected PopFunc adapter / packaged tests | 24 + 6 passed, zero skips |
| Selected Phylonco adapter / packaged-reference tests | 25 + 23 passed, zero skips |
| Selected Relax adapter / packaged tests | 5 + 6 passed, zero skips |
| Selected CPP adapter / packaged-reference tests | 41 + 10 passed, zero skips |
| PopFunc-paper L86 launcher | 36 reference/update comparisons and 30-step chain passed |
| Complete UI acceptance and report gate | 37 classes, 262 cases, zero failures/errors/skips; four package deliveries |

The UI result includes actual JavaFX controls and independent packaged Runner execution using
only these new bundles and regenerated reference fixtures. Native modules were compiled without
their full test suites; the selected adapter tests above are not the broader suites containing
the retained native failures. No test assertion, model formula, prior or operator was weakened.

Separate script checks also passed:

- `--prepare` with relative, space-containing workspace/cache paths produced **PREPARED ONLY**,
  no build/test results, and source archives without `.git` or old `target` content. Source,
  archive and dependency hashes verified; no first-party cache artifacts remained in the seed.
- Extra arguments, missing cache and missing prerequisite parent POM each returned a clear
  nonzero exit before copying. A temporary Java stub reporting version 21 exercised the old-JDK
  rejection; this was a preflight simulation, not a real JDK 21 build.
- The exact embedded report gate accepted U2's complete reports and rejected seven temporary
  mutations: missing suite, failure, error, skip, zero cases, fewer delivery cases and an extra
  suite. The fresh U3 reports subsequently passed the same gate.

Checksums confirmed all six original source inventories matched the pre-build inputs, and all
5,255 original Maven-cache files were unchanged with no additions/removals. Only this final
README result record was added after the successful build; the executed script is unchanged.
The syntax check (`bash -n bin/verify-packages`) and `git diff --check` also passed. No commit or
push was performed. The repeat command remains the one under **Rebuilding the acceptance
environment**; `--prepare` alone is never a substitute for that complete run.

This establishes rebuildability of the recorded source-and-cache combination, not an empty-cache
bootstrap, availability of uncommitted sources on GitHub, a public binary release, BEAGLE support,
complete native test coverage, convergence or reproduction of every paper result.

### Reference-configuration audit (14 October 2026)

This is the current cross-package checklist, not another expansion of model coverage. The four
baseline examples in the next section already pass. The references below come from the local
package checkouts; they have **not** been confirmed as the complete set of analyses required by
the papers. A checked reference does not certify every model in its package.

| Package / local reference | Backend evidence | UI evidence | Decision for this reference |
| --- | --- | --- | --- |
| PopFunc: `examples/jcCoalGompertz_f0.lphy` | `ancestral-selection.phylospec` preserves the four demographic priors, uniform `I_na`, shared parameters, inactive `NA` prior and clock rate 0.01. `AncestralSelectionTest` has five passing cases; the packaged example runs. Its four-taxon inference data are not the original 40-taxon, 1000-site simulation. | `PopulationSelectionTest` and `PopulationFormTest` now pass ten cases: shared component-vector/scalar references, copy and rename behavior, invalid-input rejection, actual JavaFX editing and packaged 30-step execution. | **Accepted for this bounded UI composition on replacement data.** No new Gompertz Tile or native model change was needed. Do not describe this as full reproduction of the LPhy simulation. |
| RelaxClockAveraging: `mixture-beast/src/test/resources/mixture.beast/examples/mixture-typed.xml` | `ReferenceDataTest` passes four native-XML comparisons on all 31 taxa and 600 sites. `DatedTreeWorkflowTest` also passes a 30-step run with an estimated dated tree, shared mean rate, gamma site rates and UC/AC selection. It appends the original ages to taxon labels for `fromNexus(age=parse(...))`. | `DatedClockTest` passes three cases: full-data import/export/reopen and packaged execution, actual JavaFX date configuration followed by execution, and rejection of an unmatched date parser. | **Accepted for this bounded reference.** Existing UI controls suffice; no new clock variant, Tile or production UI change was needed. |
| Phylonco: `examples/L86/L86_rskycoal_em_beagle.xml` | `L86ReferenceIT` passes eleven cases on all 86 taxa and 598 sites: native comparisons and a packaged run, with GT16/error observation, conditional skyline population priors and event groups. | `SkylineFormTest` configures the actual analysis through JavaFX, exports/reopens it and runs the packaged 30-step chain. | **Accepted for this bounded reference.** No additional L86 variants are required unless selected as a separate target. BEAGLE equivalence and the original operator schedule are not claimed. |
| CalibratedCPP: `calibratedcpp-beast/src/test/resources/calibratedcpp/examples/cats/xml/cats-half_uniform_mrca_not_conditioned_b3.xml` | `CatsReferenceIT` passes the full 19-taxon, 30141-site native-XML comparison and packaged run, including four calibrations and continuous IID branch rates using the Relax adapter. | `CatsWorkflowTest` passes four cases: actual JavaFX controls, export/reopen and short packaged runs, plus invalid-dimension and unknown-taxon rejection. | **Accepted for this bounded reference.** No additional cats variants are required unless selected as a separate target. |

The backend assertions establish the particular mappings, prior factors and update behavior they
exercise. Short chains establish functional execution, not convergence, equality of trajectories
or reproduction of published results. The L86 normalized event-group prior differs from the XML's
implicit uniform factor by a fixed log constant, as detailed below.

The initial audit read evidence from existing passing runs, rather than rerunning a full campaign:
PopFunc selected tests **23 + 4** unit/integration; Relax **39 + 6**; L86 **6 + 11**;
CPP cats/binding selection **61 + 8**; the full opted-in UI run **214/214**, including the four
baseline cases, L86 and cats. These selections do not supersede the broader failing backend checks
recorded below. The initial read-only PopFunc UI import probe reproduced:

```text
indicator is drawn from a distribution, which this tab cannot express for a value of type NonNegativeInteger.
```

This was an editor limitation, not a parser/AGT installation failure. The subsequent change adds
`uiHints.widget="indicator"` to PopFunc's selection input, editable component vectors, and references
to the same sampled scalars/vector. It does not make arbitrary integer settings estimable, fix the
indicator, duplicate priors, or change the backend's model definitions. The selected backend tests
were rerun: **26 unit/workflow + 4 packaged integration tests pass**. The UI workflow is described below.

The audit also rechecked tracked and untracked native module files against the local upstream
snapshots listed below: all four native modules are unchanged. It did not fetch newer remote
commits, change native models, alter Tiles or UI production code, or perform a commit/push.

#### Paper sources and concrete candidate targets

The follow-up audit inspected the package READMEs, local reference XML/LPhy files, adapter tests,
UI import/export code, and the public paper repositories linked by the packages. These are
initially **candidate targets awaiting collaborator confirmation**. The user subsequently selected
PopFunc-paper L86; its implementation and checks are described below. The other candidates are
not additions to the acceptance gate.
An available file is not evidence that it is required, and an untested configuration is not
automatically a missing Tile.

Paper-source snapshots inspected on 14 October 2026:

- [PopFunc-paper](https://github.com/yxu927/PopFunc-paper/tree/10b0f82db2da9aa5f48fc84f231128bfc98e115e),
  commit `10b0f82db2da9aa5f48fc84f231128bfc98e115e`, linked by
  `PopFunc/docs/Examples_and_Tutorials.md`. Its real-data analyses are separate from the local
  `jcCoalGompertz_f0.lphy` simulation.
- [beast-phylonco-paper](https://github.com/bioDS/beast-phylonco-paper/tree/90966017b01a35557edaf92cfdb3e95ae1be3a2f),
  commit `90966017b01a35557edaf92cfdb3e95ae1be3a2f`, linked by `beast-phylonco/README.md`.
  This accompanies Chen et al. (2022), *Accounting for errors in data improves divergence time
  estimates in single-cell cancer evolution*. It does not establish which recent analysis the
  task referring to Yao requires.
- Relax's local `mixture-beast/src/test/resources/mixture.beast/examples/README.md` identifies
  maintained XML examples, but its root README still has no paper citation. CPP's local
  `scripts/primates/README.md` explicitly identifies the configurations behind the primates figures.
  The native snapshots remain the ones recorded below; no package branch was updated.

Phylonco follow-up: Yao Xiao's abstract in the
[Waiheke 2026 programme, page 20](https://compevol.github.io/Waiheke2026/Waiheke2026_programme.pdf#page=21)
describes joint genotype/tree inference from DNA read counts with 16 diploid states.
This supports prioritizing a read-count reference, but does not identify the supervisor's required
dataset, configuration or execution settings. A read-only XML audit of the local examples found:
`test_read_count_model.xml` has 10 taxa x 50 sites, fixed genotypes and no tree likelihood;
`test_MA_read_count_model.xml` has 20 taxa x 500 sites and joint inference, but binds the old `w`
input, while the current native likelihood declares `w1` and `w2`. Neither file is an established
current paper target. Do not invent a conversion of the old prior or add a fixed-genotype workflow
merely to replace the missing target. Obtain one current runnable XML/LPhy file, its exact data and
BEAST/package revisions from the author, then compare it with the existing read-count binding.
This follow-up parsed XML and inspected code/reports; it did not execute either reference or rerun
Maven/MCMC. The previously recorded joint-update failures are not resolved by this audit.

| Candidate source | Relationship to accepted work | Concrete gap / next check, if selected |
| --- | --- | --- |
| PopFunc-paper `real/L86/data/L86.xml` | Inline alignment: **32 taxa x 598 sites**, four population models, shared ancestral indicator, GT16/error likelihood. Distinct from the 86-taxon skyline analysis. | **Selected and checked:** exact XML data, eight indicator combinations, shared priors/state, combined adapters, UI round-trip/controls and short chain now pass; see the L86 composition section. The XML's coupled MCMC and published estimates are not reproduced. |
| PopFunc-paper `real/hcv/data/hcv.xml` and `hcv.nexus` | Inline alignment: **63 taxa x 411 sites**, three codon partitions, five population models and an ancestral indicator; not the accepted two-state Gompertz example. | **Binding boundary + expression/UI verification:** its Cons-Exp-Cons inputs `NC/r/x/tau` are sampled, while the current binding permits only constants because of the recorded native cache problem. Do not fix those inputs or drop that model. Partition mutation-rate constraints and coupled MCMC also need separate checks. This exact configuration is not currently accepted. |
| Phylonco-paper `L86/data/L86_rskycoal_em_fast.xml`; `E15/data/E15_rskycoal_em_fast.xml` | The accepted reference is the modern package's local `examples/L86/L86_rskycoal_em_beagle.xml`, not these older paper XMLs. The paper also lists simulation analyses under `sim1` through `sim7`. | **Target/provenance confirmation:** identify the intended paper and exact analysis first. The old and modern XMLs have not been compared here for identical data, priors or execution settings. For a read-count target, obtain its actual observed counts and configuration; the local `gt16ReadCountModel.lphy` example simulates counts conditional on a known genotype alignment. |
| Relax `mixture-typed.xml`, `strict-typed.xml`, `ucln-fixed-typed.xml`, `ac-fixed-typed.xml` in the maintained examples directory | Mixture/dates already have the accepted backend/UI path. `FixedClockWorkflowTest` already compares the three fixed-mode references with native densities and runs short chains; its existing report has **3 passes, zero skips**. | **Paper provenance, not another clock Tile:** confirm which reference/data correspond to the intended paper. Fixed-mode backend coverage does not establish a full-data UI reproduction for each mode. Keep the accepted mixture reference closed unless the agreed target differs. |
| CPP `calibratedcpp-beast/src/test/resources/calibratedcpp/examples/primates/xmls/primates_codon_deepCal_uniform-rep1.xml` and the same family's joint/`suggested`/`condFalse` files | Representative XML: **29 taxa, three alignments of 133154 sites each, nine calibrations**. The analysis README describes three calibration schemes x two conditioning modes, plus prior-only runs. The accepted cats reference has four calibrations and a single alignment. | **Uniform-rep1 selected and checked:** full-data native density/live-update comparisons, UI import/export, three partition forms, shared vector priors and packaged short-chain execution are verified; see the primates workflow section below. The other five configurations are not selected targets. |

There is a concrete PopFunc data-provenance discrepancy: at the pinned paper snapshot,
`real/L86/data/L86meta.fasta` has 34 records (one is 560 sites; the others are 598), whereas
`L86.xml` embeds 32 sequences of 598 sites. The FASTA includes `CO8-MA-25` and `CO8-MA-26`,
which the XML omits. Do not silently substitute that FASTA or the local 86-taxon dataset.
An XML-based comparison should use its embedded alignment; confirm the intended dataset with
the author before describing it as paper reproduction. This observation is not a model repair.

The existing UI supports separate alignment files and linking their tree/clock/site models.
`ScriptReader.readDataStatement` currently accepts file loaders, not an alignment-subset expression;
`Partition` has no codon-filter setting. Exporting exact codon partitions to separate files could
reuse this UI, with explicit checks of taxon order, site counts and shared parameter identity.
That route is now verified for the selected primates configuration below; it does not add a codon-filter editor.
`CalibrationModesTest` already exercises joint, independent-uniform and explicit MRCA age laws;
the primates target must be checked against those mechanisms before adding any calibration UI.

For the next agreed target, record the source commit/file, data/taxon/site selection, complete prior
and likelihood factors, sharing, conditioning and execution requirements. Then check, in order:
data preparation, PhyloSpec expression, engine binding, UI round-trip, and packaged execution.
Only a demonstrated failure at one of those boundaries justifies a targeted code change. Original
operator schedules, coupled MCMC and published statistical results must not be inferred from a
single-chain functional check.

**Selected targets:** PopFunc-paper's 32-taxon L86 composition is checked. The user then selected
CPP's primates uniform-rep1 reference; its prerequisite history and completed UI workflow are recorded below. This does
not confirm the teacher's complete required file list. Additional PopFunc files, the Phylonco
paper/read-count target and Relax's intended paper dataset still need confirmation.

The initial audit changed only this checklist. It re-read the existing 31 acceptance XML reports
(227 tests, zero failures/errors/skips), checked the local XML data dimensions and rechecked the
four native module trees against their recorded snapshots. It did **not** rerun Maven/MCMC,
execute paper XMLs, change native or Java code, clone paper repositories, commit or push.

#### Remaining-target check after the L86 acceptance

The user subsequently selected CPP's `primates_codon_deepCal_uniform-rep1.xml` as the next
bounded target. CPP's `scripts/primates/README.md` explicitly connects these
files to the paper figures. Phylonco's linked paper is the 2022 Chen et al. study, which does not
identify the recent Yao analysis requested by the supervisor; Relax's maintained examples still
do not identify the intended paper dataset. Obtain those exact paper/configuration references
before treating either package's additional examples as requirements.

At CPP commit `1f1e4f5157b3fa42d852da79f6b588139e186dfc`, inspection of all six
`primates_codon_deepCal*-rep1.xml` files confirms identical taxon-to-sequence maps in their three
partitions: 29 taxa, 133154 sites per partition (399462 sites in total), and nine calibrations.
The XML SHA-256 for the proposed first, independent-uniform conditioned reference is
`0e5b6988827de8c6c96f8509475c56eb805af1dc174b353b3e17be76e0cd1bed`.
This is one bounded reference, not approval to implement all six configurations or run
their 100-million-step chains.

The rate vector `r` has a native `beastlabs.math.distributions.WeightedDirichlet` prior with
concentration `[1,1,1]`, weights `[133154,133154,133154]` and default mean 1. Its XML operator is
`beast.base.spec.inference.operator.DeltaExchangeOperator`, with `rvparameter="@r"` and the same
integer weight vector. Each partition's site model reads the corresponding live `VectorElement`
of `r`; all three share the tree, continuous branch rates and overall mean clock rate. With equal
weights, `r` sums to 3, not 1. Independent rate priors or an unscaled simplex are not substitutes.
The existing beast-labs 2.1.0-beta2 source JAR already contains the distribution implementation;
any PhyloSpec binding should reuse it, not introduce another probability formula.

Five isolated Java construction probes were actually executed against the current compiled
core/BEAST classes on 14 October 2026. They do not run MCMC or certify the full reference:

**Historical diagnostic results:** the table below predates the subsequent partition-rate work.
The backend now has a WeightedDirichlet component and CPP adapter binding, fixed/loop-index
numeric-vector views, and optional `PhyloCTMC(mutationRate=...)`. It does not add a general RPN
scalar bridge or change Normal's generic input contract. New tests use compatible scalar
consumers; the earlier Normal probes are not claimed to have all become supported.
See CalibratedCPP's README section "Primates partition-rate prerequisite" for current verification.
The exact 29-taxon empirical reconstruction and UI workflow have since been verified in the
primates section below; the bounded L86 acceptance is unchanged.

| Probe | Actual result | Boundary |
| --- | --- | --- |
| `WeightedDirichlet(...)` | Type resolution rejects an unknown function | Missing public component definition and binding |
| Indexed scalar LogNormal draws, then `hky(kappa=r[1],...)` | Builds and initializes; three state nodes | Existing list/index path works and should remain unchanged |
| An IID positive vector, then `hky(kappa=r[1],...)` | Tile selection requires `List<?>` | Native numeric-vector element binding is missing on this path |
| A Dirichlet simplex, then `Normal(mean=r[1],...)` | Tile selection requires `List<?>` | An unscaled simplex does not bypass the numeric-vector binding gap |
| `PositiveReal rate=a*b` from two LogNormal draws, then `hky(kappa=rate,...)` | Tile selection requires `RealScalar<PositiveReal>` | Current RPN output is not a scalar engine-input bridge on this path |

At that initial audit, core `PhyloCTMC` had no separate partition mutation-rate argument.
The subsequent prerequisite added that input without relaxing public types. Shared state and
live updates were checked before the UI work. The selected reference now reuses existing
calibration handling, linked partitions and `sharedRatesClock(...,normalize=false)` with IID
branch rates, plus separately exported codon files. This history is not a remaining task list.

That historical follow-up changed only the audit text; the probes were temporary diagnostics. It did not
change model/Tile/UI Java code, modify native package implementations, rerun Maven or a full-data
MCMC, commit, or push. One positive construction and four demonstrated unsupported paths are
not five passing model tests. Keep the previous L86 acceptance closed while the next target is
being confirmed.

The Relax dated-data check and PopFunc UI composition have subsequently been completed as described below. Reopen the
accepted rows only for a demonstrated regression, changed agreed target or
dependency incompatibility. A newly noticed optional variant does not extend this milestone.

### PopFunc-paper L86 composition

The selected source is `real/L86/data/L86.xml` at PopFunc-paper commit
`10b0f82db2da9aa5f48fc84f231128bfc98e115e`. The unchanged XML, original MIT license,
PhyloSpec counterpart and executable check are in the sibling PhyloSpec repository under
`integrations/beast3/examples/popfunc-l86/`. `L86Analysis.java` verifies the XML SHA-256
`645b3ac9a90d6ec153fe07fcc096c12601c6d9f8f7190fbd1d0f6c1ed39f7c8a`, extracts exactly its
**32 taxa x 598 sites**, and refuses to overwrite an existing result directory. It does not
substitute the 34-record FASTA or the 86-taxon Phylonco analysis.

The model preserves four candidates (constant, exponential, logistic, Gompertz F0), seven shared
demographic draws, the four-way `I`, the independent binary `I_na`, all original demographic/error/
Dirichlet priors, GT16/error likelihood, and a fixed unit clock. Two nested selection functions
share `I`; the outer selection switches ancestral-population presence. All inactive parameters
retain their priors. There are 14 state nodes including the tree, 14 explicit prior factors and
one likelihood. Existing AGT mappings and handwritten observation/coalescent tiles are reused;
no new model mapping, public component type or native formula was added.

This composition exposed two integration gaps. `ListVectorTile` and its `LiveVectorTile` wrapper
now expose their children to the existing consistency check, preventing two distinct draws for
the same AST variable. The previous bug could produce an extra `I_2`. The UI allows references
for metadata-declared indicators and preserves a strictly narrower imported scalar type:
`PositiveReal b` remains positive even when its owning exponential input accepts `Real`.
Argument types are not relaxed; equivalent aliases such as `Rate` keep their existing spelling.
Copying, prior choices, reference menus and export use that retained scalar type.

Backend validation compares freshly constructed native objects with the PhyloSpec graph for all
eight indicator states and accept/restore changes of all 14 state variables (36 checks total),
then runs a separate 30-step chain with ordinary initialization. The two explicit uniform
indicator priors add **`-log(8)`** relative to the XML's implicit bounded-uniform factors; this
constant is checked explicitly, not treated as exact unnormalized log-density equality.
Five additional independent JVM runs passed the same checks after the binding fix.
`PopFuncL86Test` checks shared objects, priors, copy/rename, invalid references, save/reopen and
combined-adapter execution. `PopFuncL86FormTest` reconnects the shared indicator and narrowed
growth-rate scalar through actual JavaFX controls and executes the resulting export.

Prepare and check this fixture from the PhyloSpec repository (JDK 25, compatible bundles already
built; these are the targeted regression checks, not the complete backend suite):

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
cd /path/to/phylospec
mvn -o -Ppopfunc -pl integrations/beast3/popfunc -am \
  -Dtest=ListVectorTileTest,LiveVectorTileTest,AncestralSelectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PackagedAdapterIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
POP_ADAPTER="$PWD/integrations/beast3/popfunc/target/phylospec-adapter"
PHY_ADAPTER="$(cd ../beast-phylonco && pwd)/phylonco-phylospec/target/phylonco-phylospec-adapter-1.2.1"
L86_RESULTS="$(mktemp -d /private/tmp/popfunc-l86.XXXXXX)/run"
java -Dbeast.debug=true -cp "$POP_ADAPTER/*:$PHY_ADAPTER/*" \
  integrations/beast3/examples/popfunc-l86/L86Analysis.java \
  integrations/beast3/examples/popfunc-l86 "$L86_RESULTS"
cd ../phylospec-ui
mvn -o -Pacceptance -Dphylospec.popfuncL86Analysis="$L86_RESULTS/analysis.phylospec" verify
bin/phylospec-ui --adapter-path "$POP_ADAPTER" --adapter-path "$PHY_ADAPTER"
```

In the application, use **File -> Open** on `$L86_RESULTS/analysis.phylospec`. Tree Prior contains
the outer ancestral choice and two four-candidate lists; the second list's indicator references
the first list's indicator. Shared demographic inputs use **Reference...**, and Priors contains
13 sampled parameters, not separate priors for every input. The tree is the 14th backend state
node. Site Model and Likelihood show GT16 and its two estimated error probabilities. Save As to
a new file, reopen it, then run the saved file with:

```bash
java -Djava.only=true -Dbeast.debug=true -cp "$POP_ADAPTER/*:$PHY_ADAPTER/*" \
  runner.PhyloSpecCli --library popfunc,phylonco,beast2 /absolute/path/to/saved.phylospec
```

Choose a fresh MCMC output prefix before running an exported file. The prepared script has no
explicit `outputPrefix`: the source launcher passes the `analysis` prefix inside its result
directory to the runner, and the CLI uses the source path by default or the supplied `--run-name`.
If an exported file explicitly
sets `outputPrefix`, that value still takes precedence over `--run-name`.
Inspect `analysis.log` (31 finite samples, 0 through 30), `analysis.trees` (`STATE_30`), and
`analysis.state.xml` (14 state nodes). UI test exports remain in `target/popfunc-l86/accepted-*`.

**Limits:** the source requests four coupled chains and 20 million steps on historical BEAST
2.7.6 classes. These checks use the current BEAST 2.8.0-beta7/PopFunc/Phylonco APIs, a common test
tree for density comparisons and a single short chain. They do not run that historical XML,
recreate its initialization/operator schedule, establish historical-engine equivalence, infer
posterior model frequencies, demonstrate convergence, or reproduce published estimates.

The full backend command `mvn -o -Ppopfunc -pl integrations/beast3/popfunc -am install` was also
executed: core 322 cases (320 pass, two existing skips), processor 77/77 and BEAST integration
341/341 pass; PopFunc has 70 passes and three `PopFuncEquivalenceTest` failures concerning
Cons-Exp-Cons inverse-intensity boundaries and cached population-size updates. Those assertions
remain unchanged. This L86 target does not use Cons-Exp-Cons. The narrower binding/package run
passes 16 unit/workflow checks plus four packaged checks. Phylonco's selected GT16 tests pass
8/8; existing L86 and packaged integration tests pass 23/23. No native package source was changed.
The final complete UI `mvn -o -Pacceptance verify` passes **234/234, zero skips**. Initial regression
failures from converting the existing `Rate` spelling to its equivalent `PositiveReal` alias were
fixed by retaining equivalent aliases; no assertions were weakened. Backend and UI result files
are distinct, so this passing UI result does not hide the three broader backend failures above.

### Four-package delivery scope

The baseline below is intentionally finite: **one representative editable and runnable analysis per
package**, not every native class, parameter combination or published analysis. These paths are
covered by `PackageDeliveryTest`. The two new DNA examples reuse
`examples/calibratedcpp/four-taxa.nex`; keep the examples directory structure when copying them.

| Package | File to open with File → Open | Accepted baseline |
| --- | --- | --- |
| PopFunc | `examples/popfunc/gompertz.phylospec` | Gompertz F0 population function, four explicitly estimated demographic parameters, Coalescent and sequence likelihood |
| Phylonco | `examples/phylonco/gt16-error.phylospec` | GT16, latent sequence process, estimated error/dropout probabilities and observed genotypes |
| RelaxClockAveraging | `examples/clockmodelaveraging/mixture.phylospec` | Strict/relaxed likelihood mixture, UC/AC indicator, shared branch rates and hierarchical diagnostic log |
| CalibratedCPP | `examples/calibratedcpp/skyline.phylospec` | Skyline tree prior, fixed clade/root calibration data, signed diversification parameters and estimated ordered change time |

Keep these paths stable. Fix failures that lose parameters, alter priors/likelihoods/operators or
prevent an exported analysis from running. Additional model variants, broader partition/date
support, performance improvements and general refactoring are **not** prerequisites for this
baseline. Existing broader tests and the Phylonco read-count/CPP continuous-clock examples remain;
they are not removed or promoted into an unlimited delivery requirement.

The gate exercises adapter discovery, published engine capabilities, relative data paths, the UI
analysis model, a run-length edit, file save/reopen and standalone BEAST execution. It checks every
scheduled sample, finite posterior/prior/likelihood and selected parameter columns, final tree and
state output; Relax also checks its two switching operators and hierarchical diagnostic columns.
Runs have 30 MCMC steps. This is functional evidence, **not convergence, statistical equivalence or
paper-result reproduction**. The gate uses the models behind the tabs; existing `*FormTest` classes
separately exercise actual JavaFX controls. It does not automate mouse clicks for all four examples.

The following broad adapter/regression commands are retained for diagnosis. They assume JDK 25,
sibling checkouts and cached dependencies; they are not the current bounded rebuild recipe and
may stop at the known failures below. For rebuilding the accepted paths, use
`bash bin/verify-packages`. These commands do not run the complete native-package or LPhy test suites:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
cd ../phylospec
mvn -o -Ppopfunc -pl integrations/beast3/popfunc -am install
cd ../beast-phylonco
mvn -o -pl phylonco-phylospec -am \
  '-Dtest=tiles.phylonco.*Test,tiling.phylonco.*Test,phylonco.phylospec.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false verify
cd ../RelaxClockAveraging
mvn -o -pl phylospec-adapter -am '-Dtest=tiles.clockmodelaveraging.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false verify
cd ../CalibratedCPP
mvn -o -Pphylospec -pl phylospec-adapter -am '-Dtest=tiles.calibratedcpp.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PackagedAdapterIT verify
cd ../phylospec-ui
```

`install` above updates the **local** Maven repository for downstream adapter builds; it does not
publish to GitHub or a remote artifact repository. Use the exact profile spelling `-Ppopfunc`.
Rebuild the bundles after changing PhyloSpec: old copies of a SNAPSHOT JAR inside a bundle do not
update themselves.

From this UI repository, set the four bundle locations and run the bounded gate:

```sh
PHY_ROOT="$(cd .. && pwd)"
POP_ADAPTER="$PHY_ROOT/phylospec/integrations/beast3/popfunc/target/phylospec-adapter"
PHYLONCO_ADAPTER="$PHY_ROOT/beast-phylonco/phylonco-phylospec/target/phylonco-phylospec-adapter-1.2.1"
CLOCK_ADAPTER="$PHY_ROOT/RelaxClockAveraging/phylospec-adapter/target/phylospec-adapter-adapter-0.1.0"
CPP_ADAPTER="$PHY_ROOT/CalibratedCPP/phylospec-adapter/target/calibratedcpp-phylospec-adapter-0.0.1-SNAPSHOT"
mvn -o -Dtest=PackageDeliveryTest -Dphylospec.deliveryTests=true \
  -Dphylospec.popfuncAdapter="$POP_ADAPTER" \
  -Dphylospec.phyloncoAdapter="$PHYLONCO_ADAPTER" \
  -Dphylospec.clockAdapter="$CLOCK_ADAPTER" \
  -Dphylospec.calibratedAdapter="$CPP_ADAPTER" test
```

Expected result: **4 tests, no failures, errors or skips**. This integration gate is disabled in a
normal UI-only build. Once enabled, any missing bundle fails the gate rather than silently skipping
one package. Outputs are retained in fresh `target/package-delivery/<package>-*/` directories:
`analysis.phylospec`, `runner.txt`, `analysis.log`, `analysis.trees`, `analysis.state.xml` and the
Relax diagnostic log. Runs do not overwrite each other; `mvn clean` removes this evidence with
the rest of `target`, so copy a directory elsewhere before cleaning if you want to retain it.

To inspect one path manually, for example PopFunc:

```sh
mvn -o package
bin/phylospec-ui --adapter-path "$POP_ADAPTER"
# File → Open → examples/popfunc/gompertz.phylospec; inspect Tree Prior and Priors, then save.
PHY_RUN=$(mktemp -d /private/tmp/popfunc-delivery.XXXXXX)
PHY_SCRIPT="$PWD/examples/popfunc/gompertz.phylospec"
(cd "$PHY_RUN" && java -Djava.only=true -cp "$POP_ADAPTER/*" runner.PhyloSpecCli \
  --library popfunc,beast2 "$PHY_SCRIPT")
```

Replace `PHY_SCRIPT` with the absolute path of your saved file to test your export. For the other
rows use their bundle/file and library ID `phylonco`, `clockmodelaveraging` or `calibratedcpp`, each
followed by `,beast2`. Each example sets `outputPrefix="analysis"`, so use a fresh working directory;
`--run-name` does not override an explicit script output prefix. The gate tests one bundle per
package; existing `MultiPackageTest` and `CalibratedClockTest` cover selected package combinations,
not arbitrary version mixtures.

**Compatibility update (13 October 2026):** local native-source modifications have been withdrawn
and backed up outside the repositories. PopFunc's native artifact was rebuilt and installed locally;
Phylonco and CPP native modules were clean-built with their adapters. The four-path gate still passes
(4/4), and the full UI suite passes (197/197, including the gate, enabled JavaFX tests and PopFunc
capability checks), using
BEAST `2.8.0-beta7`. This covers these local upstream snapshots, not a fresh network fetch:
PopFunc `527e214`, Phylonco `f38d11a`, RelaxClockAveraging `73b7977`, CalibratedCPP `1f1e4f5`.
The native modules match those snapshots; adapter code remains separate.

**Recorded broader backend failures on unchanged native snapshots:** these figures describe
the historical suites identified below, not a rerun by the bounded acceptance gate.

| Package | Unresolved checks outside the four-path gate |
| --- | --- |
| PopFunc | On 14 October, 70 of 73 adapter unit/workflow cases pass; the same three Cons–Exp–Cons analytical/inverse-intensity/live-parameter assertions still fail. The binding rejects non-constant Cons–Exp–Cons inputs rather than claiming that parameter estimation works. |
| Phylonco | On 14 October, 282 of 306 adapter cases pass; the same 24 cases fail when one proposal changes a read-count parameter and a latent genotype together. The native partial-taxon cache retains stale contributions. Separate parameter/genotype proposals and the registered Gibbs operator are checked independently below. |
| CalibratedCPP | The 13 October run recorded 482 adapter cases with 9 failures and 4 errors. The later full run on 14 October recorded 496 cases: 483 passed, with the same 9 failures and 4 errors involving Erlang criticality, singular/invalid runtime rates and non-finite origins. These cases need native-author review. Initial constant-rate validation is performed in the adapter using the unchanged native conversion. |

The bounded rebuild selects supported adapter/reference tests and compiles native modules without
running their full suites. Its PASS does not mean these broader failures were rerun or repaired.
Zero skipped tests is required for UI acceptance; the core run retained its two documented
converter-test skips. Older counts elsewhere in this document describe their own dated selections,
not a newer full-suite result. Preserve the failure logs before a selected run replaces test reports.

These assertions remain enabled and unchanged; a full backend `verify` may therefore fail.
The bundle-only builds needed to recheck the baseline do not certify the full suite. No native
formula or numerical repair was moved into the adapters. Do not interpret a UI-valid script or a
passing short chain as support for the cases above. In particular, older claims in this history
based on locally patched packages no longer certify these cases. The four examples are a bounded
functional baseline, not a complete-paper or unrestricted-release claim.

For Cons–Exp–Cons, all four `InputMapping` declarations now accept only `CONSTANT`. The generated
Tile enforces this and publishes `canBeStochastic=false` in its engine specification. The existing
UI uses that metadata to omit estimate controls and reject imported sampled inputs; it does not
silently convert a sampled parameter to a fixed value. Named constants remain supported, and
Gompertz's four estimated parameters remain supported. This is an engine binding restriction, not
a change to the component JSON's public types or priors. Other expressions classified as
non-constant are conservatively refused, even if the caller expects their value to remain fixed.

`ValidatedConsExpCons` lives in the PhyloSpec adapter, not the PopFunc native module. It checks
finite ages and `0 <= growthStartAge <= growthEndAge` before calling the unchanged native
initializer, which otherwise retries an invalid age indefinitely. Core component constraints are
currently warnings, so they alone cannot prevent that loop. The guard overrides no population,
intensity, inverse-intensity or MCMC calculation. Valid fixed calculations were compared with a
direct native object, and a fixed-population Coalescent short chain passes. This does **not** certify
the native inverse-intensity implementation, extreme numerical regimes, or demographic-parameter
estimation. The previous estimated-Cons–Exp–Cons workflow test has been replaced by a fixed-input
workflow and explicit rejection tests; the three numerical failure assertions are retained.

Recheck this boundary with an already-built PopFunc bundle:

```sh
mvn -o -Dtest=PopFuncPackageTest \
  -Dphylospec.popfuncAdapter=../phylospec/integrations/beast3/popfunc/target/phylospec-adapter test
```

Before rebuilding PopFunc adapters on another machine, install the intended unmodified native
artifact with `(cd ../PopFunc && mvn -o -pl popfunc-beast -am clean install)`; the PhyloSpec reactor
does not rebuild that sibling automatically. Keep failing backend checks visible while discussing
them with the native authors. Separately map the intended paper configurations to the accepted
paths and implement only missing PhyloSpec bindings—not native scientific-model repairs.

**Read-count update (14 October 2026):** 140 new cases pass for GT10 and GT16: 112 check
separate parameter/genotype proposals in both orders, with acceptance and rejection, and 28
check the native Gibbs operator after accepting or rejecting each of the seven read-count
parameter updates. These compare cached densities and Gibbs input probabilities against fresh
native objects; they do not prove the native formulas or statistical convergence. The current
adapter registers no operator that jointly edits the latent alignment and a read-count parameter.
The 24 joint-update failures remain enabled; they are not evidence that all separate parameter
estimation must be disabled. Custom joint operators remain unverified/unsafe for these cases.

The adapter now also rejects tree/read-count taxon mismatches before constructing the latent
graph. Different tip order is allowed without reordering counts or size factors. Graph/workflow
checks pass (20/20), standalone packaged checks pass (12/12), and the full UI suite passes again
(197/197, including the four-package gate) with the rebuilt bundle. No native model or operator
implementation was modified.

### Relax dated reference through the UI

The clock adapter's existing `DatedTreeWorkflowTest` now retains its verified input in
`phylospec-adapter/target/dated-reference/`: `analysis.phylospec` and `dated.nex`. The latter contains
all 31 taxa and 600 sites from `mixture-typed.xml`, with the original backward ages appended to
the taxon labels. Sequences and age values are unchanged. This uses the existing loader convention,
not a new arbitrary date-table importer. The test still samples the tree; it does not fix the tree
to the XML's initial Newick or claim an identical operator schedule.

`DatedClockTest` imports that same file through the UI's package metadata, checks the population,
gamma shape, mean-rate and UC/AC variance priors, shared mean rate, fixed mixture weights,
normalization and indicator bounds, then exports/reopens it. A separate test recreates the dates
using the existing JavaFX controls: **Use tip dates**, **Age (time before present)**, **Split on
delimiter**, delimiter `_`, part `2`. Its canonical script must be identical to the imported model.
Both valid paths execute the full alignment in a fresh JVM using only the adapter bundle, with
`beast.debug` enabled. They check all 31 scheduled samples, finite traces, posterior = prior +
likelihood, at least one accepted change, eight state nodes, final tree output, required operators
and the strict/UC/AC diagnostic probabilities summing to one. An unmatched date regex is rejected
before a final state is produced; it is not treated as contemporaneous data.

The focused backend check passes **5 reference/dated tests + 6 packaged integration tests**.
The UI check passes **3/3 with zero skips**. Initial test assertions incorrectly required the
omitted default-zero invariant proportion to be explicit and formatted as `0.0`; the assertions
were corrected to preserve omission and compare its numeric value. No production Java, component
JSON, Tile or native model was changed for this milestone.

The full UI regression was then executed with JavaFX, all four adapter bundles, the four-package
delivery gate and the L86, cats and dated-clock fixtures enabled: **217/217 pass, zero skips**.
This does not rerun or certify the broader native-boundary failures recorded above.

Using JDK 25, prepare the verified input and bundle from `RelaxClockAveraging`:

```sh
mvn -o -pl phylospec-adapter -am -Djava.only=true \
  -Dtest=ReferenceDataTest,DatedTreeWorkflowTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=PackagedWorkflowIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Then, from `phylospec-ui`:

```sh
CLOCK_ROOT="$(cd ../RelaxClockAveraging && pwd)"
CLOCK_ADAPTER="$CLOCK_ROOT/phylospec-adapter/target/phylospec-adapter-adapter-0.1.0"
DATED_ANALYSIS="$CLOCK_ROOT/phylospec-adapter/target/dated-reference/analysis.phylospec"
mvn -o -DreuseForks=false -Dtest=DatedClockTest -Dphylospec.fxTests=true \
  -Dphylospec.clockAdapter="$CLOCK_ADAPTER" -Dphylospec.datedAnalysis="$DATED_ANALYSIS" test
bin/phylospec-ui --adapter-path "$CLOCK_ADAPTER"
```

Open `DATED_ANALYSIS` via File → Open and inspect **Tip Dates & Species**, **Clock Model** and
**Priors**. Tests retain exported scripts and logs in fresh `target/dated-clock/accepted-*` and
`rejected-*` directories. This closes the dated-reference UI verification item, not every Relax
variant or paper-result reproduction. The short chains do not establish convergence.

### PopFunc ancestral selection through the UI

The PhyloSpec repository now contains
`integrations/beast3/popfunc/examples/ancestral-selection.phylospec` and its synthetic alignment.
It translates the estimated `I_na` in PopFunc's local `examples/jcCoalGompertz_f0.lphy` using
existing components: two AGT-generated Gompertz functions share `N0`, `b` and `f0`; only the
second has `NA`. `modelIndicator(models=[withoutAncestor,withAncestor])` supplies the same
UniformDiscrete(0,1) prior and a bounded proposal, and `stochasticPopulationSelection` chooses
the active function. `NA` retains its LogNormal prior even when inactive. No parameters or priors
are duplicated, and no native formula is reimplemented. The original four continuous priors and
strict clock rate 0.01 are preserved; the synthetic four-taxon inference dataset is not the
original 40-taxon simulation. This is not a full paper reproduction or an exact operator-schedule
comparison. The cross-package audit exposed an editor gap; the UI now preserves this composition
using generic component-vector entries and parameter references, not a PopFunc-specific panel.

The reference comparison exposed a shared binding issue: the BEAST3 `CoalescentTile` must wire
`Tree -> TreeIntervals -> Coalescent` explicitly. The intervals then participate in BEAST's
dirty/store/restore graph instead of remaining a hidden cache. The tile now uses that native input,
and PopFunc's operator setup obtains the same tree through it (while retaining compatibility with
direct-tree bindings). No native Coalescent implementation was changed. Rebuild adapter bundles
after installing the corrected PhyloSpec BEAST integration; old bundles retain the old binding.

From the PhyloSpec repository, using JDK 25:

```sh
mvn -o -pl integrations/beast3/java -am -Djava.only=true install
mvn -o -Ppopfunc -pl integrations/beast3/popfunc -am -Djava.only=true \
  -Dtest=CoalescentTileTest,AncestralSelectionTest,PopFuncOperatorsTest,PopFuncWorkflowTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=PackagedAdapterIT -Dfailsafe.failIfNoSpecifiedTests=false verify
POP_ADAPTER="$PWD/integrations/beast3/popfunc/target/phylospec-adapter"
POP_SCRIPT="$PWD/integrations/beast3/popfunc/examples/ancestral-selection.phylospec"
POP_RUN=$(mktemp -d /private/tmp/popfunc-ancestral.XXXXXX)
java -Djava.only=true -cp "$POP_ADAPTER/*" runner.PhyloSpecCli \
  --library popfunc,beast2 --run-name "$POP_RUN/analysis" "$POP_SCRIPT"
```

The tests compare both indicator states and accepted/rejected proposals with a fresh native
Gompertz object using `I_na` directly, retain the inactive parameter's prior, and execute the
example in a separate JVM with packaged JARs only. The short chain is a functional check, not
evidence of convergence, correct model frequencies, or correctness at all native numerical boundaries.

After rebuilding the bundle above (including the new indicator UI hint), launch from the UI repository:

```bash
cd ../phylospec-ui
POP_ADAPTER="$(cd ../phylospec && pwd)/integrations/beast3/popfunc/target/phylospec-adapter"
POP_SCRIPT="$(cd ../phylospec && pwd)/integrations/beast3/popfunc/examples/ancestral-selection.phylospec"
mvn -o package
bin/phylospec-ui --adapter-path "$POP_ADAPTER"
```

Open `POP_SCRIPT` via **File → Open**. In **Tree Prior**, the Coalescent's population is
`stochasticPopulationSelection`, with **average over** enabled for its indicator. Its `models`
input owns two editable Gompertz entries. The first omits `ancestralPopulationSize`; the second
estimates it. In the second entry, **Reference…** points initial proportion, growth rate and initial
population size to the first entry's estimated parameters. Untick **estimate** before selecting
a reference when configuring a new entry. Ticking it again explicitly creates an independent
parameter and clears the reference. **Use literal value** explicitly removes a reference.

In **Priors**, the indicator uses `modelIndicator`; its `models` input references the same vector
owned by the selection function. It must not own a second copy of the population models.
The four continuous priors appear once each; the `NA` prior is retained in both indicator states.
For a new selection, **Edit entries**, **+** and **−** build its candidate list using the existing
component chooser. Choose priors explicitly; the editor does not infer model averaging from
the presence of multiple entries. Save/reopen keeps the references, including after variable
renaming or copying a tree-prior component.

```bash
mvn -o -DreuseForks=false -Dtest=PopulationSelectionTest,PopulationFormTest \
  -Dphylospec.fxTests=true -Dphylospec.popfuncAdapter="$POP_ADAPTER" \
  -Dphylospec.ancestralAnalysis="$POP_SCRIPT" test
```

All ten focused cases pass. JavaFX adds/removes a candidate and reconnects the shared scalar/vector
inputs; the canonical script stays unchanged. Both exported-model runs use packaged JARs and
`beast.debug=true`, checking all 31 samples (0–30), finite trace values, posterior = prior +
likelihood, a binary indicator, six state nodes (four demographic parameters, indicator, tree),
and final tree output. Evidence is in `target/population-selection/accepted-*`.

This editor accepts a named component array with distinct, named function-valued entries and an
editable owner in the model tabs. Empty arrays, duplicate component entries, inline component
array entries, incompatible/dangling references and dependency cycles are refused rather than
silently changing object identity or priors. It is not a general arbitrary graph editor. Numeric
vector editing retains its previous ownership rules. No new native formula, prior or operator is
introduced by this UI work; the previously recorded broader backend failures were not rerun or repaired.

The final complete UI run passes **227/227, with zero failures, errors or skips**, including all
four package delivery cases, the L86/cats/dated references and the ten new PopFunc checks. The first
full run exposed one Phylonco multi-partition regression: rebinding the same shared likelihood
parameter was mistaken for a new input referencing itself. The reader now distinguishes an
existing owner from a different referencing input; the original regression test and final full run
both pass without changing the backend or weakening the assertion.

The following command records the earlier 227-test milestone. It predates the required
PopFunc-paper L86 and primates fixtures and is not a current full-acceptance command. For current
complete UI acceptance, prepare every required fixture and use `mvn -o -Pacceptance verify`
as described above:

```bash
PHY_ROOT="$(cd .. && pwd)"
mvn -o -DreuseForks=false -Dphylospec.fxTests=true -Dphylospec.deliveryTests=true \
  -Dphylospec.popfuncAdapter="$PHY_ROOT/phylospec/integrations/beast3/popfunc/target/phylospec-adapter" \
  -Dphylospec.phyloncoAdapter="$PHY_ROOT/beast-phylonco/phylonco-phylospec/target/phylonco-phylospec-adapter-1.2.1" \
  -Dphylospec.clockAdapter="$PHY_ROOT/RelaxClockAveraging/phylospec-adapter/target/phylospec-adapter-adapter-0.1.0" \
  -Dphylospec.calibratedAdapter="$PHY_ROOT/CalibratedCPP/phylospec-adapter/target/calibratedcpp-phylospec-adapter-0.0.1-SNAPSHOT" \
  -Dphylospec.clockJars="$PHY_ROOT/RelaxClockAveraging/phylospec-adapter/target/phylospec-adapter-0.1.0.jar:$PHY_ROOT/RelaxClockAveraging/mixture-beast/target/mixture-beast-0.1.0.jar" \
  -Dphylospec.l86Analysis="$PHY_ROOT/beast-phylonco/phylonco-phylospec/target/l86-reference/analysis.phylospec" \
  -Dphylospec.catsAnalysis="$PHY_ROOT/CalibratedCPP/phylospec-adapter/target/cats-reference/analysis.phylospec" \
  -Dphylospec.datedAnalysis="$PHY_ROOT/RelaxClockAveraging/phylospec-adapter/target/dated-reference/analysis.phylospec" \
  -Dphylospec.ancestralAnalysis="$PHY_ROOT/phylospec/integrations/beast3/popfunc/examples/ancestral-selection.phylospec" package
```

After the interval-binding fix, the full core/processor/BEAST3 suites pass (322 with two skipped,
77 and 320 cases respectively). Targeted Phylonco checks pass (23 plus 12 packaged), the clock
adapter checks pass (39 plus six packaged), and CPP reference/binding checks pass (59 plus seven
packaged). The complete UI suite passes again (197/197) against rebuilt bundles. Those package
selections do not replace the broader failing suites recorded above. Existing analyses using the
old Coalescent binding should be rerun; a previously finite trace did not establish correct tree
interval updates.

The local Phylonco `examples/L86/L86_rskycoal_em_beagle.xml` now has a backend reference path:
`beast-phylonco/phylonco-phylospec/examples/l86-skyline.phylospec`. The separate core
`BayesianSkyline` generator uses event groups, not the fixed `changeTimes` required by the
unchanged `SkylineCoalescent`. Its current BEAST3 binding takes a sum-constrained group vector
from `UniformComposition(total=85,num=4)`; arbitrary integer vectors are not advertised as
interchangeable with such a state. Both the total and number of groups must currently be constants.
Four scalar populations retain the original first-value LogNormal prior and subsequent conditional
`Exponential(mean=previousPopulation)` factors. They are joined by the existing live-vector binding,
not copied into a second sampled vector. No `ExponentialMarkovChain` implementation is needed for
this fixed four-group configuration.

GT16 additionally accepts `relativeRates: Simplex` through an AGT-generated overload, preserving
the original Dirichlet rate prior. The old `rates: Vector<PositiveReal>` interface is unchanged;
public `Simplex`/`Probability` types have not been broadened. Native package model source is unchanged.

`L86ReferenceIT` extracts all 86 taxa and 598 sites from that XML. Ten accepted/rejected update
checks compare the prior factors, branch rates and native Java GT16 error likelihood, and a separate
JVM runs the packaged 30-step analysis. This checks the probability configuration, not the XML's
BEAGLE implementation, original operator schedule, posterior convergence, or every L86 variant.
The normalized uniform group prior adds the fixed log constant `-log(choose(84,3))` relative to the
XML's implicit uniform factor; MCMC ratios are unchanged for this fixed group count. The test writes
the extracted NEXUS, runnable script, launcher and results into the adapter's `target/l86-reference`.

After installing the updated PhyloSpec core/processor/BEAST3 reactor, run from `beast-phylonco`:

```bash
mvn -o -pl phylonco-phylospec -am -Djava.only=true \
  -Dtest=GT16GeneratedTileTest,GT16ErrorModelEquivalenceTest,PhyloncoWorkflowTest,PhyloncoCoverageTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=L86ReferenceIT,PackagedAdapterIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

The broader adapter unit suite still has the same 24 native read-count joint-update failures
(307 cases: 283 pass, 24 fail); they are not suppressed or fixed by this work. The other 83 unit
cases and all 23 packaged/reference integration cases pass. Rebuild package bundles together before
combining them so that they contain the same updated core JARs.

With this addition, the core/processor/BEAST3 suites pass again (322 with two skipped, 77 and
337 cases). Rebuilt PopFunc, RelaxClockAveraging and CalibratedCPP bundles pass their selected
regressions (23 + 4, 39 + 6 and 59 + 7 unit/integration cases respectively). All four bundles
contain the same updated core JAR, and the complete UI suite passes (197/197, including JavaFX
controls and the existing four-package delivery checks). At that backend milestone, the only UI
test adjustment was adding the newly discovered `BayesianSkyline` to the tree-prior catalogue.

#### Configuring the L86 conditional skyline in the UI

The generic parameter editor now supports references from a scalar prior hyperparameter to an
existing estimated scalar. For example, the mean of the second population's `Exponential` can
reference the first population. This is one shared random variable, not another draw with a
copied value or prior. The writer declares dependencies first and keeps references valid when
it assigns unique variable names. Unlinking a component copies its internal references along
with its parameters. External references intentionally remain shared.

The reader accepts a named numeric array such as `populations = [theta0,theta1,theta2,theta3]`
used by one editable vector input. It does not silently duplicate a sampled vector entry used
in multiple places. References to removed/fixed parameters, incompatible types, cyclic
dependencies or inputs restricted to constants are refused. A new hyperprior that has no
estimated owner in the model tabs remains outside this UI's scope; arbitrary expression graphs
and shared numeric-vector aliases are not implemented by the skyline change. Component-vector
references used by PopFunc are supported as described above.

Prepare the adapter's `target/l86-reference` using the command above, then launch the UI from
this repository with JDK 25:

```bash
PHY_ADAPTER="$(cd ../beast-phylonco && pwd)/phylonco-phylospec/target/phylonco-phylospec-adapter-1.2.1"
L86_ANALYSIS="$(cd ../beast-phylonco && pwd)/phylonco-phylospec/target/l86-reference/analysis.phylospec"
bin/phylospec-ui --adapter-path "$PHY_ADAPTER"
```

Open `L86_ANALYSIS` with **File → Open**. The adapter example now has explicit `data` and `model`
blocks, making the same file usable by both the runner and UI. Relative `l86.nex` is resolved
against the file's directory. In **Tree Prior**, `BayesianSkyline` has four separately estimated
population entries and an estimated `groupSizes` vector. In **Priors**, the first population is
`LogNormal(logMean=-2.3, logSd=1.8)`; the other three use the `Exponential(mean)` overload.
Use the **Reference…** menu beside each mean to select the preceding population variable.
**Use literal value** removes a reference explicitly. The group prior is
`UniformComposition(total=85,num=4)`: 86 taxa require 85 coalescent events. Editing the group count
does not silently resize populations or change any priors.

To configure this tree prior from scratch, select `BayesianSkyline`, press **Edit entries** on
`populationSizes`, tick **estimate** on each of the four entries, and tick **estimate vector**
on `groupSizes`. Then set the priors and references above. No special L86 panel is required.

Run the focused checks from this repository:

```bash
mvn -o -DreuseForks=false \
  -Dtest=ParameterReferenceTest,SkylineFormTest \
  -Dphylospec.fxTests=true \
  -Dphylospec.phyloncoAdapter="$PHY_ADAPTER" \
  -Dphylospec.l86Analysis="$L86_ANALYSIS" test
```

`ParameterReferenceTest` passes all 12 cases. `SkylineFormTest` imports that real-data analysis,
reconfigures the tree prior through JavaFX controls, checks that the complete canonical script
is unchanged apart from the output path, reopens the export, and runs it with the packaged CLI.
The 30-step run produces finite logged values, trees and a state file; every sampled group vector
has four positive integer entries summing to 85. Evidence is kept in `target/l86-ui/form-*`.
These are functional checks, not convergence or BEAGLE-equivalence claims.

The complete UI suite passes **210/210**, with JavaFX, all four package bundles, the four-package
delivery gate and `phylospec.l86Analysis` enabled. The adapter was also reverified after the
example format change: six selected unit tests and all eleven `L86ReferenceIT` cases pass.
The previously documented native read-count failures were not rerun or changed in this UI step.

### CalibratedCPP cats reference through the UI

`CatsWorkflowTest` extends the real-data backend reference to the existing UI, without a new
cats-specific panel or changes to native model code. Its input is the analysis prepared by
CalibratedCPP's `CatsReferenceIT`, using all 19 taxa and 30141 sites from
`cats-half_uniform_mrca_not_conditioned_b3.xml`. This is distinct from the four-taxon delivery
example. The backend reference has been rerun after native changes were withdrawn: 61 selected
unit tests and eight integration tests pass, including the real-data native-XML comparison.
At the compared state, both implementations give posterior `-72424.68688381907` and likelihood
`-72236.19153398229`; the existing live-parameter comparisons pass as well.

The UI workflow explicitly checks five independently estimated GTR rates (GT fixed to one),
Dirichlet frequencies, gamma site rates, the two demographic priors, fixed `rho=0.01`, the initial
tree and all four Uniform calibration ranges. `calibrationMode="independentUniform"` and
`conditionOnCalibrations=false` are preserved. The clock remains
`IID(base=LogNormal(mean=1.0,logSd=sigma),num=36)` feeding RelaxClockAveraging's `sharedRatesClock`
with `normalize=false`; it is not replaced by core's category-based `RelaxedClock`.

Four tests cover file read/edit/save/reopen with a 30-step packaged run, actual JavaFX calibration
and clock controls with a 10-step run, and rejection of a 35-dimensional clock or an unknown
calibration taxon. The valid runs enable `beast.debug`, check finite trace columns, posterior =
prior + likelihood, at least one accepted change, 13 state nodes and final tree output. Fresh
`target/cats-ui/accepted-*` and `rejected-*` directories retain each script and console log.
The tests use the existing raw clade-expression editor; this is not a new graphical clade picker.
The focused four tests pass, and the complete UI suite passes **214/214 with zero skips** when
JavaFX, the four-package delivery gate, both real-data fixtures and all package paths are enabled.
Both the native-XML test and UI test use matching Relax adapter contents: the installed and target
JAR archives differ in their file checksum but have identical entry lists and uncompressed contents.

With JDK 25 and compatible locally installed PhyloSpec and Relax artifacts, prepare the fixture:

```bash
cd ../CalibratedCPP
mvn -o -Pphylospec,cats-reference -pl phylospec-adapter -am -Djava.only=true \
  -Dtest=CalibratedAdapterTest,CatsLikelihoodTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=CatsReferenceIT,PackagedAdapterIT -Dfailsafe.failIfNoSpecifiedTests=false verify
cd ../phylospec-ui
```

Then run the UI checks (the opt-in properties are all required):

```bash
PHY_ROOT="$(cd .. && pwd)"
CPP_ADAPTER="$PHY_ROOT/CalibratedCPP/phylospec-adapter/target/calibratedcpp-phylospec-adapter-0.0.1-SNAPSHOT"
CLOCK_JARS="$PHY_ROOT/RelaxClockAveraging/phylospec-adapter/target/phylospec-adapter-0.1.0.jar:$PHY_ROOT/RelaxClockAveraging/mixture-beast/target/mixture-beast-0.1.0.jar"
CATS_ANALYSIS="$PHY_ROOT/CalibratedCPP/phylospec-adapter/target/cats-reference/analysis.phylospec"
mvn -o -DreuseForks=false -Dtest=CatsWorkflowTest -Dphylospec.fxTests=true \
  -Dphylospec.calibratedAdapter="$CPP_ADAPTER" -Dphylospec.clockJars="$CLOCK_JARS" \
  -Dphylospec.catsAnalysis="$CATS_ANALYSIS" test
bin/phylospec-ui --adapter-path "$CPP_ADAPTER:$CLOCK_JARS"
```

Open `CATS_ANALYSIS` via File → Open. The Tree Prior tab exposes the calibrations, fixed rho,
conditioning mode and initial tree. The Clock Model and Priors tabs retain the continuous
36-branch prior and its estimated standard deviation. Full paths to the data are written on
export, so saving into a different directory does not change which alignment is analysed.

These checks establish a specific empirical configuration and integration path, not posterior
convergence, equality of MCMC trajectories/operators, or every cats XML variant. The known
Erlang/invalid-runtime-rate boundary failures remain outside this selected run and were not
changed. The four-package baseline remains finite. The audit at the top of this document records
the now-verified PopFunc UI composition and Relax dated-data UI workflow;
neither requires adding further L86 or cats variants.

### CalibratedCPP primates uniform-rep1 workflow

The selected 29-taxon, three-partition reference now follows the backend reconstruction in
CalibratedCPP's `PrimatesReferenceIT` through the UI. The backend fixture verifies all 399462 sites,
nine calibrations, matched-state native densities and live updates. This UI step does not change
native model code or introduce another calibration/clock implementation.

Two ordinary UI capabilities were needed. `SiteModel` now owns the optional `PhyloCTMC`
mutation-rate multiplier for its partition group, obtaining its type/default from component
metadata. `Analysis.sharedVectors` owns sampled vectors used through scalar element references.
`Param` stores the referenced parameter and a one-based index; `ScriptReader` reconstructs these
relationships and `ScriptWriter` declares each vector and its prior exactly once. In particular,
three `r[i]` inputs remain one WeightedDirichlet draw, not three independent priors or a simplex.
Known IID/WeightedDirichlet lengths and input types are checked on import and export. Missing
targets, out-of-range indices and unused shared vectors produce explicit errors. Unknown dynamic
lengths still require backend validation. This UI supports fixed single indices into sampled
numeric vectors, not arbitrary computed/stochastic indexing.

The existing Site Model tab shows every group, with its mutation-rate input and `Reference…`
choice/index field. Priors can add a shared positive vector and edit its ordinary distribution.
Use IID(LogNormal, num=3) for the reference's kappa vector and WeightedDirichlet for its partition
rates; these are different scientific priors, not interchangeable UI presets. Choose their
parameters explicitly. Removing a referenced vector leaves a reported invalid reference until
its inputs are reconfigured; it does not silently replace sampled values with constants.
Numeric vectors/simplexes can also be shared whole, preserving shared frequency priors when only
the partition multipliers differ. Calibration and clock controls are reused without a primates panel.
Role inference now requires a `tree` input for a clock or a `numSites` input for a site-rate model;
type compatibility alone must not put an arbitrary positive-vector prior into the Site Model chooser.
An explicitly declared package role still takes precedence. WeightedDirichlet remains selectable
as a vector prior, without modifying its public type or the engine's support rules.

`PrimatesWorkflowTest` checks import/export/reopen, all three actual JavaFX tab groups and editable
indices, and a packaged 10-step run using the real data and both adapters. The run checks finite
trace values, posterior = prior + likelihood, 12 state nodes, positive rates summing to 3,
and tree/state files. `SharedVectorTest` covers bounds, changed dimensions, removed targets,
shared frequency priors and unlinking without duplicating a vector. The beta4 engine fixture is
still historical: its tests explicitly exclude new arguments when testing its old shape, and
verify that it does not claim support for partition mutation rates.

Actual verification on 14 October 2026: `mvn -o -Pacceptance package` completed with
**248 tests, zero failures/errors/skips**, including four-package delivery, the real-data workflows
and JavaFX controls. The initial full run exposed two stale historical-signature assertions and
the incorrect WeightedDirichlet site-role inference; these were fixed and the complete command
rerun successfully. This is the UI suite plus its packaged runs, not a rerun of every native or
CalibratedCPP adapter boundary test.

After preparing the backend fixture, run from the `phylospec-ui` checkout with JDK 25 and
compatible sibling adapter builds. Set `PHY_ROOT` explicitly if the checkouts are elsewhere:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
PHY_ROOT="$(cd .. && pwd)"
mvn -o -Pacceptance -Dphylospec.workspace="$PHY_ROOT" \
  -Dtest=PrimatesWorkflowTest,SharedVectorTest test
# Current complete UI acceptance, including the four-package gate and graphical controls:
mvn -o -Pacceptance -Dphylospec.workspace="$PHY_ROOT" verify

CPP_ADAPTER="$PHY_ROOT/CalibratedCPP/phylospec-adapter/target/calibratedcpp-phylospec-adapter-0.0.1-SNAPSHOT"
CLOCK_JARS="$PHY_ROOT/RelaxClockAveraging/phylospec-adapter/target/phylospec-adapter-0.1.0.jar:$PHY_ROOT/RelaxClockAveraging/mixture-beast/target/mixture-beast-0.1.0.jar"
bin/phylospec-ui --adapter-path "$CPP_ADAPTER:$CLOCK_JARS"
```

Use File → Open to load
`../CalibratedCPP/phylospec-adapter/target/primates-reference/analysis.phylospec`.
The Site Model tab should show codon_0, codon_1 and codon_2; their kappa and mutation-rate fields
reference elements 1, 2 and 3 of the shared vectors. Priors retains the reference's IID kappa,
WeightedDirichlet rates, three separate frequency simplexes, shared gamma and clock hyperparameters.
Tree Prior retains rho=0.05, independent-uniform calibrations and conditioning on calibrations.
Save a copy; relative alignment files from the source are exported as absolute paths. The test
retains its export, trace, trees, state and console under `target/primates-ui/run-*`.

This is one agreed empirical integration path. The short chain uses PhyloSpec's initialization
and operators, not the XML's original parameter initialization or ORC schedule. It proves neither
convergence nor reproduced published estimates. Other primates calibration variants, arbitrary
codon-subset expressions and the previously recorded native boundary failures remain outside
this acceptance; no native boundary fix is claimed.

### Phylonco GT16 observation model

The Likelihood tab supports the explicit composition `PhyloCTMC → GT16ErrorModel → observed
genotypes`. The alignment passed to the error model is a **latent draw**, not the loaded data.
The ordinary Site Model, Clock Model and Tree Prior tabs configure that latent process. Epsilon
and delta use the existing fixed/estimate controls and Priors tab. This is a UI composition of
existing backend generators; no native Phylonco formula or operator was changed.

Use JDK 25 and an already-built compatible adapter bundle:

```sh
PHY_ADAPTER="$(cd ../beast-phylonco && pwd)/phylonco-phylospec/target/phylonco-phylospec-adapter-1.2.1"
mvn -o package
bin/phylospec-ui --adapter-path "$PHY_ADAPTER"
```

Open `examples/phylonco/gt16-error.phylospec` using **File → Open**. It uses a small synthetic
four-taxon genotype dataset, GT16, a strict clock, Yule, Dirichlet frequencies and independently
estimated Beta error/dropout probabilities. It is a reproducible functional example, **not a
reconstruction of an entire paper or a convergence analysis**. Data paths resolve relative to
the script. Alternatively, load `genotypes.nex`, select `gt16` in Site Model and `GT16ErrorModel`
in Likelihood, then configure the other tabs. Saving and reopening preserves the two-layer model.

Run an exported model in a fresh output directory:

```sh
PHY_RUN=$(mktemp -d /private/tmp/phylonco-ui.XXXXXX)
PHY_SCRIPT="$PWD/examples/phylonco/gt16-error.phylospec"
(cd "$PHY_RUN" && java -Djava.only=true -cp "$PHY_ADAPTER/*" runner.PhyloSpecCli \
  --library phylonco,beast2 "$PHY_SCRIPT")
```

Run the configuration, script round-trip, invalid-composition, packaged-runner and actual JavaFX
control tests (the latter require a display):

```sh
mvn -o -Dtest=GenotypeErrorTest,GenotypeFormTest -DreuseForks=false \
  -Dphylospec.fxTests=true -Dphylospec.phyloncoAdapter="$PHY_ADAPTER" test
```

Use `-DreuseForks=false` when enabling multiple JavaFX test classes: JavaFX cannot restart after
`Platform.exit()` in the same JVM. The short runs check finite logs and trace/tree/state outputs,
not statistical equivalence. Existing Phylonco backend graph and density-comparison tests provide
the separate checks that the latent likelihood is not counted twice and error parameters reach
the native likelihood.

This preset has one latent PhyloCTMC draw per partition, with shared error probabilities across
partitions. Import rejects shared latent draws, partition-specific error probabilities, one sampled
parameter used as both epsilon and delta, inline drawn arguments, and additional latent inputs
that the preset cannot preserve (including `useAmbiguities`). It does not infer arbitrary observation
chains. Read-count observations use the separate, explicit composition below.

### Phylonco read-count observations

The UI also supports `PhyloCTMC → ReadCountModel → observed read counts` with GT10 or GT16.
For mixed fixed/estimated size factors, first build the current PhyloSpec BEAST integration, which
includes live real-array binding; old adapter bundles cannot execute these arrays.
Rebuild the sibling adapter before launching the UI: the bundle must include `readCountTaxa` and
`readCountNumSites`, which supply tree taxa and site-rate dimensions directly from the count data.
These functions add no likelihood, prior or state; the existing backend Tile owns the latent
genotype alignment, its phylogenetic likelihood and the native Gibbs operator.

```sh
cd ../phylospec
mvn -o -pl integrations/beast3/java -am install
cd ../beast-phylonco
mvn -o -pl phylonco-phylospec -am -Djava.only=true \
  '-Dtest=ReadCountAccessorsTest,ReadCountModelGraphTest,FromReadCountNexusTileTest,PhyloncoWorkflowTest,ReadCountModelEquivalenceTest#separateParameterAndGenotypeProposalsPreserveDensities+nativeGibbsUsesCurrentParametersAfterAcceptanceOrRejection' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=PackagedAdapterIT -Dfailsafe.failIfNoSpecifiedTests=false verify
cd ../phylospec-ui
PHY_ADAPTER="$(cd ../beast-phylonco && pwd)/phylonco-phylospec/target/phylonco-phylospec-adapter-1.2.1"
mvn -o package
bin/phylospec-ui --adapter-path "$PHY_ADAPTER"
```

This build deliberately selects the supported-path regression checks, not the entire adapter
suite. To rerun that broader selection, historically recorded as 306 cases with 24 failures on
the native snapshot above, run the following from `beast-phylonco`. Later additions can change
the total case count; the joint-update assertions remain enabled and unchanged:

```sh
mvn -o -pl phylonco-phylospec -am -Djava.only=true \
  '-Dtest=tiles.phylonco.*Test,tiling.phylonco.*Test,phylonco.phylospec.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Open `examples/phylonco/read-count.phylospec`. Alternatively, import `read-counts.nex`, select
`gt10` or `gt16` on Site Model, and `ReadCountModel` on Likelihood. The input must declare
`datatype=readCount`, with one taxon per matrix row and comma-separated A:C:G:T count quadruples.
The `.nex`, `.nexus` and `.nxs` extensions are accepted. As in the backend loader, read-count
dimensions come from the matrix rows, not potentially stale `ntax`/`nchar` declarations.
Epsilon, delta, t, v, w1 and w2 use the existing fixed/estimate controls and Priors tab. Size factors
`s` start as one value of 1 per taxon, in file row order; user-entered values are not normalized.
On first selecting **estimate vector**, the new prior's `num` is initialized from the existing
numeric vector (four here). Existing or imported prior settings are never silently resized;
check Priors if you subsequently change the data or vector length. **Edit entries** also allows
separate fixed/estimated size factors. The backend keeps these as live scalar references, without
adding a second state vector, prior or operator.

This UI composition currently supports **one undated read-count partition** without species-name
parsing. Multiple partitions, auxiliary taxon sources and shared latent alignments are not inferred.
Unsupported exports show an error rather than saving a stale preview. Opening a read-count script
requires its data file to be available so the UI can distinguish it from an ordinary alignment.

```sh
PHY_RUN=$(mktemp -d /private/tmp/readcount-ui.XXXXXX)
PHY_SCRIPT="$PWD/examples/phylonco/read-count.phylospec"
(cd "$PHY_RUN" && java -Djava.only=true -cp "$PHY_ADAPTER/*" runner.PhyloSpecCli \
  --library phylonco,beast2 "$PHY_SCRIPT")

mvn -o -Dtest=ReadCountTest,ReadCountFormTest -DreuseForks=false \
  -Dphylospec.fxTests=true -Dphylospec.phyloncoAdapter="$PHY_ADAPTER" test
```

This synthetic example and the 30-step tests verify data loading, configuration, round-trip export,
finite numeric logs, valid latent genotype logs and runnable MCMC. They do not establish convergence,
parameter identifiability or reproduction of a paper's analysis; example priors are illustrative.

### CalibratedCPP adapter

Build the adapter in a sibling CalibratedCPP checkout (with compatible PhyloSpec artifacts already
installed), then load its packaged directory. This adapter requires Java 25 or later, including
when loading it in the UI. No component JSON is copied into the UI:

```sh
(cd ../CalibratedCPP && mvn -Pphylospec -pl phylospec-adapter -am clean package)
CPP_ADAPTER="$(cd ../CalibratedCPP && pwd)/phylospec-adapter/target/calibratedcpp-phylospec-adapter-0.0.1-SNAPSHOT"
mvn package
bin/phylospec-ui --adapter-path "$CPP_ADAPTER"
```

Use **File → Open** to load `examples/calibratedcpp/skyline.phylospec`. Its small synthetic alignment
is resolved relative to the script. The Tree Prior tab uses the adapter's component metadata and
engine capabilities for `CalibratedBirthDeath`, `CalibratedBirthDeathSkyline` and
`CalibratedAgeDependentExtinction`, including their nested calibration forest and lifetime inputs.
This covers the adapter's extant-taxon models with root or origin conditioning, not every native
package mode. An estimated `origin` needs an `initialOrigin` above the initial root. With origin,
omit full-tree calibrations; without origin, supply a bounded root calibration.

For a numeric vector without a prescribed length, **Edit entries** gives ordinary scalar inputs
with individual **estimate** checkboxes. Their priors appear on the Priors tab. **Estimate vector**
instead assigns one distribution to the whole vector; these two modes do not create simultaneous
priors. The example uses two independent Normal diversification rates (negative values are legal)
and an `OrderedUniform(count=1, upper=1)` relative change time. Keep the number of change times one
less than the number of rates. Supply exactly two skyline parameterisations; reproductive number
and turnover cannot be that pair. The component descriptions explain these engine-specific rules.
Importing a script that reuses a sampled scalar in multiple vector entries is explicitly refused:
the current entry editors cannot express that sharing without changing the model. Such scripts
can still be run directly by the backend.

Calibration information currently uses annotated Newick, not a graphical calibration editor.
The example bounds the AB clade and the all-taxa root. `calibrationMode` chooses `joint` (soft
calibration-age density) or `independentUniform` (independent bounded MRCA-age densities).
`conditionOnCalibrations=false` changes the CPP conditioning, but does not remove the selected
calibration-age density. Bounds and taxon names remain part of the annotated Newick input.
The status bar checks syntax and types; data-dependent and engine-specific checks run in the backend.

To execute the example without the UI, use a fresh directory so the script's `outputPrefix="analysis"`
cannot overwrite previous results:

```sh
CPP_EXAMPLE="$PWD/examples/calibratedcpp/skyline.phylospec"
CPP_RUN_DIR=$(mktemp -d /private/tmp/calibratedcpp-run.XXXXXX)
(cd "$CPP_RUN_DIR" && java -cp "$CPP_ADAPTER/*" runner.PhyloSpecCli \
  --library calibratedcpp,beast2 "$CPP_EXAMPLE")
ls "$CPP_RUN_DIR"/analysis.*
```

This is a 200-step functional check, not a convergence or statistical-equivalence test. Run the
metadata, script round-trip, packaged-runner and invalid-input tests with:

```sh
mvn -Dphylospec.calibratedAdapter="$CPP_ADAPTER" test
# Also exercise actual JavaFX controls; requires a desktop/display:
mvn -Dtest=CalibratedFormTest -Dphylospec.fxTests=true \
  -Dphylospec.calibratedAdapter="$CPP_ADAPTER" test
```

#### Continuous branch rates with ClockModelAveraging

`examples/calibratedcpp/continuous.phylospec` is a four-taxon functional example combining CPP,
GTR, four-category Gamma site rates, and a continuous lognormal clock. It is not the empirical cats
dataset or a convergence analysis. Load the CPP runtime directory above plus these two compatible,
already-built ClockModelAveraging JARs; do not add a second full runtime bundle with stale core JARs:

```sh
CLOCK_ROOT="$(cd ../RelaxClockAveraging && pwd)"
CLOCK_JARS="$CLOCK_ROOT/phylospec-adapter/target/phylospec-adapter-0.1.0.jar:$CLOCK_ROOT/mixture-beast/target/mixture-beast-0.1.0.jar"
bin/phylospec-ui --adapter-path "$CPP_ADAPTER" --adapter-path "$CLOCK_JARS"
```

Use **File → Open** to open `examples/calibratedcpp/continuous.phylospec` (a bare launch argument
is an alignment, not a saved script). **Tree Prior** configures CPP and calibrations; **Site Model**
configures GTR and Gamma; **Clock Model** selects `sharedRatesClock`. In **Priors**, the rate vector
uses `IID`, whose `base` is `LogNormal(mean=1, logSd=...)`. Keep mean fixed at 1; the base's Log sd
can be estimated and gets its own prior. **Likelihood** exposes `Use ambiguities`; the example
enables it. Saving and reopening preserves these settings and the nested prior.

Set IID's `Num` explicitly: **6** for this four-taxon rooted binary tree, or **36** for the
19-taxon cats tree. The current backend rejects `numBranches(tree)` here because that expression
depends on a sampled tree. The UI does not silently infer a universal binary-tree rule or correct
an explicitly wrong count. Backend validation still checks the supplied dimension.

Run the UI round-trip, invalid-input and packaged short-chain tests, including actual JavaFX controls:

```sh
mvn -Dphylospec.calibratedAdapter="$CPP_ADAPTER" \
  -Dphylospec.clockJars="$CLOCK_JARS" -Dphylospec.fxTests=true package
```

After exporting a script through the UI, run it in a fresh output directory:

```sh
CPP_RUN_DIR=$(mktemp -d /private/tmp/calibratedcpp-clock.XXXXXX)
java -Djava.only=true -cp "$CPP_ADAPTER/*:$CLOCK_JARS" runner.PhyloSpecCli \
  --library calibratedcpp,clockmodelaveraging,beast2 \
  --run-name "$CPP_RUN_DIR/analysis" "$PWD/examples/calibratedcpp/continuous.phylospec"
```

Replace the last path with your exported script. The UI status bar validates syntax/types; it is
not a claim that calibration bounds, starting values, dimensions or a full MCMC have been checked.

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

A type *property* would not do the same job. Declaring the tip kind as `Tree<;taxonType=species>`
and requiring it on an argument is accepted whatever is passed, including a gene tree where a
species tree is wanted, because a property on an argument is advisory: the same gap as the declared
vector lengths above. A parameter is checked, and the check is what the tabs read to narrow a menu
to one candidate.

Nothing above is typed, and nothing below it is chosen twice. Setting one up, from an empty window:

| Tab | What to do |
|---|---|
| Partitions | Add the alignments |
| Tip Dates & Species | Tick "Read a species from each taxon name", and set the delimiter and part |
| Partitions | Select every row, choose **Tree**, click **Unlink** |
| Tree Prior | Choose `MultispeciesCoalescent` |

That is the whole of it. The species tree, the distribution over it, the species set and the loci
they come from are all settled by the types: there is one candidate at each step, so each is already
chosen. The Priors tab is where they can be changed, not where they have to be filled in.

The population sizes come out of it too. A multispecies coalescent has one per branch of the species
tree, not one overall, so the argument is declared
`Vector<PositiveReal; num=speciesTree.numBranches>` and the prior on it is told how long it is:

```
Vector<PositiveReal> populationSizes ~ IID(base=LogNormal(logMean=0.0, logSd=1.0), num=numBranches(speciesTree))
```

Nobody types that length. A declared length that is an expression is read as the call that computes
it, since a type property and the function reading it are the same name: `speciesTree.numBranches`
becomes `numBranches(speciesTree)`, as `vector.num` becomes `num(vector)`. Where core has no such
function the length is not used at all rather than guessed at, which is why SNAPP's `theta` stays a
literal: a `Tree` declares `numNodes` among its properties and core offers no `numNodes(tree)`.

That `IID` is core's own, and it is generic: it draws a `Vector<T>` from a `Distribution<T>`. What
`T` is comes from the value being drawn, so asking for a vector of positive reals makes `base` a
`Distribution<PositiveReal>` and the choosers offer distributions over one. An engine library can
declare a generic component the same way, by listing its variables in `typeParameters` on the
generator, as core does for `IID`, `subset` and `repeat`.

Two of core's generic components are still left out, for reasons that are theirs rather than ours.
`Mixture` takes a `Vector<Distribution<T>>`, and there is no editor for a list whose elements are
chosen. `Truncated` declares its `T` only in the generated type and in optional arguments, so a call
never pins it down and the resolver refuses it, and its `lower` and `upper` default to `-Inf` and
`+Inf`, which are not literals in the language.

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

Three spellings say this, and the one core settled on is a **constraint** on the component:
`baseFrequencies.num == 20` on `wag`. That is the spelling the resolver checks, since #77 landed, so
it is the one to build from. Reading only the other two left every amino-acid model writing four
frequencies where it wanted twenty, and being told so by the warning channel.

The other two are the schema's `dimension` field on the argument, and the type language's
`num` property — `Simplex<;num=6>` — which is already how *generated* types say it. All three are
read here, the constraint winning, then the property, then the field. Neither of the other two is
enforced, re-checked on `b8e96901`, so a tool reading only those writes a wrong-length vector that
still type-checks. `ValidatorTest` asserts that gap rather than the fix, so it fails if they are
enforced too. That half of CODEPhylo/phylospec#74 is still open; the constraint half landed in #77.

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

Data inputs are checked against the loaded engine specifications too. Adding files (chooser,
drag-and-drop or command-line arguments), opening a script, and exporting an edited analysis
check the actual loader call and supplied arguments. Ordinary `fromNexus(file=...)` remains valid
even when the library also declares an optional argument, such as `speciesName`, that the engine
does not support. Using that unsupported argument is rejected. A single engine entry must cover
the complete call; partial claims from different providers are not combined.

With the current BEAST metadata, FASTA files are refused with an explanation naming `fromFasta`
and the loaded engines. Other supported files in the same import are kept, and the dialog says so.
There is no automatic conversion or renaming to NEXUS. A provider declaring FASTA support enables
that input; without engine specifications the existing unrestricted editing mode is preserved,
but that is not a claim that a backend can execute the script. Opening a script with a loader that
disagrees with the file extension is refused, rather than silently changing its loader on save.

Run the focused non-display checks with:

```bash
mvn -o -Dtest=LoaderSupportTest,PackageMetadataTest,EngineSupportTest,PartitionTest,ScriptReaderTest,ScriptWriterTest test
```

`LoaderFormTest` additionally exercises the real JavaFX error dialog and a mixed supported/unsupported
import. It is included in `mvn -o -Pacceptance verify`, together with the existing four-package workflows.

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
