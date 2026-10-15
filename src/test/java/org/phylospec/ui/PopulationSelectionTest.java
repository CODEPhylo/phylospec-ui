package org.phylospec.ui;

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

@EnabledIfSystemProperty(named = "phylospec.popfuncAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.ancestralAnalysis", matches = ".+")
class PopulationSelectionTest {
    static Analysis example() throws Exception {
        var metadata = PackageMetadata.discover(List.of(adapter()));
        return ScriptReader.read(metadata.library(), metadata.support(),
                Path.of(System.getProperty("phylospec.ancestralAnalysis")));
    }

    static Path adapter() {
        return Path.of(System.getProperty("phylospec.popfuncAdapter")).toAbsolutePath();
    }

    static Component selection(Analysis analysis) {
        return analysis.treePrior().param("populationSize").priorProperty().get();
    }

    static Param models(Analysis analysis) {
        return selection(analysis).param("models");
    }

    static void assertShared(Analysis analysis) {
        Component selection = selection(analysis);
        assertEquals("stochasticPopulationSelection", selection.name());
        Param indicator = selection.param("indicator");
        assertTrue(indicator.isEstimated());
        assertTrue(indicator.isIndicator());
        assertEquals("modelIndicator", indicator.priorProperty().get().name());
        assertSame(models(analysis), indicator.priorProperty().get().param("models").referenceProperty().get());
        var entries = models(analysis).elements();
        assertEquals(2, entries.size());
        Component without = entries.getFirst().priorProperty().get();
        Component with = entries.getLast().priorProperty().get();
        assertEquals("gompertzF0PopulationFunction", without.name());
        assertEquals(without.name(), with.name());
        for (String name : List.of("initialProportion", "growthRate", "initialPopulationSize")) {
            assertTrue(without.param(name).isEstimated(), name);
            assertFalse(with.param(name).isEstimated(), name);
            assertSame(without.param(name), with.param(name).referenceProperty().get(), name);
        }
        assertFalse(without.param("ancestralPopulationSize").includeProperty().get());
        assertTrue(with.param("ancestralPopulationSize").isEstimated());
        assertEquals(5, analysis.estimatedParams().size(), "Four demographics and one indicator; no duplicated priors.");
        assertEquals("Beta", without.param("initialProportion").priorProperty().get().name());
        assertPrior(without.param("initialProportion"), "alpha", "20.0", "beta", "7.0");
        assertPrior(without.param("growthRate"), "logMean", "-0.95", "logSd", "0.2");
        assertPrior(without.param("initialPopulationSize"), "logMean", "5.0", "logSd", "0.5");
        assertPrior(with.param("ancestralPopulationSize"), "logMean", "3.0", "logSd", "0.3");
        assertEquals("0.01", analysis.clockModel().param("clockRate").valueProperty().get());
        assertFalse(analysis.clockModel().param("clockRate").isEstimated());
    }

    private static void assertPrior(Param param, String a, String x, String b, String y) {
        Component prior = param.priorProperty().get();
        assertEquals(x, prior.param(a).valueProperty().get());
        assertEquals(y, prior.param(b).valueProperty().get());
    }

    @Test
    void importsAndRoundTripsTheSharedModelVectorAndAllOriginalPriors() throws Exception {
        Analysis analysis = example();
        assertShared(analysis);
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        assertEquals(1, script.split("Vector<PopulationFunction> models =", -1).length - 1, script);
        assertEquals(1, script.split("~ modelIndicator\\(models=models\\)", -1).length - 1, script);
        assertEquals(3, script.split("~ LogNormal\\(", -1).length - 1, script);
        Analysis restored = ScriptReader.read(analysis.library(), analysis.support(), script);
        assertShared(restored);
        assertEquals(script, ScriptWriter.write(restored));
    }

    @Test
    void copiedPopulationKeepsInternalReferencesWithinTheCopy() throws Exception {
        Analysis analysis = example();
        Component copy = Component.estimable(analysis.library(), analysis.support());
        Component.copy(analysis.treePrior(), copy);
        Component population = copy.param("populationSize").priorProperty().get();
        Param copied = population.param("models");
        assertNotSame(models(analysis), copied);
        assertSame(copied, population.param("indicator").priorProperty().get().param("models").referenceProperty().get());
        for (String name : List.of("growthRate", "initialPopulationSize", "initialProportion")) {
            assertSame(copied.elements().getFirst().priorProperty().get().param(name),
                    copied.elements().getLast().priorProperty().get().param(name).referenceProperty().get());
        }
    }

    @Test
    void refusesDeletedSharedParameterInsteadOfGivingItANewPrior() throws Exception {
        Analysis analysis = example();
        models(analysis).elements().getFirst().priorProperty().get()
                .param("growthRate").estimateProperty().set(false);
        assertTrue(assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis))
                .getMessage().contains("no longer estimated"));
    }

    @Test
    void refusesWrongTypeAndCyclicVectorReferences() throws Exception {
        Analysis analysis = example();
        Param reference = selection(analysis).param("indicator").priorProperty().get().param("models");
        reference.referenceProperty().set(models(analysis).elements().getFirst().priorProperty().get().param("growthRate"));
        assertTrue(assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis))
                .getMessage().contains("Invalid parameter reference"));
        reference.referenceProperty().set(models(analysis));
        models(analysis).referenceProperty().set(models(analysis));
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
    }

    @Test
    void editedDemographicPriorAndRenamedVariablesKeepSharing() throws Exception {
        Analysis analysis = example();
        analysis.partitions().getFirst().nameProperty().set("models");
        models(analysis).elements().getFirst().priorProperty().get().param("growthRate")
                .priorProperty().get().param("logMean").valueProperty().set("-1.0");
        String script = ScriptWriter.write(analysis);
        assertNotEquals("models", models(analysis).variableProperty().get());
        assertTrue(script.contains("modelIndicator(models=" + models(analysis).variableProperty().get() + ")"), script);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), script)));
    }

    @Test
    void newSelectionOffersEditableModelEntriesWithoutInventingASecondVector() throws Exception {
        Analysis analysis = example();
        Component population = selection(analysis);
        Component fresh = Component.nested(population.generator(), analysis.library(), true, analysis.support());
        Param models = fresh.param("models");
        assertTrue(models.canEditComponents());
        assertFalse(models.isEstimated());
        assertTrue(models.elements().isEmpty());
        models.editElements(analysis.library(), analysis.support());
        models.addElement("", analysis.library(), analysis.support());
        assertEquals(2, models.elements().size());
        for (Param entry : models.elements()) {
            assertTrue(entry.isComponentValued());
            assertFalse(entry.isEstimated());
            assertNotNull(entry.priorProperty().get().generator());
            assertTrue(analysis.support().supports(entry.priorProperty().get().generator()));
        }
        models.removeLastElement();
        models.removeLastElement();
        assertEquals(1, models.elements().size(), "The editor cannot silently empty the candidate set.");
    }

    @Test
    void refusesPriorVectorWithoutAnEditableModelOwner() throws Exception {
        Analysis analysis = example();
        String source = ScriptWriter.write(analysis)
                .replace("modelIndicator(models=models)", "modelIndicator(models=otherModels)")
                .replace("Vector<PopulationFunction> models = [models1, models2]",
                        "Vector<PopulationFunction> models = [models1, models2]\n"
                                + "Vector<PopulationFunction> otherModels = [models1, models2]");
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(analysis.library(), analysis.support(), source));
        assertTrue(error.getMessage().contains("needs a compatible editable owner"), error.getMessage());
    }

    @Test
    void refusesEmptyRepeatedOrInlineComponentEntries() throws Exception {
        Analysis analysis = example();
        String original = ScriptWriter.write(analysis);
        var empty = assertThrows(ScriptReader.Unsupported.class, () -> ScriptReader.read(
                analysis.library(), analysis.support(), original.replace("models = [models1, models2]", "models = []")));
        assertTrue(empty.getMessage().contains("cannot be assigned to variable `models`"), empty.getMessage());
        for (String entries : List.of("[models1, models1]",
                "[gompertzF0PopulationFunction(initialProportion=0.7,growthRate=0.3,initialPopulationSize=100.0)]")) {
            String source = original.replace("models = [models1, models2]", "models = " + entries);
            var error = assertThrows(ScriptReader.Unsupported.class,
                    () -> ScriptReader.read(analysis.library(), analysis.support(), source), entries);
            assertTrue(error.getMessage().contains("component vector")
                    || error.getMessage().contains("Component vector"), error.getMessage());
        }
    }

    @Test
    void exportedModelRunsWithOnlyPackagedJars() throws Exception {
        Analysis analysis = example();
        assertShared(analysis);
        run(analysis);
    }

    static void run(Analysis analysis) throws Exception {
        Path directory = Files.createTempDirectory(Files.createDirectories(Path.of("target", "population-selection")),
                "accepted-").toAbsolutePath();
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        Path source = directory.resolve("analysis.phylospec");
        Files.writeString(source, script);
        assertShared(ScriptReader.read(analysis.library(), analysis.support(), source));
        Path output = directory.resolve("runner.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.only=true", "-Dbeast.debug=true", "-cp", adapter().resolve("*").toString(),
                "runner.PhyloSpecCli", "--library", "popfunc,beast2", source.toString())
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
        int length = Integer.parseInt(analysis.chainLengthProperty().get());
        var trace = Files.readAllLines(directory.resolve("analysis.log")).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(length + 2, trace.size());
        List<String> header = List.of(trace.getFirst().trim().split("\\s+"));
        assertTrue(header.containsAll(List.of("initialProportion", "growthRate", "initialPopulationSize",
                "ancestralPopulationSize", "indicator", "posterior", "prior", "likelihood")), header.toString());
        for (int sample = 0; sample <= length; sample++) {
            String[] values = trace.get(sample + 1).trim().split("\\s+");
            assertEquals(sample, Integer.parseInt(values[0]));
            assertEquals(header.size(), values.length);
            for (String value : values) assertTrue(Double.isFinite(Double.parseDouble(value)), trace.get(sample + 1));
            double indicator = Double.parseDouble(values[header.indexOf("indicator")]);
            assertTrue(indicator == 0 || indicator == 1);
            assertEquals(Double.parseDouble(values[header.indexOf("prior")]) + Double.parseDouble(values[header.indexOf("likelihood")]),
                    Double.parseDouble(values[header.indexOf("posterior")]), 1e-6);
        }
        assertEquals(6, Files.readString(directory.resolve("analysis.state.xml")).split("<statenode ", -1).length - 1);
        assertTrue(Files.readString(directory.resolve("analysis.trees")).contains("STATE_" + length));
    }
}
