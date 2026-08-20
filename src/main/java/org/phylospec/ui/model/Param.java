package org.phylospec.ui.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import org.phylospec.components.Argument;
import org.phylospec.ui.spec.Library;

/**
 * One argument of a chosen component, as the user is editing it.
 *
 * <p>A param is either <em>fixed</em> — written into the script as a literal — or <em>estimated</em>,
 * in which case it becomes a random variable with a prior. This single distinction replaces BEAUti's
 * separate editor class per input type.
 */
public final class Param {

    private final String name;
    private final String type;
    private final String description;
    private final boolean required;
    private final boolean estimable;
    private final boolean indicator;
    private final Integer dimension;

    private final StringProperty value = new SimpleStringProperty();
    private final BooleanProperty estimate = new SimpleBooleanProperty(false);
    private final BooleanProperty include = new SimpleBooleanProperty(true);
    private final ObjectProperty<Component> prior = new SimpleObjectProperty<>();

    /** Set when this argument has to be built by calling something rather than typed in. */
    private boolean nested;

    /** The variable name used in the generated script; set by the writer to keep names unique. */
    private final StringProperty variable = new SimpleStringProperty();

    public Param(Argument argument, boolean estimable) {
        this.name = argument.getName();
        this.type = argument.getType();
        this.description = argument.getDescription();
        this.required = Boolean.TRUE.equals(argument.getRequired());
        this.indicator = isIndicator(argument);
        this.estimable = estimable && (indicator || isEstimatableType(type));
        this.dimension = fixedDimension(argument);
        this.value.set(defaultValue(argument, dimension));
        this.estimate.set(this.estimable && this.required);
        this.variable.set(name);
        this.include.set(this.required || argument.getDefault() != null);
    }

    private static String defaultValue(Argument argument, Integer dimension) {
        Object supplied = argument.getDefault();
        if (supplied != null) return String.valueOf(supplied);
        int size = dimension == null ? DEFAULT_DIMENSION : dimension;
        return switch (Library.head(argument.getType())) {
            case "Real" -> "0.0";
            case "PositiveReal", "Rate" -> "1.0";
            case "NonNegativeReal", "Age" -> "0.0";
            case "Probability" -> "0.5";
            case "Integer", "NonNegativeInteger", "PositiveInteger", "Count" -> "1";
            case "Simplex" -> flatSimplex(size);
            case "Vector" -> repeated("1.0", size);
            case "Boolean" -> "true";
            default -> "";
        };
    }

    /**
     * The length a vector-valued argument is given when the library does not say. Right for the
     * nucleotide frequencies that are much the commonest case, and a guess otherwise — which is why
     * an argument whose length is fixed by the model should declare a {@code dimension}.
     */
    private static final int DEFAULT_DIMENSION = 4;

    /**
     * The declared length of a vector-valued argument, when the library gives one as a number.
     *
     * <p>Two spellings say this. The schema has a {@code dimension} field on the argument, and the
     * type language has a {@code num} property — {@code Simplex<;num=6>} — which is already how
     * generated types say it. Neither is enforced by the type resolver, so both are read here and
     * the property wins, being the one a resolver could come to check.
     *
     * <p>A length written as an expression rather than a number — {@code tree.numBranches} — is
     * left alone: it depends on a model that is not built yet, and the writer wires those arguments
     * itself.
     */
    private static Integer fixedDimension(Argument argument) {
        Integer declared = positive(Library.property(argument.getType(), "num"));
        if (declared != null) return declared;
        return argument.getDimension() instanceof Number number
                ? positive(String.valueOf(number.intValue()))
                : positive(String.valueOf(argument.getDimension()));
    }

    private static Integer positive(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private static String repeated(String element, int size) {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> element)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    /**
     * A flat simplex of {@code size} elements.
     *
     * <p>The elements have to sum to one, and for a size that does not divide one exactly — six
     * relative rates, three states — no repeated decimal does. So the last element takes up whatever
     * rounding the others left behind: {@code 1/4} and {@code 1/20} still come out exact, and
     * {@code 1/6} comes out as five of one value and one of another.
     */
    private static String flatSimplex(int size) {
        BigDecimal one = BigDecimal.ONE.setScale(SIMPLEX_SCALE);
        BigDecimal element = one.divide(BigDecimal.valueOf(size), RoundingMode.HALF_UP);
        BigDecimal remainder = one.subtract(element.multiply(BigDecimal.valueOf(size - 1L)));

        List<String> elements = new ArrayList<>(Collections.nCopies(size - 1, plain(element)));
        elements.add(plain(remainder));
        return elements.stream().collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Enough places that the rounding the last element absorbs stays far below the tolerance an
     * engine allows on the sum — BEAST's is 1e-6, and PhyloSpec's own Simplex asks for 1e-10.
     */
    private static final int SIMPLEX_SCALE = 12;

    private static String plain(BigDecimal value) {
        String text = value.stripTrailingZeros().toPlainString();
        return text.contains(".") ? text : text + ".0";
    }

    /**
     * An indicator selects among models rather than measuring anything, so it is sampled to average
     * over the choice — bModelTest's model indicator is the example. A library marks one with the
     * {@code indicator} widget, since nothing about an integer argument's type says which it is.
     */
    private static boolean isIndicator(Argument argument) {
        return argument.getUiHints() != null && "indicator".equals(argument.getUiHints().getWidget());
    }

    /**
     * Only continuous quantities get an estimate checkbox. Other integer-valued arguments in the
     * library are discretisation settings — gamma category counts and the like — which are chosen,
     * not inferred, so offering them a prior would be misleading.
     */
    private static boolean isEstimatableType(String type) {
        return switch (Library.head(type)) {
            case "Real", "PositiveReal", "Rate", "NonNegativeReal", "Age", "Probability", "Simplex" -> true;
            default -> false;
        };
    }

    /** True for arguments that are themselves distributions, such as a relaxed clock's base. */
    public boolean isDistributionValued() {
        return "Distribution".equals(Library.head(type));
    }

    /**
     * True when the value is produced by another component — a distribution, or a function such as
     * the population function a coalescent takes. Both are edited by the same nested chooser.
     */
    public boolean isComponentValued() {
        return isDistributionValued() || nested;
    }

    void markNested() {
        this.nested = true;
    }

    /** The type a prior on this param must support. */
    public String priorSupport() {
        return isDistributionValued() ? Library.inner(type) : type;
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    public String description() {
        return description;
    }

    public boolean required() {
        return required;
    }

    public boolean estimable() {
        return estimable;
    }

    /** True for a choice among models, which is averaged over rather than estimated. */
    public boolean isIndicator() {
        return indicator;
    }

    /** The length the library fixes this argument at, or null if it does not fix one. */
    public Integer dimension() {
        return dimension;
    }

    /**
     * Rebuilds a vector-valued default at the length of the value being drawn, so that a prior on a
     * six-element simplex is given six concentrations rather than the usual four.
     */
    void resizeTo(int size) {
        String head = Library.head(type);
        if (!"Vector".equals(head) && !"Simplex".equals(head)) return;
        value.set("Simplex".equals(head) ? flatSimplex(size) : repeated("1.0", size));
    }

    public StringProperty valueProperty() {
        return value;
    }

    public BooleanProperty estimateProperty() {
        return estimate;
    }

    public BooleanProperty includeProperty() {
        return include;
    }

    public ObjectProperty<Component> priorProperty() {
        return prior;
    }

    public StringProperty variableProperty() {
        return variable;
    }

    public boolean isEstimated() {
        return estimate.get() && estimable;
    }

    /** A human-readable label: {@code baseFrequencies} becomes {@code Base frequencies}. */
    public String label() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) {
                out.append(' ').append(Character.toLowerCase(c));
            } else {
                out.append(i == 0 ? Character.toUpperCase(c) : c);
            }
        }
        return out.toString();
    }
}
