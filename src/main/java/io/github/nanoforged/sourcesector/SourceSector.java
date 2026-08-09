package io.github.nanoforged.sourcesector;

import io.github.nanoforged.sourcesector.util.CliUtil;
import io.github.nanoforged.sourcesector.command.EnigmaConvertCommand;
import io.github.nanoforged.sourcesector.command.LayerMappingCommand;
import io.github.nanoforged.sourcesector.command.VerifyCommand;
import io.github.nanoforged.sourcesector.mapping.core.MapperFacade;
import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;


@Command(name = "sourcesector",
        mixinStandardHelpOptions = true,
        version = "0.1.0-SNAPSHOT",
        description = "Generate deterministic intermediary name mappings (Tiny v2, mapping-io read/write) for obfuscated jars",
        subcommands = {
            VerifyCommand.class,
            LayerMappingCommand.class,
            EnigmaConvertCommand.class
}
)
public final class SourceSector implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-i", "--input"}, paramLabel = "<jar>",
            description = "Input obfuscated jar (repeatable)")
    private List<Path> inputs = new ArrayList<>();

    @Option(names = {"--input-dir"}, paramLabel = "<dir>",
            description = "Directory of input jars (scans *.jar, sorted and included; repeatable)")
    private List<Path> inputDirs = new ArrayList<>();

    @Option(names = {"-l", "--library"}, paramLabel = "<jar>",
            description = "Library jar (used only for inheritance analysis, not mapped; repeatable)")
    private List<Path> libraries = new ArrayList<>();

    @Option(names = {"--library-dir"}, paramLabel = "<dir>",
            description = "Directory of library jars (repeatable)")
    private List<Path> libraryDirs = new ArrayList<>();

    @Option(names = {"-p", "--prefix"}, paramLabel = "<pkg>", defaultValue = "com/fs",
            description = "Intermediary package prefix (default 'com/fs', Fabric intermediary convention; pass empty string to remove prefix)")
    private String prefix;

    @Option(names = {"-o", "--output"}, paramLabel = "<file>",
            description = "Output intermediary mapping (obf→intermediary, Tiny v2, complete; required)")
    private Path output;

    @Option(names = {"-r", "--readable-output"}, paramLabel = "<file>",
            description = "Output readable back‑mapping (intermediary→readable, defaults to <output>.readable)")
    private Path readableOutput;


    public static void main(String[] args) {
        int exitCode = new CommandLine(new SourceSector())
                .setExecutionExceptionHandler((exception, commandLine, parseResult) -> {
                    commandLine.getErr().println("Error: " + exception.getMessage());
                    if (exception.getCause() != null) {
                        commandLine.getErr().println("Cause: " + exception.getCause().getMessage());
                    }
                    return CommandLine.ExitCode.SOFTWARE;
                })
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws IOException {
        if (output == null) {
            throw new ParameterException(spec.commandLine(), "Missing required option: -o/--output");
        }
        List<Path> resolvedInputs = CliUtil.resolveInputs(inputs, inputDirs, spec);
        List<Path> resolvedLibraries = CliUtil.resolveLibraries(libraries, libraryDirs, spec);
        String normalizedPrefix = CliUtil.validatePrefix(prefix, spec);

        MapperFacade.MapperResult result = new MapperFacade()
                .generateMappings(resolvedInputs, resolvedLibraries, normalizedPrefix);


        MemoryMappingTree tree = MappingTreeUtil.fromEntries(result.entries(), "obf",
                List.of("intermediary", "named"));
        MappingTreeUtil.writeProjection(output, tree, null, List.of("intermediary"), false);
        Path readable = readableOutput != null ? readableOutput
                : output.resolveSibling(output.getFileName() + ".readable");
        MappingTreeUtil.writeProjection(readable, tree, "intermediary", List.of("named"), true);

        spec.commandLine().getOut().printf(
                "Generation complete: %d classes, %d methods, %d fields, %d readable back-writes%n"
                        + "  obf→intermediary: %s%n"
                        + "  intermediary→readable: %s%n",
                result.mappedClasses(), result.mappedMethods(), result.mappedFields(),
                result.readableCount(), output, readable);
        return CommandLine.ExitCode.OK;
    }
}