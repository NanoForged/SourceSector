package io.github.nanoforged.sourcesector.command;

import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.format.enigma.EnigmaDirReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitOrder;
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
 * {@code sourcesector enigma}: convert an Enigma mapping directory (a folder of
 * {@code *.mapping} files) into a single Tiny v2 mapping.
 * <p>
 * Every {@code .mapping} file under the input folder (recursively) is parsed by
 * mapping-io {@link EnigmaDirReader} and merged into one {@link MemoryMappingTree}.
 * The mapping is written with {@link VisitOrder#createByName()} so the output is
 * deterministic regardless of the filesystem traversal order.
 */
@Command(name = "enigma",
        mixinStandardHelpOptions = true,
        description = "Convert an Enigma mapping directory (folder of *.mapping files, mapping-io read) into a Tiny v2 mapping")
public final class EnigmaConvertCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-i", "--input"}, paramLabel = "<dir>",
            description = "Enigma mapping directory (scanned recursively for *.mapping; required)")
    private Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "<file>",
            description = "Output mapping (Tiny v2, required)")
    private Path output;

    @Option(names = "--source-ns", paramLabel = "<name>", defaultValue = "obf",
            description = "Source namespace name (default 'obf')")
    private String sourceNs;

    @Option(names = "--target-ns", paramLabel = "<name>", defaultValue = "named",
            description = "Target namespace name (default 'named')")
    private String targetNs;

    @Override
    public Integer call() throws IOException {
        if (input == null || output == null) {
            throw new ParameterException(spec.commandLine(),
                    "Missing required options: -i/--input, -o/--output");
        }

        MemoryMappingTree tree = readTree();
        if (tree.getClasses().isEmpty()) {
            throw new ParameterException(spec.commandLine(),
                    "No mappings found under Enigma directory: " + input);
        }

        MappingTreeUtil.write(output, tree, VisitOrder.createByName());

        long classes = tree.getClasses().size();
        long members = tree.getClasses().stream()
                .flatMap(c -> c.getFields().stream()).count()
                + tree.getClasses().stream()
                .flatMap(c -> c.getMethods().stream()).count();
        spec.commandLine().getOut().printf(
                "Convert complete: %d classes, %d members (namespace %s→%s)%n  %s%n",
                classes, members, sourceNs, targetNs, output);
        return CommandLine.ExitCode.OK;
    }

    private MemoryMappingTree readTree() {
        MemoryMappingTree tree = new MemoryMappingTree();
        try {
            EnigmaDirReader.read(input, sourceNs, targetNs, tree);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Enigma mapping directory: " + input, e);
        }
        return tree;
    }
}