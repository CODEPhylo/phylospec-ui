package org.phylospec.ui.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import org.phylospec.components.Argument;
import org.phylospec.components.Constraint;
import org.phylospec.components.Generator;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;

/**
 * One argument of a chosen component, as the user is editing it.
 *
 * <p>A param is fixed or estimated, in which case it owns a random variable and its prior.
 * A scalar input can instead reference an existing estimated param without another draw.
 * A component-vector input can own editable entries or reference an existing vector.
 */
public final class Param {

    private final String name;
    private final String type;
    private final String description;
    private final boolean required;
    private final boolean estimable;
    private final boolean elementsEstimable;
    private final List<Param> elements = new ArrayList<>();
    private final boolean componentVector;
    private final boolean indicator;
    private final Integer dimension;

    /**
     * The length written as an expression, when it is one: {@code speciesTree.numBranches}.
     *
     * <p>It cannot be turned into a number here, since it depends on a model that is still being
     * built, but it can be handed to whatever draws the value: an {@code IID} over the branches of
     * a species tree needs to be told how many there are, and the declaration already says.
     */
    private final String dimensionExpression;

    private final StringProperty value = new SimpleStringProperty();
    private final BooleanProperty estimate = new SimpleBooleanProperty(false);
    private final BooleanProperty include = new SimpleBooleanProperty(true);
    private final ObjectProperty<Component> prior = new SimpleObjectProperty<>();
    private final ObjectProperty<Param> reference = new SimpleObjectProperty<>();
    // Zero references the whole value; positive indices use PhyloSpec's 1-based indexing.
    private final IntegerProperty referenceIndex = new SimpleIntegerProperty(0);
    private Generator referenceOwner;
    private String drawType;

    /** Set when this argument has to be built by calling something rather than typed in. */
    private boolean nested;

    /** This branch-rate vector can use a distribution conditioned on the active tree. */
    private boolean clockRates;
    private Integer lowerBound;
    private Integer upperBound;

    /** Whether arguments of the selected nested component may themselves be estimated. */
    private boolean nestedArgsEstimable;

    /** The variable name used in the generated script; set by the writer to keep names unique. */
    private final StringProperty variable = new SimpleStringProperty();

    public Param(Argument argument, boolean estimable) {
        this(argument, estimable, null);
    }

    /**
     * The same, with the library on hand to recognise a type by what it extends.
     *
     * <p>An engine's {@code SpeciesTree} is a {@code Tree}, and should be estimable for the same
     * reason: it is a value the model infers. A list of type names could not know that, since the
     * name is the engine's to choose.
     */
    public Param(Argument argument, boolean estimable, Library library) {
        this(argument, estimable, library, null);
    }

    /**
     * The same, with a length the component's constraints fix for this argument.
     *
     * <p>Core states a vector's length as a constraint, {@code baseFrequencies.num == 20}, which
     * belongs to the component rather than to the argument, so it cannot be read from the argument
     * alone. It is the spelling the resolver checks, so it wins over the other two.
     */
    public Param(Argument argument, boolean estimable, Library library, Integer constrainedLength) {
        this.name = argument.getName();
        this.type = argument.getType();
        this.description = argument.getDescription();
        this.required = Boolean.TRUE.equals(argument.getRequired());
        this.indicator = isIndicator(argument);
        this.dimension = constrainedLength != null ? constrainedLength : fixedDimension(argument);
        this.dimensionExpression = declaredExpression(argument, library);
        this.componentVector = library != null && "Vector".equals(Library.head(type))
                && !numeric(Library.element(type)) && !"Alignment".equals(Library.head(Library.element(type)))
                && dimension == null && dimensionExpression == null && Library.property(type, "num") == null
                && !library.producing(Library.element(type)).isEmpty();
        this.elementsEstimable = estimable;
        this.estimable = estimable && (indicator
                || isEstimatableType(type, library));
        this.value.set(defaultValue(argument, dimension));
        this.estimate.set(this.estimable && this.required
                && (!"Vector".equals(Library.head(type)) || dimension != null || dimensionExpression != null));
        this.variable.set(name);
        this.estimate.addListener((observable, was, now) -> {
            if (now) reference.set(null);
        });
        this.reference.addListener((observable, was, now) -> {
            if (now == null) referenceIndex.set(0);
        });
        this.include.set(this.required || argument.getDefault() != null);
    }

    /** Whether a vector of this element type is a list of numbers that can be defaulted. */
    private static boolean numeric(String element) {
        if (element == null) return true;
        // The element of a generated type carries its properties: Vector<PositiveReal; num=...>.
        return switch (Library.head(element.split(";", 2)[0].trim())) {
            case "Real", "PositiveReal", "Rate", "NonNegativeReal", "Age", "Probability",
                    "Integer", "NonNegativeInteger", "PositiveInteger", "Count" -> true;
            default -> false;
        };
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
            // A vector of numbers can be guessed at; a vector of alignments cannot, and a made-up
            // one would hide the fact that nothing has been chosen.
            case "Vector" -> numeric(Library.inner(argument.getType())) ? repeated("1.0", size) : "";
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
    /**
     * The declared length when it is an expression rather than a number, translated into the call
     * that computes it: {@code speciesTree.numBranches} becomes {@code numBranches(speciesTree)}.
     *
     * <p>The two spellings are the same name either way round, which is not a coincidence: a type
     * property and the function that reads it describe the same quantity, so core has
     * {@code numBranches(tree)} beside {@code tree.numBranches}, and {@code num(vector)} beside
     * {@code vector.num}. Not every property has one, though, so the library is asked.
     */
    private static String declaredExpression(Argument argument, Library library) {
        String declared = Library.property(argument.getType(), "num");
        if (declared == null && argument.getDimension() != null) {
            declared = String.valueOf(argument.getDimension());
        }
        if (declared == null || positive(declared) != null) return null;

        int dot = declared.indexOf('.');
        if (dot < 1 || dot == declared.length() - 1) return null;
        String owner = declared.substring(0, dot).trim();
        String property = declared.substring(dot + 1).trim();
        if (!property.matches("[A-Za-z][A-Za-z0-9]*") || !owner.matches("[A-Za-z][A-Za-z0-9]*")) {
            return null;
        }
        // Only if the property has a function to read it. A Tree declares numNodes among its
        // properties and core offers no numNodes(tree), so that length cannot be computed and the
        // vector stays a literal rather than being drawn at a length nothing can work out.
        if (library == null || library.overloads(property).isEmpty()) return null;
        return property + "(" + owner + ")";
    }

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
    private static boolean isEstimatableType(String type, Library library) {
        // A tree, or anything an engine has declared to be one.
        if (library != null && library.isSubtype(type, "Tree")) return true;

        // A declared length is supplied to the prior. Otherwise estimation is opt-in and the
        // user sets the count in the prior (e.g. OrderedUniform), or estimates individual entries.
        if ("Vector".equals(Library.head(type)) && numeric(Library.inner(type))) return true;

        return switch (Library.head(type)) {
            case "Real", "PositiveReal", "Rate", "NonNegativeReal", "Age", "Probability", "Simplex" -> true;
            // A tree can be a value the model estimates rather than the tree the data sits on: a
            // multispecies coalescent draws its gene trees within a species tree, and that species
            // tree is drawn from a Yule of its own. The trees the partitions are drawn on never
            // reach here, since the writer supplies those and they are never params.
            case "Tree" -> true;
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

    void markClockRates() {
        this.clockRates = true;
    }

    public boolean isClockRates() {
        return clockRates;
    }

    void applyIndicatorBounds(List<Constraint> constraints) {
        if (!indicator || constraints == null) return;
        for (Constraint constraint : constraints) {
            if (!name.equals(constraint.getArgument())
                    || !"value".equals(constraint.getProperty())) continue;
            Double constant = constraint.getConstant();
            if (constant == null || !Double.isFinite(constant)
                    || constant != Math.rint(constant)
                    || constant < Integer.MIN_VALUE || constant > Integer.MAX_VALUE) continue;
            int bound = constant.intValue();
            if (constraint.getOperator() == Constraint.Operator.GREATER_THAN_OR_EQUAL) {
                lowerBound = bound;
            } else if (constraint.getOperator() == Constraint.Operator.LESS_THAN_OR_EQUAL) {
                upperBound = bound;
            }
        }
    }

    public Integer lowerBound() {
        return lowerBound;
    }

    public Integer upperBound() {
        return upperBound;
    }

    void markNested(boolean argsEstimable) {
        this.nested = true;
        this.nestedArgsEstimable = argsEstimable;
    }

    /**
     * Builds a replacement for this nested value without losing the estimation policy inherited
     * from its parent component.
     *
     * <p>The population function inside a coalescent is the motivating case. Switching from the
     * default population function to one supplied by a package must not turn all of the new
     * function's numeric arguments into fixed literals.
     */
    public Component nestedChoice(
            Generator generator, Library library, EngineSupport support) {
        if (!nested) {
            throw new IllegalStateException(name + " is not a component-valued argument.");
        }
        return Component.nested(
                generator, library, nestedArgsEstimable, support, type);
    }

    /** The type a prior on this param must support. */
    public String priorSupport() {
        return isDistributionValued() ? Library.inner(type) : valueType();
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    /** A sampled scalar can have a narrower declared type than the argument accepting it. */
    public String valueType() {
        return isEstimated() && drawType != null ? drawType : type;
    }

    public void setDrawType(String declared, Library library) {
        if (!numeric(declared) || declared.contains("<")) return;
        if (!library.isSubtype(declared, type)) {
            throw new IllegalArgumentException("Draw type " + declared + " cannot be used for " + name + " : " + type);
        }
        // Keep the established spelling for equivalent aliases (e.g. Rate/PositiveReal).
        drawType = library.isSubtype(type, declared) ? null : declared;
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

    /** The declared length as a call, for a length that is an expression. Null if there is none. */
    public String dimensionExpression() {
        return dimensionExpression;
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

    /** An input can reference an existing compatible value, or a numeric vector element. */
    public ObjectProperty<Param> referenceProperty() {
        return reference;
    }

    public IntegerProperty referenceIndexProperty() { return referenceIndex; }

    public boolean validReference(Library library, EngineSupport support) {
        Param target = reference.get();
        if (target == null || !canReference(support)) return false;
        int index = referenceIndex.get();
        if (index == 0) return library.isSubtype(target.valueType(), type);
        if (index < 1 || !"Vector".equals(Library.head(target.valueType()))) return false;
        Integer length = target.vectorLength();
        return (length == null || index <= length)
                && library.isSubtype(Library.element(target.valueType()), type);
    }

    /** Known dimensions of the supported numeric priors; unknown lengths remain backend-checked. */
    public Integer vectorLength() {
        if (dimension != null) return dimension;
        Component law = prior.get();
        if (law == null) return null;
        if ("IID".equals(law.name()) && law.param("num") != null) {
            return positive(law.param("num").valueProperty().get());
        }
        if ("WeightedDirichlet".equals(law.name()) && law.param("concentration") != null) {
            Param concentration = law.param("concentration");
            if (!concentration.elements().isEmpty()) return concentration.elements().size();
            try { return concentration.numericLiterals().length; }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    void allowReference(Generator owner) {
        if (componentVector || indicator || ("Vector".equals(Library.head(type)) && numeric(Library.element(type)))
                || switch (Library.head(type)) {
            case "Real", "PositiveReal", "Rate", "NonNegativeReal", "Age", "Probability", "Simplex" -> true;
            default -> false;
        }) referenceOwner = owner;
    }

    public boolean canReference(EngineSupport support) {
        return referenceOwner != null
                && (componentVector || !Boolean.FALSE.equals(support.canBeStochastic(referenceOwner, name)));
    }

    public StringProperty variableProperty() {
        return variable;
    }

    public boolean isEstimated() {
        return reference.get() == null && estimate.get() && estimable;
    }

    /** Numeric entries use the ordinary scalar editor and priors; no extra vector state is made. */
    public boolean canEditElements() {
        return "Vector".equals(Library.head(type)) && numeric(Library.element(type))
                && dimension == null && dimensionExpression == null && Library.property(type, "num") == null;
    }

    public boolean canEditComponents() {
        return componentVector;
    }

    public boolean hasComponentElements() {
        return componentVector && !elements.isEmpty() && reference.get() == null;
    }

    public List<Param> elements() {
        return Collections.unmodifiableList(elements);
    }

    void clearElements() {
        elements.clear();
    }

    public Param addElement(String literal, Library library) {
        return addElement(literal, library, EngineSupport.unclaimed());
    }

    public Param addElement(String literal, Library library, EngineSupport support) {
        if (!canEditElements() && !componentVector) throw new IllegalStateException("This parameter has no editable entries.");
        Argument argument = new Argument();
        argument.setName(name + (elements.size() + 1));
        argument.setType(Library.element(type));
        argument.setRequired(true);
        Param entry = new Param(argument, elementsEstimable, library);
        entry.valueProperty().set(literal);
        entry.estimateProperty().set(false);
        if (componentVector) {
            entry.markNested(elementsEstimable);
            library.producing(entry.type()).stream().filter(support::supports).findFirst().ifPresent(
                    choice -> entry.priorProperty().set(Component.nested(
                            choice, library, elementsEstimable, support, entry.type())));
        }
        entry.estimateProperty().addListener((observable, was, now) -> {
            if (now && entry.priorProperty().get() == null) {
                Generator choice = library.defaultPriorFor(entry.type());
                if (choice != null) entry.priorProperty().set(Component.prior(choice, null, library, entry.type()));
            }
        });
        elements.add(entry);
        return entry;
    }

    /** Entering per-entry mode never discards an expression that we cannot safely interpret. */
    public void editElements(Library library) {
        editElements(library, EngineSupport.unclaimed());
    }

    public void editElements(Library library, EngineSupport support) {
        if (!elements.isEmpty()) return;
        if (componentVector) {
            if (!value.get().isBlank() && !"[]".equals(value.get().trim())) {
                throw new IllegalArgumentException("Import named component entries before editing this expression.");
            }
            addElement("", library, support);
            return;
        }
        for (String entry : numericLiterals()) addElement(entry, library);
    }

    /** A default for a newly chosen prior, not a constraint or an edit to an existing prior. */
    Integer initialVectorLength() {
        if (!canEditElements()) return null;
        if (!elements.isEmpty()) return elements.size();
        try {
            return numericLiterals().length;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String[] numericLiterals() {
        String text = value.get().trim();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            throw new IllegalArgumentException("Enter a numeric array such as [1.0, 2.0] first.");
        }
        String[] values = text.substring(1, text.length() - 1).split(",", -1);
        for (String entry : values) {
            try {
                if (!Double.isFinite(Double.parseDouble(entry.trim()))) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Per-entry editing requires finite numeric literals.");
            }
        }
        return java.util.Arrays.stream(values).map(String::trim).toArray(String[]::new);
    }

    public void removeLastElement() {
        if (elements.size() > 1) elements.remove(elements.size() - 1);
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
