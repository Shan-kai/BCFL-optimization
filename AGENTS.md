# AGENTS.md - AI Agent Project Guide

与用户交流始终使用中文。

## 项目概述

基于 **IBM CPLEX** 的**双层竞争设施选址（BCFL）**优化算法，Java 实现。
建模 **Stackelberg 博弈**：领导者（电子零售商，无人机配送）vs 追随者（传统零售商）。
使用**价值函数双层重构** + **外逼近（OA）切割**求解双层 MINLP。

## 快速命令

### 编译
```bash
# 完整编译（UTF-8 编码，Windows classpath 用 ;）
javac -encoding UTF-8 -cp "lib/cplex.jar" -d bin src/input/*.java src/utils/*.java src/pool/*.java src/mp/*.java src/sp/*.java src/result/*.java src/run/*.java

# 编译测试
javac -encoding UTF-8 -cp "lib/cplex.jar;bin" -d bin src/test/*.java
```

### 运行
```bash
# 单实例（默认 data.txt）
java -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# 指定数据文件
java -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main alldata/100-30.txt

# 实验模式开关（JVM 参数）
java -Ddisable.drone=true -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main
java -Ddisable.charging=true -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# 自定义输出目录
java -Doutput.dir=output_myexperiment -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# 批量运行（自动编译+并发执行 alldata/*.txt，结果汇总为 CSV）
python run_scripts/run_all.py
```

**输出文件约定**：输出保存在 `[output.dir]/MMdd_HHmm-<datafile>-output<modeSuffix>.txt`。
`output.dir` 默认为 `output无人机基础实验`（无论 modeSuffix 如何）。

### 前置要求
- IBM CPLEX 已安装，`lib/cplex.jar` 存在
- CPLEX 原生库路径在 PATH 或 `java.library.path` 中（Windows: `D:\cplex\bin\x64_win64` 或 `C:\cplex\bin\x64_win64`）
- 大规模问题建议 `-Xmx4g`

## 核心架构

### 算法流程（Main.java）
```
初始化:
  1. 求解 MaxLeaderProblem 得到 gammaMax（领导者最大需求解）
  2. 代入子问题求追随者响应 psiMax
  3. 用 psiMax 初始化 Ξ 集合，(gammaMax, psiMax) 加入 A 集合
  4. LB = Pi(gammaMax, psiMax)

循环直到 (UB-LB)/|UB| ≤ 1e-3 或超时(3h):
  1. 同步 A 集合 → MP，重置 IntYZSet/IntZSet
  2. 求解主问题 MP → 获取 gamma^h 和 UB
  3. 批量收集分支定界中的所有 gamma，统一求解子问题
  4. 对每个 (gamma, psi)：添加到 Ξ 和 A 集合，更新 LB
```

### 两个关键集合（每次迭代必须同步）

| 集合 | 管理类 | 内容 | 用途 |
|------|--------|------|------|
| **A 集合** | `SolPool.java` | {(γ^h, ψ^h)} 可行解对 | 逻辑切约束、phi约束 |
| **Ξ 集合** | `XiSet.java` | 历史追随者解 {ψ^h} | tau变量的OA切约束 |

同步模式：
```java
MP.setFeasibleSet(solpoolInstance.getFeasibleSet());  // 同步A集合
MP.addToXiSet(psi);                                    // 添加到Ξ集合
solpoolInstance.addFeasibleSolution(g, psi);           // 添加到A集合
MP.resetIntYZSet();                                    // 重置OA切整数解记录
CSP.clearIntZSet();
```

### 目录结构
```
src/
├── input/     Data.java（数据解析）, Input.java
├── pool/      Gamma, Psi, FeasibleSolution, SolPool, XiSet
├── mp/        BCFLMasterProblem.java, MaxLeaderProblem.java
├── sp/        BCFLSubProblem.java
├── utils/     BCFLCalculator.java（所有数学计算）
├── test/      测试类
├── result/    结果输出
└── run/       Main.java（入口）

alldata/       批量实验数据文件（命名格式: I-J.txt，如 100-30.txt）
run_scripts/   Python 批量实验脚本
output_*/      各实验输出目录
```

## 关键类

### BCFLMasterProblem (MP)
领导者优化问题，目标 `max Σ_i Σ_n d_in - phi`。
- `initializeVariables()` — 声明变量
- `buildObjectiveFunction()` — 构建目标
- `buildOmegaConstraints()` — 构建约束
- `OACutCallback` — 整数解回调，动态添加 OA 切割
- OA 切**通过回调动态添加**，不在模型构建时静态添加

### BCFLSubProblem (SP)
追随者优化问题，给定 γ^h 求解最优响应。目标 `max Σ_i Σ_n dF_in`。
含 `OAConstraintCallback` 为 `dF[i][n] ≤ D_inF(y,z)` 添加切割。

### MaxLeaderProblem
初始化用：求解领导者最大可能需求（不考虑追随者竞争），用于生成高质量的初始 Ξ 集合。

### BCFLCalculator
所有数学计算核心，含预计算缓存优化。
- 效用函数：`calculateU_sk`, `calculateU_ijk`, `calculateU_ij`
- 需求函数：`calculateD_in`, `calculateD_inL`, `calculateD_inF`
- 批量梯度：`calculateD_in_and_AllGradients()`, `calculateD_inF_and_AllGradients()`
- **注意**：计算公式不能加入任何数值保护，避免影响函数性质

### Data.java
节点类型：`type=1` 领导者候选点 J，`type=2` 追随者候选点 J_f，`type=0` 现有零售商 J_0，`type=-1` 客户区 I。

## 开发规范

### 修改约束
1. 在 `buildOmegaConstraints()` 中创建
2. 用 `cplex.addLe/Ge/Eq()` 添加，命名便于调试
3. OA 切通过回调动态添加

### 修改决策变量
1. 在 `initializeVariables()` 中声明
2. 按需添加到 `buildObjectiveFunction()`
3. 更新 `serializeBestSolution()` 中的编码
4. 更新 Main.java 中的提取方法

### 添加新类
- 按现有包结构放置，中文注释，驼峰命名

## 测试
```bash
javac -encoding UTF-8 -cp "lib/cplex.jar;bin" -d bin src/test/*.java
java -cp "bin;lib/cplex.jar" test.BCFLMasterProblemTest
java -cp "bin;lib/cplex.jar" test.BCFLSubProblemTest
java -cp "bin;lib/cplex.jar" test.DataTest
java -cp "bin;lib/cplex.jar" test.GradientValidationTest
java -cp "bin;lib/cplex.jar" test.MaxLeaderProblemTest
```

## 实验工作流

### 数据生成
```bash
# 从基准文件生成不同 I×J 规模的数据集
python generate_alldata.py
```
数据文件命名格式：`I-J.txt`（如 `100-30.txt` 表示 I=100, J=30）。

### 批量实验
```bash
# run_scripts/run_all.py — 自动编译 + 批量运行 alldata/*.txt
# 输出保存到 output_MP(BI)SP(BI)/，结果汇总为 results_*.csv
python run_scripts/run_all.py
```

### 敏感性分析脚本（run_scripts/）
- `run_sensitivity_lambda.py` — lambda 参数敏感性
- `run_sensitivity_lambda_Q2.py` — lambda + Q2 参数
- `run_sensitivity_lambda_l_f.py` — lambda_l, lambda_f 敏感性
- `run_sensitivity_lambda_l_f_Blbf.py` — lambda_l, lambda_f + 预算
- `run_sensitivity_R3_Mcoef.py` — R3 + M 系数敏感性
- `run_enum_all.py` — 全枚举实验

## 注意事项
1. **CPLEX 路径**：VS Code 配置用 `C:\cplex`，AGENTS.md 示例用 `D:\cplex`，以实际安装为准
2. **数值稳定性**：leaderSum≈0 时 OA 切使用平移后的 y 值
3. **集合同步**：每次迭代必须正确同步 A 集合和 Ξ 集合
4. **输出重定向**：Main.java 自动将输出重定向到 `[output.dir]/MMdd_HHmm-<datafile>-output<modeSuffix>.txt`
5. **超时**：主程序内置 3 小时超时限制
6. **CPLEX 许可证**：通常限制并发数为 1，批量实验脚本 CONCURRENCY 默认为 1


