# ProTerm

一个 Android 终端 App：**打开默认就落在容器内发行版（Alpine）的 shell 里**，
而不是 Android 的 bionic 环境。UI 与 JNI 桥接全部自研，**不使用 Termux**，
底层执行器直接拉取 [proot-me/proot](https://github.com/proot-me/proot) 官方源码
自行交叉编译、按需改造。

## 架构

```text
┌──────────────────────────────────────────────────────────┐
│  MainActivity                                               │
│    └─ GLTerminalView（GLSurfaceView 子类：软键盘/触摸/resize）│
│         └─ TerminalRenderer（OpenGL ES 2.0：顶点缓冲批量绘制）│
│              └─ BitmapFont（字体图集：ASCII/DEC 静态 + 中文动态）│
│         └─ TerminalEmulator（屏幕缓冲/光标/滚动/字符集/SGR）    │
│              └─ Vt100Parser（转义序列状态机）                  │
│         └─ TerminalSession：PTY master fd 字节流读写          │
│              └─ ProotManager：解压 proot + rootfs             │
│                   └─ ProotBridge (JNI)                       │
│                        └─ proot_bridge.c                      │
│                             ├─ open /dev/ptmx 建 PTY          │
│                             ├─ fork                           │
│                             ├─ execl(proot ...)               │
│                             └─ resizePty (TIOCSWINSZ)         │
│                                  └─ proot (静态可执行)         │
│                                       └─ -r rootfs            │
│                                            └─ /bin/sh         │
└──────────────────────────────────────────────────────────┘
        ↑ 容器内 Alpine（filesDir/rootfs）
```

核心链路：`ProotBridge.startProot()` 在 native 侧创建 PTY、`fork` 后
`execl` 启动 proot，proot 以 `filesDir/rootfs` 作为 `/` 拉起 `/bin/sh`，
PTY master fd 交回 Kotlin；容器输出的**原始字节**由 `Vt100Parser` 解析、
`TerminalEmulator` 维护屏幕状态、**`GLTerminalView` + `TerminalRenderer` 用 OpenGL ES 自绘**
（**纯自研，不依赖任何外部终端库 / 也不依赖 Canvas 逐格绘制**）。

## 渲染层（OpenGL ES，纯自研）

- `GLTerminalView`：`GLSurfaceView` 子类，负责软键盘输入、触摸滚动回看历史、
  以及按字体 cell 尺寸把视图像素尺寸换算成 `cols × rows` 再 `resizePty`。
- `TerminalRenderer`（`GLSurfaceView.Renderer`）：每帧构建两套顶点缓冲——
  背景层（每格一个纯色 quad，真彩直接做顶点色）+ 字形层（带字形纹理 quad，
  前景色做 tint），单 draw call 批量提交；含光标（带闪烁）。
- `BitmapFont`：启动时把 ASCII + Latin1 + DEC 画线/符号字符一次性栅格化进一张
  静态纹理图集；中文等宽字符不在图集时**运行时按需栅格化并 LRU 缓存**为独立纹理。
  所有字形最终都是 GPU 纹理，彻底摆脱 Canvas 逐格 `drawText` 的性能瓶颈。

## 终端能力（对照 bash / vim / htop / tmux）

- **光标与编辑**：CUU/CUD/CUF/CUB、CUP/HPA/VPA、IND/RI/NEL、DECSC/DECRC 光标保存恢复。
- **擦除**：ED（`?J` 0/1/2）、EL（`?K` 0/1/2）、ECH、DCH、ICH、IL/DL。
- **滚屏**：`scrollUp/scrollDown`、DECSTBM 滚动区域、自动滚动。
- **字符集**：G0/G1 选择（`ESC ( B`、`ESC ( 0` 等）、SO/SI 切 GL、
  **DEC 特殊图形字符集**（htop/vim 的 `─│┌┐└┘├┤┬┴┼` 边框画线正确显示）。
- **宽字符**：中文/全角按显示宽度占 2 格，单元格含 `widePad` 延续格标记，渲染跳过字形。
- **替代屏**：`?1049` 正确保存主屏、重建 alt-grid，退出还原主屏内容。
- **scrollback**：主屏滚出的行进入环形历史缓冲（上限 5000 行），触摸上滑回看。
- **自动换行**：`?7` DECAWM 开关。
- **真彩**：SGR `38;2;r;g;b` / `48;2;r;g;b` 真彩与 `38;5;n` `256` 色调色板，直接做顶点色。
- **UTF-8**：完整多字节解码，中文输入正常。

## 目录结构

```text
ProTerm/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── jni/
│       │   ├── CMakeLists.txt        # JNI 桥接 .so 构建
│       │   └── proot_bridge.c        # PTY + fork + execl proot
│       ├── java/com/proterm/app/
│       │   ├── MainActivity.kt
│       │   ├── proot/ProotBridge.kt  # JNI 声明
│       │   ├── proot/ProotManager.kt # 解压 proot + rootfs
│       │   └── terminal/
│       │       ├── TerminalSession.kt   # PTY 字节流读写 + resize
│       │       ├── TerminalEmulator.kt  # 屏幕缓冲/光标/滚动/字符集/SGR
│       │       ├── Vt100Parser.kt       # ANSI/VT100 转义序列状态机
│       │       ├── Cell.kt              # 单元格模型（含 widePad）
│       │       ├── AnsiColors.kt        # 16/256 色映射
│       │       └── gl/
│       │           ├── GLTerminalView.kt # GLSurfaceView + 输入 + resize
│       │           ├── TerminalRenderer.kt # OpenGL ES 渲染器
│       │           └── BitmapFont.kt      # 字体纹理图集
│       ├── res/...                   # 主题/布局/字符串
│       └── assets/                   # 放 proot 二进制与 rootfs.tar.gz（构建时生成）
├── external/proot/                   # proot 官方源码（git submodule）
├── scripts/
│   ├── build_proot.sh                # NDK 交叉编译 proot
│   └── prepare_rootfs.sh             # 下载并打包 Alpine minirootfs
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── ROOTFS.md                         # rootfs 约定
└── README.md
```

## 前置依赖

- JDK 17+（本机测得 OpenJDK 25 可用）
- Android SDK（compileSdk 35）+ build-tools
- **Android NDK**（编 JNI `.so` 与交叉编译 proot 必须）
- git、curl

## 本地编译与真机运行

```bash
# 1. 引入 proot 官方源码
git submodule add https://github.com/proot-me/proot external/proot
git submodule update --init --recursive

# 2. 交叉编译 proot（需先 export ANDROID_NDK_HOME）
export ANDROID_NDK_HOME=/path/to/Android/Sdk/ndk/<version>
bash scripts/build_proot.sh

# 3. 准备容器 rootfs（Alpine minirootfs）
bash scripts/prepare_rootfs.sh

# 4. 用 Android Studio 打开本项目，连真机，Run。
#    启动即进入容器内 /bin/sh，可 apt/apk、gcc 等。
```

> 模拟器需选 `x86_64` 镜像（`build_proot.sh` 已编出 x86_64 版 proot）。

## 改造点（patch）

见 `external/proot/README.md`。要点：seccomp 关闭（`PROOT_NO_SECCOMP=1`）、
默认 rootfs 环境变量、`-0` 伪装 root、loader 关闭（同架构无需）。

## 已知坑

- **fork 安全**：`proot_bridge.c` 在 `fork` 后、`execl` 前只做紧贴系统调用
  的动作（setsid/ioctl/dup2/setenv），不碰任何 bionic/Java 锁。
- **SELinux**：非 root 机型 enforcing 可能拦部分 syscall，proot `-0` 可绕。
- **noexec**：rootfs 必须放 `filesDir`（非 sdcard），否则无法执行。
- **渲染层（OpenGL ES，纯自研）**：`GLTerminalView` + `TerminalRenderer` +
  `BitmapFont` 组成完整 GPU 终端渲染，**不依赖 termux-terminal-emulator 等外部库，
  也不走 Canvas 逐格 drawText**。包含 DEC 字符集画线、宽字符双格、替代屏重建、
  scrollback、真彩顶点色、自动换行；窗口尺寸由 `GLTerminalView` 测量后经
  JNI `resizePty` 下发 `TIOCSWINSZ`。
- **纹理复用**：中文等动态字形走 LRU 缓存，超出上限回收最旧 GL 纹理。

## License

App 代码按 Apache-2.0；proot 部分遵循其上游许可证。
