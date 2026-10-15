package org.phylospec.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.phylospec.ui.model.Partition;

import static org.junit.jupiter.api.Assertions.*;

class PartitionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"nex", "nexus", "nxs"})
    void readCountDimensionsComeFromMatrixRatherThanStaleHeader(String extension) throws Exception {
        Path file = directory.resolve("counts." + extension);
        Files.writeString(file, """
                #NEXUS
                [The matrix is not declared by this comment;]
                begin data;
                  dimensions ntax=99 nchar=99;
                  format datatype=READCOUNT;
                  matrix cell_B 1:2:3:4,4:3:2:1
                    cell_A 0:1:2:3,3:2:1:0
                  ;
                end;
                """);
        Partition partition = new Partition(file);
        assertTrue(partition.isReadCount());
        assertEquals("fromReadCountNexus", partition.loader());
        assertEquals(2, partition.taxaProperty().get());
        assertEquals(2, partition.sitesProperty().get());
    }

    @Test
    void ordinaryNexusRetainsItsHeaderBasedSummary() throws Exception {
        Path file = directory.resolve("alignment.nex");
        Files.writeString(file, """
                #NEXUS
                [This is not datatype=readCount.]
                begin data;
                  dimensions ntax=2 nchar=4;
                  format datatype=dna;
                  matrix
                    a ACGT
                    b AGGT;
                end;
                """);
        Partition partition = new Partition(file);
        assertFalse(partition.isReadCount());
        assertEquals("fromNexus", partition.loader());
        assertEquals(2, partition.taxaProperty().get());
        assertEquals(4, partition.sitesProperty().get());
    }
}
