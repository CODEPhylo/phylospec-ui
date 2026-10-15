package org.phylospec.ui;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** Uses packaged engine metadata, not a second UI-specific list of PopFunc capabilities. */
@EnabledIfSystemProperty(named = "phylospec.popfuncAdapter", matches = ".+")
class PopFuncPackageTest {
    @Test
    void fixedPopulationInputsRoundTripWithoutOfferingEstimation() {
        var metadata = metadata();
        var analysis = ScriptReader.read(metadata.library(), metadata.support(), fixedSource());
        var population = analysis.treePrior().param("populationSize").priorProperty().get();
        assertEquals("constantExponentialConstantPopulationFunction", population.name());
        assertTrue(metadata.support().supports(population.generator()));
        assertEquals(4, population.params().size());
        for (var param : population.params()) {
            assertEquals(Boolean.FALSE, metadata.support().canBeStochastic(population.generator(), param.name()));
            assertFalse(param.estimable(), param.name());
            assertFalse(param.isEstimated(), param.name());
        }

        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(metadata.library(), script).all());
        assertTrue(script.contains("recentPopulationSize=1000.0"), script);
        assertTrue(script.contains("growthRate=0.2"), script);
        assertTrue(script.contains("growthStartAge=2.0"), script);
        assertTrue(script.contains("growthEndAge=10.0"), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(metadata.library(), metadata.support(), script)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"recentPopulationSize", "growthRate", "growthStartAge", "growthEndAge"})
    void refusesImportedEstimatedInputRatherThanSilentlyFixingIt(String argument) {
        var metadata = metadata();
        String source = fixedSource()
                .replace("model {", "model {\nPositiveReal sampled ~ LogNormal(logMean=0.0, logSd=1.0)")
                .replaceAll(argument + "=[0-9.]+", argument + "=sampled");
        // The public language allows this; it is the selected engine binding that cannot run it.
        assertEquals(List.of(), Validator.check(metadata.library(), source).all());
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(metadata.library(), metadata.support(), source));
        assertTrue(error.getMessage().contains(argument), error.getMessage());
        assertTrue(error.getMessage().contains("drawn from a distribution"), error.getMessage());
    }

    @Test
    void gompertzStillAllowsAllFourEstimatedInputs() throws Exception {
        var metadata = metadata();
        var analysis = ScriptReader.read(metadata.library(), metadata.support(),
                Path.of("examples/popfunc/gompertz.phylospec"));
        var population = analysis.treePrior().param("populationSize").priorProperty().get();
        assertEquals("gompertzF0PopulationFunction", population.name());
        assertEquals(4, population.params().size());
        for (var param : population.params()) {
            assertEquals(Boolean.TRUE, metadata.support().canBeStochastic(population.generator(), param.name()));
            assertTrue(param.estimable(), param.name());
            assertTrue(param.isEstimated(), param.name());
        }
    }

    private static PackageMetadata metadata() {
        return PackageMetadata.discover(List.of(Path.of(System.getProperty("phylospec.popfuncAdapter"))));
    }

    private static String fixedSource() {
        return """
                use popfunc.functions.coalescent
                data {
                    Alignment data = fromNexus(file="%s")
                }
                model {
                    PopulationFunction populationSize = constantExponentialConstantPopulationFunction(
                        recentPopulationSize=1000.0, growthRate=0.2, growthStartAge=2.0, growthEndAge=10.0)
                    Tree tree ~ Coalescent(populationSize=populationSize, taxa=taxa(data))
                    QMatrix qMatrix = jc69()
                    Alignment alignment ~ PhyloCTMC(tree=tree, qMatrix=qMatrix) observed as data
                }
                """.formatted(Path.of("examples/calibratedcpp/four-taxa.nex").toAbsolutePath());
    }
}
