# named 游戏 jar 安装链路（obf jar → mapping → named jar → 游戏目录）

## 背景

Starsector 本体 jar 是混淆产物，成员名（类/字段/方法）不可读。SourceSector 以
mapping（tiny 全量表，windows 平台为基准）把 obf jar remap 成 named jar，供
NanoForge / 上层工程以语义名开发、反编译、调试。remap 产物要真正被游戏运行时
使用，还需覆盖安装回游戏目录——本链路即描述从 obf jar 到游戏目录安装的完整流程，
以及安装任务的备份/门禁语义。

## 安装链路

```
game-jars/{platform}/ vendor obf jar（或 -Pstarsector.gameDir 游戏目录 jar）
        │  remapGameClasspathToNamed（消费全量 tiny 表，batch obf-to-named）
        ▼
build/named-game-jars/{platform}/   ← 4 个游戏 jar（starfarer_obf / starfarer.api /
                                        fs.common_obf / fs.sound_obf，另有透传的第三方 jar）
        │  verifyNamedJarLinks（成员链接完整性门禁：继承链名字分叉 → NoSuchMethodError 源头）
        ▼
publishNamedGameJars（发布到本地仓库 build/named-game-repo/{platform}，供 app 模块依赖消费）
        │
        ▼
installNamedGameJars（-Pstarsector.gameDir=<游戏目录>）
        │  备份游戏目录原 jar → <name>.jar.obf-backup（已存在则跳过）
        ▼
游戏目录 4 个 jar 被 named 版覆盖 → 启动前由 NanoForge precheck 的 named 判定门复核
```

- 4 个游戏 jar 基名见 `mapping/build.gradle.kts` 的 `gameJarBaseNames`；
  `named-game-jars/{platform}/` 目录内还包含 remap 时透传的第三方 jar，安装时
  只按基名精确匹配这 4 个，不整目录覆盖。
- 安装目标目录由 `-Pstarsector.gameDir` 指定（沿用 remap 任务的同一属性约定）；
  存在 `starsector-core` 子目录时安装进该子目录，否则按游戏根目录处理。

## 使用方式

```bash
./gradlew :mapping:installNamedGameJars -Pstarsector.gameDir=/path/to/Starsector
```

任务语义：

1. **门禁**：`dependsOn("publishNamedGameJars")`，其依赖链内含
   `verifyNamedJarLinks`——named 产物成员链接断裂时发布失败，安装不会发生。
2. **备份**：首次覆盖前把游戏目录原 jar 复制为 `<name>.jar.obf-backup`；
   备份已存在则跳过（不重复覆盖备份），保证幂等。
3. **覆盖**：把 `build/named-game-jars/{platform}/` 的 4 个游戏 jar 覆盖到游戏目录同名文件。
4. **完成提示**：输出「可用 NanoForge LaunchPrecheck（启动 precheck 的 named
   判定门）复核安装结果」。
5. **不做兜底**：游戏目录缺目标 jar、备份失败、named 产物缺失均直接报错失败。

## 幂等与复核

- 安装是副作用操作，任务不做增量跳过（`outputs.upToDateWhen { false }`），
  每次执行都重新做备份判定并覆盖安装。
- 安装后建议复核：
  - `javap -cp <游戏目录>/fs.common_obf.jar com.fs.graphics.TextureObject` 抽查
    字段/方法为可读语义名（如 `imageWidth` / `bindTarget`）；
  - 用 NanoForge `LaunchPrecheck` 的 named 判定门做整体复核（各游戏 jar 全部
    判定为 named 时启动就绪）。
