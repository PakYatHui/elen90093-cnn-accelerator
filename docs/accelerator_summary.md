# Accelerator Summary

## 当前项目进度

当前项目已经从最初的 control-path-only accelerator 更新为 configurable convolution accelerator。

现在的 accelerator 已经可以在 Chipyard 中成功编译。

当前版本支持：

- 32x32 input matrix
- 32x32 output matrix
- 1x1 convolution kernel
- 3x3 convolution kernel
- 5x5 convolution kernel
- 16-bit signed fixed-point 8.8 数据格式
- zero padding
- RoCC custom instruction interface
- memory load
- memory store
- internal input buffer
- internal kernel buffer
- internal output buffer
- rd 返回 success/error

## 组员需要看的主要文件

GitHub 项目地址：

```text
https://github.com/PakYatHui/elen90093-cnn-accelerator
```

当前开发分支：

```text
feature/accelerator-fsm
```

Accelerator 源代码位置：

```text
src/main/chisel/ConvAccelerator.scala
```

直接查看 accelerator 文件：

```text
https://github.com/PakYatHui/elen90093-cnn-accelerator/blob/feature/accelerator-fsm/src/main/chisel/ConvAccelerator.scala
```

## Chipyard 中的正确放置位置

如果组员要在 Chipyard 中编译和运行 accelerator，需要把这个文件：

```text
src/main/chisel/ConvAccelerator.scala
```

复制到 Chipyard 的这个位置：

```text
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

也就是说：

GitHub project 里的文件是版本管理用的。

Chipyard 里的文件才是实际编译用的。

## 复制 accelerator 文件的命令

在本地环境中执行：

```bash
cd ~/elen90093-cnn-accelerator

git checkout feature/accelerator-fsm
git pull origin feature/accelerator-fsm

cp src/main/chisel/ConvAccelerator.scala \
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

## 避免 duplicate class error

Chipyard 编译目录中只能有一个 Scala 文件定义：

```scala
class MyConvAccel
class MyConvAccelModule
```

需要检查：

```bash
grep -R "class MyConvAccel" -n ~/chipyard/generators/myaccelerators/src/main/scala
```

正确情况应该只看到：

```text
~/chipyard/generators/myaccelerators/src/main/scala/ConvAccelerator.scala
```

如果还看到类似：

```text
~/chipyard/generators/myaccelerators/src/main/scala/MyConvAccel.scala
```

就要把它改名成 `.bak`：

```bash
cd ~/chipyard/generators/myaccelerators/src/main/scala

mv MyConvAccel.scala MyConvAccel.scala.bak
```

注意：

```text
MyConvAccel.scala.bak 不会被 Scala 编译。

MyConvAccel_backup.scala 仍然会被编译，因为它还是 .scala 文件。
```

## TutorialConfigs.scala 需要加入的内容

需要把这个文件里的代码加入到 Chipyard 的 `TutorialConfigs.scala`：

```text
docs/TutorialConfigs_MyConvAccel.scala
```

Chipyard 文件位置通常是：

```text
~/chipyard/generators/chipyard/src/main/scala/config/TutorialConfigs.scala
```

如果这个文件不存在，可以用 find 查找：

```bash
find ~/chipyard/generators/chipyard/src/main/scala -name "TutorialConfigs.scala"
```

## 编译命令

进入 Chipyard verilator 目录：

```bash
cd ~/chipyard/sims/verilator
```

清理旧 build：

```bash
make clean CONFIG=MyConvAccelConfig
```

重新编译：

```bash
make CONFIG=MyConvAccelConfig
```

如果编译成功，说明 accelerator 已经正确接入 Chipyard。

## 运行测试的基本命令

如果已经有 `test_conv.riscv`，可以运行：

```bash
cd ~/chipyard/sims/verilator

make CONFIG=MyConvAccelConfig run-binary BINARY=~/chipyard/workshop5/test_conv.riscv
```

测试文件路径可以根据实际位置修改。

## 当前 custom instruction 设计

当前 accelerator 使用 `funct7` 区分命令。

```text
funct7 = 0
指令 = CONFIG
rs1 = kernel size
rs2 = output address
rd = 1 表示 success，0 表示 error

funct7 = 1
指令 = LOAD
rs1 = input address
rs2 = kernel address
rd = 1 表示 success，0 表示 error

funct7 = 2
指令 = COMPUTE
rs1 = unused
rs2 = unused
rd = 1 表示 success，0 表示 error

funct7 = 3
指令 = STORE
rs1 = unused
rs2 = unused
rd = 1 表示 success，0 表示 error
```

正确调用顺序：

```c
conv_config(kernel_size, output_addr);
conv_load(input_addr, kernel_addr);
conv_compute();
conv_store();
```

## CONFIG 说明

CONFIG 用来设置 kernel size 和 output address。

```text
rs1 = kernel size
rs2 = output matrix address
```

当前只支持：

```text
1
3
5
```

也就是：

```text
1x1 kernel
3x3 kernel
5x5 kernel
```

如果传入 2、4 或其他值，accelerator 会返回 error。

## LOAD 说明

LOAD 用来从 memory 读取 input matrix 和 kernel。

```text
rs1 = input matrix address
rs2 = kernel address
```

LOAD 会先读取 32x32 input matrix，然后根据 kernel size 读取 kernel。

```text
1x1 kernel 读取 1 个 kernel element。
3x3 kernel 读取 9 个 kernel elements。
5x5 kernel 读取 25 个 kernel elements。
```

## COMPUTE 说明

COMPUTE 执行 convolution。

当前 hardware 内部有最大 5x5 的 25-MAC datapath。

对于不同 kernel size：

```text
1x1 只启用 1 个 MAC term。
3x3 启用 9 个 MAC terms。
5x5 启用 25 个 MAC terms。
```

当前设计每个 cycle 计算一个 output element。

## STORE 说明

STORE 会把 32x32 output matrix 写回 memory。

output address 在 CONFIG 阶段通过 rs2 设置。

所以 STORE 本身不需要再传 output address。

## 数据格式

当前使用 16-bit signed fixed-point 8.8。

例如：

```text
1.0 = 256
```

乘法时：

```text
8.8 x 8.8 会产生 16.16
```

所以 accelerator 会把乘法结果右移 8 bits，让结果回到 8.8 格式。

## Padding 规则

当前只支持 odd-sized kernels：

```text
1x1
3x3
5x5
```

原因是 odd-sized kernel 有明确的中心点，适合 symmetric zero padding。

边界外的 input value 会被当成 0。

## 当前还需要完成的工作

下一步主要是测试和性能分析：

- 写 C test program
- 使用 RoCC macro 调用 accelerator
- 准备 1x1 测试
- 准备 3x3 测试
- 准备 5x5 测试
- 和 CPU reference convolution 结果对比
- 使用 rdcycle 测量 cycle count
- 对比 software-only convolution 和 accelerator convolution
- 整理 FSM diagram
- 整理 datapath diagram
- 整理 memory flow diagram
- 写 report 和 presentation
