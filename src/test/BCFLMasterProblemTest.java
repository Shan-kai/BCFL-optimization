package test;

import mp.BCFLMasterProblem;
import sp.BCFLSubProblem;
import input.Data;


/**
 * BCFL主问题测试类
 * 测试主问题的求解并输出包含初始zt的Ξ集合的最优解
 */
public class BCFLMasterProblemTest {
    
    public static void main(String[] args) {
        try {
            System.out.println("=== BCFL主问题测试开始 ===");
            
            // 1. 读取数据文件
            System.out.println("1. 读取数据文件...");
            String dataFilePath = "data.txt"; // 默认数据文件路径
            if (args.length > 0) {
                dataFilePath = args[0]; // 使用命令行参数指定的文件路径
            }
            
            Data data = Data.readFromTxt(dataFilePath);
            
            System.out.println("数据文件读取成功:");
            System.out.println("  - 客户区数量 I: " + data.getI());
            System.out.println("  - 电商候选点数量 J: " + data.getJ());
            System.out.println("  - 服务数量 S: " + data.getS());
            // K_l dimension removed in refactoring
            System.out.println("  - 包裹类型数量 N: " + data.getN());
            System.out.println("  - 下层候选点数量 J: " + data.getJ());
            System.out.println("  - 折扣等级数量 K_f: " + data.getK_f());
            
            
            // 2. 创建主问题和子问题（内部会自动创建计算器）
            System.out.println("\n2. 创建主问题和子问题...");
            BCFLMasterProblem masterProblem = new BCFLMasterProblem(data);
            BCFLSubProblem subProblem = new BCFLSubProblem(data);
            
            // 4. 求解主问题（带CSP以收集分支定界过程中的整数解）
            System.out.println("\n4. 求解主问题...");
            boolean solveSuccess = masterProblem.solve_Model();
            
            if (solveSuccess) {
                System.out.println("主问题求解成功！");
                
                // 5. 输出求解结果
                System.out.println("\n5. 求解结果:");
                
                // 输出目标函数值
                double objectiveValue = masterProblem.getMpObjectiveValue();
                System.out.println("目标函数值: " + objectiveValue);
                
                // 输出最优解
                double[] bestSolution = masterProblem.getBestSolution();
                System.out.println("最优解数组长度: " + bestSolution.length);
                
                // 输出分支定界过程中收集的解
                java.util.List<pool.FeasibleSolution> collectedSolutions = masterProblem.getCollectedSolutions();
                System.out.println("\n分支定界过程中收集的(gamma, psi)解对数量: " + collectedSolutions.size());
                
                for (int i = 0; i < collectedSolutions.size(); i++) {
                    pool.FeasibleSolution sol = collectedSolutions.get(i);
                    System.out.printf("  解对 #%d: Pi=%.4f, Phi=%.4f\n", i+1, sol.PiH, sol.PhiH);
                }

                // 输出时间统计
                System.out.println("\n--- 时间统计 ---");
                System.out.printf("总求解时间:   %.4f 秒\n", masterProblem.getTotalSolveTime());
                System.out.printf("子问题时间:   %.4f 秒\n", masterProblem.getSubProblemTime());
                System.out.printf("主问题时间:   %.4f 秒\n", masterProblem.getMasterProblemTime());

            } else {
                System.err.println("主问题求解失败！");
            }
            
            System.out.println("\n=== BCFL主问题测试结束 ===");
            
        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    
    
    
}
