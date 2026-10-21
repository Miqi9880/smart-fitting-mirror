当前为revision3／51件，安装和已知限制见 [运行说明](../../../docs/studio-outfits.md)，新增提示词见 [处理记录](../../../docs/materials/cutout-processing-2026-10-21.md)。以下保留初版处理历史。

# 本地官网商品图素材包

来源与逐件校验值见 [manifest.json](manifest.json)。官网商品图及辅助提取的 PNG 不随 Git 分发，安装方法见 [组合试穿](../../../docs/studio-outfits.md)。品牌与原图权利归原权利人，未宣称开放许可。

原图由负责人从 UNIQLO 官方 image.uniqlo.com 下载到本地；黑色上衣使用 `WesternCommon/.../goods_09_465185_3x4.jpg` 平铺图，未选 `usgoods_09_465185_3x4.jpg` 的模特图。原照片未修改。

五件素材各执行一次内置 image_gen 编辑，`transparent_background=true`。完整共用提示词如下，方括号按单品分别替换为 black/blue/red front-facing flat lay AIRism oversized T-shirt、pair of black front-facing pleated trousers、black front-facing ribbed knit beanie：

> Use case: background-extraction. EDIT TARGET: the supplied UNIQLO official studio product photograph, one [item]. Extract ONLY the photographed product into genuine alpha transparency for an Android fitting preview. Remove the gray backdrop, its cast shadow, and detached page corner logos or other page annotations. Keep the actual fabric product, original color, texture, knit or seams, neckline, silhouette and proportions EXACTLY as photographed, without repainting, smoothing, redesigning, inventing logos or changing view. Keep the front-facing view. Tightly frame the full garment with a small uniform transparent margin about 5 percent; preserve the garment's own pixel proportions when adjusting canvas. No person, mannequin, scene, drawing, checkerboard or additional object. This is a cutout of the existing photograph, not a generated replacement product.

提示词为编辑意图，不是保真验收结论。没有用 Python 重绘或编辑商品图；仅用 Pillow 只读检查尺寸、alpha 和锚点，处理图原样复制。初始锚点需真人复核，边缘少量残色/低 alpha 像素也需后续人工质量检查。
