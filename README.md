# DeepTutor Android（AI 老师 · 安卓版）

把 `DeepTutor-main` 打包成安卓 App：**后端（FastAPI + Python）和前端（Next.js）都在手机本地运行**，不需要电脑、服务器、Termux 或 root，装好就能用。

## 下载 / 安装

| 文件 | 用途 |
| --- | --- |
| `dist/DeepTutor-1.7.1-arm64.apk` | **真机用**（几乎所有 2017 年后的安卓手机，arm64-v8a） |
| `dist/DeepTutor-1.7.1-x86_64-emulator.apk` | 电脑上的安卓模拟器用 |

要求：Android 8.0+（API 26），建议 6 GB 以上内存，**首次启动需要约 2 GB 空闲存储**（APK 约 500 MB，解压后运行环境约 1.5 GB）。

## 使用

1. 安装后打开，首次启动会自动解压内置运行环境（只做一次，约 30 秒到 3 分钟），然后启动本地后端。之后每次打开约 10–30 秒。
2. 进入后点左上角菜单 → **Settings → Language models → Add model**，填入你的大模型服务商和 API Key（DeepSeek、通义千问、OpenAI、Kimi、智谱等，任何 OpenAI 兼容接口都可以）。
3. 回到 Chat 即可提问。知识库/RAG 还需要在 Settings → Embedding 里配置一个向量模型。

- 服务以前台服务运行（通知栏显示“DeepTutor 本地服务运行中”），切到后台对话不中断；点通知里的“退出”彻底关闭。
- 所有数据（会话、知识库、设置、API Key）只保存在本机 App 私有目录；卸载 App 会一并删除。
- 启动失败时界面会显示日志，可“复制日志”、“重试”或“重新解压运行环境”。

## 触屏操作

DeepTutor 的网页界面原本是为鼠标设计的（按钮 24–36px、悬停才出现的操作、几像素宽的拖拽条）。App 在触屏设备上做了适配，鼠标/触控板设备上的行为完全不变：

| 操作 | 行为 |
| --- | --- |
| 点按 | 所有按钮的可点区域至少 44×44（视觉大小不变）；紧挨着的图标拉开间距，避免误触旁边的按钮；按下有即时反馈，没有蓝色闪烁 |
| 悬停才出现的操作 | 在触屏上直接显示（会话的重命名/删除、消息的复制/编辑、图片删除等）；悬停才展开的菜单（“更多能力”、提示）改为点按展开 |
| 从屏幕左边缘向右滑 | 打开导航抽屉（手机竖屏等窄屏）；向左滑关闭 |
| 返回键 / 返回手势 | 先关闭最上层的菜单、弹窗、抽屉或取消文字选择，然后才后退页面；在首页连按两次退出到后台 |
| 长按选中回复中的文字 | 弹出“加入对话 / 提问”操作条（阅读器、PDF、协作写作同样支持） |
| 输入框 | 屏幕键盘的回车 = 换行，点发送按钮发送（外接键盘可用 Ctrl+Enter 发送）；“Tab 补全”提示变成可点的“轻触补全”按钮；输入框字号 ≥16px，聚焦时页面不会被放大 |
| 长按外部链接 | 弹出“在浏览器中打开 / 复制链接” |
| 拖拽调整面板宽度 | 拖拽条更宽，且不会被当成页面滚动 |
| 字体大小 | 跟随系统“设置 → 显示 → 字体大小” |

实现：`web/app/touch.css`（只在 `(hover: none) and (pointer: coarse)` 下生效）、`hooks/useTouchPrimary.ts`、`hooks/useTouchSelectionSettled.ts`、`components/common/AndroidBackBridge.tsx`，以及 `ComposerInput`、`ChatComposer`、`AppShell`、`PartnerComposer`、阅读器与协作写作中的少量改动；Android 侧在 `MainActivity`（返回键、长按链接、边缘手势让位、字体缩放）。全部改动导出为 `patches/web-touch.patch`，可直接应用到 DeepTutor 源码：

```bash
cd ../DeepTutor-main && git apply ../DTA/patches/web-touch.patch
```

## 架构

```
┌──────────── Android App (com.opendeeptutor.android) ────────────┐
│ MainActivity: WebView ──http://127.0.0.1:18802──┐                │
│   + compat.js（给旧版系统 WebView 补齐新 JS API）  │                │
│ RuntimeService（前台服务）                         │                │
│   1. 首次：assets/runtime/payload.tgz → files/rootfs │              │
│   2. libproot.so（Termux proot，ptrace 用户态 chroot）│              │
│      └─ Ubuntu 24.04 base (glibc)                  ▼                │
│          ├─ Node 22  → Next.js standalone (:18802) ──proxy /api /ws─┐│
│          └─ Python 3.12 → deeptutor serve (:18801) ◄────────────────┘│
│ 用户数据：files/home → 挂载为 /home/deeptutor（升级 App 时保留）      │
└──────────────────────────────────────────────────────────────────────┘
```

为什么这样做：DeepTutor 依赖 PyMuPDF、faiss、numpy、tiktoken、pydantic-core 等原生扩展，PyPI 上没有 Android 版轮子；但它们都有 `manylinux aarch64` 轮子。所以 App 内置一个 glibc 的 Linux 用户态环境，用 proot 运行，**后端源码一行未改**，功能与桌面版一致（Manim 动画渲染、外部 CLI 伙伴等需要额外系统组件的可选功能除外）。前端只加了触屏适配（见上一节）。

## 目录

```
android/                 Gradle 工程（Java，无 Kotlin）
  app/src/main/java/…    MainActivity / RuntimeService / PayloadInstaller / RuntimeState
  app/src/main/assets/compat.js
  app/src/arm64/         arm64 运行环境（构建生成：assets/runtime + jniLibs）
  app/src/x86_64/        x86_64 运行环境（构建生成）
scripts/
  build_payload.py       组装 Linux 运行环境 tar（Ubuntu + Python + 依赖 + Node + 源码 + 预编译 .pyc）
  rebuild_all.sh         一键重建：前端 → 两个运行环境 → release APK → 复制到 dist/
  runtime/start.sh       在 proot 内启动后端和前端
  runtime/proc/*         Android 隐藏的 /proc 文件替身
  add_locale_keys.py     给前端各语言包添加新文案（如“轻触补全”）
  debtool.py             解 Termux .deb / 读 ELF 依赖
  guest.sh, adb-guest.sh 调试：在设备上的运行环境里执行命令
  wait_ready.py          调试：等设备上的运行环境启动完成，并接上 WebView DevTools
  cdp.py                 调试：通过 DevTools 协议检查 WebView
  touch_*.py, touch_audit.js  触屏测试：点按区域审计、触屏模拟、行为检查（touch_checks.py）、开关对比（touch_ab.py）
  mock_llm.py            调试：无 Key 的 OpenAI 兼容假模型
patches/web-touch.patch  前端触屏适配改动（相对 DeepTutor-main）
toolchain/               Android SDK、Gradle（本机构建用，可删）
build/                   中间产物（可删，重建需重新下载）；build/src 是打过补丁的源码副本（git 管理）
dist/                    最终 APK
```

## 重新构建

在 Windows（Git Bash）中，按顺序：

1. 下载到 `build/dl/`：`python-build-standalone` CPython 3.12（linux aarch64/x86_64 + windows，用于交叉安装）、Node 22 linux arm64/x64、Ubuntu 24.04 base arm64/amd64、Termux 的 `proot`/`libtalloc`/`libandroid-shmem` 包、`@img/sharp-linux-*`、tiktoken 编码文件。
2. 交叉安装 Python 依赖（无需 Linux 机器）：

   ```bash
   uv pip install --python build/hostpy/python/python.exe --python-platform aarch64-manylinux_2_28 --python-version 3.12 --target build/site-aarch64 --only-binary :all: --override scripts/overrides.txt -r ../DeepTutor-main/requirements/full.txt
   ```

   （`scripts/overrides.txt` 把 `pystemmer` 提到有 aarch64 轮子的 3.x；x86_64 同理换 `x86_64-manylinux_2_28` 与 `site-x86_64`。）
3. 前端：复制源码到 `build/src`，`git apply patches/web-touch.patch`，在 `build/src/web` 执行 `npm ci --legacy-peer-deps` 和 `npm run build`。
4. 组装运行环境：

   ```bash
   build/hostpy/python/python.exe scripts/build_payload.py --abi aarch64 --assets android/app/src/arm64/assets/runtime --jnilibs android/app/src/arm64/jniLibs/arm64-v8a
   ```

5. 打包：

   ```bash
   cd android && ./gradlew assembleArm64Release assembleX64Release
   ```

DeepTutor 源码更新后重复 2–5 即可；App 检测到新的运行环境版本会自动重新解压，用户数据保留。环境已就绪时，3–5 可以用一条命令完成：

```bash
bash scripts/rebuild_all.sh
```

## 签名

当前 APK 使用 Android 调试证书签名，可以直接安装，但**不适合上架应用商店**，且以后换正式证书签名的版本无法覆盖安装（需先卸载）。正式发布时在 `android/keystore.properties` 写入 `storeFile/storePassword/keyAlias/keyPassword`，release 构建会自动使用。

## 已验证 / 未验证

- 已在 Android 14 x86_64 模拟器（系统 WebView 113）上完整验证：首次解压（约 25 秒）、后端与前端启动（约 10 秒）、WebSocket 流式对话、多轮对话、模型设置页；PyMuPDF / faiss / numpy / tiktoken / llama-index 在运行环境内可正常导入，DNS 与 HTTPS 正常。
- 已在真机（OPPO OPD2501 平板，Android 16，arm64，WebView 138）上验证：安装、首次解压、后端与前端启动（约 15 秒）、界面正常显示；运行环境内 PyMuPDF / faiss / numpy / tiktoken / llama-index 可正常导入（约 7 秒），DNS 与 HTTPS 正常，一次完整对话（经 CLI）约 15 秒；切到后台 1 分钟后前台服务仍在运行、仍可联网。
- 注意：安卓系统会在 App 没有任何前台组件时禁止它联网；本 App 依靠前台服务（通知栏常驻）保持联网，请不要在系统设置里关闭它的通知或把它加入“禁止后台运行”。
- 对话测试使用本地假模型（`scripts/mock_llm.py`），未用真实 API Key 测试。

### 触屏适配的测试

- 模拟器（窄屏 412px，触屏模拟）：`scripts/touch_checks.py` 15 项行为检查全部通过——左边缘滑出/左滑关闭抽屉、竖直拖动和屏幕中间横滑不误开抽屉、返回键依次关闭抽屉/菜单/文字选择后才后退、屏幕键盘回车换行不发送、无“悬停才可见”的控件、输入框 ≥16px、无点按高亮、拖拽条 `touch-action:none`、点在小图标下方 5px 仍命中、长按选中回复文字后出现“加入对话/提问”。系统返回键实测：先关抽屉，再从日历后退到对话页。
- 真机平板（OPD2501，1244×830，原生触屏）上，用同一审计脚本对比适配开/关（`scripts/touch_ab.py`，12 个页面共约 350 个可交互控件）：小于 28px 的点按区域从 146 个降到 59 个，小于 40px 的比例从 96% 降到 63%。仍低于 40px 的主要是日历月视图里的日期数字（每格本身只有约 50px 宽）、侧边栏 36px 高的导航行，以及若干 36px 的图标按钮——都在 36–40px 之间，接近但未达到 44px 的建议值。
- 上述平板数据测于最后一处修复（日历样式被路由级样式覆盖，已提高选择器优先级）之前；修复后的版本已在模拟器上检查，平板上请以实际操作为准。
