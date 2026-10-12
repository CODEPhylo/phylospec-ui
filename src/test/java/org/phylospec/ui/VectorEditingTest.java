package org.phylospec.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.phylospec.components.Argument;
import org.phylospec.ui.model.Param;
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

    private Param vector(boolean estimable) {
        Argument argument = new Argument();
        argument.setName("values");
        argument.setType("Vector<Real>");
        argument.setRequired(true);
        return new Param(argument, estimable, library);
    }
}
