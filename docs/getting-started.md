# 第一次协作开发：环境和交付步骤

先读 [第 6–18 周目标](semester-plan.md) 和 [自己的任务](tasks.md)。四人共用一个工程，不新建独立 Android 项目。不懂的错误保留完整报错、执行命令和系统版本发到自己的 Issue。

## 所有人先做

1. 安装 Git，使用自己的 GitHub 账号接受协作者邀请。可以用 GitHub Desktop 克隆；不要共享账号、密码或令牌。
2. 将 `https://github.com/Miqi9880/smart-fitting-mirror.git` 克隆到本机普通开发目录。私有仓库看不到时先确认登录账号及邀请；鉴权用 Git Credential Manager/浏览器登录，不把凭证写进命令或截图。
3. 安装 Android Studio。在 SDK Manager 装 Android SDK Platform 35、Build Tools 35.0.0、Platform Tools、Android Emulator 和 API 35 系统镜像。Gradle JDK 选择 17 或 21，项目自带 Gradle Wrapper 8.11.1，不另装 Gradle。
4. 用 Android Studio **打开仓库内 android 目录**，等待 Sync。让 IDE 生成本机 local.properties。创建 API 35 模拟器（需要启用电脑虚拟化），或连接开启 USB 调试的 Android 手机。
5. 在 Android Studio Terminal（android 目录）执行以下命令，启动 App 后先试合成样例，再尝试 RGB。首次下载依赖可能较慢；失败记录第一个实际报错。

```powershell
.\gradlew.bat :app:assembleDebug :core-contracts:test :core-vision:testDebugUnitTest
.\gradlew.bat :app:lintDebug :core-vision:lintDebug
.\gradlew.bat :app:installDebug
```

macOS/Linux 用 `./gradlew`。APK 在 android/app/build/outputs/apk/debug/app-debug.apk。摄像头/厂商 SDK 细节及有条件的仓库镜像见 [Android 说明](../android/README.md)。没有真机也能跑合成数据。

## 后端环境与 Android 地址

haiyangniu191-design 必须配置；其他人做完整联调也需在自己的电脑启动服务。安装 Python 3.12，在**仓库根目录**执行，不需要 GPU、CUDA、模型训练或付费 AI 密钥：

```powershell
python --version
python -m venv .venv
.venv\Scripts\python -m pip install -r backend/requirements-dev.txt
.venv\Scripts\python tools/install_studio_assets.py "收到的商品素材包.zip"
.venv\Scripts\python -m pytest backend/tests -q
.venv\Scripts\python -m backend --host 127.0.0.1 --port 8000 --mode success
```

保持最后一个终端运行；另开终端在仓库根目录执行：

```powershell
.venv\Scripts\python -m backend.smoke --url http://127.0.0.1:8000
```

macOS/Linux 用 `.venv/bin/python`。成功/失败/延时/限流切换方法见 [后端 README](../backend/README.md)。重启服务会让旧会话失效，App 应重新进入会话。

本轮默认展示官网上衣、裤子和帽子，PNG 由负责人另供本地素材包；不随 Git 克隆。安装及组合操作见 [商品素材说明](studio-outfits.md)。仅运行历史合成回归时才显式使用 `--catalog-profile regression` 和独立数据库，无需下载品牌图片。

| 运行客户端的位置 | 服务地址与操作 |
| --- | --- |
| 同一开发电脑上的脚本 | http://127.0.0.1:8000 |
| Android Studio 官方模拟器 | http://10.0.2.2:8000（指向电脑，不是模拟器自己） |
| USB 连接且 ADB 已授权的 Android 真机 | 电脑执行 `adb reverse tcp:8000 tcp:8000`，App 用 http://127.0.0.1:8000；多设备用 `adb -s <设备> reverse ...` |

USB 断开/设备重启后检查转发；结束调试可执行 `adb reverse --remove tcp:8000`。无 USB 转发时才考虑电脑局域网 IP、服务监听和防火墙配置，不能把 10.0.2.2 照抄到真机。HTTP 仅用于本地调试，发布配置另行处理。

## 第一个小 PR

Issue 是任务卡，commit 是本地记录，push 是把个人分支上传，PR 是申请把分支合入 main。三位成员可以推送个人分支、提交 PR；**main 受保护，只由 Miqi9880 合并**。不要点击管理员绕过，不要改保护规则。

工作区没有未提交改动时，在仓库根目录：

```powershell
git status
git switch main
git pull --ff-only origin main
git switch -c feat/你的账号-具体任务
# 开发、运行自己的检查后：
git diff
git diff --check
git add 你确认要提交的文件
git commit -m "docs: record local setup and verification"
git push -u origin HEAD
```

然后在 GitHub 点 Compare & pull request，base 选 main，关联自己的 Issue，写“改了什么/怎样验证/实际结果/未验证项”，邀请 Miqi9880 审阅。有依赖或没做完就创建 Draft PR，不要勾已完成。提交后还可在同一分支补改，PR 会自动更新。合并后切回 main 并 pull，再开下一条分支。

已有未提交工作先保存/提交，不用 reset --hard 或强推来解决问题。首个 PR 只动自己模块或本人报告。公共合同、Gradle、Manifest、入口改动先在 Issue 中和负责人/调用方对齐。

验证报告可放 `docs/reports/<账号>-week6.md`：系统/工具版本、main 提交号、执行命令、结果、复现步骤、未知项。成功记录也应说明真实/模拟输入；不上传真实人像、密钥、个人 SDK 路径、.venv、编译目录和 APK。

每周在自己的 Issue 更新：

```text
已完成（PR/commit/验证证据）：
下一步（一个具体小任务）：
阻塞（完整错误、复现命令、需要谁提供什么）：
```
