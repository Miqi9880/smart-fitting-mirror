# 实拍来源的服装预览素材

`white-tee-cutout.png`：1448×1086 RGBA。来源是 [Elkagye / T-shirt2.jpg](https://commons.wikimedia.org/wiki/File:T-shirt2.jpg)，原作者按 PD-self 贡献至公有领域；[原始照片](../../../docs/materials/white-tshirt-original.jpg) 保留。来源、校验值、尺寸、初始锚点见 [元数据](white-tee-cutout.json)。

2026-10-19 使用内置 `image_gen` 工具两次编辑：先提取透明背景，再清理背景碎点。目视核对保留白色 T 恤的摄影外观和褶皱，但**这是辅助去背景版本，不是逐像素无损抠图**，不作为商品精确外观承诺。边缘、纹理与真人贴合仍需成员复核；原始照片继续可用于手工抠图替换。本轮负责人为推进接入先提供此原型版本，原周计划中的“纯手工保真素材”验收不据此自动勾选。

本素材来自实拍照片；服务器生成结果仍明确 `is_mock=true`。两者分别标识，不把素材来源当作真实 AI 换装能力。

初始四点：穿着者左肩 `(0.72, 0.13)`、右肩 `(0.25, 0.13)`、左髋 `(0.70, 0.78)`、右髋 `(0.30, 0.78)`。肩点选躯干与袖部连接区域；髋点位于下摆上方。它们不是旧演示图坐标，也不是最终人体尺寸标定。

## 工具与完整提示词

第一轮编辑目标为原始 JPEG，`transparent_background=true`：

> Use case: background-extraction. This is an EDIT TARGET, a public-domain photograph of one flat laid white T-shirt on a brown carpet. Create a reusable transparent PNG garment cutout for a university virtual fitting application. Remove ONLY the brown carpet/background to true alpha transparency. Preserve the exact photographed white T-shirt, its entire silhouette, neckline and collar, sleeve shape and asymmetry, seams, tag, fabric texture, lighting and all wrinkles. Do not repaint, retouch, smooth, redesign, reconstruct, add logos, alter proportions, invent sleeves, or replace the garment. Keep the original landscape orientation and composition and original width/height aspect ratio. Keep the shirt fully visible with a small transparent margin around its perimeter. Do not show a checkerboard or backdrop, and do not add a person, shadow, text, or props. The shirt interior and visible collar/label remain original photographic material. Deliver actual RGBA transparency.

第二轮编辑目标为第一轮透明输出，`transparent_background=true`：

> Precise transparent cutout cleanup of the EDIT TARGET. Preserve this photographed T-shirt exactly as it is, same shape, fabric, folds, collar, label, pixels and proportions. Remove ALL tiny disconnected floating white/brown specks and ragged background debris outside the actual garment silhouette. In particular clean above the shoulders, below the bottom hem, outside the sleeves, both underarms and the sides. Use a single clean antialiased silhouette boundary around the actual shirt; everything outside that boundary must have alpha=0. No extra isolated particles. Do not change or retouch the shirt interior. No checkerboard or shadow; true alpha transparency, full shirt visible.

提示词是工具输入意图，不是像素保真度或全部边缘质量已获验证的结论。没有使用 Python 重绘/编辑该图；只读取了尺寸、alpha 分布与校验值，最终输出直接复制入仓库。
