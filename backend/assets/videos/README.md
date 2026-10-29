# 授权穿搭参考视频

7条Pexels内容，2026-10-29核对[官方许可](https://www.pexels.com/license/)及每条来源页。许可允许免费使用和修改；不得暗示出镜者／品牌背书，不得将原样内容作为商品或素材平台重新分发。本项目用于课程App中的穿搭参考，**不是衣柜同款商品演示，也不是AI生成视频**。已保留作者、页面、下载原件与处理后SHA256、处理方式，详见[manifest.json](manifest.json)及[catalog.json](catalog.json)。不据视频人物推断身份或敏感标签。

## 安装与启动

媒体不提交Git。向负责人领取课程本地包`smart-fitting-mirror-video-assets-v1.zip`（17,727,086字节），SHA256：
`d472c5de4f0a49292c16fb3a7d938c82a34c46450200c4633af3563a8fa8f2f7`

在仓库根目录、现有Python3.12后端环境运行（Windows路径按实际调整）：

```powershell
python tools/install_video_assets.py "C:/path/smart-fitting-mirror-video-assets-v1.zip"
python tools/install_video_assets.py --check
python tools/run_video_mock.py --port 8018 --catalog-db "C:/path/video-demo.sqlite3"
```

安装器只读取固定包，不联网；按仓库清单逐项验证文件名／大小／哈希，全部通过才写入被Git忽略的`media/`。不能往里面放人物原图、生成结果或密钥。服务只把上架、授权已核验、可播已核验且实际存在的文件放入候选；未安装时为空库。该固定包安装器不代替后端同学负责的通用目录校验／盘点工具。

`run_video_mock.py`只绑定127.0.0.1且始终MOCK，不读云端配置。模拟器配置`http://10.0.2.2:8018`；镜子后续可由负责人使用ADB reverse或按后端README在受控局域网启动正式MOCK服务。本轮不自动连接镜子。保留原8010服务，8018可独立演示视频。

App待机“相关视频”或试衣页“当前搭配相关视频”进入；使用当前衣柜真实ID查询。上衣样例有5条候选，连衣裙样例含2条裙装参考，也可能因颜色命中其他内容；数量按相关上下文计算，不重复凑数。仅已安装7条总库不代表每个品类都满足5条。短裤／半裙等专用品类内容可继续由负责人扩充。

公共视频／封面GET和HEAD无需Token，支持字节Range；游客照片、生成结果不走此路由。首次进入默认静音；这些短片已去音轨，音量控件状态验证不等于真实扬声器音效验收。转码使用ffmpeg：截取前至多12秒，H.264/yuv420p、24fps、最长边1280、不裁切，移除音轨及元数据；第1秒取JPEG封面最长边480。

## 来源

| 稳定ID | 作者及原始页面 | 安装视频尺寸 | 实际时长 |
| --- | --- | --- | --- |
| pexels-5901089 | [Leeloo The First](https://www.pexels.com/video/woman-walking-and-modelling-in-the-street-5901089/) | 1280×720 | 12秒 |
| pexels-6769384 | [cottonbro studio](https://www.pexels.com/video/woman-bending-wearing-a-white-shirt-and-jeans-6769384/) | 720×1280 | 12秒 |
| pexels-4723041 | [olia danilevich](https://www.pexels.com/video/female-model-wearing-a-white-shirts-and-denim-pants-4723041/) | 720×1280 | 12秒 |
| pexels-6532083 | [cottonbro studio](https://www.pexels.com/video/a-woman-walking-6532083/) | 1280×676 | 12秒 |
| pexels-6566210 | [Tim Douglas](https://www.pexels.com/video/two-women-walking-at-the-sidewalk-6566210/) | 1280×720 | 10秒 |
| pexels-7671653 | [Anastasia Shuraeva](https://www.pexels.com/video/woman-looking-at-camera-while-walking-7671653/) | 720×1280 | 12秒 |
| pexels-9511253 | [cottonbro studio](https://www.pexels.com/video/a-woman-posing-while-walking-on-the-runway-9511253/) | 1280×676 | 12秒 |

[集成和验证记录](../../../docs/reports/video-playback-2026-10-29.md)。
