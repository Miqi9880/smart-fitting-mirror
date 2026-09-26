# Android 首版验证记录

日期：2026-09-26。范围：T01 工程基线、T02 RGB 适配与开发诊断页。尚未完成企业硬件验收。

## 环境与结果

Windows 主机构建，JDK 21.0.10，Gradle 8.11.1，AGP 8.9.2，Android SDK 35。设备测试使用 API 35 / Android 15 的 x86_64 模拟器，配置模拟 RGB 摄像头；没有连接真实手机或企业深度摄像机。

| 检查 | 结果 |
| --- | --- |
| app:assembleDebug | 通过，生成并安装调试 APK |
| core-contracts:test | 11 项通过，含 13 份 JSON 样例双向映射 |
| core-vision:testDebugUnitTest | 4 项通过，含坐标、置信度、出框提示 |
| app:connectedDebugAndroidTest | 2 项通过，含停止/重建状态、RGB 推理及前后台恢复 |
| core-vision:connectedDebugAndroidTest | 2 项通过，含方向变换、原图无叠加和停止清理 |
| app:lintDebug / core-vision:lintDebug | 通过，0 Error；合计 23 个非阻断 Warning，主要涉及固定依赖版本和诊断页样式/国际化/绘制建议 |
| Python 合同检查 | 通过：OpenAPI、两份 Schema、13 样例、五类错误拒绝及本地文档链接 |
| 页面视觉检查 | 已检查竖屏与横屏；竖屏完整显示，横屏使用纵向滚动，保留原图比例 |

运行命令见 [Android README](../android/README.md)。本机 Central 域名返回 403，构建时使用文档中的 centralRepositoryUrl 参数；未关闭 TLS。CI 保持默认官方 Central。

设备测试曾发现：停止后重建 Activity，原始帧按钮恢复为可用。已修复按钮初始状态并重新通过测试。低置信度不再直接标记人体出框。

## 当前可交付

- 公共 DTO、图像坐标与帧所有权接口。
- 合成数据源、普通 RGB 摄像头与本地八点姿态适配。
- 原始帧查看、权限入口、停止清理、前后台恢复与诊断显示。
- 为两位协作者保留可构建的 core-network / feature-tryon 空模块及接入说明。

以下截图是明确标记的合成样例，不是人体识别精度或试衣效果证明：

<img src="images/vision-diagnostic.png" width="360" alt="合成关键点诊断页，显示八个点、镜面开关和清理按钮" />

## 仍需验证

- 在真实手机上验证全身关键点、左右对齐、抬手、转身、不同身高和人物离开。
- 记录真实设备的输出帧率、延迟、内存和发热；模拟器 FPS 不代表企业大屏性能。
- 获取企业设备与 SDK 后验证深度采集、RGB/Depth 对齐和连接恢复；当前 mask/depth 均为空。
- 云端试衣、服装渲染、视频推荐由对应成员实现，当前未接入。
- 8–12 小时稳定性、真实环境遮挡与素材适配仍未验证。

任务应在代码审阅合并、对应证据确认后更新状态，不能因生成了 APK 就把全部企业需求标记完成。
