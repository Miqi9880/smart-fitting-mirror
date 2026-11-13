# 视频页读屏语义与键盘焦点查漏

2026-11-06，关联长期任务 #7。范围仅为 `android/app` 现有 `video/VideoPanel.kt`、`video/VideoExperience.kt` 及相关 UI 测试；没有重做视频 HTTP、列表、封面、播放器或页面接入，没有修改公共类型、网络、相机、`feature-tryon`、默认尺寸或渲染算法。

## 基线与交付边界

当前下载目录起初没有 `.git`。为保留完整文件，先把 372 个文件的下载快照保存为本地基线提交 `24c27f4`，再建立 `fix/video-accessibility`。该基线不是已核实的远端 `main`；本机 GitHub 访问仍需要登录，因此本记录不声称已同步远端、已推送或已有 PR。功能、对应测试和本记录在同一本地分支提交。合入前需把该提交放到真实最新 `main` 上重查差异并重跑适用检查。

## 查出的具体问题与修复

### 1. 卡片节点缺少来源和匹配依据，子文本可能重复遍历

触发路径：进入视频列表，用辅助功能遍历任一卡片。旧卡片 `contentDescription` 只有标题、时长、方向、内容性质和标签，没有 `matchesText` 与 `sourceText`；标题、元数据、标签和匹配依据子 `TextView` 又保持辅助功能默认可见。该节点自身不能提供来源／匹配依据，且不同服务遍历子节点时可能重复读出已汇总文本。

修复后：

- 卡片保留一个可点击、可聚焦的汇总描述，增加“相关依据”和“来源”。来源使用来源正文第一段；完整授权说明仍由原有“来源与许可”按钮及对话框提供，不把长许可全文塞进卡片描述。
- 没有匹配依据时明确读“未提供具体匹配依据”；合成数据没有来源正文时读“程序生成测试数据”，不虚构授权来源。
- 卡片内普通子文本设置为 `IMPORTANT_FOR_ACCESSIBILITY_NO`，避免和汇总描述重复；来源按钮保持独立可聚焦、可点击。
- 封面原有 `IMPORTANT_FOR_ACCESSIBILITY_NO` 保持不变，并加入授权视频回归断言。

### 2. 面板切换只改可见性，没有接续键盘焦点

触发路径：用 Tab／方向键聚焦卡片并按确认键，旧代码把列表设为 `GONE`、显示播放器，但不把焦点交给播放器控件；“返回列表”和“返回试衣”同样只切换可见性。焦点可能留在已经隐藏或移除的 View 上，后续方向键失去可见起点。

修复后：

- 列表结果出现时聚焦第一张卡片；打开／恢复播放器时聚焦播放／暂停按钮。
- “返回列表”和系统返回先把原卡片索引保存下来，再释放播放器并聚焦原卡片；只有用户导航走该焦点恢复，生命周期释放不会把焦点拉进隐藏页面。
- 加载失败聚焦“重试”，不可重试／空状态聚焦“返回试衣”。
- 退出视频页后聚焦原有“查看当前搭配的相关视频”入口；若用户已快速重新打开视频，延迟任务不会把焦点拉回隐藏试衣页。
- 面板继续使用 `GONE` 隔离隐藏试衣页、列表和播放器，不新增自动播放、生成或相机启动。

### 3. 音量静态标签与控件名称重复，异步状态区缺少实时语义

- 音量条继续使用原生 `contentDescription="音量"` 和 `RangeInfo` 暴露当前数值，0% 即既有静音状态；去掉独立“音量”静态标签的辅助功能遍历，避免相同名称连续出现。没有另加一套静音状态机。
- 播放／暂停、下一条、返回和重试仍用各自动态按钮文字及原生 enabled 状态，不叠加相同 `contentDescription`。
- 加载／空／错误消息改为 polite live region，异步错误替换加载文字时具有实时通知语义。

## 自动化覆盖

`VideoPlaybackFlowTest` 从 6 项增加到 9 项，新增：

1. 注入明确测试来源和匹配依据，检查卡片描述、时长、去重子文本及独立来源按钮。
2. 真实发送 Tab、方向键、确认键，检查卡片 → 播放器 → 返回原卡片 → 下一张卡片，并读取音量节点的 `RangeInfo`。
3. 播放器内重建、退后台再回来，检查焦点属于新播放器，旧 View 和隐藏列表不持有焦点。

`LicensedVideoFlowTest` 保持 9 项，增强其中 3 项：

- 真实授权目录断言来源／匹配依据进入卡片描述，封面不参与辅助功能遍历。
- 通过键盘打开产品视频页；暂停、重建、后台恢复后焦点在播放器；系统返回回到原卡片，退出回到视频入口，同时保留原图、结果、搭配和贴合目标。
- 130%／150% 字体、横竖屏下继续检查 48dp 和文字不裁切，并按 Tab 检查“下一条 → 结束体验 → 音量”的可见焦点顺序。

这些仪器测试源码已编译，**本机没有在线 ADB 设备，未实际执行，不能写成原生测试通过**。真实 TalkBack 朗读同样未运行。

## 本机实际验证

环境：Windows 11、JDK 21.0.8、Gradle 8.11.1、现有 SDK35。通过 `C:\sfm` 和 `C:\gradle-home` 规避中文用户目录的 Gradle 测试 worker 问题；SDK 仅通过命令环境变量指定，没有把本机路径写入仓库。

```powershell
$env:GRADLE_USER_HOME="C:\gradle-home"
$env:ANDROID_HOME="C:\Users\可之一\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
& "C:\sfm\android\gradlew.bat" -p "C:\sfm\android" --no-daemon `
  :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

最终结果：`BUILD SUCCESSFUL`；35 项 App JVM 单元测试，0 失败、0 错误、0 跳过；Debug APK、AndroidTest APK 和 lint 均完成。`git diff --check` 通过。首次命令因未设置 SDK 环境变量报 `SDK location not found`，指定现有 SDK 后重跑成功；没有把该环境失败算作代码测试失败或通过。

`adb devices -l` 返回空列表。没有重启已知会卡住的模拟器，没有重装或清空环境。当前目录也未安装课程服装 PNG 包和视频 `media/` 包，因此授权视频动态检查需要负责人使用既有批准素材包代跑；没有下载替代素材或配置 COS／云账号。

## 负责人代跑步骤

### A. 无需服装／视频包的合成语义与键盘回归

准备一台 API35 设备或可用模拟器，前后相机可为 None。安装本次生成的两个 APK 后运行：

```powershell
adb -s emulator-5554 shell am instrument -w -r `
  -e class "com.smartfitting.mirror.VideoPlaybackFlowTest" `
  com.smartfitting.mirror.test/androidx.test.runner.AndroidJUnitRunner
```

该类使用程序生成短片，不访问网络、不开启相机。预期 9 项通过；以设备输出的 `INSTRUMENTATION_STATUS_CODE=0` 为准。

### B. 既有授权视频、返回保留与大字体回归

向负责人领取并校验既有服装包和视频包，启动仓库现有 loopback MOCK：

```powershell
python tools/run_video_mock.py --port 8018 --catalog-db "C:/path/video-demo.sqlite3"
adb -s emulator-5554 reverse tcp:8018 tcp:8018
adb -s emulator-5554 shell am instrument -w -r `
  -e class "com.smartfitting.mirror.LicensedVideoFlowTest#fiveUpperReferencesAndTwoDressReferencesReallyRenderFromHttp,com.smartfitting.mirror.LicensedVideoFlowTest#pauseRotationBackgroundAndReturnPreserveOriginalAndOutfitWithoutAutoplay,com.smartfitting.mirror.LicensedVideoFlowTest#enlargedFontsAndOrientationKeepVideoControlsReachable" `
  -e video_test_host "http://127.0.0.1:8018" -e video_evidence true `
  com.smartfitting.mirror.test/androidx.test.runner.AndroidJUnitRunner
```

三项全部预期通过且无假设跳过。证据截图保存在测试 App 的本地 `evidence/` 输出目录，只用于复核，不提交人像、品牌素材或生成结果。若 `10.0.2.2` 出现连接提前关闭，沿用上述 ADB reverse＋127.0.0.1，不修改公共网络重试策略。

### C. 真实 TalkBack／键盘人工清单（当前未验）

1. 开启 TalkBack，进入视频列表。卡片一次读出标题、时长、来源和匹配依据；封面不单独朗读，下一焦点可到“来源与许可”，完整许可仍可打开。
2. 仅用键盘 Tab／方向键选择第二张卡片并按确认。焦点到播放／暂停；继续 Tab 检查下一条、结束体验、音量，方向键返回列表后仍在原卡片。
3. 播放后暂停，旋转及退后台再回来：产品页保持暂停，焦点在当前播放器；列表和隐藏试衣页不可进入。返回列表、返回试衣分别恢复原卡片和视频入口。
4. 在 0% 和非 0% 音量检查“音量＋当前数值”，确认 0% 静音且调整音量不会自行播放；触发加载失败后检查错误消息通知及“重试”名称。
5. 系统字体 130%／150% 各跑横竖屏，聚焦控件应自动滚入可见区域，文字和 48dp 高度不裁切。确认视频入口没有自动拍照、生成或开启相机。

真实朗读措辞以 TalkBack 实际输出为准，不能由 `contentDescription`／`RangeInfo` 断言替代。镜子现场、真实扬声器音效、真人／相机验证不在本项中。
