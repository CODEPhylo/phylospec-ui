package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.phylospec.components.Generator;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Partition;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

class PackageMetadataTest {

    @Test
    void discoversOnlyAdaptersVisibleToTheSuppliedClassLoader() throws Exception {
        try (URLClassLoader isolated = new URLClassLoader(new URL[0], null)) {
            PackageMetadata metadata = PackageMetadata.discover(isolated);

            assertEquals(List.of(), metadata.engines());
            assertEquals(List.of(), metadata.library().overloads("testPopulationFunction"));
        }
    }

    @Test
    void discoversComponentAndEngineMetadataFromTheSameAdapter() {
        PackageMetadata metadata = PackageMetadata.discover();

        assertEquals(
                List.of("test-package"),
                metadata.engines().stream()
                        .map(engine -> engine.getName())
                        .toList());

        List<Generator> generators = metadata.library().overloads("testPopulationFunction");
        assertEquals(1, generators.size());
        assertTrue(metadata.support().supports(generators.getFirst()));
    }

    @Test
    void discoveredPopulationFunctionWritesAndReadsAValidScript() {
        PackageMetadata metadata = PackageMetadata.discover();
        Library library = metadata.library();
        Analysis analysis = new Analysis(library, metadata.support());
        analysis.partitions().add(new Partition(Path.of("primates.nex")));

        Generator coalescent = library.overloads("Coalescent").stream()
                .filter(generator -> generator.getArguments().stream()
                        .anyMatch(argument -> "populationSize".equals(argument.getName())
                                && "PopulationFunction".equals(argument.getType())))
                .findFirst()
                .orElseThrow();
        analysis.treePrior().generatorProperty().set(coalescent);

        Generator packageFunction = library.overloads("testPopulationFunction").getFirst();
        Param populationSize = analysis.treePrior().param("populationSize");
        populationSize.priorProperty().set(Component.nested(
                packageFunction, library, true, metadata.support(), "PopulationFunction"));

        String script = ScriptWriter.write(analysis);
        assertTrue(script.startsWith("use test.functions.coalescent\n"), script);
        assertTrue(script.contains("PopulationFunction populationSize = testPopulationFunction()"), script);
        assertEquals(List.of(), Validator.check(library, script).all(), script);

        Analysis loaded = ScriptReader.read(library, script);
        assertEquals("testPopulationFunction",
                loaded.treePrior().param("populationSize").priorProperty().get().name());
    }
}
