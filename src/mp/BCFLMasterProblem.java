package mp;

import java.util.ArrayList;
import ilog.cplex.IloCplex;
import input.Data;
import ilog.concert.*;
import utils.BCFLCalculator;
import utils.BCFLCalculator.MPDinGradientResult;
import utils.BCFLCalculator.MPTauGradientResult;
import input.Data.Node;
import input.Data.Service;
import pool.FeasibleSolution;
import pool.Gamma;
import pool.XiSet;
import input.Data.Parameters;
import java.util.List;


/**
 * 整数解存储类：用于存储OA切割依赖的整数解(y^w, z^w)
 */
class IntYZSolution {
    public final double[][] y;
    public final double[][] z;

    public IntYZSolution(double[][] y, double[][] z) {
        this.y = y;
        this.z = z;
    }
}


/**
 * BCFL-NLM算法主问题类
 * 负责构建并求解主问题MP(Ξ,F)，包含设施选址、服务配置、变量约束及目标函数定义
 * 核心逻辑：基于双层规划的上层领导者决策（电子零售商设施与服务规划）
 */
public class BCFLMasterProblem {
    // 数据依赖
    private final Data data ;
    private final List<Service> services;
    private final Parameters params;
    private final utils.BCFLCalculator calculator;

    // CPLEX求解器实例
    private IloCplex cplex;

    // 决策变量（对应领导者问题决策维度）
    private IloIntVar[] vL;          // vL[j]：候选点j是否建设电子零售店（0-1变量）
    private IloIntVar[] vP;          // vP[j]：候选点j是否建设换电站（0-1变量）
    private IloIntVar[][] x;        // x[j][s]：设施j是否提供服务s（0-1变量）
    private IloIntVar[][] y;        // y[i][s]：客户区i是否选择服务s（0-1变量）
    private IloIntVar[] vF;         // vF[j]：候选点j是否建设传统零售店（0-1变量）
    private IloIntVar[][] z;        // z[j][k]：传统零售商j是否选择折扣等级k（0-1变量）
    private IloNumVar[][] d;        // d[i][n]：辅助变量，替代D_in(y,z)（市场总需求）
    private IloNumVar phi;          // 辅助变量，替代Φ(y,z)（追随者市场份额）
    private IloNumVar[] tau;          // tau[h]：聚合形式辅助变量，替代Φ(y,z^h)=Σ_{i,n}D_in^F(y,z^h)
    private IloIntVar[][] t;        // t[h][j]：辅助二元变量，用于新约束（0-1变量，维度 H×J）

    // 集合与迭代数据
    private XiSet xiSet;  // Ξ集合管理器：存储历史γ^h解（完整领导者解）
    private List<FeasibleSolution> feasibleSet;// A集合：存储历史(γ^h,z^h)可行解
    private List<IntYZSolution> IntYZSet;// IntYZSet集合：存储整数解(y,z)用于OA切割
    private double MpObjective;      // 主问题最优目标函数值（上界UB）
    private double[] bestSolution;     // 最优解向量（序列化存储所有决策变量）
    private boolean isOptimal;         // 主问题是否最优求解标记
    // 数值稳定性相关常数
    private static final double LEADER_SUM_EPS = 1e-8;   // 判断leader部分为0的容差
    private static final double DELTA_Y_SHIFT   = 1e-4;  // y变量的线性化平移量
    
    // ========== psi收集相关变量（分支定界过程中收集整数解）==========
    // 子问题引用已移除：回调中不再求解子问题，改为在主问题求解完成后批量处理
    private List<pool.Gamma> globalCollectedGammaList;       // 全局gamma列表（跨迭代累积，用于去重）
    private List<pool.Psi> currentIterationPsiList;          // 临时psi列表（每次迭代专用）
    private List<pool.FeasibleSolution> currentIterationSolutions; // 本次迭代收集的(gamma, psi)可行解对
    private List<pool.Gamma> currentIterationGammaList;      // 本次迭代在B&B中收集的gamma列表（不重复）

    // 时间统计字段（用于区分主问题和子问题耗时）
    private double totalSolveTime;   // 总求解时间（秒）
    private double subProblemTime;   // 子问题累计求解时间（秒）

    // 常量定义（与问题参数映射）
    private int J; // 候选点数量（J类节点：type=1）
    private int I; // 客户区数量（I类节点：type=-1）
    private int K_f; // 折扣等级数量
    private int S; // 服务类型数量（含传统零售服务）

    private int N; // 包裹类型数量（默认2：n=0不可无人机配送，n=1可配送）
    private boolean enableResolvingLoop = false;  // 是否启用重新求解循环（默认true）
    private boolean useAdaptiveM2 = true;  // 是否使用自适应M2_h（true=每个h独立计算，false=使用统一M2）
    private boolean disableDrone = false;  // 是否禁用无人机服务（无无人机对比实验开关）
    private boolean disableChargingStation = false;  // 是否禁用换电站（固定vP=0）
    private boolean useOriginalMode = false;  // 实验模式开关：false=1.2版本（默认），true=原始版本

    // ========== 加速策略开关 ==========
    private boolean fixAggCov = false;      // 聚合覆盖链接策略：y[i][s] ≤ Σ r_{ijs}·vL[j]
    private boolean fixTSum = false;        // T求和约束：Σ_h t[h][j] ≤ 1, ∀j
    private boolean fixStationVI = false;   // 换电站有效不等式：vP[j] ≤ Σ vL[j']
    private boolean fixVar = false;         // 追随者变量固定：z[j][k]=0 若 CF_j+f_jk>B_f

    /**
     * 构造函数：初始化数据依赖与求解器
     * @param data 全局数据对象（含节点、资源、参数）
     */
    public BCFLMasterProblem(Data data) throws IloException {
        this.data = data;
        this.services = data.getServices();
        this.params = data.getParams();
        this.calculator = new BCFLCalculator(data);

        // 初始化维度参数（直接从data获取）
        this.J = data.getJ();
        this.I = data.getI();
        this.K_f = data.getK_f();
        this.S = data.getS();

        this.N = data.getN();

        // 初始化集合与求解器
        this.xiSet = new XiSet(data); // 默认添加ψ^0
        this.feasibleSet = new ArrayList<>();
        this.IntYZSet = new ArrayList<>();
        this.globalCollectedGammaList = new ArrayList<>();  // 初始化全局gamma列表
        this.currentIterationPsiList = new ArrayList<>();   // 初始化临时psi列表
        this.currentIterationSolutions = new ArrayList<>(); // 初始化本次迭代的可行解对列表
        this.currentIterationGammaList = new ArrayList<>(); // 初始化本次迭代的gamma列表
        this.cplex = new IloCplex();
        this.cplex.setOut(null); // 关闭CPLEX日志输出（如需调试可注释）

        // 禁用预处理以确保LazyConstraints正确应用
        // this.cplex.setParam(IloCplex.Param.Preprocessing.Reduce, 0);
        // System.out.println("主问题：已禁用CPLEX预处理，确保LazyConstraints正确应用");

    }
    /**
     * 初始化空集合（用于新的算法逻辑）
     */
    public void initializeEmpty() {
        xiSet.clear();
        feasibleSet.clear();
        System.out.println("xi,A集合初始化为空");
    }

    /**
     * 用指定的psi初始化XiSet（替代默认ψ^0），并清空A集合
     * @param initialPsi 用于初始化XiSet的ψ解
     */
    public void initializeWithPsi(pool.Psi initialPsi) {
        xiSet.clearAndSetInitialPsi(initialPsi);
        feasibleSet.clear();
        System.out.println("XiSet已用指定psi初始化，A集合已清空");
    }

    public void buildModel() throws IloException {
        // 清空既有模型
        cplex.clearModel();
        // 清空动态创建的变量存储

        // 初始化变量
        initializeVariables();

        // 加速策略：追随者变量固定（建模前收紧变量界）
        if (fixVar) applyFollowerZFixing();

        // 构建
        buildObjectiveFunction();
        buildOmegaConstraints();

        // 加速策略：聚合覆盖链接约束 y[i][s] ≤ Σ r_{ijs}·vL[j]
        if (fixAggCov) addAggregatedCoverageConstraint();

        // 加速策略：换电站有效不等式 vP[j] ≤ Σ vL[j']
        if (fixStationVI) addChargingStationVI();

        buildFeasibleCut();//M1,M2,oacut在回调函数

        // 加速策略：T求和约束 Σ_h t[h][j] ≤ 1
        if (fixTSum) addTSumConstraint();

        addLogicalCutConstraint();// 4.9 逻辑切约束（基于A集合）：∑d_in ≤ M3_h×[x/vL/vP偏离项] + D^h，其中M3_h=sumM−D^h，vL与vP分开处理
    }

    /**
     * 数值稳定性辅助方法：
     * 当某个客户i在包裹类型n上的leader部分
     *   Σ_s a_sn y_is e^{u_s/λ_l}
     * 为0时，在该客户维度上对y施加一个平移δ，用于构造OA切割的线性化展开点(y+δ, z)。
     *
     * 说明：该方法仅用于计算D_in及其梯度的参考点，不会修改CPLEX中的原始变量值。
     */
    private double[][] buildShiftedY(double[][] yBase, int targetI, double delta) {
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
     * 深拷贝二维矩阵
     */
    private double[][] cloneMatrix(double[][] source) {
        double[][] clone = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            clone[i] = source[i].clone();
        }
        return clone;
    }

    /**
     * 步骤2：初始化所有决策变量与辅助变量
     * 变量定义严格遵循领导者问题
     */
    private void initializeVariables() throws IloException {
        // 1. 设施选址变量：w[j]（零售店）、v[j]（换电站）
        vL = new IloIntVar[J];
        vP = new IloIntVar[J];
        for (int j = 0; j < J; j++) {
            vL[j] = cplex.boolVar("vL_" + j); // 0-1变量
            vP[j] = cplex.boolVar("vP_" + j); // 0-1变量
        }

        // 2. 服务配置变量：x[j][s]（设施j-服务s）s∈S/{|S|}，排除换电站服务
        x = new IloIntVar[J][S-1];
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < S-1; s++) {
                x[j][s] = cplex.boolVar("x_" + j + "_" + s);
            }
        }

        // 3. 客户选择变量：y[i][s]（客户i-服务s）
        y = new IloIntVar[I][S];
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                y[i][s] = cplex.boolVar("y_" + i + "_" + s);
            }
        }
        // wF变量: 下层候选点选址变量 (J个)
        vF = new IloIntVar[J];
        for (int j = 0; j < J; j++) {
            vF[j] = cplex.boolVar("vF_" + j); // 0-1变量，传统零售商选择变量
        }

        // 4. 传统零售商折扣变量：z[j][k]（下层候选点j∈J-折扣等级k）
        z = new IloIntVar[J][K_f];
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                z[j][k] = cplex.boolVar("z_" + j + "_" + k);
            }
        }
        // 4. 辅助变量：d[i][n]、phi（Φ(y,z)）
        d = new IloNumVar[I][N];

        // 从数据中获取Min值（M_in数组的最小值）
        double Min[][] = new double[I][N];
        for (int i = 0; i < I; i++) {
            Data.INodes iNode = data.getINodes().get(i);
            double[] M_in = iNode.getM_in();
            for (int n = 0; n < N; n++) {
                Min[i][n] = M_in[n]; // 每个客户区每种包裹类型使用对应的M_in值
            }
        }

        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                d[i][n] = cplex.numVar(0, Min[i][n], "d_" + i + n); // 非负约束，上界Min[i][n]
            }
        }

        // phi的上界：所有d[i][n]的和的上界
        double sumMin = 0.0;
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                sumMin += Min[i][n];
            }
        }
        phi = cplex.numVar(0, sumMin, "phi"); // 非负约束，上界sum_{i,n}Min[i][n]

        // 动态调整tau数组大小以适应xiSet的变化
        int currentXiSize = xiSet.getSize();

        // 创建tau[h]变量数组（每个历史解h对应一个tau变量，聚合形式）
        tau = new IloNumVar[currentXiSize];
        for (int h = 0; h < currentXiSize; h++) {
            tau[h] = cplex.numVar(0, sumMin, "tau_" + h);
        }

        // 5. 初始化辅助变量t[h][j]（H×J维度的二元变量）
        // H = currentXiSize（即|Ξ|集合的大小）
        t = new IloIntVar[currentXiSize][J];
        for (int h = 0; h < currentXiSize; h++) {
            for (int j = 0; j < J; j++) {
                t[h][j] = cplex.boolVar("t_" + h + "_" + j);
            }
        }
    }

    /**
     * 步骤3：构建主问题目标函数
     * 目标：最大化电子零售商市场份额（文档5公式9、24）
     * 转化为：max ΣΣd_in - phi（d_in替代D_in，phi替代追随者份额Φ）
     */
    private void buildObjectiveFunction() throws IloException {
        IloLinearNumExpr objExpr = cplex.linearNumExpr();

        // 累加所有d_in（市场总需求）
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                objExpr.addTerm(1.0, d[i][n]);
            }
        }
        // 减去追随者份额phi
        objExpr.addTerm(-1.0, phi);

        // 设置最大化目标
        cplex.addMaximize(objExpr);
    }

    /**
     * 步骤4：构建主问题所有约束
     * 约束分类：预算约束、设施互斥约束、服务范围约束、无人机特殊约束、辅助变量约束
     */
    private void buildOmegaConstraints() throws IloException {
        // 4.1 预算约束（公式10）：建设+服务成本 ≤ 预算B
        addBudgetConstraint();

        // 4.2 服务范围约束（公式13）：客户选择y依赖设施服务x
        addServiceRangeConstraint();

        // 4.4 设施互斥约束（文档5公式14）：同一候选点最多1种设施（零售店/换电站）
        addFacilityMutexConstraint();

        // 4.5 换电站依赖约束（文档5公式15）：换电站需在无人机服务范围内
        addChargingStationConstraint();

        // 4.6 经过换电站的无人机服务依赖约束（文档5公式16）：经过换电站的无人机服务依赖换电站
        addChargingStationDependentConstraint();

        // 4.7 无人机服务互斥约束（文档5公式17）：客户最多选1种无人机服务
        addDroneServiceMutexConstraint();

        // 4.8 服务配置约束（文档5公式16）：仅零售店可提供服务
        addServiceConfigConstraint();

        // 4.9 新增辅助约束：建店则至少配置一种服务（vL_j ≤ Σ_s x_js, s∈S\{|S|}）
        addLeaderFacilityServiceActivationConstraint();

        // 4.11 传统零售商选择约束（公式22-23）：每个传统零售商必须选择折扣等级
        addTraditionalRetailerConstraints();

        // 4.12 至少选择一个y约束：∑_{i∈I} ∑_{s∈S} y[i][s] ≥ 1
        addAtLeastOneYConstraint();

        // 4.13 至少选择一个z约束：∑_{j∈J} ∑_{k∈K_f} z[j][k] ≥ 1
        //addAtLeastOneZConstraint();
        
        // 4.14 无无人机对比实验：禁用不经过换电站的无人机服务
        // 通过约束传导：x[j][S-2]=0 → vP[j]=0 → y[i][S-1]=0，两种无人机服务全部禁用
        if (disableDrone) {
            int droneNoChargeS = S - 2;
            for (int j = 0; j < J; j++) {
                cplex.addEq(x[j][droneNoChargeS], 0, "DisableDrone_x_" + j);
            }
            System.out.println("[MP] 无无人机模式已启用：x[j][" + droneNoChargeS + "]=0");
        }

        // 4.15 禁用换电站实验：固定vP[j]=0
        if (disableChargingStation) {
            for (int j = 0; j < J; j++) {
                cplex.addEq(vP[j], 0, "DisableCharging_vP_" + j);
            }
            System.out.println("[MP] 禁用换电站模式已启用：vP[j]=0");
        }
    }

    private void buildFeasibleCut() throws IloException {
        // 4.10 辅助变量约束：phi (M2约束)
        addAuxiliaryPhiConstraintM2();

        // 4.12 新增t约束：基于历史解的设施选址约束 (M1约束)
        addNewTConstraintM1();

        // addTSumConstraint() 已移至加速策略中（fixTSum控制）
    }

    /**
     * 4.1 预算约束（公式10）
     * 成本构成：服务提供成本(F_s*x_js) + 零售店建设成本(l_j*vL_j) + 换电站建设成本(c_j*vP_j)
     */
    private void addBudgetConstraint() throws IloException {
        IloLinearNumExpr costExpr = cplex.linearNumExpr();

        // 第一部分：服务提供成本 ∑_{j∈J} ∑_{s∈S\{|S|}} F_s x_{js}
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < S-1; s++) { // s ∈ S\{|S|}，即排除最后一个服务
                Service service = services.get(s);
                double fs = service.getFs(); // F_s - 服务s的固定成本
                costExpr.addTerm(fs, x[j][s]);
            }
        }

        // 第二部分：零售店建设成本 ∑_{j∈J} L_j vL_j
        for (int j = 0; j < J; j++) {
            Data.JNodes jNode = data.getJNodes().get(j);
            double lj = jNode.getL_J(); // L_j - 零售店j的建设成本
            costExpr.addTerm(lj, vL[j]);
        }

        // 第三部分：换电站建设成本 ∑_{j∈J} C_j vP_j
        for (int j = 0; j < J; j++) {
            Data.JNodes jNode = data.getJNodes().get(j);
            double cj = jNode.getC_J(); // C_j - 换电站j的建设成本
            costExpr.addTerm(cj, vP[j]);
        }

        // 预算约束：总成本 ≤ B
        cplex.addLe(costExpr, params.B_l, "BudgetConstraint");
    }

    /**
     * 4.3 设施互斥约束（公式14）
     * 约束：vL[j] + vP[j] ≤ 1（同一候选点不能同时为零售店和换电站）
     */
    private void addFacilityMutexConstraint() throws IloException {
        for (int j = 0; j < J; j++) {
            IloLinearNumExpr expr = cplex.linearNumExpr();
            expr.addTerm(1.0, vP[j]);
            expr.addTerm(1.0, vL[j]);
            cplex.addLe(expr, 1.0, "FacilityMutex_" + j);
        }
    }

    /**
     * 4.3 服务范围约束（公式13）
     * 约束：y[i][s] ≤ Σ(r_ijs * x[j][s])（客户i选择服务s需设施j覆盖）
     * r_ijs：客户i到设施j的服务s覆盖标记（1=覆盖，0=未覆盖）
     * 注意：s=0,1,2是电子零售店服务，需要设施配置，对应x[j][0,1,2]；s=3通过换电站依赖约束处理
     */
    private void addServiceRangeConstraint() throws IloException {
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S-1; s++) { // s=0,1,2，排除s=3（换电站服务）
                // s=0,1,2对应x[j][0,1,2]
                IloLinearNumExpr rhsExpr = cplex.linearNumExpr();
                // 遍历所有设施j，直接获取r_ijs并累加x[j][s]
                for (int j = 0; j < J; j++) {
                    // 直接从Data中获取服务覆盖值r_ijs
                    double rIJS = data.getServiceCoverage(i, j, s);
                    rhsExpr.addTerm(rIJS, x[j][s]);
                }
                // 约束：y[i][s] ≤ 累加结果
                cplex.addLe(y[i][s], rhsExpr, "ServiceRange_" + i + "_" + s);
            }
        }
    }

    /**
     * 聚合覆盖链接约束（加速策略6）
     * 约束：y[i][s] ≤ Σ_j r_{ijs} · vL[j], ∀i∈I, s∈S\{|S|}
     *
     * 由原始约束 y[i][s] ≤ Σ_j r_{ijs}·x[j][s] 和 x[j][s] ≤ vL[j] 推导而来，
     * 但更紧：LP松弛中 x[j][s] 可取分数，而 vL[j] 受互斥等约束限制更严。
     * 相当于提前做变量消除，收紧LP松弛界。
     */
    private void addAggregatedCoverageConstraint() throws IloException {
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S - 1; s++) {
                IloLinearNumExpr rhsExpr = cplex.linearNumExpr();
                boolean hasCoverage = false;
                for (int j = 0; j < J; j++) {
                    double rIJS = data.getServiceCoverage(i, j, s);
                    if (rIJS > 0.5) {
                        rhsExpr.addTerm(rIJS, vL[j]);
                        hasCoverage = true;
                    }
                }
                if (hasCoverage) {
                    cplex.addLe(y[i][s], rhsExpr, "AggCov_y_" + i + "_" + s);
                }
            }
        }
    }

    /**
     * 追随者变量固定策略：若 CF_j + f_jk > B_f，则 z[j][k] = 0
     */
    private void applyFollowerZFixing() throws IloException {
        double B_f = params.B_f;
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
            System.out.println("[加速] 固定变量-追随者z: " + zFixedCount + "个");
    }

    /**
     * 换电站有效不等式：vP[j] ≤ Σ_{j'≠j, r_{jj',|S|}=1} vL[j']
     * 由约束 vP[j] ≤ Σ r_{jj'}·x[j'][S-2] 和 x[j'][S-2] ≤ vL[j'] 推导，
     * 直接关联选址决策变量，收紧LP松弛。
     */
    private void addChargingStationVI() throws IloException {
        int droneChargeS = S - 1;
        double droneCs = data.getServices().get(droneChargeS).getR_s();
        int addedCount = 0;

        for (int j = 0; j < J; j++) {
            Node nodeJ = data.getJNodes().get(j);
            double xJ = nodeJ.getX();
            double yJ = nodeJ.getY();
            IloLinearNumExpr rhsExpr = cplex.linearNumExpr();
            boolean hasCandidate = false;

            for (int jPrime = 0; jPrime < J; jPrime++) {
                if (jPrime == j) continue;
                Node nodeJP = data.getJNodes().get(jPrime);
                double dist = Math.sqrt(Math.pow(xJ - nodeJP.getX(), 2) + Math.pow(yJ - nodeJP.getY(), 2)) / 1000.0;
                if (dist <= droneCs) {
                    rhsExpr.addTerm(1.0, vL[jPrime]);
                    hasCandidate = true;
                }
            }

            if (hasCandidate) {
                cplex.addLe(vP[j], rhsExpr, "StationVI_" + j);
                addedCount++;
            } else {
                vP[j].setUB(0.0);
            }
        }
        System.out.println("[加速] 换电站VI: " + addedCount + "条约束");
    }


    /**
     * 4.4 换电站依赖约束（公式15）
     * 约束：vP_j ≤ ∑_{j≠j'∈J}{r_{jj',|S|}·x_{j',|S|-1}}（换电站需在无人机服务范围内）
     * s=|S|-2：不经过换电站的无人机服务
     */
    private void addChargingStationConstraint() throws IloException {

        int droneChargeS = S - 1; // s=|S|-1：经过换电站的无人机服务
        Service droneCharges = services.get(droneChargeS);
        double droneCs = droneCharges.getR_s();
        System.out.println("droneCs:"+droneCs);

        for (int j = 0; j < J; j++) {
            // 换电站j的坐标
            Node chargingNode = data.getJNodes().get(j);
            double xJ = chargingNode.getX();
            double yJ = chargingNode.getY();

            IloLinearNumExpr rhsExpr = cplex.linearNumExpr();
            // 遍历其他设施j'（j'≠j）
            for (int jPrime = 0; jPrime < J; jPrime++) {
                if (jPrime == j) continue;
                // 设施j'的坐标
                Node facilityJPrime = data.getJNodes().get(jPrime);
                double xJPrime = facilityJPrime.getX();
                double yJPrime = facilityJPrime.getY();

                // 计算r_{jj',|S|-1}：j'到j的距离≤无人机续航则为1
                double distance = Math.sqrt(Math.pow(xJ - xJPrime, 2) + Math.pow(yJ - yJPrime, 2));
                double distanceInKm = distance / 1000.0;  // 米 → 公里
                double rJJPrime = (distanceInKm <= droneCs) ? 1.0 : 0.0;

                // 累加x[j'][droneServiceS]（无人机服务配置）
                rhsExpr.addTerm(rJJPrime, x[jPrime][droneChargeS-1]);//x只有前三种服务索引
            }

            // 约束：换电站建设v[j] ≤ 无人机服务覆盖的设施x总和（公式13）
            cplex.addLe(vP[j], rhsExpr, "ChargingStationDep_" + j);
        }
    }

    /**
     * 4.5 经过换电站的无人机服务依赖约束（公式16）
     * 约束：y_{i,|S|} ≤ ∑_{j∈J}r_{ij,|S|-1}·vP_j（客户i选择经过换电站的无人机服务需换电站j覆盖）
     * 其中：s=|S|表示经过换电站的无人机服务，s=|S|-1表示不经过换电站的无人机服务
     */
    private void addChargingStationDependentConstraint() throws IloException {

        int droneServiceS = S - 2; // s=|S|-1（不经过换电站的无人机服务）
        int droneWithChargeS = S - 1;  // s=|S|（经过换电站的无人机服务）

        for (int i = 0; i < I; i++) {
            // 约束：y_{i,|S|} ≤ ∑_{j∈J}r_{ij,|S|-1}·vP_j
            IloLinearNumExpr rhsExpr1 = cplex.linearNumExpr();

            // 遍历所有换电站j，累加r_{ij,|S|-1} * vP_j
            for (int j = 0; j < J; j++) {
                // 计算r_{ij,|S|-1}：客户i到换电站j的距离≤不经过换电站的无人机服务续航
                Service droneService = services.get(droneServiceS);
                double droneRs = droneService.getR_s();

                // 客户i的坐标
                Node customerNode = data.getINodes().get(i);
                double xI = customerNode.getX();
                double yI = customerNode.getY();

                // 换电站j的坐标
                Node chargingNode = data.getJNodes().get(j);
                double xJ = chargingNode.getX();
                double yJ = chargingNode.getY();

                // 计算距离并判断是否在服务范围内
                double distance = Math.sqrt(Math.pow(xI - xJ, 2) + Math.pow(yI - yJ, 2));
                double distanceInKm = distance / 1000.0;  // 米 → 公里
                double rIJ = (distanceInKm <= droneRs) ? 1.0 : 0.0;

                // 累加r_{ij,|S|-1} * vP_j
                rhsExpr1.addTerm(rIJ, vP[j]);
            }

            // 约束：y[i][|S|] ≤ Σ(r_{ij,|S|-1} * vP_j)
            cplex.addLe(y[i][droneWithChargeS], rhsExpr1, "ChargingStationDependent1_" + i);
        }
    }

    /**
     * 4.6 无人机服务互斥约束（公式18）
     * 约束：y[i][|S|-1] + y[i][|S|] ≤ 1（客户i最多选1种无人机服务）
     * s=|S|-1：不经过换电站的无人机服务；s=|S|：经过换电站的无人机服务
     */
    private void addDroneServiceMutexConstraint() throws IloException {
        if (S < 3) return; // 需至少包含3种服务（传统+2类无人机）

        int droneNoChargeS = S - 2; // s=|S|-1（不经过换电站）
        int droneWithChargeS = S - 1; // s=|S|（经过换电站）

        for (int i = 0; i < I; i++) {
            IloLinearNumExpr expr = cplex.linearNumExpr();
            expr.addTerm(1.0, y[i][droneNoChargeS]);
            expr.addTerm(1.0, y[i][droneWithChargeS]);

            // 约束：两种无人机服务不能同时选择
            cplex.addLe(expr, 1.0, "DroneMutex_" + i);
        }
    }

    /**
     * 4.7 服务配置约束（公式19）
     * 约束：x_{js} ≤ vL_j，∀j∈J,s∈S/{|S|}（仅建设零售店的候选点j，才能提供服务s）
     */
    private void addServiceConfigConstraint() throws IloException {
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < S-1; s++) { // s∈S/{|S|}，排除换电站服务
                // 约束：x_{js} ≤ w_j（w[j]=1才允许提供服务s）
                cplex.addLe(x[j][s], vL[j], "ServiceConfig_" + j + "_" + s);
            }
        }
    }
    /**
     * 新增辅助约束$$v^L_j\le \sum_{s\in S/\{|S|\}}x_{js}\ ,\forall j\in J$$
     * @throws IloException
     */
    private void addLeaderFacilityServiceActivationConstraint() throws IloException {
        // 若没有可配置的服务维度（S\{|S|}为空），则不添加该约束
        if (S < 2) return;

        for (int j = 0; j < J; j++) {
            IloLinearNumExpr rhs = cplex.linearNumExpr();
            for (int s = 0; s < S - 1; s++) { // s ∈ S\{|S|}
                rhs.addTerm(1.0, x[j][s]);
            }
            // v^L_j ≤ Σ_{s∈S\{|S|}} x_{js}
            cplex.addLe(vL[j], rhs, "LeaderFacilityActivation_" + j);
        }
    }

    // ========== 加速策略方法 ==========
    /**
     * 4.10 传统零售商选择约束
     * 约束(26): Σ_{j∈J} l_j*v^F_j + Σ_{j∈J} Σ_{k∈K_f} f_{jk}*z_{jk} ≤ b
     * 约束(27): Σ_{k∈K_f} z_{jk} ≤ v^F_j, ∀j∈J
     * 约束(28): v^F_j ≤ 1 - v^L_j - vP_j, ∀j∈J (共址禁止约束)
     * 约束(29): v^F_j ∈ {0,1}, ∀j∈J (已在变量定义中处理)
     * 约束(30): z_{jk} ∈ {0,1}, ∀j∈J, k∈K_f (已在变量定义中处理)
     */
    private void addTraditionalRetailerConstraints() throws IloException {
        // 约束(26): Σ_{j∈J_f} l_j*v^F_j + Σ_{j∈J_f} Σ_{k∈K_f} f_{jk}*z_{jk} ≤ b
        IloLinearNumExpr constraint26Expr = cplex.linearNumExpr();

        // 添加 l_j*v^F_j 项 (j∈J_f)
        for (int j = 0; j < J; j++) {
            Data.JNodes jfNode = data.getJNodes().get(j);
            double l_j = jfNode.getCF_j(); // 获取 l_j
            constraint26Expr.addTerm(l_j, vF[j]);
        }

        // 添加 f_{jk}*z_{jk} 项 (j∈J_f)
        for (int j = 0; j < J; j++) {
            Data.JNodes jfNode = data.getJNodes().get(j);
            double[] f_jk = jfNode.get_f_jk();
            for (int k = 0; k < K_f; k++) {
                double fjk = f_jk[k]; // f_{jk} - 传统零售商j在折扣等级k的成本
                constraint26Expr.addTerm(fjk, z[j][k]);
            }
        }
        cplex.addLe(constraint26Expr, params.B_f, "Constraint26_BudgetLimit");

        // 约束(27): Σ_{k∈K_f} z_{jk} = v^F_j, ∀j∈J
        for (int j = 0; j < J; j++) {
            IloLinearNumExpr zSumExpr = cplex.linearNumExpr();
            for (int k = 0; k < K_f; k++) {
                zSumExpr.addTerm(1.0, z[j][k]);
            }
            cplex.addEq(zSumExpr, vF[j], "Constraint27_TraditionalRetailerChoice_" + j);
        }

        // 约束(28): v^F_j ≤ 1 - v^L_j - vP_j, ∀j∈J (共址禁止约束)
        // 说明: 由于上下层候选点共用J集合,下层不能在上层已建店或建换电站的位置建店
        for (int j = 0; j < J; j++) {
            IloLinearNumExpr expr = cplex.linearNumExpr();
            expr.addTerm(1.0, vF[j]);
            expr.addTerm(1.0, vL[j]);
            expr.addTerm(1.0, vP[j]);
            // v^F_j + v^L_j + vP_j ≤ 1
            cplex.addLe(expr, 1.0, "Constraint28_CoLocationProhibition_" + j);
        }
    }

    /**
     * 4.12 至少选择一个y约束
     * 约束形式：∑_{i∈I} ∑_{s∈S} y[i][s] ≥ 1
     * 说明：确保领导者至少提供一个服务选择
     */
    private void addAtLeastOneYConstraint() throws IloException {
        IloLinearNumExpr ySumExpr = cplex.linearNumExpr();

        // 累加所有y变量
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                ySumExpr.addTerm(1.0, y[i][s]);
            }
        }

        // 添加约束：sum(y) ≥ 1
        cplex.addGe(ySumExpr, 1.0, "Constraint_AtLeastOneY");
        System.out.println("已添加约束：至少选择一个y (sum(y) >= 1)");
    }

    /**
     * 4.13 至少选择一个z约束（主问题）
     * 约束形式：∑_{j∈J} ∑_{k∈K_f} z[j][k] ≥ 1
     * 说明：确保跟随者至少在一个候选点提供一个折扣等级
     */
    // private void addAtLeastOneZConstraint() throws IloException {
    //     IloLinearNumExpr zSumExpr = cplex.linearNumExpr();

    //     // 累加所有z变量
    //     for (int j = 0; j < J; j++) {
    //         for (int k = 0; k < K_f; k++) {
    //             zSumExpr.addTerm(1.0, z[j][k]);
    //         }
    //     }

    //     // 添加约束：sum(z) ≥ 1
    //     cplex.addGe(zSumExpr, 1.0, "Constraint_AtLeastOneZ");
    //     System.out.println("已添加约束：至少选择一个z (sum(z) >= 1)");
    // }

    /**
     * 4.11 新增约束：基于历史解的设施选址约束
     * 约束形式：v^L_j + v^P_j ≥ Σ_{h∈H} b_{hj} * t_{hj}, ∀j∈J
     * 其中：
     *   - H = |Ξ|（Ξ集合的大小）
     *   - b_{hj} = 1 - v^F_j^h + ε
     *   - v^F_j^h 是历史解h中的vF[j]值（从xiSet获取）
     *   - ε = 1e-4（小的正数）
     *   - t_{hj} 是辅助二元变量
     *
     * 简化形式：v^L_j + v^P_j ≥ Σ_{h∈H} (1 - v^F_j^h + ε) * t_{hj}, ∀j∈J
     */
    private void addNewTConstraintM1() throws IloException {
        // 定义epsilon（小的正数）
        final double EPSILON = 1e-3;

        // 获取当前Ξ集合大小（H = |Ξ|）
        int currentXiSize = xiSet.getSize();

        // 对每个候选点j添加约束
        for (int j = 0; j < J; j++) {
            // 构造约束左侧：v^L_j + v^P_j
            IloLinearNumExpr lhs = cplex.linearNumExpr();
            lhs.addTerm(1.0, vL[j]);
            lhs.addTerm(1.0, vP[j]);

            // 构造约束右侧：Σ_{h∈H} b_{hj} * t_{hj}
            IloLinearNumExpr rhs = cplex.linearNumExpr();

            for (int h = 0; h < currentXiSize; h++) {
                // 获取历史解h中的vF^h[j]
                double[] vFh = xiSet.getVFh(h);

                if (vFh != null && j < vFh.length) {
                    // 计算b_{hj} = 1 - v^F_j^h + ε
                    double b_hj = 1.0 - vFh[j] + EPSILON;

                    // 添加项：b_{hj} * t_{hj}
                    rhs.addTerm(b_hj, t[h][j]);
                }
            }

            // 添加约束：v^L_j + v^P_j ≥ Σ_{h∈H} b_{hj} * t_{hj}
            cplex.addGe(lhs, rhs, "NewTConstraint_" + j);
        }

        System.out.printf("已添加新的t约束：共 %d 条（每个候选点j一条），基于 %d 个历史解\n", J, currentXiSize);
    }

    /**
     * 新增约束：t变量的和约束
     * 约束形式：∑_{h∈Ξ} t_{hj} ≤ 1, ∀j∈J
     * 说明：对于每个候选点j，所有历史解h对应的t[h][j]之和不能超过1
     */
    private void addTSumConstraint() throws IloException {
        // 获取当前Ξ集合大小（H = |Ξ|）
        int currentXiSize = xiSet.getSize();

        if (currentXiSize == 0) {
            System.out.println("Ξ集合为空，跳过t和约束");
            return;
        }

        // 对每个候选点j添加约束
        for (int j = 0; j < J; j++) {
            // 构造约束：∑_{h=0}^{H-1} t[h][j] ≤ 1
            IloLinearNumExpr sumExpr = cplex.linearNumExpr();

            for (int h = 0; h < currentXiSize; h++) {
                sumExpr.addTerm(1.0, t[h][j]);
            }

            // 添加约束：sum(t[h][j]) ≤ 1
            cplex.addLe(sumExpr, 1.0, "TSumConstraint_" + j);
        }

        System.out.printf("已添加t和约束：共 %d 条（每个候选点j一条），基于 %d 个历史解\n", J, currentXiSize);
    }

    /**
     * 4.10 辅助变量phi约束（M²约束）
     * 约束形式：φ ≥ Σ_i Σ_n τ_inh - M²_h × Σ_{(h',q)∈B(h,H)} t_{h'q}, ∀h∈{0,1,...,H}
     *
     * 其中：
     * - B(h,H) = {(h',q) | b_{h'q} ≥ b_{hq}, h'∈{0,1,...,H}, q∈J}
     * - b_{hq} = 1 - v^F_q^h + ε
     * - M²_h: 大M常数（暂时使用占位值）
     */
    private void addAuxiliaryPhiConstraintM2() throws IloException {
        // 定义epsilon（与M1约束保持一致）
        final double EPSILON = 1e-3;

        // 获取当前Ξ集合大小（H = |Ξ|）
        int currentXiSize = xiSet.getSize();

        if (currentXiSize == 0) {
            System.out.println("Ξ集合为空，跳过phi的M²约束");
            return;
        }

        // 计算M²值（根据开关选择使用自适应M2_h或统一M2）
        double[] M2_h = new double[currentXiSize];
        double M2_uniform = 0.0;

        if (useAdaptiveM2) {
            // 使用自适应M²_h计算
            // 计算 Φ(γ_max, 0)
            double phiGammaMax = calculatePhiGammaMax();
            System.out.printf("Φ(γ_max, 0) = %.6f%n", phiGammaMax);

            // 公式：M²_h = Σ_{i,n} M_{in}·g(U_{in}(0,ψ^h)) - Φ(γ_max, 0)
            for (int h = 0; h < currentXiSize; h++) {
                double[][] z_h = xiSet.getZh(h);
                double sum_M_g = 0.0;
                for (int i = 0; i < I; i++) {
                    Data.INodes iNode = data.getINodes().get(i);
                    double[] M_in = iNode.getM_in();
                    for (int n = 0; n < N && n < M_in.length; n++) {
                        double U_in = calculator.calculateU_in(i, n, new double[I][S], z_h);
                        double g_Uin = 1.0 - Math.exp(-data.getParams().getLambda() * U_in);
                        sum_M_g += M_in[n] * g_Uin;
                    }
                }
                M2_h[h] = sum_M_g - phiGammaMax;
                if (M2_h[h] < 0) M2_h[h] = 0.0;
            }
            System.out.printf("M²约束：使用自适应M²_h，范围[%.6f, %.6f]%n",
                java.util.Arrays.stream(M2_h).min().orElse(0), java.util.Arrays.stream(M2_h).max().orElse(0));
        } else {
            // 使用统一的M²（传统方法）
            for (int i = 0; i < I; i++) {
                Data.INodes iNode = data.getINodes().get(i);
                double[] M_in = iNode.getM_in();
                for (int n = 0; n < N && n < M_in.length; n++) {
                    M2_uniform += M_in[n];
                }
            }
            java.util.Arrays.fill(M2_h, M2_uniform);
            System.out.printf("M²约束：使用统一M² = %.6f%n", M2_uniform);
        }

        // 预先计算所有历史解的 b_{hj} 矩阵
        double[][] b_matrix = new double[currentXiSize][J];
        for (int h = 0; h < currentXiSize; h++) {
            double[] vFh = xiSet.getVFh(h);
            if (vFh != null) {
                for (int j = 0; j < J; j++) {
                    if (j < vFh.length) {
                        // b_{hj} = 1 - v^F_j^h + ε
                        b_matrix[h][j] = 1.0 - vFh[j] + EPSILON;
                    } 
                }
            } else {
                // 如果vFh为null，使用默认值
                for (int j = 0; j < J; j++) {
                    b_matrix[h][j] = 1.0 + EPSILON;
                }
            }
        }

        // 对每个历史解h添加一个约束
        for (int h = 0; h < currentXiSize; h++) {
            // 构造约束右侧表达式: τ_h - M²_h × Σ_{(h',q)∈B(h,H)} t_{h'q}
            IloLinearNumExpr rhsExpr = cplex.linearNumExpr();

            // 第一项：τ_h（历史解h对应的追随者市场份额上界，聚合形式）
            rhsExpr.addTerm(1.0, tau[h]);

            // 第二项：-M²_h × Σ_{(h',q)∈B(h,H)} t_{h'q}（惩罚项）
            // 构建集合 B(h,H)
            for (int q = 0; q < J; q++) {
                double b_hq = b_matrix[h][q]; // 当前的 b_{hq}

                // 遍历所有 h'，检查 b_{h'q} ≥ b_{hq}
                for (int hPrime = 0; hPrime < currentXiSize; hPrime++) {
                    double b_hPrimeQ = b_matrix[hPrime][q];

                    // 如果 b_{h'q} ≥ b_{hq}，则 (h', q) ∈ B(h,H)
                    if (b_hPrimeQ >= b_hq - 1e-9) { // 使用小容差避免浮点误差
                        // 添加 -M²_h × t_{h'q}
                        rhsExpr.addTerm(-M2_h[h], t[hPrime][q]);
                    }
                }
            }

            // 添加约束：phi ≥ rhsExpr
            cplex.addGe(phi, rhsExpr, "AuxPhiConstraintM2_" + h);
        }

        System.out.printf("已添加phi的M²约束：共 %d 条（每个历史解h一条）%n", currentXiSize);
    }


    /**
     * 4.9 逻辑切约束 - 基于 x, vL 和 vP 变量，vL与vP分开处理
     * 【已修改：删去phi，Pi改为D，vL与vP分开处理】
     *
     * 约束形式：
     *   ∑_{i∈I} ∑_{n∈N} d_{in} ≤ M³_h × [
     *     ∑_{(j,s)∈M¹(x^h)} (1 - x_{js}) + ∑_{(j,s)∈M⁰(x^h)} x_{js}
     *     + ∑_{j∈M¹(vL^h)} (1 - vL_j) + ∑_{j∈M⁰(vL^h)} vL_j
     *     + ∑_{j∈M¹(vP^h)} (1 - vP_j) + ∑_{j∈M⁰(vP^h)} vP_j
     *   ] + D(γ^h, ψ^h), ∀(γ^h, ψ^h) ∈ A
     *
     * 其中 M³_h = sumM − D^h，sumM = ∑_{i,n} M_{in}
     *       D(γ^h, ψ^h) = Π(γ^h, ψ^h) + Φ(γ^h, ψ^h)
     *
     * - M¹(x^h): x^h_{js} = 1 的索引集合
     * - M⁰(x^h): x^h_{js} = 0 的索引集合
     * - M¹(vL^h): vL^h_j = 1 的索引集合
     * - M⁰(vL^h): vL^h_j = 0 的索引集合
     * - M¹(vP^h): vP^h_j = 1 的索引集合
     * - M⁰(vP^h): vP^h_j = 0 的索引集合
     */
    private void addLogicalCutConstraint() throws IloException {
        // Step 1. 计算总市场规模 sumM = ∑_{i,n} M_{in}
        double sumM = 0.0;
        for (int i = 0; i < I; i++) {
            Node customerNode = data.getINodes().get(i);
            if (customerNode instanceof Data.INodes) {
                Data.INodes iNode = (Data.INodes) customerNode;
                double[] M_in = iNode.getM_in();
                for (int n = 0; n < N && n < M_in.length; n++) {
                    sumM += M_in[n];
                }
            }
        }
        System.out.printf("逻辑切使用 sumM = %.6f%n", sumM);

        // Step 2. 遍历A集合中的历史可行解 (γ^h, ψ^h)
        for (int h = 0; h < feasibleSet.size(); h++) {
            FeasibleSolution feasibleSolh = feasibleSet.get(h);
            if (feasibleSolh == null) continue;

            double PiH = feasibleSolh.PiH; // Π(γ^h, ψ^h) - 历史领导者市场份额
            double PhiH = feasibleSolh.PhiH; // Φ(γ^h, ψ^h) - 历史追随者市场份额
            double DH = PiH + PhiH; // D(γ^h, ψ^h) = Π + Φ

            // M3_h = sumM - D^h，每个历史解使用独立的大M
            double M = sumM - DH;

            // 构造约束: ∑ d_{in} - M3_h×[偏离项] ≤ D^h
            IloLinearNumExpr lhs = cplex.linearNumExpr();

            // 第1部分: 添加 ∑_{i∈I} ∑_{n∈N} d_{in}
            for (int i = 0; i < I; i++) {
                for (int n = 0; n < N; n++) {
                    lhs.addTerm(1.0, d[i][n]);
                }
            }

            // 第2部分: x 变量的偏离项
            double constant = 0.0; // 累计 -M×|M¹|
            double[][] xH = feasibleSolh.getX();
            for (int j = 0; j < J; j++) {
                for (int s = 0; s < S-1; s++) {  // x[j][s]中s∈{0,...,S-2}
                    double xVal = (xH != null && xH[j] != null) ? xH[j][s] : 0.0;
                    if (xVal > 0.5) {
                        // M¹集合: 添加 M×x_{js} 到左边，常数项 -M
                        lhs.addTerm(M, x[j][s]);
                        constant -= M;
                    } else {
                        // M⁰集合: 添加 -M×x_{js} 到左边
                        lhs.addTerm(-M, x[j][s]);
                    }
                }
            }

            // 第3部分: vL 变量的偏离项（分开处理）
            double[] vLH = feasibleSolh.getVL();
            for (int j = 0; j < J; j++) {
                double vLVal = (vLH != null) ? vLH[j] : 0.0;
                if (vLVal > 0.5) {
                    // M¹(vL^h): 添加 M*vL_j 到左边，常数项 -M
                    lhs.addTerm(M, vL[j]);
                    constant -= M;
                } else {
                    // M⁰(vL^h): 添加 -M*vL_j 到左边
                    lhs.addTerm(-M, vL[j]);
                }
            }

            // 第4部分: vP 变量的偏离项（分开处理）
            double[] vPH = feasibleSolh.getVP();
            for (int j = 0; j < J; j++) {
                double vPVal = (vPH != null) ? vPH[j] : 0.0;
                if (vPVal > 0.5) {
                    // M¹(vP^h): 添加 M*vP_j 到左边，常数项 -M
                    lhs.addTerm(M, vP[j]);
                    constant -= M;
                } else {
                    // M⁰(vP^h): 添加 -M*vP_j 到左边
                    lhs.addTerm(-M, vP[j]);
                }
            }

            // 右边常数项 = D^h - constant
            double rhs = DH - constant;

            // 添加约束: lhs ≤ rhs
            String cname = "LogicalCut_vLvP_h" + h;
            cplex.addLe(lhs, rhs, cname);
        }

        System.out.printf("共添加 %d 条逻辑切约束（基于x、vL和vP变量，vL与vP分开处理）%n", feasibleSet.size());
    }





    /**
     * OA-cut回调函数类
     * 在LP松弛求解过程中动态添加OA切割约束
     * 约束类型：
     * 1. d_in ≤ D_in(y,z) - 凹函数约束
     * 2. phi ≥ Φ(y,z^{γ^h}) - 凸函数约束（对Ξ集合中每个γ^h）
     */
    private class OACutCallback extends IloCplex.LazyConstraintCallback {

        @Override
        protected void main() throws IloException {
            try {
                // ===== 第1部分：gamma收集逻辑（仅1.2版本执行）=====
                if (!useOriginalMode) {
                    // 提取当前整数解的gamma
                    pool.Gamma gamma = extractGammaFromCallback();

                    // 检查gamma是否重复（使用全局列表）
                    boolean isDuplicateGamma = false;
                    for (pool.Gamma existing : globalCollectedGammaList) {
                        if (existing.equals(gamma)) {
                            isDuplicateGamma = true;
                            break;
                        }
                    }

                    if (!isDuplicateGamma) {
                        // gamma不重复，记录到本次迭代的收集列表
                        globalCollectedGammaList.add(gamma);
                        currentIterationGammaList.add(gamma);
                        System.out.printf("  [OA回调] 捕获新gamma#%d（本次迭代累计%d个）\n",
                                        globalCollectedGammaList.size(),
                                        currentIterationGammaList.size());
                    }
                }

                // ===== 第2部分：OA cuts逻辑（保留原有）=====
                // 获取当前整数解
                double[][] yBar = getCurrentYSolution();
                double[][] zBar = getCurrentZSolution();

                // 检查d约束违反: d[i][n] ≤ D_in(y,z)
                for (int i = 0; i < I; i++) {
                    for (int n = 0; n < N; n++) {
                        double dinValue = getValue(d[i][n]);

                        // 数值稳定性处理：
                        // 如果该(i,n)的leader部分 Σ_s a_sn y_is e^{u_s/λ_l} 近似为0，
                        // 则在客户i维度上对y施加平移δ，使用(y+δ, z)作为OA cut的线性化展开点，
                        // 避免梯度中的leaderSum^(λ_l-1)出现数值奇异。
                        double leaderSum = calculator.calculateLeaderSum(i, n, yBar);
                        double[][] yRef = yBar;
                        if (leaderSum < LEADER_SUM_EPS) {
                            yRef = buildShiftedY(yBar, i, DELTA_Y_SHIFT);
                        }
                               
                        // 使用批量计算方法（基于可能平移后的yRef）
                        MPDinGradientResult result = calculator.calculateD_in_and_AllGradients(i, n, yRef, zBar);
                        double D_in = result.D_in;

                        // 检查违反：d_in > D_in (允许1e-4的数值容差)
                        if (dinValue > D_in * (1.0 + 1e-4)) {
                            //addDinOACutConstraintBatch(i, n, yRef, zBar, result);
                            // 添加次模切割（使用原始yBar，不做δ偏移）
                            addDinSubmodularCutBatch(i, n, yBar, zBar);
                        }
                    }
                }

                // 检查tau约束违反: tau[h] ≥ Φ(y,z^h) = Σ_{i,n} D_inF(y,z^h)
                int currentXiSize = xiSet.getSize();
                for (int h = 0; h < currentXiSize; h++) {
                    double tauHValue = getValue(tau[h]);
                    double[][] zh = xiSet.getZh(h);

                    // 聚合计算 Φ(ȳ_ref, z^h)，每个(i,n)独立检查是否需要δ平移
                    double phiH = 0.0;
                    for (int i = 0; i < I; i++) {
                        for (int n = 0; n < N; n++) {
                            double leaderSum = calculator.calculateLeaderSum(i, n, yBar);
                            double[][] yRef = yBar;
                            if (leaderSum < LEADER_SUM_EPS) {
                                yRef = buildShiftedY(yBar, i, DELTA_Y_SHIFT);
                            }
                            MPTauGradientResult result = calculator.calculateD_inF_and_AllYGradients(i, n, yRef, zh);
                            phiH += result.D_inF;
                        }
                    }

                    // 检查违反：tau[h] < Φ (允许1e-4的数值容差)
                    if (tauHValue * (1.0 + 1e-4) < phiH) {
                        //addTauOACutForH(yBar, h, zh, phiH);
                        // 与d_in处理一致：tau违反时同时添加次模切
                        addTauSubmodularCutForH(yBar, h, zh);
                    }
                }

                // 记录整数解
                addToIntYZSet(yBar, zBar);

            } catch (Exception e) {
                System.err.println("OA-cut回调函数执行出错: " + e.getMessage());
                e.printStackTrace();
            }
        }
        /**
         * 获取当前y解
         */
        private double[][] getCurrentYSolution() throws IloException {
            double[][] yCurrent = new double[I][S];
            for (int i = 0; i < I; i++) {
                for (int s = 0; s < S; s++) {
                    yCurrent[i][s] = getValue(y[i][s]);
                }
            }
            return yCurrent;
        }
        /**
         * 获取当前z解
         */
        private double[][] getCurrentZSolution() throws IloException {
            double[][] zCurrent = new double[J][K_f];
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    double rawValue = getValue(z[j][k]);
                    zCurrent[j][k] = rawValue;
                }
            }
            return zCurrent;
        }
        
        /**
         * 从回调中提取完整的Gamma解
         * @return Gamma对象 γ = (vL, vP, x, y)
         * @throws IloException CPLEX异常
         */
        private pool.Gamma extractGammaFromCallback() throws IloException {
            // 提取vL
            double[] vLValues = new double[J];
            for (int j = 0; j < J; j++) {
                vLValues[j] = getValue(vL[j]);
            }

            // 提取vP
            double[] vPValues = new double[J];
            for (int j = 0; j < J; j++) {
                vPValues[j] = getValue(vP[j]);
            }

            // 提取x
            double[][] xValues = new double[J][S-1];
            for (int j = 0; j < J; j++) {
                for (int s = 0; s < S-1; s++) {
                    xValues[j][s] = getValue(x[j][s]);
                }
            }

            // 提取y
            double[][] yValues = new double[I][S];
            for (int i = 0; i < I; i++) {
                for (int s = 0; s < S; s++) {
                    yValues[i][s] = getValue(y[i][s]);
                }
            }

            return new pool.Gamma(vLValues, vPValues, xValues, yValues);
        }
        /**
         * 将整数解添加到IntYZSet
         */
        private void addToIntYZSet(double[][] yBar, double[][] zBar) {
            // 深拷贝yBar和zBar
            double[][] yCopy = new double[I][S];
            double[][] zCopy = new double[J][K_f];
            for (int i = 0; i < I; i++) {
                for (int s = 0; s < S; s++) {
                    yCopy[i][s] = yBar[i][s];
                }
            }
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    zCopy[j][k] = zBar[j][k];
                }
            }
            IntYZSet.add(new IntYZSolution(yCopy, zCopy));
            System.out.println("已添加整数解到IntYZSet，当前IntYZSet大小: " + IntYZSet.size());
        }
        
        /**
         * 添加OA切割约束（优化版：使用批量梯度计算）
         */
        private void addDinOACutConstraintBatch(int i, int n, double[][] yRef, double[][] zRef, MPDinGradientResult result) throws IloException {
            IloLinearNumExpr cut = cplex.linearNumExpr();
            cut.addTerm(1.0, d[i][n]);
            double constant = result.D_in;

            // 使用预计算的y梯度
            for (int s = 0; s < S; s++) {
                cut.addTerm(-result.gradients_y[s], y[i][s]);
                constant -= result.gradients_y[s] * yRef[i][s];
            }

            // 使用预计算的z梯度
            for (int j = 0; j < J; j++) {
                for (int h = 0; h < K_f; h++) {
                    cut.addTerm(-result.gradients_z[j][h], z[j][h]);
                    constant -= result.gradients_z[j][h] * zRef[j][h];
                }
            }

            IloRange constraint = cplex.le(cut, constant);
            add(constraint);
        }

        /**
         * 添加d_in的次模切割约束（Submodular-cut）
         * 使用原始yBar，不做δ偏移，因为次模切割基于离散差分而非梯度
         */
        private void addDinSubmodularCutBatch(int i, int n, double[][] yBar, double[][] zBar) throws IloException {
            IloLinearNumExpr cut = cplex.linearNumExpr();
            cut.addTerm(1.0, d[i][n]);
            double U_in = calculator.calculateU_in(i, n, yBar, zBar);
            double D_in = calculator.calculateD_in(i, n, U_in);
            double constant = D_in;
            double[][] yMax = cloneMatrix(yBar);
                for (int ii = 0; ii < I; ii++) {
                    for (int ss = 0; ss < S; ss++) {
                        if(ss<S-1){
                            yMax[ii][ss] = 1.0;
                        }else{
                            yMax[ii][ss] = 0.0;
                        }
                    }
                }
            double[][] zMax = cloneMatrix(zBar);
                for (int j = 0; j < J; j++) {
                    for (int k = 0; k < K_f; k++) {
                        zMax[j][k] = 0.0;
                    }
                    zMax[j][K_f - 1] = 1.0;
                }
            double UMax = calculator.calculateU_in(i, n, yMax, zMax);
            double DMax = calculator.calculateD_in(i, n, UMax);
            // M^1(yBar): 计算 rho^- = D_in(yMax, zMar)) - D_in(yMax)\\{s}, zMax)
            for (int s = 0; s < S; s++) {
                if (yBar[i][s] > 0.5) {
                    double [][]ynoS = cloneMatrix(yMax);
                    ynoS[i][s] = 0.0;
                    
                    double UnoS = calculator.calculateU_in(i, n, ynoS, zMax);
                    double DnoS = calculator.calculateD_in(i, n, UnoS);
                    double rhoMinus = DMax - DnoS;
                    constant -= rhoMinus;
                    cut.addTerm(-rhoMinus, y[i][s]);
                }
            }

            // M^0(yBar): 计算 rho^+ = D_in(M^1(yBar) U {s}, M^1(zBar)) - D_in(M^1(yBar), M^1(zBar))
            for (int s = 0; s < S; s++) {
                if (yBar[i][s] < 0.5) {
                    double[][] yPlus = cloneMatrix(yBar);
                    yPlus[i][s] = 1.0;
                    double UPlus = calculator.calculateU_in(i, n, yPlus, zBar);
                    double DPlus = calculator.calculateD_in(i, n, UPlus);
                    double rhoPlus = DPlus - D_in;
                    cut.addTerm(-rhoPlus, y[i][s]);
                }
            }

            // M^1(zBar): 计算 rho^- = D_in(yBar, M^1(zBar)) - D_in(yBar, M^1(zBar)\\{(j,k)})
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    if (zBar[j][k] > 0.5) {
                        double[][] zNoJK = cloneMatrix(zMax);
                        zNoJK[j][k] = 0.0;
                        double UNoJK = calculator.calculateU_in(i, n, yMax, zNoJK);
                        double DNoJK = calculator.calculateD_in(i, n, UNoJK);
                        double rhoMinus = DMax - DNoJK;
                        constant -= rhoMinus;
                        cut.addTerm(-rhoMinus, z[j][k]);
                    }
                }
            }

            // M^0(zBar): 计算 rho^+ = D_in(yBar, M^1(zBar) U {(j,k)}) - D_in(yBar, M^1(zBar))
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    if (zBar[j][k] < 0.5) {
                        double[][] zPlus = cloneMatrix(zBar);
                        zPlus[j][k] = 1.0;
                        double UPlus = calculator.calculateU_in(i, n, yBar, zPlus);
                        double DPlus = calculator.calculateD_in(i, n, UPlus);
                        double rhoPlus = DPlus - D_in;
                        cut.addTerm(-rhoPlus, z[j][k]);
                    }
                }
            }

            IloRange constraint = cplex.le(cut, constant);
            add(constraint);
        }

        /**
         * 添加聚合tau的OA切割约束（带δ平移）
         * 公式: τ_h ≥ Φ(ȳ_ref, z^h) + Σ_{i,n,s} ∂D^F_{in}(ȳ_ref, z^h)/∂y_{is} × (y_{is} - ȳ_ref_{is})
         */
        private void addTauOACutForH(double[][] yBar, int h, double[][] zh, double phiH) throws IloException {
            IloLinearNumExpr cut = cplex.linearNumExpr();
            cut.addTerm(1.0, tau[h]);
            double constant = phiH;

            for (int i = 0; i < I; i++) {
                for (int n = 0; n < N; n++) {
                    double leaderSum = calculator.calculateLeaderSum(i, n, yBar);
                    double[][] yRef = yBar;
                    if (leaderSum < LEADER_SUM_EPS) {
                        yRef = buildShiftedY(yBar, i, DELTA_Y_SHIFT);
                    }
                    MPTauGradientResult result = calculator.calculateD_inF_and_AllYGradients(i, n, yRef, zh);
                    for (int s = 0; s < S; s++) {
                        double gradient = result.gradients_y[s];
                        cut.addTerm(-gradient, y[i][s]);
                        constant -= gradient * yRef[i][s];
                    }
                }
            }

            IloRange constraint = cplex.ge(cut, constant);
            add(constraint);
        }

        /**
         * 计算聚合追随者需求：Φ(y, z^h) = Σ_{i,n} D^F_{in}(y, z^h)
         */
        private double calculateAggregatedPhi(double[][] yValue, double[][] zh) {
            double phi = 0.0;
            for (int i = 0; i < I; i++) {
                for (int n = 0; n < N; n++) {
                    phi += calculator.calculateD_inF(i, n, yValue, zh);
                }
            }
            return phi;
        }

        /**
         * 添加tau_h的次模切割约束（Submodular-cut）
         * 形式：
         * tau_h ≥ Φ(yBar, z^h)
         *       + Σ_{(i,s)∈M0} rhoPlus(i,s) * y_is
         *       - Σ_{(i,s)∈M1} rhoMinus(i,s) * (1 - y_is)
         */
        private void addTauSubmodularCutForH(double[][] yBar, int h, double[][] zh) throws IloException {
            IloLinearNumExpr cut = cplex.linearNumExpr();
            cut.addTerm(1.0, tau[h]);

            double phiBar = calculateAggregatedPhi(yBar, zh);
            double constant = phiBar;
            double[][] yMax = cloneMatrix(yBar);
                for (int ii = 0; ii < I; ii++) {
                    for (int ss = 0; ss < S; ss++) {
                        if(ss<S-1){
                            yMax[ii][ss] = 1.0;
                        }else{
                            yMax[ii][ss] = 0.0;
                        }
                    }
                }
            double phiMax = calculateAggregatedPhi(yMax, zh);
            for (int i = 0; i < I; i++) {
                for (int s = 0; s < S; s++) {
                    if (yBar[i][s] > 0.5) {
                        // M1: rhoMinus = Φ(M1) - Φ(M1\{(i,s)})
                        double[][] yMinus = cloneMatrix(yMax);
                        yMinus[i][s] = 0.0;
                        double phiMinus = calculateAggregatedPhi(yMinus, zh);
                        double rhoMinus = phiMax - phiMinus;
                        constant -= rhoMinus;
                        cut.addTerm(-rhoMinus, y[i][s]);
                    } else {
                        // M0: rhoPlus = Φ(M1∪{(i,s)}) - Φ(M1)
                        double[][] yPlus = cloneMatrix(yBar);
                        yPlus[i][s] = 1.0;
                        double phiPlus = calculateAggregatedPhi(yPlus, zh);
                        double rhoPlus = phiPlus - phiBar;
                        cut.addTerm(-rhoPlus, y[i][s]);
                    }
                }
            }

            IloRange constraint = cplex.ge(cut, constant);
            add(constraint);
        }
    }


    /**
     * 工具方法：序列化最优解（用于传递给子问题FSP）
     */
    private void serializeBestSolution() throws IloException {
        List<Double> solList = new ArrayList<>();

        // 1. 序列化vL变量（J个）
        for (IloIntVar vL : vL) {
            solList.add(cplex.getValue(vL));
        }

        // 2. 序列化vP变量（J个）
        for (IloIntVar vP : vP) {
            solList.add(cplex.getValue(vP));
        }

        // 3. 序列化x变量（J*(S-1)个）：排除传统零售服务s=|S|
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < S-1; s++) {
                solList.add(cplex.getValue(x[j][s]));
            }
        }

        // 4. 序列化y变量（I*S个）：客户区选择的服务
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                solList.add(cplex.getValue(y[i][s]));
            }
        }

        // 5. 序列化vF变量（J个）：传统零售商选择变量
        for (IloIntVar vF : vF) {
            solList.add(cplex.getValue(vF));
        }

        // 6. 序列化z变量（J*K_f个）：传统零售商折扣等级选择
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                solList.add(cplex.getValue(z[j][k]));
            }
        }

        // 7. 序列化辅助变量 d[i][n]（I*N个）
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                solList.add(cplex.getValue(d[i][n]));
            }
        }

        // 8. 序列化辅助变量 phi（1个）
        solList.add(cplex.getValue(phi));

        // 9. 序列化辅助变量 t[h][j]（H*J个）
        int currentXiSize = xiSet.getSize();
        for (int h = 0; h < currentXiSize; h++) {
            for (int j = 0; j < J; j++) {
                solList.add(cplex.getValue(t[h][j]));
            }
        }

        // 10. 序列化辅助变量 tau[h]（H个，聚合形式）
        for (int h = 0; h < currentXiSize; h++) {
            solList.add(cplex.getValue(tau[h]));
        }

        // 11. 追加当前最优目标函数值（Π^t），用于逻辑切约束
        solList.add(MpObjective);

        // 转换为数组存储
        bestSolution = solList.stream().mapToDouble(Double::doubleValue).toArray();
    }


    /**
     * 获取主问题最优解（用于传递给子问题FSP）
     * @return 最优解数组（vL/vP/vF/x/y/t/z/d/phi/Π^t）
     */
    public double[] getBestSolution() {
        return bestSolution;
    }

    /**
     * 提取vL解（电商零售店选址）
     * @return vL[j]
     * @throws IloException CPLEX异常
     */
    private double[] extractVL() throws IloException {
        double[] vLValues = new double[J];
        for (int j = 0; j < J; j++) {
            double val = cplex.getValue(vL[j]);
            // 规范化负零为正零，避免后续Math.pow计算产生NaN
            vLValues[j] = (val == 0.0) ? 0.0 : val;
        }
        return vLValues;
    }

    /**
     * 提取vP解（充电站选址）
     * @return vP[j]
     * @throws IloException CPLEX异常
     */
    private double[] extractVP() throws IloException {
        double[] vPValues = new double[J];
        for (int j = 0; j < J; j++) {
            double val = cplex.getValue(vP[j]);
            vPValues[j] = (val == 0.0) ? 0.0 : val;
        }
        return vPValues;
    }

    /**
     * 提取x解（设施-服务分配）
     * @return x[j][s]
     * @throws IloException CPLEX异常
     */
    private double[][] extractX() throws IloException {
        int numServices = S - 1;
        double[][] xValues = new double[J][numServices];
        for (int j = 0; j < J; j++) {
            for (int s = 0; s < numServices; s++) {
                double val = cplex.getValue(x[j][s]);
                xValues[j][s] = (val == 0.0) ? 0.0 : val;
            }
        }
        return xValues;
    }

    /**
     * 提取y解（客户服务选择）
     * @return y[i][s]
     * @throws IloException CPLEX异常
     */
    private double[][] extractY() throws IloException {
        double[][] yValues = new double[I][S];
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                double val = cplex.getValue(y[i][s]);
                yValues[i][s] = (val == 0.0) ? 0.0 : val;
            }
        }
        return yValues;
    }
    /**
     * 提取vF解（传统零售店选址）
     * @return vF[j]
     * @throws IloException CPLEX异常
     */
    private double[] extractVF() throws IloException {
        double[] vFValues = new double[J];
        for (int j = 0; j < J; j++) {
            double val = cplex.getValue(vF[j]);
            // 规范化负零为正零，避免后续Math.pow计算产生NaN
            vFValues[j] = (val == 0.0) ? 0.0 : val;
        }
        return vFValues;
    }
    public double[][] extractZ() throws IloException {
        double[][] zValues = new double[J][K_f];
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                zValues[j][k] = cplex.getValue(z[j][k]);
            }
        }
        return zValues;
    }


    /**
     * 获取最优Gamma解（领导者完整决策）
     * @return Gamma对象 γ = (vL, vP, x, y)
     * @throws IloException CPLEX异常
     */
    public Gamma getBestGamma() throws IloException {
        double[] vLValues = extractVL();
        double[] vPValues = extractVP();
        double[][] xValues = extractX();
        double[][] yValues = extractY();

        return new Gamma(vLValues, vPValues, xValues, yValues);
    }

    /**
     * 获取主问题的psi_l解（Main.java调用的接口）
     * @return Psi_l对象，包含完整的(vF, z)追随者解
     */
    public pool.Psi getPsi_l() throws IloException {
        // 获取vF和z的解值
        double[] vF = extractVF();
        double[][] z = extractZ();

        // 创建并返回Psi对象
        return new pool.Psi(vF, z);
    }

    
    /**
     * 求解主问题模型（步骤4）
     * 注意：回调中不再求解子问题，仅收集gamma整数解。
     *       子问题在主问题求解完成后由调用方批量统一求解。
     * @return 是否成功求解
     */
    public boolean solve_Model() {
        try {
            // 清空本次迭代的临时列表
            currentIterationPsiList.clear();
            currentIterationSolutions.clear();
            currentIterationGammaList.clear();
            subProblemTime = 0.0; // 重置子问题累计时间
            System.out.println("\n>>> 清空本次迭代的临时列表，准备求解主问题");
            
            buildModel();
            cplex.use(new OACutCallback());

            // 添加CPLEX性能参数
            cplex.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, 0.01);  // 1%相对间隙
            // 不设置时间限制，让CPLEX自行求解到最优
            cplex.setParam(IloCplex.Param.Threads, 0);  // 多线程（0=自动使用所有可用核心）
            System.out.println("已设置CPLEX性能参数: MIPGap=1%, 无时间限制, Threads=Auto");

            // 记录求解开始时间
            double startTime = cplex.getCplexTime();

            // 求解RMP（主问题）
            isOptimal = cplex.solve();

            if (isOptimal) {
                // 根据开关决定是否执行验证循环
                if (enableResolvingLoop) {
                    int maxIterations = 10;
                    int iteration = 0;

                    System.out.println("\n>>> 开始OA约束验证循环（主问题）");

                    while (iteration < maxIterations) {
                        System.out.println("  [迭代" + iteration + "] 检查当前解是否违反OA约束...");
                        boolean hasViolation = checkAndAddViolatedCuts();
                        if (!hasViolation) {
                            if (iteration > 0) {
                                System.out.println("✓ 经过" + iteration + "次重新求解，所有OA约束已满足");
                            } else {
                                System.out.println("✓ 初始解满足所有OA约束");
                            }
                            break;
                        }

                        System.out.println("  [迭代" + iteration + "] ⚠ 发现OA约束违反，添加cuts并重新求解...");
                        double oldObj = cplex.getObjValue();

                        if (!cplex.solve()) {
                            System.out.println("  [迭代" + (iteration + 1) + "] ✗ 重新求解失败，状态: " + cplex.getStatus());
                            break;
                        }

                        double newObj = cplex.getObjValue();
                        System.out.println("  [迭代" + (iteration + 1) + "] ✓ 重新求解成功 (目标值: " +
                            String.format("%.2f", oldObj) + " → " + String.format("%.2f", newObj) + ")");
                        iteration++;
                    }

                    if (iteration >= maxIterations) {
                        System.out.println("⚠ 达到最大迭代次数(" + maxIterations + ")");
                    }
                    System.out.println("<<< OA约束验证循环结束\n");
                } else {
                    System.out.println("\n>>> 重新求解循环已禁用，跳过OA约束验证\n");
                }

                // 记录最优目标函数值（上界UB）
                MpObjective = cplex.getObjValue();
                // 序列化最优解（存储w/v/x/y变量值，用于后续子问题求解）
                serializeBestSolution();
                // 打印求解结果摘要
                System.out.println("\n=== 主问题求解结果 ===");             
                printSolutionStats();
                System.out.println("求解状态: " + cplex.getStatus()); // Optimal, Feasible, Infeasible 等
                System.out.printf("Obj=%.6f, phi=%.6f%n", cplex.getObjValue(), cplex.getValue(phi));
                // 输出时间
                System.out.println("求解时间: " + (cplex.getCplexTime() - startTime) + " 秒");                
            } else {
                // 求解失败时，更新上界为松弛解最优值
                MpObjective = cplex.getBestObjValue();
                
                System.out.println("=== 主问题求解结果 ===");
                System.out.println("模型未求得可行解，状态: " + cplex.getStatus());
                System.out.println("求解时间: " + (cplex.getCplexTime() - startTime) + " 秒");
                System.out.println("==================");
            }

            // 记录总求解时间（包含B&B和回调中的子问题求解）
            totalSolveTime = cplex.getCplexTime() - startTime;

            return isOptimal;
        } catch (IloException e) {
            System.err.println("主问题求解失败: " + e.getMessage());
            return false;
        }
    }


    // 无参版本已合并，主问题 solve_Model() 不再需要传入 CSP
    
    /**
     * 获取本次迭代收集到的所有psi解
     * @return psi列表
     */
    public List<pool.Psi> getCollectedPsiList() {
        return currentIterationPsiList;
    }

    /**
     * 获取全局收集到的所有gamma解（跨迭代）
     * @return gamma列表
     */
    public List<pool.Gamma> getCollectedGammaList() {
        return globalCollectedGammaList;
    }

    /**
     * 获取本次迭代收集到的所有(gamma, psi)可行解对
     * @return FeasibleSolution列表
     */
    public List<pool.FeasibleSolution> getCollectedSolutions() {
        return currentIterationSolutions;
    }

    /**
     * 获取本次迭代在B&B过程中收集的gamma列表（去重）
     * @return gamma列表
     */
    public List<pool.Gamma> getCurrentIterationGammaList() {
        return currentIterationGammaList;
    }

    /**
     * 检查主问题是否最优求解（步骤5）
     * @return 是否最优求解
     */
    public boolean isOptimalSolved() {
        return isOptimal;
    }
    
    /**
     * 获取主问题目标函数值（步骤6）
     * @return 目标函数值Π'
     */
    public double getMpObjectiveValue() {
        return MpObjective;
    }
    
    /**
     * 获取主问题解（步骤6）
     * @return 解向量x'
     */
    public double[] getSolution() {
        return bestSolution;
    }

    /**
     * 获取总求解时间（秒）
     * @return 总时间
     */
    public double getTotalSolveTime() {
        return totalSolveTime;
    }

    /**
     * 获取子问题累计求解时间（秒）
     * @return 子问题时间
     */
    public double getSubProblemTime() {
        return subProblemTime;
    }

    /**
     * 获取纯主问题求解时间（秒）= 总时间 - 子问题时间
     * @return 主问题时间
     */
    public double getMasterProblemTime() {
        return totalSolveTime - subProblemTime;
    }

    /**
     * 评估目标函数值（步骤9）
     * @param ySolution 主问题的y解
     * @param zSolution 子问题的z解
     * @return 目标函数值Π(yh, zh)
     */
    public double evaluatePi(double[][] ySolution, double[][] zSolution) {
        // 使用BCFLCalculator计算目标函数值
        // 目标函数：max Σ_i Σ_n D_in^L
        double objectiveValue = 0.0;
        
        // 计算Σ_i Σ_n D_in^L部分（领导者市场需求）
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                // 计算D_in^L（领导者市场需求）
                double D_inL = calculator.calculateD_inL(i, n, ySolution, zSolution);
                objectiveValue += D_inL;
            }
        }
        
        return objectiveValue;
    }

    /**
     * 评估追随者目标函数值（步骤9）
     * @param ySolution 主问题的y解
     * @param zSolution 主问题的z_l解
     * @return 目标函数值Π(yh, zh)
     */
    public double evaluatePhi(double[][] ySolution, double[][] zSolution) {
        // 使用BCFLCalculator计算目标函数值
        // 目标函数：max Σ_i Σ_n D_in^F
        double objectiveValue = 0.0;
        
        // 计算Σ_i Σ_n D_in^F部分（领导者市场需求）
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                // 计算D_in^L（领导者市场需求）
                double D_inF = calculator.calculateD_inF(i, n, ySolution, zSolution);
                objectiveValue += D_inF;
            }
        }
        
        return objectiveValue;
    }    

    

    /**
     * 计算 Φ(γ_max, 0)
     * 使用MaxLeaderProblem求解 γ_max = argmax_{γ∈Γ} D^L(γ, 0)
     * 然后计算追随者需求 Φ(γ_max, 0) = Σ_{i,n} D_{in}^F(γ_max, 0)
     *
     * @return Φ(γ_max, 0) 值
     */
    private double calculatePhiGammaMax() throws IloException {
        // 创建MaxLeaderProblem实例，z=0（追随者不采取任何行动）
        double[][] zeroZ = new double[data.getJ()][data.getK_f()];
        MaxLeaderProblem maxLeaderProblem = new MaxLeaderProblem(data, zeroZ);

        // 求解最大领导者需求问题
        maxLeaderProblem.solve();

        // 获取最优解 gamma_max
        pool.Gamma gammaMax = maxLeaderProblem.getBestGamma();

        // 计算 Φ(γ_max, 0) = Σ_{i,n} D_{in}^F(y_max, 0)
        double phiGammaMax = 0.0;
        if (gammaMax != null) {
            for (int i = 0; i < I; i++) {
                for (int n = 0; n < N; n++) {
                    phiGammaMax += calculator.calculateD_inF(i, n, gammaMax.y, zeroZ);
                }
            }
        }

        maxLeaderProblem.getCplex().end();
        return phiGammaMax;
    }
    /**
     * 获取主问题最佳松弛界（步骤14）
     * @return 最佳松弛界bestbd
     */
    public double getBestRelaxationBound() {
        return MpObjective; // 返回当前最优目标函数值
    }

    /**
     * 释放CPLEX资源（避免内存泄漏）
     */
    public void release() {
        if (cplex != null) {
            cplex.end();
        }
    }

    /**
     * 设置是否启用OA约束验证和重新求解循环
     * @param enable true=启用（默认），false=禁用（用于对比测试）
     */
    public void setEnableResolvingLoop(boolean enable) {
        this.enableResolvingLoop = enable;
        System.out.println("主问题：重新求解循环已" + (enable ? "启用" : "禁用"));
    }

    /**
     * 设置是否使用自适应M2_h计算
     * @param enable true=使用自适应M2_h（每个历史解h独立计算），false=使用统一M2
     */
    public void setUseAdaptiveM2(boolean enable) {
        this.useAdaptiveM2 = enable;
        System.out.println("主问题：M2计算方式=" + (enable ? "自适应M2_h" : "统一M2"));
    }
    
    /**
     * 设置是否禁用无人机服务（无无人机对比实验开关）
     * @param disable true=禁用无人机服务，false=正常模式（默认）
     */
    public void setDisableDrone(boolean disable) {
        this.disableDrone = disable;
        System.out.println("主问题：无人机服务" + (disable ? "已禁用（无无人机模式）" : "正常启用"));
    }

    /**
     * 设置是否禁用换电站（固定vP=0）
     * @param disable true=禁用换电站，false=正常模式（默认）
     */
    public void setDisableChargingStation(boolean disable) {
        this.disableChargingStation = disable;
        System.out.println("主问题：换电站" + (disable ? "已禁用（vP=0）" : "正常启用"));
    }

    /**
     * 设置实验模式
     * @param useOriginal true=原始版本模式，false=1.2版本模式（默认）
     */
    public void setUseOriginalMode(boolean useOriginal) {
        this.useOriginalMode = useOriginal;
        System.out.println("主问题：实验模式已设置为 " + (useOriginal ? "原始版本" : "1.2版本"));
    }

    /**
     * 设置聚合覆盖链接加速策略开关
     * @param fix true=启用，false=不启用
     */
    public void setFixAggCov(boolean fix) {
        this.fixAggCov = fix;
        System.out.println("主问题：聚合覆盖链接策略" + (fix ? "已启用" : "未启用"));
    }

    /**
     * 设置T求和约束加速策略开关
     * @param fix true=启用，false=不启用
     */
    public void setFixTSum(boolean fix) {
        this.fixTSum = fix;
        System.out.println("主问题：T求和约束策略" + (fix ? "已启用" : "未启用"));
    }

    /**
     * 设置换电站有效不等式加速策略开关
     * @param fix true=启用，false=不启用
     */
    public void setFixStationVI(boolean fix) {
        this.fixStationVI = fix;
        System.out.println("主问题：换电站有效不等式策略" + (fix ? "已启用" : "未启用"));
    }

    /**
     * 设置追随者变量固定加速策略开关
     * @param fix true=启用，false=不启用
     */
    public void setFixVar(boolean fix) {
        this.fixVar = fix;
        System.out.println("主问题：追随者变量固定策略" + (fix ? "已启用" : "未启用"));
    }

    // Getter方法（用于外部访问决策变量值）
    public IloIntVar[] getvL() { return vL; }
    public IloIntVar[] getvP() { return vP; }
    public IloIntVar[] getvF() { return vF; }
    public IloIntVar[][] getX() { return x; }
    public IloIntVar[][] getY() { return y; }
    public IloNumVar[][] getD() { return d; }
    public IloNumVar getPhi() { return phi; }
    public IloIntVar[][] getZ() { return z; }
    
    // Ξ集合相关方法
    /**
     * 获取Ξ集合大小
     */
    public int getXiSetSize() {
        return xiSet.getSize();
    }


    /**
     * 检查Ξ集合是否为空
     * @return 是否为空
     */
    public boolean isXiSetEmpty() {
        return xiSet.isEmpty();
    }

    /**
     * 添加ψ解到Ξ集合
     * @param psi ψ^h解（包含vF和z）
     * @return 是否成功添加
     */
    public boolean addToXiSet(pool.Psi psi) {
        return xiSet.addXiSet(psi);
    }


    /**
     * 打印主问题解统计信息
     */
    private void printSolutionStats() {
        try {
            System.out.println("=== 主问题解统计信息 ===");
            
            // 打印vL解（设施选址）
            System.out.println("vL解 (设施选址):");
            for (int j = 0; j < J; j++) { // 只显示前10个
                double val = cplex.getValue(vL[j]);
                System.out.printf("  设施 %d: %.1f ", j, val);
                if ((j + 1) % 5 == 0) System.out.println(); // 每5个换行
            }
            System.out.println();
            
            // 打印vP解（换电站选址）
            System.out.println("vP解 (换电站选址):");
            for (int j = 0; j < J; j++) { // 只显示前10个
                double val = cplex.getValue(vP[j]);
                System.out.printf("  换电站 %d: %.1f ", j, val);
                if ((j + 1) % 5 == 0) System.out.println(); // 每5个换行
            }
            System.out.println();

            // 打印x解（设施服务配置）
            System.out.println("x解 (设施服务配置):");
            for (int j = 0; j < J; j++) { // 只显示前10个设施
                System.out.printf("  设施 %d: ", j);
                for (int s = 0; s < Math.min(S-1, 3); s++) { // 只显示前3个服务类型（S-1因为s=0是传统零售）
                    double val = cplex.getValue(x[j][s]);
                    System.out.printf("x[%d][%d]=%.1f ",j, s, val);
                }
                System.out.println();
            }
            System.out.println();
            
            // 打印y解（客户区服务选择）
            System.out.println("y解 (客户区服务选择):");
            for (int i = 0; i < I; i++) { // 只显示前10个客户区
                System.out.printf("  客户区 %d: ", i);
                for (int s = 0; s < Math.min(S, 4); s++) { // 只显示前4个服务
                    double val = cplex.getValue(y[i][s]);
                    System.out.printf("y[%d][%d]=%.1f ",i, s, val);
                }
                System.out.println();
            }
            System.out.println();

            // 打印vF解（传统零售商选择）
            System.out.println("vF解 (传统零售商选择):");
            for (int j = 0; j < J; j++) {
                double val = cplex.getValue(vF[j]);
                System.out.printf("  传统零售商 %d: %.1f ", j, val);
                if ((j + 1) % 5 == 0) System.out.println();
            }
            System.out.println();

            // 打印z解（传统零售商折扣选择）
            System.out.println("z解 (传统零售商折扣选择):");
            for (int j = 0; j < J; j++) { // 只显示前10个零售商
                System.out.printf("  零售商 %d: ", j);
                for (int h = 0; h < K_f; h++) {
                    double val = cplex.getValue(z[j][h]);
                    System.out.printf("z[%d][%d]=%.1f ", j, h, val);
                }
                System.out.println();
            }
            System.out.println();
            
            // 计算并打印d和D_in的总和
            double sumD = 0.0;  // 累计所有d的总和
            double sumDin = 0.0; // 累计所有D_in的总和

            // 获取当前y和z解
            double[][] ySolution = new double[I][S];
            double[][] zSolution = new double[J][K_f];
            for (int i = 0; i < I; i++) {
                for (int s = 0; s < S; s++) {
                    ySolution[i][s] = cplex.getValue(y[i][s]);
                }
            }
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    zSolution[j][k] = cplex.getValue(z[j][k]);
                }
            }

            // 计算d、D_in、D_inL、D_inF的总和
            double sumDL = 0.0;  // Σ D_in^L
            double sumDF = 0.0;  // Σ D_in^F
            for (int i = 0; i < I; i++) {
                for (int n = 0; n < N; n++) {
                    sumD += cplex.getValue(d[i][n]);
                    double U_in = calculator.calculateU_in(i, n, ySolution, zSolution);
                    double D_in = calculator.calculateD_in(i, n, U_in);
                    sumDin += D_in;
                    sumDL += calculator.calculateD_inL(i, n, ySolution, zSolution);
                    sumDF += calculator.calculateD_inF(i, n, ySolution, zSolution);
                }
            }
            System.out.printf("sum_d   (Σd[i][n]):          %.6f\n", sumD);
            System.out.printf("sum_D_in (Σ D_in):           %.6f\n", sumDin);
            System.out.printf("差值 (sum_d - sum_D_in):     %.6f (%.4f%%)\n", sumD - sumDin, 100.0 * (sumD - sumDin) / sumDin);
            System.out.printf("sum_D_inL (Σ D_in^L):        %.6f\n", sumDL);
            System.out.printf("sum_D_inF (Σ D_in^F):        %.6f\n", sumDF);
            System.out.printf("sum_DL+DF (Σ D_in^L+D_in^F): %.6f\n", sumDL + sumDF);

            // 检查OA约束违反情况
            // System.out.println("=== OA约束违反检查 ===");
            // int dViolationCount = 0;

            // for (int i = 0; i < I; i++) {
            //     for (int n = 0; n < N; n++) {
            //         double d_val = cplex.getValue(d[i][n]);
            //         double U_in = calculator.calculateU_in(i, n, ySolution, zSolution);
            //         double D_in = calculator.calculateD_in(i, n, U_in);

            //         // 检查是否违反: d[i][n] > D_in * (1.0 + 1e-4)
            //         double threshold = D_in * (1.0 + 1e-4);
            //         if (d_val > threshold) {
            //             double violation = (d_val - D_in)/D_in;
            //             dViolationCount++;
                        
            //             if (dViolationCount <= 5) { // 只打印前5个违反
            //                 System.out.printf("  违反[%d]: d[%d][%d]=%.6f > D_in[%d][%d]=%.6f (差值(比例)=%.6f)\n",
            //                                 dViolationCount, i, n, d_val, i, n, D_in, violation);
            //             }
            //         }
            //     }
            // }

            // if (dViolationCount > 0) {
            //     System.out.printf("总共 %d 个d约束违反！\n", dViolationCount);
            // } else {
            //     System.out.println("所有d约束都满足！");
            // }

            // 检查tau约束违反情况（聚合形式: tau[h] ≥ Φ(y,z^h)）
            // int tauViolationCount = 0;
            // int currentXiSize = xiSet.getSize();

            // for (int h = 0; h < currentXiSize; h++) {
            //     double[][] zh = xiSet.getZh(h);
            //     double tauHValue = cplex.getValue(tau[h]);

            //     // 计算 Φ(y, z^h) = Σ_{i,n} D_inF(y, z^h)
            //     double phiH = 0.0;
            //     for (int i = 0; i < I; i++) {
            //         for (int n = 0; n < N; n++) {
            //             phiH += calculator.calculateD_inF(i, n, ySolution, zh);
            //         }
            //     }

            //     double threshold = phiH ;
            //     if (tauHValue * (1.0 + 1e-4)< threshold) {
            //         double violation = (phiH - tauHValue) / phiH;
            //         tauViolationCount++;

            //         if (tauViolationCount <= 5) {
            //             System.out.printf("  违反[%d]: tau[%d]=%.6f < Φ(y,z^%d)=%.6f (差值(比例)=%.6f)\n",
            //                             tauViolationCount, h, tauHValue, h, phiH, violation);
            //         }
            //     }
            // }

            // if (tauViolationCount > 0) {
            //     System.out.printf("总共 %d 个tau约束违反！\n", tauViolationCount);
            // } else {
            //     System.out.println("所有tau约束都满足！");
            // }
            // System.out.println();

            // 打印phi值（追随者市场份额）
            double phiValue = cplex.getValue(phi);
            System.out.printf("phi (追随者市场份额): %.6f\n", phiValue);
            System.out.println();
            
            // 打印集合信息
            System.out.println("集合信息:");
            System.out.printf("  Ξ集合大小: %d\n", getXiSetSize());
            System.out.printf("  可行解集合F大小: %d\n", feasibleSet.size());
            System.out.printf("  整数解集合IntYZSet大小: %d\n", IntYZSet.size());
            
            System.out.println("==================");
        } catch (IloException e) {
            System.err.println("打印解统计信息时出错: " + e.getMessage());
        }
    }
    
    /**
     * 获取A集合（可行解集合）大小
     * @return A集合大小
     */
    public int getFeasibleSetSize() {
        return feasibleSet.size();
    }
    
    /**
     * 重置IntYZSet集合（每次迭代开始前调用）
     * IntYZSet存储的是分支过程中产生的整数解，用于添加OA切割
     * 每次迭代都需要重新构建模型并重新添加OA切割，所以需要清空IntYZSet
     */
    public void resetIntYZSet() {
        IntYZSet.clear();
        System.out.println("已重置IntYZSet集合");
    }

    /**
     * 更新A集合（可行解集合）
     * 在每次迭代前调用，将Main.java中solpool管理的A集合同步到MP中
     * @param newFeasibleSet 来自solpool的可行解集合
     */
    public void setFeasibleSet(List<FeasibleSolution> newFeasibleSet) {
        this.feasibleSet = newFeasibleSet;
        System.out.println("已更新主问题的A集合，当前大小: " + feasibleSet.size());
    }

    /**
     * 检查当前解是否违反OA约束，如有违反则直接添加cuts
     * @return true如果有违反，false如果所有约束都满足
     */
    private boolean checkAndAddViolatedCuts() throws IloException {
        // 提取当前解
        double[][] yCurrent = extractY();
        double[][] zCurrent = new double[J][K_f];
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                zCurrent[j][k] = cplex.getValue(z[j][k]);
            }
        }

        boolean hasViolation = false;

        // 1. 检查d[i][n]约束: d[i][n] ≤ D_in(y,z)
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                double d_val = cplex.getValue(d[i][n]);

                // 使用批量计算方法
                MPDinGradientResult result = calculator.calculateD_in_and_AllGradients(i, n, yCurrent, zCurrent);
                double D_in = result.D_in;

                // 检查是否违反: d[i][n] > D_in * (1.0 + 1e-4)
                if (d_val > D_in * (1.0 + 1e-4)) {
                    // 直接添加OA cut到模型（使用批量梯度）
                    addDinOACutDirectBatch(i, n, yCurrent, zCurrent, result);
                    // 添加次模切割
                    addDinSubmodularCutDirect(i, n, yCurrent, zCurrent);
                    hasViolation = true;
                }
            }
        }

        // 2. 检查tau[h]约束: tau[h] ≥ Φ(y,z^h), ∀h
        int currentXiSize = xiSet.getSize();
        for (int h = 0; h < currentXiSize; h++) {
            double tauHValue = cplex.getValue(tau[h]);
            double[][] zh = xiSet.getZh(h);

            // 计算 Φ(y, z^h) = Σ_{i,n} D^F_{in}(y, z^h)
            double phiH = 0.0;
            for (int i = 0; i < I; i++) {
                for (int n = 0; n < N; n++) {
                    phiH += calculator.calculateD_inF(i, n, yCurrent, zh);
                }
            }

            // 检查违反条件: τ_h * (1 + 1e-4) < Φ(y, z^h)
            if (tauHValue * (1.0 + 1e-4) < phiH) {
                addTauOACutDirectForH(yCurrent, h, zh, phiH);
                addTauSubmodularCutDirectForH(yCurrent, h, zh);
                hasViolation = true;
            }
        }

        return hasViolation;
    }

    /**
     * 直接添加d约束的OA切割到模型（优化版：使用批量梯度计算）
     */
    private void addDinOACutDirectBatch(int i, int n, double[][] yRef, double[][] zRef, MPDinGradientResult result) throws IloException {
        IloLinearNumExpr cut = cplex.linearNumExpr();
        cut.addTerm(1.0, d[i][n]);
        double constant = result.D_in;

        // 使用预计算的y梯度
        for (int s = 0; s < S; s++) {
            cut.addTerm(-result.gradients_y[s], y[i][s]);
            constant -= result.gradients_y[s] * yRef[i][s];
        }

        // 使用预计算的z梯度
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                cut.addTerm(-result.gradients_z[j][k], z[j][k]);
                constant -= result.gradients_z[j][k] * zRef[j][k];
            }
        }

        String constraintName = "DirectOACut_d_" + i + "_" + n + "_" + System.currentTimeMillis();
        cplex.addLe(cut, constant, constraintName);
    }

    /**
     * 直接添加d_in的次模切割约束到模型（Submodular-cut）
     */
    private void addDinSubmodularCutDirect(int i, int n, double[][] yBar, double[][] zBar) throws IloException {
        IloLinearNumExpr cut = cplex.linearNumExpr();
        cut.addTerm(1.0, d[i][n]);
        double U_in = calculator.calculateU_in(i, n, yBar, zBar);
        double D_in = calculator.calculateD_in(i, n, U_in);
        double constant = D_in;

        // M^1(yBar)
        for (int s = 0; s < S; s++) {
            if (yBar[i][s] > 0.5) {
                double[][] yMinus = cloneMatrix(yBar);
                yMinus[i][s] = 0.0;
                double UMinus = calculator.calculateU_in(i, n, yMinus, zBar);
                double DMinus = calculator.calculateD_in(i, n, UMinus);
                double rhoMinus = D_in - DMinus;
                constant -= rhoMinus;
                cut.addTerm(-rhoMinus, y[i][s]);
            }
        }

        // M^0(yBar)
        for (int s = 0; s < S; s++) {
            if (yBar[i][s] < 0.5) {
                double[][] yPlus = cloneMatrix(yBar);
                yPlus[i][s] = 1.0;
                double UPlus = calculator.calculateU_in(i, n, yPlus, zBar);
                double DPlus = calculator.calculateD_in(i, n, UPlus);
                double rhoPlus = DPlus - D_in;
                cut.addTerm(-rhoPlus, y[i][s]);
            }
        }

        // M^1(zBar)
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                if (zBar[j][k] > 0.5) {
                    double[][] zMinus = cloneMatrix(zBar);
                    zMinus[j][k] = 0.0;
                    double UMinus = calculator.calculateU_in(i, n, yBar, zMinus);
                    double DMinus = calculator.calculateD_in(i, n, UMinus);
                    double rhoMinus = D_in - DMinus;
                    constant -= rhoMinus;
                    cut.addTerm(-rhoMinus, z[j][k]);
                }
            }
        }

        // M^0(zBar)
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                if (zBar[j][k] < 0.5) {
                    double[][] zPlus = cloneMatrix(zBar);
                    zPlus[j][k] = 1.0;
                    double UPlus = calculator.calculateU_in(i, n, yBar, zPlus);
                    double DPlus = calculator.calculateD_in(i, n, UPlus);
                    double rhoPlus = DPlus - D_in;
                    cut.addTerm(-rhoPlus, z[j][k]);
                }
            }
        }

        String constraintName = "DirectSubmodularCut_d_" + i + "_" + n + "_" + System.currentTimeMillis();
        cplex.addLe(cut, constant, constraintName);
    }

    /**
     * 直接添加聚合tau OA切割到模型（带δ平移）
     * 公式: τ_h ≥ Φ(ȳ_ref, z^h) + Σ_{i,n,s} ∂D^F_{in}(ȳ_ref, z^h)/∂y_{is} × (y_{is} - ȳ_ref_{is})
     */
    private void addTauOACutDirectForH(double[][] yBar, int h, double[][] zh, double phiH) throws IloException {
        IloLinearNumExpr cut = cplex.linearNumExpr();
        cut.addTerm(1.0, tau[h]);
        double constant = phiH;

        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                double leaderSum = calculator.calculateLeaderSum(i, n, yBar);
                double[][] yRef = yBar;
                if (leaderSum < LEADER_SUM_EPS) {
                    yRef = buildShiftedY(yBar, i, DELTA_Y_SHIFT);
                }
                MPTauGradientResult result = calculator.calculateD_inF_and_AllYGradients(i, n, yRef, zh);
                for (int s = 0; s < S; s++) {
                    double gradient = result.gradients_y[s];
                    cut.addTerm(-gradient, y[i][s]);
                    constant -= gradient * yRef[i][s];
                }
            }
        }

        String constraintName = "DirectOACut_tau_h" + h + "_" + System.currentTimeMillis();
        cplex.addGe(cut, constant, constraintName);
    }

    /**
     * 计算聚合追随者需求：Φ(y, z^h) = Σ_{i,n} D^F_{in}(y, z^h)
     */
    private double calculateAggregatedPhiDirect(double[][] yValue, double[][] zh) {
        double phi = 0.0;
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                phi += calculator.calculateD_inF(i, n, yValue, zh);
            }
        }
        return phi;
    }

    /**
     * 直接添加tau_h的次模切割约束（Submodular-cut）
     */
    private void addTauSubmodularCutDirectForH(double[][] yBar, int h, double[][] zh) throws IloException {
        IloLinearNumExpr cut = cplex.linearNumExpr();
        cut.addTerm(1.0, tau[h]);

        double phiBar = calculateAggregatedPhiDirect(yBar, zh);
        double constant = phiBar;

        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                if (yBar[i][s] > 0.5) {
                    double[][] yMinus = cloneMatrix(yBar);
                    yMinus[i][s] = 0.0;
                    double phiMinus = calculateAggregatedPhiDirect(yMinus, zh);
                    double rhoMinus = phiBar - phiMinus;
                    constant -= rhoMinus;
                    cut.addTerm(-rhoMinus, y[i][s]);
                } else {
                    double[][] yPlus = cloneMatrix(yBar);
                    yPlus[i][s] = 1.0;
                    double phiPlus = calculateAggregatedPhiDirect(yPlus, zh);
                    double rhoPlus = phiPlus - phiBar;
                    cut.addTerm(-rhoPlus, y[i][s]);
                }
            }
        }

        String constraintName = "DirectSubmodularCut_tau_h" + h + "_" + System.currentTimeMillis();
        cplex.addGe(cut, constant, constraintName);
    }
}
