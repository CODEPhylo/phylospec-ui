package org.phylospec.ui.spec;

import java.util.List;

import org.phylospec.tiling.TileLibrary;
import org.phylospec.tiling.tiles.CandidateTile;

public final class TestTileLibrary extends TileLibrary<Object> {

    @Override
    public String getId() {
        return "test-package";
    }

    @Override
    public Class<Object> getStateType() {
        return Object.class;
    }

    @Override
    public List<CandidateTile<Object>> getTiles() {
        return List.of();
    }

    @Override
    public List<String> getComponentLibraryResources() {
        return List.of("/test-package-components.json");
    }

    @Override
    public List<String> getEngineSpecificationResources() {
        return List.of("/test-package-engine.json");
    }
}
