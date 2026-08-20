package org.phylospec.ui.spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.phylospec.components.ComponentLibrary;
import org.phylospec.components.ComponentResolver;
import org.phylospec.components.Generator;
import org.phylospec.components.Type;
import org.phylospec.typeresolver.TypeResolver;

/**
 * Read-only view over the PhyloSpec component libraries.
 *
 * <p>Everything the UI knows about distributions, functions and their arguments comes from here,
 * so adding a component to the library makes it available in the GUI without any UI code. That
 * holds for engine libraries too: a library loaded alongside core reaches the tabs through
 * {@link #withRole}, without being named anywhere in the UI.
 */
public final class Library {

    /**
     * The slots a BEAUti-shaped UI has to fill. Which one a component belongs in is read off what it
     * produces — {@code QMatrix} and {@code Distribution<Tree>} say it on their own — so a library
     * declares nothing. See {@link #roleOf} for the one thing a type cannot say.
     */
    public static final String SUBSTITUTION_MODEL = "substitutionModel";
    public static final String SITE_RATES = "siteRates";
    public static final String CLOCK_MODEL = "clockModel";
    public static final String TREE_PRIOR = "treePrior";
    public static final String TREE_LIKELIHOOD = "treeLikelihood";
    public static final String POPULATION_FUNCTION = "populationFunction";

    /** Guards against cycles in malformed {@code extends}/{@code alias} chains. */
    private static final int MAX_TYPE_HOPS = 32;

    private final Map<String, List<Generator>> generators = new LinkedHashMap<>();
    private final Map<String, Type> types = new LinkedHashMap<>();
    private final Map<String, List<Generator>> byRole = new LinkedHashMap<>();

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
        importEngineNamespaces(forValidation);

        for (ComponentLibrary library : forUi) {
            for (Type type : library.getTypes()) {
                types.put(type.getName(), type);
            }
            for (Generator generator : library.getGenerators()) {
                generators.computeIfAbsent(generator.getName(), k -> new ArrayList<>()).add(generator);
            }
        }

        // Inferring a role walks the type table, so roles are indexed only once every library is in.
        for (List<Generator> overloads : generators.values()) {
            for (Generator generator : overloads) {
                String role = roleOf(generator);
                if (role != null) byRole.computeIfAbsent(role, k -> new ArrayList<>()).add(generator);
            }
        }
    }

    /** The core components on their own. */
    public static Library load() {
        return load(List.of());
    }

    /**
     * The core components, plus an engine library for each path given. Engine libraries are
     * registered after core so that they can refer to core types.
     */
    public static Library load(List<Path> engineLibraries) {
        try {
            return new Library(read(engineLibraries), read(engineLibraries));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the PhyloSpec component libraries", e);
        }
    }

    private static List<ComponentLibrary> read(List<Path> engineLibraries) throws IOException {
        List<ComponentLibrary> libraries =
                new ArrayList<>(ComponentResolver.loadCoreComponentLibraries());
        for (Path path : engineLibraries) {
            try (InputStream stream = Files.newInputStream(path)) {
                libraries.add(ComponentResolver.loadLibraryFromInputStream(stream));
            }
        }
        return libraries;
    }

    /**
     * Makes an engine library's components resolvable by their plain name during validation. The
     * resolver imports the {@code phylospec} namespace itself; any other has to be asked for, or a
     * script calling one of its components fails to resolve.
     */
    private void importEngineNamespaces(List<ComponentLibrary> libraries) {
        Set<String> roots = new LinkedHashSet<>();
        for (ComponentLibrary library : libraries) {
            for (Generator generator : library.getGenerators()) roots.add(root(generator.getNamespace()));
            for (Type type : library.getTypes()) roots.add(root(type.getNamespace()));
        }
        roots.remove(null);
        roots.remove("phylospec");
        for (String namespace : roots) {
            validationResolver.importEntireNamespace(List.of(namespace));
        }
    }

    private static String root(String namespace) {
        if (namespace == null || namespace.isBlank()) return null;
        int dot = namespace.indexOf('.');
        return dot < 0 ? namespace : namespace.substring(0, dot);
    }

    /**
     * A type resolver with an empty scope. Variable declarations accumulate in the resolver as it
     * visits statements, so each validation run needs a new one — but the component resolver
     * underneath is shared.
     */
    public TypeResolver newTypeResolver() {
        return new TypeResolver(validationResolver);
    }

    // ---------------------------------------------------------------- roles

    /**
     * Which slot of the UI a generator belongs in, or null if it fills none.
     *
     * <p>Normally this is what the generator produces, restated: only a substitution model returns
     * a {@code QMatrix}, only a tree prior is a distribution over a {@code Tree}. Declaring a role
     * for those could only ever agree with the type or contradict it.
     *
     * <p>What a type cannot say is that a component fills a slot that does not exist yet — a
     * StarBEAST gene tree is a {@code Distribution<Tree>} and still does not belong on the Tree
     * Prior tab. A {@code role} field says so, and wins over what the type implies.
     */
    public String roleOf(Generator generator) {
        Object declared = generator.getAdditionalProperties().get("role");
        if (declared instanceof String role && !role.isBlank()) return role;
        return inferRole(generator);
    }

    /**
     * The slot a generator's own type puts it in. Every component in core and in the sample BEAST
     * library is placed by this alone; the one pair it has to work for is the clock and the site
     * rates, which generate the same type.
     */
    private String inferRole(Generator generator) {
        String generated = generator.getGeneratedType();
        String produced = head(generated);
        if ("QMatrix".equals(produced)) return SUBSTITUTION_MODEL;
        if ("PopulationFunction".equals(produced)) return POPULATION_FUNCTION;
        if (!"Distribution".equals(produced)) return null;

        String support = inner(generated);
        String kind = head(support);
        if ("Tree".equals(kind)) return TREE_PRIOR;
        if ("Alignment".equals(kind)) return TREE_LIKELIHOOD;
        if (!"Vector".equals(kind) || !isSubtype(element(support), "Rate")) return null;

        // A rate per branch is a clock, a rate per site is rate heterogeneity. Their generated types
        // differ only in a type property, so it is the tree argument that tells the two apart.
        return takesAnArgumentNamed(generator, "tree") ? CLOCK_MODEL : SITE_RATES;
    }

    /**
     * Every generator filling a slot, with the preferred ones first and in the order given.
     *
     * <p>The preferred list is presentation only. A component missing from it still appears, which
     * is how an engine library reaches the tabs without being named in the UI.
     */
    public List<Generator> withRole(String role, List<String> preferred) {
        List<Generator> found = new ArrayList<>(byRole.getOrDefault(role, List.of()));
        found.sort(Comparator.comparingInt(generator -> {
            int rank = preferred.indexOf(generator.getName());
            return rank < 0 ? preferred.size() : rank;
        }));
        return found;
    }

    private static boolean takesAnArgumentNamed(Generator generator, String argument) {
        return generator.getArguments().stream().anyMatch(a -> argument.equals(a.getName()));
    }

    // ----------------------------------------------------------- generators

    /** The distribution BEAUti would reach for, given the type of the thing being estimated. */
    public Generator defaultPriorFor(String support) {
        List<Generator> candidates = priorsFor(support);
        String preferred = switch (head(support)) {
            case "PositiveReal", "Rate" -> "LogNormal";
            case "Probability" -> "Beta";
            case "Simplex" -> "Dirichlet";
            case "NonNegativeReal", "Age" -> "Exponential";
            case "Real" -> "Normal";
            case "Integer", "NonNegativeInteger", "PositiveInteger", "Count" -> "DiscreteUniform";
            case "Boolean" -> "Bernoulli";
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

    // ---------------------------------------------------------------- types

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

    /**
     * The value of a type property, as in the {@code num} of
     * {@code "Simplex<;num=6>"} or {@code "Vector<Rate; num=tree.numBranches>"}. Null if the type
     * carries no such property.
     */
    public static String property(String type, String name) {
        String inner = inner(type);
        if (inner == null) return null;
        int semicolon = inner.indexOf(';');
        if (semicolon < 0) return null;
        for (String property : inner.substring(semicolon + 1).split(",")) {
            String[] halves = property.split("=", 2);
            if (halves.length == 2 && halves[0].trim().equals(name)) return halves[1].trim();
        }
        return null;
    }

    /**
     * The element type of a generic type, with the type properties dropped:
     * {@code "Vector<Rate; num=tree.numBranches>"} to {@code "Rate"}.
     */
    public static String element(String type) {
        String inner = inner(type);
        if (inner == null) return null;
        String first = inner.split(";", 2)[0].trim();
        return first.isEmpty() ? null : head(first);
    }
}
