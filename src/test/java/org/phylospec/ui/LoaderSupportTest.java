package org.phylospec.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.phylospec.components.Argument__1;
import org.phylospec.components.EngineSpecificationSchema;
import org.phylospec.components.Generator__1;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Partition;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;

import static org.junit.jupiter.api.Assertions.*;

class LoaderSupportTest {
    private static Library library;
    private static EngineSupport beast;
    private static EngineSpecificationSchema beastSpec;
    @TempDir Path directory;

    @BeforeAll
    static void load() throws Exception {
        library = Library.load();
        beastSpec = new ObjectMapper().readValue(Path.of("engines", "beast2-2.8.0-beta4.json").toFile(),
                EngineSpecificationSchema.class);
        beast = EngineSupport.fromSpecifications(List.of(beastSpec));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fa", "fasta", "fst"})
    void rejectsFastaBeforeChangingExistingPartitions(String extension) {
        Analysis analysis = new Analysis(library, beast);
        analysis.addPartition(directory.resolve("first.nex"));
        Partition existing = analysis.partitions().getFirst();
        var tree = existing.tree();
        var site = existing.siteModel();
        var clock = existing.clockModel();
        String treeName = tree.name();

        var failure = assertThrows(IllegalStateException.class,
                () -> analysis.addPartition(directory.resolve("second." + extension)));

        assertTrue(failure.getMessage().contains("fromFasta(file)"), failure.getMessage());
        assertTrue(failure.getMessage().contains("beast2 2.8.0-beta4"), failure.getMessage());
        assertEquals(List.of(existing), analysis.partitions());
        assertSame(tree, existing.tree());
        assertSame(site, existing.siteModel());
        assertSame(clock, existing.clockModel());
        assertEquals(treeName, tree.name());
    }

    @Test
    void acceptsNexusWithoutRequiringUnusedOptionalArguments() {
        assertFalse(library.overloads("fromNexus").stream().anyMatch(beast::supports),
                "The full signatures include speciesName, which this engine does not implement.");
        Analysis analysis = new Analysis(library, beast);
        analysis.addPartition(directory.resolve("first.nex"));
        assertTrue(ScriptWriter.write(analysis).contains("fromNexus("));
        Partition partition = analysis.partitions().getFirst();
        partition.useTipDatesProperty().set(true);
        for (Partition.DateKind kind : Partition.DateKind.values()) {
            partition.dateKindProperty().set(kind);
            assertDoesNotThrow(() -> analysis.requireSupportedLoader(partition));
        }
        partition.useSpeciesProperty().set(true);
        var failure = assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
        assertTrue(failure.getMessage().contains("speciesName"), failure.getMessage());
    }

    @Test
    void keepsFastaEditingAvailableWithoutEngineClaims() {
        Analysis analysis = new Analysis(library);
        analysis.addPartition(directory.resolve("first.fasta"));
        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("fromFasta("), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));
    }

    @Test
    void usesAdditionalProviderForFastaWithoutChangingBaseSupport() {
        EngineSupport combined = EngineSupport.fromSpecifications(List.of(beastSpec,
                engine("fasta-package", "fromFasta", List.of("file"), List.of())));
        Analysis analysis = new Analysis(library, combined);
        analysis.addPartition(directory.resolve("first.fasta"));
        String script = ScriptWriter.write(analysis);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, combined, script)));
        assertFalse(beast.verdictForCall("fromFasta", Set.of("file")).supported());
    }

    @Test
    void requiresOneProviderToCoverTheWholeCall() {
        EngineSupport split = EngineSupport.fromSpecifications(List.of(
                engine("first", "fromNexus", List.of("file"), List.of("age")),
                engine("second", "fromNexus", List.of("file"), List.of("speciesName"))));
        assertTrue(split.verdictForCall("fromNexus", Set.of("file", "age")).supported());
        assertTrue(split.verdictForCall("fromNexus", Set.of("file", "speciesName")).supported());
        assertFalse(split.verdictForCall("fromNexus", Set.of("file", "age", "speciesName")).supported());
        assertFalse(split.verdictForCall("fromNexus", Set.of("age")).supported());
    }

    @Test
    void doesNotSubstituteADifferentDateOverload() {
        Analysis analysis = new Analysis(library, EngineSupport.fromSpecifications(List.of(
                engine("age-only", "fromNexus", List.of("file"), List.of("age")))));
        analysis.addPartition(directory.resolve("first.nex"));
        Partition partition = analysis.partitions().getFirst();
        partition.useTipDatesProperty().set(true);
        assertDoesNotThrow(() -> analysis.requireSupportedLoader(partition));
        partition.dateKindProperty().set(Partition.DateKind.DATE);
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
    }

    @Test
    void rejectsUnsupportedScriptInputBeforeReturningAnAnalysis() {
        Analysis unrestricted = new Analysis(library);
        unrestricted.addPartition(directory.resolve("first.fasta"));
        String script = ScriptWriter.write(unrestricted);
        var failure = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, beast, script));
        assertTrue(failure.getMessage().contains("fromFasta(file)"), failure.getMessage());
    }

    @Test
    void refusesToChangeScriptLoaderBasedOnTheFileExtension() {
        Analysis unrestricted = new Analysis(library);
        unrestricted.addPartition(directory.resolve("first.nex"));
        String script = ScriptWriter.write(unrestricted).replace("fromNexus(", "fromFasta(");
        var failure = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, script));
        assertTrue(failure.getMessage().contains("Cannot preserve fromFasta"), failure.getMessage());
        unrestricted.partitions().getFirst().fileProperty().set(directory.resolve("first.fasta").toString());
        String other = ScriptWriter.write(unrestricted).replace("fromFasta(", "fromNexus(");
        assertThrows(ScriptReader.Unsupported.class, () -> ScriptReader.read(library, other));
    }

    @Test
    void checksExportBeforeRenamingParametersEvenWhenTheAddMethodWasBypassed() {
        Analysis analysis = new Analysis(library, beast);
        analysis.partitions().add(new Partition(directory.resolve("first.fasta")));
        var kappa = analysis.substitutionModel().param("kappa");
        kappa.variableProperty().set("unchangedKappa");
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
        assertEquals("unchangedKappa", kappa.variableProperty().get());
    }

    @Test
    void catchesFileChangesAfterImport() {
        Analysis analysis = new Analysis(library, beast);
        analysis.addPartition(directory.resolve("first.nex"));
        analysis.partitions().getFirst().fileProperty().set(directory.resolve("first.fasta").toString());
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
    }

    @Test
    void doesNotMistakeReadCountsForAnOrdinaryNexusAlignment() throws Exception {
        Path counts = directory.resolve("counts.nex");
        Files.writeString(counts, "#NEXUS\nbegin data; format datatype=readCount; matrix a 1:2:3:4; end;\n");
        Analysis analysis = new Analysis(library, beast);
        var failure = assertThrows(IllegalStateException.class, () -> analysis.addPartition(counts));
        assertTrue(failure.getMessage().contains("fromReadCountNexus"), failure.getMessage());
        assertTrue(analysis.partitions().isEmpty());
    }

    private static EngineSpecificationSchema engine(
            String name, String loader, List<String> required, List<String> optional) {
        List<Argument__1> arguments = new ArrayList<>();
        for (String argument : required) {
            Argument__1 input = new Argument__1();
            input.setName(argument);
            input.setRequired(true);
            arguments.add(input);
        }
        for (String argument : optional) {
            Argument__1 input = new Argument__1();
            input.setName(argument);
            input.setRequired(false);
            arguments.add(input);
        }
        Generator__1 generator = new Generator__1();
        generator.setName(loader);
        generator.setArguments(arguments);
        EngineSpecificationSchema specification = new EngineSpecificationSchema();
        specification.setName(name);
        specification.setEngineVersion("test");
        specification.setGenerators(List.of(generator));
        return specification;
    }
}
