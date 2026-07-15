cmake_minimum_required(VERSION 3.22.1)

project(proterm_bridge C)

# 仅编译 JNI 桥接 .so；proot 自身是独立可执行文件，
# 由 ../scripts/build_proot.sh 使用 NDK 工具链单独交叉编译，
# 产物放入 app/src/main/assets/proot。
add_library(proot_bridge SHARED proot_bridge.c)

find_library(log-lib log)

target_link_libraries(proot_bridge ${log-lib})
