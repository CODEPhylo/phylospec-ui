package org.phylospec.ui;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

class SharedVectorTest {
    private final Library library = Library.load();
    private static final String SOURCE = """
            data {
                Alignment a = fromNexus(file="examples/calibratedcpp/four-taxa.nex")
                Alignment b = fromNexus(file="examples/calibratedcpp/four-taxa.nex")
            }
            model {
                Vector<PositiveReal> kappa ~ IID(base=LogNormal(logMean=1,logSd=1.25),num=2)
                Vector<PositiveReal> r ~ WeightedDirichlet(concentration=[1,1],weights=[12,12])
                Tree tree ~ Yule(birthRate=1,taxa=taxa(a))
                Vector<Rate> clock ~ StrictClock(clockRate=1,tree=tree)
                Alignment x ~ PhyloCTMC(tree=tree,qMatrix=hky(kappa=kappa[1],baseFrequencies=[0.25,0.25,0.25,0.25]),
                    mutationRate=r[1],branchRates=clock) observed as a
                Alignment y ~ PhyloCTMC(tree=tree,qMatrix=hky(kappa=kappa[2],baseFrequencies=[0.25,0.25,0.25,0.25]),
                    mutationRate=r[2],branchRates=clock) observed as b
            }
            mcmc {
                Integer chainLength=10
                Integer defaultLogEvery=1
            }
            """;

    @Test
    void retainsOnePriorPerVectorAndDistinctPartitionIndices() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        assertEquals(2, analysis.sharedVectors().size());
        assertEquals(2, analysis.estimatedParams().size());
        assertEquals(2, analysis.siteModels().size());
        for (int i = 0; i < 2; i++) {
            var site = analysis.partitions().get(i).siteModel();
            assertSame(analysis.sharedVectors().get(0), site.substitutionModel().param("kappa").referenceProperty().get());
            assertSame(analysis.sharedVectors().get(1), site.mutationRate().referenceProperty().get());
            assertEquals(i + 1, site.mutationRate().referenceIndexProperty().get());
        }
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertEquals(1, script.split("~ WeightedDirichlet", -1).length - 1);
        assertEquals(1, script.split("~ IID", -1).length - 1);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "3", "-1", "1.5"})
    void refusesInvalidIndicesOnImport(String index) {
        assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, SOURCE.replace("r[2]", "r[" + index + "]")));
    }

    @Test
    void refusesOutOfBoundsAndRemovedTargetsOnExport() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        var rate = analysis.siteModels().getLast().mutationRate();
        rate.referenceIndexProperty().set(3);
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
        rate.referenceIndexProperty().set(2);
        analysis.sharedVectors().remove(rate.referenceProperty().get());
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
    }

    @Test
    void distinctRatesDoNotDuplicateSharedMatrixPriors() {
        String source = SOURCE.replace("Vector<PositiveReal> kappa ~", "Simplex pi ~ Dirichlet(concentration=[1,1,1,1])\n    Vector<PositiveReal> kappa ~")
                .replace("baseFrequencies=[0.25,0.25,0.25,0.25]", "baseFrequencies=pi")
                .replace("kappa=kappa[2]", "kappa=kappa[1]");
        Analysis analysis = ScriptReader.read(library, source);
        var first = analysis.siteModels().getFirst().substitutionModel().param("baseFrequencies");
        var second = analysis.siteModels().getLast().substitutionModel().param("baseFrequencies");
        assertSame(first, second.referenceProperty().get());
        assertEquals(3, analysis.estimatedParams().size());
        String script = ScriptWriter.write(analysis);
        assertEquals(1, script.split("~ Dirichlet", -1).length - 1);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));
    }

    @Test
    void changedPriorDimensionInvalidatesAnExistingIndex() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        analysis.sharedVectors().getFirst().priorProperty().get().param("num").valueProperty().set("1");
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
    }

    @Test
    void unlinkCopiesTheBindingWithoutResamplingItsVector() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        var partition = analysis.partitions().getFirst();
        analysis.unlinkSiteModel(partition);
        assertEquals(1, partition.siteModel().mutationRate().referenceIndexProperty().get());
        assertEquals(1, partition.siteModel().substitutionModel().param("kappa").referenceIndexProperty().get());
        assertSame(analysis.sharedVectors().get(1), partition.siteModel().mutationRate().referenceProperty().get());
        assertEquals(2, analysis.estimatedParams().size());
        assertEquals(List.of(), Validator.check(library, ScriptWriter.write(analysis)).all());
    }
}
