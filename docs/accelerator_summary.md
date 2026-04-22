# Conv Accelerator Summary

## 当前实现内容

本分支实现了一个基于 RoCC 的卷积加速器，并已经完成编译集成。

当前实现约束如下：

- kernel 固定为 3x3
- input 固定为 32x32
- output 固定为 32x32
- 数据格式为 16-bit fixed-point (8.8)
- 只保留三条指令：`LOAD / COMPUTE / STORE`

## 主要代码位置

### 1. Accelerator 实现

```text
generators/myaccelerators/src/main/scala/ConvAccelerator.scala

需要你修改TutorilConfigs.scala和使用ConvAccelerator.scala就可以编译了
