package pool;

import utils.BCFLCalculator;

/**
 * 可行解数据结构：A = {(γ^h, ψ^h) | h=0,1,2,...,H}
 *
 * 其中：
 * - γ^h = (vL^h, vP^h, x^h, y^h) 是领导者完整解
 * - ψ^h = (vF^h, z^h) 是追随者完整解
 * - Π^h = Σ_i Σ_n D_{in}^L(y^h, z^h) 是领导者市场份额
 * - Φ^h = Σ_i Σ_n D_{in}^F(y^h, z^h) 是追随者市场份额
 *
 * 根据PDF第6页，A集合用于添加逻辑切约束
 */
public class FeasibleSolution {
    public final Gamma gamma;           // γ^h：领导者完整解
    public final Psi psi;               // ψ^h：追随者完整解
    public final double PiH;            // Π^h：领导者市场份额
    public final double PhiH;           // Φ^h：追随者市场份额

    /**
     * 构造函数：存储完整的(γ^h, ψ^h)可行解
     * @param gamma γ^h领导者完整解
     * @param psi ψ^h追随者完整解
     * @param calculator 计算器实例，用于计算Π^h和Φ^h
     */
    public FeasibleSolution(Gamma gamma, Psi psi, BCFLCalculator calculator) {
        this.gamma = gamma;
        this.psi = psi;

        // 通过计算器计算Π^h = Σ_i Σ_n D_{in}^L(y^h, z^h)
        this.PiH = calculatePiH(calculator);

        // 通过计算器计算Φ^h = Σ_i Σ_n D_{in}^F(y^h, z^h)
        this.PhiH = calculatePhiH(calculator);
    }

    /**
     * 通过计算器计算Π^h = Σ_i Σ_n D_{in}^L(y^h, z^h)
     * @param calculator 计算器实例
     * @return Π^h值
     */
    private double calculatePiH(BCFLCalculator calculator) {
        if (gamma == null || gamma.y == null || psi == null || psi.z == null) {
            return 0.0;
        }

        double piH = 0.0;
        int I = gamma.y.length;  // 客户区数量
        int N = 2;               // 包裹类型数量（固定为2）

        // 计算Σ_i Σ_n D_{in}^L(y^h, z^h)
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                double D_inL = calculator.calculateD_inL(i, n, gamma.y, psi.z);
                piH += D_inL;
            }
        }

        return piH;
    }

    /**
     * 通过计算器计算Φ^h = Σ_i Σ_n D_{in}^F(y^h, z^h)
     * @param calculator 计算器实例
     * @return Φ^h值
     */
    private double calculatePhiH(BCFLCalculator calculator) {
        if (gamma == null || gamma.y == null || psi == null || psi.z == null) {
            return 0.0;
        }

        double phiH = 0.0;
        int I = gamma.y.length;  // 客户区数量
        int N = 2;               // 包裹类型数量（固定为2）

        // 计算Σ_i Σ_n D_{in}^F(y^h, z^h)
        for (int i = 0; i < I; i++) {
            for (int n = 0; n < N; n++) {
                double D_inF = calculator.calculateD_inF(i, n, gamma.y, psi.z);
                phiH += D_inF;
            }
        }

        return phiH;
    }

    // ==================== Getters ====================

    /**
     * 获取Gamma解：领导者完整解
     * @return Gamma γ^h
     */
    public Gamma getGamma() {
        return gamma;
    }

    /**
     * 获取Psi解：追随者完整解
     * @return Psi ψ^h
     */
    public Psi getPsi() {
        return psi;
    }

    /**
     * 获取vL解：电商零售店选址（快捷访问）
     * @return double[] vL^h[j]
     */
    public double[] getVL() {
        return (gamma != null) ? gamma.vL : null;
    }

    /**
     * 获取vP解：充电站选址（快捷访问）
     * @return double[] vP^h[j]
     */
    public double[] getVP() {
        return (gamma != null) ? gamma.vP : null;
    }

    /**
     * 获取x解：设施-服务分配（快捷访问）
     * @return double[][] x^h[j][s]
     */
    public double[][] getX() {
        return (gamma != null) ? gamma.x : null;
    }

    /**
     * 获取y解：客户服务选择（快捷访问）
     * @return double[][] y^h[i][s]
     */
    public double[][] getY() {
        return (gamma != null) ? gamma.y : null;
    }

    /**
     * 获取vF解：传统零售店选址（快捷访问）
     * @return double[] vF^h[j]
     */
    public double[] getVF() {
        return (psi != null) ? psi.vF : null;
    }

    /**
     * 获取z解：折扣选择（快捷访问）
     * @return double[][] z^h[j][k]
     */
    public double[][] getZ() {
        return (psi != null) ? psi.z : null;
    }

    /**
     * 获取Π^h值：领导者市场份额
     * @return double Π^h
     */
    public double getPiH() {
        return PiH;
    }

    /**
     * 获取Φ^h值：追随者市场份额
     * @return double Φ^h
     */
    public double getPhiH() {
        return PhiH;
    }
}
