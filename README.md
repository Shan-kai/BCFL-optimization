# 双层竞争设施选址优化算法 (BCFL)

基于 IBM CPLEX 的双层竞争设施选址（BCFL）优化算法，Java 实现。建模 Stackelberg 博弈：领导者（电子零售商，无人机配送）vs 追随者（传统零售商）。使用价值函数双层重构 + 外逼近（OA）切割求解双层 MINLP。

## 功能特性
- 基于 CPLEX 的双层优化求解
- 支持无人机配送和换电站约束
- 实验模式开关（禁用无人机、禁用换电站）
- 批量实验和敏感性分析脚本
- 自动输出重定向和结果汇总

## 系统要求
- Java 8 或更高版本
- IBM CPLEX（需要商业许可证）
- Python 3.6+（用于批量实验脚本）

## 安装

1. **安装 IBM CPLEX**（商业软件，需要许可证）
   - 从 [IBM](https://www.ibm.com/products/ilog-cplex-optimization-studio) 获取 CPLEX Optimization Studio
   - IBM 也提供 [CPLEX Community Edition](https://www.ibm.com/products/ilog-cplex-optimization-studio/community-edition)（小规模问题免费）
   - 将 `cplex.jar` 放置到项目的 `lib/cplex.jar` 路径
   - 设置 CPLEX 原生库路径：
     - **Windows**: `-Djava.library.path=C:\cplex\bin\x64_win64`
     - **Linux**: `-Djava.library.path=/opt/ibm/ILOG/CPLEX_Studio2211/cplex/bin/x86-64_linux`
     - **macOS**: `-Djava.library.path=/Applications/CPLEX_Studio2211/cplex/bin/x86-64_osx`

2. **克隆项目**
   ```bash
   git clone https://github.com/Shan-kai/BCFL-optimization.git
   cd BCFL-optimization
   ```

3. **编译项目**
   ```bash
   javac -encoding UTF-8 -cp "lib/cplex.jar" -d bin src/input/*.java src/utils/*.java src/pool/*.java src/mp/*.java src/sp/*.java src/result/*.java src/run/*.java
   ```

## 快速开始

### 运行单个实例
```bash
# 使用默认数据文件
java -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# 指定数据文件
java -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main data.txt

# 使用示例数据（快速验证）
java -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main examples/example_data.txt

# 使用快速运行脚本（Windows）
run_example.bat

# 使用快速运行脚本（Linux/Mac）
chmod +x run_example.sh
./run_example.sh
```

### 实验模式
```bash
# 禁用无人机服务
java -Ddisable.drone=true -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# 禁用换电站
java -Ddisable.charging=true -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# 自定义输出目录
java -Doutput.dir=output_myexperiment -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main
```

### 批量实验
```bash
# 运行所有实验数据
python run_scripts/run_all.py
```

## 项目结构
```
BCFL/
├── src/                    # 源代码
│   ├── input/              # 数据解析
│   ├── mp/                 # 主问题
│   ├── sp/                 # 子问题
│   ├── pool/               # 解池管理
│   ├── utils/              # 工具类
│   ├── result/             # 结果输出
│   ├── run/                # 主程序入口
│   └── test/               # 测试
├── examples/               # 示例数据
├── run_scripts/            # 批量实验脚本（Python）
├── lib/                    # CPLEX JAR（git 忽略，需自行放置）
├── data.txt                # 默认输入数据
├── run_example.bat         # Windows 快速运行脚本
├── run_example.sh          # Linux/Mac 快速运行脚本
├── LICENSE                 # MIT 许可证
└── README.md               # 项目说明
```

## 示例数据
在 `examples/` 目录中包含一个小规模测试数据文件，用于快速验证系统功能。

## 数据格式
输入数据文件为 tab 分隔的文本文件，包含以下段落：

| 段落 | 说明 |
|------|------|
| 头部参数 | `J`（领导者候选点数）、`J_0`（现有零售商数）、`I`（客户区数）、`S`（服务类型数）、`K_f`（追随者设施类型数）、`N`（配送模式数） |
| J 节点 | 领导者候选设施坐标、建设成本、运营成本、服务质量参数 |
| J_0 节点 | 现有零售商坐标 |
| I 节点 | 客户区坐标、各配送模式需求参数 |
| S 服务参数 | 服务类型偏好参数、配送时间、服务范围、配送成本 |
| 全局参数 | 领导者/追随者预算、出行时间/成本系数、市场份额参数 |

## 输出说明
输出文件保存在 `[output.dir]/MMdd_HHmm-<datafile>-output<modeSuffix>.txt`，默认输出目录为 `output无人机基础实验`。

## 开发指南

### 修改约束
1. 在 `buildOmegaConstraints()` 中创建
2. 用 `cplex.addLe/Ge/Eq()` 添加
3. OA 切通过回调动态添加

### 修改决策变量
1. 在 `initializeVariables()` 中声明
2. 添加到 `buildObjectiveFunction()`
3. 更新 `serializeBestSolution()` 中的编码

## 测试
```bash
# 编译测试
javac -encoding UTF-8 -cp "lib/cplex.jar;bin" -d bin src/test/*.java

# 运行测试
java -cp "bin;lib/cplex.jar" test.BCFLMasterProblemTest
java -cp "bin;lib/cplex.jar" test.BCFLSubProblemTest
java -cp "bin;lib/cplex.jar" test.DataTest
```

## 常见问题

### CPLEX 路径问题
确保 `java.library.path` 指向正确的 CPLEX 原生库路径。

### 内存不足
大规模问题建议增加内存：
```bash
java -Xmx4g -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main
```

## 许可证
本项目采用 [MIT 许可证](LICENSE)。

## 贡献
欢迎贡献！请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解详情。

---

# Bilevel Competitive Facility Location (BCFL) Optimization

A Bilevel Competitive Facility Location (BCFL) optimization algorithm based on IBM CPLEX, implemented in Java. Models Stackelberg game: Leader (e-book retailer, drone delivery) vs Follower (traditional retailer). Solves bilevel MINLP using value function reformulation + outer approximation (OA) cuts.

## Features
- CPLEX-based bilevel optimization
- Supports drone delivery and charging station constraints
- Experiment mode switches (disable drone, disable charging)
- Batch experiments and sensitivity analysis scripts
- Automatic output redirection and result summarization

## Requirements
- Java 8 or higher
- IBM CPLEX (commercial license required)
- Python 3.6+ (for batch experiment scripts)

## Installation

1. **Install IBM CPLEX** (commercial license required)
   - Get CPLEX Optimization Studio from [IBM](https://www.ibm.com/products/ilog-cplex-optimization-studio)
   - IBM also offers [CPLEX Community Edition](https://www.ibm.com/products/ilog-cplex-optimization-studio/community-edition) (free for small problems)
   - Place `cplex.jar` at `lib/cplex.jar` in the project root
   - Set CPLEX native library path:
     - **Windows**: `-Djava.library.path=C:\cplex\bin\x64_win64`
     - **Linux**: `-Djava.library.path=/opt/ibm/ILOG/CPLEX_Studio2211/cplex/bin/x86-64_linux`
     - **macOS**: `-Djava.library.path=/Applications/CPLEX_Studio2211/cplex/bin/x86-64_osx`

2. **Clone the project**
   ```bash
   git clone https://github.com/Shan-kai/BCFL-optimization.git
   cd BCFL-optimization
   ```

3. **Compile the project**
   ```bash
   javac -encoding UTF-8 -cp "lib/cplex.jar" -d bin src/input/*.java src/utils/*.java src/pool/*.java src/mp/*.java src/sp/*.java src/result/*.java src/run/*.java
   ```

## Quick Start

### Run a single instance
```bash
# Using default data file
java -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# Specify data file
java -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main data.txt

# Use example data (quick verification)
java -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main examples/example_data.txt
```

### Experiment modes
```bash
# Disable drone service
java -Ddisable.drone=true -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# Disable charging stations
java -Ddisable.charging=true -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main

# Custom output directory
java -Doutput.dir=output_myexperiment -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main
```

### Batch experiments
```bash
# Run all experiment data
python run_scripts/run_all.py
```

## Project Structure
```
BCFL/
├── src/                    # Source code
│   ├── input/              # Data parsing
│   ├── mp/                 # Master problem
│   ├── sp/                 # Subproblem
│   ├── pool/               # Solution pool management
│   ├── utils/              # Utility classes
│   ├── result/             # Result output
│   ├── run/                # Main program entry
│   └── test/               # Tests
├── examples/               # Example data
├── run_scripts/            # Batch experiment scripts (Python)
├── lib/                    # CPLEX JAR (git ignored, place manually)
├── data.txt                # Default input data
├── run_example.bat         # Windows quick-run script
├── run_example.sh          # Linux/Mac quick-run script
├── LICENSE                 # MIT License
└── README.md               # Project documentation
```

## Example Data
A small-scale test data file is included in the `examples/` directory for quick system verification.

## Data Format
Input data files are tab-separated text files containing the following sections:

| Section | Description |
|---------|-------------|
| Header | `J` (leader candidates), `J_0` (existing retailers), `I` (customer zones), `S` (service types), `K_f` (follower facility types), `N` (delivery modes) |
| J nodes | Leader candidate coordinates, construction costs, operating costs, service quality parameters |
| J_0 nodes | Existing retailer coordinates |
| I nodes | Customer zone coordinates, demand parameters per delivery mode |
| S parameters | Service preference parameters, delivery time, service range, delivery costs |
| Global | Leader/follower budgets, travel time/cost coefficients, market share parameters |

## Output
Output files are saved in `[output.dir]/MMdd_HHmm-<datafile>-output<modeSuffix>.txt`, default output directory is `output无人机基础实验`.

## Development Guide

### Modifying constraints
1. Create in `buildOmegaConstraints()`
2. Add using `cplex.addLe/Ge/Eq()`
3. OA cuts added dynamically via callbacks

### Modifying decision variables
1. Declare in `initializeVariables()`
2. Add to `buildObjectiveFunction()`
3. Update encoding in `serializeBestSolution()`

## Testing
```bash
# Compile tests
javac -encoding UTF-8 -cp "lib/cplex.jar;bin" -d bin src/test/*.java

# Run tests
java -cp "bin;lib/cplex.jar" test.BCFLMasterProblemTest
java -cp "bin;lib/cplex.jar" test.BCFLSubProblemTest
java -cp "bin;lib/cplex.jar" test.DataTest
```

## Troubleshooting

### CPLEX path issues
Ensure `java.library.path` points to the correct CPLEX native library path.

### Out of memory
For large-scale problems, increase memory:
```bash
java -Xmx4g -Djava.library.path=D:\cplex\bin\x64_win64 -cp "bin;lib/cplex.jar" run.Main
```

## License
This project is licensed under the [MIT License](LICENSE).

## Contributing
Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details.
