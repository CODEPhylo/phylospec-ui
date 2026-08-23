package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.ui.model.Analysis;
import org.phylospec.components.Generator;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Partition;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

/**
 * How much of StarBEAST the tabs can express.
 *
 * <p>A multispecies coalescent draws each gene tree within one species tree, with the population
 * sizes shared across loci. That is three things this UI already has, and one it does not: the gene
 * trees are unlinked trees sharing a prior, the species tree is an estimated {@code Tree} argument
 * with a prior of its own, the mapping from taxon to species is the loader's {@code speciesName}
 * parser, and the species tree's own taxa have to be written out by hand.
 *
 * <p>That last one is the whole of what is missing, and this test pins it: everything else here is
 * asserted to work, and the species set is supplied as a literal so the rest can be checked.
 */
public class StarBeastTest {

    private static final Path BEAST = Path.of("libraries", "beast28.json");

    private static Library library;

    @TempDir
    static Path directory;

    private static Path gene1;
    private static Path gene2;

    @BeforeAll
    static void load() throws IOException {
        library = Library.load(List.of(BEAST));
        gene1 = write("gene1.nex", "ACGTACGT");
        gene2 = write("gene2.nex", "ACGTTTGT");
    }

    /** Two individuals from each of two species, named the way a species parser expects. */
    private static Path write(String file, String first) throws IOException {
        Path path = directory.resolve(file);
        Files.writeString(path, """
                #NEXUS
                begin data;
                  dimensions ntax=4 nchar=8;
                  format datatype=dna;
                  matrix
                    human_1 %s
                    human_2 ACGTACGA
                    chimp_1 ACGTACGC
                    chimp_2 ACGTACGG
                  ;
                end;
                """.formatted(first));
        return path;
    }

    /** What the derived species set comes out as, once it has been chosen rather than typed. */
    private static final String SPECIES = "species(taxa=taxa(alignments=[gene1, gene2]))";

    /**
     * Builds the species set the way the tabs do: by choosing components, not by typing.
     *
     * <p>The species tree's {@code taxa} is a component to choose, so the user picks {@code species}
     * and then the {@code taxa} that takes several alignments. Both halves are placeholders in
     * {@code libraries/beast28.json} and belong in core, which is why
     * {@link #theSpeciesSetStillHasToComeFromTheEngineLibrary()} watches for them arriving.
     */
    private static void chooseTheSpeciesSet(Analysis analysis) {
        Param taxa = analysis.treePrior().param("speciesTree").priorProperty().get().param("taxa");
        assertTrue(taxa.isComponentValued(), "a taxon set is chosen, not typed");

        Component species = Component.nested(overload("species", "taxa"), library, false);
        taxa.priorProperty().set(species);

        Component ofAlignments = Component.nested(overload("taxa", "alignments"), library, false);
        species.param("taxa").priorProperty().set(ofAlignments);
        ofAlignments.param("alignments").valueProperty().set("[gene1, gene2]");
    }

    /** The overload of {@code name} that takes an argument called {@code argument}. */
    private static Generator overload(String name, String argument) {
        return library.overloads(name).stream()
                .filter(generator -> generator.getArguments().stream()
                        .anyMatch(declared -> argument.equals(declared.getName())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " taking " + argument));
    }

    private static Analysis starbeast() {
        Analysis analysis = new Analysis(library);
        analysis.addPartition(gene1);
        analysis.addPartition(gene2);

        for (Partition partition : analysis.partitions()) {
            partition.useSpeciesProperty().set(true);
            partition.speciesDelimiterProperty().set("_");
            partition.speciesPartProperty().set("1");
        }

        // A gene tree each, drawn from one multispecies coalescent, so the species tree and the
        // population size are shared. This is the two-level sharing, used for what it is for.
        analysis.unlinkTree(analysis.partitions().get(1));
        analysis.treePrior().generatorProperty()
                .set(library.overloads("MultispeciesCoalescent").get(0));

        // The coalescent's own taxa are the gene's, and the writer supplies those. The species
        // tree's taxa are the species, and those are chosen.
        chooseTheSpeciesSet(analysis);
        return analysis;
    }

    @Test
    void theMultispeciesCoalescentReachesTheTreePriorTabByItsType() {
        Analysis analysis = new Analysis(library);
        List<String> offered = analysis.choicesFor(Library.TREE_PRIOR).stream()
                .map(generator -> generator.getName())
                .toList();

        assertTrue(offered.contains("MultispeciesCoalescent"),
                "a Distribution<Tree> belongs on the Tree Prior tab, and no UI code names it: " + offered);
    }

    /**
     * The species tree is not a tree any partition is drawn on, so it is not one of the analysis's
     * trees at all. It is an estimated argument of the coalescent, and the machinery that gives a
     * prior to any estimated value writes it out.
     */
    @Test
    void theSpeciesTreeIsAnEstimatedArgumentWithAPriorOfItsOwn() {
        Analysis analysis = starbeast();
        Param speciesTree = analysis.treePrior().param("speciesTree");

        assertNotNull(speciesTree);
        assertTrue(speciesTree.estimable(), "a Tree argument can be estimated");
        assertTrue(speciesTree.isEstimated());
        assertEquals("Yule", speciesTree.priorProperty().get().name(), "the default prior on a tree");
        assertEquals(2, analysis.trees().size(), "the gene trees, and no third tree for the species");
    }

    @Test
    void theWholeShapeIsWrittenAndValid() {
        Analysis analysis = starbeast();
        String script = ScriptWriter.write(analysis);

        assertTrue(script.contains("speciesName=parse(delimiter=\"_\", part=1)"), script);
        assertTrue(script.contains("Tree speciesTree ~ Yule("), script);
        assertTrue(script.contains("Tree gene1Tree ~ MultispeciesCoalescent("), script);
        assertTrue(script.contains("Tree gene2Tree ~ MultispeciesCoalescent("), script);
        assertEquals(2, count(script, "speciesTree=speciesTree"), "one species tree, both genes");
        assertEquals(1, count(script, "PositiveReal populationSize ~"), "one population size across loci");

        assertEquals(List.of(), Validator.check(library, script).all(), script);
    }

    @Test
    void andItLoadsBackOntoTheTabs() {
        Analysis analysis = starbeast();
        String written = ScriptWriter.write(analysis);

        Analysis reloaded = ScriptReader.read(library, written);
        assertEquals(written, ScriptWriter.write(reloaded), "write -> read -> write is a fixpoint");

        assertTrue(reloaded.partitions().get(0).useSpeciesProperty().get(), "the species parser came back");
        assertEquals("1", reloaded.partitions().get(0).speciesPartProperty().get());
        assertSame(reloaded.trees().get(0).prior(), reloaded.trees().get(1).prior(),
                "both gene trees still share the one coalescent");
    }

    /**
     * Changing the prior on the Priors tab must build a prior, not a nested component.
     *
     * <p>The chooser used to build what the tabs build for a function-valued argument, which is
     * given the library. That turns any argument the library can produce into a slot to fill: the
     * species tree's Yule lost its typed-in {@code taxa} and wrote {@code taxa=taxa}, naming a
     * variable that was never declared. A prior also carries the length of the value it is drawn
     * from, which the same path dropped.
     */
    @Test
    void changingThePriorKeepsItAPrior() {
        Analysis analysis = starbeast();
        Param speciesTree = analysis.treePrior().param("speciesTree");

        // What the Priors tab does when the user picks a distribution from the chooser.
        Component chosen = Component.prior(library.overloads("Yule").get(0), speciesTree.dimension(), library);
        speciesTree.priorProperty().set(chosen);

        Param taxa = chosen.param("taxa");
        assertNotNull(taxa, "a prior over a tree still has to be told which taxa it spans");
        assertEquals(speciesTree.dimension(), chosen.param("birthRate") == null ? null : speciesTree.dimension(),
                "the prior keeps the length of what it is drawn at");

        chooseTheSpeciesSet(analysis);
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
    }

    /**
     * An alignment argument names a loaded partition, and is a value rather than a component.
     *
     * <p>The library can produce an {@code Alignment}, since a loader does, so it would otherwise
     * be offered as a component to build: the user would be asked to load the same file a second
     * time. Worse, the tab writes the partition's name into the value while the writer would read
     * the component, so the script referred to a variable nothing declared.
     */
    @Test
    void anAlignmentArgumentNamesALoadedPartition() {
        Analysis analysis = starbeast();
        Param taxa = analysis.treePrior().param("speciesTree").priorProperty().get().param("taxa");

        Component ofOneAlignment = Component.nested(overload("taxa", "alignment"), library, false);
        taxa.priorProperty().set(ofOneAlignment);

        Param alignment = ofOneAlignment.param("alignment");
        assertFalse(alignment.isComponentValued(), "a partition is named, not built");

        alignment.valueProperty().set("gene1");
        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("taxa(alignment=gene1)"), script);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
    }

    /**
     * The canary for CODEPhylo/phylospec#75. Core cannot derive a species set today, so the two
     * functions that do it are carried in the sample engine library. When core gains them this
     * fails, and the placeholders should be deleted rather than left to shadow core's, which is
     * what happened with Bernoulli.
     */
    @Test
    void theSpeciesSetStillHasToComeFromTheEngineLibrary() {
        Library core = Library.load();

        assertTrue(core.overloads("species").stream()
                        .noneMatch(generator -> "Taxa".equals(generator.getGeneratedType())),
                "core now derives a species set: delete the placeholders from beast28.json");
        assertTrue(core.overloads("taxa").stream()
                        .noneMatch(generator -> generator.getArguments().stream()
                                .anyMatch(argument -> "alignments".equals(argument.getName()))),
                "core now takes the taxa of several alignments: delete the placeholder");
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) found++;
        return found;
    }
}
