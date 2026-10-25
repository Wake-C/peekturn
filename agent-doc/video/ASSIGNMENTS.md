# Video A 开发分工

冻结需求：`REQUIREMENTS.md`。SHA-256：`c8bdf50ca67780fb9366abd0025e589e45d9773309c1b9d2d71652cc61476e8d`。

开发 Agent 不得修改需求文件。出现偏差向主 Agent 报告，由主 Agent 在验收记录中裁决，不改写原始验收条件。

- **VideoSourceImpl**：SRC 任务。负责 YouTube 链接、NewPipe 源解析、字幕轨读取及字幕解析，独占 REQUIREMENTS 第 16 节指定 SRC 文件。
- **VideoDataImpl**：DATA 任务。负责增量数据库迁移、视频仓储、字幕缓存、真实时长和统计展示，独占 DATA 文件。
- **VideoLearningImpl**：LEARN 任务。负责共享模型传输、字幕分组校验、媒体中立解释输入及独立解释浮层，独占 LEARN 文件。
- **VideoPlayerImpl**：PLAYER 任务。负责 A 原生播放界面、生命周期、播放控制、字幕选择、导入及错误状态，独占 PLAYER 文件。
- **主 Agent**：公共模型、应用导航／分享／Graph、依赖、版本、编译集成、依赖问题、独立测试组织、偏差裁决和签名交付。

四个开发任务并行编写，开发中不运行构建、lint、测试或格式化器。文件全部交付后主 Agent 统一编译及运行受影响测试。子任务交付不是功能验收通过。

候选 APK 就绪后，另行分配不参与实现的体验测试 Agent，按 AC02~AC24 执行逐步任务式 Android 测试。再由独立规格验收 Agent 检查全部 AC 与实现／证据是否对应。设备测试串行占用同一设备；只读验收可以并行。主 Agent 负责缺陷修复后的重新验证，以及 AC32 生产包交付。当前可用 Android 设备为 API 35 模拟器，不能把模拟器或 Agent 操作描述成物理手机真人访谈。
