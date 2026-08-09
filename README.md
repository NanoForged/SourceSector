# SourceSector

Mutil-Usage Cli Tool for NanoForged Toolchain

Generate deterministic intermediary name mappings (Tiny v2) for obfuscated JARs, following the Fabric Intermediary naming convention.

## Features

- **Deterministic output** — identifiers are assigned in topological × declaration order, so identical input always produces identical mappings.
- **Inheritance-correct** — overriding methods reuse the intermediary name of the ancestor method; with diamond-interface ambiguity, the lexicographically smallest name wins.
- **Readable-name back-mapping** — an `intermediary → readable` projection is written automatically, promoting original names that pass obfuscation heuristics.
- **`verify` subcommand** — checks that the `obf → intermediary` and `intermediary → named` stages compose losslessly (zero dangling class/member/descriptor references, zero duplicate class targets).
- **`layermapping` subcommand** — merges two mappings that share the same namespace layout; the overlay overrides corresponding entries in the base.

## Usage

```
sourcesector [-i <jar>]... [--input-dir <dir>]... [-l <jar>]...
            [--library-dir <dir>]... [-p <pkg>] -o <file> [-r <file>]
```


### Main command: generate mappings

| Option | Description |
| --- | --- |
| `-i, --input <jar>` | Input obfuscated jar (repeatable) |
| `--input-dir <dir>` | Directory of input jars (scans `*.jar`, sorted; repeatable) |
| `-l, --library <jar>` | Library jar, used only for inheritance analysis, never mapped (repeatable) |
| `--library-dir <dir>` | Directory of library jars (repeatable) |
| `-p, --prefix <pkg>` | Intermediary package prefix (default `com/fs`; pass empty string to omit) |
| `-o, --output <file>` | Output mapping `obf → intermediary` (Tiny v2, required) |
| `-r, --readable-output <file>` | Readable back-mapping `intermediary → readable` (defaults to `<output>.readable`) |

Example:

```bash
java -jar build/libs/sourcesector-all.jar \
  -i game.jar --input-dir mods \
  -l game-core.jar \
  -o build/game-intermediary.tiny
```

### Subcommand: `verify`

Verify that two mapping stages compose cleanly:

```bash
java -jar build/libs/sourcesector-all.jar verify \
  -1 build/game-intermediary.tiny \
  -2 build/game-intermediary.tiny.readable
```

Prints `✓ Pairing complete` and exits `0` when the pairing is sound; otherwise prints up to 20 violations and exits `1`.

### Subcommand: `layermapping`

Merge two mappings with identical namespace layouts — the overlay overrides matching entries of the base:

```bash
java -jar build/libs/sourcesector-all.jar layermapping \
  -b base.tiny --overlay overlay.tiny -o merged.tiny
```

### Subcommand: `enigma`

Convert an Enigma mapping directory (a folder of `*.mapping` files, e.g. from ProGuard/Enigma exports) into a single Tiny v2 mapping using mapping-io:

```bash
java -jar build/libs/sourcesector-all.jar enigma \
  -i path/to/mappings \
  -o converted.tiny
```

| Option | Description |
| --- | --- |
| `-i, --input <dir>` | Enigma mapping directory (scanned recursively for `*.mapping`; required) |
| `-o, --output <file>` | Output mapping (Tiny v2, required) |
| `--source-ns <name>` | Source namespace (default `obf`) |
| `--target-ns <name>` | Target namespace (default `named`) |

The output is written in deterministic (name-sorted) order, so identical input folders always produce byte-identical files. Exits `2` if the folder contains no mappings.

## How naming works

- Classes are named `class_0`, `class_1`, …; fields `field_0`, …; methods `method_0`, … — each with its own global counter (Fabric Intermediary convention, globally unique).
- Class names can be placed under an optional package prefix (e.g. `com/fs/class_0`).
- Classes are mapped in topological order so parents are processed before children; overriding methods (same source name + descriptor) reuse the ancestor's intermediary name.
- Constructors (`<init>`/`<clinit>`) are not mapped. Library classes and phantom stubs produce no entries.
- Original names that pass `ObfuscationHeuristics` (readable ASCII identifiers that are not obfuscator dictionary names, not `o0`-style junk) are recorded in the `named` column of the back-mapping.

## Architecture

```
src/main/java/io/github/nanoforged/sourcesector/
├── SourceSector.java           CLI entry point (picocli)
├── command/                    Commands: verify, layermapping, enigma
└── mapping/
    ├── MappingEntry.java       Mapping entry record
    └── core/
        ├── ClassProvider       Loads classes from input/library jars (ASM)
        ├── ClassHierarchyBuilder / ClassHierarchyGraph
        │                       Inheritance graph, topological order
        ├── MappingGenerator    Deterministic intermediary naming
        ├── ObfuscationHeuristics   Readable-name detection
        ├── MappingPairingValidator / MappingTreeUtil
        │                       Tiny v2 I/O and compose-validation (mapping-io)
        └── MapperFacade        Pipeline orchestration
```

Pipeline: `ClassProvider → ClassHierarchyBuilder → MappingGenerator → Tiny v2 output`.

## Dependencies

- [ASM](https://asm.ow2.io/) — bytecode reading
- [mapping-io](https://github.com/FabricMC/mapping-io) — Tiny v2 read/write
- [picocli](https://picocli.info/) — CLI framework

