# 授权参考视频与同一App播放器集成

2026-10-29，企业FR-VID-01／02／05、FR-003与12.1。版本0.3.8-mock；HTTP0.4.1增加公共媒体流，视频推荐JSON仍兼容0.4.0，视觉合同仍0.2.0。生成默认MOCK、COS/Aidge暂停，本轮没有读云端密钥、拍照、开相机、连镜子或调用收费API。

## 交付及归属

yyyrrrxxx的PR #46～#50原始提交保留作者记录，提供FakeVideoRepository、纯Kotlin播放状态机、VideoPanel、合成短片、内部宿主及测试首稿。负责人接手后移动源码到正确video包目录，修正编码器输入size为0造成超时、测试旋转方向已相同时不会重建的问题；PR #45的一行video占位文件不导入，它与源码目录冲突。历史首稿见[原报告](video-card-2026-10-28.md)，不能把后续补修记作成员本人实测。

负责人本轮完成7条授权片的取得／来源核对、转码／封面／清单／安装器、HTTP媒体路由、共享会话适配、待机与试衣页入口、封面和来源许可按钮、默认静音及后台／结束清理。HTTP适配复用PR #51客户端和PR #52后端，不另建Token或游客会话，不向媒体URL附带游客Authorization。原图／结果／搭配仍由原ViewModel保存。

## 使用和部署

先安装[独立本地视频包](../../backend/assets/videos/README.md)，运行现有MOCK后端。待机点“相关视频”，或进入试衣、选好服装后点“当前搭配相关视频”；主页进入只创建游客会话，不启动摄像头、不生成。点击卡片才播放，提供暂停／继续、下一条、音量、返回列表、返回试衣及结束体验。

现有5条上衣参考与2条连衣裙参考共7条。相关性来自明确的品类／颜色等标签，是穿搭参考，**不承诺同款商品、不代表人物／品牌背书、没有AI语义提取**。上衣上下文的5条候选全部逐条播放；换连衣裙能召回两条裙装片，颜色也可能命中其他片。其他品类／任意上下文未保证都满5条；不足如实显示，缺媒体为空，不用合成片冒充。

7条来源页、作者、许可、原始下载SHA256和处理后尺寸／时长／哈希保存在manifest与catalog，媒体不入Git。核对[Pexels官方许可](https://www.pexels.com/license/)，片段前至多12秒，ffmpeg转H.264/yuv420p／24fps／最长边1280、不裁切，去音轨和元数据；1秒位置生成JPEG封面。逐条ffprobe及首帧接触表人工检查，不只检查文件扩展名。

公共视频／封面GET和HEAD按已审核目录解析固定文件，支持Range／206／416，不提供任意文件，也不提供游客原图／结果。元数据接口仍鉴权／限流／no-store；缺失、未上架或未核验媒体404。公共文件本来无游客数据，结束会话不会使其失效，App仍会停止并释放播放资源。生产部署用HTTPS；loopback工具仅MOCK，不作为生产公网配置。

## 生命周期与异常

- 复用TryOnActivity和FittingSession，视频覆盖页不触发第二套游客流程；返回保留原图、结果、搭配和贴合设置。
- 退后台／旋转释放MediaPlayer、Surface、封面下载、弹窗及查询；恢复已选视频为暂停，默认不自动发声。音量／位置只留当前游客内存。
- 查询失败、503、空库和旧服务404有返回／显式重试，不回退Fake。后台取消的查询保留“已暂停，请重试”，晚到响应不恢复旧列表，不自动重放POST；429遵守Retry-After。
- 更换搭配比较请求快照，丢弃旧结果；会话401、无操作超时或手动结束清空列表和播放器。持续播放的帧不延长游客无操作计时，只有用户操作才计入。
- 内部VideoActivity不导出，只供合成组件仪器测试；产品不导航到它。GeneratedClips只有显式测试元数据且没有HTTP URL时可用。

## 实际验证

环境：Windows、Python3.12、JDK21、Gradle8.11.1、SDK35、API35无相机模拟器emulator-5554；本地MOCK8018及独立故障夹具，原正常8010服务保留。所有图片输入为程序生成像素，视频为上述授权内容。命令中的fault工具只限127.0.0.1。

```powershell
python tools/install_video_assets.py --check
python tools/check_contracts.py
python -m pytest backend/tests -q --basetemp="新建且不存在的本轮临时目录"
# 在android目录：
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

实际结果：

| 检查 | 结果及边界 |
| --- | --- |
| 固定媒体包 | 7条MP4和7张JPEG均通过仓库清单哈希／大小校验；逐条ffprobe和首帧图检查通过 |
| 合同／链接 | OpenAPI3.0.3、2份JSON Schema、21份样例、19个拒绝案例及本地文档链接通过；git diff --check通过 |
| Python全量 | 161项通过（23.76秒）；随后加固安装器媒体目录软链接拒绝，定向4项通过、1项因Windows不允许创建软链接跳过。该项在Linux CI仍执行，不能把本机跳过写成通过 |
| Android构建／静态 | assembleDebug、assembleDebugAndroidTest、app:testDebugUnitTest共30项、app:lintDebug通过；无新依赖 |
| 视频仪器测试 | 9项真实HTTP／生命周期／故障用例与6项原组件用例最终全部通过，共15项不同用例，含7条真实帧、封面加载、空库／不足、503／401、查询后台取消及晚到丢弃、媒体不可用、手动重试／下一条恢复、默认静音、超时释放、旋转／后台暂停恢复、返回保留图片／搭配、主页无相机入口、1.3／1.5字体及横竖屏 |
| 既有UI回归 | StandbyExperienceTest5、ResultComparisonTest4、ResultComparisonIntegrationTest1、StaticImagePanelTest4、PreviewIntegrationTest1，共15项通过；与视频合计30项不同仪器用例 |

首轮仪器检查确实发现并修复：合成编码器入队size为0；旋转测试设置已是当前方向导致无重建；换装测试直接改controller与实际Spinner／搭配快照不同步，改用实际选衣操作并等待快照。后续15项视频组合中14项通过，媒体503“一次失败”夹具被MediaPlayer内部GET重试透明恢复，不能证明错误UI；夹具改为固定一条视频404、下一条正常，再定向复测该项及15项既有回归，16/16通过。没有把失败或未运行测试算成成功。

本轮证据图人工查看了5条列表／已加载封面、横竖真实播放、150%字体控制条；只包含授权片和程序生成输入，未提交私人照片。调试APK沿用原签名SHA256 `a0a115e7d29638fe70e8f601c084e4ccdb662d54d2469f8814dcb8ff7e16f935`，同applicationId可覆盖升级；不用先卸载旧版。

本地正常MOCK8010保留；视频演示MOCK8018保持运行，故障夹具仅测试使用。合并需另核对当前PR头的Android与后端CI成功，现场验收不据CI替代。

## 仍未验收和后续

本轮没有镜子现场、实际扬声器输出、真实TalkBack朗读或长期稳定性证据。授权包已去音轨，控件／默认静音状态通过不等于声音效果通过。镜子安装、网络、横竖触摸、播放流畅和实际音效仅由Miqi9880安排；本轮不连接镜子。yyyrrrxxx只接尚缺的真实TalkBack／焦点查漏，不重复已经整合的HTTP、主页和播放器。haiyangniu191-design继续通用目录离线校验／只读盘点，不重复固定包安装器；lyf-bee保持既有渲染诊断任务。

模型语义FR-VID-03、其他专用品类内容、P1理由／重排／扫码，以及真实Depth／mask／手臂遮挡仍有缺口。此次视频软件闭环不代表全部企业P0完成。具体见[当前任务](../tasks.md)与[需求对应表](../requirements-traceability.md)。
