# Android 模拟试衣闭环

本轮在已有视觉与模拟后端上增加网络状态控制、四点上衣叠加和首版交互。模拟服务没有真实 AI；静态结果上有 MOCK 标记。设备深度、分割、遮挡与真实人体效果另行验证。后续维护：core-network 由 haiyangniu191-design 接手，feature-tryon 由 lyf-bee 接手，app 由 yyyrrrxxx 接手。

## 启动并完成一次试衣

2026-10-28增量（0.3.6）：生成后在结果区点“查看原图”／“查看结果”一键切换，或点“并排对比”回到两张图。切换不上传或重新生成；结果未就绪时按钮禁用，旋转保留查看模式，结束／重连后清空。均显示未镜像原图方向，[操作与验证](reports/result-comparison-2026-10-28.md)。当前Aidge/COS仍暂停，按本地MOCK完成软件联调。

1. 按 [入门](getting-started.md) 配置 Android 与 Python。仓库根目录启动 `python -m backend --host 127.0.0.1 --port 8000 --mode success`（用安装过 requirements-dev 的虚拟环境解释器）。
2. 在 android 目录执行 ` .\gradlew.bat :app:installDebug`，从设备图标打开 App，默认进入“试衣联调”。硬件诊断仍可从页底进入。
3. 官方 Android 模拟器填写 `http://10.0.2.2:8000`；USB 真机先 `adb reverse tcp:8000 tcp:8000`，填 `http://127.0.0.1:8000`。地址只填 origin，不加 /api/v1。
4. 点连接服务，选上衣。默认合成输入不需摄像头权限；RGB 按钮会请求权限。绿色上衣仅可预览，拍摄按钮禁用。服务/素材失败会显示错误，可重试请求或重载服装。
5. 用平移/缩放/丢失恢复场景、暂停/单步/重放和镜面开关检查实时预览。没有可靠肩髋时隐藏衣服；图像和衣服在同一显示变换中镜像。
6. 点击“拍摄并上传生成”，上传当前未镜像且不含衣服/UI 的原始帧，等待模拟任务，查看原图与结果对比。结果方向保持原图方向，与镜面预览的左右可能不同，页面有说明。
7. 点击结束并清理，立即清掉本地图像和帧，等待服务确认远端清理。离线时会提示未确认，可以重试；不能把本地清理当成服务已删除。退出进程时仅尝试有界远端撤销，服务器 30 分钟 TTL 是后备。

HTTP 仅在 debug 构建中允许。此版本用于可信本机服务联调；release 禁止明文流量，需要配置 HTTPS 服务。会话凭证/照片不写入磁盘，不使用磁盘图片缓存，不转发凭证到不同来源图片地址。

## 成员的接入点

| 文件 / 类型 | 输入和职责 | 维护人 |
| --- | --- | --- |
| HttpFittingApi | 实现现有 FittingApi；真实 HTTP、受鉴权素材与结果下载；可配置 origin | haiyangniu191-design |
| FittingSession | StateFlow 状态；持有会话令牌及图像字节；控制上传/幂等重试/轮询/取消/结束 | haiyangniu191-design |
| TryOnView / TorsoWarp | 持有最新 FrameLease 与自有素材 Bitmap，肩髋四点透视形变、一次镜像 | lyf-bee |
| TryOnModel | Activity 重建时保留同一远端任务/选择，销毁时清理 | yyyrrrxxx |
| TryOnActivity / OwnedImage | 页面装配、权限、原图交接、图片解码/释放、前后对比与诊断入口 | yyyrrrxxx |
| CameraRgbSource / SyntheticVisionSource | 帧采集与原图；仍由 core-vision 提供 | Miqi9880 |

`FittingSession` 的公开方法在 UI 调度器调用。`generate(photo)` 接管照片所有权，上传结束/取消后关闭；页面不得同时清零或保存其字节。上传请求额外持有独立数组至 OkHttp 终止回调，避免取消协程时破坏仍在写入的请求。

创建任务时固定上传 ID、服装 ID 和幂等键。未知创建结果重试原 key，不重新上传/创建另一请求；最多三次自动尝试，失败给用户重试。限流遵守 Retry-After。后台暂停轮询，返回/旋转查询同一任务；取消使用服务端终态，完成先胜出则展示结果。结束递增版本并拒绝旧回调。

TryOnView 接管 `setAsset(garment, bitmap)` 的 Bitmap，并校验与素材声明尺寸一致；替换和 clear 时回收。submit 借用输入帧并保留自己的引用，绘制后释放临时引用；clear 释放最新帧。不要自行 recycle 来源帧。素材选择可在输入暂停时切换；当前是整件图像四点形变，不模拟袖子独立运动、真实布料或遮挡。

## 复现测试

常规构建/测试（android 目录）：

```powershell
.\gradlew.bat :app:assembleDebug :core-contracts:test :core-vision:testDebugUnitTest :core-network:testDebugUnitTest :feature-tryon:testDebugUnitTest
.\gradlew.bat :app:lintDebug :core-vision:lintDebug :core-network:lintDebug :feature-tryon:lintDebug
```

端到端仪器测试使用四个独立本地服务。仓库根目录开四个终端（下方 python 均指项目虚拟环境解释器），每个执行一行：

```powershell
python -m backend --host 127.0.0.1 --port 8000 --mode success
python -m backend --host 127.0.0.1 --port 8001 --mode failure
python -m backend --host 127.0.0.1 --port 8002 --mode delayed
python -m backend --host 127.0.0.1 --port 8003 --mode rate-limit
```

在已启动且配置摄像头的 API 35 模拟器上，从 android 目录执行：

```powershell
.\gradlew.bat -Pandroid.testInstrumentationRunnerArguments.fitting_test_host=http://10.0.2.2 :app:connectedDebugAndroidTest :core-vision:connectedDebugAndroidTest
```

未传 fitting_test_host 时，TryOnFlowTest 的四项真实 HTTP 用例会跳过，不得声称完整闭环通过；其他不依赖服务的仪器测试仍执行。测试只允许回环/官方模拟器主机地址，避免意外触发真实付费生成。USB 真机需转发 8000–8003 四个端口，参数改为 http://127.0.0.1；相机/人体效果仍须人工实测。

测试覆盖：成功与原图/结果清理、仅预览服装、模拟失败、同任务 Activity 重建/后台恢复、取消、429 重试、结束后不显示迟到结果；另有像素级渲染/整体镜像/暂停换衣/丢失隐藏/帧释放测试。测试完 Ctrl+C 停止各自服务。结果见 app/core-vision 的 build/reports/androidTests 及各模块 build/test-results。

可选截图证据：额外传 `-Pandroid.testInstrumentationRunnerArguments.fitting_evidence=true`，成功流程用例会暂停合成输入，滚动到预览/结果/结束位置，将截图存入设备应用的 external files/evidence 目录。仅在测试合成输入中使用；这不授权保存真人照片。结束后可用 adb pull 导出，清空 App 测试数据会删除该目录。

## 目前限制

配置只在当前进程保留；进程死亡后重新进入会话，不持久化私有照片或任务令牌。恢复旧任务到跨进程存储未实现。网络库限制图片下载大小，解码限制 1600 万像素，但还没有企业大屏内存/FPS 基准。页面是可交接的首版，仍需 yyyrrrxxx 在真实大屏上改进布局和交互；错误恢复和生命周期改动应保留回归用例。
