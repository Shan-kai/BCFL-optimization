# 贡献指南

感谢您对双层竞争设施选址优化项目的关注！我们欢迎各种形式的贡献。

## 如何贡献

### 报告问题
1. 在 GitHub Issues 中搜索是否已有类似问题
2. 如果没有，请创建新 Issue，包含：
   - 问题描述
   - 复现步骤
   - 期望行为和实际行为
   - 环境信息（Java 版本、CPLEX 版本、操作系统）

### 提交代码
1. Fork 项目仓库
2. 创建功能分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -m "Add some feature"`
4. 推送到分支：`git push origin feature/your-feature`
5. 创建 Pull Request

### 代码规范
- 遵循现有代码风格
- 使用驼峰命名法（camelCase）
- 添加必要的注释
- 确保编译通过：`javac -encoding UTF-8 -cp "lib/cplex.jar" -d bin src/**/*.java`

### 测试
- 运行现有测试确保没有破坏功能
- 如果添加新功能，请添加相应测试
- 测试命令：
  ```bash
  javac -encoding UTF-8 -cp "lib/cplex.jar;bin" -d bin src/test/*.java
  java -cp "bin;lib/cplex.jar" test.BCFLMasterProblemTest
  java -cp "bin;lib/cplex.jar" test.BCFLSubProblemTest
  java -cp "bin;lib/cplex.jar" test.DataTest
  ```

## 开发环境设置
1. 安装 Java 8+ 和 IBM CPLEX
2. 克隆项目并放置 `cplex.jar` 到 `lib/` 目录
3. 编译项目：参考 README.md 中的编译说明

## 提交规范
- 使用清晰的提交信息
- 每个提交专注于一个功能或修复
- 避免提交敏感信息（如 API 密钥、许可证密钥、个人数据）

## 问题和建议
如有任何问题或建议，请通过 GitHub Issues 联系我们。

感谢您的贡献！

---

# Contributing Guide

Thank you for your interest in the Bilevel Competitive Facility Location (BCFL) optimization project! We welcome contributions of all kinds.

## How to Contribute

### Reporting Issues
1. Search GitHub Issues for similar problems first
2. If none found, create a new Issue including:
   - Problem description
   - Steps to reproduce
   - Expected vs actual behavior
   - Environment info (Java version, CPLEX version, OS)

### Submitting Code
1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit changes: `git commit -m "Add some feature"`
4. Push to your branch: `git push origin feature/your-feature`
5. Create a Pull Request

### Code Style
- Follow existing code style
- Use camelCase naming
- Add necessary comments
- Ensure compilation passes: `javac -encoding UTF-8 -cp "lib/cplex.jar" -d bin src/**/*.java`

### Testing
- Run existing tests to ensure no regressions
- Add tests for new features
- Test commands:
  ```bash
  javac -encoding UTF-8 -cp "lib/cplex.jar;bin" -d bin src/test/*.java
  java -cp "bin;lib/cplex.jar" test.BCFLMasterProblemTest
  java -cp "bin;lib/cplex.jar" test.BCFLSubProblemTest
  java -cp "bin;lib/cplex.jar" test.DataTest
  ```

## Development Environment Setup
1. Install Java 8+ and IBM CPLEX
2. Clone the project and place `cplex.jar` in the `lib/` directory
3. Compile: see README.md for detailed instructions

## Commit Guidelines
- Write clear commit messages
- One feature or fix per commit
- Never commit sensitive information (API keys, license keys, personal data)

## Questions & Suggestions
For any questions or suggestions, please reach out via GitHub Issues.

Thank you for your contribution!
