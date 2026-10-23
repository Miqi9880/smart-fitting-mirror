# 静态 CG 输入与同一页面联调准备

准备日期：2026-10-23。本地基线提交 `86fb002`（main 初始提交，209 文件），工作分支 `feat/static-cg-import`。
本文只盘点可独立完成的事实与入口，用于与 @yyyrrrxxx（Android 交互 / #7）和 @haiyangniu191-design（后端 / 外部 API / #5）对齐静态输入与同一生成页；不构成任何「已端到端通过」的结论。

## 结论速览

- 本地树是 **MOCK 后端**，`backend/service.py` 恒定 `is_mock=true`，**没有 Aidge/COS 提供方代码**。真实提供方与配置待持有者提供。
- 生成页 `TryOnActivity` 当前只接受「RGB 摄像头」或「合成样例」作为输入，**没有从文件 / 相册导入获准 CG 静态图的入口**。
- 51 件 studio PNG 不随仓库分发，需 `tools/install_studio_assets.py <素材包.zip>` 安装；素材包与获准 CG 静态图均为 **持有人待提供**。
- 单件上衣真实闭环可用的服装 ID 为 studio 上衣 15 件（`generatable=true`）。

## 1. CG / 静态样例可用性

| 素材 | 位置 | 状态 | 许可 / 备注 |
| --- | --- | --- | --- |
| upper_blue / upper_sand / upper_preview.png | `backend/assets/` | 已在仓库（几何绘制，非人像） | CC0-1.0；upper_preview 不可生成（用于 422 分支） |
| real/white-tee-cutout.png | `backend/assets/real/` | 已在仓库（实拍去背景） | 公有领域来源，辅助去背景、非无损抠图 |
| 51 件 studio 透明图 | `backend/assets/studio/*.png` | **本地缺失**，仅 manifest.json + README | 品牌官网商品图，不随 Git 分发；素材包持有人待提供 |
| 获准 CG 静态图（作为生成输入上传） | 不在仓库 | **持有人待提供** | 运行时图片目录均被 `.gitignore` 排除，不落库 |

## 2. 脱敏配置项 / 启动方式 / 服务地址

- 启动（MOCK，Python 3.12，仓库根目录）：
  `python -m backend --host 127.0.0.1 --port 8000 --mode success`
  烟雾检查：`python -m backend.smoke --url http://127.0.0.1:8000`
- MOCK 模式**不需要任何供应商密钥或 GitHub 令牌**。
- 服务地址：本机 `http://127.0.0.1:8000`；Android 模拟器 `http://10.0.2.2:8000`；真机需主机 IP + 端口转发。生成页地址输入框 `tag=fitting.address` 可填。
- 真实 Aidge/COS 配置只放后端（环境变量），**Android 不保存云密钥**；相关代码与配置均不在本地树，需远端 PR #37 或由持有配置者在本机跑后端与模拟器联合检查。

## 3. 服装 ID（可发起单件真实生成）

仅 `generatable=true` 的上衣可 `POST /api/v1/tryon-jobs`；其他品类返回 `422 GARMENT_NOT_GENERATABLE`（当前不支持整套生成）。

studio 上衣 15 件（默认目录）：

- T 恤：`studio_black_tee`、`studio_blue_tee`、`studio_red_tee`
- 衬衫：`studio_oxford_shirt_01`、`studio_oxford_shirt_06`、`studio_oxford_shirt_64`
- Polo：`studio_pique_polo_00`、`studio_pique_polo_09`、`studio_pique_polo_69`
- 卫衣：`studio_crew_sweatshirt_01`、`studio_crew_sweatshirt_09`、`studio_crew_sweatshirt_69`
- 连帽卫衣：`studio_zip_hoodie_01`、`studio_zip_hoodie_04`、`studio_zip_hoodie_09`

回归目录（`--catalog-profile regression`）另有 `upper_blue`、`upper_sand`、`real_white_tee` 可生成；`upper_preview` 不可生成。

## 4. 生成页导入入口评估（供 yyyrrrxxx 参考）

- 现链路：`TryOnActivity.capture()` → `source.captureOriginal(...)`（`rgb` 摄像头 / `mock` 合成样例）→ `controller.generate(photo)`。
- 全 Android 无 `GetContent` / `PickVisualMedia` / `ACTION_GET_CONTENT` / `ACTION_PICK` 等文件选择代码，**确认缺静态图导入入口**。
- 最小接入缝：`FittingSession.generate(photo: CapturedPhoto)` 接受任意
  `CapturedPhoto(sessionId, frameId, width, height, mimeType, bytes)`；导入即「选择图片 → 校验 PNG/JPEG ≤10MiB ≤1600万像素（可复用 `decodeBounded`）→ 构造 `CapturedPhoto` → 直接调 `controller.generate(photo)`」。
- 注意：不要走 `capture()` 的 `activeVision` sessionId 校验（静态导入不绑定实时帧）；不要把合成关键点网格当成人物照片作为输入。

## 5. 待提供 / 待对齐（明确记录）

- 获准 CG 静态图：**持有人待提供**。
- 51 件 studio 素材包（PNG）：**持有人待提供**（本地仅 manifest）。
- 真实 Aidge/COS 提供方代码与配置：**不在本地树**，需可 fetch 的凭据，或由持有配置者在本机运行后端 + 模拟器联合检查（不要求把密钥复制给组员）。

未完成项：与组员对齐静态输入与同一页面、生成页最小导入增量、真实提供方启用后的一次单件上衣闭环验证——均未执行。
