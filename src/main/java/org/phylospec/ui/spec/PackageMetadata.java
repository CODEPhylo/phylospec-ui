package org.phylospec.ui.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

import org.phylospec.components.ComponentLibrary;
import org.phylospec.components.EngineSpecificationSchema;
import org.phylospec.tiling.TileLibrary;

/** Component and engine metadata supplied by installed tile-library adapters. */
public final class PackageMetadata {

    private final List<ComponentLibrary> components;
    private final List<EngineSpecificationSchema> engines;

    private PackageMetadata(
            List<ComponentLibrary> components,
            List<EngineSpecificationSchema> engines) {
        this.components = List.copyOf(components);
        this.engines = List.copyOf(engines);
    }

    /** Discovers every tile-library adapter visible on the runtime classpath. */
    public static PackageMetadata discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    /** Discovers adapters visible to a caller-supplied runtime class loader. */
    public static PackageMetadata discover(ClassLoader classLoader) {
        List<TileLibrary<?>> libraries = new ArrayList<>();
        ServiceLoader.load(TileLibrary.class, Objects.requireNonNull(classLoader))
                .forEach(libraries::add);
        return fromLibraries(libraries);
    }

    /** Loads metadata from an already selected set of adapters. */
    public static PackageMetadata fromLibraries(
            List<? extends TileLibrary<?>> libraries) {
        try {
            return new PackageMetadata(
                    TileLibrary.loadComponentLibraries(libraries),
                    TileLibrary.loadEngineSpecifications(libraries));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load installed package metadata", exception);
        }
    }

    public List<ComponentLibrary> components() {
        return components;
    }

    public List<EngineSpecificationSchema> engines() {
        return engines;
    }

    public Library library() {
        return Library.fromComponents(components);
    }

    public EngineSupport support() {
        return EngineSupport.fromSpecifications(engines);
    }
}
