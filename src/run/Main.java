package run;
import ilog.concert.IloException;
import input.Data;
import input.Data.Service;
import input.Input;
import result.Result;
import mp.BCFLMasterProblem;
import mp.MaxLeaderProblem;
import sp.*;
import pool.FeasibleSolution;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
/**
 * 类：
 * Data: 数据类，记录BCFL-NLM问题相关参数
 * BCFLMasterProblem：BCFL-NLM算法主问题类，包含主问题MP(Ξ,F)的构建和求解
 * BCFLSubProblem：BCFL-NLM算法子问题类，包含割平面子问题CSP(x')的构建和求解
 * Result：打印BCFL-NLM算法迭代过程
 */

public class Main {
    // ========== 算法配置参数 ==========

    // ========== 调试配置 ==========

    /** 是否打印A集合中每个可行解的 Pi 和 Phi（调试用） */
    private static final boolean DEBUG_PRINT_FEASIBLE_SET = true;
    
    /** 无无人机对比实验开关：true=禁用无人机服务，false=正常模式（默认）
     *  可通过 -Ddisable.drone=true 启动参数控制 */
    private static final boolean DISABLE_DRONE = Boolean.parseBoolean(System.getProperty("disable.drone", "false"));

    /** 禁用换电站实验开关：true=固定vP=0，false=正常模式（默认）
     *  可通过 -Ddisable.charging=true 启动参数控制 */
    private static final boolean DISABLE_CHARGING = Boolean.parseBoolean(System.getProperty("disable.charging", "false"));

    /** 是否使用原始版本模式进行对比实验 */
    private static final boolean USE_ORIGINAL_MODE = false;  // false=1.2版本（默认），true=原始版本

    /** 聚合覆盖链接加速策略：true=启用，false=不启用
     *  可通过 -Dfix.aggcov=true 启动参数控制 */
    private static final boolean FIX_AGG_COV = Boolean.parseBoolean(System.getProperty("fix.aggcov", "false"));

    /** T求和约束加速策略：true=启用 Σ_h t[h][j]≤1，false=不启用
     *  可通过 -Dfix.tsum=true 启动参数控制 */
    private static final boolean FIX_TSUM = Boolean.parseBoolean(System.getProperty("fix.tsum", "false"));

    /** 换电站有效不等式策略（MP）：true=启用 vP[j]≤ΣvL[j']，false=不启用
     *  可通过 -Dfix.stationvi=true 启动参数控制 */
    private static final boolean FIX_STATION_VI = Boolean.parseBoolean(System.getProperty("fix.stationvi", "false"));

    /** 追随者变量固定策略（MP+SP）：true=启用 z[j][k]=0，false=不启用
     *  可通过 -Dfix.var=true 启动参数控制 */
    private static final boolean FIX_VAR = Boolean.parseBoolean(System.getProperty("fix.var", "false"));

    // ========== 全局变量 ==========

    /** 全局静态 SolPool 实例，供静态方法访问和打印 A 集合 */
    private static pool.SolPool solpoolInstance;
    

    // 当前最优可行解存储
    private static double[][] bestYSolution;  // 当前最优可行解的 y 解
    private static double[][] bestZSolution;    // 当前最优可行解的 z 解
    private static utils.BCFLCalculator calculatorInstance; // 复用计算器实例，避免重复创建

    public static void main(String[] args) {
        // 设置输出重定向到文件
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            // 先加载数据以获取J和I（支持命令行参数指定数据文件）
            String dataFile = "data.txt";
            if (args.length > 0) {
                dataFile = args[0];
            }
            Input input = new Input(dataFile);
            Data data = input.getData();
            
            // 使用数据文件名（不含路径和扩展名）作为输出前缀，确保不同实验的输出文件不冲突
            String dataFileBase = new File(dataFile).getName().replaceAll("\\.txt$", "");
            SimpleDateFormat sdf = new SimpleDateFormat("MMdd_HHmm");
            String timestamp = sdf.format(new Date());
            String modeSuffix;
            if (DISABLE_DRONE && DISABLE_CHARGING) {
                modeSuffix = "无无人机无换电站";
            } else if (DISABLE_DRONE) {
                modeSuffix = "无无人机";
            } else if (DISABLE_CHARGING) {
                modeSuffix = "无换电站";
            } else {
                modeSuffix = "基础实验";
            }
            // 加速策略标记追加到文件名
            if (FIX_AGG_COV) modeSuffix += "-聚合覆盖";
            if (FIX_TSUM) modeSuffix += "-T求和";
            if (FIX_STATION_VI) modeSuffix += "-换电站VI";
            if (FIX_VAR) modeSuffix += "-固定变量";
            String outputFileName = timestamp + "-" + dataFileBase + "-output" + modeSuffix + ".txt";
            
            // 确保输出文件夹存在（支持 -Doutput.dir=xxx 自定义输出目录）
            String outputDirName = System.getProperty("output.dir", "output无人机基础实验");
            File outputDir = new File(outputDirName);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            // 重定向标准输出和错误输出到文件
            PrintStream fileOut = new PrintStream(new FileOutputStream(new File(outputDir, outputFileName)));
            System.setOut(fileOut);
            System.setErr(fileOut);
            
            System.out.println("=== BCFL算法开始运行 ===");
            System.out.println("运行时间: " + new Date());
            System.out.println("输出文件: " + outputDirName + "/" + outputFileName);
            System.out.println("=====================================");

            // 记录算法开始时间
            long algorithmStartTime = System.currentTimeMillis();

            // 步骤0 初始化上下界和容差
            int t = 0; //记录迭代次数
            double UB = Double.MAX_VALUE; //上界 = +∞
            double LB = 0.0; //下界 = 0
            double boundTol = 1e-3; //容差 bound_tol
            int maxIterations = 1000; //最大迭代次数限制
            
            // 停滞检测变量
            int stagnantCount = 0;
            int maxStagnantIterations =1; // 连续不变即认为停滞
            double lastUB = Double.MAX_VALUE;
            double lastLB = 0.0;

            Result result = new Result(data);
            BCFLMasterProblem MP = new BCFLMasterProblem(data);
            MP.setUseOriginalMode(USE_ORIGINAL_MODE);
            MP.setDisableDrone(DISABLE_DRONE);
            MP.setDisableChargingStation(DISABLE_CHARGING);
            // 加速策略开关
            MP.setFixAggCov(FIX_AGG_COV);
            MP.setFixTSum(FIX_TSUM);
            MP.setFixStationVI(FIX_STATION_VI);
            MP.setFixVar(FIX_VAR);
            BCFLSubProblem CSP = new BCFLSubProblem(data);
            CSP.setFixVar(FIX_VAR);
            solpoolInstance = new pool.SolPool(data);
            // 关闭SolPool内部的添加/重复打印（verbose控制）
            solpoolInstance.setVerbose(true);

            // 步骤1 初始化：求解MaxLeaderProblem得到gammaMax，代入子问题求psi
            // 用该psi初始化XiSet（替代默认ψ^0），并将(gammaMax, psi)加入A集合
            System.out.println("\n=== 初始化：求解MaxLeaderProblem ===");
            double[][] zeroZ = new double[data.getJ()][data.getK_f()];
            MaxLeaderProblem maxLeaderProblem = new MaxLeaderProblem(data, zeroZ);
            maxLeaderProblem.setDisableDrone(DISABLE_DRONE);
            maxLeaderProblem.setDisableChargingStation(DISABLE_CHARGING);
            pool.Gamma gammaMax = maxLeaderProblem.solve();
            System.out.println("MaxLeaderProblem求解完成，最优需求=" + maxLeaderProblem.getMaxLeaderDemand());

            // 将gammaMax代入子问题求解最优响应psi
            CSP.solve_Model(gammaMax);
            pool.Psi psiMax = CSP.getPsi();
            System.out.println("子问题求解完成，获取到追随者最优响应psi");

            // 用psiMax初始化XiSet（替代默认全零ψ^0）
            MP.initializeWithPsi(psiMax);

            // 将(gammaMax, psiMax)加入A集合
            pool.FeasibleSolution initialSol = new pool.FeasibleSolution(gammaMax, psiMax, new utils.BCFLCalculator(data));
            solpoolInstance.addFeasibleSolution(gammaMax, psiMax);

            // 用初始解的Pi更新下界
            if (initialSol.PiH > LB) {
                LB = initialSol.PiH;
                updateBestSolution(gammaMax.y, psiMax.z, data);
                System.out.println("  初始解更新下界: LB=" + LB);
            }
            
            System.out.println("\n=== 初始化完成 ===");
            System.out.println("XiSet已用MaxLeader对应的psi初始化");
            System.out.println("  当前A集合大小: " + solpoolInstance.getSize());
            System.out.println("  当前Ξ集合大小: " + MP.getXiSetSize());
            System.out.println("  初始解Pi=" + initialSol.PiH + ", Phi=" + initialSol.PhiH);
            System.out.println("  当前下界 LB=" + LB);
            System.out.println("=============================\n");

            // 根据调试开关决定是否输出Min值矩阵
            if (DEBUG_PRINT_FEASIBLE_SET) {
                System.out.println("\n从数据中获取的Min值矩阵:");
                for (int i = 0; i < data.getI(); i++) {
                    Data.INodes iNode = data.getINodes().get(i);
                    double[] M_in = iNode.getM_in();
                    for (int n = 0; n < data.getN(); n++) {
                        System.out.printf("Min[%d][%d] = %.2f ", i, n, M_in[n]);
                    }
                    System.out.println();
                }
                System.out.println("========================");
            }

            //创建循环
            // 步骤2: 使用基于UB的相对间隙判断
            while ((UB - LB) / Math.max(Math.abs(UB), 1e-9) > boundTol && t < maxIterations) {
                // 检查总时间是否超过3小时（10800秒）
                long currentTime = System.currentTimeMillis();
                double elapsedSeconds = (currentTime - algorithmStartTime) / 1000.0;
                if (elapsedSeconds >= 10800.0) {
                    System.out.println("达到总时间上限3小时，算法终止");
                    break;
                }
                
                // 步骤3 t = t + 1
                t++;
                
                System.out.printf("\n[迭代%d] 当前间隙: %.6f%% (UB=%.6f, LB=%.6f)\n", 
                    t, 100 * (UB - LB) / Math.max(Math.abs(UB), 1e-9), UB, LB);
                
                // 检测停滞（从第二个迭代开始）
                if (t > 1) { // 只在第二个迭代及以后检测停滞
                    if (Math.abs(UB - lastUB) < 1e-9 && Math.abs(LB - lastLB) < 1e-9) {
                        stagnantCount++;
                        System.out.println("警告：上下界已连续 " + stagnantCount + " 次迭代未变化");
                        
                        if (stagnantCount >= maxStagnantIterations) {
                            double currentGap = (UB - LB) / Math.max(Math.abs(UB), 1e-9);
                            System.out.println("算法停滞，连续" + maxStagnantIterations + "次迭代上下界未改善，当前相对间隙=" + String.format("%.6f%%", 100 * currentGap));
                            if (currentGap < 0.01) {
                                System.out.println("相对间隙小于1%，提前终止");
                                break;
                            } else {
                                System.out.println("相对间隙仍大于1%，重置停滞计数器继续求解");
                                stagnantCount = 0;
                            }
                        }
                    } else {
                        stagnantCount = 0; // 重置计数器
                    }
                }
                
                lastUB = UB;
                lastLB = LB;
                
                // 重置IntYZSet集合（每次迭代都需要重新在分支过程中添加）
                MP.resetIntYZSet();
                // 重置子问题的IntZSet集合
                CSP.clearIntZSet();

                // 【关键修复】将SolPool的A集合同步到MP中
                // 这样MP在构建模型时才能正确添加phi约束（基于Ξ集合）和逻辑切约束（基于A集合）
                MP.setFeasibleSet(solpoolInstance.getFeasibleSet());

                // 输出迭代信息
                System.out.println("\n=== 迭代 " + t + " 开始 ===");
                System.out.println("A集合大小: " + solpoolInstance.getSize()+"  Ξ集合大小: " + MP.getXiSetSize());
                System.out.println("当前上界 UB: " + UB+"当前下界 LB: " + LB);
                System.out.println("========================");

                // 步骤4 求解主问题 MP(A)
                // 注意：buildModel()会重新构建整个模型，包括变量和约束
                // 约束基于A集合：逻辑切约束（已更新：删去phi、Pi改为D、vL/vP分开、M3_h=sumM−D^h）
                // 主问题求解（回调中仅收集gamma，子问题在主问题完成后批量求解）
                MP.solve_Model();
                
                // 步骤5 判断主问题求解状态
                if (MP.isOptimalSolved()) {
                    // 步骤6 获得解yt和目标函数值Πt
                    // 步骤7 UB = min{UB, Πt}
                    double newUB = MP.getMpObjectiveValue();
                    if (newUB < UB) {
                        System.out.printf("[迭代%d] 更新上界: %.6f -> %.6f\n", t, UB, newUB);
                        UB = newUB;
                    }

                    // 步骤8：批量收集gamma并统一求解子问题
                    // 获取主问题最优解gamma
                    pool.Gamma bestGamma = MP.getBestGamma();

                    // 获取分支定界过程中收集的所有gamma（去重）
                    java.util.List<pool.Gamma> branchGammas = MP.getCurrentIterationGammaList();
                    System.out.printf("\n>>> 本次迭代在分支定界过程中收集了 %d 个新gamma\n", branchGammas.size());

                    // 合并gamma列表（主问题最优解 + 分支收集的），并用LinkedHashSet去重
                    java.util.Set<pool.Gamma> gammaSet = new java.util.LinkedHashSet<>(branchGammas);
                    gammaSet.add(bestGamma);
                    java.util.List<pool.Gamma> allGammas = new java.util.ArrayList<>(gammaSet);

                    System.out.printf(">>> 去重后共 %d 个gamma需要求解子问题\n", allGammas.size());

                    // 批量求解子问题，并将解对添加至A和Xi，同时更新下界
                    int addedToXiCount = 0;
                    int addedToACount = 0;
                    int skippedNaNCount = 0;
                    double bestPiInBatch = LB;
                    pool.Gamma bestPiGamma = null;
                    pool.Psi bestPiPsi = null;
                    utils.BCFLCalculator batchCalculator = new utils.BCFLCalculator(data);

                    for (pool.Gamma g : allGammas) {
                        // 求解子问题
                        CSP.solve_Model(g);
                        pool.Psi psi = CSP.getPsi();

                        // 创建可行解对
                        pool.FeasibleSolution sol = new pool.FeasibleSolution(g, psi, batchCalculator);

                        // 跳过NaN
                        if (Double.isNaN(sol.PiH) || Double.isNaN(sol.PhiH)) {
                            skippedNaNCount++;
                            System.out.println("警告：批量求解的解对中PiH/PhiH含NaN，跳过添加");
                            continue;
                        }

                        // 添加到Ξ集合
                        if (MP.addToXiSet(psi)) {
                            addedToXiCount++;
                        }

                        // 添加到A集合
                        if (solpoolInstance.addFeasibleSolution(g, psi)) {
                            addedToACount++;
                        }

                        // 跟踪最大Pi用于更新下界
                        if (sol.PiH > bestPiInBatch) {
                            bestPiInBatch = sol.PiH;
                            bestPiGamma = g;
                            bestPiPsi = psi;
                        }
                    }

                    System.out.printf("  批量求解完成：%d 个新解添加到Ξ，%d 个新解对添加到A", addedToXiCount, addedToACount);
                    if (skippedNaNCount > 0) {
                        System.out.printf("，%d 个含NaN解对被跳过", skippedNaNCount);
                    }
                    System.out.println();

                    // 步骤9-10：用批量求解中最好的Pi更新下界
                    if (bestPiInBatch > LB && bestPiGamma != null && bestPiPsi != null) {
                        System.out.printf("[迭代%d] 更新下界: %.6f -> %.6f\n", t, LB, bestPiInBatch);
                        LB = bestPiInBatch;

                        // 步骤11 (x*, y*) = (yh, z_h)
                        updateBestSolution(bestPiGamma.y, bestPiPsi.z, data);
                        // 输出当前最优可行解的sumDLin和sumDFin
                        System.out.println("\n=== 当前最优可行解 (x*, y*) 的市场需求 ===");
                        double bestSumDLin = calculateBestSumDLin(data);
                        double bestSumDFin = calculateBestSumDFin(data);
                        System.out.printf("sumDLin = Σ_i Σ_n D_in^L(y*, z*) = %.6f\n", bestSumDLin);
                        System.out.printf("sumDFin = Σ_i Σ_n D_in^F(y*, z*) = %.6f\n", bestSumDFin);
                        System.out.printf("总市场需求 = sumDLin + sumDFin = %.6f\n", bestSumDLin + bestSumDFin);
                        System.out.println("==================");
                    }

                    // 每次迭代都输出当前最优解对的详细gamma*和psi*
                    printBestSolutionPair(data);

                    // 步骤12：输出本次迭代A集合状态
                    // 根据调试开关决定是否打印A集合中所有可行解的 Pi 和 Phi 信息
                    if (DEBUG_PRINT_FEASIBLE_SET) {
                        printFeasibleSetPhiPi();
                    }

                    // 如果A集合没有添加新解，说明算法可能停滞
                    if (addedToACount == 0) {
                        System.out.println("警告：本次迭代未添加任何新解到集合A");
                    }

                    //输出结果
                    result.print(t, MP, CSP, UB, LB);

                    // 输出迭代累计时间
                    long iterationEndTime = System.currentTimeMillis();
                    double cumulativeTimeSeconds = (iterationEndTime - algorithmStartTime) / 1000.0;
                    System.out.printf("[迭代%d] 累计用时: %.3f 秒 (%.2f 分钟)\n", t, cumulativeTimeSeconds, cumulativeTimeSeconds / 60.0);

                } else {
                    // 步骤14 提取主问题的最佳松弛界作为bestbd
                    double bestbd = MP.getBestRelaxationBound();
                    // 步骤15 UB = min{UB, bestbd}
                    UB = Math.min(UB, bestbd);
                    // 步骤16 终止算法
                    System.out.println("主问题无法最优求解，算法终止");
                    result.print(t, MP, CSP, UB, LB);
                    break;
                }
            }

            // 步骤17 Return LB, UB, (x*, y*)
            if (t >= maxIterations) {
                System.out.println("达到最大迭代次数，算法终止");
            } else {
            }

            // 计算总运行时间
            long algorithmEndTime = System.currentTimeMillis();
            double totalTimeSeconds = (algorithmEndTime - algorithmStartTime) / 1000.0;

            System.out.println("\n=== 算法运行总结 ===");
            System.out.println("最终上界 UB = " + UB);
            System.out.println("最终下界 LB = " + LB);
            System.out.println("总迭代次数 = " + t);
            System.out.printf("算法总运行时间: %.3f 秒 (%.2f 分钟)\n", totalTimeSeconds, totalTimeSeconds / 60.0);
            System.out.println("平均每次迭代时间: " + String.format("%.3f", totalTimeSeconds / t) + " 秒");
            System.out.println("======================");
            printBestSolutionPair(data);
            // 输出数据信息和集合大小
            System.out.println("\n=== 数据信息 ===");
            System.out.println("集合大小信息:");
            System.out.println("  J (上层候选点数量): " + data.getJ());
            System.out.println("  J_f (下层候选点数量): " + data.getJ());
            System.out.println("  I (客户区数量): " + data.getI());
            System.out.println("  K_f (折扣等级数量): " + data.getK_f());
            System.out.println("  S (服务类型数量): " + data.getS());
            System.out.println("  N (包裹类型数量): " + data.getN());
            int droneChargeS = data.getS() - 1; // s=|S|-1：经过换电站的无人机服务
            Service droneCharges = data.getServices().get(droneChargeS);
            double droneCs = droneCharges.getR_s();
            System.out.println("droneCs:"+droneCs);

            // 输出参数信息
            System.out.println("\n参数信息:");
            var params = data.getParams();
            System.out.println("  B_l = " + params.B_l);
            System.out.println("  B_f = " + params.B_f);
            System.out.println("  beta_0 = " + params.beta_0);
            System.out.println("  beta_tt = " + params.beta_tt);
            System.out.println("  beta_tc = " + params.beta_tc);
            System.out.println("  beta_dt = " + params.beta_dt);
            System.out.println("  beta_dc = " + params.beta_dc);
            System.out.println("  lambda_L = " + params.lambda_l);
            System.out.println("  lambda_F = " + params.lambda_f);
            System.out.println("  lambda = " + params.lambda);

            // 输出服务参数
            System.out.println("\n服务参数:");
            System.out.println("  s\tbeta_0s\tDTs\tQ_s\tr_s(km)\ta_sn0\ta_sn1\tF_s");
            for (int si = 0; si < data.getS(); si++) {
                Service sv = data.getServices().get(si);
                System.out.printf("  %d\t%.2f\t%.1f\t%.2f\t%d\t%d\t%d\t%.0f\n",
                    sv.getS(), sv.getBeta_0s(), sv.getDTs(), sv.getQ_s(),
                    (int)sv.getR_s(), sv.getA_sn0(), sv.getA_sn1(), sv.getFs());
            }
            System.out.println("======================");

        } catch (IloException e) {
            System.err.println("CPLEX求解错误: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("程序运行错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 恢复原始输出流
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.out.println("程序运行完成，输出已保存到文件。");
        }
    }
     
    
    
    /**
     * 更新最优解（步骤11）
     * @param ySolution 当前最优可行解的 y 解
     * @param zSolution 当前最优可行解的 z 解
     * @param data 数据对象
     */
    public static void updateBestSolution(double[][] ySolution, double[][] zSolution, Data data) {
        // 保存当前最优可行解的 y 解
        bestYSolution = new double[data.getI()][data.getS()];
        for (int i = 0; i < data.getI(); i++) {
            for (int s = 0; s < data.getS(); s++) {
                bestYSolution[i][s] = ySolution[i][s];
            }
        }
        
        // 保存当前最优可行解的 z 解
        bestZSolution = new double[data.getJ()][data.getK_f()];
        for (int j = 0; j < data.getJ(); j++) {
            for (int k = 0; k < data.getK_f(); k++) {
                bestZSolution[j][k] = zSolution[j][k];
            }
        }
        
        System.out.println("已更新当前最优可行解 (x*, y*) = (yh, z_h)");
    }
    
    /**
     * 计算当前最优可行解的sumDLin（领导者市场需求总和）
     * @param data 数据对象
     * @return sumDLin = Σ_i Σ_n D_in^L(y*, z*)
     */
    public static double calculateBestSumDLin(Data data) {
        if (bestYSolution == null || bestZSolution == null) {
            System.out.println("警告：当前最优可行解未设置，无法计算sumDLin");
            return 0.0;
        }
        
        if (calculatorInstance == null) {
            calculatorInstance = new utils.BCFLCalculator(data);
        }
        double sumDLin = 0.0;
        for (int i = 0; i < data.getI(); i++) {
            for (int n = 0; n < data.getN(); n++) {
                double D_inL = calculatorInstance.calculateD_inL(i, n, bestYSolution, bestZSolution);
                sumDLin += D_inL;
            }
        }
        return sumDLin;
    }
    
    /**
     * 计算当前最优可行解的sumDFin（追随者市场需求总和）
     * @param data 数据对象
     * @return sumDFin = Σ_i Σ_n D_in^F(y*, z*)
     */
    public static double calculateBestSumDFin(Data data) {
        if (bestYSolution == null || bestZSolution == null) {
            System.out.println("警告：当前最优可行解未设置，无法计算sumDFin");
            return 0.0;
        }
        
        if (calculatorInstance == null) {
            calculatorInstance = new utils.BCFLCalculator(data);
        }
        double sumDFin = 0.0;
        for (int i = 0; i < data.getI(); i++) {
            for (int n = 0; n < data.getN(); n++) {
                double D_inF = calculatorInstance.calculateD_inF(i, n, bestYSolution, bestZSolution);
                sumDFin += D_inF;
            }
        }
        return sumDFin;
    }
    
    // 记录上次打印A集合的位置，避免每次迭代都打印全部历史解
    private static int lastPrintedFeasibleSetSize = 0;

    /**
     * 打印A集合中新增可行解的 Pi^h 和 Phi^h（只打印本次迭代新增的部分）
     */
    public static void printFeasibleSetPhiPi() {
        try {
            int currentSize = solpoolInstance.getSize();
            if (solpoolInstance == null || currentSize == 0) {
                System.out.println("\n=== A集合可行解信息 ===\nA集合为空\n====================================\n");
                return;
            }
            if (currentSize <= lastPrintedFeasibleSetSize) {
                System.out.println("\n=== A集合无新增解 ===\n");
                return;
            }
            System.out.println("\n=== A集合新增可行解信息（Pi^h 与 Phi^h）===");
            for (int h = lastPrintedFeasibleSetSize; h < currentSize; h++) {
                FeasibleSolution fs = solpoolInstance.getFeasibleSolution(h);
                if (fs == null) {
                    System.out.printf("h=%d: (null)\n", h);
                    continue;
                }
                System.out.printf("h=%d: Pi^h=%.6f, Phi^h=%.6f\n", h, fs.PiH, fs.PhiH);
            }
            System.out.println("====================================\n");
            lastPrintedFeasibleSetSize = currentSize;
        } catch (Exception e) {
            System.err.println("打印A集合可行解信息时出错: " + e.getMessage());
        }
    }
    
    /**
     * 输出A集合中Pi最大的最优解对详细信息
     * 格式参考主问题 printSolutionStats
     * @param data 数据对象
     */
    public static void printBestSolutionPair(Data data) {
        if (solpoolInstance == null || solpoolInstance.getSize() == 0) {
            System.out.println("\n=== 最优解对 ===");
            System.out.println("A集合为空，未找到最优解对");
            System.out.println("======================");
            return;
        }
        
        // 遍历A集合找到Pi最大的解
        FeasibleSolution bestSol = null;
        double maxPi = -Double.MAX_VALUE;
        int bestH = -1;
        int size = solpoolInstance.getSize();
        for (int h = 0; h < size; h++) {
            FeasibleSolution fs = solpoolInstance.getFeasibleSolution(h);
            if (fs != null && fs.PiH > maxPi) {
                maxPi = fs.PiH;
                bestSol = fs;
                bestH = h;
            }
        }
        
        if (bestSol == null || bestSol.gamma == null || bestSol.psi == null) {
            System.out.println("\n=== 最优解对 ===");
            System.out.println("未找到有效的最优解对");
            System.out.println("======================");
            return;
        }
        
        pool.Gamma gamma = bestSol.gamma;
        pool.Psi psi = bestSol.psi;
        
        if (calculatorInstance == null) {
            calculatorInstance = new utils.BCFLCalculator(data);
        }
        
        int J = data.getJ();
        int I = data.getI();
        int S = data.getS();
        int K_f = data.getK_f();
        
        System.out.println("\n=== 最优解对 (A集合中Pi最大, h=" + bestH + ") ===");
        System.out.printf("领导者市场份额 Pi* = %.6f\n", bestSol.PiH);
        System.out.printf("追随者市场份额 Phi* = %.6f\n", bestSol.PhiH);
        System.out.println();
        
        // ========== gamma* (领导者解) ==========
        System.out.println("=== gamma* (领导者解) ===");
        
        // vL解（设施选址）
        System.out.println("vL解 (设施选址):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  设施 %d: %.1f ", j, gamma.vL[j]);
            if ((j + 1) % 5 == 0) System.out.println();
        }
        System.out.println();
        
        // vP解（换电站选址）
        System.out.println("vP解 (换电站选址):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  换电站 %d: %.1f ", j, gamma.vP[j]);
            if ((j + 1) % 5 == 0) System.out.println();
        }
        System.out.println();
        
        // x解（设施服务配置）
        System.out.println("x解 (设施服务配置):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  设施 %d: ", j);
            for (int s = 0; s < S - 1; s++) {
                System.out.printf("x[%d][%d]=%.1f ", j, s, gamma.x[j][s]);
            }
            System.out.println();
        }
        System.out.println();
        
        // y解（客户区服务选择）
        System.out.println("y解 (客户区服务选择):");
        for (int i = 0; i < I; i++) {
            System.out.printf("  客户区 %d: ", i);
            for (int s = 0; s < S; s++) {
                System.out.printf("y[%d][%d]=%.1f ", i, s, gamma.y[i][s]);
            }
            System.out.println();
        }
        System.out.println();
        
        // ========== psi* (追随者解) ==========
        System.out.println("=== psi* (追随者解) ===");
        
        // vF解（传统零售商选择）
        System.out.println("vF解 (传统零售商选择):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  传统零售商 %d: %.1f ", j, psi.vF[j]);
            if ((j + 1) % 5 == 0) System.out.println();
        }
        System.out.println();
        
        // z解（传统零售商折扣选择）
        System.out.println("z解 (传统零售商折扣选择):");
        for (int j = 0; j < J; j++) {
            System.out.printf("  零售商 %d: ", j);
            for (int k = 0; k < K_f; k++) {
                System.out.printf("z[%d][%d]=%.1f ", j, k, psi.z[j][k]);
            }
            System.out.println();
        }
        System.out.println("======================");
    }
 
}