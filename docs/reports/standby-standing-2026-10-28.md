# 待机、游客体验与站位引导

2026-10-28，Miqi9880明确委托Codex补位企业FR-001／002／004和FR-VTO-01的软件部分。版本 **0.3.7-mock / versionCode10**，公共HTTP0.3.0和视觉帧0.2.0不变，后端及视频模块未改写。

## 现在怎样使用

1. 从应用图标进入新的`StandbyActivity`待机页。首次使用在“服务连接设置”填电脑服务地址；模拟器用`http://10.0.2.2:8000`，镜子用同网电脑地址或已建立ADB转发的回环地址。仅服务origin和相机通道作为设备配置保存，不保存Token、照片、关键点或游客会话。
2. 点击“游客开始试衣”即可进入同一App试衣页并连接匿名会话，无需登录。进入不会自动开相机、拍照、上传或生成；实时RGB预览仍由用户主动点击。照片导入、原图／结果切换、全屏／缩放和贴合设置继续使用。
3. 可选开启“本地人体感应”，授权后只在待机页前台检测姿态并显示欢迎提示，不上传或保存人像。初次启动保持关闭，感应意愿仅在本进程内保留；离开待机页停止取流，本进程内已开启的模式回到前台可恢复，进程重启后不自动开启。可选相机通道，不固定Gemini2的设备ID。
4. 无设备时点击“合成引导演示（非真人）”观察欢迎状态，或在试衣页点合成样例观察站位提示。合成内容有明确标记，不是人体或深度实测。
5. 试衣页给出头部／躯干／腿脚缺失、越出画面、未居中或入镜基本合适的提示。RGB没有可靠距离，显示“尚未测得距离”，不根据人体大小编造米数或前后移动。
6. 点击“结束并清理”或触发超时后，清掉当前预览、搭配、原图／结果及临时导入，回到待机。全屏返回键先退出全屏，游客页再按返回键则结束体验。设备诊断留在待机页，游客试衣中不跳入另一个独立相机诊断流程。

主页展示试衣、AI试妆、视频推荐三个位置，**当前只有试衣入口可用**；另两项明确禁用并标为暂不可用。试妆等待面部组联动，视频由yyyrrrxxx按原任务实现，不能把这些位置的存在当作FR-003全部交付。

## 超时和清理约定

原文没有固定秒数，本轮在独立`GuestTimeouts`集中设定软件默认值，不冒称企业已经指定：

| 条件 | 当前行为 |
| --- | --- |
| 连续有效新帧未检出人体30秒 | 结束当前体验；短暂丢点有宽限 |
| 5分钟没有用户操作 | 结束；视频帧、跟踪成功和网络轮询不刷新此计时 |
| 本地会话达到30分钟 | 即使仍有操作也结束，与当前服务会话上限一致 |
| 断流、停止预览、合成暂停或仅有不充分的局部点 | 不推断人已离开；新帧不新鲜时清除无人连续计时，无操作／会话上限仍生效 |

“未检出人体”是姿态输入的候选判断，不是现实中确定无人；遮挡／光线等影响仍需现场确认。重新识别到人或不确定输入会打断离开计时。旋转保留同一体验时钟，后台不能靠重新进入重置时限；恢复页面时先检查到期并同步清理，再启动预览。Android冻结／杀进程不承诺后台定时器准点执行；恢复时检查单调时钟，进程死亡不恢复图片，服务原有TTL继续兜底。

游客退出先同步停止源并清除本机临时内容，再确认服务端结束。待机页最多等待5秒让下一次游客开始恢复可用；未得到确认时明确显示“服务端清理暂未确认”。不把本地清理称作远端已删除；现有控制器销毁时的有界撤销和服务TTL保持，不自动重放生成。技术测试直接启动`TryOnActivity`时保留原有关闭状态页；产品图标和游客流从待机页进入。

## 站位与深度边界

`StandingGuide`只输出不含图像的枚举／距离反馈。至少两个可靠躯干点才认作有人；头部参考点、肩髋膝踝可靠、位置／顺序合理且居中时才给“基本合适”。这是二维关键点近似，不保证身体轮廓或头顶／鞋底的像素级完整，不替代mask或实际深度。

距离必须同时具有本帧深度元数据和调用方明确的对齐深度可用标志；试衣调用方还核对当前帧像素的深度元数据一致。只有显式提供现场标定范围才输出前进／后退；默认RGB／合成路径不传该范围、距离保持未知。算法单测中的模拟深度仅检查软件分支，**没有接入Gemini2深度SDK、得到真实距离或完成RGB-Depth标定**。

## 代码与协作

- `StandbyActivity`／`StandbyModel`：待机欢迎、明确启停感应、一键游客入口和设备连接设置。
- `GuestExperienceClock`：独立单调时钟状态机；`TryOnModel`保留时钟和纯标量反馈，`TryOnActivity`负责源／图像清理与返回。
- `StandingGuide`：可在JVM验证的站位判定；`StandingGuidePanel`和全屏源摘要显示反馈。
- app新增JVM测试依赖及10项时钟测试，Android CI增加`:app:testDebugUnitTest`，避免只编译不执行这些测试。

yyyrrrxxx继续视频卡片／播放，不重做待机、超时和站位。本轮主页的`home.video`待其增量接入，先协调当前游客／返回流程再改入口。后端调用治理和lyf-bee的渲染诊断归属保持；全部镜子／真人验收仍只归Miqi9880。

## 实际验证

环境：Windows、JDK21、SDK35、Gradle8.11.1、API35 x86_64模拟器。使用程序生成照片、合成姿态、本机8010 MOCK；8014为独立MOCK清理延迟10秒的测试服务，未使用任何云提供方或密钥。所有原生测试均不点击RGB感应／RGB相机，不打开摄像头。

在仓库根目录分别启动两个测试服务（先安装backend/requirements-dev.txt并安装获准的运行素材）：

```bash
python -m backend --host 127.0.0.1 --port 8010 --provider mock --catalog-db data/standby-success-test.sqlite3
python tools/run_standby_mock.py --port 8014 --catalog-db data/standby-delayed-cleanup-test.sqlite3
```

第二个工具仅绑定回环地址且固定MOCK，不提供云启用选项；它刻意延迟并拒绝DELETE确认，用来验证“未确认”提示，不用于正式部署。

```bash
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :core-vision:testDebugUnitTest :app:lintDebug :core-vision:lintDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.smartfitting.mirror.StandbyExperienceTest,com.smartfitting.mirror.ResultComparisonTest,com.smartfitting.mirror.ResultComparisonIntegrationTest,com.smartfitting.mirror.StaticImagePanelTest,com.smartfitting.mirror.PreviewDisplayTest \
  -e studio_test_host http://10.0.2.2:8010 -e standby_failure_host http://10.0.2.2:8014 \
  -e standby_evidence true com.smartfitting.mirror.test/androidx.test.runner.AndroidJUnitRunner
```

Android命令在android目录执行，使用项目Wrapper或同版本Gradle；Windows可将换行整理为一行。没有传本地测试地址时相关用例会跳过，不能称闭环通过。

构建、app／core-vision lint和41项受影响JVM测试通过（app10、core-vision31，其中新增站位7项）。18项原生用例通过、0跳过：5项待机／游客增量、13项原图切换／导入／全屏既有回归；包含真实等待30秒的合成LOST退出、虚拟推进无操作期限后的清理、正常结束确认和10秒MOCK清理延迟时的5秒未确认返回。检查130%／150%字体、横竖屏关键按钮可滚动抵达及文字高度；截图只含合成数据，保留本机不提交Git。最后补设备配置持久化、恢复时同步检查到期及状态栏对比度后，重新构建／lint／JVM检查并针对性重跑5项待机／游客用例，全部通过、0跳过；同时验证新Model读回设备配置但不启用感应，正常结束不误报未确认。

未做：镜子实际触摸、真人进场1秒提示、RGB站位质量、真实Depth／距离标定、mask／遮挡、真实TalkBack朗读和8–12小时长稳。无操作5分钟／会话30分钟以单元测试及虚拟时钟触发原生退出检查，没有冒充现场等待这些时长；本轮没有COS/Aidge调用。上述条件仍列企业缺口，不把软件增量记作所有P0已完成。
