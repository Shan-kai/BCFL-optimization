package run;

import ilog.concert.IloException;
import input.Data;
import input.Input;
import sp.BCFLSubProblem;
import pool.Gamma;
import pool.Psi;
import pool.FeasibleSolution;
import utils.BCFLCalculator;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;

/**
 * 全枚举验证器：枚举所有可行的上层解 gamma=(vL,vP,x,y)，对每个gamma求解子问题获取悲观解对，
 * 遍历所有解对找出Pi最大的最优解，与现有算法结果对比验证正确性。
 *
 * 枚举顺序：vL → x → vP → 推导y
 * - vL[j]: 候选点j是否建电商零售店
 * - x[j][s]: 建店后的服务配置（至少一种服务）
 * - vP[j]: 候选点j是否建换电站（依赖x[j'][S-2]的覆盖范围）
 * - y[i][s]: 由(vL,vP,x)通过覆盖关系直接推导
 */
public class EnumerationVerifier {

    // ========== 数据维度 ==========
    private static int J, I, S, N, K_f;
    private static Data data;
    private static Data.Parameters params;
    private static double B_l, B_f;

    // ========== 服务索引 ==========
    private static int droneNoChargeS;  // S-2: 不经过换电站的无人机服务
    private static int droneWithChargeS; // S-1: 经过换电站的无人机服务

    // ========== 覆盖矩阵 ==========
    // r_ijs[i][j][s]: 直接从Data获取
    // r_jjPrime[j][j']: 设施j'到换电站候选点j的距离 <= droneCs（S-1续航）
    private static double[][] r_jjPrime;
    // r_iCharge[i][j]: 客户i到换电站j的距离 <= droneRs（S-2续航）
    private static double[][] r_iCharge;

    // ========== 枚举状态（回溯用） ==========
    private static double[] vL, vP;
    private static double[][] x, y;
    private static double costF, costL, costC;

    // ========== 枚举统计 ==========
    private static long countVLPVX = 0;    // 可行的(vL,vP,x)组合数
    private static long countGamma = 0;    // 有效Gamma数（y至少一个为1）
    private static long countInfeasibleSP = 0;  // 子问题不可行
    private static long countNaN = 0;      // Pi/Phi含NaN
    private static long countEvaluated = 0; // 实际评估数
    private static long lastProgressTime = 0;
    private static final long PROGRESS_INTERVAL_MS = 50000; // 每5秒打印进度
    private static final long MAX_RUNTIME_MS = 10800_000; // 运行时间上限10800秒(3小时)
    private static long enumStartTimeMs = 0;
    private static boolean timedOut = false;

    // ========== 最优解跟踪 ==========
    private static double bestEnumPi = -Double.MAX_VALUE;
    private static double bestEnumPhi = 0;
    private static Gamma bestEnumGamma = null;
    private static Psi bestEnumPsi = null;

    // ========== 去重 ==========
    private static HashSet<Gamma> evaluatedGammas = new HashSet<>();

    // ========== 子问题求解器 ==========
    private static BCFLSubProblem subproblem;
    private static BCFLCalculator calculator;

    // ========== 输出控制 ==========
    private static PrintStream terminalOut;
    private static PrintStream terminalErr;
    private static PrintStream fileOutStream;
    private static PrintStream nullOut;

    public static void main(String[] args) {
        terminalOut = System.out;
        terminalErr = System.err;
        nullOut = new PrintStream(new OutputStream() {
            public void write(int b) {}
        });

        try {
            // 加载数据
            String dataFile = "data.txt";
            if (args.length > 0) {
                dataFile = args[0];
            }
            Input input = new Input(dataFile);
            data = input.getData();
            J = data.getJ();
            I = data.getI();
            S = data.getS();
            N = data.getN();
            K_f = data.getK_f();
            params = data.getParams();
            B_l = params.B_l;
            B_f = params.B_f;

            // 设置输出重定向到文件
            String dataFileBase = new File(dataFile).getName().replaceAll("\\.txt$", "");
            SimpleDateFormat sdf = new SimpleDateFormat("MMdd_HHmmss");
            String timestamp = sdf.format(new Date());
            String outputFileName = timestamp + "-" + dataFileBase + "-enumoutput.txt";

            File outputDir = new File("output全枚举结果");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            fileOutStream = new PrintStream(new FileOutputStream(new File(outputDir, outputFileName)));
            System.setOut(fileOutStream);
            System.setErr(fileOutStream);

            System.out.println("=============================================");
            System.out.println("  全枚举验证器");
            System.out.println("=============================================");
            System.out.println("运行时间: " + new Date());
            System.out.println("数据文件: " + dataFile);
            System.out.println("输出文件: output全枚举结果/" + outputFileName);
            System.out.println("维度: J=" + J + ", I=" + I + ", S=" + S + ", K_f=" + K_f + ", N=" + N);
            System.out.println("预算: B_l=" + B_l + ", B_f=" + B_f);

            droneNoChargeS = S - 2;   // 不经过换电站的无人机
            droneWithChargeS = S - 1; // 经过换电站的无人机

            // 检查枚举规模
            long estimatedVLCombos = 1L << J;
            System.out.println("\n规模预估:");
            System.out.println("  vL组合数(上界): 2^" + J + " = " + estimatedVLCombos);
            System.out.println("  (实际受预算和约束限制)");
            if (J > 10 || I > 20) {
                System.out.println("  *** 警告：J=" + J + " 或 I=" + I + " 较大，枚举可能非常耗时 ***");
                System.out.println("  *** 建议使用小规模测试实例 (J<=5, I<=5) ***");
            }
            System.out.println("=============================================");

            // 预计算覆盖矩阵
            precomputeCoverageMatrices();

            // 初始化求解器
            calculator = new BCFLCalculator(data);
            subproblem = new BCFLSubProblem(data);

            // 初始化枚举状态数组
            vL = new double[J];
            vP = new double[J];
            x = new double[J][S - 1];
            y = new double[I][S];

            // 开始计时
            enumStartTimeMs = System.currentTimeMillis();
            lastProgressTime = enumStartTimeMs;

            System.out.println("\n[枚举开始] " + new Date());
            System.out.println("运行时间上限: " + (MAX_RUNTIME_MS / 1000) + " 秒");

            // 开始枚举
            enumerateVL(0);

            long enumEndTime = System.currentTimeMillis();
            double elapsedSeconds = (enumEndTime - enumStartTimeMs) / 1000.0;

            // 输出结果
            printSummary(elapsedSeconds);

            // 输出数据信息
            printDataInfo(elapsedSeconds);

            // 清理
            subproblem = null;
            calculator = null;

            // 恢复终端输出
            System.setOut(terminalOut);
            System.setErr(terminalErr);
            System.out.println("全枚举完成，输出已保存到: output全枚举结果/" + outputFileName);

        } catch (Exception e) {
            System.setOut(terminalOut);
            System.setErr(terminalErr);
            System.err.println("枚举器运行错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ================================================================
    // 覆盖矩阵预计算
    // ================================================================

    private static void precomputeCoverageMatrices() {
        // 确保Data中的r_ijs已计算
        data.calculater_ijs();

        // r_jjPrime[j][j']: 设施j'到候选点j的距离 <= droneCs (S-1服务续航)
        double droneCs = data.getServices().get(droneWithChargeS).getR_s();
        r_jjPrime = new double[J][J];
        for (int j = 0; j < J; j++) {
            Data.JNodes jNode = data.getJNodes().get(j);
            for (int jPrime = 0; jPrime < J; jPrime++) {
                if (j == jPrime) {
                    r_jjPrime[j][jPrime] = 0;
                    continue;
                }
                Data.JNodes jPrimeNode = data.getJNodes().get(jPrime);
                double distKm = euclideanDistance(
                    jNode.getX(), jNode.getY(),
                    jPrimeNode.getX(), jPrimeNode.getY()
                ) / 1000.0;
                r_jjPrime[j][jPrime] = (distKm <= droneCs + 1e-9) ? 1.0 : 0.0;
            }
        }

        // r_iCharge[i][j]: 客户i到换电站j的距离 <= droneRs (S-2服务续航)
        // 使用 getServiceCoverage(i, j, droneNoChargeS) —— 即无人机服务对客户的覆盖
        r_iCharge = new double[I][J];
        for (int i = 0; i < I; i++) {
            for (int j = 0; j < J; j++) {
                r_iCharge[i][j] = data.getServiceCoverage(i, j, droneNoChargeS);
            }
        }

        System.out.println("覆盖矩阵预计算完成: r_jjPrime[" + J + "][" + J + "], r_iCharge[" + I + "][" + J + "]");
    }

    // ================================================================
    // 枚举：第1步 — 枚举vL
    // ================================================================

    private static void enumerateVL(int j) {
        if (timedOut) return;
        if (j == J) {
            // 所有设施的vL已确定，进入x枚举
            enumerateX(0, 0);
            return;
        }

        // 选项0：不建零售店
        vL[j] = 0;
        enumerateVL(j + 1);

        // 选项1：建零售店（需要预算检查）
        double L_j = data.getJNodes().get(j).getL_J();
        if (costL + L_j <= B_l + 1e-9) {
            vL[j] = 1;
            costL += L_j;
            enumerateVL(j + 1);
            costL -= L_j; // 回溯
        }
    }

    // ================================================================
    // 枚举：第2步 — 枚举x（对每个vL[j]=1的设施）
    // ================================================================

    private static void enumerateX(int j, int s) {
        if (j == J) {
            // 所有设施的vL和x已确定，进入vP枚举
            enumerateVP(0);
            return;
        }

        if (vL[j] == 0) {
            // 该设施不建零售店，x[j][*]全为0
            for (int ss = 0; ss < S - 1; ss++) {
                x[j][ss] = 0;
            }
            enumerateX(j + 1, 0);
            return;
        }

        // vL[j] == 1：需要枚举x[j][0..S-2]（至少一个为1）
        if (s == S - 1) {
            // 当前设施的所有服务已分配完
            // 检查至少有一个服务被选中
            boolean hasService = false;
            for (int ss = 0; ss < S - 1; ss++) {
                if (x[j][ss] > 0.5) {
                    hasService = true;
                    break;
                }
            }
            if (hasService) {
                enumerateX(j + 1, 0);
            }
            // 如果没有任何服务，视为无效，跳过
            return;
        }

        // s = 当前要决定的服务
        // 选项0：不提供该服务
        x[j][s] = 0;
        enumerateX(j, s + 1);

        // 选项1：提供该服务（需预算检查）
        double F_s = data.getServices().get(s).getFs();
        if (costF + F_s <= B_l + 1e-9 - costL - costC) {
            x[j][s] = 1;
            costF += F_s;
            enumerateX(j, s + 1);
            costF -= F_s; // 回溯
        }
    }

    // ================================================================
    // 枚举：第3步 — 枚举vP（依赖x已确定）
    // ================================================================

    private static void enumerateVP(int j) {
        if (j == J) {
            // 所有vP已确定，检查跨设施约束
            if (checkChargingStationDependency()) {
                // 推导y
                if (deriveY()) {
                    countVLPVX++;
                    // 构建Gamma并评估
                    evaluateCurrentGamma();
                }
            }
            return;
        }

        // 设施互斥：vL[j]=1的位置不能建换电站
        if (vL[j] > 0.5) {
            vP[j] = 0;
            enumerateVP(j + 1);
            return;
        }

        // 选项0：不建换电站
        vP[j] = 0;
        enumerateVP(j + 1);

        // 选项1：建换电站（需预算检查 + 覆盖率检查）
        // 覆盖率检查：需要至少一个其他设施j'在覆盖范围内提供了无人机服务
        boolean canBuild = false;
        for (int jPrime = 0; jPrime < J; jPrime++) {
            if (jPrime != j && r_jjPrime[j][jPrime] > 0.5 && x[jPrime][droneNoChargeS] > 0.5) {
                canBuild = true;
                break;
            }
        }

        if (canBuild) {
            double C_j = data.getJNodes().get(j).getC_J();
            if (costL + costF + costC + C_j <= B_l + 1e-9) {
                vP[j] = 1;
                costC += C_j;
                enumerateVP(j + 1);
                costC -= C_j; // 回溯
            }
        }
    }

    // ================================================================
    // 约束检查：换电站依赖约束
    // ================================================================

    /**
     * 检查充电站依赖约束：每个vP[j]=1的候选点j，
     * 必须存在j' != j使得 r_jjPrime[j][j']=1 且 x[j'][droneNoChargeS]=1
     */
    private static boolean checkChargingStationDependency() {
        for (int j = 0; j < J; j++) {
            if (vP[j] > 0.5) {
                boolean covered = false;
                for (int jPrime = 0; jPrime < J; jPrime++) {
                    if (jPrime != j && r_jjPrime[j][jPrime] > 0.5
                        && x[jPrime][droneNoChargeS] > 0.5) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    return false;
                }
            }
        }
        return true;
    }

    // ================================================================
    // y推导：由(vL, vP, x)确定y
    // ================================================================

    /**
     * 由(vL,vP,x)确定性推导y：
     * 1. s=0..S-2: y[i][s] = 1 当存在j覆盖该客户并提供服务s
     * 2. 无人机服务：优先S-2（不经过换电站），仅当S-2不可用时才设S-1
     * 3. 检查sum(y) >= 1
     * @return true如果推导出的y有效
     */
    private static boolean deriveY() {
        // 清零y
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                y[i][s] = 0;
            }
        }

        boolean anyY = false;

        for (int i = 0; i < I; i++) {
            // s=0..S-2（不含无人机服务）: 服务可用则设为1
            for (int s = 0; s <= droneNoChargeS - 1; s++) {
                boolean available = false;
                for (int j = 0; j < J; j++) {
                    if (x[j][s] > 0.5 && data.getServiceCoverage(i, j, s) > 0.5) {
                        available = true;
                        break;
                    }
                }
                if (available) {
                    y[i][s] = 1;
                    anyY = true;
                }
            }

            // s=S-2: 不经过换电站的无人机服务
            boolean droneNoChargeAvail = false;
            for (int j = 0; j < J; j++) {
                if (x[j][droneNoChargeS] > 0.5
                    && data.getServiceCoverage(i, j, droneNoChargeS) > 0.5) {
                    droneNoChargeAvail = true;
                    break;
                }
            }

            // s=S-1: 经过换电站的无人机服务
            boolean droneWithChargeAvail = false;
            for (int j = 0; j < J; j++) {
                if (vP[j] > 0.5 && r_iCharge[i][j] > 0.5) {
                    droneWithChargeAvail = true;
                    break;
                }
            }

            // 优先不经过换电站的无人机（S-2），其次经过换电站的（S-1）
            if (droneNoChargeAvail) {
                y[i][droneNoChargeS] = 1;
                anyY = true;
            } else if (droneWithChargeAvail) {
                y[i][droneWithChargeS] = 1;
                anyY = true;
            }
        }

        return anyY; // 至少有一个y为1
    }

    // ================================================================
    // Gamma评估：构建Gamma，求解子问题，计算Pi
    // ================================================================

    private static void evaluateCurrentGamma() {
        // 超时检查
        if (System.currentTimeMillis() - enumStartTimeMs >= MAX_RUNTIME_MS) {
            if (!timedOut) {
                timedOut = true;
                System.out.println("\n*** 达到运行时间上限3600秒，终止枚举 ***");
            }
            return;
        }

        countGamma++;

        // 构建Gamma对象
        Gamma gamma = new Gamma(vL, vP, x, y);

        // 去重检查
        if (evaluatedGammas.contains(gamma)) {
            return;
        }
        evaluatedGammas.add(gamma);

        countEvaluated++;

        // 进度打印
        long now = System.currentTimeMillis();
        if (now - lastProgressTime >= PROGRESS_INTERVAL_MS) {
            lastProgressTime = now;
            long elapsedMs = now - enumStartTimeMs;
            System.out.printf("  进度: 已评估 %d 个Gamma, 有效(vL,vP,x)=%d, 当前最优Pi=%.6f, 已用时%.1fs\n",
                countEvaluated, countVLPVX, bestEnumPi, elapsedMs / 1000.0);
        }

        // 抑制子问题求解期间的System.out输出（BCFLSubProblem打印大量日志）
        System.setOut(nullOut);
        System.setErr(nullOut);
        try {
            // 使用悲观解求解方法
            subproblem.solve_Model_Smart(gamma);
            Psi psi = subproblem.getPsi();

            // 恢复到文件输出
            System.setOut(fileOutStream);
            System.setErr(fileOutStream);

            // 计算Pi（直接使用FeasibleSolution）
            FeasibleSolution sol = new FeasibleSolution(gamma, psi, calculator);

            if (Double.isNaN(sol.PiH) || Double.isNaN(sol.PhiH)) {
                countNaN++;
                return;
            }

            // 更新最优解
            if (sol.PiH > bestEnumPi) {
                bestEnumPi = sol.PiH;
                bestEnumPhi = sol.PhiH;
                bestEnumGamma = gamma;
                bestEnumPsi = psi;
                System.out.printf("  >>> 更新最优解: Pi=%.6f, Phi=%.6f (已评估%d个)\n",
                    bestEnumPi, bestEnumPhi, countEvaluated);
            }

        } catch (IloException e) {
            // 子问题不可行或求解失败
            countInfeasibleSP++;
            System.setOut(fileOutStream);
            System.setErr(fileOutStream);
        } catch (Exception e) {
            countInfeasibleSP++;
            System.setOut(fileOutStream);
            System.setErr(fileOutStream);
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private static double euclideanDistance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ================================================================
    // 结果输出
    // ================================================================

    private static void printSummary(double elapsedSeconds) {
        System.out.println("\n=============================================");
        if (timedOut) {
            System.out.println("  [枚举超时终止] " + new Date());
        } else {
            System.out.println("  [枚举完成] " + new Date());
        }
        System.out.println("=============================================");
        System.out.println("统计信息:");
        System.out.println("  可行(vL,vP,x)组合数: " + countVLPVX);
        System.out.println("  有效Gamma数: " + countGamma);
        System.out.println("  去重后实际评估数: " + countEvaluated);
        System.out.println("  子问题不可行/异常: " + countInfeasibleSP);
        System.out.println("  NaN跳过: " + countNaN);
        System.out.printf("  枚举耗时: %.3f 秒 (%.2f 分钟)\n", elapsedSeconds, elapsedSeconds / 60.0);
        if (countEvaluated > 0) {
            System.out.printf("  平均每个Gamma: %.3f 秒\n", elapsedSeconds / countEvaluated);
        }

        // 最优解
        if (bestEnumGamma != null && bestEnumPsi != null) {
            System.out.println("\n=== 枚举最优解 ===");
            System.out.printf("  Pi* (领导者市场份额) = %.6f\n", bestEnumPi);
            System.out.printf("  Phi* (追随者市场份额) = %.6f\n", bestEnumPhi);
            printGamma(bestEnumGamma);
            printPsi(bestEnumPsi);
        } else {
            System.out.println("\n  *** 未找到任何可行解 ***");
        }

        System.out.println("=============================================");
    }

    private static void printGamma(Gamma gamma) {
        System.out.println("\n  Gamma (vL,vP,x,y):");

        // vL
        StringBuilder vLStr = new StringBuilder("  vL=[");
        for (int j = 0; j < J; j++) {
            vLStr.append((int) gamma.vL[j]);
            if (j < J - 1) vLStr.append(",");
        }
        vLStr.append("]");
        System.out.println(vLStr);

        // vP
        StringBuilder vPStr = new StringBuilder("  vP=[");
        for (int j = 0; j < J; j++) {
            vPStr.append((int) gamma.vP[j]);
            if (j < J - 1) vPStr.append(",");
        }
        vPStr.append("]");
        System.out.println(vPStr);

        // x (只打印非零)
        System.out.println("  x (非零项):");
        for (int j = 0; j < J; j++) {
            boolean hasX = false;
            for (int s = 0; s < S - 1; s++) {
                if (gamma.x[j][s] > 0.5) {
                    if (!hasX) {
                        System.out.printf("    设施%d: ", j);
                        hasX = true;
                    }
                    System.out.printf("s%d=1 ", s);
                }
            }
            if (hasX) System.out.println();
        }

        // y (只打印非零项，摘要)
        int yCount = 0;
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                if (gamma.y[i][s] > 0.5) yCount++;
            }
        }
        System.out.printf("  y: %d个非零项 (共%d个客户区×%d种服务)\n", yCount, I, S);
    }

    private static void printPsi(Psi psi) {
        System.out.println("\n  Psi (vF,z):");

        // vF
        StringBuilder vFStr = new StringBuilder("  vF=[");
        for (int j = 0; j < J; j++) {
            vFStr.append((int) psi.vF[j]);
            if (j < J - 1) vFStr.append(",");
        }
        vFStr.append("]");
        System.out.println(vFStr);

        // z (只打印非零)
        System.out.println("  z (非零项):");
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                if (psi.z[j][k] > 0.5) {
                    System.out.printf("    零售商%d: k%d=1\n", j, k);
                }
            }
        }
    }

    // ================================================================
    // 数据信息输出
    // ================================================================

    private static void printDataInfo(double elapsedSeconds) {
        int droneChargeS = S - 1;
        Data.Service droneCharges = data.getServices().get(droneChargeS);
        double droneCs = droneCharges.getR_s();

        System.out.println("\n=== 数据信息 ===");
        System.out.println("集合大小信息:");
        System.out.println("  J (上层候选点数量): " + J);
        System.out.println("  J_f (下层候选点数量): " + J);
        System.out.println("  I (客户区数量): " + I);
        System.out.println("  K_f (折扣等级数量): " + K_f);
        System.out.println("  S (服务类型数量): " + S);
        System.out.println("  N (包裹类型数量): " + N);
        System.out.println("droneCs:" + droneCs);

        System.out.println("\n参数信息:");
        System.out.println("  B_l = " + B_l);
        System.out.println("  B_f = " + B_f);
        System.out.println("  beta_0 = " + params.beta_0);
        System.out.println("  beta_tt = " + params.beta_tt);
        System.out.println("  beta_tc = " + params.beta_tc);
        System.out.println("  beta_dt = " + params.beta_dt);
        System.out.println("  beta_dc = " + params.beta_dc);
        System.out.println("  lambda_L = " + params.lambda_l);
        System.out.println("  lambda_F = " + params.lambda_f);
        System.out.println("  lambda = " + params.lambda);

        System.out.println("\n服务参数:");
        System.out.println("  s\tbeta_0s\tDTs\tQ_s\tr_s(km)\ta_sn0\ta_sn1\tF_s");
        for (int si = 0; si < S; si++) {
            Data.Service sv = data.getServices().get(si);
            System.out.printf("  %d\t%.2f\t%.1f\t%.2f\t%d\t%d\t%d\t%.0f\n",
                sv.getS(), sv.getBeta_0s(), sv.getDTs(), sv.getQ_s(),
                (int)sv.getR_s(), sv.getA_sn0(), sv.getA_sn1(), sv.getFs());
        }

        // 求解时间
        System.out.printf("\n求解时间: %.3f 秒 (%.2f 分钟)\n", elapsedSeconds, elapsedSeconds / 60.0);

        System.out.println("======================");
    }
}
