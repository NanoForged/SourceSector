package io.github.nanoforged.sourcesector.command;

import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code sourcesector layermapping}: merge two mappings sharing the same namespaces;
 * the high layer (overlay) overrides corresponding entries of the low layer (base).
 * Classes are matched by source name, members by (source class name, kind, source member name, descriptor)
 * using mapping-io {@code addClass} merging semantics. Overlay-only entries are added to the output.
 * <p>
 * Typical use: overlay a readable‑name layer (intermediary → named) to override migrated/manually
 * named entries, keeping readable names authoritative. Both files must share the same namespace
 * layout (src and dst namespaces identical).
 */
@Command(name = "layermapping",
        mixinStandardHelpOptions = true,
        description = "Merge two mappings sharing the same namespaces: high layer overrides corresponding entries in low layer (class by src name, members by owner+kind+src+desc)")
public final class LayerMappingCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-b", "--base"}, paramLabel = "<file>",
            description = "Base mapping file (Tiny v2)")
    private Path base;

    @Option(names = "--overlay", paramLabel = "<file>",
            description = "Overlay mapping file (Tiny v2, same namespace as base; overrides corresponding entries in base)")
    private Path overlay;

    @Option(names = {"-o", "--output"}, paramLabel = "<file>",
            description = "Output path for merged mapping (Tiny v2, required)")
    private Path output;

    @Override
    public Integer call() throws IOException {
        if (base == null || overlay == null || output == null) {
            throw new ParameterException(spec.commandLine(),
                    "Missing required options: -b/--base, --overlay, -o/--output");
        }
        MemoryMappingTree low = read(base);
        MemoryMappingTree high = read(overlay);
        if (!low.getSrcNamespace().equals(high.getSrcNamespace())
                || !low.getDstNamespaces().equals(high.getDstNamespaces())) {
            throw new ParameterException(spec.commandLine(),
                    "Namespace layout of the two mappings is inconsistent: " + describe(low) + " vs " + describe(high));
        }

        MappingTreeUtil.mergeInto(low, high);
        MappingTreeUtil.write(output, low);

        long classes = low.getClasses().size();
        long members = low.getClasses().stream()
                .flatMap(c -> c.getFields().stream()).count()
                + low.getClasses().stream()
                .flatMap(c -> c.getMethods().stream()).count();
        spec.commandLine().getOut().printf(
                "Merge complete: %d classes, %d members (namespace %s→%s, %s overrides %s)%n  %s%n",
                classes, members, low.getSrcNamespace(), low.getDstNamespaces(),
                overlay.getFileName(), base.getFileName(), output);
        return CommandLine.ExitCode.OK;
    }

    private MemoryMappingTree read(Path file) {
        try {
            return MappingTreeUtil.read(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read mapping: " + file, e);
        }
    }

    private static String describe(MemoryMappingTree tree) {
        return tree.getSrcNamespace() + "→" + String.join("+", tree.getDstNamespaces());
    }
}