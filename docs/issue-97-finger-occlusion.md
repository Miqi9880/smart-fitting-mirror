# Issue #97 修复报告：手指被短袖上衣遮挡

分支：`fix/finger-occlusion`
涉及模块：`android/feature-tryon`

## 结论

`DepthOcclusion.refineArms` 里把「深度为 0（无采样）」当成了「不能判定为前景」，导致整根由未知采样组成的手指永远打不穿衣服。修复后，**只有很细的指节段**可以仅凭线段几何把无采样像素判为前景，臂段（半径 ≈ 9% 肩宽）行为保持不变。全量单测 298 个全部通过，其中包含一条新增的回归用例（去掉修复后该用例会失败）。

## 1. 判断前后顺序的标准（排查结论）

`DepthOcclusion` 有两段逻辑，判据并不一致，这正是 Bug 的根源。

**第一段 `fillMask`（第 48–55 行）**，逐像素写掩码：

```kotlin
write(y * width + x, scene != 0 && scene < thresholdMm)
```

- `scene == 0` → `occluded = false` → 写入 `-1`（不遮挡，**衣服照常画上去**）
- `scene != 0 && scene < garmentMm - 40` → `occluded = true` → 写入 `0`（挖空，露出前方物体）

也就是说 `0` 的含义是「传感器没有返回样本」，不是「很远」，也不是「很近」。它对衣服是**放行**的。

**第二段 `refineArms`（第 57–121 行）**用骨架线段（`elbow→wrist`、`wrist→finger`）去修补第一段的漏判：把落在肢体线段半径内的像素强制置 `0`。修补前有两道关卡：

| 关卡 | 旧代码 | 对 `scene == 0` 的行为 |
| --- | --- | --- |
| 自身深度闸门 | `if (scene >= torsoMm - ARM_TOLERANCE_MM) continue` | `0 >= 1985` 为假，**放行**（这一关是好的） |
| 邻域证据闸门 | `if (hasForegroundSupport(...)) pixels[index] = 0` | `hasForegroundSupport` 只统计 `mm in 1 until threshold`，`0` 不计入，**邻域全是 0 时永远返回 false** |

于是出现一个夹缝：**一根手指如果整段都是无采样，第一段放行（被衣服盖住），第二段又不肯挖（没有邻域证据），最终结果就是手指被画在衣服后面。** 指尖恰好是整段无采样最容易出现的位置——手指只有几个像素宽，深度传感器在这么细的结构上直接返回连续的一串 0。

`4c0bb97`（#96）已经接入了手指关键点，也用小邻域补偿了手附近的深度缺口，但补偿依赖 `hasForegroundSupport` 找到「实测的浅于躯干的样本」。一旦缺口连成片（指尖），这个补偿就失效了——所以 #96 之后手指仍然被遮挡。

## 2. 复现用的 JVM 单测

`android/feature-tryon/src/test/kotlin/.../GarmentMeshTest.kt`，第 90 行新增
`sparseFingerDepthDoesNotFlipAnOpenHandBehindTheShirt`。

构造的模拟帧：

- 100×100 深度图，整幅先填 `2000 mm`；
- 前臂横在胸前：第 45 行、x ∈ [20, 56] 填 `1850 mm`（浅于躯干，会被 `fillMask` 正确判为遮挡）；
- 四根张开的手指从腕点 `(42, 45)` 呈扇形散去，指尖在 `x = 64`、`y = 38/41/44/47`，每根粗 3 像素；
- **关键**：手指根部 x ∈ [50, 55] 有真实采样 `1850`，指尖 x ∈ [56, 64] 是**连续的一串 0**——这正是传感器的真实表现；
- 指尖到腕点距离 22.0–23.1 px，落在 `refineArms` 的 `maxLength = 0.55 × shoulderSpan = 27.5` 之内（超出会被静默丢弃，这是搭 fixture 时的一个坑）。

断言：手指（含被丢样的指尖段）所有像素掩码必须为 `0`（在衣服前方）；衣服上远离手的三个点必须仍为 `-1`。另有一条 `fingerPixels > 20` 的自检，防止 fixture 退化成空跑。

**反向验证**：把修复临时关掉重跑，该用例失败，报
`dropped fingertip sample (57,39) must stay in front expected:<0> but was:<-1>`。
即断言不是空转的。

## 3. 代码改动（逐行）

### `android/feature-tryon/src/main/kotlin/com/smartfitting/mirror/tryon/DepthOcclusion.kt`

| 行号 | 改动 |
| --- | --- |
| 69–71 | **新增** `val digitRadius = shoulderSpan * .045f`：把「指节段的半径」作为细结构的判据 |
| 82 | **新增** `val thin = radius <= digitRadius + .001f`：每段线段提前算好自己是不是细结构 |
| 93–97 | **新增** `val unknown = scene == 0`，并把原来的 `if (scene >= ...) continue` 改为 `if (!unknown && scene >= ...) continue`。注释说明 0 是「无采样」，不能当背景读 |
| 103–110 | **新增** 注释，并把原来的 `if (hasForegroundSupport(...)) pixels[index] = 0` 改为 `if ((unknown && thin) || hasForegroundSupport(...)) pixels[index] = 0` |

改动净行数：**+21 / −2**。`hasForegroundSupport`、常量 `TORSO_TOLERANCE_MM = 40`、`ARM_TOLERANCE_MM = 15`、`TryOnView.prepareDepthMask` 全部未动。

### 为什么限定在 `thin`

无采样本身是个弱信号：它既可能是「手指在这里」，也可能是「这一块什么都没有」。区别只能靠几何——指节段的半径是 `0.045 × 肩宽`（≈2.25 px @ 肩宽 50），比臂段的 `0.09 × 肩宽`（≈4.5 px）细一半。一条 2 像素宽的线段不可能扫到一大片空白躯干，所以对它来说「像素落在线段半径内」本身就是足够的前景证据。而臂段如果也接受无采样，就会把一大块空白躯干挖空，出现新的漏光。

试过两个更「严格」的方案，都不成立，已放弃：

1. 用「邻域内未知样本数 > 实测样本数」做判据——1/3 的稀疏零值下永远触发不了，且把手指自身的像素算作「实测」在语义上是错的；
2. 要求邻域内存在浅于躯干的实测样本——邻域一旦挨到一个 `>= threshold` 的躯干像素就会被否决，真正的指尖反而被拒。

### 顺带改动（与本 Issue 无关，可单独 review）

`android/settings.gradle.kts`：在 `pluginManagement` 和 `dependencyResolutionManagement` 的 `repositories` 最前面各加了阿里云 `google` / `public` 镜像，解决本机从 Google 拉 `segmentation-selfie` 依赖超时的问题。如果不希望混进这个 PR，可以单独 drop。

## 4. 单测结果

`./gradlew.bat test --continue` → **BUILD SUCCESSFUL in 4m 21s**，165 个 task。

| 模块 | tests | failures | errors |
| --- | --- | --- | --- |
| app | 74 | 0 | 0 |
| core-contracts | 22 | 0 | 0 |
| core-network | 60 | 0 | 0 |
| core-vision | 72 | 0 | 0 |
| feature-tryon | 70 | 0 | 0 |
| **合计** | **298** | **0** | **0** |

`feature-tryon` 明细（含既有的 `GarmentMeshTest` 5 条旧用例，全部仍通过）：

```
DrapedPlacementTest   9 ok
GarmentMeshTest       6 ok   ← 含新增 sparseFingerDepthDoesNotFlipAnOpenHandBehindTheShirt
HeadProtectionTest    4 ok
OutfitPlacementTest   8 ok
PreviewViewportTest   4 ok
TorsoWarpTest         4 ok
```

## 5. 这条修复能证明什么 / 不能证明什么

- **能证明**：在合成深度图上，由无采样组成的手指段现在会被判到衣服前方，且远离手的衣服不受影响；既有的遮挡逻辑（实测浅于躯干的样本、掩码语义、容差常量）行为未变，298 个单测全绿。
- **不能证明**：真机上传感器噪声、深度图与 RGB 的对齐误差、手指与衣服边缘的交界处的最终观感。合成输入不等于真实效果，仍需真机验证「手指张开时指缝里能看见衣服、手放下后衣服恢复完整」。
