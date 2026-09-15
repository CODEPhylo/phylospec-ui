package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    private static final String TILE_LIBRARY_SERVICE =
            "META-INF/services/org.phylospec.tiling.TileLibrary";

    @Test
    void discoversOnlyAdaptersVisibleToTheSuppliedClassLoader() throws Exception {
        try (URLClassLoader isolated = new URLClassLoader(new URL[0], null)) {
            PackageMetadata metadata = PackageMetadata.discover(isolated);

            assertEquals(List.of(), metadata.engines());
            assertEquals(List.of(), metadata.library().overloads("testPopulationFunction"));
        }
    }

    @Test
    void refusesAMissingAdapterPath() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PackageMetadata.discover(List.of(Path.of("missing-adapter.jar"))));

        assertTrue(exception.getMessage().contains("missing-adapter.jar"));
    }

    @Test
    void discoversAdapterJarsInsideADirectory(@TempDir Path directory) throws Exception {
        Path adapter = directory.resolve("test-adapter.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(adapter))) {
            jar.putNextEntry(new JarEntry(TILE_LIBRARY_SERVICE));
            jar.write("org.phylospec.ui.spec.TestTileLibrary\n"
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader withoutParentServices = new ClassLoader(original) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                return TILE_LIBRARY_SERVICE.equals(name)
                        ? Collections.emptyEnumeration()
                        : super.getResources(name);
            }
        };

        Thread.currentThread().setContextClassLoader(withoutParentServices);
        try {
            PackageMetadata metadata = PackageMetadata.discover(List.of(directory));
            assertEquals(List.of("test-package"), metadata.engines().stream()
                    .map(engine -> engine.getName())
                    .toList());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void explainsWhenAnAdapterRuntimeDependencyIsMissing() {
        ClassLoader missingDependency = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("org.phylospec.ui.spec.TestTileLibrary")) {
                    throw new NoClassDefFoundError("example/missing/Dependency");
                }
                return super.loadClass(name, resolve);
            }
        };

        PackageMetadata.LoadFailure failure = assertThrows(PackageMetadata.LoadFailure.class,
                () -> PackageMetadata.discover(missingDependency));

        assertTrue(failure.getMessage().contains("example.missing.Dependency"));
        assertTrue(failure.getMessage().contains("--adapter-path"));
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
