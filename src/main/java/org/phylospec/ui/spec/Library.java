package org.phylospec.ui.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.phylospec.components.ComponentLibrary;
import org.phylospec.components.ComponentResolver;
import org.phylospec.components.Generator;
import org.phylospec.components.Type;
import org.phylospec.typeresolver.TypeResolver;

/**
 * Read-only view over the PhyloSpec component libraries.
 *
 * <p>Everything the UI knows about distributions, functions and their arguments comes from here,
 * so adding a component to the library makes it available in the GUI without any UI code.
 */
public final class Library {

    /** Guards against cycles in malformed {@code extends}/{@code alias} chains. */
    private static final int MAX_TYPE_HOPS = 32;

    private final Map<String, List<Generator>> generators = new LinkedHashMap<>();
    private final Map<String, Type> types = new LinkedHashMap<>();

    /**
     * A resolver over a second, independently loaded copy of the libraries.
     *
     * <p>Building a resolver qualifies the library's type names in place, and a library can only be
     * registered once. So the resolver is built exactly once, over a copy the UI never reads —
     * otherwise those qualified names would leak into the generated script.
     */
    private final ComponentResolver validationResolver;

    private Library(List<ComponentLibrary> forUi, List<ComponentLibrary> forValidation) {
        this.validationResolver = new ComponentResolver(forValidation);
        for (ComponentLibrary library : forUi) {
            for (Type type : library.getTypes()) {
                types.put(type.getName(), type);
            }
            for (Generator generator : library.getGenerators()) {
                generators.computeIfAbsent(generator.getName(), k -> new ArrayList<>()).add(generator);
            }
        }
    }

    public static Library load() {
        try {
            return new Library(
                    ComponentResolver.loadCoreComponentLibraries(),
                    ComponentResolver.loadCoreComponentLibraries());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the PhyloSpec core component libraries", e);
        }
    }

    /**
     * A type resolver with an empty scope. Variable declarations accumulate in the resolver as it
     * visits statements, so each validation run needs a new one — but the component resolver
     * underneath is shared.
     */
    public TypeResolver newTypeResolver() {
        return new TypeResolver(validationResolver);
    }

    /** The distribution BEAUti would reach for, given the type of the thing being estimated. */
    public Generator defaultPriorFor(String support) {
        List<Generator> candidates = priorsFor(support);
        String preferred = switch (head(support)) {
            case "PositiveReal", "Rate" -> "LogNormal";
            case "Probability" -> "Beta";
            case "Simplex" -> "Dirichlet";
            case "NonNegativeReal", "Age" -> "Exponential";
            case "Real" -> "Normal";
            default -> null;
        };
        return candidates.stream()
                .filter(generator -> generator.getName().equals(preferred))
                .findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
    }

    /** All signatures declared under {@code name}; a generator may be overloaded. */
    public List<Generator> overloads(String name) {
        return generators.getOrDefault(name, List.of());
    }

    /** The named generators, in the order given, expanding each into its overloads. */
    public List<Generator> lookup(List<String> names) {
        List<Generator> found = new ArrayList<>();
        for (String name : names) {
            found.addAll(overloads(name));
        }
        return found;
    }

    /**
     * Distributions usable as a prior on a value of {@code type}: those whose support is
     * {@code type} or a subtype of it.
     *
     * <p>Distributions parameterised by another distribution (IID, Mixture, Truncated, Offset)
     * are left out — they would need a nested editor, and BEAUti has no equivalent.
     */
    public List<Generator> priorsFor(String type) {
        List<Generator> found = new ArrayList<>();
        for (List<Generator> overloads : generators.values()) {
            for (Generator generator : overloads) {
                if (!"Distribution".equals(head(generator.getGeneratedType()))) continue;
                if (takesADistribution(generator)) continue;
                String support = inner(generator.getGeneratedType());
                if (support != null && isSubtype(support, type)) found.add(generator);
            }
        }
        return found;
    }

    private static boolean takesADistribution(Generator generator) {
        return generator.getArguments().stream().anyMatch(a -> "Distribution".equals(head(a.getType())));
    }

    /** Functions that build a value of {@code type}, such as the population functions a coalescent takes. */
    public List<Generator> producing(String type) {
        String wanted = head(type);
        List<Generator> found = new ArrayList<>();
        for (List<Generator> overloads : generators.values()) {
            for (Generator generator : overloads) {
                if (wanted.equals(head(generator.getGeneratedType()))) found.add(generator);
            }
        }
        return found;
    }

    /**
     * True if a value of this type can simply be typed in. Numbers, strings and lists can; a
     * population function or a rate matrix has to be built by calling something.
     */
    public static boolean hasLiteralSyntax(String type) {
        return switch (head(type)) {
            case "Real", "NonNegativeReal", "PositiveReal", "Rate", "Probability", "Age",
                    "Integer", "NonNegativeInteger", "PositiveInteger", "Count",
                    "String", "Boolean", "Vector", "Simplex", "Matrix", "SquareMatrix",
                    "StochasticMatrix", "Sequence" -> true;
            default -> false;
        };
    }

    /** True if a value of type {@code sub} is always a legal value of type {@code sup}. */
    public boolean isSubtype(String sub, String sup) {
        String target = canonical(head(sup));
        String current = canonical(head(sub));
        for (int hop = 0; hop < MAX_TYPE_HOPS && current != null; hop++) {
            if (current.equals(target)) return true;
            Type type = types.get(current);
            String parent = type == null ? null : type.getExtends();
            current = parent == null ? null : canonical(head(parent));
        }
        return false;
    }

    /** Follows {@code alias} declarations, so {@code Rate} resolves to {@code PositiveReal}. */
    private String canonical(String name) {
        for (int hop = 0; hop < MAX_TYPE_HOPS; hop++) {
            Type type = types.get(name);
            if (type == null || type.getAlias() == null) return name;
            String alias = head(type.getAlias());
            if (alias.equals(name)) return name;
            name = alias;
        }
        return name;
    }

    /** {@code "Distribution<Tree<;numTaxa=taxa.num>>"} to {@code "Distribution"}. */
    public static String head(String type) {
        if (type == null) return null;
        int open = type.indexOf('<');
        return (open < 0 ? type : type.substring(0, open)).trim();
    }

    /** {@code "Distribution<PositiveReal>"} to {@code "PositiveReal"}, or null if not generic. */
    public static String inner(String type) {
        if (type == null) return null;
        int open = type.indexOf('<');
        int close = type.lastIndexOf('>');
        if (open < 0 || close < open) return null;
        return type.substring(open + 1, close).trim();
    }
}
