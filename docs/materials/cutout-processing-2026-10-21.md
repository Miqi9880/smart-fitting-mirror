# 51件透明素材处理记录

使用内置 image_gen；5件沿用此前版本，46件新增逐件提取。项目图片保存在本地 `backend/assets/studio/`（Git忽略）及负责人透明素材包；原图保存在独立来源库。未使用Python或其他脚本修改图片像素；脚本只复制、读取尺寸／alpha统计、初始标定、计算哈希、写元数据和打包。图像不是逐像素无损分割，纹理／细节可能变化，不能当作品牌精确商品复现。

## 基础提示词（实际使用）

```text
Use case: background-extraction. Edit target: the single supplied official garment product photograph. Remove only the surrounding pale grey background and its cast shadow. Return only this EXACT garment on genuinely transparent background, centered with modest transparent padding, completely visible. Preserve original front view, proportions, neckline, sleeves, hem length, stitching, fabric texture, wrinkles and exact original color. No redesign, recoloring, smoothing or new folds. No person, mannequin, added hanger, text, checkerboard or solid background. Separate garment edge cleanly including spaces between sleeves and body. This is a faithful cutout for a virtual fitting prototype.
```

短裤／牛仔裤有独立店铺字样时追加移除衣服之外的标签；半裙要求保持连续轮廓，不新增裤腿缝；帽子保留原斜前视角、刺绣和帽沿，不重建正面。所有色款分别输入真实官网下载图片，没有用改色生成3色。

## 光晕失败后的重做提示词（实际使用）

```text
Use case: background-extraction. Edit only the supplied product photograph. CRITICAL output framing: a PORTRAIT 3:4 canvas (not landscape), with substantial completely TRANSPARENT padding above and below the product. Cleanly cut out the exact hat/shorts fabric. Keep all original fabric colors, embroidery, seams, texture, design and viewing angle. Every area outside the hard fabric boundary must be transparent, no colored halo, NO glow, no soft outline, no shadow, no gradient or fog. Remove separate collection labels. Preserve the original product itself, do not redesign or recolor. This is a plain transparent garment sprite, centred on a tall portrait canvas.
```

明显大范围光晕的棕渔夫帽、三色鸭舌帽与蓝短裤未采用，重做后再入包；奶白连衣裙的额外精修未明显改善，保留较忠实的初始提取。最终51件在9页黑白底总览检查过，仍有局部浅色／彩色细碎残边，保留为已知原型限制，不称精细抠图全部通过。

每件尺寸和源控制点独立记录。初始位置根据真实透明轮廓逐款设置，合成动作验证不等于真人适配。鸭舌帽3张斜前图只能做二维近似；帽沿、头发／耳部遮挡和不同人头型仍待真人复核。原图与透明图哈希见 [运行manifest](../../backend/assets/studio/manifest.json)，原图采集清单见 [来源manifest](wardrobe-source-library-2026-10-21.json)。
