package org.phylospec.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** The selected 32-taxon PopFunc-paper L86 analysis, not the 86-taxon Phylonco skyline. */
@EnabledIfSystemProperty(named = "phylospec.popfuncAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.popfuncL86Analysis", matches = ".+")
class PopFuncL86Test {
    static Analysis example() throws Exception {
        var metadata = PackageMetadata.discover(List.of(PopulationSelectionTest.adapter(), GenotypeErrorTest.adapter()));
        return ScriptReader.read(metadata.library(), metadata.support(),
                Path.of(System.getProperty("phylospec.popfuncL86Analysis")));
    }

    static Component selection(Analysis analysis) {
        return analysis.treePrior().param("populationSize").priorProperty().get();
    }

    static Component inner(Component selection, int index) {
        return selection.param("models").elements().get(index).priorProperty().get();
    }

    static void assertShared(Analysis analysis) {
        Component outer = selection(analysis);
        assertEquals("stochasticPopulationSelection", outer.name());
        assertEquals(2, outer.param("models").elements().size());
        Param ancestor = outer.param("indicator");
        Component without = inner(outer, 0);
        Component with = inner(outer, 1);
        Param indicator = without.param("indicator");
        assertTrue(ancestor.isEstimated());
        assertTrue(indicator.isEstimated());
        assertTrue(with.param("indicator").canReference(analysis.support()));
        assertFalse(with.param("indicator").isEstimated());
        assertSame(indicator, with.param("indicator").referenceProperty().get());
        assertSame(outer.param("models"), ancestor.priorProperty().get().param("models").referenceProperty().get());
        assertSame(without.param("models"), indicator.priorProperty().get().param("models").referenceProperty().get());
        for (Component branch : List.of(without, with)) {
            assertEquals(List.of("constantPopulationFunction", "exponentialPopulationFunction",
                            "logisticPopulationFunction", "gompertzF0PopulationFunction"),
                    branch.param("models").elements().stream().map(p -> p.priorProperty().get().name()).toList());
        }
        Param constant = inner(without, 0).param("populationSize");
        Param size = inner(without, 1).param("populationSize");
        Param growth = inner(without, 1).param("growthRate");
        Param age = inner(without, 2).param("inflectionAge");
        Param proportion = inner(without, 3).param("initialProportion");
        Param initial = inner(without, 3).param("initialPopulationSize");
        Param ancestral = inner(with, 1).param("ancestralPopulationSize");
        assertSame(constant, inner(with, 0).param("populationSize").referenceProperty().get());
        assertSame(size, inner(with, 1).param("populationSize").referenceProperty().get());
        for (Component branch : List.of(without, with)) {
            assertSame(size, inner(branch, 2).param("carryingCapacity").referenceProperty().get());
            assertSame(growth, inner(branch, 2).param("growthRate").referenceProperty().get());
            assertSame(growth, inner(branch, 3).param("growthRate").referenceProperty().get());
        }
        assertSame(growth, inner(with, 1).param("growthRate").referenceProperty().get());
        assertSame(age, inner(with, 2).param("inflectionAge").referenceProperty().get());
        assertSame(proportion, inner(with, 3).param("initialProportion").referenceProperty().get());
        assertSame(initial, inner(with, 3).param("initialPopulationSize").referenceProperty().get());
        for (int i = 1; i < 4; i++) {
            Param absent = inner(without, i).param("ancestralPopulationSize");
            assertTrue(absent == null || !absent.includeProperty().get());
        }
        for (int i = 2; i < 4; i++) assertSame(ancestral, inner(with, i).param("ancestralPopulationSize").referenceProperty().get());
        prior(constant, "LogNormal", "3.0", "2.0");
        prior(size, "LogNormal", "4.5", "2.0");
        prior(growth, "LogNormal", "3.0", "1.0");
        assertEquals("Real", growth.type());
        assertEquals("PositiveReal", growth.valueType());
        prior(age, "LogNormal", "-2.2", "0.8");
        prior(proportion, "Beta", "1.0", "1.0");
        prior(initial, "LogNormal", "4.5", "2.0");
        prior(ancestral, "LogNormal", "4.0", "2.0");
        prior(analysis.likelihood().param("delta"), "Beta", "1.5", "4.5");
        prior(analysis.likelihood().param("epsilon"), "Beta", "2.0", "18.0");
        assertEquals("gt16", analysis.substitutionModel().name());
        assertEquals("GT16ErrorModel", analysis.likelihood().name());
        assertEquals("[1.0, 2.0, 1.0, 1.0, 2.0, 1.0]", analysis.substitutionModel().param("relativeRates")
                .priorProperty().get().param("concentration").valueProperty().get());
        assertEquals("[30.0, 3.0, 3.0, 3.0, 3.0, 30.0, 3.0, 3.0, 3.0, 3.0, 30.0, 3.0, 3.0, 3.0, 3.0, 30.0]",
                analysis.substitutionModel().param("freq").priorProperty().get().param("concentration").valueProperty().get());
        assertEquals(13, analysis.estimatedParams().size(), "No repeated demographic or indicator draws");
        assertFalse(analysis.clockModel().param("clockRate").isEstimated());
        assertEquals("1.0", analysis.clockModel().param("clockRate").valueProperty().get());
    }

    private static void prior(Param param, String name, String first, String second) {
        assertTrue(param.isEstimated(), param.name());
        Component law = param.priorProperty().get();
        assertEquals(name, law.name());
        assertEquals(first, law.param(name.equals("Beta") ? "alpha" : "logMean").valueProperty().get());
        assertEquals(second, law.param(name.equals("Beta") ? "beta" : "logSd").valueProperty().get());
    }

    @Test
    void preservesBothIndicatorsSharedDemographyAndAllPriorsOnRoundTrip() throws Exception {
        Analysis analysis = example();
        assertShared(analysis);
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        assertEquals(2, script.split("~ modelIndicator\\(", -1).length - 1, script);
        assertEquals(6, script.split("~ LogNormal\\(", -1).length - 1, script);
        assertEquals(3, script.split("~ Beta\\(", -1).length - 1, script);
        assertEquals(2, script.split("~ Dirichlet\\(", -1).length - 1, script);
        Analysis restored = ScriptReader.read(analysis.library(), analysis.support(), script);
        assertShared(restored);
        assertEquals(script, ScriptWriter.write(restored));
    }

    @Test
    void copiedTreePriorRebindsTheSharedIndicatorToTheCopy() throws Exception {
        Analysis analysis = example();
        Component copy = Component.estimable(analysis.library(), analysis.support());
        Component.copy(analysis.treePrior(), copy);
        Component copied = copy.param("populationSize").priorProperty().get();
        assertNotSame(inner(selection(analysis), 0).param("indicator"), inner(copied, 0).param("indicator"));
        assertSame(inner(copied, 0).param("indicator"), inner(copied, 1).param("indicator").referenceProperty().get());
        assertSame(inner(copied, 0).param("models"), inner(copied, 0).param("indicator")
                .priorProperty().get().param("models").referenceProperty().get());
        assertEquals("PositiveReal", inner(inner(copied, 0), 1).param("growthRate").valueType());
    }

    @Test
    void rejectsDeletedOrWrongTypeIndicatorReferences() throws Exception {
        Analysis analysis = example();
        inner(selection(analysis), 0).param("indicator").estimateProperty().set(false);
        assertTrue(assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis))
                .getMessage().contains("no longer estimated"));
        inner(selection(analysis), 1).param("indicator").referenceProperty()
                .set(analysis.likelihood().param("epsilon"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis))
                .getMessage().contains("Invalid parameter reference"));
    }

    @Test
    void renamedIndicatorStillHasOnlyOneDraw() throws Exception {
        Analysis analysis = example();
        analysis.partitions().getFirst().nameProperty().set("indicator");
        String source = ScriptWriter.write(analysis);
        assertShared(ScriptReader.read(analysis.library(), analysis.support(), source));
        assertEquals(2, source.split("~ modelIndicator\\(", -1).length - 1);
    }

    @Test
    void uiExportRunsAgainstBothPackagedAdapters() throws Exception {
        run(example());
    }

    static void run(Analysis analysis) throws Exception {
        assertShared(analysis);
        Path directory = Files.createTempDirectory(Files.createDirectories(Path.of("target", "popfunc-l86")),
                "accepted-").toAbsolutePath();
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        analysis.chainLengthProperty().set("30");
        analysis.logEveryProperty().set("1");
        Path source = directory.resolve("analysis.phylospec");
        Files.writeString(source, ScriptWriter.write(analysis));
        assertShared(ScriptReader.read(analysis.library(), analysis.support(), source));
        String classpath = PopulationSelectionTest.adapter().resolve("*") + File.pathSeparator
                + GenotypeErrorTest.adapter().toAbsolutePath().resolve("*");
        Path output = directory.resolve("runner.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.only=true", "-Dbeast.debug=true", "-cp", classpath, "runner.PhyloSpecCli",
                "--library", "popfunc,phylonco,beast2", source.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        String console = Files.readString(output);
        assertTrue(finished, console);
        assertEquals(0, process.exitValue(), console);
        assertTrue(console.contains("ModelIndicatorOperator"), console);
        assertTrue(console.contains("End likelihood:"), console);
        var trace = Files.readAllLines(directory.resolve("analysis.log")).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(32, trace.size());
        List<String> header = List.of(trace.getFirst().trim().split("\\s+"));
        Param ancestor = selection(analysis).param("indicator");
        Param model = inner(selection(analysis), 0).param("indicator");
        for (Param indicator : List.of(ancestor, model)) assertTrue(header.contains(indicator.variableProperty().get()), header.toString());
        for (int sample = 0; sample <= 30; sample++) {
            String[] values = trace.get(sample + 1).trim().split("\\s+");
            assertEquals(sample, Integer.parseInt(values[0]));
            assertEquals(header.size(), values.length);
            for (String value : values) assertTrue(Double.isFinite(Double.parseDouble(value)), trace.get(sample + 1));
            double i = Double.parseDouble(values[header.indexOf(model.variableProperty().get())]);
            double a = Double.parseDouble(values[header.indexOf(ancestor.variableProperty().get())]);
            assertTrue(i >= 0 && i <= 3 && i == Math.rint(i));
            assertTrue(a == 0 || a == 1);
            assertEquals(Double.parseDouble(values[header.indexOf("prior")]) + Double.parseDouble(values[header.indexOf("likelihood")]),
                    Double.parseDouble(values[header.indexOf("posterior")]), 1e-6);
        }
        assertTrue(Files.readString(directory.resolve("analysis.trees")).contains("STATE_30"));
        assertEquals(14, Files.readString(directory.resolve("analysis.state.xml")).split("<statenode ", -1).length - 1);
    }
}
