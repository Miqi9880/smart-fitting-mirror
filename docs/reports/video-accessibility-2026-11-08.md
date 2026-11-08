# 视频页读屏与焦点查漏（FR-VID-05）

日期：2026-11-08。范围仅为 `android/app` 的 `VideoPanel.kt`、`VideoExperience.kt` 与相关 UI 测试，输入使用既有合成短片／本地构造元数据；未使用真人、相机、镜子或外部付费服务。

## 基线与限制

- 当前交付目录是源码副本，没有 `.git` 元数据，无法确认 main 的提交号或创建 `fix/video-accessibility`。提交前应在最新 main 建分支，再应用这五个文件。
- 本机 Gradle 启动失败：`JAVA_HOME is not set and no 'java' command could be found in your PATH`（退出码 49）。未找到 `adb`／`emulator`，因此没有可附的模拟器启动日志，也没有设备运行、截图或真实 TalkBack 证据。
- 下列“复现步骤”由代码路径审查确定，预期行为已写成仪器测试，但测试仍待在 API 35 模拟器实际执行；不记为动态验收通过。

## 查到的问题与最小修复

### 1. 页面切换后键盘焦点没有明确落点

原路径只切换 `visibility`：选中卡片打开播放器后未聚焦播放控件；返回列表未恢复原卡片；退出视频页未恢复试衣入口；加载／失败状态也没有明确焦点。键盘或读屏焦点可能留在刚隐藏的控件，或由系统重新选择起点。

复现路径：聚焦“查看当前搭配的相关视频”并确认 → 选中视频 → 暂停／继续 → 下一条 → 返回列表 → 返回试衣；每一步检查 `currentFocus`，并用 Tab／方向键继续移动。

修复：

- `VideoPanel.present(Items)` 显示列表后聚焦第一张卡片。
- 从列表进入播放器时聚焦“返回列表”；播放器内切下一条不重置焦点。
- 返回列表时，在释放播放状态前保存索引并恢复原卡片。
- 加载、空态和失败状态显示后，聚焦“重试”（可重试）或“返回试衣”。
- `VideoExperience.hide()` 先恢复试衣页，再把焦点还给 `fitting.videos`。视频页显示时试衣页仍为 `GONE`，不会进入其隐藏后代。

### 2. 整张卡片的读屏描述漏掉匹配依据

原卡片 `contentDescription` 只有标题、时长、方向、来源类型和标签，“相关依据”仅在子 `TextView` 中。聚焦可点击卡片时可能漏读该信息；保留可朗读的子文本也可能造成标题等重复朗读。真实朗读顺序仍需 TalkBack 确认。

修复：把非空 `matchesText` 合并进卡片描述，将卡片内纯展示 `TextView` 标为 `IMPORTANT_FOR_ACCESSIBILITY_NO`。封面原有的 `NO` 保留，“来源与许可”按钮仍为独立可聚焦节点，描述继续包含视频标题；未修改许可弹窗或素材内容。

## 回归用例

`VideoPlaybackFlowTest.kt` 新增：

1. `keyboardFocusFollowsPlaybackAndReturnsToSelectedCard`：使用本地合成短片，实际派发方向键确认、Tab、Shift+Tab 和 Enter，覆盖“选中 → 暂停／继续 → 下一条 → 返回原卡片”，并断言下一条后焦点不跳回起点、方向键移动后焦点仍可见。
2. `cardDescriptionIncludesMatchReasonWithoutDuplicateTextNodes`：本地构造含 1:05 时长、匹配依据和来源的卡片，检查完整描述、展示文本不重复暴露、来源按钮名称与可聚焦性。

`LicensedVideoFlowTest.kt` 新增／加强：

1. `returnFromVideoRestoresEntryFocusWithoutEnteringHiddenFittingPage`：无需服务或相机，进入无连接错误页后返回，断言焦点恢复到 `fitting.videos`，视频页隐藏时试衣页为 `GONE`。
2. `enlargedFontsAndOrientationKeepVideoControlsReachable` 既有 130%／150% 与横竖屏检查，新增音量条；逐项请求滚动并聚焦后，检查返回、暂停／继续、下一条、结束和音量的完整可见宽高。

未改播放状态机、MediaPlayer、重试请求、搭配／结果保留、后台释放或结束清理逻辑。

## 实际执行的检查

在 `android` 目录尝试：

```bash
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

结果：未进入 Gradle 构建，因缺少 Java 失败，错误原文见上。故以下项目均**未运行**：Debug APK 编译、AndroidTest 编译、JVM 单测、lint、`VideoPlaybackFlowTest`、`LicensedVideoFlowTest`、真实 TalkBack、大字号截图。

## 补跑与 TalkBack 验收步骤

1. 在具备 JDK 21、SDK 35 的环境重跑上述四项 Gradle 检查，再运行 `VideoPlaybackFlowTest`；授权包和本机 MOCK 可用时运行 `LicensedVideoFlowTest`。
2. 开启真实 TalkBack，从试衣页聚焦“查看当前搭配的相关视频”，确认进入列表。逐卡检查标题、时长、标签、相关依据只读一遍，封面不重复标题；继续滑动可到“查看…的来源与许可”，打开后能读到完整来源与许可。
3. 打开第一条，确认焦点落在“返回列表”；Tab 顺序应为返回列表 → 暂停／继续 → 下一条 → 结束体验 → 音量。确认暂停后读到“继续”和“已暂停”，继续后读到“暂停”和“播放中”。
4. 移到下一条并确认，检查新标题和播放状态，焦点仍在“下一条”；Shift+Tab 返回“暂停”及“返回列表”。返回列表后应聚焦刚才播放的卡片，再“返回试衣”应聚焦视频入口。视频显示期间反复 Tab／方向键，不得进入隐藏试衣控件。
5. 模拟加载失败，确认状态原因可读、焦点落在“重试”；重试恢复列表后焦点落在第一张卡片。空态／加载中应可到“返回试衣”。
6. 系统字号分别设为 130%／150%，横竖屏各检查一次；通过键盘焦点或横向滚动到达返回、暂停／继续、下一条、结束和音量，不裁剪、不被系统栏遮挡。
7. 保存 TalkBack 操作记录与对应截图。以上仅作为模拟器软件验收；镜子触摸、真实性能和现场验证仍由 Miqi9880 负责。
