package pool;

import java.util.ArrayList;
import java.util.List;
import utils.BCFLCalculator;
import input.Data;


/**
 * 解池类：存储主方法迭代产生的可行解，供主问题调用以添加逻辑切约束
 * 管理A集合：{(γ^h, ψ^h) | h=0,1,2,...,H}
 * 根据PDF第6页，A集合存储领导者完整解γ^h和追随者响应ψ^h的配对
 */
public class SolPool {
    // 储存每次迭代的可行解A集合
    private List<FeasibleSolution> feasibleSet = new ArrayList<>();

    // 迭代计数器
    private int iterationCount = 0;

    // 是否打印内部调试信息（例如添加/重复提示）
    private boolean verbose = true;

    // 计算器实例（用于计算Π^t）
    private BCFLCalculator calculator;

    // 数据实例（用于获取维度信息）
    private Data data;

    /**
     * 构造函数：初始化解池和计算器
     * @param data 全局数据对象
     */
    public SolPool(Data data) {
        this.data = data;
        this.calculator = new BCFLCalculator(data);
    }

    /**
     * 控制是否打印SolPool的调试/信息输出
     * @param verbose true 表示打印，false 表示静默
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * 获取所有可行解A集合
     * @return List<FeasibleSolution> 所有历史可行解
     */
    public List<FeasibleSolution> getFeasibleSet() {
        return feasibleSet;
    }

    /**
     * 获取指定迭代t的可行解
     * @param t 迭代索引
     * @return FeasibleSolution 第t次迭代的可行解
     */
    public FeasibleSolution getFeasibleSolution(int t) {
        if (t >= 0 && t < feasibleSet.size()) {
            return feasibleSet.get(t);
        }
        return null;
    }

    /**
     * 获取指定迭代h的Gamma解
     * @param h 迭代索引
     * @return Gamma γ^h
     */
    public Gamma getGamma(int h) {
        FeasibleSolution sol = getFeasibleSolution(h);
        return (sol != null) ? sol.getGamma() : null;
    }

    /**
     * 获取指定迭代h的Psi解
     * @param h 迭代索引
     * @return Psi ψ^h
     */
    public Psi getPsi(int h) {
        FeasibleSolution sol = getFeasibleSolution(h);
        return (sol != null) ? sol.getPsi() : null;
    }

    /**
     * 获取指定迭代h的vL解
     * @param h 迭代索引
     * @return double[] vL^h[j]
     */
    public double[] getVL(int h) {
        FeasibleSolution sol = getFeasibleSolution(h);
        return (sol != null) ? sol.getVL() : null;
    }

    /**
     * 获取指定迭代h的vP解
     * @param h 迭代索引
     * @return double[] vP^h[j]
     */
    public double[] getVP(int h) {
        FeasibleSolution sol = getFeasibleSolution(h);
        return (sol != null) ? sol.getVP() : null;
    }

    /**
     * 获取指定迭代h的x解
     * @param h 迭代索引
     * @return double[][] x^h[j][s]
     */
    public double[][] getX(int h) {
        FeasibleSolution sol = getFeasibleSolution(h);
        return (sol != null) ? sol.getX() : null;
    }

    /**
     * 获取指定迭代h的y解
     * @param h 迭代索引
     * @return double[][] y^h[i][s]
     */
    public double[][] getY(int h) {
        FeasibleSolution sol = getFeasibleSolution(h);
        return (sol != null) ? sol.getY() : null;
    }

    /**
     * 获取指定迭代h的vF解
     * @param h 迭代索引
     * @return double[] vF^h[j]
     */
    public double[] getVF(int h) {
        FeasibleSolution sol = getFeasibleSolution(h);
        return (sol != null) ? sol.getVF() : null;
    }

    /**
     * 获取指定迭代t的z解
     * @param t 迭代索引
     * @return double[][] z^t[j][k]
     */
    public double[][] getZ(int t) {
        FeasibleSolution sol = getFeasibleSolution(t);
        return (sol != null) ? sol.getZ() : null;
    }

    /**
     * 获取指定迭代t的目标函数值Π^t
     * @param t 迭代索引
     * @return double Π^t = Σ_i Σ_n D_{in}^L
     */
    public double getPiT(int t) {
        FeasibleSolution sol = getFeasibleSolution(t);
        return (sol != null) ? sol.getPiH() : 0.0;
    }

    /**
     * 获取所有可行解中的最大Φ^h值
     * @return double max{Φ^h}
     */
    public double getMaxPhiH() {
        double maxPhiH = 0.0;
        for (FeasibleSolution sol : feasibleSet) {
            if (sol.getPhiH() > maxPhiH) {
                maxPhiH = sol.getPhiH();
            }
        }
        return maxPhiH;
    }

    /**
     * 获取当前迭代次数
     * @return int 迭代次数
     */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * 获取可行解集合大小
     * @return int A集合中可行解的数量
     */
    public int getSize() {
        return feasibleSet.size();
    }

    /**
     * 清空解池（用于重新开始算法）
     */
    public void clear() {
        feasibleSet.clear();
        iterationCount = 0;
    }

    /**
     * 检查解池是否为空
     * @return boolean 是否为空
     */
    public boolean isEmpty() {
        return feasibleSet.isEmpty();
    }

    /**
     * 添加可行解到集合A（步骤12）
     * 添加领导者完整解γ^h和追随者响应ψ^h的配对，通过计算器计算PiH
     * @param gamma γ^h领导者完整解
     * @param psi ψ^h追随者完整解
     * @return boolean 是否成功添加（false表示重复解）
     */
    public boolean addFeasibleSolution(Gamma gamma, Psi psi) {
        // 检查是否已存在
        if (containsSolution(gamma, psi)) {
            if (verbose) System.out.println("警告：可行解已存在于A集合中，跳过添加");
            return false;
        }

        // 使用计算器计算PiH并创建可行解
        FeasibleSolution feasibleSol = new FeasibleSolution(gamma, psi, calculator);
        feasibleSet.add(feasibleSol);
        iterationCount++;

        if (verbose) {
            System.out.printf("已添加可行解到A集合，当前A集合大小: %d, Π^h=%.6f, Φ^h=%.6f\n",
                             feasibleSet.size(), feasibleSol.getPiH(), feasibleSol.getPhiH());
        }
        return true;
    }

    /**
     * 检查A集合中是否已存在相同的(γ,ψ)解
     * @param gamma 领导者完整解
     * @param psi 追随者响应
     * @return boolean 是否已存在
     */
    private boolean containsSolution(Gamma gamma, Psi psi) {
        for (FeasibleSolution existingSol : feasibleSet) {
            if (existingSol.getGamma().equals(gamma) && existingSol.getPsi().equals(psi)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 向后兼容的方法（已弃用） ====================

    /**
     * @deprecated 使用 getY(int h) 替代
     */
    @Deprecated
    public List<double[][]> getYList() {
        List<double[][]> yList = new ArrayList<>();
        for (FeasibleSolution sol : feasibleSet) {
            yList.add(sol.getY());
        }
        return yList;
    }

    /**
     * @deprecated 使用 getZ(int h) 替代
     */
    @Deprecated
    public List<double[][]> getZList() {
        List<double[][]> zList = new ArrayList<>();
        for (FeasibleSolution sol : feasibleSet) {
            zList.add(sol.getZ());
        }
        return zList;
    }
}
