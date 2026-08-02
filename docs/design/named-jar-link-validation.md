# named 游戏 jar 成员链接校验（remap 产物链接校验器）

## 背景

remap 产物的「跨类引用/声明名字分叉」缺陷：子类字节码中对继承方法的引用名
与父类中该方法的声明名不一致（例如 `AssaultBattleStrategy.preCombat` 调用
`super(ZZ)V`、`Ó00000(I)V`，而 `BaseBattleStrategy` 中既没有这些名字的方法），
运行期抛 `NoSuchMethodError` 直接炸毁游戏主循环。

现有质量门 `FullMappingMerger.duplicateRemapTargetLines` 只管「同类内 remap 目标名
撞名」（同名同描述符的多个混淆成员映射为同一 named 名），管不到「跨类名字分叉」。
本校验器对 remap 产物做整体链接校验，弥补该缺口。

## 校验模型

- **索引**：以 4 个游戏 named jar（`starfarer_obf` / `starfarer.api` / `fs.common_obf` /
  `fs.sound_obf`）为唯一事实源，建立 `类名 →（声明方法 name:desc 集合、字段 name:desc 集合、
  父类、接口）` 索引（复用 `ClassStructure` 的结构扫描，不读方法体）。
- **扫描**：读全部类文件方法体字节码，采集引用：
  - `MethodInsn`（invokeinterface/invokespecial/invokestatic/invokevirtual）与 `FieldInsn`；
  - 常量池 `Handle` 常量（ldc 的 MethodHandle）；
  - invokedynamic 的 BootstrapMethods 属性：bootstrap 方法句柄 + 参数句柄
    （覆盖 lambda 的 impl 句柄）。
  - `Type`（类常量）与 `MethodType`（方法描述符常量）不含 owner+name 成员引用，不校验。
- **解析规则**（严格避免误报）：
  - a. owner 是数组类型/基本类型 → 跳过（如数组 `clone()`）；
  - b. owner 不在索引中 → 外部引用（JDK / 第三方库），跳过；
  - c. owner 在索引中 → 沿 superclass/接口链（仅在索引内的部分）查找 name+desc 匹配的声明；
  - d. 链走到索引外的非 Object 类 → 外部父类/接口可能声明，无法证伪，视为可解析；
  - e. 走到 `java/lang/Object` 仍未找到 → Object 自身声明（getClass/hashCode/equals/clone/
    toString/notify/notifyAll/wait×3/finalize）之外记为断裂。
  - 特殊方法 `<init>`/`<clinit>` 不沿继承链解析（构造器不继承），只查 owner 自身声明。
  - 字段与方法都按 remap 后名字直接比较（产物内自洽），无需命名空间换算。
- 断裂按（引用所在类, 引用所在方法, 目标 owner/name/desc）去重，报告按目标 owner 类聚类排序。

## 使用方式

Gradle 任务（推荐）：

```bash
./gradlew :mapping:verifyNamedJarLinks
```

独立 CLI：

```bash
java -cp <mapping-runtime-classpath> \
  io.github.nanoforged.sourcesector.mapping.gen.NamedJarLinkCli \
  <namedJarDir> <reportFile>
```

- `<namedJarDir>`：`build/named-game-jars/<platform>/`（默认 windows；目录下第三方 jar 不参与索引）。
- `<reportFile>`：断裂报告输出路径（Gradle 任务写到 `mapping/build/reports/named-jar-link-violations.txt`）。
- 存在断裂时抛 `MappingLookupException`，非零退出；报告文件在抛出前已完整写出。

## 接入点（已接入）

校验已挂到发布链：`publishNamedGameJars` `dependsOn("verifyNamedJarLinks")`——
named 产物成员链接断裂时发布直接失败；`verifyNamedJarLinks` 同时 `mustRunAfter("remapGameClasspathToNamed")`，
同一构建中两者被同时调度时保证 remap 先行（独立校验不强制 remap）。

## 已知盲区（保守设计，只可能漏报、不会误报）

1. 链上出现索引外非 Object 类（外部父类/接口）时按规则 d 视为可解析——外部类是否声明
   无从证伪。极端情况下（如实现 `java/io/Serializable` 的类引用幽灵方法）会漏报。
2. 类名恰为单个基本类型字母（如 `I`）的 owner 按规则 a 跳过，漏报而非误报。
3. 反射 / Mixin 等字符串形式的成员引用不在此校验范围。
4. scope 片段内部的 named **类名**撞名（两个不同混淆类映射为同一 named 名，产物类互相
   覆盖）不在校验器范围——已在修复期人工清理存量（见下），但无自动化门禁，建议后续在
   `ScopeFragments` 增加类名唯一性检查。

## 修复记录（2026-08-02，windows named 产物 149 条 → 0 条）

### 根因（两处）

1. **继承传播误判覆写（148 条的主体）**：混淆器给同一逻辑方法在声明类与子类分配
   **同名不同描述符**的垃圾名（子类声明 `super()F`、父类声明 `super(ZZ)V`），
   `InheritedMemberPropagator` 原按「成员名」判定覆写/隐藏，把父类的 `super(ZZ)V` 等
   误判为已被子类覆写而跳过传播 → 子类引用侧 remap 不命中保持混淆名，声明侧 remap 成
   语义名 → 分叉（`AssaultBattleStrategy.preCombat` 调用 `super(ZZ)V` 即此例）。
2. **scope 片段内部 named 类名撞名（1 条 + 3 处隐患）**：`RowColumnComparator` 是
   `FighterBlueprintBrowser$2` 与 `HullmodBlueprintBrowser$1` 两个独立匿名内部类被映射为
   同一 named 名，产物类互相覆盖；`HullmodBlueprintBrowser.tableGetComparator` 引用的
   `<init>(HullmodBlueprintBrowser;Object;)V` 落在幸存类 `FighterBlueprintBrowser$2` 上，
   签名不匹配即断裂。同机制隐患还有 `Column`（`HullmodBlueprintBrowser$o` 与
   `IntelIncomePanel$o`）、`CampaignShipEngineGlow$SlotWidthComparator`（`$1` 与 `$3`）。

### 修复

1. `InheritedMemberPropagator` 覆写/隐藏判定改为 **name+desc 粒度**（`ownMethodKeys`/
   `ownFieldKeys`），同名不同描述符不再误判覆写——全量条目 +737 条（补回漏传播的别名）。
2. 新增 `InheritedMemberAligner` 继承对齐 pass（在继承传播之后、重名质量门之前）：
   子类侧继承引用别名条目的 remap 目标统一为「沿继承链最近真实声明类的表内条目」目标，
   并按 (owner, kind, obfName, desc) 去重；声明侧表内无条目 / 索引内链无声明类时保留现状
   并告警。修复后替换 0 条（当前数据无「子类侧已有分叉条目」场景，纯结构性兜底），
   告警 124 条（子类 scope 翻译了但声明类未翻译的成员，两边保持原名则不断裂）。
3. scope 源表修正（windows，3 处类名撞名）：
   - `HullmodBlueprintBrowser$1` → `HullmodColumnComparator`（`FighterBlueprintBrowser$2`
     保留 `RowColumnComparator`，与产物幸存者一致）；
   - `IntelIncomePanel$o` → `IncomeColumn`（`HullmodBlueprintBrowser$o` 保留 `Column`）；
   - `CampaignShipEngineGlow$3` → `CampaignShipEngineGlow$SlotWidthComparator2`（`$1` 保留）。
   linux 表存在同样撞名（campaign-command-a-linux.tiny），任务范围只改 windows，linux 待后续维护。

### 结果

- `:mapping:verifyNamedJarLinks`：索引类 **6382** 个，断裂 **0 条**；报告
  `mapping/build/reports/named-jar-link-violations.txt` 为空（无断裂段）。
- 产物 javap 抽查：`AssaultBattleStrategy.preCombat` 引用全部为语义名
  （`computeFleetPoints(ZZ)V`、`orderAssaultObjectives(I)V`、`updateAssaultReadiness(F)V` 等），
  与 `BaseBattleStrategy` 声明一致；`super(ZZ)V`/`Ó00000(I)V`/`String(F)V` 等 garbage 引用 0 残留。
- 全量 `:mapping:test` 71 项全绿（新增：传播粒度修复 1 例 + 对齐器 5 例）。

