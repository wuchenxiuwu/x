# 容器 rootfs（默认环境）

App 启动后默认落入的"容器内发行版"就是这里准备的根文件系统。

## 它是什么

一个最小 Linux 根文件系统（rootfs），包含 `/bin /usr /lib /etc` 等，
装有 `/bin/sh`、`apk` 包管理器等。proot 用 `-r <rootfs>` 把它当 `/` 挂载，
于是 App 里跑的就是一个完整的 Alpine 用户态，而非 Android 的 bionic 环境。

## 如何准备

```bash
# 默认下载 aarch64（绝大多数现代手机）
bash scripts/prepare_rootfs.sh

# 其它架构
bash scripts/prepare_rootfs.sh armv7
bash scripts/prepare_rootfs.sh x86_64
```

产物：`app/src/main/assets/rootfs.tar.gz`。App 首次运行解压到
`filesDir/rootfs`（见 `ProotManager.rootfsDir`）。

## 约定

- 体积：Alpine minirootfs 约 2~3 MB（gzip 后），解压后约 8~10 MB，很轻。
- 多架构：当前 `ProotManager` 默认读 `rootfs.tar.gz`（aarch64）。
  若要 armeabi-v7a / x86_64 设备也能用，把对应 rootfs 命名为
  `rootfs-<abi>.tar.gz` 并扩展 `ProotManager.pickAbi` 的选型逻辑即可。
- 自定义：想换成 Debian/Ubuntu 根文件系统？换下载源、把 `prepare_rootfs.sh`
  的 URL 改掉即可，App 端无需改动（只要它是标准 tar.gz）。

## 安全提示

rootfs 在 `app/build` 打包进 APK 前请确保来源可信——Alpine 官方镜像
`dl-cdn.alpinelinux.org` 是可信源。不要使用来路不明的 rootfs。
