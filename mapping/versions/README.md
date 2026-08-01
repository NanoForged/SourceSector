# mapping/versions — 已发布全量 mapping 版本库

按「游戏版本-平台-表类型」命名存放**已发布**的全量 mapping 表，作为跨游戏版本
mapping 迁移（intermediary 锚点）与多版本并存的对照基线。

## 命名约定

```
<游戏版本小写>-<平台>-full.tiny
```

例：`0.98a-rc8-windows-full.tiny`

- 游戏版本取 `Version.versionOnly` 小写（`0.98a-RC8` → `0.98a-rc8`）；
- 平台当前只有 `windows`（windows 为唯一基准，见 docs/design/vendor-jar-audit.md）；
- 文件为 `tiny 2 0 obf intermediary named` 三命名空间全量表，
  由 `:mapping:generateFullMappings` 产出后原样拷贝入库。

## 入库时机

- 游戏版本升级并完成漂移处理后，先归档**旧版本**表，再生成新版本表入库；
- mapping 语义面发生重大批次变更（如新一波大规模命名合入）时可追加归档，
  文件名追加日期后缀（如 `0.98a-rc8-windows-full-20260802.tiny`）。

## 注意

- 单表约 36 MB（文本高重复，git 压缩率良好）；不参与构建，仅作历史对照。
- 构建期实际消费的全量表始终在 `mapping/build/generated/mappings/`（生成物，不入库）。
