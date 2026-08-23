package org.phylospec.ui.spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.phylospec.components.Argument;
import org.phylospec.components.Argument__1;
import org.phylospec.components.EngineSpecificationSchema;
import org.phylospec.components.Generator;
import org.phylospec.components.Generator__1;

/**
 * What the loaded engines can actually run.
 *
 * <p>A component library says what a component <em>is</em>; an engine specification says which of
 * them an engine <em>implements</em>. They are separate documents with separate authors, and an
 * engine implements only a subset of core: the generated BEAST 2 specification covers 51 of core's
 * 92 components, leaving out {@code PhyloBM}, {@code SkylineCoalescent}, {@code mk} and
 * {@code fromFasta} among others. Offering those as choices produces a script that type-checks and
 * then cannot be run.
 *
 * <p>With no specification loaded nothing is claimed and everything is offered, which is the state
 * of the UI before this class existed.
 */
public final class EngineSupport {

    /** One engine's claim to implement a generator. */
    private record Entry(EngineSpecificationSchema specification, Generator__1 generator) {}

    /**
     * Whether a specification entry covers a library generator.
     *
     * <p>Not equality of argument lists, because the two documents spell three things differently.
     * Order: core declares {@code PhyloCTMC} as {@code tree, qMatrix, siteRates, branchRates} and
     * the generator emits {@code tree, qMatrix, branchRates, siteRates}. A call names its
     * arguments, so the order carries no meaning and matching on it would invent a difference.
     * Optionality: core says an optional argument by declaring a second overload without it, while
     * a specification says it with {@code required: false}, so one {@code Yule} entry with an
     * optional {@code rootAge} covers both of core's {@code Yule} signatures. And absence: an
     * argument the library declares but the engine never heard of means this is not the component
     * the engine implements, whatever the name says.
     *
     * <p>So: everything the library asks for must be something the engine offers, and everything
     * the engine insists on must be something the library declares.
     */
    private static boolean covers(Generator__1 entry, Generator library) {
        if (!entry.getName().equals(library.getName())) return false;

        Set<String> offered = new LinkedHashSet<>();
        Set<String> insisted = new LinkedHashSet<>();
        for (Argument__1 argument : entry.getArguments()) {
            offered.add(argument.getName());
            if (Boolean.TRUE.equals(argument.getRequired())) insisted.add(argument.getName());
        }

        Set<String> declared = library.getArguments().stream()
                .map(Argument::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return offered.containsAll(declared) && declared.containsAll(insisted);
    }

    /** Whether a component can be run, and by what, or why not. */
    public record Verdict(boolean supported, String engine, String reason) {

        /** Nothing has been claimed, so nothing is ruled out. */
        static Verdict unclaimed() {
            return new Verdict(true, null, null);
        }
    }

    private final List<EngineSpecificationSchema> specifications;

    /** Every generator any loaded engine implements, indexed by name. */
    private final Map<String, List<Entry>> implemented = new LinkedHashMap<>();

    private EngineSupport(List<EngineSpecificationSchema> specifications) {
        this.specifications = specifications;
        for (EngineSpecificationSchema specification : specifications) {
            for (Generator__1 generator : specification.getGenerators()) {
                implemented.computeIfAbsent(generator.getName(), name -> new ArrayList<>())
                        .add(new Entry(specification, generator));
            }
        }
    }

    /** Nothing loaded: every component is offered, as it was before specifications existed. */
    public static EngineSupport unclaimed() {
        return new EngineSupport(List.of());
    }

    /** The engines described by the given specification files, taken together. */
    public static EngineSupport load(List<Path> specifications) {
        ObjectMapper mapper = new ObjectMapper();
        List<EngineSpecificationSchema> read = new ArrayList<>();
        for (Path path : specifications) {
            try (InputStream stream = Files.newInputStream(path)) {
                read.add(mapper.readValue(stream, EngineSpecificationSchema.class));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the engine specification " + path, e);
            }
        }
        return new EngineSupport(read);
    }

    /** Whether any specification was loaded at all. */
    public boolean claimsAnything() {
        return !specifications.isEmpty();
    }

    /**
     * Whether some loaded engine implements this component.
     *
     * <p>Several engines can be loaded and a model may need more than one of them, since a BEAST 2
     * package is an engine in its own right. So a component is supported if <em>any</em> of them
     * implements it.
     */
    public Verdict verdictFor(Generator generator) {
        if (specifications.isEmpty()) return Verdict.unclaimed();

        for (Entry entry : implemented.getOrDefault(generator.getName(), List.of())) {
            if (covers(entry.generator(), generator)) {
                return new Verdict(true, describe(entry.specification()), null);
            }
        }
        return new Verdict(false, null, generator.getName() + " is not implemented by "
                + specifications.stream().map(EngineSupport::describe).collect(Collectors.joining(" or ")) + ".");
    }

    /** Shorthand for {@code verdictFor(generator).supported()}. */
    public boolean supports(Generator generator) {
        return verdictFor(generator).supported();
    }

    /**
     * Whether an argument may be drawn from a distribution rather than given a value.
     *
     * <p>Null where no loaded engine has an opinion, which is both the no-specification case and an
     * argument of a component nothing implements. The UI decides for itself in that case.
     *
     * <p>Where more than one entry covers the component the answers can disagree: the BEAST 2
     * specification lists {@code exp(x)} twice, once with {@code canBeStochastic} true and once
     * false, because two core overloads collapse onto the one shape. A yes from any of them is
     * taken as a yes, since one of the two is the overload in hand and refusing both would forbid
     * something an engine can do.
     */
    public Boolean canBeStochastic(Generator generator, String argument) {
        Boolean answer = null;
        for (Entry entry : implemented.getOrDefault(generator.getName(), List.of())) {
            if (!covers(entry.generator(), generator)) continue;
            for (Argument__1 declared : entry.generator().getArguments()) {
                if (!declared.getName().equals(argument)) continue;
                if (Boolean.TRUE.equals(declared.getCanBeStochastic())) return true;
                answer = false;
            }
        }
        return answer;
    }

    /**
     * How to obtain the engines, for a UI that has to explain an unavailable component.
     *
     * <p>Empty where nothing was loaded. The specification carries these fields precisely so that a
     * consumer can say what is missing and where to get it, rather than silently hiding a component
     * and leaving the user to wonder why the model they read about is not there.
     */
    public List<String> installationAdvice() {
        return specifications.stream()
                .map(specification -> describe(specification) + ": "
                        + specification.getInstallationInstructions() + " " + specification.getInstallationWebsite())
                .toList();
    }

    /** The engines loaded, named and versioned, in the order given. */
    public List<String> engines() {
        return specifications.stream().map(EngineSupport::describe).toList();
    }

    private static String describe(EngineSpecificationSchema specification) {
        return specification.getName() + " " + specification.getEngineVersion();
    }
}
