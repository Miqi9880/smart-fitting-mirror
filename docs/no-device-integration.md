# 无设备联调：负责人交付与共同验收

本轮由 Miqi9880 提供可控人体帧、开发诊断页和异常流程验证。source 始终为 mock；合成几何不能证明人体识别精度、真实设备性能或试衣效果。公共 JSON / Kotlin 帧格式不变。

## 在模拟器上操作

打开应用，选择“合成样例”。第一个下拉框选择场景，第二个选择图像画幅。点击“暂停”固定画面，“单步”推进一个样本，“从头重放”回到样本 0。播放按每 100 毫秒推进一个样本调度，不承诺实际设备恒定帧率。

场景每 80 个样本循环。重放只重置场景样本号；同一会话内 frame_id 继续递增，timestamp_ms 仍为单调时钟。界面更换场景/画幅会开启新会话，旧截图回调失效。暂停不结束会话，可以查看暂停位置的原始图。

后台恢复或 Activity 重建会保留场景、画幅、播放选择和镜面选择，开始新会话并从样本 0 重放；用户已停止时不自动启动。图像画幅与屏幕方向分别测试，不把旋转屏幕等同于改变输入图像。

## 共同场景与预期

| 编号 | 场景与操作 | 视觉输出 | 渲染方验收 |
| --- | --- | --- | --- |
| V01 | 静止人体，暂停 | tracking，八点固定，四个躯干锚点可靠 | 一件上衣位置和大小稳定 |
| V02 | 左右移动，样本 0 / 20 / 60 | 中心 / 向图像右移 0.1 / 向左移 0.1；点间距离不变 | 衣服整体平移，不额外缩放 |
| V03 | 人物大小变化，样本 0 / 40 | 以 (0.5, 0.45) 为中心，几何倍率 0.7 / 1.2 | 衣服随躯干缩放；不显示距离测量结果 |
| V04 | 缺少左肩 | partial；left_shoulder=null，其余七点有效 | 隐藏衣服，提示调整站位，不沿用旧衣服位置 |
| V05 | 人物丢失 | lost；八点均为 null | 隐藏衣服并清理旧渲染状态 |
| V06 | 丢失与恢复，样本 0 / 20 / 40 / 60 | tracking → partial → lost → tracking | 隐藏后可重新定位，无旧帧闪回 |
| V07 | 竖图/横图，镜面开关，旋转屏幕 | 图像分别 480×640 / 640×480，内部均竖正且未镜像 | 背景、关键点、服装共用等比变换，仅显示时翻转一次 |
| V08 | 查看原始帧后立即切换源或结束会话 | 照片绑定采集时 session_id / frame_id；旧会话结果被清理 | 不弹出旧照片，不把旧结果更新到新页面 |

所有场景的 mask、depth、distance_m 都为空，standing_hint=unknown；缺点场景不冒充深度测距或真实遮挡检测。默认“综合演示”为左右移动，在周期样本 60–69 丢失。

## lyf-bee：渲染接入

在 feature-tryon 实现 TryOnRenderer，消费 FrameLease<BitmapPixels> 与 Garment。按 V01–V07 验证衣服和背景的关系，Miqi9880 提供的诊断骨架线不是衣服效果。

开发调试代码可在主线程精确定位样本，不必等待真实人体摆出姿势：

```kotlin
val source = SyntheticVisionSource()
source.configure(SyntheticSettings(SyntheticScenario.RECOVERY, SyntheticViewport.LANDSCAPE))
source.setPlaying(false)
source.start(sessionId, { borrowed ->
    renderer.submit(borrowed, selectedGarment)
}, { status -> /* 展示数据源状态 */ })
source.seekTo(20) // partial；接下来可依次定位 40、60，再回到 0
```

回调只借出帧；异步消费者返回前 retain，用完 release。不要释放没有 retain 的引用，不要自行 recycle Bitmap；渲染器只保留最新待处理帧。结束时 clear 渲染器，再 stop/close 数据源。

## haiyangniu191-design：网络接入

在 core-network 实现 FittingApi，并连接 T03 模拟 HTTP 服务。captureOriginal 返回的 CapturedPhoto 提供 PNG/JPEG 字节、MIME、宽高、session_id 和 frame_id；合成源返回 PNG，RGB 源返回 JPEG。

收到照片后，调用者持有至上传完成或放弃，再 close；不要在上传结束前清理。先以暂停的竖图/横图各跑一次上传，检查 MIME 和尺寸。生成状态需要覆盖成功、失败、取消、超时与结束会话后的迟到结果；模拟结果保持 is_mock=true。服务地址及素材由后端同学提供，不能使用合同样例中的 .invalid 占位地址。

## 首次全链路联调的就绪条件

| 内容 | 负责人 | 当前证据 / 后续工作 |
| --- | --- | --- |
| 合成数据与诊断控制 | Miqi9880 | 见 [Android 验证记录](android-validation.md)；只能证明数据源和诊断页 |
| 透明上衣素材及锚点 | haiyangniu191-design + lyf-bee | 按 [T03](https://github.com/Miqi9880/smart-fitting-mirror/issues/4) 交付并共同确认 |
| 服装渲染 | lyf-bee | [T05](https://github.com/Miqi9880/smart-fitting-mirror/issues/6)，负责人补位基线待交接 |
| 产品页面与装配 | yyyrrrxxx | [T06](https://github.com/Miqi9880/smart-fitting-mirror/issues/7)，接手首版后继续验收与改进 |
| 模拟服务与 Android 网络 | haiyangniu191-design | [T03](https://github.com/Miqi9880/smart-fitting-mirror/issues/4)、[T04](https://github.com/Miqi9880/smart-fitting-mirror/issues/5)，模拟服务已在 PR #9 合并，Android 网络基线待交接 |
| 无设备端到端演示 | yyyrrrxxx 集成、全员参与，Miqi9880 组织 | 上述依赖满足后按 [T06](https://github.com/Miqi9880/smart-fitting-mirror/issues/7) 验收 |

全链路目标：进入 → 选上衣 → 随合成人体移动 → 拍摄原图 → 模拟生成 → 对比 → 结束。记录提交号、模拟器配置、服务启动方式、一条正常流程和一次失败恢复。只有数据源通过测试，不能勾选“服装渲染通过”或“端到端已完成”。

有可用人像视频或实际设备后，再补真实人体移动、抬手、转身、出框、识别精度和性能验证；企业深度硬件继续保留为后续任务。
