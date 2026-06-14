@echo off
REM 运行示例数据的快速脚本
REM 使用前请确保已编译项目：javac -encoding UTF-8 -cp "lib/cplex.jar" -d bin src/**/*.java

echo 运行双层竞争设施选址优化算法示例...
echo 请确保已安装IBM CPLEX并设置正确的库路径

REM 设置CPLEX库路径（根据实际安装路径调整）
set CPLEX_PATH=D:\cplex\bin\x64_win64

REM 运行示例数据
java -Djava.library.path=%CPLEX_PATH% -cp "bin;lib/cplex.jar" run.Main examples/example_data.txt

echo 运行完成！
pause