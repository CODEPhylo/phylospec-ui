package org.phylospec.ui;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

class ParameterReferenceTest {
    private final Library library = Library.load();

    @Test
    void retainsNarrowScalarDrawTypeWithoutWeakeningTheArgumentType() {
        var argument = new org.phylospec.components.Argument();
        argument.setName("growthRate");
        argument.setType("Real");
        argument.setRequired(true);
        Param param = new Param(argument, true, library);
        param.setDrawType("PositiveReal", library);
        assertEquals("Real", param.type());
        assertEquals("PositiveReal", param.valueType());
        assertEquals("PositiveReal", param.priorSupport());
        param.estimateProperty().set(false);
        assertEquals("Real", param.valueType(), "Fixed values still use the original input domain.");
        param.estimateProperty().set(true);
        assertEquals("PositiveReal", param.valueType());

        argument.setType("PositiveReal");
        Param positive = new Param(argument, true, library);
        assertThrows(IllegalArgumentException.class, () -> positive.setDrawType("Real", library));
        assertEquals("PositiveReal", positive.type());

        argument.setType("Rate");
        Param rate = new Param(argument, true, library);
        rate.setDrawType("PositiveReal", library);
        assertEquals("Rate", rate.valueType(), "Equivalent aliases keep the existing generated spelling.");
    }

    static final String SOURCE = """
            data {
                Alignment data = fromNexus(file="examples/calibratedcpp/four-taxa.nex")
            }
            model {
                PositiveReal theta0 ~ LogNormal(logMean=-2.3,logSd=1.8)
                PositiveReal theta1 ~ Exponential(mean=theta0)
                Vector<PositiveReal> populations = [theta0,theta1]
                Vector<PositiveInteger> groups ~ UniformComposition(total=3,num=2)
                Tree tree ~ BayesianSkyline(populationSizes=populations,groupSizes=groups,taxa=taxa(data))
                QMatrix qMatrix = jc69()
                Vector<Rate> branchRates ~ StrictClock(clockRate=1.0,tree=tree)
                Alignment observed ~ PhyloCTMC(tree=tree,qMatrix=qMatrix,branchRates=branchRates) observed as data
            }
            mcmc {
                Integer chainLength=30
                Integer defaultLogEvery=1
                Integer randomSeed=34891
                String outputPrefix="analysis"
            }
            """;

    @Test
    void namedVectorAndConditionalPriorsRoundTripWithoutDuplicatingState() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        var entries = entries(analysis);
        assertEquals(2, entries.size());
        assertSame(entries.getFirst(), mean(entries.getLast()).referenceProperty().get());
        assertEquals(3, analysis.estimatedParams().size());
        Param groups = analysis.treePrior().param("groupSizes");
        assertTrue(groups.isEstimated());
        assertEquals("UniformComposition", groups.priorProperty().get().name());
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertEquals(1, script.split("~ LogNormal\\(", -1).length - 1);
        assertEquals(1, script.split("~ Exponential\\(", -1).length - 1);
        assertTrue(script.contains("mean=populationSizes1"), script);
        Analysis reloaded = ScriptReader.read(library, script);
        assertSame(entries(reloaded).getFirst(), mean(entries(reloaded).getLast()).referenceProperty().get());
        assertEquals(script, ScriptWriter.write(reloaded));
    }

    @Test
    void declaresDependenciesBeforeTheirUsersEvenWhenVectorOrderIsReversed() {
        Analysis analysis = ScriptReader.read(library, SOURCE.replace("[theta0,theta1]", "[theta1,theta0]"));
        String script = ScriptWriter.write(analysis);
        assertTrue(script.indexOf("~ LogNormal(") < script.indexOf("~ Exponential("), script);
        assertTrue(script.contains("populationSizes=[populationSizes1, populationSizes2]"), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));
    }

    @Test
    void copyingATreePriorRebindsInternalReferencesToTheCopiedEntries() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        Component copy = Component.estimable(library);
        Component.copy(analysis.treePrior(), copy);
        var copied = copy.param("populationSizes").elements();
        assertNotSame(entries(analysis).getFirst(), copied.getFirst());
        assertSame(copied.getFirst(), mean(copied.getLast()).referenceProperty().get());
        Component.copy(analysis.treePrior(), copy);
        assertEquals(2, copy.param("populationSizes").elements().size());
    }

    @Test
    void retainedReferenceFollowsRenamedVariables() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        // Reserve the usual parameter name for a data partition. References must follow the
        // writer's unique name, not retain the string that was typed or originally imported.
        analysis.partitions().getFirst().nameProperty().set("populationSizes1");
        String script = ScriptWriter.write(analysis);
        String target = entries(analysis).getFirst().variableProperty().get();
        assertNotEquals("populationSizes1", target);
        assertTrue(script.contains("mean=" + target), script);
        assertEquals(List.of(), Validator.check(library, script).all());
    }

    @Test
    void refusesDanglingReferenceAfterAnEntryIsFixed() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        entries(analysis).getFirst().estimateProperty().set(false);
        var error = assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
        assertTrue(error.getMessage().contains("no longer estimated"), error.getMessage());
    }

    @Test
    void refusesDanglingReferenceAfterAVectorIsReplaced() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        Param entries = analysis.treePrior().param("populationSizes");
        Param removed = entries.elements().getLast();
        entries.elements().getFirst().priorProperty().get().param("logMean").referenceProperty().set(removed);
        entries.removeLastElement();
        var error = assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
        assertTrue(error.getMessage().contains("no longer estimated"), error.getMessage());
    }

    @Test
    void refusesCyclicReferencesWithoutStackOverflow() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        entries(analysis).getFirst().priorProperty().get().param("logMean")
                .referenceProperty().set(entries(analysis).getLast());
        var error = assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
        assertTrue(error.getMessage().contains("Cyclic parameter reference"), error.getMessage());
    }

    @Test
    void refusesWrongReferenceType() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        mean(entries(analysis).getLast()).referenceProperty().set(analysis.treePrior().param("groupSizes"));
        var error = assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
        assertTrue(error.getMessage().contains("Invalid parameter reference"), error.getMessage());
    }

    @Test
    void refusesAHyperpriorWithoutAnEditableOwnerInsteadOfDroppingIt() {
        String source = SOURCE.replace("~ LogNormal(logMean=-2.3,logSd=1.8)", "~ Exponential(mean=hyper)")
                .replace("model {", "model {\n PositiveReal hyper ~ Exponential(mean=1.0)");
        var error = assertThrows(ScriptReader.Unsupported.class, () -> ScriptReader.read(library, source));
        assertTrue(error.getMessage().contains("needs an estimated parameter"), error.getMessage());
    }

    @Test
    void doesNotTurnRepeatedSampledEntriesIntoIndependentPriors() {
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, SOURCE.replace("[theta0,theta1]", "[theta0,theta0]")));
        assertTrue(error.getMessage().contains("Repeated sampled vector entry"), error.getMessage());
    }

    @Test
    void literalModeRemainsEditableAfterClearingAReference() {
        Analysis analysis = ScriptReader.read(library, SOURCE);
        mean(entries(analysis).getLast()).referenceProperty().set(null);
        mean(entries(analysis).getLast()).valueProperty().set("0.25");
        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("Exponential(mean=0.25)"), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));
    }

    @Test
    void engineConstantOnlyInputCannotReferenceASampledScalar() {
        var argument = new org.phylospec.components.Argument__1();
        argument.setName("mean");
        argument.setRequired(true);
        argument.setCanBeStochastic(false);
        var generator = new org.phylospec.components.Generator__1();
        generator.setName("Exponential");
        generator.setArguments(List.of(argument));
        // Declare the source loader without importing another engine's stochastic Exponential.
        var file = new org.phylospec.components.Argument__1();
        file.setName("file");
        file.setRequired(true);
        var loader = new org.phylospec.components.Generator__1();
        loader.setName("fromNexus");
        loader.setArguments(List.of(file));
        var specification = new org.phylospec.components.EngineSpecificationSchema();
        specification.setName("constant-only-test-engine");
        specification.setGenerators(List.of(generator, loader));
        var support = EngineSupport.fromSpecifications(List.of(specification));
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, support, SOURCE));
        assertTrue(error.getMessage().contains("which this tab cannot express"), error.getMessage());
        Analysis analysis = ScriptReader.read(library, SOURCE);
        assertFalse(mean(entries(analysis).getLast()).canReference(support));
    }

    static List<Param> entries(Analysis analysis) {
        return analysis.treePrior().param("populationSizes").elements();
    }

    static Param mean(Param param) {
        return param.priorProperty().get().param("mean");
    }
}
