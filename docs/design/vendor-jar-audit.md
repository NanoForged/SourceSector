# vendor jar 核实结论（2026-08-02，R5 阶段 0）

针对 `cross-platform-match.txt` 仅 1312/2932 类指纹匹配的疑点，核实结果如下。

## 版本一致性

| 项 | linux vendor | windows vendor |
|---|---|---|
| `Version.versionOnly` | 0.98a-RC8 | 0.98a-RC8 |
| `Version.versionString` | Starsector 0.98a-RC8 | Starsector 0.98a-RC8 |
| starfarer_obf.jar 类文件数 | 2928 | 2928 |

**两端游戏版本一致**，低匹配率不是版本错位导致。

## 低匹配率的真实原因

1. **混淆字典分叉**：linux / windows 安装包由混淆器分别以不同字典产出，
   成员级混淆名双平台不同（mapping 工作流文档早有记载），类结构指纹
   （父类 + 接口 + 字段/方法描述符多重集）不随名字变化，但混淆器的
   平台分支裁剪（条件编译类、synthetic 增删）会造成真实结构差异。
2. **windows vendor 已换为汉化版 jar**（2026-07，用户手动替换）：汉化补丁
   直接改写了部分类的成员（marketinfo/refit UI、ScrollPanel 等文本布局
   相关类），类时间戳为 1970-01-01（确定性重打包标记），与原 windows
   安装包不是同一份字节。换 jar 时导致 scope 片段 20 项描述符漂移
   （片段中引用占位类 `C_xxxxxxxx` 的描述符在三命名空间表下不再参与
   named→obf 换算），已随 R5 阶段 0 全部改写为 obf 规范名修复。

## 结论

- 「全平台统一 windows 产物」模型不受影响：linux vendor 降级为
  历史参考，不再承担对位验证职责；cross-platform-match 报告不再作为门禁。
- windows vendor（汉化版）是**唯一基准**，named jar / 全量表 / 部署
  均以其为准。汉化补丁改动的类集合即为 mapping 需要跟随的漂移面，
  版本升级（如 0.98.5a）时按工作流文档的漂移流程处理。
- linux scope 片段 / 小表资源：保留冻结（linux 人工表与 scope 片段仍是
  双平台语义面测试 `platformMappingsKeepSameNamedSurface` 的一部分，
  且为可能的 linux 原生调研保留参照），不做清理。
