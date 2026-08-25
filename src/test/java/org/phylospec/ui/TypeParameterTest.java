package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.components.Generator;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.Validator;

/**
 * Choosing by what a type is <em>of</em>, and by what it says about itself, not only by its head.
 *
 * <p>A chooser offers whatever produces the type an argument wants. Matching on the head alone
 * cannot tell {@code Taxa<Species>} from {@code Taxa<Individual>}, so a species tree's taxon set
 * would list the taxa of a single alignment: the individuals, which is a different model and a
 * valid script. Nothing in the resolver catches that today, because core's {@code Taxa} carries no
 * such parameter.
 *
 * <p>The library here is a sketch of what it would be if it did. It is written in the test rather
 * than kept as a fixture because it is the proposal, not a component anything ships.
 */
public class TypeParameterTest {

    private static final String LIBRARY = """
            {
              "componentLibrary": {
                "name": "parameterised-taxa",
                "version": "1.0.0",
                "engine": "sketch",
                "engineVersion": "1.0.0",
                "description": "Taxa and trees parameterised by what their tips are.",
                "authors": ["phylospec-ui tests"],
                "license": "MIT",
                "types": [
                  { "name": "Species", "namespace": "sketch.types", "description": "A species." },
                  { "name": "Individual", "namespace": "sketch.types", "description": "One sampled individual." },
                  { "name": "STaxa", "namespace": "sketch.types", "typeParameters": ["T"],
                    "description": "A taxon set whose members are of kind T." },
                  { "name": "STree", "namespace": "sketch.types", "typeParameters": ["T"],
                    "description": "A tree whose tips are of kind T." }
                ],
                "generators": [
                  { "name": "taxaOf", "namespace": "sketch.functions",
                    "description": "The individuals an alignment samples.",
                    "generatedType": "STaxa<Individual>",
                    "arguments": [
                      { "name": "alignment", "type": "Alignment", "required": true, "description": "The locus." }
                    ] },
                  { "name": "speciesOf", "namespace": "sketch.functions",
                    "description": "The species those individuals belong to.",
                    "generatedType": "STaxa<Species>",
                    "arguments": [
                      { "name": "taxa", "type": "STaxa<Individual>", "required": true, "description": "The individuals." }
                    ] },
                  { "name": "YuleSpecies", "namespace": "sketch.distributions",
                    "description": "A Yule over species.",
                    "generatedType": "Distribution<STree<Species>>",
                    "arguments": [
                      { "name": "birthRate", "type": "PositiveReal", "required": true, "description": "." },
                      { "name": "taxa", "type": "STaxa<Species>", "required": true, "description": "The species." }
                    ] },
                  { "name": "MSC", "namespace": "sketch.distributions",
                    "description": "Gene trees drawn within a species tree.",
                    "generatedType": "Distribution<STree<Individual>>",
                    "arguments": [
                      { "name": "speciesTree", "type": "STree<Species>", "required": true, "description": "." },
                      { "name": "taxa", "type": "STaxa<Individual>", "required": true, "description": "." }
                    ] }
                ]
              }
            }
            """;

    private static Library sketch(Path directory) throws IOException {
        Path path = directory.resolve("parameterised-taxa.json");
        Files.writeString(path, LIBRARY);
        return Library.load(List.of(path));
    }

    /** The point of the whole thing: the wrong taxon set is not on the menu. */
    @Test
    void aChooserOffersOnlyWhatTheParameterAllows(@TempDir Path directory) throws IOException {
        Library library = sketch(directory);

        assertEquals(List.of("speciesOf"), names(library.producing("STaxa<Species>")),
                "a species tree's taxa cannot be the individuals of an alignment");
        assertEquals(List.of("taxaOf"), names(library.producing("STaxa<Individual>")));
    }

    /** Asking without a parameter is asking for any of them, which is what every core type does. */
    @Test
    void anUnparameterisedRequestOffersEverything(@TempDir Path directory) throws IOException {
        Library library = sketch(directory);

        assertEquals(List.of("taxaOf", "speciesOf"), names(library.producing("STaxa")));
    }

    /** And the resolver refuses it too, so the two agree rather than the menu being the only guard. */
    @Test
    void theResolverRefusesTheWrongOneAsWell(@TempDir Path directory) throws IOException {
        Library library = sketch(directory);
        String data = "data {\n    Alignment gene1 = fromNexus(file=\"examples/Primates.nex\")\n}\n";

        List<String> right = Validator.check(library, data + """
                model {
                    STree<Species> speciesTree ~ YuleSpecies(birthRate=1.0, taxa=speciesOf(taxa=taxaOf(alignment=gene1)))
                }
                """).all();
        assertEquals(List.of(), right, "species taxa are what a species tree wants");

        List<String> wrong = Validator.check(library, data + """
                model {
                    STree<Species> speciesTree ~ YuleSpecies(birthRate=1.0, taxa=taxaOf(alignment=gene1))
                }
                """).all();
        assertTrue(wrong.toString().contains("Wrong argument type"), wrong.toString());
    }

    /** The same library, with the tip kind said as a property instead of as a parameter. */
    private static final String BY_PROPERTY = """
            {
              "componentLibrary": {
                "name": "property-taxa",
                "version": "1.0.0",
                "engine": "sketch",
                "engineVersion": "1.0.0",
                "description": "Taxa and trees saying what their tips are, as a property.",
                "authors": ["phylospec-ui tests"],
                "license": "MIT",
                "types": [
                  { "name": "PTaxa", "namespace": "sketch.types", "typeProperties": ["taxonType"],
                    "description": "A taxon set saying what its members are." },
                  { "name": "PTree", "namespace": "sketch.types", "typeProperties": ["taxonType"],
                    "description": "A tree saying what its tips are." }
                ],
                "generators": [
                  { "name": "taxaOf", "namespace": "sketch.functions",
                    "description": "The individuals an alignment samples.",
                    "generatedType": "PTaxa<;taxonType=individual>",
                    "arguments": [
                      { "name": "alignment", "type": "Alignment", "required": true, "description": "The locus." }
                    ] },
                  { "name": "speciesOf", "namespace": "sketch.functions",
                    "description": "The species those individuals belong to.",
                    "generatedType": "PTaxa<;taxonType=species>",
                    "arguments": [
                      { "name": "taxa", "type": "PTaxa<;taxonType=individual>", "required": true, "description": "." }
                    ] },
                  { "name": "YuleSpecies", "namespace": "sketch.distributions",
                    "description": "A Yule over species.",
                    "generatedType": "Distribution<PTree<;taxonType=species>>",
                    "arguments": [
                      { "name": "birthRate", "type": "PositiveReal", "required": true, "description": "." },
                      { "name": "taxa", "type": "PTaxa<;taxonType=species>", "required": true, "description": "." }
                    ] }
                ]
              }
            }
            """;

    /**
     * The same narrowing, for the other spelling of the same idea.
     *
     * <p>A tip kind could be a type property rather than a parameter,
     * {@code Tree<;taxonType=species>}. The resolver does not check those: it accepts a tree of
     * individuals where a species tree is asked for, exactly as it accepts four elements where six
     * are declared. So the chooser has to narrow, or the wrong component is offered and nothing
     * downstream objects.
     */
    @Test
    void aChooserAlsoNarrowsOnASettledProperty(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("property-taxa.json");
        Files.writeString(path, BY_PROPERTY);
        Library library = Library.load(List.of(path));

        assertEquals(List.of("speciesOf"), names(library.producing("PTaxa<;taxonType=species>")),
                "the individuals of an alignment are not the species");
        assertEquals(List.of("taxaOf"), names(library.producing("PTaxa<;taxonType=individual>")));

        // The resolver, unlike the chooser, does not mind at all. That is the whole reason the
        // chooser has to: with this spelling it is the only thing standing in the way.
        String data = "data {\n    Alignment gene1 = fromNexus(file=\"examples/Primates.nex\")\n}\n";
        assertEquals(List.of(), Validator.check(library, data + """
                model {
                    PTree speciesTree ~ YuleSpecies(birthRate=1.0, taxa=taxaOf(alignment=gene1))
                }
                """).all(),
                "a property on an argument is advisory");
    }

    private static List<String> names(List<Generator> generators) {
        return generators.stream().map(Generator::getName).toList();
    }
}
