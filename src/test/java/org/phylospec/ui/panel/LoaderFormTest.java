package org.phylospec.ui.panel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptWriter;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
class LoaderFormTest {
    @TempDir Path directory;

    @Test
    void showsUnsupportedInputAndKeepsSupportedFiles() throws Exception {
        Path nexus = directory.resolve("dna.nex");
        Path fasta = directory.resolve("dna.fasta");
        Files.writeString(nexus, "#NEXUS\nbegin data; dimensions ntax=2 nchar=4; end;\n");
        Files.writeString(fasta, ">a\nACGT\n>b\nACGA\n");
        Analysis analysis = new Analysis(Library.load(),
                EngineSupport.load(List.of(Path.of("engines", "beast2-2.8.0-beta4.json"))));

        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        FutureTask<Void> exercise = new FutureTask<>(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene((Parent) PartitionsPanel.build(analysis), 900, 400));
            stage.show();
            try {
                FutureTask<String> dismiss = new FutureTask<>(() -> {
                    Window window = Window.getWindows().stream()
                            .filter(candidate -> candidate != stage && candidate.isShowing())
                            .findFirst().orElseThrow();
                    DialogPane dialog = (DialogPane) window.getScene().getRoot();
                    try {
                        assertEquals("Unsupported data input", dialog.getHeaderText());
                        return dialog.getContentText();
                    } finally {
                        ((Button) dialog.lookupButton(ButtonType.OK)).fire();
                    }
                });
                Platform.runLater(dismiss);
                assertFalse(PartitionsPanel.addFiles(stage, analysis, List.of(nexus.toFile(), fasta.toFile())));
                String message = dismiss.get(10, TimeUnit.SECONDS);
                assertTrue(message.contains("fromFasta(file)"), message);
                assertTrue(message.contains("beast2 2.8.0-beta4"), message);
                assertTrue(message.contains("other supported files have been kept"), message);
                assertEquals(1, analysis.partitions().size());
                assertEquals(nexus.toString(), analysis.partitions().getFirst().fileProperty().get());
                assertTrue(ScriptWriter.write(analysis).contains("fromNexus("));
                assertTrue(PartitionsPanel.addFiles(stage, analysis, List.of(nexus.toFile())));
                assertEquals(1, analysis.partitions().size(), "A repeated file must not duplicate the partition.");
            } finally {
                stage.close();
            }
            return null;
        });
        try {
            Platform.runLater(exercise);
            exercise.get(30, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
    }
}
