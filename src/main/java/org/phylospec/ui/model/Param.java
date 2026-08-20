package org.phylospec.ui.model;

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
        this.value.set(defaultValue(argument));
        this.estimate.set(this.estimable && this.required);
        this.variable.set(name);
        this.include.set(this.required || argument.getDefault() != null);
    }

    private static String defaultValue(Argument argument) {
        Object supplied = argument.getDefault();
        if (supplied != null) return String.valueOf(supplied);
        return switch (Library.head(argument.getType())) {
            case "Real" -> "0.0";
            case "PositiveReal", "Rate" -> "1.0";
            case "NonNegativeReal", "Age" -> "0.0";
            case "Probability" -> "0.5";
            case "Integer", "NonNegativeInteger", "PositiveInteger", "Count" -> "1";
            case "Simplex" -> "[0.25, 0.25, 0.25, 0.25]";
            case "Vector" -> "[1.0, 1.0, 1.0, 1.0]";
            case "Boolean" -> "true";
            default -> "";
        };
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
