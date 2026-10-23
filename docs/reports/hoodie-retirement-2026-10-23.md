# 连帽卫衣下架与资源计划更新

2026-10-23按负责人讨论决定，下架三色拉链连帽卫衣（米白01、灰04、黑09），保留圆领卫衣。当前目录revision4为48件、16款，上衣4款各3色；不再为历史“五款上衣”要求自动补新款。

运行清单不再注册这三件素材。SQLite启动升级按asset_id移除旧条目及改名副本，保留其他编辑、自建衣服与删除状态；若移除后为空，不自动回填。旧v3包的三张下架PNG可被识别并忽略，仍拒绝其他未知路径、重复或缺失文件；本机旧图片和历史来源／测试报告不当作在售目录，也不改写历史云端调用证据。

新运行包为smart-fitting-mirror-studio-assets-20261023-v4.zip，48张透明PNG，54,437,531字节；SHA-256为70b6c9af7fefd50037ee935e9b377d71bace46a87dee32c5b8509499938cfbd2。素材包在负责人本机、仓库外保存，不提交品牌图片。

验证：

- `python -m pytest backend/tests -q`：109项通过；包括revision3升级、三色及改名副本移除、编辑／删除保留、移除后空库、公开目录无悬空素材和旧包兼容。
- `python tools/check_contracts.py`、`git diff --check`通过；没有公共接口／坐标／状态改动。
- Android与CI相同的构建、四模块单元测试及四模块lint命令通过：`:app:assembleDebug :app:assembleDebugAndroidTest :core-contracts:test :core-vision:testDebugUnitTest :core-network:testDebugUnitTest :feature-tryon:testDebugUnitTest :app:lintDebug :core-vision:lintDebug :core-network:lintDebug :feature-tryon:lintDebug`。真实清单的48件×9种合成运动检查保留，画廊数量同步48，三件组合回归改用黑色圆领卫衣＋长裤＋针织帽；本轮没有连接到Android设备，不声称重新运行了设备端测试。
- 本机8000模拟服务和8110真实提供方服务分别新建临时会话核对：均返回48件、无连帽卫衣；有效黑T素材读取正常，下架素材返回404，临时会话均结束。本轮没有拍摄、上传或再次提交云端生成。

部署只需更新后端和素材配置、重新启动服务并重新连接衣柜，不必重装0.3.5 APK；已有会话不能假定自动同步。当前本机两项服务已按新目录运行，镜子未连接，实体衣柜刷新留到下次连接。

协作任务同步为不再精修／验收连帽卫衣，其余渲染与交互职责保持。此前负责人完成的[真实生成闭环与现场反馈](mirror-real-generation-2026-10-23.md)已补为公共文字证据，不重复要求成员重做。独立微调仍先核对老师服务器，建议及来源见[GPU资源估算](../gpu-planning.md)，未选模型前不承诺固定最低显存或训练耗时。
