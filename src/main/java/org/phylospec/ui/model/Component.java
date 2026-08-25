package org.phylospec.ui.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.phylospec.components.Argument;
import org.phylospec.components.Generator;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;

/**
 * A chosen generator together with the params the user edits for it.
 *
 * <p>The same class backs the substitution model, the site rates, the clock model, the tree prior
 * and every prior distribution — a prior is simply a component whose arguments cannot themselves
 * be estimated.
 */
public final class Component {

    /**
     * Arguments the writer supplies from the structure of the analysis rather than from the user:
     * every tree distribution needs {@code taxa}, every clock needs {@code tree}, and so on.
     */
    private static final Set<String> WIRED = Set.of("tree", "taxa", "numSites", "qMatrix", "siteRates", "branchRates");

    private final ObjectProperty<Generator> generator = new SimpleObjectProperty<>();
    private final ObservableList<Param> params = FXCollections.observableArrayList();
    private final boolean argsEstimable;
    private final Library library;
    private final EngineSupport support;

    /**
     * Whether this is one of the analysis's own model slots, whose structural arguments the writer
     * supplies from the shape of the analysis.
     *
     * <p>A prior is not: a Yule over an estimated species tree has to be told which taxa it spans,
     * because no partition is drawn on that tree and nothing else can say. Neither is a nested
     * function, for the same reason.
     */
    private final boolean structural;

    /**
     * What this component's type variables stand for, when it came from a generic generator.
     *
     * <p>Core's {@code IID} takes a {@code Distribution<T>}; which distribution the tab should
     * offer depends on what is being drawn, so the binding is worked out where the component is
     * chosen and applied to every argument here. Empty for everything that is not generic.
     */
    private Map<String, String> bindings = Map.of();

    private Component(boolean argsEstimable, Library library, EngineSupport support, boolean structural) {
        this.argsEstimable = argsEstimable;
        this.library = library;
        this.support = support;
        this.structural = structural;
        generator.addListener((observable, old, chosen) -> rebuild(chosen));
    }

    /** A component whose arguments each get an "estimate" checkbox. */
    public static Component estimable(Library library) {
        return estimable(library, EngineSupport.unclaimed());
    }

    /**
     * The same, but with the engines consulted over which arguments may be drawn from a
     * distribution. An engine that cannot sample an argument should not be offered the tick.
     */
    public static Component estimable(Library library, EngineSupport support) {
        return new Component(true, library, support, true);
    }

    /** A prior: its hyperparameters are always fixed. */
    public static Component prior(Generator generator) {
        return prior(generator, null, null);
    }

    /** A prior whose own component-valued arguments can still be chosen from {@code library}. */
    public static Component prior(Generator generator, Integer dimension) {
        return prior(generator, dimension, null);
    }

    /**
     * A prior on a value whose length the library fixes. Its own vector-valued hyperparameters are
     * built to that length: a Dirichlet drawing a six-element simplex needs six concentrations, and
     * nothing on the Dirichlet's side of the library can say so.
     */
    public static Component prior(Generator generator, Integer dimension, Library library) {
        return prior(generator, dimension, library, null);
    }

    /** The same, for a prior over {@code support}, which decides any type variables it has. */
    public static Component prior(Generator generator, Integer dimension, Library library, String support) {
        Component component = new Component(false, library, EngineSupport.unclaimed(), false);
        if (library != null && support != null) {
            component.boundTo(library.bindingsForPrior(generator, support));
        }
        component.generator.set(generator);
        if (dimension != null) {
            component.params().forEach(param -> param.resizeTo(dimension));
        }
        return component;
    }

    /** A nested function, whose own arguments stay editable — and estimable — like its parent's. */
    public static Component nested(Generator generator, Library library, boolean argsEstimable) {
        return nested(generator, library, argsEstimable, EngineSupport.unclaimed());
    }

    /** The same, with the engines consulted as in {@link #estimable(Library, EngineSupport)}. */
    public static Component nested(
            Generator generator, Library library, boolean argsEstimable, EngineSupport support) {
        return nested(generator, library, argsEstimable, support, null);
    }

    /** The same, for a value of {@code wanted}, which decides any type variables the generator has. */
    public static Component nested(Generator generator, Library library, boolean argsEstimable,
            EngineSupport support, String wanted) {
        Component component = new Component(argsEstimable, library, support, false);
        if (library != null && wanted != null) {
            component.boundTo(library.bindingsForValue(generator, wanted));
        }
        component.generator.set(generator);
        return component;
    }

    /**
     * Makes {@code to} a copy of {@code from}: the same generator, the same values, ticks and
     * priors, all the way down.
     *
     * <p>This is what unlinking uses. Unlinking is nearly always the prelude to changing one thing
     * about one partition, so a copy of what was already chosen asks the user for less than a fresh
     * default would, and loses nothing.
     */
    public static void copy(Component from, Component to) {
        to.generator.set(from.generator());
        if (from.generator() == null) return;

        for (Param source : from.params()) {
            Param target = to.param(source.name());
            if (target == null) continue;

            target.valueProperty().set(source.valueProperty().get());
            target.includeProperty().set(source.includeProperty().get());
            // Ticking this attaches a default prior, which the copy below then overwrites.
            target.estimateProperty().set(source.isEstimated());

            Component sourceInner = source.priorProperty().get();
            if (sourceInner == null || sourceInner.generator() == null) continue;

            Component targetInner = target.priorProperty().get();
            if (targetInner == null) {
                targetInner = source.isEstimated()
                        ? prior(sourceInner.generator(), target.dimension(), to.library)
                        : nested(sourceInner.generator(), to.library, to.argsEstimable, to.support);
                target.priorProperty().set(targetInner);
            }
            copy(sourceInner, targetInner);
        }
    }

    private void rebuild(Generator chosen) {
        params.clear();
        if (chosen == null) return;
        for (Argument argument : chosen.getArguments()) {
            argument = substituted(argument);
            // The writer supplies the structural arguments of the model's own components. A prior
            // is not one of those: a Yule over an estimated species tree has to be told which taxa
            // it spans, because no partition is drawn on it.
            if (structural && WIRED.contains(argument.getName())) continue;
            Param param = new Param(argument, argsEstimable && canBeStochastic(chosen, argument),
                    library, Library.declaredLength(chosen, argument.getName()));
            if (needsNesting(param)) param.markNested();
            attachPriors(param);
            params.add(param);
        }
    }

    /**
     * The same argument with its type variables replaced, or the argument itself when there are
     * none. A copy, since the declaration belongs to the library and is shared by everything.
     */
    private Argument substituted(Argument argument) {
        String type = Library.substitute(argument.getType(), bindings);
        if (type == null || type.equals(argument.getType())) return argument;

        Argument copy = new Argument();
        copy.setName(argument.getName());
        copy.setType(type);
        copy.setRequired(argument.getRequired());
        copy.setRecommended(argument.getRecommended());
        copy.setDefault(argument.getDefault());
        copy.setDimension(argument.getDimension());
        copy.setDescription(argument.getDescription());
        copy.setUiHints(argument.getUiHints());
        return copy;
    }

    /** Fixes what this component's type variables stand for, before its params are built. */
    private Component boundTo(Map<String, String> bindings) {
        this.bindings = bindings == null ? Map.of() : bindings;
        return this;
    }

    /**
     * Tells a prior how long the value it draws is, where the declaration says.
     *
     * <p>An {@code IID} over the branches of a species tree has to be told how many branches there
     * are, and asking the user would be asking them to repeat what the component already declares.
     * The argument to fill is the one the drawn type takes its {@code num} from, which by
     * convention is called {@code num} as well.
     */
    public static Component sized(Component prior, Param drawn) {
        Param count = prior.param("num");
        if (count == null) return prior;

        if (drawn.dimensionExpression() != null) {
            count.valueProperty().set(drawn.dimensionExpression());
        } else if (drawn.dimension() != null) {
            count.valueProperty().set(String.valueOf(drawn.dimension()));
        }
        return prior;
    }

    /**
     * Whether the engines allow this argument to be drawn from a distribution.
     *
     * <p>Only a declared no is taken as a no. Where no specification is loaded, or none of them
     * implements the component, there is no opinion to defer to and the UI decides for itself.
     */
    private boolean canBeStochastic(Generator generator, Argument argument) {
        Boolean declared = support.canBeStochastic(generator, argument.getName());
        return declared == null || declared;
    }

    /**
     * Keeps a default prior attached to anything that needs one, so a script is complete even if the
     * user never opens the Priors tab.
     */
    private void attachPriors(Param param) {
        if (library == null) return;
        param.estimateProperty().addListener((observable, was, now) -> {
            if (now) ensurePrior(param);
        });
        if (param.isEstimated() || param.isComponentValued()) ensurePrior(param);
    }

    private void ensurePrior(Param param) {
        if (param.priorProperty().get() != null) return;
        if (param.isDistributionValued() || param.isEstimated()) {
            Generator choice = library.defaultPriorFor(param.priorSupport());
            if (choice != null) {
                param.priorProperty().set(
                        sized(prior(choice, param.dimension(), library, param.priorSupport()), param));
            }
        } else if (param.isComponentValued()) {
            List<Generator> choices = library.producing(param.type());
            if (!choices.isEmpty()) {
                param.priorProperty().set(
                        nested(choices.get(0), library, argsEstimable, support, param.type()));
            }
        }
    }

    private boolean needsNesting(Param param) {
        // An alignment argument names a partition that is already loaded, never a call. The library
        // can produce one, since a loader does, but offering fromNexus here would ask the user to
        // load the same file twice.
        if ("Alignment".equals(Library.head(param.type()))) return false;

        return library != null
                && !Library.hasLiteralSyntax(param.type())
                && !library.producing(param.type()).isEmpty();
    }

    public ObjectProperty<Generator> generatorProperty() {
        return generator;
    }

    public Generator generator() {
        return generator.get();
    }

    public String name() {
        Generator chosen = generator.get();
        return chosen == null ? null : chosen.getName();
    }

    public ObservableList<Param> params() {
        return params;
    }

    public Param param(String name) {
        return params.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * Combo-box label. Overloaded generators are disambiguated by their arguments, which is how
     * the two BirthDeath parameterisations are told apart.
     */
    public static String describe(Generator generator, List<Generator> among) {
        String name = generator.getName();
        boolean overloaded = among.stream().filter(g -> g.getName().equals(name)).count() > 1;
        if (!overloaded) return name;
        String args = generator.getArguments().stream()
                .filter(a -> !WIRED.contains(a.getName()))
                .map(Argument::getName)
                .collect(Collectors.joining(", "));
        return name + " (" + args + ")";
    }
}
