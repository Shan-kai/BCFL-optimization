package utils;

import input.Data;
import input.Data.Service;

/**
 * BCFL模型计算工具类
 * 包含所有BCFL模型相关的数学计算函数
 * 避免在多个类中重复相同的计算代码
 *
 * 优化版本：添加预计算缓存，大幅减少重复计算
 */
public class BCFLCalculator {
    private final Data data;

    // ========== 预计算缓存 ==========
    // U_sk[s]: 服务效用缓存
    private double[] cachedU_sk;
    // U_ijk[i][j][k]: 传统零售商效用缓存 (针对J候选点)
    private double[][][] cachedU_ijk;
    // U_ij[i][j]: 现有零售商效用缓存 (针对J_0节点)
    private double[][] cachedU_ij;
    // exp_U_sk[s]: exp(U_sk/lambda_l) 缓存
    private double[] cachedExpU_sk;
    // exp_U_ijk[i][j][k]: exp(U_ijk/lambda_f) 缓存
    private double[][][] cachedExpU_ijk;
    // exp_U_ij[i][j]: exp(U_ij/lambda_f) 缓存
    private double[][] cachedExpU_ij;
    // B[i]: 现有零售商效用指数和 Σ_{j∈J_0} exp(U_ij/lambda_f) 缓存
    private double[] cachedB;
    // a_sn[s][n]: 服务权重缓存
    private double[][] cachedA_sn;

    private boolean cacheInitialized = false;

    public BCFLCalculator(Data data) {
        this.data = data;
    }

    /**
     * 初始化所有预计算缓存（应在求解前调用一次）
     */
    public void initializeCache() {
        if (cacheInitialized) return;

        int S = data.getS();
        int K_f = data.getK_f();
        int I = data.getI();
        int J = data.getJ();
        int J_0 = data.getJ_0();
        int N = data.getN();

        double lambda_l = data.getParams().getLambdal();
        double lambda_f = data.getParams().getLambdaf();

        // 1. 预计算 U_sk 和 exp(U_sk/lambda_l)
        cachedU_sk = new double[S];
        cachedExpU_sk = new double[S];
        for (int s = 0; s < S; s++) {
            cachedU_sk[s] = computeU_sk(s);
            cachedExpU_sk[s] = Math.exp(cachedU_sk[s] / lambda_l);
        }

        // 2. 预计算 U_ij 和 exp(U_ij/lambda_f) - 现有零售商J_0
        cachedU_ij = new double[I][J_0];
        cachedExpU_ij = new double[I][J_0];
        cachedB = new double[I];
        for (int i = 0; i < I; i++) {
            cachedB[i] = 0.0;
            for (int j = 0; j < J_0; j++) {
                cachedU_ij[i][j] = computeU_ij(i, j);
                cachedExpU_ij[i][j] = Math.exp(cachedU_ij[i][j] / lambda_f);
                cachedB[i] += cachedExpU_ij[i][j];
            }
        }

        // 3. 预计算 U_ijk 和 exp(U_ijk/lambda_f) - 候选点J
        cachedU_ijk = new double[I][J][K_f];
        cachedExpU_ijk = new double[I][J][K_f];
        for (int i = 0; i < I; i++) {
            for (int j = 0; j < J; j++) {
                for (int k = 0; k < K_f; k++) {
                    cachedU_ijk[i][j][k] = computeU_ijk(i, j, k);
                    cachedExpU_ijk[i][j][k] = Math.exp(cachedU_ijk[i][j][k] / lambda_f);
                }
            }
        }

        // 4. 预计算服务权重 a_sn
        cachedA_sn = new double[S][N];
        for (int s = 0; s < S; s++) {
            for (int n = 0; n < N; n++) {
                cachedA_sn[s][n] = data.getServices().get(s).getAsn(n);
            }
        }

        cacheInitialized = true;
        System.out.println("BCFLCalculator缓存初始化完成: U_sk[" + S + "], U_ijk[" + I + "×" + J + "×" + K_f + "], U_ij[" + I + "×" + J_0 + "]");
    }

    /**
     * 检查缓存是否已初始化，未初始化则自动初始化
     */
    private void ensureCacheInitialized() {
        if (!cacheInitialized) {
            initializeCache();
        }
    }

    // ========== 内部计算方法（不使用缓存）==========

    private double computeU_sk(int s) {
        Service service = data.getServices().get(s);
        double beta0s = service.getBeta_0s();
        double betaDt = data.getParams().getBetaDt();
        double DT_s = service.getDTs();
        double timeCost = betaDt * DT_s;
        double betaDc = data.getParams().getBetaDc();
        double Q_s = service.getQs();
        double priceCost = betaDc * Q_s;
        return beta0s - timeCost - priceCost;
    }

    private double computeU_ij(int i, int j) {
        double beta_0 = data.getParams().getBeta_0();
        double betaTt = data.getParams().getBetaTt();
        double betaTc = data.getParams().getBetaTc();
        double TT_ij = data.getTravelTimeMatrixIJ_0()[i][j];
        double TC_ij = data.getTravelCostMatrixIJ_0()[i][j];
        return beta_0 - betaTt * TT_ij - betaTc * TC_ij;
    }

    private double computeU_ijk(int i, int j, int k) {
        double beta_0 = data.getParams().getBeta_0();
        double betaTt = data.getParams().getBetaTt();
        double betaTc = data.getParams().getBetaTc();
        Data.INodes customer = data.getINodes().get(i);
        Data.JNodes candidate = data.getJNodes().get(j);
        double distance = data.calculateEuclideanDistance(
            customer.getX(), customer.getY(),
            candidate.getX(), candidate.getY()
        );
        double TT_ij = data.calculateTravelTime(distance);
        double TC_ij = data.calculateTravelCost(distance);
        double theta_jk = candidate.getTheta_jk(k);
        return beta_0 - betaTt * TT_ij - betaTc * TC_ij + theta_jk;
    }
    
    // ========== 公开接口方法（使用缓存）==========

    /**
     * 计算服务效用u_s（使用缓存）
     */
    public double calculateU_sk(int s) {
        ensureCacheInitialized();
        return cachedU_sk[s];
    }

    /**
     * 获取exp(U_sk/lambda_l)（使用缓存）
     */
    public double getExpU_sk(int s) {
        ensureCacheInitialized();
        return cachedExpU_sk[s];
    }

    /**
     * 计算传统零售商效用u_{ijk}（使用缓存）
     */
    public double calculateU_ijk(int i, int j, int k) {
        ensureCacheInitialized();
        return cachedU_ijk[i][j][k];
    }

    /**
     * 获取exp(U_ijk/lambda_f)（使用缓存）
     */
    public double getExpU_ijk(int i, int j, int k) {
        ensureCacheInitialized();
        return cachedExpU_ijk[i][j][k];
    }

    /**
     * 计算现有零售商效用u_{ij}（使用缓存）
     */
    public double calculateU_ij(int i, int j) {
        ensureCacheInitialized();
        return cachedU_ij[i][j];
    }

    /**
     * 获取exp(U_ij/lambda_f)（使用缓存）
     */
    public double getExpU_ij(int i, int j) {
        ensureCacheInitialized();
        return cachedExpU_ij[i][j];
    }

    /**
     * 获取B[i] = Σ_{j∈J_0} exp(U_ij/lambda_f)（使用缓存）
     */
    public double getCachedB(int i) {
        ensureCacheInitialized();
        return cachedB[i];
    }

    /**
     * 获取服务权重a_sn（使用缓存）
     */
    public double getServiceWeight(int s, int n) {
        ensureCacheInitialized();
        return cachedA_sn[s][n];
    }



    /**
     * 计算总效用U_{in}(y,z)（优化版）
     */
    public double calculateU_in(int i, int n, double[][] y, double[][] z) {
        double leaderPart = calculateLeaderPart(i, n, y);
        double followerPart = calculateFollowerPart(i, z);
        return leaderPart + followerPart;
    }

    /**
     * 计算领导者部分: (Σ_s a_sn y_is e^{u_s/λ_l})^λ_l（优化版，使用缓存）
     */
    public double calculateLeaderPart(int i, int n, double[][] y) {
        ensureCacheInitialized();
        double sum = calculateLeaderSum(i,n,y);
        double lambda_l = data.getParams().getLambdal();

        return Math.pow(sum, lambda_l);
    }

    /**
     * 计算追随者部分: (B[i] + Σ_j Σ_k z_{jk} e^{u_{ijk}/λ_f})^λ_f（优化版，使用缓存）
     */
    public double calculateFollowerPart(int i, double[][] z) {
        ensureCacheInitialized();
        double lambda_f = data.getParams().getLambdaf();
        double sum =calculateFollowerSum(i, z);
        return Math.pow(sum, lambda_f);
    }

    /**
     * 计算追随者部分的中间和 c = B[i] + Σ_j Σ_k z_{jk} e^{u_{ijk}/λ_f}（优化版）
     * 返回c值，用于梯度计算
     */
    public double calculateFollowerSum(int i, double[][] z) {
        ensureCacheInitialized();
        double sum = cachedB[i];
        for (int j = 0; j < data.getJ(); j++) {
            for (int k = 0; k < data.getK_f(); k++) {
                sum += z[j][k] * cachedExpU_ijk[i][j][k];
            }
        }
        return sum;
    }

    /**
     * 计算领导者部分的中间和 Σ_s a_sn y_is e^{u_s/λ_l}（优化版）
     * 返回sum值，用于梯度计算
     */
    public double calculateLeaderSum(int i, int n, double[][] y) {
        ensureCacheInitialized();
        double sum = 0.0;
        for (int s = 0; s < data.getS(); s++) {
            double a_sn = cachedA_sn[s][n];
            sum += a_sn * y[i][s] * cachedExpU_sk[s];
        }
        return sum;
    }
    
    /**
     * 计算领导者选择概率p_{in}^L
     * 公式：p_{in}^L = (Σ_s a_{sn} * y_{is} * e^{u_s/λ_e})^{λ_e} / U_{in}
     */
    public double calculateP_inL(int i, int n, double[][] y, double U_in) {
        double leaderUtility = calculateLeaderPart(i, n, y);
        
        return leaderUtility / U_in;
    }
    
    /**
     * 计算追随者选择概率p_{in}^F
     * 公式：p_{in}^F = (Σ_b Σ_h z_{bh} * e^{u_{ibhn}/λ_t})^{λ_t} / U_{in}
     */
    public double calculateP_inF(int i, int n, double[][] z, double U_in) {
        
        double followerUtility= calculateFollowerPart(i, z);
        
        return followerUtility / U_in;
    }
    
    /**
     * 计算市场需求D_{in}
     * 公式：D_{in} = M_{in} * g(U_{in})
     */
    public double calculateD_in(int i, int n, double U_in) {
        double M_in = data.getMarketSize(i, n);
        double g_Uin = 1.0 - Math.exp(-data.getParams().getLambda() * U_in);
        return M_in * g_Uin;
    }
    
    /**
     * 计算领导者市场需求D_{in}^L
     * 公式：D_{in}^L = M_{in} * g(U_{in}) * p_{in}^L
     */
    public double calculateD_inL(int i, int n, double[][] y, double[][] z) {
        double U_in = calculateU_in(i, n, y, z);
        double D_in = calculateD_in(i, n, U_in);
        double p_inL = calculateP_inL(i, n, y, U_in);
        return D_in * p_inL;
    }
    
    /**
     * 计算追随者市场需求D_{in}^F
     * 公式：D_{in}^F = M_{in} * g(U_{in}) * p_{in}^F
     */
    public double calculateD_inF(int i, int n, double[][] y, double[][] z) {
        double U_in = calculateU_in(i, n, y, z);
        double D_in = calculateD_in(i, n, U_in);
        double p_inF = calculateP_inF(i, n, z, U_in);
        return D_in * p_inF;
    }

    /**
     * 计算D_in对y_is的偏导数（优化版，使用缓存）
     */
    public double calculatePartialD_in_wrt_y(int i, int n, int s, double[][] y, double[][] z) {
        ensureCacheInitialized();
        double M_in = data.getMarketSize(i, n);
        Data.Parameters params = data.getParams();
        double lambda = params.getLambda();
        double lambda_l = params.getLambdal();

        double U_in = calculateU_in(i, n, y, z);
        double leaderSum = calculateLeaderSum(i, n, y);

        double a_sn = cachedA_sn[s][n];
        double expU_sk = cachedExpU_sk[s];

        double gradient = M_in * lambda * Math.exp(-lambda * U_in)
                        * lambda_l * Math.pow(leaderSum, lambda_l - 1)
                        * a_sn * expU_sk;

        return gradient;
    }

    /**
     * 计算D_in^L对y_is的偏导数（修正版，与论文公式等价）
     *
     * 论文简化符号形式：
     * 令 A_in = sum_s a_{sn} * y_{is} * exp(u_s/lambda_l)
     * 令 U_in = A_in^{lambda_l} + B_in，其中 B_in 是追随者效用部分
     *
     * D_in^L = M_in * (1 - exp(-lambda*U_in)) / U_in * A_in^{lambda_l}
     *
     * 正确求导（使用乘积法则）：
     * d(D_in^L)/dy_is = M_in * [d(A^{lambda_l})/dy * (1-exp(-lambda*U))/U
     *                          + A^{lambda_l} * d((1-exp(-lambda*U))/U)/dy]
     *
     * 最终公式（与论文形式等价）：
     * d(D_in^L)/dy_is = M_in * lambda_l * a_{sn} * exp(u_s/lambda_l) * A_in^{lambda_l-1}
     *                   * [lambda * exp(-lambda*U) * A_in^{lambda_l}/U
     *                      + (1-exp(-lambda*U))/U * B_in/U]
     *
     * @param i 客户区索引
     * @param n 情境索引
     * @param s 服务索引
     * @param y 当前y值矩阵
     * @param z 当前z值矩阵（追随者决策）
     * @return D_in^L 对 y_is 的偏导数值
     */
    public double calculatePartialD_inL_wrt_y(int i, int n, int s, double[][] y, double[][] z) {
        ensureCacheInitialized();
        double M_in = data.getMarketSize(i, n);
        Data.Parameters params = data.getParams();
        double lambda = params.getLambda();
        double lambda_l = params.getLambdal();
        double lambda_f = params.getLambdaf();

        // 步骤1：计算 A_in = sum_s a_{sn} * y_{is} * exp(u_s/lambda_l)
        double A_in = calculateLeaderSum(i, n, y);

        // 步骤2：计算 B_in（追随者效用部分）
        // B_in = (sum_{j in J_0} exp(u_ij/lambda_f) + sum_{j,k} z_{jk} * exp(u_ijk/lambda_f))^{lambda_f}
        double followerSum = calculateFollowerSum(i, z);
        double B_in = Math.pow(followerSum, lambda_f);

        // 步骤3：计算 U_in = A_in^{lambda_l} + B_in
        double A_power_l = Math.pow(A_in, lambda_l);
        double U_in = A_power_l + B_in;

        // 步骤4：计算 a_{sn} * exp(u_s/lambda_l)
        double a_sn = cachedA_sn[s][n];
        double expU_s = cachedExpU_sk[s];
        double a_exp_term = a_sn * expU_s;

        // 步骤5：计算公共因子 d(A^{lambda_l})/dy
        // = lambda_l * a_{sn} * exp(u_s/lambda_l) * A_in^{lambda_l-1}
        double commonFactor = lambda_l * a_exp_term * Math.pow(A_in, lambda_l - 1);

        // 步骤6：计算方括号内的两项（论文形式）
        double exp_neg_lambda_U = Math.exp(-lambda * U_in);

        // 第一项：lambda * exp(-lambda*U) * A^{lambda_l}/U
        // 来自市场需求函数 g(U) = (1-exp(-lambda*U))/U 的导数
        double term1 = lambda * exp_neg_lambda_U * A_power_l / U_in;

        // 第二项：(1-exp(-lambda*U))/U * B_in/U
        // 来自选择概率 p^L = A^{lambda_l}/U 的导数（分母U的变化）
        double term2 = (1.0 - exp_neg_lambda_U) / U_in * B_in / U_in;

        // 最终梯度 = M_in * 公共因子 * (第一项 + 第二项)
        double gradient = M_in * commonFactor * (term1 + term2);

        return gradient;
    }

    /**
     * 计算D_in对z_jk的偏导数（优化版，使用缓存）
     */
    public double calculatePartialD_in_wrt_z(int i, int n, int j, int k, double[][] y, double[][] z) {
        ensureCacheInitialized();
        double M_in = data.getMarketSize(i, n);
        Data.Parameters params = data.getParams();
        double lambda = params.getLambda();
        double lambda_f = params.getLambdaf();

        double U_in = calculateU_in(i, n, y, z);
        double followerSum = calculateFollowerSum(i, z);
        double expU_ijk = cachedExpU_ijk[i][j][k];

        double gradient = M_in * lambda * Math.exp(-lambda * U_in)
                         * lambda_f * Math.pow(followerSum, lambda_f - 1)
                         * expU_ijk;

        return gradient;
    }

    /**
     * 计算D_in^F对y_is的偏导数（优化版，使用缓存）
     */
    public double calculatePartialD_inF_wrt_y(int i, int n, int s, double[][] y, double[][] z) {
        ensureCacheInitialized();
        double M_in = data.getMarketSize(i, n);
        Data.Parameters params = data.getParams();
        double lambda = params.getLambda();
        double lambda_l = params.getLambdal();
        double lambda_f = params.getLambdaf();

        double B = cachedB[i];
        double c_extra = 0.0;
        for (int jj = 0; jj < data.getJ(); jj++) {
            for (int kk = 0; kk < data.getK_f(); kk++) {
                c_extra += z[jj][kk] * cachedExpU_ijk[i][jj][kk];
            }
        }

        double leaderSum = calculateLeaderSum(i, n, y);
        double base = B + c_extra;

        double U = Math.pow(leaderSum, lambda_l) + Math.pow(base, lambda_f);

        double leaderPart = Math.pow(leaderSum, lambda_l - 1);

        double a_sn = cachedA_sn[s][n];
        double expU_sk = cachedExpU_sk[s];

        double x = Math.pow(base, lambda_f);
        double gradient = M_in * (lambda * Math.exp(-lambda * U) * x / U
                        - (1 - Math.exp(-lambda * U)) * x / (U * U))
                        * lambda_l * leaderPart * a_sn * expU_sk;
        return gradient;
    }

    /**
     * 计算 ∂D_{in}^F / ∂z_{jk}（优化版，使用缓存）
     */
    public double calculatePartialD_inF_wrt_z(int i, int n, int j, int k,
                                              double[][] y, double[][] z) {
        ensureCacheInitialized();
        double lambda = data.getParams().getLambda();
        double lambda_l = data.getParams().getLambdal();
        double lambda_f = data.getParams().getLambdaf();
        double M_in = data.getMarketSize(i, n);


        double leaderSum = calculateLeaderSum(i, n, y);
        double A = Math.pow(leaderSum, lambda_l);

        double B = cachedB[i];
        double c_extra = 0.0;
        for (int jj = 0; jj < data.getJ(); jj++) {
            for (int kk = 0; kk < data.getK_f(); kk++) {
                c_extra += z[jj][kk] * cachedExpU_ijk[i][jj][kk];
            }
        }

        double c = B + c_extra;

        double c_pow = Math.pow(c, lambda_f);
        double U = A + c_pow;

        double expTarget = cachedExpU_ijk[i][j][k];

        double exp_minus = Math.exp(-lambda * U);
        double g = 1.0 - exp_minus;
        double gPrime = lambda * exp_minus;

        double term1 = M_in * gPrime * lambda_f * Math.pow(c, 2 * lambda_f - 1)
                    / U * expTarget;

        double c_pow_minus1 = Math.pow(c, lambda_f - 1.0);
        double term2num = lambda_f * c_pow_minus1 * (U - c_pow);
        double term2 = M_in * g * (term2num / (U * U)) * expTarget;

        return term1 + term2;
    }

    // ========== 批量梯度计算方法（子问题优化专用）==========

    /**
     * 批量计算所有z梯度（子问题OA切割专用）
     * 一次计算所有 ∂D_{in}^F/∂z_{jk}，避免重复计算中间变量
     * @return gradients[j][k] = ∂D_{in}^F/∂z_{jk}
     */
    public double[][] calculateAllGradients_DinF_wrt_z(int i, int n, double[][] y, double[][] z) {
        ensureCacheInitialized();
        int J = data.getJ();
        int K_f = data.getK_f();
        double[][] gradients = new double[J][K_f];

        double lambda = data.getParams().getLambda();
        double lambda_l = data.getParams().getLambdal();
        double lambda_f = data.getParams().getLambdaf();
        double M_in = data.getMarketSize(i, n);


        // 1) 计算 A = leaderSum^λ_e（只计算一次）
        double leaderSum = calculateLeaderSum(i, n, y);
        double A = Math.pow(leaderSum, lambda_l);

        // 2) 计算 c = B + c_extra（只计算一次）
        double B = cachedB[i];
        double c_extra = 0.0;
        for (int jj = 0; jj < J; jj++) {
            for (int kk = 0; kk < K_f; kk++) {
                c_extra += z[jj][kk] * cachedExpU_ijk[i][jj][kk];
            }
        }
        double c = B + c_extra;

        // 3) 计算 U = A + c^λ_f（只计算一次）
        double c_pow = Math.pow(c, lambda_f);
        double U = A + c_pow;

        // 4) 计算公共因子（只计算一次）
        double exp_minus = Math.exp(-lambda * U);
        double g = 1.0 - exp_minus;
        double gPrime = lambda * exp_minus;

        double c_pow_2f_minus1 = Math.pow(c, 2 * lambda_f - 1);
        double c_pow_f_minus1 = Math.pow(c, lambda_f - 1.0);

        double commonFactor1 = M_in * gPrime * lambda_f * c_pow_2f_minus1 / U;
        double term2num = lambda_f * c_pow_f_minus1 * (U - c_pow);
        double commonFactor2 = M_in * g * (term2num / (U * U));

        // 5) 计算每个(j,k)的梯度（只需乘以对应的expU_ijk）
        for (int jj = 0; jj < J; jj++) {
            for (int kk = 0; kk < K_f; kk++) {
                double expTarget = cachedExpU_ijk[i][jj][kk];
                gradients[jj][kk] = (commonFactor1 + commonFactor2) * expTarget;
            }
        }

        return gradients;
    }

    /**
     * 批量计算D_inF和所有梯度（子问题OA切割专用，最高效版本）
     * 返回: result[0] = D_inF值, result[1...J*K_f] = 梯度展平
     */
    public SPGradientResult calculateD_inF_and_AllGradients(int i, int n, double[][] y, double[][] z) {
        ensureCacheInitialized();
        int J = data.getJ();
        int K_f = data.getK_f();

        double lambda = data.getParams().getLambda();
        double lambda_l = data.getParams().getLambdal();
        double lambda_f = data.getParams().getLambdaf();
        double M_in = data.getMarketSize(i, n);


        // 1) 计算 leaderSum 和 A
        double leaderSum = calculateLeaderSum(i, n, y);
        double A = Math.pow(leaderSum, lambda_l);

        // 2) 计算 c = B + c_extra
        double B = cachedB[i];
        double c_extra = 0.0;
        for (int jj = 0; jj < J; jj++) {
            for (int kk = 0; kk < K_f; kk++) {
                c_extra += z[jj][kk] * cachedExpU_ijk[i][jj][kk];
            }
        }
        double c = B + c_extra;

        // 3) 计算 U
        double c_pow = Math.pow(c, lambda_f);
        double U = A + c_pow;

        // 4) 计算 D_inF = M_in * g(U) * p_inF
        double exp_minus = Math.exp(-lambda * U);
        double g = 1.0 - exp_minus;
        double p_inF = c_pow / U;
        double D_inF = M_in * g * p_inF;

        // 5) 计算梯度公共因子
        double gPrime = lambda * exp_minus;
        double c_pow_2f_minus1 = Math.pow(c, 2 * lambda_f - 1);
        double c_pow_f_minus1 = Math.pow(c, lambda_f - 1.0);

        double commonFactor1 = M_in * gPrime * lambda_f * c_pow_2f_minus1 / U;
        double term2num = lambda_f * c_pow_f_minus1 * (U - c_pow);
        double commonFactor2 = M_in * g * (term2num / (U * U));
        double gradientCommon = commonFactor1 + commonFactor2;

        // 6) 计算所有梯度
        double[][] gradients = new double[J][K_f];
        for (int jj = 0; jj < J; jj++) {
            for (int kk = 0; kk < K_f; kk++) {
                gradients[jj][kk] = gradientCommon * cachedExpU_ijk[i][jj][kk];
            }
        }

        return new SPGradientResult(D_inF, gradients);
    }

    /**
     * 子问题梯度计算结果容器
     */
    public static class SPGradientResult {
        public final double D_inF;
        public final double[][] gradients;

        public SPGradientResult(double D_inF, double[][] gradients) {
            this.D_inF = D_inF;
            this.gradients = gradients;
        }
    }

    // ========== 主问题批量梯度计算方法 ==========

    /**
     * 主问题d约束梯度计算结果容器
     * 包含D_in值和所有y、z梯度
     */
    public static class MPDinGradientResult {
        public final double D_in;
        public final double[] gradients_y;    // [S]
        public final double[][] gradients_z;  // [J][K_f]

        public MPDinGradientResult(double D_in, double[] gradients_y, double[][] gradients_z) {
            this.D_in = D_in;
            this.gradients_y = gradients_y;
            this.gradients_z = gradients_z;
        }
    }

    /**
     * 主问题tau约束梯度计算结果容器
     * 包含D_inF值和所有y梯度
     */
    public static class MPTauGradientResult {
        public final double D_inF;
        public final double[] gradients_y;    // [S]

        public MPTauGradientResult(double D_inF, double[] gradients_y) {
            this.D_inF = D_inF;
            this.gradients_y = gradients_y;
        }
    }

    /**
     * 批量计算D_in和所有梯度（主问题d约束OA切割专用）
     * 一次计算D_in、所有∂D_in/∂y_isk和所有∂D_in/∂z_jk，避免重复计算中间变量
     * @return MPDinGradientResult 包含D_in和所有y、z梯度
     */
    public MPDinGradientResult calculateD_in_and_AllGradients(int i, int n, double[][] y, double[][] z) {
        ensureCacheInitialized();
        int S = data.getS();
        int J = data.getJ();
        int K_f = data.getK_f();

        double M_in = data.getMarketSize(i, n);
        double lambda = data.getParams().getLambda();
        double lambda_l = data.getParams().getLambdal();
        double lambda_f = data.getParams().getLambdaf();

        // 1) 计算 leaderSum 和 followerSum（只计算一次）
        double leaderSum = calculateLeaderSum(i, n, y);
        double followerSum = calculateFollowerSum(i, z);

        // 2) 计算 U_in 和 D_in
        double leaderPart = Math.pow(leaderSum, lambda_l);
        double followerPart = Math.pow(followerSum, lambda_f);
        double U_in = leaderPart + followerPart;

        double g_Uin = 1.0 - Math.exp(-lambda * U_in);
        double D_in = M_in * g_Uin;

        // 3) 计算梯度公共因子
        double exp_minus_lambda_U = Math.exp(-lambda * U_in);
        double commonBase = M_in * lambda * exp_minus_lambda_U;

        // 4) 计算y梯度公共因子和所有y梯度
        double y_commonFactor = commonBase * lambda_l * Math.pow(leaderSum, lambda_l - 1);
        double[] gradients_y = new double[S];
        for (int s = 0; s < S; s++) {
            double a_sn = cachedA_sn[s][n];
            double expU_sk = cachedExpU_sk[s];
            gradients_y[s] = y_commonFactor * a_sn * expU_sk;
        }

        // 5) 计算z梯度公共因子和所有z梯度
        double z_commonFactor = commonBase * lambda_f * Math.pow(followerSum, lambda_f - 1);
        double[][] gradients_z = new double[J][K_f];
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                gradients_z[j][k] = z_commonFactor * cachedExpU_ijk[i][j][k];
            }
        }

        return new MPDinGradientResult(D_in, gradients_y, gradients_z);
    }

    /**
     * 批量计算D_inF和所有y梯度（主问题tau约束OA切割专用）
     * 一次计算D_inF和所有∂D_inF/∂y_isk，避免重复计算中间变量
     * @param i 客户区索引
     * @param n 包裹类型索引
     * @param y 当前y解
     * @param z 历史z^h解
     * @return MPTauGradientResult 包含D_inF和所有y梯度
     */
    public MPTauGradientResult calculateD_inF_and_AllYGradients(int i, int n, double[][] y, double[][] z) {
        ensureCacheInitialized();
        int S = data.getS();
        int J = data.getJ();
        int K_f = data.getK_f();

        double M_in = data.getMarketSize(i, n);
        double lambda = data.getParams().getLambda();
        double lambda_l = data.getParams().getLambdal();
        double lambda_f = data.getParams().getLambdaf();

        // 1) 计算 leaderSum
        double leaderSum = calculateLeaderSum(i, n, y);
        double A = Math.pow(leaderSum, lambda_l);

        // 2) 计算 c = B + c_extra
        double B = cachedB[i];
        double c_extra = 0.0;
        for (int jj = 0; jj < J; jj++) {
            for (int kk = 0; kk < K_f; kk++) {
                c_extra += z[jj][kk] * cachedExpU_ijk[i][jj][kk];
            }
        }
        double c = B + c_extra;

        // 3) 计算 U
        double c_pow = Math.pow(c, lambda_f);
        double U = A + c_pow;

        // 4) 计算 D_inF = M_in * g(U) * p_inF
        double exp_minus = Math.exp(-lambda * U);
        double g = 1.0 - exp_minus;
        double p_inF = c_pow / U;
        double D_inF = M_in * g * p_inF;

        // 5) 计算y梯度
        // 公式: ∂D_inF/∂y_isk = M_in * (lambda*exp(-lambda*U)*c^lambda_f/U - (1-exp(-lambda*U))*c^lambda_f/U^2)
        //                     * lambda_l * leaderSum^(lambda_l-1) * a_sn * expU_sk
        double gPrime = lambda * exp_minus;
        double leaderPart = Math.pow(leaderSum, lambda_l - 1);

        // x = c^lambda_f
        double x = c_pow;
        double gradientCommon = M_in * (gPrime * x / U - g * x / (U * U))
                              * lambda_l * leaderPart;

        double[] gradients_y = new double[S];
        for (int s = 0; s < S; s++) {
            double a_sn = cachedA_sn[s][n];
            double expU_sk = cachedExpU_sk[s];
            gradients_y[s] = gradientCommon * a_sn * expU_sk;
        }

        return new MPTauGradientResult(D_inF, gradients_y);
    }
}