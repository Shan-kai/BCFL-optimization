package sp;
import input.Data;
import ilog.concert.*;
import ilog.cplex.*;
import utils.BCFLCalculator;
import utils.BCFLCalculator.SPGradientResult;
import pool.Gamma;
import java.util.List;
import java.util.ArrayList;

/**
 * 分支定界子问题求解类，使用CPLEX构建并求解模型
 * 优化版本：使用批量梯度计算，大幅提升OA切割效率
 */
public class BCFLSubProblem {
    private final Data data;
    private final IloCplex cplex;
    private final BCFLCalculator calculator;

    // 决策变量
    private IloNumVar[][] z;  // z[j][k]: 下层候选点j∈J_f选择折扣等级k (0-1变量)
    private IloNumVar[] vF;   // wF[j]: 追随者在候选点j∈J_f建设 (0-1变量)
    private IloNumVar[][] dF;  // dF[i][n]: 辅助变量，D_in^F的线性近似

    // 切割相关
    private int currentIteration;
    private double[][] currentYh;  // 当前传入的yh
    private List<double[][]> IntZSet; // 存储需要加OA切的整数解

    // 维度信息
    private final int J;
    private final int K_f;  // 折扣等级数量
    private final int I;    // 客户区数量
    private final int N;    // 包裹类型数量

    // 加速策略开关（由Main.java统一控制）
    private boolean fixVar = false;       // 追随者变量固定 z[j][k]=0（若CF_j+f_jk>B_f）

    public BCFLSubProblem(Data data) throws IloException {
        this.data = data;
        this.cplex = new IloCplex();
        this.cplex.setOut(null); // 关闭CPLEX日志输出
        this.cplex.setWarning(null); // 关闭CPLEX警告输出

        this.calculator = new BCFLCalculator(data);

        // 初始化维度参数

        this.J = data.getJ();
        this.K_f = data.getK_f();
        this.I = data.getI();
        this.N = data.getN();

        // 注意：变量初始化移到buildModel中，因为clearModel会清除所有变量
        // initializeVariables(); // 已移到buildModel中

        // 初始化切割相关
        this.currentIteration = 0;
        this.IntZSet = new ArrayList<>();

    }

    /**
     * 初始化CPLEX决策变量
     */
    private void initializeVariables() throws IloException {
        // z[j][k]: 0-1整数变量, j∈J_f (下层候选点)
        z = new IloNumVar[J][K_f];
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                z[j][k] = cplex.boolVar("z_" + j + "_" + k);
            }
        }

        // wF[j]: 0-1整数变量, j∈J_f
        vF = new IloNumVar[J];
        for (int j = 0; j < J; j++) {
            vF[j] = cplex.boolVar("wF_" + j);
        }

        // dF[i][n]: 连续变量，非负
        dF = new IloNumVar[I][N];
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                dF[i][n] = cplex.numVar(0, 1e8, "dF_" + i + "_" + n);
            }
        }
    }

    /**
     * 求解子问题（Main.java调用的接口）
     * 使用回调函数在分支定界过程中添加OA切割
     * @param gamma 主问题的Gamma解，包含y, vL, vP等信息
     */
    public void solve_Model(Gamma gamma) throws IloException {
        this.currentIteration = 0;
        this.currentYh = gamma.y; // 设置当前传入的y解
        this.IntZSet.clear(); // 重置OA切整数解记录

        buildModel(gamma.y, gamma.vL, gamma.vP, currentIteration);

        // 设置回调函数
        cplex.use(new OAConstraintCallback());

        // 记录求解开始时间
        double startTime = cplex.getCplexTime();
        
        // 求解模型
        if (cplex.solve()) {

            // 打印求解结果摘要
            double obj = cplex.getObjValue();
            System.out.println("=== 求解结果 ===");
            System.out.println("求解状态: " + cplex.getStatus()+",最优目标值: " + obj); // Optimal, Feasible, Infeasible 等

            System.out.println("求解时间: " + (cplex.getCplexTime() - startTime) + " 秒");
            System.out.println("==================");
            
            printSolutionStats();
        } else {
            System.out.println("=== 求解结果 ===");
            System.out.println("模型未求得可行解，状态: " + cplex.getStatus());
            System.out.println("==================");
            throw new IloException("子问题求解失败，状态: " + cplex.getStatus());
        }
    }
    
    /**
     * OA切割回调函数类（优化版：使用批量梯度计算）
     */
    private class OAConstraintCallback extends IloCplex.LazyConstraintCallback {
        @Override
        protected void main() throws IloException {
            try {
                // 获取当前整数解
                double[][] zBar = getCurrentZSolution();

                boolean hasViolation = false;
                int cutCount = 0;
                int maxCutsPerCall = 10000;

                for (int i = 0; i < I; i++) {
                    for (int n = 0; n < N; n++) {
                        // 获取当前dF值
                        double dinFValue = getValue(dF[i][n]);

                        // 使用批量计算方法，一次获取D_inF和所有梯度
                        SPGradientResult result = calculator.calculateD_inF_and_AllGradients(i, n, currentYh, zBar);
                        double D_inF = result.D_inF;

                        // 检查违反约束
                        double violationThreshold = (1.0 + 1e-4) * D_inF;

                        if (dinFValue > violationThreshold) {
                            // 使用批量计算的梯度添加OA切割
                            //addOACutConstraintBatch(i, n, zBar, D_inF, result.gradients);
                            // 添加次模切割(Submodular-cut)
                            addSubmodularCutBatch(i, n, zBar, D_inF);
                            hasViolation = true;
                            cutCount++;
                        }
                    }
                    if (cutCount >= maxCutsPerCall) {
                        break;
                    }
                }

                if (hasViolation) {
                    // 将整数解添加到IntZSet
                    addToIntZSet(zBar);
                }
            } catch (Exception e) {
                System.err.println("回调函数执行出错: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * 获取当前z解
         */
        private double[][] getCurrentZSolution() throws IloException {
            double[][] zBar = new double[J][K_f];
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    zBar[j][k] = getValue(z[j][k]);
                }
            }
            return zBar;
        }

        /**
         * 将整数解添加到IntZSet
         */
        private void addToIntZSet(double[][] zBar) {
            double[][] zCopy = new double[J][K_f];
            for (int j = 0; j < J; j++) {
                System.arraycopy(zBar[j], 0, zCopy[j], 0, K_f);
            }
            IntZSet.add(zCopy);
        }

        /**
         * 使用预计算的梯度添加OA切割（优化版）
         */
        private void addOACutConstraintBatch(int i, int n, double[][] zBar, double D_inF, double[][] gradients) throws IloException {
            IloLinearNumExpr cut = getCplex().linearNumExpr();
            cut.addTerm(1.0, dF[i][n]);

            // 使用预计算的梯度
            double constant = D_inF;
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    cut.addTerm(-gradients[j][k], z[j][k]);
                    constant -= gradients[j][k] * zBar[j][k];
                }
            }

            IloRange constraint = cplex.le(cut, constant);
            add(constraint);
        }

        /**
         * 添加次模切割(Submodular-cut)
         * d_{in}^F <= D^F_{in}(y^h,\bar z)
         *   + sum_{(j,k) in M^0(\bar z)} rho_{in(j,k)}(M^1(\bar z)) * z_{jk}
         *   - sum_{(j,k) in M^1(\bar z)} rho_{injk}(M^1(\bar z)\\{(j,k)})) * (1 - z_{jk})
         */
        private void addSubmodularCutBatch(int i, int n, double[][] zBar, double D_inF) throws IloException {
            IloLinearNumExpr cut = getCplex().linearNumExpr();
            cut.addTerm(1.0, dF[i][n]);
            double constant = D_inF;

            // M^1(\bar z): 计算 rho^- = D^F_{in}(Z/\{j,k\} U {(j,k)}) - D^F_{in}(Z/\{j,k\})
            // 其中 Z/\{j,k\} 中设施j固定为最高等级(K_f-1)，其余z_{j,k'}=0（包括jk）
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    if (zBar[j][k] > 0.5) {
                        double[][] zBase = cloneZMatrix(zBar);
                        // 设施j固定为最高等级，其余为0
                        for (int kk = 0; kk < K_f; kk++) {
                            zBase[j][kk] = (kk == K_f - 1) ? 1.0 : 0.0;
                        }
                        double DBase = calculator.calculateD_inF(i, n, currentYh, zBase);
                        double[][] znoJK = cloneZMatrix(zBase);
                        znoJK[j][k] = 0;
                        double DnoJK = calculator.calculateD_inF(i, n, currentYh, znoJK);
                        double rhoMinus = DBase - DnoJK;
                        // -rhoMinus * (1 - z_{jk}) => constant -= rhoMinus, term += rhoMinus * z_{jk}
                        constant -= rhoMinus;
                        cut.addTerm(-rhoMinus, z[j][k]);
                    }
                }
            }

            // M^0(\bar z): 计算 rho^+ = D^F_{in}(M^1 U {(j,k)}) - D^F_{in}(M^1)
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    if (zBar[j][k] < 0.5) {
                        double[][] zPlus = cloneZMatrix(zBar);
                        zPlus[j][k] = 1.0;
                        double DPlus = calculator.calculateD_inF(i, n, currentYh, zPlus);
                        double rhoPlus = DPlus - D_inF;
                        cut.addTerm(-rhoPlus, z[j][k]);
                    }
                }
            }

            add(getCplex().le(cut, constant));
        }
    }
    
    /**
     * 打印解统计信息
     */
    private void printSolutionStats() throws IloException {
        System.out.println("=== 解统计信息 ===");

        // 打印z解（完整矩阵）
        System.out.println("z解 (完整矩阵):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  候选点 %d: ", j);
            for (int k = 0; k < K_f; k++) {
                double val = cplex.getValue(z[j][k]);
                System.out.printf("k%d=%.1f ", k, val);
                }
            System.out.println();
        }

        // 打印wF解
        System.out.println("vF解:");
        for (int j = 0; j < J; j++) {
            double val = cplex.getValue(vF[j]);
            System.out.printf("候选点%d: %.1f ", j, val);
        }
        System.out.println();
        
        // 打印dF解（完整矩阵）
        // System.out.println("dF解 (完整矩阵):");
        // for (int i = 0; i < I; i++) {
        //     System.out.printf("  客户区 %d: ", i);
        //     for (int n = 0; n < N; n++) {
        //         double val = cplex.getValue(dF[i][n]);
        //         System.out.printf("包裹%d=%.6f ", n, val);
        //     }
        //     System.out.println();
        // }

        // 打印当前解下的DinF（完整矩阵）
        System.out.println("DinF解 :");
        double sumDinF = 0.0;
        double[][] currentZ = new double[J][K_f];
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                currentZ[j][k] = cplex.getValue(z[j][k]);
            }
        }

        for (int i = 0; i < I; i++) {
            //System.out.printf("  客户区 %d: ", i);
            for (int n = 0; n < N; n++) {
                double DinF = calculator.calculateD_inF(i, n, currentYh, currentZ);
                //System.out.printf("包裹%d=%.6f ", n, DinF);
                sumDinF += DinF;
            }
            //System.out.println();
        }
        System.out.printf("DinF总和: %.6f\n", sumDinF);

        // 打印目标函数值
        System.out.printf("目标函数值: %.6f\n", cplex.getObjValue());

        // 打印IntZSet信息
        System.out.printf("  IntZSet大小: %d\n", IntZSet.size());

        // 检查OA约束违反情况
        System.out.println("\n=== OA约束违反检查 (dF) ===");
        int dFViolationCount = 0;

        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                double dF_val = cplex.getValue(dF[i][n]);
                double D_inF = calculator.calculateD_inF(i, n, currentYh, currentZ);

                // 检查是否违反: dF[i][n] > D_inF * (1.0 + 1e-4)
                double threshold = D_inF * (1.0 + 1e-4);
                if (dF_val > threshold) {
                    double violation = (dF_val - D_inF) / Math.max(Math.abs(D_inF), 1e-9);
                    dFViolationCount++;

                    if (dFViolationCount <= 5) { // 只打印前5个违反
                        System.out.printf("  违反[%d]: dF[%d][%d]=%.6f > D_inF[%d][%d]=%.6f (差值(比例)=%.6f)\n",
                                        dFViolationCount, i, n, dF_val, i, n, D_inF, violation);
                    }
                }
            }
        }

        if (dFViolationCount > 0) {
            System.out.printf("总共 %d 个dF约束违反！\n", dFViolationCount);
        } else {
            System.out.println("所有dF约束都满足！");
        }
        System.out.println("==================");
    }
    
    
    /**
     * 获取子问题的解（Main.java调用的接口）
     * @return Psi对象，包含完整的(vF, z)追随者解
     */
    public pool.Psi getPsi() throws IloException {
        // 获取vF和z的解值
        double[] vF = getVFValues();
        double[][] z = getZValues();

        // 创建并返回Psi对象
        return new pool.Psi(vF, z);
    }

    /**
     * 使用CPLEX构建子问题模型
     */
    private void buildModel(double[][] yh, double[] vL, double[] vP, int currentIteration) throws IloException {
        // 清除之前的模型（每次调用重新构建）
        cplex.clearModel();

        // 重新初始化变量（关键：clearModel会删除所有变量，必须重新创建）
        initializeVariables();

        // 加速策略
        if (fixVar) applyFollowerZFixing();

        // 1. 目标函数: max ΣΣd[i][n]
        IloLinearNumExpr obj = cplex.linearNumExpr();
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                obj.addTerm(1.0, dF[i][n]);
            }
        }
        cplex.addMaximize(obj);

        // 2. 添加约束
        // 添加传统零售商约束
        addTraditionalRetailerConstraints(vL, vP);
        
        // OA切割约束通过回调函数动态添加（所有切割）
    }
    
    
    /**
     * 添加传统零售商约束
     * 约束(26)-(28): 传统零售商的决策约束
     */
    private void addTraditionalRetailerConstraints(double[] vL, double[] vP) throws IloException {
        // 约束(26): 预算约束
        // ∑_{j∈J_f}l_j w^F_j + ∑_{j∈J_f}∑_{k∈K_f}f_{jk}z_{jk} ≤ b
        IloLinearNumExpr budgetExpr = cplex.linearNumExpr();
        for (int j = 0; j < J; j++) {
            Data.JNodes jfNode = data.getJNodes().get(j);
            double CF_j = jfNode.getCF_j();
            budgetExpr.addTerm(CF_j, vF[j]);
            double[] f_jk = jfNode.get_f_jk();
            for (int k = 0; k < K_f; k++) {
                budgetExpr.addTerm(f_jk[k], z[j][k]);
            }
        }
        double B_f = data.getParams().B_f;
        cplex.addLe(budgetExpr, B_f, "Constraint_26_BudgetConstraint");

        // 约束(27): 折扣选择约束
        // ∑_{k∈K_f}z_{jk} = v^F_j, ∀j∈J
        for (int j = 0; j < J; j++) {
            IloLinearNumExpr expr = cplex.linearNumExpr();
            for (int k = 0; k < K_f; k++) {
                expr.addTerm(1.0, z[j][k]);
            }
            cplex.addEq(expr, vF[j], "Constraint_27_DiscountSelection_" + j);
        }

        // 约束(28): 共址禁止约束
        // v^F_j ≤ 1 - w^L_j - v_j, ∀j∈J
        // 说明: 由于上下层候选点共用J集合,下层不能在上层已建店或建换电站的位置建店
        for (int j = 0; j < J; j++) {
            IloLinearNumExpr expr = cplex.linearNumExpr();
            expr.addTerm(1.0, vF[j]);
            // v^F_j ≤ 1 - w^L_j - v_j  ==>  v^F_j + w^L_j + v_j ≤ 1
            cplex.addLe(expr, 1.0 - vL[j] - vP[j], "Constraint_28_CoLocationProhibition_" + j);
        }

        // 注意：'至少选一个z'约束已移除（用户确认为辅助约束，不影响模型正确性）

        // 辅助约束(29): 至少选择一个z
        // ∑_{j∈J}∑_{k∈K_f} z_{jk} ≥ 1
        // 防止追随者不选任何设施导致U_in=0产生NaN
        IloLinearNumExpr minZExpr = cplex.linearNumExpr();
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                minZExpr.addTerm(1.0, z[j][k]);
            }
        }
        cplex.addGe(minZExpr, 1.0, "Constraint_29_MinOneZ");
    }

    /**
     * 设置追随者变量固定策略开关
     * @param fix true=启用 z[j][k]=0
     */
    public void setFixVar(boolean fix) {
        this.fixVar = fix;
    }

    /**
     * 策略1：若 CF_j + f_jk > B_f，则 z[j][k] = 0
     */
    private void applyFollowerZFixing() throws IloException {
        double B_f = data.getParams().B_f;
        int zFixedCount = 0;
        for (int j = 0; j < J; j++) {
            Data.JNodes jNode = data.getJNodes().get(j);
            double CF_j = jNode.getCF_j();
            double[] f_jk = jNode.get_f_jk();
            for (int k = 0; k < K_f; k++) {
                if (CF_j + f_jk[k] > B_f) {
                    z[j][k].setUB(0.0);
                    zFixedCount++;
                }
            }
        }
        if (zFixedCount > 0)
            System.out.println("[SP加速] 固定z: " + zFixedCount + "个");
    }

    

    /**
     * 设置当前yt值，用于梯度计算
     */
    public void setCurrentYh(double[][] yt) {
        this.currentYh = yt;
    }

    /**
     * 克隆z矩阵
     */
    private double[][] cloneZMatrix(double[][] source) {
        double[][] clone = new double[source.length][];
        for (int j = 0; j < source.length; j++) {
            clone[j] = source[j].clone();
        }
        return clone;
    }

    // 其他辅助方法和getter
    public IloCplex getCplex() {
        return cplex;
    }

    public double[][] getZValues() throws IloException {
        double[][] values = new double[J][K_f];
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                values[j][k] = cplex.getValue(z[j][k]);
            }
        }
        return values;
    }

    public double[] getVFValues() throws IloException {
        double[] values = new double[J];
        for (int j = 0; j < J; j++) {
            values[j] = cplex.getValue(vF[j]);
        }
        return values;
    }

    public void close() throws IloException {
        cplex.end();
    }
    
    /**
     * 获取当前迭代次数
     */
    public int getCurrentIteration() {
        return currentIteration;
    }
    
    /**
     * 设置当前迭代次数
     */
    public void setCurrentIteration(int iteration) {
        this.currentIteration = iteration;
    }
    
    
    /**
     * 获取求解状态信息
     */
    public String getSolverStatus() {
        try {
            if (cplex.getStatus() == IloCplex.Status.Optimal) {
                return "最优解";
            } else if (cplex.getStatus() == IloCplex.Status.Feasible) {
                return "可行解";
            } else if (cplex.getStatus() == IloCplex.Status.Infeasible) {
                return "不可行";
            } else if (cplex.getStatus() == IloCplex.Status.Unbounded) {
                return "无界";
            } else {
                return "其他状态: " + cplex.getStatus();
            }
        } catch (IloException e) {
            return "状态获取错误: " + e.getMessage();
        }
    }
    
    /**
     * 获取目标函数值
     */
    public double getObjectiveValue() throws IloException {
        if (cplex.getStatus() == IloCplex.Status.Optimal || cplex.getStatus() == IloCplex.Status.Feasible) {
            return cplex.getObjValue();
        }
        return Double.NaN;
    }
    
    /**
     * 打印求解统计信息
     */
    public void printSolverStats() {
        try {
            System.out.println("=== 子问题求解统计 ===");
            System.out.println("求解状态: " + getSolverStatus());
            System.out.println("目标函数值: " + getObjectiveValue());
            System.out.println("当前迭代: " + currentIteration);
            System.out.println("当前yt: 已设置");
        } catch (IloException e) {
            System.err.println("统计信息获取错误: " + e.getMessage());
        }
    }
     
    
    /**
     * 评估领导者目标函数Π(w', v', x', y', z)
     * 公式：Π = Σ_{i∈I} Σ_{n∈N} D_{in}^L(y, z)
     * 其中D_{in}^L是领导者市场需求，只与(y, z)有关
     */
    private double evaluateLeaderObjective(double[][] yt, double[][] z) {
        double totalLeaderDemand = 0.0;
        
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                // 计算领导者市场需求D_in^L
                double D_inL = calculator.calculateD_inL(i, n, yt, z);
                totalLeaderDemand += D_inL;
            }
        }
        
        return totalLeaderDemand;
    }
    
    /**
     * 设置z变量的值（用于设置悲观解）
     */
    private void setZValues(double[][] values) throws IloException {
        // 通过添加等式约束来固定z变量的值
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                cplex.addEq(z[j][k], values[j][k], "FixZ_" + j + "_" + k);
            }
        }
    }
    
    /**
     * 使用解池功能检测多个最优解
     * 这是更可靠和高效的方法
     */
    public boolean hasMultipleOptimalSolutionsWithPool() throws IloException {
        // 检查当前求解状态
        if (cplex.getStatus() != IloCplex.Status.Optimal) {
            return false;
        }
        
        double bestObj = cplex.getObjValue();
        System.out.println("当前最优目标值: " + bestObj);
        
        // 设置解池参数
        cplex.setParam(IloCplex.Param.MIP.Pool.Capacity, 20);       // 最多存50个解
        cplex.setParam(IloCplex.Param.MIP.Pool.AbsGap, 0.0);        // 绝对gap=0
        cplex.setParam(IloCplex.Param.MIP.Pool.RelGap, 0.0);        // 相对gap=0
        cplex.setParam(IloCplex.Param.MIP.Pool.Replace, 2);         // 替换策略(2=多样化)
        cplex.setParam(IloCplex.Param.MIP.Pool.Intensity, 4);       // 高强度搜索
        cplex.setParam(IloCplex.Param.Threads, 0);                    // 多线程（0=自动使用所有可用核心）
        
        // 重新求解以填充解池
        if (cplex.solve()) {
            int numSolutions = cplex.getSolnPoolNsolns();
            System.out.println("解池中找到的解数量: " + numSolutions);
            
            if (numSolutions > 1) {
                analyzeMultipleSolutions(numSolutions, bestObj);
                return true;
            } else {
                System.out.println("ℹ️ 只有一个最优解");
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * 分析解池中的多个解
     */
    private void analyzeMultipleSolutions(int numSolutions, double bestObj) throws IloException {
        System.out.println("\n=== 多解分析 ===");
        System.out.println("解池统计信息:");
        System.out.printf("- 最优解数量: %d\n", numSolutions);
        System.out.printf("- 最优目标值: %.6f\n", bestObj);
        
        // 获取当前解的z值作为参考
        double[][] currentZ = getZValues();
        System.out.println("\n当前解 (解0):");
        printZSolution(currentZ, 0);
        
        // 分析当前解对领导者的影响
        double currentLeaderObj = evaluateLeaderObjective(currentYh, currentZ);
        System.out.printf("领导者目标值: %.6f\n", currentLeaderObj);
        
        System.out.println("- CPLEX解池已找到多个最优解");

        
        System.out.println("==================\n");
    }
    
    /**
     * 打印z解
     */
    private void printZSolution(double[][] zSolution, int solutionIndex) {
        System.out.printf("z解 %d: ", solutionIndex);
        for (int j = 0; j < Math.min(J, 5); j++) { // 只显示前5个候选点
            System.out.printf("[");
            for (int k = 0; k < K_f; k++) {
                System.out.printf("%.0f", zSolution[j][k]);
                if (k < K_f - 1) System.out.print(",");
            }
            System.out.printf("]");
            if (j < Math.min(J, 5) - 1) System.out.print(" ");
        }
        if (J > 5) {
            System.out.printf(" ... (省略其他%d个候选点)", J - 5);
        }
        System.out.println();
    }



    /**
     * 向原始模型添加排除约束并返回约束对象
     */
    private IloRange addExclusionConstraintToModel(double[][] zStar) throws IloException {
        IloLinearNumExpr expr = cplex.linearNumExpr();
        int constantCount = 0; // 计算常数项数量

        // 对于z*_{jk} = 1的情况，添加(1 - z_{jk})项
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                if (Math.abs(zStar[j][k] - 1.0) < 1e-6) {
                    // 添加-z_{jk}项，常数项1将在右端处理
                    expr.addTerm(-1.0, z[j][k]);
                    constantCount++;
                }
            }
        }

        // 对于z*_{jk} = 0的情况，添加z_{jk}项
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                if (Math.abs(zStar[j][k]) < 1e-6) {
                    expr.addTerm(1.0, z[j][k]);
                }
            }
        }

        // 添加约束：expr + constantCount ≥ 1，即 expr ≥ 1 - constantCount
        return cplex.addGe(expr, 1.0 - constantCount, "ExclusionConstraint_" + System.currentTimeMillis());
    }
    
    
    /**
     * 获取悲观解选择统计信息
     */
    public void printPessimisticSolutionStats() {
        try {
            System.out.println("=== 悲观解选择统计 ===");
            System.out.println("当前迭代: " + currentIteration);
            System.out.println("求解状态: " + getSolverStatus());
            System.out.println("目标函数值: " + getObjectiveValue());
            
            // 检查是否存在多个最优解
            boolean hasMultiple = hasMultipleOptimalSolutionsWithPool();
            System.out.println("是否存在多个最优解: " + hasMultiple);
            
        } catch (IloException e) {
            System.err.println("统计信息获取错误: " + e.getMessage());
        }
    }
    
    /**
     * 智能求解方法：自动选择是否使用悲观解
     * 如果存在多个最优解，则使用悲观解选择
     * @param gamma 主问题的Gamma解
     */
    public void solve_Model_Smart(Gamma gamma) throws IloException {
        // 先尝试普通求解
        solve_Model(gamma);
        
        // 检查是否存在多个最优解（使用解池功能）
        try {
            if (hasMultipleOptimalSolutionsWithPool()) {
                System.out.println("检测到多个最优解，开始悲观解选择");
                
                // 获取初始最优解和最优目标值
                double[][] z0 = getZValues();
                double phiStar = cplex.getObjValue(); // 追随者最优目标值Φ*

                // 初始化悲观解
                double[][] zPessimistic = new double[J][K_f];
                for (int j = 0; j < J; j++) {
                    for (int k = 0; k < K_f; k++) {
                        zPessimistic[j][k] = z0[j][k];
                    }
                }
                
                // 计算领导者目标值（悲观值）
                // Π(w', v', x', y', z) = Σ_{i∈I} Σ_{n∈N} D_{in}^L(y, z)
                double piStar = evaluateLeaderObjective(gamma.y, zPessimistic);
                System.out.println("初始领导者目标值: " + piStar);
                
                // 主循环 - 寻找悲观解
                int n = 0;
                int maxIterations = 10; // 限制迭代次数
                
                while (n < maxIterations) {
                    try {
                        // 添加目标值约束：要求目标值等于当前最优值
                        IloLinearNumExpr obj = cplex.linearNumExpr();
                        for (int i = 0; i < I; i++) {
                            for (int n2 = 0; n2 < N; n2++) {
                                obj.addTerm(1.0, dF[i][n2]);
                            }
                        }
                        IloRange optimalValueConstraint = cplex.addEq(obj, phiStar, "OptimalValueConstraint_" + n);
                        
                        // 添加排除当前解的约束
                        IloRange exclusionConstraint = addExclusionConstraintToModel(zPessimistic);
                        
                        // 尝试求解
                    if (!cplex.solve()) {
                            // 移除约束并退出
                            cplex.remove(optimalValueConstraint);
                            cplex.remove(exclusionConstraint);
                        break; // 无更多解
                    }
                    
                    // 获取新解
                    double[][] zNew = getZValues();
                    
                        // 检查是否为更悲观的领导者解
                        double piNew = evaluateLeaderObjective(gamma.y, zNew);
                        System.out.println("找到新解，领导者目标值: " + piNew);
                        
                        if (piNew < piStar) {
                            piStar = piNew;
                            for (int j = 0; j < J; j++) {
                                for (int k = 0; k < K_f; k++) {
                                    zPessimistic[j][k] = zNew[j][k];
                                }
                            }
                            System.out.println("更新悲观解，新的领导者目标值: " + piStar);
                        }
                        
                        // 移除约束
                        cplex.remove(optimalValueConstraint);
                        cplex.remove(exclusionConstraint);
                        
                    } catch (Exception e) {
                        System.err.println("悲观解选择循环中出错: " + e.getMessage());
                        break;
                    }
                    
                    n++;
                }
                
                // 设置最终悲观解到原始模型
                setZValues(zPessimistic);
                System.out.println("悲观解选择完成，迭代次数: " + n + ", 最终领导者目标值: " + piStar);
                
            } else {
                System.out.println("单一最优解，使用普通求解结果");
            }
        } catch (IloException e) {
            System.err.println("悲观解选择错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 获取IntZSet（存储需要加OA切的整数解）
     */
    public List<double[][]> getIntZSet() {
        return new ArrayList<>(IntZSet); // 返回深拷贝
    }
    
    /**
     * 获取IntZSet大小
     */
    public int getIntZSetSize() {
        return IntZSet.size();
    }
    
    /**
     * 清空IntZSet
     */
    public void clearIntZSet() {
        IntZSet.clear();
        System.out.println("已清空IntZSet");
    }

}