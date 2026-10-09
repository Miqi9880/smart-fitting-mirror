# 实时衣服渲染接入

维护人：lyf-bee。负责人先行补充 TorsoWarp 与 TryOnView：透明 Bitmap 经四个肩髋锚点透视形变，背景/衣服共享 FIT_CENTER 和一次镜像；缺点、丢失和退化四边形隐藏衣服。

TryOnView 实现 TryOnRenderer<BitmapPixels>；submit 只保留最新帧。setAsset 接管素材 Bitmap，替换和 clear 会回收；输入帧只能 retain/release，不能自行 recycle。支持暂停输入时更换服装。清理顺序和联调见 [试衣说明](../../docs/tryon-integration.md)。

已有四项几何单测和 app 内的像素渲染/镜像/丢失/资源释放测试；真实人体跟随、美观、防抖、非正面效果、深度遮挡尚待实测。下一周先复现和核对素材锚点，详见 [#6](https://github.com/Miqi9880/smart-fitting-mirror/issues/6)。

app 页面和模块装配由 yyyrrrxxx 负责；不重写摄像头或 HTTP。保留硬件诊断入口。
