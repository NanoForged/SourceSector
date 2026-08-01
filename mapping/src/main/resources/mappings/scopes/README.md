# scope 语义映射片段

本目录存放按作用域（scope）拆分的语义映射片段，是构建期全量表的语义层数据源，
由 Phase 2 的 swarm 按 scope 并行产出。

## 平台约定

mapping 以 **windows 为唯一基准平台**（单平台收敛）：生成与校验只消费
`*-windows.tiny` 片段；linux 片段文件保留作历史参考，不进入构建。
全平台统一部署 windows 版产物，跨平台运行时承载（natives / 启动环境）
由 NanoForge 负责。

## 文件约定

- 文件名：`{scope}-{platform}.tiny`（如 `campaign-fleet-windows.tiny`）。
- 格式与 `../ssoptimizer-{platform}.tiny` 一致：Tiny v2 双列（头部
  `tiny	2	0	obf	named`），obf 列保持该平台的游戏真实混淆名，named 列为语义名；
  允许注释行（类行下 `\tc ...`、成员行下 `\t\tc ...`）。
- 注释按工作流约定记录：`来源(API-impl种子|javap取证|运行时验证) + 置信度 + 证据文件路径`。
- 片段允许只映射部分成员：未覆盖的成员在全量表中保持未命名（named 列为空，
  remap 时落 intermediary 锚点名 `f_<hash8>`/`m_<hash8>`）。
- scope 之间必须互不相交：同一混淆类/成员不得被两个 scope 映射，
  同一 named 类名不得被两个 scope 用于不同混淆类（`generateFullMappings` 与
  `mergeScopeFragments` 会报错并指明两个 scope）。

## 全量表格式（生成物，非人工输入）

`generateFullMappings` 输出的 `ssoptimizer-windows-full.tiny` 为三列 Tiny v2
（头部 `tiny	2	0	obf	intermediary	named`），与双列人工层输入不同：

- 类行：`c	<obf>	<intermediary>	[<named>]`；成员行：`f|m	<obf>	<intermediary>	[<named>]	<desc>`。
- named 列省略（为空）表示未命名条目，remap 目标规则为 `named ?: intermediary`。
- intermediary 列为结构指纹锚点名（类 `C_<hash8>`、成员 `f_/m_<hash8>`），
  由 `IntermediaryNameGenerator` 确定性生成；人工/identity 层条目无锚点时
  以 obf 名充当 identity intermediary。
- 成员描述符以 **obf 侧为 canonical**（人工/scope 片段中的 named 描述符在
  合并时统一换算为 obf 形式）。

## 分层优先级

占位生成 < `ssoptimizer-identity.tiny` < **scope 片段** < 人工运行期表
`ssoptimizer-{platform}.tiny`（同混淆 key 高层胜出）。运行期权威表保持人工维护，
app 运行期管线不消费本目录。

## 校验

- `./gradlew :mapping:mergeScopeFragments`：校验全部片段（可解析、逐条目 jar 一致性、
  跨 scope 唯一性），输出汇总报告 `mapping/build/reports/scope-fragments.txt`；
  只校验并报告，不改动任何表。
- `./gradlew :mapping:generateFullMappings`：合并片段进全量表
  `mapping/build/generated/mappings/windows/ssoptimizer-windows-full.tiny`，
  并产出跨平台指纹对位报告 `mapping/build/reports/cross-platform-match.txt`（CI 门禁）。
