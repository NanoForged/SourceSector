# SSOptimizer × SourceSector mapping 分叉裁决记录

## 背景

SSOptimizer 删除自研 `:mapping` 模块前，对两仓 tiny 资源做了条目级比对
（以 obf 侧 `owner + name + desc` 为键）。本文件记录比对结果与后续裁决跟踪。

- 比对时间：2026-08-03
- SSOptimizer 侧：`mapping/src/main/resources/mappings/`（123 文件，4135 类，56743 成员条目）
- SourceSector 侧：`mapping/src/main/resources/mappings/scopes/` + `versions/0.98a-rc8-windows-full.tiny`（124 文件，4166 类，249993 成员条目）

## 比对结论

- 类条目：SSOptimizer 独有 0，键同 named 不同 0 —— 类级命名已完全对齐。
- 成员条目：SSOptimizer 独有 20 条 → 其中 19 条已回填 SourceSector scopes
  （campaign-ui-marketinfo-b/c、coreui-refit-a/b、ui-core-a 的 windows 文件，见本次提交）。
- 成员条目：键同 named 不同 71 条 —— **未裁决**，分类见下。

### 误报说明（desc 键）

第 20 条「独有」`combat/entities/ship/A/String#glowEffect` 为误报：
SourceSector 已有同名字段，仅 desc 中的中间名类型更新
（SourceSector=`String$Oo` vs SSOptimizer=`C_33d7ced3`）。无需处理。

注意：以 desc 为键的比对对「仅 desc 内中间名漂移」的条目会产生误报/误判，
逐案裁决时应以 (owner, name) 为准，desc 仅作佐证。

## 71 条 named 冲突分类（待逐案裁决）

### A 类：SourceSector 保留混淆名/中间名，SSOptimizer 已翻译（~~应采纳 SSO 译名~~ → 已裁决，SSO 错译）

| 键 | SSO named | SS named（现状） |
|---|---|---|
| campaign/ui/MarketConditionsWidget#oöøO00 Color (linux) | baseColor（错译） | oöøO00（未翻译） |
| campaign/ui/MarketConditionsWidget#super.null$for Color (windows) | baseColor（错译） | super.null$for（中间名） |

**已裁决（2026-08-04，javap 取证）**：`getBaseColor()` 返回的是另一字段（obf `ÕöøO00`，已正确命名 baseColor）；
该字段为 `private final Color`，仅在构造器由第 4 个 Color 参数赋值后**从不读取**（死字段）。
SSO 的 baseColor 译名为一名多赋的错译。两仓统一采纳 `unusedColor`，已回填 scope 并重装 named jar。

注：scope 数据残留统计（77194 条目）：成员级未翻译仅 6 条（0.008%），类级中间名残留 27 条
（多为 misc-unscoped 的匿名内部类与低价值工具类），全量表语义覆盖率 99.7%。

### B 类：SSOptimizer 一名多赋（粗猜），SourceSector 区分更细（应保留 SS）

`ui/A/B` 的 5 个 float 字段 SSO 全部命名 `menuWidth`，SS 区分为
borderInset / fadeOutDuration / itemSpacing / itemBottomOffset / fadeInDuration —— SS 更准。

`ui/O0oOO…`（超长高辨识度混淆类）的 6 个字段 SSO 全部命名 `textOffset`，
SS 区分为 height / cutInset / unusedPadding / color / fontScale / width —— SS 更准。

`ui/G` 两个 Color 字段 SSO 全部 `textColor`，SS 区分 disabledColor / imageColor。

### C 类：双向语义化但指向不同（旋转错配，需 javap 逐案取证）

同一 (owner, name, desc) 两仓给出不同语义名，无法仅凭名称判断谁对谁错：

- `combat/E/F` 与 `combat/o0OO/F` 多字段：dpsDuration / hardFluxFraction / multiplier / damage /
  fluxComponent / overrideStats / modifiersApplied 等交叉错配（两仓疑似同一组字段的不同轮转分配）。
- `combat/entities/ContrailEngine$Oo#oO0000F`：SSO=width vs SS=baseWidth（语义近似，SS 更精确）。
- `combat/entities/ship/trackers/ooOO…#Ó00000F`：SSO=reloadProgress vs SS=reloadTime。
- `combat/systems/D$oo` 两个 Fader 字段：SSO 均为 fader vs SS 区分 resetFader / fadeInFader（倾向 SS）。
- `coreui/j` / `coreui/oOO0` 的 LabelImpl 字段：SSO=nameLabel vs SS=levelLabel。

裁决方式：对 named jar `javap -c` 取证字段实际读写语境，逐案确认；属 mapping 演进议题，
不阻塞 SSOptimizer 删除 `:mapping` 模块（SSOptimizer 侧常量已字面量化，不再消费这些条目）。

## 跟踪

- [x] A 类 2 条：javap 取证裁决，SSO 错译，统一命名 unusedColor（已回填并重装）。
- [ ] B 类：确认 SS 现状保留（无需动作，复核后勾销）。
- [ ] C 类：javap 取证逐案裁决。
