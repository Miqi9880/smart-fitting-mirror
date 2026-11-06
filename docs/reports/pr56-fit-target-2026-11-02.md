# 多件搭配的贴合调整目标：PR #56／#57复核

2026-11-02，接收yyyrrrxxx的功能提交`c84c13c`和配套测试`d45c9d7`，在#56统一交付，#57按已整合关闭。负责人补充修复与验证，长期Issue #7保持Open。运行代码基线`af377d7`，APK0.3.9-mock（versionCode12）。

## 行为与修复

帽子＋上衣＋长裤同时搭配时，展开“调整…贴合”，通过“正在调整的品类”选择当前搭配内的目标。切换不替换衣服；宽度、纵向尺寸、位置和“恢复本类默认”只作用于该类。未明确选目标前跟随当前有效选品，明确选择后在品类仍存在时保留；移除、裙装互斥和清空有有效回退／禁用。

目标只保留在当前游客内存，页面重建、后台和视频返回可恢复，结束／重连清理。主动保存开关仍仅保存按品类的非默认尺寸，不保存目标、人像或会话。未改默认尺寸、feature-tryon、视觉或HTTP合同；交互改善不代表真人轮廓精度改善。

复核补位：

- #57单独基于main，缺少#56的`fitTarget`和新控件签名而CI编译失败。保留原作者提交，整合进#56共同验证。
- 视频返回测试原先设置GONE面板内的Spinner，未布局触发回调就等待目标改变。改为先展开面板并等待布局，再选择，符合实际操作顺序。
- 下拉菜单使用标准下拉布局并明确48dp最小触摸高度；补130%／150%字体、320dp／700dp宽度的选项与文字不裁切检查。

## 实际验证

本机Windows、JDK21.0.10、Gradle8.11.1、SDK35、API35 x86_64模拟器；前后相机均None。48件透明服装包、7条授权视频包校验通过。仅本地MOCK／程序图／合成源，无真人上传或COS/Aidge调用。

构建、30项App JVM测试和lint通过：

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

原生模拟器最终9项全部通过，无跳过：

| 用例 | 数量／覆盖 |
| --- | --- |
| FitControlsTest | 3：多件目标／独立改值与重置／选品不抢目标／裙装互斥／移除清空／重建后台／结束重连；大字体与选项触摸高度；既有关键点开关和尺寸回归 |
| FitPreferenceIntegrationTest | 2：损坏标志降级；主动保存、页面重建和关闭保存边界 |
| OutfitPreviewTest.nativeOutfitKeepsOnePerRegionAcrossRotationAndCanClear | 1：本地PNG三层加载／互斥／重建／清空，预览为合成源 |
| LicensedVideoFlowTest | 2：7条授权片实际出帧；暂停／重建／后台／返回保留调整目标、原图、结果和搭配 |
| StandbyExperienceTest.guestStartsWithoutCameraShowsSyntheticStandingAndManualEndClearsAndReturns | 1：游客入口、合成站位、MOCK生成、结束清理并回待机 |

准备已安装的App与仪器APK、[服装包](../studio-outfits.md)和[视频包](../../backend/assets/videos/README.md)。服务均绑定电脑loopback，8010为普通MOCK，8018可用`tools/run_video_mock.py`启动。仅对本地模拟器转发：

```powershell
adb -s emulator-5554 reverse tcp:8010 tcp:8010
adb -s emulator-5554 reverse tcp:8018 tcp:8018
adb -s emulator-5554 shell am instrument -w -r -e class "com.smartfitting.mirror.FitControlsTest,com.smartfitting.mirror.FitPreferenceIntegrationTest,com.smartfitting.mirror.OutfitPreviewTest#nativeOutfitKeepsOnePerRegionAcrossRotationAndCanClear,com.smartfitting.mirror.LicensedVideoFlowTest#pauseRotationBackgroundAndReturnPreserveOriginalAndOutfitWithoutAutoplay,com.smartfitting.mirror.LicensedVideoFlowTest#fiveUpperReferencesAndTwoDressReferencesReallyRenderFromHttp,com.smartfitting.mirror.StandbyExperienceTest#guestStartsWithoutCameraShowsSyntheticStandingAndManualEndClearsAndReturns" -e studio_test_host http://127.0.0.1:8010 -e video_test_host http://127.0.0.1:8018 com.smartfitting.mirror.test/androidx.test.runner.AndroidJUnitRunner
```

过程保留：最初4项有1项隐藏控件操作失败；扩展检查又发现选项高度及视频网络问题，均未忽略。补最小高度后字体用例通过。`10.0.2.2:8018`下既有视频用例在播放后第二次推荐遇到OkHttp `unexpected end of stream`，电脑直连同接口200；改ADB反向转发后同一诊断用例及最终9项通过。未修改公共客户端重试策略，未宣称所有网络环境已修复。

## 交付与未验

0.3.9沿用applicationId和签名，可覆盖升级；不要求先卸载旧APK。负责人本机导出新版和通用最新APK，历史0.3.8保留；操作证据保存在本机输出目录，不提交品牌素材或人像。

真实TalkBack、镜子实际触摸／摄像头／人体贴合和性能未验，现场仍只由负责人执行。yyyrrrxxx模拟器暂不可用，本次由负责人代跑，不要求准备安卓手机；后续接视频页读屏语义／键盘焦点查漏，不重做本次目标选择、视频HTTP或播放器集成。
