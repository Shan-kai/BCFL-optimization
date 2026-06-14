package mp;

import ilog.cplex.IloCplex;
import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import input.Data;
import pool.Gamma;

/**
 * MaxLeaderProblem：求解领导者最大需求问题
 *
 * 问题定义：
 *   gamma_max = argmax_{gamma in Gamma} D^L(gamma, z)
 *
 * 其中：
 *   - D^L(gamma, z) 是领导者需求函数
 *   - z 是固定值（通常为0，表示追随者不采取任何行动）
 *
 * 这是一个单层MIP问题，用于获得紧的上界。
 * 通过求解在z=0时的领导者最优决策，可以得到当前追随者状态下的最大领导者需求。
 *
 * 核心思想：
 *   - 将上层优化问题（博弈论）转化为单层优化问题
 *   - 追随者决策z固定为0（或不采取行动）
 *   - 领导者最大化其市场份额D^L
 *
 * 算法特点：
 *   1. 决策变量：vL[·], vP[·], x[·][·], y[·][·], dL[·][·]
 *   2. OA切割：在B&B过程中动态添加dL <= D^L(y)的线性切
 *   3. 使用LazyConstraintCallback实现非线性约束的线性化
 */
public class MaxLeaderProblem {

    /**
     * 数据对象，包含所有问题参数和节点信息
     */
    private final Data data;

    /**
     * CPLEX求解器实例
     */
    private final IloCplex cplex;

    /**
     * 计算引擎，提供数学计算函数
     */
    private final utils.BCFLCalculator calculator;

    /**
     * 决策变量：领导者零售店选址
     * vL[j] = 1 表示在候选点j开设电商零售店，否则为0
     */
    private IloNumVar[] vL;

    /**
     * 决策变量：充电站选址
     * vP[j] = 1 表示在候选点j开设充电站，否则为0
     */
    private IloNumVar[] vP;

    /**
     * 决策变量：设施-服务分配
     * x[j][s] = 1 表示设施j提供服务s，否则为0
     * s ∈ {0,...,S-2}（不包括最后一个无人机充电服务）
     */
    private IloNumVar[][] x;

    /**
     * 决策变量：客户服务选择
     * y[i][s] = 1 表示客户区i选择服务s，否则为0
     * s ∈ {0,...,S-1}（包括最后一个无人机充电服务）
     */
    private IloNumVar[][] y;

    /**
     * 辅助变量：领导者需求
     * dL[i][n] 表示客户区i在第n种情境下的领导者需求
     * 用于表示目标函数中的需求项
     */
    private IloNumVar[][] dL;

    /**
     * 固定的追随者决策z
     * zFixed[j][k] = 1 表示在候选点j提供第k个价格等级的服务
     * 对于MaxLeaderProblem，z通常固定为0（追随者不采取行动）
     */
    private final double[][] zFixed;

    /**
     * 当前找到的最好领导者解（gamma）
     */
    private Gamma bestGamma;

    // ========== 数值稳定性常量 ==========
    private static final double LEADER_SUM_EPS = 1e-8;   // 判断leader部分为0的容差
    private static final double DELTA_Y_SHIFT   = 1e-4;  // y变量的线性化平移量

    /**
     * 当前最大领导者需求值
     */
    private double maxLeaderDemand;
    
    /**
     * 无无人机对比实验开关
     */
    private boolean disableDrone = false;
    private boolean disableChargingStation = false;  // 是否禁用换电站（固定vP=0）

    /**
     * 构造函数
     *
     * @param data 数据对象
     * @param zFixed 固定的追随者决策z
     * @throws IloException CPLEX初始化异常
     */
    public MaxLeaderProblem(Data data, double[][] zFixed) throws IloException {
        this.data = data;
        this.zFixed = zFixed;
        this.calculator = new utils.BCFLCalculator(data);
        this.cplex = new IloCplex();
        this.cplex.setParam(IloCplex.Param.Preprocessing.Reduce, 0);
        this.cplex.setParam(IloCplex.Param.Threads, 0);  // 多线程（0=自动使用所有可用核心）
    }

    /**
     * 构建平移后的y矩阵
     *
     * 当leaderSum ≈ 0时，在该客户维度上对y施加一个平移δ，用于构造OA切割的
     * 线性化展开点(y+δ, z)。这避免了梯度计算中的leaderSum^(λ_l-1)数值奇异。
     *
     * 说明：该方法仅用于计算D_in及其梯度的参考点，不会修改CPLEX中的原始变量值。
     *
     * @param yBase 基础y值矩阵
     * @param targetI 需要平移的客户区索引
     * @param delta 平移量
     * @return 平移后的y矩阵
     */
    private double[][] buildShiftedY(double[][] yBase, int targetI, double delta) {
        int I = data.getI();
        int S = data.getS();
        double[][] yShifted = new double[I][S];
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                double val = yBase[i][s];
                if (i == targetI) {
                    // 仅在目标客户维度上平移，用于避免leaderSum=0导致的梯度奇异
                    yShifted[i][s] = val + delta;
                } else {
                    yShifted[i][s] = val;
                }
            }
        }
        return yShifted;
    }

    /**
     * 求解领导者最大需求问题
     *
     * 步骤：
     * 1. 构建优化模型
     * 2. 启用OA切割回调
     * 3. 求解模型
     * 4. 提取解
     *
     * @return 最好的领导者解gamma
     * @throws IloException 求解过程中的异常
     */
    public Gamma solve() throws IloException {
        buildModel();
        enableOACuts();
        solveModel();
        return extractSolution();
    }

    /**
     * 构建优化模型
     *
     * 步骤：
     * 1. 清空现有模型
     * 2. 初始化决策变量
     * 3. 构建目标函数（最大化总需求）
     * 4. 添加约束
     * 5. 关闭CPLEX输出
     *
     * @throws IloException 模型构建过程中的异常
     */
    private void buildModel() throws IloException {
        cplex.clearModel();
        int I = data.getI();
        int J = data.getJ();
        int S = data.getS();
        int N = data.getN();

        initializeVariables(I, J, S);
        buildObjectiveFunction();
        buildConstraints(J, S, N);
        cplex.setOut(null);
    }

    /**
     * 初始化决策变量
     *
     * 变量类型：
     * - vL[j]：领导者零售店选址（binary，0-1）
     * - vP[j]：充电站选址（binary，0-1）
     * - x[j][s]：设施-服务分配（binary，0-1）
     * - y[i][s]：客户服务选择（binary，0-1）
     * - dL[i][n]：领导者需求（continuous，非负）
     *
     * @param I 客户区数量
     * @param J 领导者候选点数量
     * @param S 服务类型数量
     * @throws IloException 变量创建异常
     */
    private void initializeVariables(int I, int J, int S) throws IloException {
        int N = data.getN();

        vL = new IloNumVar[J];
        vP = new IloNumVar[J];
        x = new IloNumVar[J][S-1];
        y = new IloNumVar[I][S];
        dL = new IloNumVar[I][N];

        for (int j = 0; j < J; j++) {
            vL[j] = cplex.boolVar("vL_" + j);
            vP[j] = cplex.boolVar("vP_" + j);
            for (int s = 0; s < S-1; s++) {
                x[j][s] = cplex.boolVar("x_" + j + "_" + s);
            }
        }

        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                y[i][s] = cplex.boolVar("y_" + i + "_" + s);
            }
        }

        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                dL[i][n] = cplex.numVar(0, Double.MAX_VALUE, "dL_" + i + "_" + n);
            }
        }
    }

    private void buildObjectiveFunction() throws IloException {
        IloLinearNumExpr objExpr = cplex.linearNumExpr();
        for (int i = 0; i < data.getI(); i++) {
            for (int n = 0; n < data.getN(); n++) {
                objExpr.addTerm(1.0, dL[i][n]);
            }
        }
        cplex.addMaximize(objExpr);
    }

    /**
     * 构建约束集
     *
     * 约束类型：
     * 1. 预算约束：总建设成本不超过预算B_l
     * 2. 设施互斥约束：每个候选点最多开设一个设施
     * 3. 服务范围约束：客户i选择的服务s必须在设施j的服务覆盖范围内
     * 4. 充电站约束：充电站必须与无人机充电服务设施相邻
     * 5. 无人机依赖约束：使用无人机服务需要充电站
     * 6. 无人机服务互斥约束：同一客户区只能选择一种无人机服务
     * 7. 服务配置约束：设施提供的服务必须由该设施开设
     * 8. 设施激活约束：开设设施必须提供至少一种服务
     * 9. 至少一个客户选择：每个客户区必须至少选择一种服务
     *
     * dL约束通过OA切割动态添加
     *
     * @param J 领导者候选点数量
     * @param S 服务类型数量
     * @param N 情境数量
     * @throws IloException 约束添加异常
     */
    private void buildConstraints(int J, int S, int N) throws IloException {
        addBudgetConstraint(J, S);
        addFacilityMutexConstraint(J);
        addServiceRangeConstraint(J, S);
        addChargingStationConstraint(J, S);
        addChargingStationDependentConstraint(S);
        addDroneServiceMutexConstraint(S);
        addServiceConfigConstraint(J, S);
        addLeaderFacilityServiceActivationConstraint(J, S);
        addAtLeastOneYConstraint(S);
        addDOptimizationConstraint();
        
        // 无无人机对比实验：禁用不经过换电站的无人机服务
        if (disableDrone) {
            int droneNoChargeS = S - 2;
            for (int j = 0; j < J; j++) {
                cplex.addEq(x[j][droneNoChargeS], 0, "DisableDrone_x_" + j);
            }
            System.out.println("[MaxLeader] 无无人机模式已启用：x[j][" + droneNoChargeS + "]=0");
        }

        // 禁用换电站实验：固定vP[j]=0
        if (disableChargingStation) {
            for (int j = 0; j < J; j++) {
                cplex.addEq(vP[j], 0, "DisableCharging_vP_" + j);
            }
            System.out.println("[MaxLeader] 禁用换电站模式已启用：vP[j]=0");
        }
    }

    /**
     * 预算约束
     *
     * 目标：Σ_{j,s} (Fs * x[j][s] + Lj * vL[j] + Cj * vP[j]) ≤ B_l
     *
     * 其中：
     * - Fs：服务s的建设成本
     * - Lj：开设电商零售店j的成本
     * - Cj：开设充电站j的成本
     * - B_l：领导者总预算
     *
     * @param J 领导者候选点数量
     * @param S 服务类型数量
     * @throws IloException 约束添加异常
     */
    private void addBudgetConstraint(int J, int S) throws IloException {
        IloLinearNumExpr costExpr = cplex.linearNumExpr();
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < S-1; s++) {
                costExpr.addTerm(data.getServices().get(s).getFs(), x[j][s]);
            }
            costExpr.addTerm(data.getJNodes().get(j).getL_J(), vL[j]);
            costExpr.addTerm(data.getJNodes().get(j).getC_J(), vP[j]);
        }
        cplex.addLe(costExpr, data.getParams().B_l, "BudgetConstraint");
    }

    /**
     * 设施互斥约束
     *
     * 目标：vL[j] + vP[j] ≤ 1,  ∀j ∈ J
     *
     * 含义：每个候选点j最多开设一个设施（要么是电商零售店，要么是充电站）
     *
     * @param J 领导者候选点数量
     * @throws IloException 约束添加异常
     */
    private void addFacilityMutexConstraint(int J) throws IloException {
        for (int j = 0; j < J; j++) {
            IloLinearNumExpr expr = cplex.linearNumExpr();
            expr.addTerm(1.0, vP[j]);
            expr.addTerm(1.0, vL[j]);
            cplex.addLe(expr, 1.0, "FacilityMutex_" + j);
        }
    }

    /**
     * 服务范围约束
     *
     * 目标：y[i][s] ≤ Σ_{j∈J} r_{ijs} * x[j][s],  ∀i∈I, ∀s
     *
     * 含义：客户i选择服务s的前提是至少有一个设施j在服务s的覆盖范围内
     * r_{ijs}：设施j的服务s对客户i的覆盖程度（0或1）
     *
     * @param J 领导者候选点数量
     * @param S 服务类型数量
     * @throws IloException 约束添加异常
     */
    private void addServiceRangeConstraint(int J, int S) throws IloException {
        for (int i = 0; i < data.getI(); i++) {
            for (int s = 0; s < S-1; s++) {
                IloLinearNumExpr rhsExpr = cplex.linearNumExpr();
                for (int j = 0; j < J; j++) {
                    double rIJS = data.getServiceCoverage(i, j, s);
                    rhsExpr.addTerm(rIJS, x[j][s]);
                }
                cplex.addLe(y[i][s], rhsExpr, "ServiceRange_" + i + "_" + s);
            }
        }
    }

    /**
     * 充电站约束
     *
     * 目标：vP[j] ≤ Σ_{j'≠j} r_{jj'}^CS * x[j'][S-2],  ∀j ∈ J
     *
     * 含义：在j处开设充电站的前提是至少有一个其他设施j'在无人机充电服务范围内
     * S-2：无人机充电服务索引（倒数第二个服务）
     * r_{jj'}^CS：设施j'对充电站j的充电服务覆盖范围
     *
     * @param J 领导者候选点数量
     * @param S 服务类型数量
     * @throws IloException 约束添加异常
     */
    private void addChargingStationConstraint(int J, int S) throws IloException {
        int droneChargeS = S - 1;
        double droneCs = data.getServices().get(droneChargeS).getR_s();
        for (int j = 0; j < J; j++) {
            IloLinearNumExpr rhsExpr = cplex.linearNumExpr();
            for (int jPrime = 0; jPrime < J; jPrime++) {
                if (jPrime == j) continue;
                double distance = calculateDistance(j, jPrime);
                double rJJPrime = (distance / 1000.0 <= droneCs) ? 1.0 : 0.0;
                rhsExpr.addTerm(rJJPrime, x[jPrime][droneChargeS-1]);
            }
            cplex.addLe(vP[j], rhsExpr, "ChargingStationDep_" + j);
        }
    }

    /**
     * 无人机依赖约束
     *
     * 目标：y[i][S-1] ≤ Σ_{j∈J} r_{ij}^DRONE * vP[j],  ∀i∈I
     *
     * 含义：客户i选择无人机配送服务的前提是j处设有充电站
     * S-1：无人机配送服务索引（最后一个服务）
     * r_{ij}^DRONE：设施j对客户i的无人机配送服务覆盖范围
     *
     * @param S 服务类型数量
     * @throws IloException 约束添加异常
     */
    private void addChargingStationDependentConstraint(int S) throws IloException {
        int droneWithChargeS = S - 1;
        double droneRs = data.getServices().get(S-2).getR_s();
        for (int i = 0; i < data.getI(); i++) {
            IloLinearNumExpr rhsExpr = cplex.linearNumExpr();
            for (int j = 0; j < data.getJ(); j++) {
                double distance = calculateCustomerDistance(i, j);
                double rIJ = (distance / 1000.0 <= droneRs) ? 1.0 : 0.0;
                rhsExpr.addTerm(rIJ, vP[j]);
            }
            cplex.addLe(y[i][droneWithChargeS], rhsExpr, "ChargingStationDependent1_" + i);
        }
    }

    /**
     * 无人机服务互斥约束
     *
     * 目标：y[i][S-2] + y[i][S-1] ≤ 1,  ∀i∈I
     *
     * 含义：同一客户区只能选择一种无人机服务（要么是常规无人机配送，要么是充电服务）
     * S-2：常规无人机配送服务索引
     * S-1：无人机充电服务索引
     *
     * @param S 服务类型数量
     * @throws IloException 约束添加异常
     */
    private void addDroneServiceMutexConstraint(int S) throws IloException {
        if (S < 3) return;
        for (int i = 0; i < data.getI(); i++) {
            IloLinearNumExpr expr = cplex.linearNumExpr();
            expr.addTerm(1.0, y[i][S-2]);
            expr.addTerm(1.0, y[i][S-1]);
            cplex.addLe(expr, 1.0, "DroneMutex_" + i);
        }
    }

    /**
     * 服务配置约束
     *
     * 目标：x[j][s] ≤ vL[j],  ∀j∈J, ∀s
     *
     * 含义：设施j提供的服务s的前提是该设施由领导者开设
     *
     * @param J 领导者候选点数量
     * @param S 服务类型数量
     * @throws IloException 约束添加异常
     */
    private void addServiceConfigConstraint(int J, int S) throws IloException {
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < S-1; s++) {
                cplex.addLe(x[j][s], vL[j], "ServiceConfig_" + j + "_" + s);
            }
        }
    }

    /**
     * 设施激活约束
     *
     * 目标：vL[j] ≤ Σ_{s} x[j][s],  ∀j∈J, s∈{0,...,S-2}
     *
     * 含义：开设设施j的前提是该设施至少提供一种服务
     *
     * @param J 领导者候选点数量
     * @param S 服务类型数量
     * @throws IloException 约束添加异常
     */
    private void addLeaderFacilityServiceActivationConstraint(int J, int S) throws IloException {
        if (S < 2) return;
        for (int j = 0; j < J; j++) {
            IloLinearNumExpr rhs = cplex.linearNumExpr();
            for (int s = 0; s < S-1; s++) {
                rhs.addTerm(1.0, x[j][s]);
            }
            cplex.addLe(vL[j], rhs, "LeaderFacilityActivation_" + j);
        }
    }

    /**
     * 至少一个客户选择约束
     *
     * 目标：Σ_{i,s} y[i][s] ≥ 1
     *
     * 含义：至少有一个客户区选择服务，确保模型有解
     *
     * @param S 服务类型数量
     * @throws IloException 约束添加异常
     */
    private void addAtLeastOneYConstraint(int S) throws IloException {
        IloLinearNumExpr ySumExpr = cplex.linearNumExpr();
        for (int i = 0; i < data.getI(); i++) {
            for (int s = 0; s < S; s++) {
                ySumExpr.addTerm(1.0, y[i][s]);
            }
        }
        cplex.addGe(ySumExpr, 1.0, "AtLeastOneY");
    }

    /**
     * dL约束占位方法
     *
     * 说明：dL[i][n] <= D_in^L(y) 约束通过OA切割动态添加
     * 不在模型构建时静态添加，而是在B&B过程中通过LazyConstraintCallback动态添加
     *
     * @throws IloException 约束添加异常
     */
    private void addDOptimizationConstraint() throws IloException {
        // dL约束通过OA切割动态添加，不在此处静态添加
    }

    /**
     * 求解优化模型
     *
     * 步骤：
     * 1. 调用CPLEX求解器
     * 2. 提取解到bestGamma
     *
     * @throws IloException 求解异常
     */
    private void solveModel() throws IloException {
        cplex.solve();
        extractSolution();
    }

    /**
     * 提取最优解
     *
     * 从CPLEX求解器中提取决策变量值，构建Gamma解对象
     * 并计算最大领导者需求
     *
     * @return 最优的领导者解gamma
     * @throws IloException 解提取异常
     */
    private Gamma extractSolution() throws IloException {
        int J = data.getJ();
        int S = data.getS();
        double[] vLValues = new double[J];
        double[] vPValues = new double[J];
        double[][] xValues = new double[J][S-1];
        double[][] yValues = new double[data.getI()][S];

        for (int j = 0; j < J; j++) {
            vLValues[j] = cplex.getValue(vL[j]);
            vPValues[j] = cplex.getValue(vP[j]);
            for (int s = 0; s < S-1; s++) {
                xValues[j][s] = cplex.getValue(x[j][s]);
            }
        }
        for (int i = 0; i < data.getI(); i++) {
            for (int s = 0; s < S; s++) {
                yValues[i][s] = cplex.getValue(y[i][s]);
            }
        }

        bestGamma = new Gamma(vLValues, vPValues, xValues, yValues);
        calculateMaxDemand();
        return bestGamma;
    }

    /**
     * 计算最大领导者需求
     *
     * 步骤：
     * 1. 初始化maxLeaderDemand为0
     * 2. 遍历所有客户区i和情境n
     * 3. 使用计算器计算D_inL
     * 4. 累加得到总需求
     *
     * 注意：dL值可以从CPLEX模型中直接获取，
     * 也可以通过计算器重新计算。这里优先使用模型中的值。
     */
    private void calculateMaxDemand() {
        maxLeaderDemand = 0.0;
        for (int i = 0; i < data.getI(); i++) {
            for (int n = 0; n < data.getN(); n++) {
                // 使用模型中的 dL 值
                try {
                    maxLeaderDemand += cplex.getValue(dL[i][n]);
                } catch (IloException e) {
                    // 如果无法获取，使用计算器
                    maxLeaderDemand += calculator.calculateD_inL(i, n, bestGamma.y, zFixed);
                }
            }
        }
    }

    /**
     * 计算两个设施点之间的距离（欧几里得距离）
     *
     * @param j1 设施点1的索引
     * @param j2 设施点2的索引
     * @return 两点之间的距离（公里）
     */
    private double calculateDistance(int j1, int j2) {
        double x1 = data.getJNodes().get(j1).getX(), y1 = data.getJNodes().get(j1).getY();
        double x2 = data.getJNodes().get(j2).getX(), y2 = data.getJNodes().get(j2).getY();
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    /**
     * 计算客户区与设施点之间的距离
     *
     * @param i 客户区索引
     * @param j 设施点索引
     * @return 客户区与设施点之间的距离（公里）
     */
    private double calculateCustomerDistance(int i, int j) {
        double x1 = data.getINodes().get(i).getX(), y1 = data.getINodes().get(i).getY();
        double x2 = data.getJNodes().get(j).getX(), y2 = data.getJNodes().get(j).getY();
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    /**
     * 获取最优领导者解
     *
     * @return 最好的领导者解gamma
     */
    public Gamma getBestGamma() {
        return bestGamma;
    }

    /**
     * 获取最大领导者需求值
     *
     * @return 最大领导者需求值（总需求）
     */
    public double getMaxLeaderDemand() {
        return maxLeaderDemand;
    }

    /**
     * 获取CPLEX求解器实例
     *
     * @return CPLEX求解器实例
     */
    public IloCplex getCplex() {
        return cplex;
    }

    /**
     * OA切割回调类
     *
     * 作用：在CPLEX分支定界（B&B）过程中动态添加外逼近（OA）切割
     *
     * 工作原理：
     * 1. 当CPLEX发现一个新的整数解时，回调函数被触发
     * 2. 提取当前解的y值（客户服务选择变量）
     * 3. 检查是否满足约束 dL <= D^L(y)
     * 4. 如果不满足，添加OA切平面
     *
     * OA切平面公式：
     * dL <= D^L(yBar) + sum_s d(D^L)/dy_is * (y_is - yBar_is)
     *
     * 其中：
     * - yBar 是当前整数解
     * - D^L(yBar) 是函数在yBar处的值
     * - d(D^L)/dy_is 是函数在yBar处的偏导数
     *
     * 这是一个线性切平面，用于逼近非线性约束 dL <= D^L(y)
     */
    private class OACutCallback extends IloCplex.LazyConstraintCallback {
        /**
         * 回调主函数
         *
         * 步骤：
         * 1. 从回调中提取当前整数解的y值
         * 2. 遍历所有客户区i和情境n
         * 3. 检查是否违反约束 dL <= D^L(y)
         * 4. 计算D^L(y)和偏导数
         * 5. 添加OA切平面
         *
         * @throws IloException 回调执行异常
         */
        @Override
        protected void main() throws IloException {
            try {
                // 步骤1：提取当前解的 y 值（客户服务选择变量）
                // yBar[i][s] 表示客户区i选择服务s的当前解值（0或1）
                double[][] yBar = new double[data.getI()][data.getS()];
                for (int i = 0; i < data.getI(); i++) {
                    for (int s = 0; s < data.getS(); s++) {
                        yBar[i][s] = getValue(y[i][s]);
                    }
                }

                // 创建全零的z矩阵（z=0表示追随者不采取任何行动）
                double[][] zeroZ = new double[data.getJ()][data.getK_f()];

                // 步骤2：检查每个 (i,n) 的 OA 约束违反
                // 遍历所有客户区i和情境n，检查 dL[i][n] <= D_in^L(y) 是否被违反
                boolean addedCut = false;
                for (int i = 0; i < data.getI(); i++) {
                    for (int n = 0; n < data.getN(); n++) {
                        // 获取当前解中的 dL 值和对应的 D_in^L(y) 值
                        double dL_val = getValue(dL[i][n]);
                        double D_inL_at_y = calculator.calculateD_inL(i, n, yBar, zeroZ);

                        // 步骤3：检查是否违反约束 dL <= D^L(y)
                        // 如果 dL > D^L(y) + 容差，则添加OA切平面
                        if (dL_val > D_inL_at_y * (1 + 1e-4)) {
                            // 步骤4：构建并添加 OA 切平面
                            addOACutForDL(i, n, yBar, D_inL_at_y);
                            addedCut = true;
                        }
                    }
                }

            } catch (Exception e) {
                System.err.println("[MaxLeader OA回调] 错误: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * 构建并添加dL的OA切平面
         *
         * 标准形式：dL[i][n] <= D_inL_at_y + sum_s d(D^L)/dy_is * (y[i][s] - yBar[i][s])
         * 展开后：dL[i][n] - sum_s d(D^L)/dy_is * y[i][s] <= D_inL_at_y - sum_s d(D^L)/dy_is * yBar[i][s]
         *
         * @param i 客户区索引
         * @param n 情境索引
         * @param yBar 当前y解值
         * @param D_inL_at_y 当前解处的D_in^L值
         * @return 是否成功添加切平面
         * @throws IloException CPLEX异常
         */
        private boolean addOACutForDL(int i, int n, double[][] yBar, double D_inL_at_y) throws IloException {
            // 检查leaderSum是否接近0，如果是则使用平移后的yRef
            double leaderSum = calculator.calculateLeaderSum(i, n, yBar);
            double[][] yRef = yBar;
            if (leaderSum < LEADER_SUM_EPS) {
                yRef = buildShiftedY(yBar, i, DELTA_Y_SHIFT);
                // 重新计算D_inL_at_y使用平移后的yRef
                D_inL_at_y = calculator.calculateD_inL(i, n, yRef, zFixed);
            }

            // 构建切平面左侧：dL[i][n] - sum_s d(D^L)/dy_is * y[i][s]
            IloLinearNumExpr cutExpr = cplex.linearNumExpr();
            cutExpr.addTerm(1.0, dL[i][n]);

            // 计算切平面右侧常数项：D_inL_at_y - sum_s d(D^L)/dy_is * yRef[i][s]
            double constantTerm = D_inL_at_y;

            for (int s = 0; s < data.getS(); s++) {
                // 计算偏导数 d(D^L)/dy_is（使用yRef作为展开点）
                // 注意：使用 calculatePartialD_inL_wrt_y 计算 D_in^L 对 y 的偏导数
                double grad = calculator.calculatePartialD_inL_wrt_y(i, n, s, yRef, zFixed);
                cutExpr.addTerm(-grad, y[i][s]);     // -d(D^L)/dy_is * y[i][s]
                constantTerm -= grad * yRef[i][s];   // 减去梯度*yRef项
            }

            // 添加切平面约束到CPLEX模型
            add(cplex.le(cutExpr, constantTerm,
                "OACut_dL_" + i + "_" + n + "_" + System.currentTimeMillis()));

            return true;
        }
    }

    /**
     * 启用OA切割回调
     *
     * 在CPLEX求解器中注册OACutCallback，
     * 使得在分支定界过程中可以动态添加OA切割。
     *
     * 必须在调用cplex.solve()之前调用此方法。
     *
     * @throws IloException 注册回调时的异常
     */
    public void enableOACuts() throws IloException {
        cplex.use(new OACutCallback());
    }
    
    /**
     * 设置是否禁用无人机服务（无无人机对比实验开关）
     * @param disable true=禁用无人机服务，false=正常模式（默认）
     */
    public void setDisableDrone(boolean disable) {
        this.disableDrone = disable;
        System.out.println("MaxLeader：无人机服务" + (disable ? "已禁用（无无人机模式）" : "正常启用"));
    }

    /**
     * 设置是否禁用换电站（固定vP=0）
     * @param disable true=禁用换电站，false=正常模式（默认）
     */
    public void setDisableChargingStation(boolean disable) {
        this.disableChargingStation = disable;
        System.out.println("MaxLeader：换电站" + (disable ? "已禁用（vP=0）" : "正常启用"));
    }

    /**
     * 检查OA约束违反情况
     *
     * 检查当前最优解是否满足 dL[i][n] <= D_in^L(y) 约束
     * 这是求解后的验证，用于确认OA切割的有效性
     *
     * 输出格式模仿主问题的 printSolutionStats() 方法
     *
     * @throws IloException 检查过程中的异常
     */
    public void checkOAViolations() throws IloException {
        if (bestGamma == null) {
            System.out.println("警告：尚未求解，无法检查约束违反");
            return;
        }

        System.out.println("=== OA约束违反检查 (dL) ===");

        int I = data.getI();
        int N = data.getN();
        int dLViolationCount = 0;
        double totalViolation = 0.0;

        // 创建全零的z矩阵（z=0表示追随者不采取任何行动）
        double[][] zeroZ = new double[data.getJ()][data.getK_f()];

        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                double dL_val = cplex.getValue(dL[i][n]);
                double D_inL = calculator.calculateD_inL(i, n, bestGamma.y, zeroZ);

                // 检查是否违反: dL[i][n] > D_inL * (1.0 + 1e-4)
                double threshold = D_inL * (1.0 + 1e-4);
                if (dL_val > threshold) {
                    double violation = (dL_val - D_inL) / D_inL;
                    totalViolation += (dL_val - D_inL);
                    dLViolationCount++;

                    if (dLViolationCount <= 5) { // 只打印前5个违反
                        System.out.printf("  违反[%d]: dL[%d][%d]=%.6f > D_inL[%d][%d]=%.6f (差值(比例)=%.6f)%n",
                                dLViolationCount, i, n, dL_val, i, n, D_inL, violation);
                    }
                }
            }
        }

        if (dLViolationCount > 0) {
            System.out.printf("总共 %d 个dL约束违反！总违反量: %.6f%n", dLViolationCount, totalViolation);
        } else {
            System.out.println("所有dL约束都满足！");
        }
        System.out.println();
    }

    /**
     * 打印完整的解统计信息
     *
     * 模仿主问题的 printSolutionStats() 方法格式
     * 包含变量值和约束违反检查
     *
     * @throws IloException 打印过程中的异常
     */
    public void printSolutionStats() throws IloException {
        if (bestGamma == null) {
            System.out.println("警告：尚未求解，无法打印统计信息");
            return;
        }

        int I = data.getI();
        int J = data.getJ();
        int S = data.getS();
        int N = data.getN();

        System.out.println("=== MaxLeaderProblem 解统计信息 ===");

        // 打印vL解（设施选址）
        System.out.println("vL解 (设施选址):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  设施 %d: %.1f ", j, bestGamma.vL[j]);
            if ((j + 1) % 5 == 0) System.out.println();
        }
        System.out.println();

        // 打印vP解（换电站选址）
        System.out.println("vP解 (换电站选址):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  换电站 %d: %.1f ", j, bestGamma.vP[j]);
            if ((j + 1) % 5 == 0) System.out.println();
        }
        System.out.println();

        // 打印x解（设施服务配置）
        System.out.println("x解 (设施服务配置):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  设施 %d: ", j);
            for (int s = 0; s < Math.min(S - 1, 3); s++) {
                System.out.printf("x[%d][%d]=%.1f ", j, s, bestGamma.x[j][s]);
            }
            System.out.println();
        }
        System.out.println();

        // 打印y解（客户区服务选择）
        System.out.println("y解 (客户区服务选择):");
        for (int i = 0; i < I; i++) {
            System.out.printf("  客户区 %d: ", i);
            for (int s = 0; s < Math.min(S, 4); s++) {
                System.out.printf("y[%d][%d]=%.1f ", i, s, bestGamma.y[i][s]);
            }
            System.out.println();
        }
        System.out.println();

        // 计算并打印dL和D_inL的总和
        double sumDL_var = 0.0;  // 累计所有dL的总和
        double sumDinL = 0.0;    // 累计所有D_inL的总和

        double[][] zeroZ = new double[data.getJ()][data.getK_f()];

        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                sumDL_var += cplex.getValue(dL[i][n]);
                sumDinL += calculator.calculateD_inL(i, n, bestGamma.y, zeroZ);
            }
        }

        System.out.printf("sum_dL   (ΣdL[i][n]):          %.6f%n", sumDL_var);
        System.out.printf("sum_D_inL (Σ D_in^L):           %.6f%n", sumDinL);
        System.out.printf("差值 (sum_dL - sum_D_inL):      %.6f (%.4f%%)%n",
                sumDL_var - sumDinL, 100.0 * (sumDL_var - sumDinL) / sumDinL);
        System.out.println();

        // 检查OA约束违反
        checkOAViolations();

        System.out.println("==================");
    }
}
