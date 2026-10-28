# 视频卡片与播放：FakeVideoRepository 首个独立增量

2026-10-28，#7／企业 FR-VID-01／02／05（依据 8.2、10 与草案 docs/interfaces/video-recommendation-draft.md）。本页记录 yyyrrrxxx 侧首个可审阅小 PR：同一 App 内的视频数据消费、推荐卡片与播放组件。后端合同未定稿，本轮不接 HTTP、不改公共合同、不动 StandbyActivity 的 `home.video` 入口（仍为禁用，接入前需在本卡协调）。

## 用户操作（验收路径）

1. 评审直接启动独立宿主：`adb shell am start -n com.smartfitting.mirror/.video.VideoActivity`（未挂入口，见下）。
2. 列表页展示推荐卡片：标题、时长、横／竖屏、相关标签，并有醒目的“测试数据 · 程序生成短片，非授权内容”标识；底部“返回试衣”退出。
3. 只有点击卡片才播放；播放页有播放／暂停、下一条、返回列表与音量条；横竖视频均按比例完整显示（黑边适配，不裁剪）。
4. 播完显示“已播完”，可重新播放或下一条；到最后一条时“下一条”禁用并提示。
5. 无候选时显示“暂无相关视频…”与返回路径；加载失败显示可读原因与“重试”，重试后恢复列表；播放失败提示可读并可返回列表，不白屏。
6. 退后台、旋转、返回列表、返回试衣（结束）都会停止并释放播放器与挂起的短片生成；返回前台／旋转后从原位置恢复播放或暂停状态。不触碰现有选衣、贴合、预览与对比设置。

## 实现与边界

- 新增 `com.smartfitting.mirror.video` 包（app 模块）：
  - `VideoCatalog.kt`：`VideoItem／VideoQuery／VideoResult／VideoRepository` 与 `FakeVideoRepository`。召回为标签／关联服装至少一个维度匹配，按 videoId 去重，下架内容不返回；不足 5 条时如实空态，不重复凑数。默认目录 6 条合成元数据（1 条下架、2 条竖屏），全部 `SYNTHETIC_TEST` 标记，不把测试 URL 说成已授权内容。
  - `VideoPlayback.kt`：纯 Kotlin 播放状态机（IDLE／LOADING／PLAYING／PAUSED／COMPLETED／ERROR），含下一条边界、错误信息、音量（0–1，释放后保留），JVM 可测。
  - `GeneratedClips.kt`：MediaCodec＋MediaMuxer 程序生成 2–3 秒 H.264/MP4 测试短片（色块背景＋移动块＋“测试视频 TEST”字样），640×360 横屏与 360×640 竖屏，缓存于 cacheDir。不下载任何不明授权媒体。
  - `VideoPanel.kt`：卡片列表＋TextureView/MediaPlayer 播放器。卡片含测试标识与 contentDescription；状态文案走 accessibilityLiveRegion；控制条放入横向滚动，1.3／1.5 倍系统字体下按钮不裁剪。`releasePlayer()` 停播、取消挂起生成并释放 MediaPlayer。
  - `VideoActivity.kt`：独立评审宿主（manifest 注册、不导出、无 launcher）。onStop 统一捕获进度并释放播放器；旋转经 ViewModel 恢复 index／进度／播放状态／音量。
- 未接入 `home.video`（保持禁用）、未改 TryOnActivity／待机／感应／对比任何现有逻辑；未新增依赖；未改版本号（建议合并时由负责人决定 0.3.8-mock）。
- 留给后续：HTTP 合同定稿后接真实目录（沿用现有鉴权与临时会话）；≥5 条相关且可播放的授权内容到位后才验收完整推荐播放闭环（内容来源由负责人协调）；接入 `home.video` 前先在本卡说明。

## 验证

环境为 Windows、JDK21、SDK35、项目 Gradle Wrapper。输入全部为合成元数据与程序生成短片：不开摄像头、不连镜子、不访问网络、不用云账号。

```bash
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

结果：`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`、`:app:lintDebug` 全部通过（27 项 JVM 测试 0 失败，含 10 项既有体验时钟回归）。

环境说明：本机用户名含非 ASCII 字符，Gradle 测试 worker 从中文路径加载类报 ClassNotFoundException（既有测试同样受影响，属环境问题）。本轮通过 ASCII 联接点运行：`C:\sfm`→项目根、`C:\gradle-home`→`C:\Users\可之一\.gradle`（junction，不复制文件、不重下依赖）。后续本地跑 JVM 测试沿用该方式。

新增 JVM 测试 17 项：`FakeVideoRepositoryTest` 8 项（下架过滤、测试标记、单维度召回、服装关联召回、诚实空态、去重、一次性模拟失败恢复、横竖覆盖），`PlaybackControllerTest` 9 项（加载→播放、暂停／继续、下一条边界、播完重播、错误保 index、释放保音量、音量钳制、越界忽略、状态监听）。

新增设备测试 `VideoPlaybackFlowTest` 6 项：列表测试标识、点击→播放→暂停→继续→下一条→返回列表、空态与返回路径、模拟失败与重试恢复、退后台释放＋回前台恢复＋真实旋转（requestedOrientation）恢复、程序生成竖屏短片可被 MediaMetadataRetriever 读取（时长／360×640）。**本机模拟器启动挂起（WHPX），以上设备测试已编写但本地未运行**，需在可用模拟器或 CI 设备场执行；因此“列表→播放→…→释放”的模拟器交互本轮标为未实测，不写成已通过。

未验证：真实 TalkBack 朗读（未运行，仅做了标签／焦点／live-region 静态检查）、实体镜子与真人、真实授权内容播放。首次运行会即时生成短片，低端设备上首条点击可能有约 1–2 秒准备时间，界面显示“正在准备测试短片…”。
