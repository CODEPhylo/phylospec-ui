package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.components.Generator;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

/** Verifies the GUI model against a real packaged Phylonco adapter. */
@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
class PhyloncoPackageTest {

    @Test
    void discoversConfiguresAndWritesSiFit3(@TempDir Path directory) throws Exception {
        Path adapter = Path.of(System.getProperty("phylospec.phyloncoAdapter"));
        PackageMetadata metadata = PackageMetadata.discover(List.of(adapter));
        Library library = metadata.library();

        Generator siFit3 = library.overloads("siFit3").getFirst();
        assertTrue(metadata.support().supports(siFit3));
        assertTrue(metadata.support().engines().stream()
                .anyMatch(engine -> engine.startsWith("phylonco ")));

        Path alignment = directory.resolve("ternary.nex");
        Files.writeString(alignment, """
                #NEXUS
                begin data;
                    dimensions ntax=4 nchar=20;
                    format datatype=ternary missing=? gap=-;
                    matrix
                        cell_1  01020112021001220120
                        cell_2  01020112021011220120
                        cell_3  22101200102122001201
                        cell_4  22101200102122011201
                    ;
                end;
                """);

        Analysis analysis = new Analysis(library, metadata.support());
        analysis.addPartition(alignment);
        assertTrue(analysis.choicesFor(Library.SUBSTITUTION_MODEL).contains(siFit3));

        analysis.substitutionModel().generatorProperty().set(siFit3);
        analysis.substitutionModel().param("lambdaD").valueProperty().set("0.5");
        analysis.substitutionModel().param("lambdaL").valueProperty().set("0.5");
        analysis.substitutionModel().param("mutationPath").valueProperty().set("true");
        analysis.chainLengthProperty().set("50");
        analysis.logEveryProperty().set("1");
        analysis.logFileProperty().set("phylonco-sifit3.log");
        analysis.seedProperty().set("24680");

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("use phylonco.functions.substitution"), script);
        assertTrue(script.contains("PositiveReal lambdaD ~"), script);
        assertTrue(script.contains("PositiveReal lambdaL ~"), script);
        assertTrue(script.contains("QMatrix qMatrix = siFit3("), script);
        assertTrue(script.contains("lambdaD=lambdaD"), script);
        assertTrue(script.contains("lambdaL=lambdaL"), script);
        assertTrue(script.contains("mutationPath=true"), script);
        assertTrue(script.contains("Integer chainLength = 50"), script);
        assertEquals(List.of(), Validator.check(library, script).all(), script);

        Analysis reloaded = ScriptReader.read(library, script);
        assertEquals("siFit3", reloaded.substitutionModel().name());
        assertEquals("true", reloaded.substitutionModel().param("mutationPath").valueProperty().get());
    }
}
