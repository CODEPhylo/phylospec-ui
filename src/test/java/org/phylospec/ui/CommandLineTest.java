package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class CommandLineTest {

    @Test
    void acceptsRepeatedAdapterPathsWithoutTreatingThemAsAlignments() {
        PhyloSpecUI.CommandLine commandLine = PhyloSpecUI.CommandLine.parse(List.of(
                "--adapter-path", "popfunc.jar" + File.pathSeparator + "beast3.jar",
                "--adapter-path=package-dependency.jar",
                "Primates.nex"));

        assertEquals(List.of(
                        Path.of("popfunc.jar"),
                        Path.of("beast3.jar"),
                        Path.of("package-dependency.jar")),
                commandLine.adapterPaths());
        assertEquals(List.of("Primates.nex"), commandLine.alignments());
    }
}
