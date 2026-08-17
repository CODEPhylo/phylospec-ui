package org.phylospec.ui.model;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.phylospec.components.Argument;
import org.phylospec.components.Generator;
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

    private Component(boolean argsEstimable, Library library) {
        this.argsEstimable = argsEstimable;
        this.library = library;
        generator.addListener((observable, old, chosen) -> rebuild(chosen));
    }

    /** A component whose arguments each get an "estimate" checkbox. */
    public static Component estimable(Library library) {
        return new Component(true, library);
    }

    /** A prior: its hyperparameters are always fixed. */
    public static Component prior(Generator generator) {
        Component component = new Component(false, null);
        component.generator.set(generator);
        return component;
    }

    /** A nested function, whose own arguments stay editable — and estimable — like its parent's. */
    public static Component nested(Generator generator, Library library, boolean argsEstimable) {
        Component component = new Component(argsEstimable, library);
        component.generator.set(generator);
        return component;
    }

    private void rebuild(Generator chosen) {
        params.clear();
        if (chosen == null) return;
        for (Argument argument : chosen.getArguments()) {
            if (WIRED.contains(argument.getName())) continue;
            Param param = new Param(argument, argsEstimable);
            if (needsNesting(param)) param.markNested();
            attachPriors(param);
            params.add(param);
        }
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
            if (choice != null) param.priorProperty().set(prior(choice));
        } else if (param.isComponentValued()) {
            List<Generator> choices = library.producing(param.type());
            if (!choices.isEmpty()) {
                param.priorProperty().set(nested(choices.get(0), library, argsEstimable));
            }
        }
    }

    private boolean needsNesting(Param param) {
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
