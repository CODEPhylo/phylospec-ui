package org.phylospec.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.phylospec.components.Argument;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;

import static org.junit.jupiter.api.Assertions.*;

class VectorEditingTest {
    private final Library library = Library.load();

    @Test
    void unknownLengthStaysFixedUntilUserChoosesHowToEstimateIt() {
        Param vector = vector(true);
        assertTrue(vector.estimable());
        assertFalse(vector.isEstimated());
        vector.valueProperty().set("[-0.3, 1.2]");
        vector.editElements(library);
        assertEquals(2, vector.elements().size());
        assertTrue(vector.elements().stream().noneMatch(Param::isEstimated));
        Param first = vector.elements().getFirst();
        first.estimateProperty().set(true);
        assertEquals("Real", first.type());
        assertEquals("Normal", first.priorProperty().get().name());
        assertFalse(vector.elements().get(1).isEstimated());
        vector.removeLastElement();
        vector.removeLastElement();
        assertEquals(1, vector.elements().size());
        vector.addElement("-1.0", library);
        assertEquals("values2", vector.elements().get(1).name());
        assertSame(first, vector.elements().getFirst());
    }

    @Test
    void engineConstantRestrictionAppliesToIndividualEntries() {
        Param vector = vector(false);
        vector.editElements(library);
        assertTrue(vector.elements().stream().noneMatch(Param::estimable));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[1,]", "[NaN]", "[Infinity]", "[x, 1]", "someFunction()"})
    void refusesToDiscardExpressionsOrInvalidValues(String value) {
        Param vector = vector(true);
        vector.valueProperty().set(value);
        assertThrows(IllegalArgumentException.class, () -> vector.editElements(library));
        assertTrue(vector.elements().isEmpty());
        assertEquals(value, vector.valueProperty().get());
    }

    @Test
    void priorMenusRespectVectorElementTypes() {
        assertFalse(library.isSubtype("Vector<Real>", "Vector<PositiveReal>"));
        assertFalse(library.isSubtype("Vector<NonNegativeReal>", "Vector<PositiveReal>"));
        assertTrue(library.isSubtype("Vector<PositiveReal>", "Vector<Real>"));
        assertFalse(library.isSubtype("Simplex", "Vector<PositiveReal>"));
        assertTrue(library.priorsFor("Vector<PositiveReal>").stream()
                .noneMatch(generator -> generator.getName().equals("ExponentialMarkovChain")
                        || generator.getName().equals("Dirichlet") || generator.getName().equals("MultivariateNormal")));
    }

    @Test
    void newVectorPriorKeepsTheLengthOfTheEnteredValues() {
        Param vector = vector(true);
        vector.valueProperty().set("[-0.3, 1.2, 2e-3]");
        Component prior = prior(vector);
        assertEquals("3", prior.param("num").valueProperty().get());
        assertEquals("[-0.3, 1.2, 2e-3]", vector.valueProperty().get());
        assertTrue(vector.elements().isEmpty());
        assertFalse(vector.isEstimated());
    }

    @Test
    void newVectorPriorUsesEditedEntryCountRatherThanOldLiteral() {
        Param vector = vector(true);
        vector.valueProperty().set("[1.0, 2.0]");
        vector.editElements(library);
        vector.addElement("3.0", library).estimateProperty().set(true);
        assertEquals("3", prior(vector).param("num").valueProperty().get());
        assertTrue(vector.elements().getLast().isEstimated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[1,]", "[NaN]", "[Infinity]", "[x, 1]", "someFunction()"})
    void doesNotInventDimensionsForUnknownOrInvalidExpressions(String text) {
        Param vector = vector(true);
        vector.valueProperty().set(text);
        Component prior = Component.prior(library.overloads("IID").getFirst(), null, library, vector.type());
        prior.param("num").valueProperty().set("7");
        Component.sized(prior, vector);
        assertEquals("7", prior.param("num").valueProperty().get());
        assertEquals(text, vector.valueProperty().get());
    }

    @Test
    void declaredDimensionWinsOverLiteralLength() {
        Argument argument = new Argument();
        argument.setName("values");
        argument.setType("Vector<Real;num=6>");
        argument.setRequired(true);
        Param vector = new Param(argument, true, library);
        vector.valueProperty().set("[1.0, 2.0]");
        assertEquals("6", prior(vector).param("num").valueProperty().get());
    }

    private Component prior(Param param) {
        return Component.distributionFor(library.overloads("IID").getFirst(), param, library,
                EngineSupport.unclaimed());
    }

    private Param vector(boolean estimable) {
        Argument argument = new Argument();
        argument.setName("values");
        argument.setType("Vector<Real>");
        argument.setRequired(true);
        return new Param(argument, estimable, library);
    }
}
