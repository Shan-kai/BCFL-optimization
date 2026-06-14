#!/bin/bash
# 运行示例数据的快速脚本
# 使用前请确保已编译项目：javac -encoding UTF-8 -cp "lib/cplex.jar" -d bin src/**/*.java

echo "运行双层竞争设施选址优化算法示例..."
echo "请确保已安装IBM CPLEX并设置正确的库路径"

# 设置CPLEX库路径（根据实际安装路径调整）
CPLEX_PATH="/opt/ibm/ILOG/CPLEX_Studio2211/cplex/bin/x86-64_linux"

# 运行示例数据
java -Djava.library.path=$CPLEX_PATH -cp "bin:lib/cplex.jar" run.Main examples/example_data.txt

echo "运行完成！"