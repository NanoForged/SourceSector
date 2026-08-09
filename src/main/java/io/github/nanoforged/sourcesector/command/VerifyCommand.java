package io.github.nanoforged.sourcesector.command;

import io.github.nanoforged.sourcesector.mapping.core.MappingPairingValidator;
import io.github.nanoforged.sourcesector.util.MappingTreeUtil;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code sourcesector verify}: verify that the intermediary mapping (obf → intermediary)
 * and the named mapping (intermediary → named) can be directly composed and consumed —
 * zero dangling class/member/descriptor references and zero duplicate class targets.
 * An empty violation list means a complete pairing (exit code 0); otherwise violations
 * are printed and the command exits with code 1.
 */
@Command(name = "verify",
        mixinStandardHelpOptions = true,
        description = "Verify that two mapping stages can be directly composed and consumed (zero dangling class/member/desc references, zero duplicate class targets)")
public final class VerifyCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-1", "--intermediary"}, paramLabel = "<file>", required = true,
            description = "Intermediary mapping (obf→intermediary, Tiny v2)")
    private Path intermediary;

    @Option(names = {"-2", "--named"}, paramLabel = "<file>", required = true,
            description = "Named mapping (intermediary→named, Tiny v2)")
    private Path named;

    @Override
    public Integer call() throws IOException {
        MemoryMappingTree stage1 = MappingTreeUtil.read(intermediary);
        MemoryMappingTree stage2 = MappingTreeUtil.read(named);
        List<String> violations = MappingPairingValidator.validate(stage1, stage2);

        if (violations.isEmpty()) {
            long classes = stage1.getClasses().size();
            long members = stage1.getClasses().stream()
                    .flatMap(c -> c.getFields().stream()).count()
                    + stage1.getClasses().stream()
                    .flatMap(c -> c.getMethods().stream()).count();
            spec.commandLine().getOut().printf(
                    "✓ Pairing complete: %d classes, %d members, zero dangling references%n  %s%n  %s%n",
                    classes, members, intermediary, named);
            return picocli.CommandLine.ExitCode.OK;
        }
        spec.commandLine().getErr().printf("Pairing broken: %d violations%n", violations.size());
        for (String violation : violations.stream().limit(20).toList()) {
            spec.commandLine().getErr().println("  " + violation);
        }
        if (violations.size() > 20) {
            spec.commandLine().getErr().println("  ... (" + (violations.size() - 20) + " more omitted)");
        }
        return picocli.CommandLine.ExitCode.SOFTWARE;
    }
}