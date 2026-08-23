package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import org.phylospec.ui.model.Partition;
import org.phylospec.ui.model.TreeModel;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

/**
 * Partitions that do not share a model.
 *
 * <p>Everything here is checked the same way: the script is valid, and it survives
 * {@code write -> read -> write} unchanged. The fixpoint is what matters, because an
 * {@link Analysis} has no equality of its own and the script is what the user keeps. Reading the
 * grouping back is the hard half: the writer only has to emit more statements, while the reader has
 * to work out which partitions shared what, from a script whose variable names it cannot trust.
 */
public class UnlinkedPartitionsTest {

    private static Library library;

    @TempDir
    static Path directory;

    private static Path gene1;
    private static Path gene2;

    @BeforeAll
    static void load() throws IOException {
        library = Library.load();
        // Two loci over the same taxa. Partitions that share a tree must share its taxa, or the
        // resolver rightly doubts a script that observes ten taxa on a twelve-taxon tree.
        gene1 = write("gene1.nex", "ACGTACGT");
        gene2 = write("gene2.nex", "ACGTTTGT");
    }

    private static Path write(String file, String first) throws IOException {
        Path path = directory.resolve(file);
        Files.writeString(path, """
                #NEXUS
                begin data;
                  dimensions ntax=4 nchar=8;
                  format datatype=dna;
                  matrix
                    taxonA %s
                    taxonB ACGTACGA
                    taxonC ACGTACGC
                    taxonD ACGTACGG
                  ;
                end;
                """.formatted(first));
        return path;
    }

    private static Analysis twoPartitions() {
        Analysis analysis = new Analysis(library);
        analysis.addPartition(gene1);
        analysis.addPartition(gene2);
        return analysis;
    }

    /** Writes, checks the script is valid, reads it back and writes again, asserting a fixpoint. */
    private static Analysis roundTrip(Analysis analysis) {
        String written = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, written).all(), written);

        Analysis reloaded = ScriptReader.read(library, written);
        assertEquals(written, ScriptWriter.write(reloaded), "write -> read -> write is a fixpoint");
        return reloaded;
    }

    /**
     * Linked is what an analysis starts as, and it must keep producing exactly the script it
     * produced before groups existed: one {@code qMatrix}, one {@code tree}, one
     * {@code branchRates}, with no qualifying names.
     */
    @Test
    void linkedPartitionsShareOneOfEverything() {
        Analysis analysis = twoPartitions();
        String script = ScriptWriter.write(analysis);

        assertTrue(script.contains("QMatrix qMatrix = "), script);
        assertTrue(script.contains("Tree tree ~ "), script);
        assertTrue(script.contains("Vector<Rate> branchRates ~ "), script);
        assertEquals(1, analysis.siteModels().size());
        assertEquals(1, analysis.trees().size());

        Analysis reloaded = roundTrip(analysis);
        assertEquals(1, reloaded.siteModels().size());
        assertEquals(1, reloaded.trees().size());
    }

    @Test
    void anUnlinkedSiteModelBecomesASecondRateMatrix() {
        Analysis analysis = twoPartitions();
        analysis.unlinkSiteModel(analysis.partitions().get(1));

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("QMatrix gene1QMatrix = "), script);
        assertTrue(script.contains("QMatrix gene2QMatrix = "), script);

        Analysis reloaded = roundTrip(analysis);
        assertEquals(2, reloaded.siteModels().size());
        assertNotSame(reloaded.partitions().get(0).siteModel(), reloaded.partitions().get(1).siteModel());
    }

    /**
     * The two-level part, and the reason trees are not like site models. Separate trees drawn from
     * one shared prior give a single population size estimated across every locus, which is the
     * multi-locus coalescent and is not something BEAUti can express without editing the XML.
     */
    @Test
    void unlinkedTreesCanShareAPriorAndSoOneParameter() {
        Analysis analysis = twoPartitions();
        analysis.treePrior().generatorProperty().set(library.overloads("Coalescent").get(0));
        analysis.unlinkTree(analysis.partitions().get(1));

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("Tree gene1Tree ~ Coalescent("), script);
        assertTrue(script.contains("Tree gene2Tree ~ Coalescent("), script);
        assertEquals(1, count(script, "PositiveReal populationSize ~"),
                "one population size, drawn once and used by both trees:\n" + script);
        assertEquals(2, count(script, "populationSize=populationSize"), script);

        Analysis reloaded = roundTrip(analysis);
        assertEquals(2, reloaded.trees().size());
        assertSame(reloaded.trees().get(0).prior(), reloaded.trees().get(1).prior(),
                "the shared prior is read back as shared, not as two identical ones");
    }

    /** The other case, which is what BEAUti does: a tree of its own, with parameters of its own. */
    @Test
    void aTreePriorCanBeUnlinkedTooForIndependentParameters() {
        Analysis analysis = twoPartitions();
        analysis.treePrior().generatorProperty().set(library.overloads("Coalescent").get(0));
        TreeModel second = analysis.unlinkTree(analysis.partitions().get(1));
        analysis.unlinkTreePrior(second);

        String script = ScriptWriter.write(analysis);
        assertEquals(2, count(script, "PositiveReal populationSize"),
                "one population size per tree:\n" + script);

        Analysis reloaded = roundTrip(analysis);
        assertEquals(2, reloaded.trees().size());
        assertNotSame(reloaded.trees().get(0).prior(), reloaded.trees().get(1).prior());
    }

    /**
     * Branch rates belong to a clock and a tree together. One clock shared by partitions on
     * different trees still needs a vector per tree, since the length of the vector is the tree's.
     */
    @Test
    void oneClockAcrossTwoTreesStillDrawsAVectorPerTree() {
        Analysis analysis = twoPartitions();
        analysis.unlinkTree(analysis.partitions().get(1));

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("Vector<Rate> gene1BranchRates ~ StrictClock("), script);
        assertTrue(script.contains("Vector<Rate> gene2BranchRates ~ StrictClock("), script);
        assertEquals(1, analysis.clockModels().size(), "still one clock model");

        Analysis reloaded = roundTrip(analysis);
        assertEquals(1, reloaded.clockModels().size(),
                "two vectors drawn from one clock are read back as one clock");
    }

    @Test
    void anUnlinkedClockModelBecomesASecondClock() {
        Analysis analysis = twoPartitions();
        Partition second = analysis.partitions().get(1);
        analysis.unlinkClockModel(second);
        second.clockModel().generatorProperty().set(library.overloads("RelaxedClock").get(0));

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("StrictClock("), script);
        assertTrue(script.contains("RelaxedClock("), script);

        Analysis reloaded = roundTrip(analysis);
        assertEquals(2, reloaded.clockModels().size());
    }

    /** Unlinking and linking back again is the script it started as, not merely an equivalent one. */
    @Test
    void relinkingRestoresTheLinkedScript() {
        Analysis analysis = twoPartitions();
        String before = ScriptWriter.write(analysis);

        Partition second = analysis.partitions().get(1);
        analysis.unlinkSiteModel(second);
        analysis.unlinkTree(second);
        assertEquals(2, analysis.siteModels().size());

        analysis.linkSiteModel(second, analysis.partitions().get(0).siteModel());
        analysis.linkTree(second, analysis.partitions().get(0).tree());

        assertEquals(1, analysis.siteModels().size(), "the group left empty is dropped");
        assertEquals(1, analysis.trees().size());
        assertEquals(before, ScriptWriter.write(analysis));
    }

    /** An unlinked site model starts as a copy, so unlinking alone does not change the model. */
    @Test
    void unlinkingCopiesWhatWasAlreadyChosen() {
        Analysis analysis = twoPartitions();
        analysis.substitutionModel().generatorProperty().set(library.overloads("gtr").get(0));

        Partition second = analysis.partitions().get(1);
        analysis.unlinkSiteModel(second);

        assertEquals("gtr", second.siteModel().substitutionModel().name());
        assertNotSame(analysis.partitions().get(0).siteModel(), second.siteModel());
        roundTrip(analysis);
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) found++;
        return found;
    }
}
