package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.phylospec.components.Generator;
import org.phylospec.ui.spec.PackageMetadata;

class PackageMetadataTest {

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
}
