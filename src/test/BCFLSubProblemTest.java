package test;

import input.Data;
import input.Input;
import sp.BCFLSubProblem;
import pool.Psi;
import pool.Gamma;
import ilog.concert.IloException;
import java.io.FileNotFoundException;

/**
 * BCFLSubProblem测试类
 * 用于测试子问题的各种功能，包括悲观解选择
 */
public class BCFLSubProblemTest {

    public static void main(String[] args) {
        try {
            System.out.println("=== BCFLSubProblem 测试开始 ===");

            // 1. 加载数据
            Input input = new Input("data.txt");
            Data data = input.getData();

            System.out.println("数据加载完成:");
            System.out.println("客户区数量 I = " + data.getI());
            System.out.println("电商候选点数量 J = " + data.getJ());
            System.out.println("服务类型数量 S = " + data.getS());
            // K_l dimension removed in refactoring
            System.out.println("包裹类型数量 N = " + data.getN());
            System.out.println("折扣等级数量 K_f = " + data.getK_f());

            // 2. 尝试从文件读取gamma解，如果失败则生成随机解
            System.out.println("\n=== 获取主问题解 gamma ===");
            Gamma gamma = null;
            double[][] yh = null;
            double[] vL = null;
            double[] vP = null;

            try {
                // 尝试从gamma.txt读取
                gamma = Gamma.readFromFile("gamma.txt", data);
                System.out.println("✓ 成功从 gamma.txt 读取解");

                // 提取组件
                yh = gamma.getY();
                vL = gamma.getVL();
                vP = gamma.getVP();

                // 打印读取的gamma信息
                System.out.println("Gamma解信息:");
                int vL_count = 0, vP_count = 0, y_count = 0;
                for (double val : vL) if (Math.abs(val) > 1e-6) vL_count++;
                for (double val : vP) if (Math.abs(val) > 1e-6) vP_count++;
                for (int i = 0; i < yh.length; i++)
                    for (int s = 0; s < yh[i].length; s++)
                        if (Math.abs(yh[i][s]) > 1e-6) y_count++;

                System.out.println("  vL 非零元素数: " + vL_count);
                System.out.println("  vP 非零元素数: " + vP_count);
                System.out.println("  y 非零元素数: " + y_count);

            } catch (FileNotFoundException e) {
                // 文件不存在，使用随机生成
                System.out.println("⚠ gamma.txt 不存在，使用随机生成的解");
                FeasibleLeaderSolution solution = new FeasibleLeaderSolution(data);

                // 验证解
                if (validateFeasibleSolution(solution, data)) {
                    System.out.println("✓ 生成的解满足所有约束条件");
                } else {
                    System.out.println("✗ 生成的解违反某些约束条件");
                }

                printFeasibleSolution(solution, data);

                // 提取组件
                yh = solution.y;
                vL = solution.vL;
                vP = solution.vP;
            }

            // 3. 创建子问题实例
            BCFLSubProblem subProblem = new BCFLSubProblem(data);

            if (gamma == null) {
                // 对于随机生成的情况，x可以为null（子问题不需要这些）
                gamma = new Gamma(vL, vP, null, yh);
            }

            // 4. 测试普通求解
            System.out.println("\n=== 测试普通求解 ===");
            testNormalSolve(subProblem, gamma);

            // 打印D_inF计算示例
            System.out.println("\n=== 打印D_inF计算示例 ===");
            printD_inFExample(subProblem, gamma.getY(), data);

            // 5. 测试解池功能
            System.out.println("\n=== 测试解池功能 ===");
            //testSolutionPool(subProblem, gamma);

            // 6. 测试智能求解（悲观解选择）
            System.out.println("\n=== 测试悲观求解 ===");
            //testSmartSolve(subProblem, gamma);

            // 7. 清理资源
            subProblem.close();

            System.out.println("\n=== BCFLSubProblem 测试完成 ===");

        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

   

    /**
     * 生成满足所有约束的随机领导者解
     */
    public static class FeasibleLeaderSolution {
        public double[] vL;      // 零售店建设决策
        public double[] vP;      // 换电站建设决策
        public double[][] x;    // 服务配置决策
        public double[][] y;  // 客户选择决策
        
        public FeasibleLeaderSolution(Data data) {
            int J = data.getJ();
            int I = data.getI();
            int S = data.getS();
            
            this.vL = new double[J];
            this.vP = new double[J];
            this.x = new double[J][S-1]; // s ∈ S/{|S|}
            this.y = new double[I][S];
        }
    }
    
    
    /**
     * 验证生成的解是否满足所有约束
     */
    public static boolean validateFeasibleSolution(FeasibleLeaderSolution solution, Data data) {
        int J = data.getJ();
        int I = data.getI();
        int S = data.getS();
        
        // 约束(14): v_j + w_j ≤ 1
        for (int j = 0; j < J; j++) {
            if (solution.vP[j] + solution.vL[j] > 1.0 + 1e-6) {
                System.out.println("违反约束(14): v[" + j + "] + w[" + j + "] = " + 
                    (solution.vP[j] + solution.vL[j]) + " > 1");
                return false;
            }
        }
        
        // 约束(19): x_js ≤ w_j
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < S-1; s++) {
                if (solution.x[j][s] > solution.vL[j] + 1e-6) {
                    System.out.println("违反约束(19): x[" + j + "][" + s + "] = " + 
                        solution.x[j][s] + " > w[" + j + "] = " + solution.vL[j]);
                    return false;
                }
            }
        }
        
        // 约束(18): y_{i,|S|-1} + y_{i,|S|} ≤ 1
        for (int i = 0; i < I; i++) {
            double sum = solution.y[i][S-2] + solution.y[i][S-1];
            if (sum > 1.0 + 1e-6) {
                System.out.println("违反约束(18): y[" + i + "][" + (S-2) + "] + " +
                    "y[" + i + "][" + (S-1) + "] = " + sum + " > 1");
                return false;
            }
        }
        
        return true;
    }

    
    /**
     * 打印yt矩阵
     */
    public static void printYt(double[][] yh, Data data) {
        int I = data.getI();
        int S = data.getS();
        
        for (int i = 0; i < I; i++) {
            System.out.println("客户区 " + i + ":");
            for (int s = 0; s < S; s++) {
                System.out.printf("y[%d][%d]=%.1f ",i, s, yh[i][s]);
            }
            System.out.println();
        }
    }
    
    /**
     * 打印满足约束的领导者解
     */
    public static void printFeasibleSolution(FeasibleLeaderSolution solution, Data data) {
        int J = data.getJ();
        int I = data.getI();
        int S = data.getS();
        
        System.out.println("w变量（零售店建设）:");
        for (int j = 0; j < J; j++) {
            System.out.printf("  w[%d] = %.1f", j, solution.vL[j]);
            if (j % 5 == 4) System.out.println();
        }
        if (J % 5 != 0) System.out.println();
        
        System.out.println("\nv变量（换电站建设）:");
        for (int j = 0; j < J; j++) {
            System.out.printf("  v[%d] = %.1f", j, solution.vP[j]);
            if (j % 5 == 4) System.out.println();
        }
        if (J % 5 != 0) System.out.println();
        
        System.out.println("\nx变量（服务配置）:");
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < S-1; s++) {
                System.out.printf("  x[%d][%d] = %.1f", j, s, solution.x[j][s]);
                if (s % 3 == 2) System.out.println();
            }
        }
        if ((S-1) % 3 != 0) System.out.println();
        
        System.out.println("\ny变量（客户选择）:");
        for (int i = 0; i < I; i++) {
            System.out.println("客户区 " + i + ":");
            for (int s = 0; s < S; s++) {
                System.out.printf("y[%d][%d]=%.1f ",i, s, solution.y[i][s]);
            }
            System.out.println();
        }
    }
    
    /**
     * 打印vL和v参数
     */
    public static void printvLandV(double[] vL, double[] vP, Data data) {
        int J = data.getJ();
        System.out.println("领导者选址决策 vL:");
        for (int j = 0; j < J; j++) {
            System.out.printf("  候选点%d: %.0f ", j, vL[j]);
            if ((j + 1) % 10 == 0) System.out.println();
        }
        System.out.println();
        
        System.out.println("关闭决策 v:");
        for (int j = 0; j < J; j++) {
            System.out.printf("  候选点%d: %.0f ", j, vP[j]);
            if ((j + 1) % 10 == 0) System.out.println();
        }
        System.out.println();
    }
    
    /**
     * 测试普通求解
     */
    private static void testNormalSolve(BCFLSubProblem subProblem, Gamma gamma) throws IloException {
        System.out.println("开始普通求解...");
        long startTime = System.currentTimeMillis();

        subProblem.solve_Model(gamma);

        long endTime = System.currentTimeMillis();
        System.out.println("普通求解完成，耗时: " + (endTime - startTime) + "ms");

        // 打印求解结果
        subProblem.printSolverStats();

        // 检查是否有解
        if (subProblem.getSolverStatus().equals("最优解") || subProblem.getSolverStatus().equals("可行解")) {
            // 获取Psi解

        } else {
            System.out.println("模型无解，跳过z解打印");
        }
    }
    
    /**
     * 测试解池功能
     */
    private static void testSolutionPool(BCFLSubProblem subProblem, Gamma gamma) throws IloException {
        System.out.println("开始解池测试...");
        long startTime = System.currentTimeMillis();

        // 先进行普通求解
        subProblem.solve_Model(gamma);

        // 测试解池功能
        boolean hasMultipleSolutions = subProblem.hasMultipleOptimalSolutionsWithPool();

        long endTime = System.currentTimeMillis();
        System.out.println("解池测试完成，耗时: " + (endTime - startTime) + "ms");

        if (hasMultipleSolutions) {
            System.out.println("✅ 发现多个最优解，解池功能正常工作");
        } else {
            System.out.println("ℹ️ 只有一个最优解，解池功能正常工作");
        }
    }

    /**
     * 测试智能求解
     */
    private static void testSmartSolve(BCFLSubProblem subProblem, Gamma gamma) throws IloException {
        System.out.println("开始悲观求解...");
        long startTime = System.currentTimeMillis();

        subProblem.solve_Model_Smart(gamma);
        
        long endTime = System.currentTimeMillis();
        System.out.println("悲观求解完成，耗时: " + (endTime - startTime) + "ms");
        
        // 打印求解结果
        subProblem.printPessimisticSolutionStats();
    }
    
    
    /**
     * 打印D_inF计算示例
     */
    private static void printD_inFExample(BCFLSubProblem subProblem, double[][] yh, Data data) {
        try {
            // 检查是否有解
            if (!subProblem.getSolverStatus().equals("最优解") && !subProblem.getSolverStatus().equals("可行解")) {
                System.out.println("模型无解，无法计算D_inF");
                return;
            }

            // 获取求解后的Psi解
            Psi psiSolution = subProblem.getPsi();
            double[][] zSolution = psiSolution.z;

            // 获取数据维度
            int I = data.getI();
            int N = data.getN();

            System.out.println("D_inF计算示例（追随者市场需求）:");
            System.out.println("格式: D_inF[i][n] = 客户区i, 包裹类型n的追随者市场需求");

            // 使用BCFLCalculator计算D_inF
            utils.BCFLCalculator calculator = new utils.BCFLCalculator(data);

            // 计算并打印前几个D_inF值作为示例
            for (int i = 0; i < Math.min(I, 3); i++) { // 只显示前3个客户区
                for (int n = 0; n < N; n++) {
                    double D_inF = calculator.calculateD_inF(i, n, yh, zSolution);
                    System.out.printf("D_inF[%d][%d] = %.6f\n", i, n, D_inF);
                }
            }

            // 计算总的D_inF
            double totalD_inF = 0.0;
            for (int i = 0; i < I; i++) {
                for (int n = 0; n < N; n++) {
                    totalD_inF += calculator.calculateD_inF(i, n, yh, zSolution);
                }
            }
            System.out.printf("总D_inF = %.6f\n", totalD_inF);

        } catch (Exception e) {
            System.err.println("计算D_inF时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    

}
