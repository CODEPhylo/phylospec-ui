package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.Validator;

/**
 * What the reference implementation tells the UI, on the two channels it uses.
 *
 * <p>The resolver throws what it refuses and reports what it merely doubts, and the doubts are
 * where a component's {@code constraints} land. Seven core components carry them already —
 * {@code PhyloCTMC} relates {@code tree.numBranches} to {@code branchRates.num} — so a UI that does
 * not listen on that channel tells the user a script is valid while the resolver is saying it
 * probably is not.
 */
public class ValidatorTest {

    /**
     * A library declaring a fixed length both ways, and a constraint relating two arguments.
     *
     * <p>It is written here rather than kept as a fixture because what it declares is the whole
     * point of the test, and reading it in another file makes the assertions below look arbitrary.
     */
    private static final String LIBRARY = """
            {
              "componentLibrary": {
                "name": "validator-test",
                "version": "1.0.0",
                "engine": "test",
                "engineVersion": "1.0.0",
                "description": "Declares lengths, so that the checking of them can be tested.",
                "authors": ["phylospec-ui tests"],
                "license": "MIT",
                "types": [],
                "generators": [
                  {
                    "name": "byProperty",
                    "namespace": "test.functions",
                    "description": "Declares its length with the type property.",
                    "generatedType": "QMatrix",
                    "arguments": [
                      { "name": "freqs", "type": "Simplex<;num=6>", "required": true,
                        "description": "Six frequencies." }
                    ]
                  },
                  {
                    "name": "byDimension",
                    "namespace": "test.functions",
                    "description": "Declares its length with the dimension field.",
                    "generatedType": "QMatrix",
                    "arguments": [
                      { "name": "freqs", "type": "Simplex", "required": true, "dimension": 6,
                        "description": "Six frequencies." }
                    ]
                  },
                  {
                    "name": "byConstraint",
                    "namespace": "test.functions",
                    "description": "Requires its two vectors to be the same length.",
                    "generatedType": "QMatrix",
                    "arguments": [
                      { "name": "a", "type": "Simplex", "required": true, "description": "First." },
                      { "name": "b", "type": "Simplex", "required": true, "description": "Second." }
                    ],
                    "constraints": ["a.num == b.num"]
                  }
                ]
              }
            }
            """;

    private static final String FOUR = "[0.25, 0.25, 0.25, 0.25]";
    private static final String SIX = "[0.16, 0.16, 0.17, 0.17, 0.17, 0.17]";

    @Test
    void aContradictedConstraintIsReportedAsADoubtRatherThanARefusal(@TempDir Path directory)
            throws IOException {
        Library library = library(directory);

        Validator.Report mismatched = Validator.check(library, call("byConstraint",
                "a=" + FOUR + ", b=" + SIX));

        assertEquals(List.of(), mismatched.problems(), "the resolver does not refuse the script");
        assertEquals(1, mismatched.warnings().size(), mismatched.warnings().toString());
        assertTrue(mismatched.warnings().get(0).contains("byConstraint"),
                "the warning names the call: " + mismatched.warnings().get(0));

        Validator.Report matched = Validator.check(library, call("byConstraint",
                "a=" + FOUR + ", b=" + FOUR));
        assertEquals(List.of(), matched.all(), "matching lengths are beyond reproach");
    }

    /**
     * The gap behind CODEPhylo/phylospec#74, still open on {@code 21cba006}: a length declared on an
     * argument is advisory, in both spellings. This asserts the current behaviour rather than the
     * wanted one, so that it fails — loudly, and in the right place — when the resolver starts
     * enforcing declared lengths, which is the point at which the UI can stop sizing vectors itself.
     */
    @Test
    void aDeclaredLengthIsNotYetEnforcedInEitherSpelling(@TempDir Path directory) throws IOException {
        Library library = library(directory);

        for (String generator : List.of("byProperty", "byDimension")) {
            assertEquals(List.of(), Validator.check(library, call(generator, "freqs=" + FOUR)).all(),
                    generator + " declares six frequencies and takes four without complaint");
            assertEquals(List.of(), Validator.check(library, call(generator, "freqs=" + SIX)).all(),
                    generator + " accepts the length it asks for");
        }
    }

    private static Library library(Path directory) throws IOException {
        Path path = directory.resolve("validator-test-library.json");
        Files.writeString(path, LIBRARY);
        return Library.load(List.of(path));
    }

    private static String call(String generator, String arguments) {
        return "model {\n  QMatrix q = " + generator + "(" + arguments + ")\n}\n";
    }
}
