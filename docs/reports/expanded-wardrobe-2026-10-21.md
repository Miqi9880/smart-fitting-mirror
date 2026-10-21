# 六品类51件初始预览验证

基线main `061d67b`，功能分支 `feat/expanded-wardrobe`。负责人委托新增素材去背景、初始定位和接入；用户追加三色连衣裙。对应 #26，素材任务已从 #6 取消，企业连续交付方案由 #27 合并。原图51张／17款各3色；复用5张已有透明图，新增46张，PNG在本地运行包，Git只含来源、尺寸、哈希和标定。

HTTP0.3.0新增 dress／skirt／shorts及hem_ratio，视觉仍0.2.0。连衣裙／半裙用连续面片，短裤用髋膝之间的双腿面片；搭配互斥、缺点隐藏、换装时Bitmap释放和迟到结果丢弃共用。目录容量100；revision2→3仅补新ID，保留删除、编辑、自建和空衣柜。缩略图并发限制3。

## 已执行的软件检查

- `python tools/check_contracts.py`：OpenAPI、17份样例、8个拒绝用例、本地文档链接通过。
- `python -m pytest backend/tests -q --basetemp <本轮独立临时目录>`：54通过。Windows原默认pytest临时目录无权限，改用本轮工作目录；不是测试代码失败。
- JVM单元测试69通过：core-contracts17、core-vision18、core-network16、feature-tryon18。包括读取实际51件manifest，在静止／平移／缩放共459个姿态组合中验证非空有限面片；另测裙摆不随分腿裂开、短款不依赖踝点、缺点恢复和类别互斥。
- `:app:assembleDebug :app:assembleDebugAndroidTest`通过；本次改动的app／feature-tryon／core-network lint为0错误，保留既有本地化／布局警告，不将警告称为零。
- API35／Android15／x86_64本项目模拟器5556：App套件20项，14通过、6因未传旧四模式 fitting_test_host 跳过；这6项不计本次回归通过。新连衣裙连续下摆像素、原帧未改、缺腿隐藏／恢复、镜像、替换回收和迟到图拒绝通过；传入studio_test_host的本地8010三色切换、帽子保留、裙裤互斥、旋转、清空和结束也通过。
- 51张已安装PNG的尺寸／RGBA／SHA检查通过；ZIP CRC、文件清单和逐文件哈希通过。51个需要会话授权的素材HTTP读取与manifest匹配，结束后访问返回401。
- 8010正常MOCK烟雾检查：生成成功、幂等、取消、隔离、撤销通过，输出 `is_mock=true`。
- 本地透明检查页按9页观察51件的黑／白底；明显光晕结果已弃用重做。提示词、精修未改善的处理及已知残边见 [图像记录](../materials/cutout-processing-2026-10-21.md)。

模拟器安装最初出现debug签名不匹配，恢复本项目已有ANDROID_USER_HOME的debug keystore重建后覆盖安装成功，未卸载清空原App数据。没有打开物理摄像头，没有使用人像测试真实AI。

## 验收边界

这些是初始二维预览和合成／软件检查。局部浅色和彩色残边仍存在，鸭舌帽为斜前视角近似；不代表51件真人贴合、真实穿着尺寸／颜色、遮挡、目标镜子摄像头或性能通过。多上衣叠穿、整套生成和真实API未据此宣布完成。lyf-bee继续效果与稳定性验收，初始素材位置问题由负责人修。

本地运行包：smart-fitting-mirror-studio-assets-20261021-v3.zip；SHA256 `f39f47e6b3eb43840783966dca26fe6b6a28685977ecf5c1f4cbab8c3f6626aa`。使用[安装与操作说明](../studio-outfits.md)配套更新Android／后端，不能混用旧枚举客户端。演示服务继续运行；原图8020保留，透明检查页8030另开。
