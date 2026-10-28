# 原图与生成结果一键切换

2026-10-28，企业 FR-VTO-06（P0）：原文要求“支持原始画面/试衣结果一键切换或滑杆对比”。本轮按负责人明确安排，由 Miqi9880 委托 Codex 实现同一结果页的一键切换；yyyrrrxxx继续视频卡片与播放，不重复实现本项。

## 用户操作

1. 连接本地 MOCK 服务，选择可生成上衣，用获准静态照片或显式选择的合成样例发起一次模拟生成。云端 Aidge/COS仍因费用暂停，本轮不恢复付费链路。
2. 在原有结果区点击“查看原图”或“查看结果”，同一显示区域展示对应图片；“并排对比”恢复左原图、右结果。单张查看使用更大的显示区域，两张图均按自身比例居中完整显示，不裁剪或额外镜像。MOCK标识继续保留。
3. 结果尚未就绪时仅能查看原图，结果／并排按钮禁用；没有图片时三个按钮均禁用。图片解码失败显示可读提示，不保留上一张结果。
4. 查看模式在当前进程内随旋转／返回页面保留；结束或重连恢复默认并排模式。下一次生成会清掉上次结果，待新结果就绪后再按所选模式查看。点击查看只改变显示，不重新拍摄、上传、创建或查询生成任务。
5. 退后台时释放结果区持有的解码图片，返回后从同一会话内存重新显示；结束会话清除原图／结果与解码图。不会把图片、查看模式或会话写入新的磁盘缓存。

## 实现与边界

- 新增独立 `ResultComparisonPanel`，复用两个 `OwnedImage`，保持既有 `fitting.before`／`fitting.after` 入口。切换仅调整可见性与布局，不新建第三张解码图，也不反复解码。
- `TryOnActivity`只替换结果区装配和清理入口；`TryOnModel`仅新增非图像查看模式。不修改后端或公共合同，不影响视频组件、照片导入、实时预览的全屏／缩放／关键点和近似头部保护。
- `OwnedImage`忽略已被替换／清除来源的迟到错误，避免旧解码失败覆盖新来源状态。原图字节仍由`FittingSession`持有，结果区不擅自清零控制器拥有的数据。
- 同一MOCK会话第二次生成的联调发现空闲连接断开问题：`HttpFittingApi.upload`沿用已有结束会话入口的方式，先清理本客户端空闲连接再开始上传，不中断活跃请求，不新增自动POST重放或改变生成幂等。已在#5说明局部补位，后端成员原有调用治理／视频库任务保持。
- 软件版本为 **0.3.6-mock / versionCode 9**，沿用本项目已有调试签名，可覆盖旧联调版安装。名称中的mock是联调版本标签，实际结果继续以服务返回的`is_mock`标识为准；当前验证只用MOCK。

## 验证

环境为Windows、JDK21、SDK35、Gradle8.11.1、Android15/API35 x86_64模拟器，测试输入由程序生成，不开摄像头、不连接实体镜子、不读取云密钥。MOCK地址严格限制为本机8010，使用独立测试目录数据库。

```bash
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :core-network:testDebugUnitTest :core-network:lintDebug
```

构建、测试APK、app／core-network lint与core-network的19项JVM测试通过。新增空闲连接回归在修复前按预期失败（复用了旧连接，序号1而非0），修复后通过，同时验证未确认POST不被传输层自动重放、调用方原图所有权不变。

Gradle设备测试连接层在执行用例前超时，实际0项，不计作测试通过；随后覆盖安装同一构建的APK／测试APK，直接用AndroidJUnitRunner执行原生测试：

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.smartfitting.mirror.ResultComparisonTest,com.smartfitting.mirror.ResultComparisonIntegrationTest,com.smartfitting.mirror.StaticImagePanelTest,com.smartfitting.mirror.PreviewDisplayTest \
  -e studio_test_host http://10.0.2.2:8010 -e comparison_evidence true \
  com.smartfitting.mirror.test/androidx.test.runner.AndroidJUnitRunner
python tools/check_contracts.py
git diff --check
```

Android命令在android目录执行，Python检查在仓库根目录执行；以上换行是shell示例，Windows可整理为一行。直接运行结果为**13项通过、0跳过**：5项新增对比测试与8项既有导入／预览回归。第一次完整联调第二次生成遇到旧空闲连接失败，修复后在相同服务的5秒空闲关闭条件下主动等待6秒，再次上传生成、切换和结束全部通过，没有通过放宽服务保活时间绕过问题。

覆盖未生成／原图待结果／结果可用、快速切换不重新解码、非对称像素验证方向及相同显示区域、解码失败／换图／迟到回调、130%／150%字体和320dp／700dp宽控件、同一MOCK任务旋转／后台恢复、下一次生成不残留旧图、结束时控制器字节清零与视图Bitmap回收。检查了模拟器竖屏与横屏截图，截图仅含程序生成输入和明确MOCK结果，保留本机而不提交Git。检查按钮文字、选中状态和状态通知，不冒充真实TalkBack朗读。

APK0.3.6与本机原0.3.5调试包的签名证书摘要一致，支持覆盖安装；本轮只在模拟器安装验证，尚未给离线镜子安装。合同／本地文档链接与diff检查通过。

本轮没有镜子触摸／真人或付费生成验收，也没有真实TalkBack朗读记录；相应现场检查仍只由Miqi9880执行。一键切换的软件交付不代表待机、Depth/mask、推荐视频或整份企业P0已经完成。
