# 参考项目的完整可用模块迁移

2026-10-19，负责人授权跨原分工补位，先迁入能运行并能验证的功能，再让成员接手验证和后续完善。[上游固定版本](https://github.com/J0KER-N/SmartMirror/tree/11b667b6ec8c367395db146a7048fc047d874696) 未变化；课程复用许可与归属见 [NOTICE](../third_party/SmartMirror/NOTICE.md)。

## 功能覆盖与落点

| 上游功能 | 本项目落地 | 验证入口／适用边界 |
| --- | --- | --- |
| MediaPipe 关节平滑 | 已有 PoseStabilizer＋当前 ML Kit 输出 | PR #19；丢失立即清理，不引入假人体/假距离 |
| ListScreen 的商品卡片、搜索、筛选、无结果提示 | GarmentBrowser＋GarmentSearch，图片缩略图与选中标记 | 连接服务→浏览衣柜；中文宽松字符匹配、英文分词、标签筛选 |
| DetailScreen 的图片、品牌/材质/描述、尺寸/颜色/季节/价格与护理信息 | 可选 GarmentDetails＋详情对话框＋试穿按钮 | 字段为空时不编造品牌/价格/评价；颜色/尺寸是资料，不声称可测量合身程度 |
| AddClothesScreen 的现有图片选择、资料校验与保存 | 衣柜内新增/编辑/移除，沿用素材库 | 游客只编辑自己的本次衣柜，结束/过期后清理，避免改动其他游客目录 |
| ClothingController/Service/Repository 的 CRUD、数据库保存 | 现有 FastAPI 新增目录读写；SQLite CatalogRepository 保存内置基础目录 | SQLite 是永久服装元数据；本机 CLI 管理，新的游客会话取其快照；无需额外 Spring Boot/MySQL |
| Retrofit/Repository 的 HTTP 加载、错误状态、重试 | 扩展既有 HttpFittingApi/FittingSession | 单一会话凭证与同源图片下载；目录失败后刷新，不盲目重放新增请求；补素材断流恢复测试 |
| GarmentDriver 的 OBJ 读取、三角面与躯干/手臂驱动 | GarmentMesh／GarmentMeshDriver，实际打包上游 tshirt.obj | 试衣页打开 OBJ 网格实验；当前二维点驱动三维网格的正交投影，未获得真实三维人体坐标 |
| TryOnRenderer 的相机底图、衣服覆盖、网格光照 | 接在同一个 TryOnView：照片模式与 OBJ 模式切换 | Canvas 复用本项目帧所有权与 FitCenter；网格为简化蓝色模型，不当成实拍纹理 |
| garment_fs 的深度比较/遮挡 | DepthOcclusion＋可选同帧 AlignedDepthPixels，照片衣服层单独合成 | 输入须为实际 RGB 对齐的 uint16 小端毫米深度，并提供 torso distance；无有效数据就不启用。软件测试不代表真实硬件管线已打通 |
| BootReceiver 开机入口意图 | 可选 HOME 主屏幕入口＋系统设置按钮 | Android 15 使用系统/管理员选择默认桌面，未复制不可靠的后台强行启动。模拟器仅验证可被解析，目标主机重启仍待核验 |
| CameraManager/CombinedFrame/IBodyTracker 模块边界 | 沿用已运行的 CameraX＋ML Kit＋VisionSource/FrameLease | 上游真实 Orbbec 初始化/取流是注释占位，没有可运行 SDK 驱动可直接复制；型号确认后再做厂商适配 |
| MockJointProvider 与演示输入 | 沿用更完整的 SyntheticVisionSource 场景/回放；修复 V07 等比例输入 | 明确合成标识、暂停/单步/恢复；同一人体横竖图保持像素比例 |

JNI 与 Kotlin 原本是同一网格驱动的两条执行路径。本轮将可用算法适配为可测试的 Kotlin 实现，统一接入现有渲染；没有同时引入一份未调用的 JNI、NDK 构建或第二套摄像头。上游演示矢量衣服、常量深度、失败后的模拟 T-Pose 不能算真人能力，因此不替换当前真实输入。

## 立即怎么试

沿用原 Android Studio、JDK 17/21、SDK 35 与 Python 虚拟环境，无需为本轮安装 Compose、NDK、MySQL、Unity、CUDA 或厂家 SDK。

1. 在仓库根目录运行 `python -m backend --host 127.0.0.1 --port 8000 --mode success` 并保持运行。
2. Android Studio 构建安装 App；官方模拟器地址 `http://10.0.2.2:8000`。普通手机用已有 `adb reverse tcp:8000 tcp:8000` 后填写 `http://127.0.0.1:8000`。
3. 连接服务，默认优先选择实拍来源白色 T 恤。点击 RGB 摄像头授权后看实际人体；暂无设备时显式选择合成样例作开发验证。
4. 打开“浏览衣柜与服装详情”，搜索 `白恤`、筛选 `photo`，看详情并试穿；新增条目时选择已有素材，填写资料。编辑、移除只影响当前会话。
5. 打开“显示关键点”，检查点/衣服共同镜像，缺点和 lost 不残留；关闭后拍摄原图不应含绘制。OBJ 开关单独查看模型投影实验，不把它当成深度试衣。
6. 进入“硬件诊断→设备信息与 USB 枚举”，可查看/复制型号、SoC 报告、ABI、内存、显示、Camera2 尺寸和 USB VID/PID/接口类。本页不申请 USB 访问或读取序列号，枚举不代表摄像头出图。

实拍素材路径、处理提示词与初始锚点见 [素材说明](../backend/assets/real/README.md)。使用内置 image_gen 做背景提取和清理，辅助版本不计作纯手工、逐像素保真的素材验收。

## 永久基础目录与临时衣柜

服务器 CLI 默认将基础服装元数据保存到 `data/catalog.sqlite3`（已忽略，不入 Git）。不保存人像、结果或会话令牌。服务重启后基础目录仍在；首次建立数据库时写入四件初始服装，全部删除后也不会偷偷重新种入。

在仓库根目录管理基础目录：

```powershell
python -m backend.catalog_repository --db data/catalog.sqlite3 list
python -m backend.catalog_repository --db data/catalog.sqlite3 put contracts/examples/garment-write.json
python -m backend.catalog_repository --db data/catalog.sqlite3 delete real_white_tee
```

`put` 使用 GarmentWrite JSON，按 ID 新增/更新；只能引用打包素材库里的 asset_id。删除会移除基础目录元数据，不删除图片文件。CLI 管理结果在**新建会话**生效，既有游客保持自己的快照。以上最后一条是操作示例，不需要为复现先删除白色 T 恤。

测试或分别运行多个服务时可传 `--catalog-db <单独路径>`，避免相互修改同一测试目录。API 工厂不传路径时使用内存初始目录，便于隔离测试。

## 合同与验证范围

HTTP 合同从 0.1.0 递增到 **0.1.1**：新增可选 details、目录查询参数与读写路径，旧的衣服字段和生成请求不变。视觉 Schema 仍为 **0.1.0**，可选深度像素是本地内存载体；当前 RGB 源继续报告深度/遮挡不可用。

本轮需验证 JVM、后端真实 HTTP、Android 绘制/生命周期、完整四服务流程和实际页面操作；最终结果以 [迁移验证记录](reports/reference-full-migration-2026-10-19.md) 为准。真机 ABI/安装、摄像头接线与 SDK、真人运动、实际深度、性能和共同验收不能用模拟器照片或数学用例替代。
