# 静态照片输入与同一页面联调

原始盘点由 yyyrrrxxx 于 2026-10-23 提交；2026-10-28 按负责人明确授权由 Codex 接手修正 PR #38／#39。原盘点基于旧代码，本文以 PR #38 修复提交 `fbd2be6` 和当前 main 为准，替代“尚无提供方／51件／首次闭环待做”的旧结论。

## 当前事实

- `backend/tryon_provider.py` 已有 Aidge/COS 适配；10/23 已完成标准 CG HTTP 验证和镜子 App 真人生成，[历史记录](mirror-real-generation-2026-10-23.md)仍有效。10/24 因 COS 外网流量费用禁用 COS，真实云链路暂停，见[暂停边界](cloud-pause-2026-10-24.md)。本轮未恢复服务、读取密钥、上传真人或提交付费任务。
- 当前 studio 目录是 **48件、其中12件可生成上衣**；三件连帽卫衣已下架，圆领卫衣保留。图片不随 Git 分发，新环境按 `tools/install_studio_assets.py` 和素材说明安装负责人提供的运行包，缺素材应明确报缺失。旧51件包不能作为现行目录数量。
- 静态输入作为无相机时的辅助入口，复用同一 `FittingSession` 上传／任务／结果对比／结束清理。它不是企业人体感知、真实深度／mask或遮挡验收的替代。
- 获准 CG 曾由负责人提供并完成历史真实验证；它不进入仓库。当前软件验证仅用程序生成色块图，无须再索取真人图或重跑首次付费闭环。本地模型尚未部署，不能称为已替代云服务。

## 使用方法

1. 启动本地 MOCK（Python3.12，仓库根目录）：

   ```bash
   python -m backend --host 127.0.0.1 --port 8000 --provider mock
   ```

   电脑服务地址是 `http://127.0.0.1:8000`，Android 模拟器填 `http://10.0.2.2:8000`。实际镜子用同网电脑地址，网络与现场操作由 Miqi9880 负责。MOCK 不需要云账号、GitHub 令牌或供应商密钥。

2. 在试衣页连接服务，选择可生成上衣。无需启动 RGB 摄像头或合成预览，静态入口独立可用。
3. 点击“选择照片（仅本机预览）”，从系统文件选择器选获准的 JPEG/PNG。**选图只做本机处理，不调用生成，也不上传**。取消选择不生成任务，错误文件显示导入失败并允许重选。
4. 检查预览方向与内容；可重选或“清除待上传照片”。确认后另点“上传所选照片生成”，此时才向页面上方的服务发送归一化 JPEG，并进入现有生成流程。测试必须连接 MOCK；真实提供方仍暂停，不能擅自启用。
5. 结果区显示未镜像原图与结果，并明确标记 MOCK。当前只生成所选单件可生成上衣；裤裙帽可参与实时搭配预览，不代表整套 AI 生成。
6. 点击“结束并清理”撤销会话与原图／结果访问。待上传照片在换图、清除、旋转、退后台或结束时清除，**旋转／返回后需重新选择**；不会删除相册／文件中的用户源图。离开页面清除的是待上传内容，不冒充已经上传任务的取消，已提交任务仍使用现有取消／结束流程。

## 输入边界与实现

`TryOnActivity` 通过 `OpenDocument` 接收 URI，`StaticImagePanel` 在 Activity 中持有待确认图片和读取任务，`StaticImageInput` 负责离线归一化；不持久化 URI 权限、不复制到磁盘、不保存到 ViewModel。`FittingSession.generate(CapturedPhoto)` 接收照片所有权，拒绝或结束后按既有流程释放。

- 原始输入有界读取到 10 MiB + 1 字节，超限立即拒绝，而非全部读取后再判断；JPEG/PNG 源图最多1600万像素，先检查尺寸再解码。
- 处理 EXIF 的全部八种旋转／翻转方向，最长边缩至2048且保持比例；透明 PNG 转白底，输出 JPEG，不复制原 EXIF/GPS。不会把镜面开关、衣服层或关键点叠加进源图，也不会额外镜像一次。
- 选图／归一化不进行网络上传。明确上传使用当前控制器即时状态，拍摄与导入不能并发提交；已选照片被消费后不能重复点击再次发送。
- 取消／换图关闭当前输入流、取消任务并使旧回调失效，未移交的照片释放；退出清除本机预览持有的图像。不声称系统解码器、系统缓存或云端内存已被物理安全擦除。
- 保留当前 PreviewPanel、全屏／缩放／拖动／恢复全景、停止／重试、关键点、近似头部保护与贴合参数。不使用旧版整份 Activity 覆盖 main。

当前可生成上衣：

| 款式 | 当前 ID |
| --- | --- |
| T恤 | `studio_black_tee`、`studio_blue_tee`、`studio_red_tee` |
| 衬衫 | `studio_oxford_shirt_01`、`studio_oxford_shirt_06`、`studio_oxford_shirt_64` |
| Polo | `studio_pique_polo_00`、`studio_pique_polo_09`、`studio_pique_polo_69` |
| 圆领卫衣 | `studio_crew_sweatshirt_01`、`studio_crew_sweatshirt_09`、`studio_crew_sweatshirt_69` |

## 2026-10-28 实际验证

环境：Windows、JDK21、SDK35、Gradle8.11.1、API35 x86_64 无相机模拟器；本地独立数据库和 `--provider mock`，测试端口8010。恢复项目原有 ANDROID_USER_HOME 调试签名后覆盖安装，未卸载清空旧 App 数据。原来一次因测试 IntentFilter 未匹配 MIME 而未拦截选择器的失败已修正，以下结果以最终重跑为准。

```bash
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest \
  :core-contracts:test :core-vision:testDebugUnitTest \
  :core-network:testDebugUnitTest :feature-tryon:testDebugUnitTest \
  :app:lintDebug :core-vision:lintDebug :core-network:lintDebug :feature-tryon:lintDebug

gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.smartfitting.mirror.StaticImageInputTest,com.smartfitting.mirror.StaticImagePanelTest,com.smartfitting.mirror.PreviewDisplayTest,com.smartfitting.mirror.PreviewIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.studio_test_host=http://10.0.2.2:8010

python tools/check_contracts.py
git diff --check
```

使用项目 Wrapper 或相同版本的 Gradle；命令中的换行符是 shell 示例，Windows 可逐行改为一行执行。

结果：APK与测试APK构建成功；四模块89项 JVM 测试无失败；上述 lint 通过；13项模拟器测试全部通过、0跳过；合同／本地文档链接和 diff 检查通过。

模拟器覆盖：EXIF八方向、源文件不变／GPS不复制、透明白底／缩放、超字节／超像素／坏格式拒绝、选择不提交及明确提交一次、取消读取与重选、取消选择、无相机 MOCK 生成、旋转／后台／结束清理，以及已有全屏／缩放／三件搭配回归。新增面板在150%字体下以320dp与700dp宽检查触摸目标和文字高度，检查按钮文字、预览说明和状态可访问通知。

限制：系统选择器回调用测试 IntentFilter 拦截并返回程序生成文件，**没有**宣称所有厂商文件选择器、真实 TalkBack 朗读／完整焦点顺序、镜子现场或付费链路本轮已验收。面板大字体自动检查也不等于全 App 无障碍已完成。

## 交接

PR #38／#39 的本轮修正由负责人委托 Codex 完成，不记作 yyyrrrxxx 本人已完成这些补测，也不让她重复修改。后续 Android 主任务是企业 FR-VID-01／02／05 同一 App 推荐卡片与视频播放：先 FakeVideoRepository 和程序生成／获准短片，合同确定后再接 HTTP；所有镜子／真人／现场确认继续只由负责人承担。详见[当前任务](../tasks.md)和[#7](https://github.com/Miqi9880/smart-fitting-mirror/issues/7)。
