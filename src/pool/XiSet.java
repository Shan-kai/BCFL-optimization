package pool;

import java.util.ArrayList;
import java.util.List;
import input.Data;

/**
 * Ξ集合管理类：存储历史ψ^h解（追随者解的可行域限制）
 * Ξ = {ψ^h | h=0,1,2,...,H}，其中ψ^h = (vF^h, z^h)
 * 用于限制追随者解空间，生成OA-cut约束
 *
 * 注意：Ξ集合是BCFLMasterProblem内部使用，用于tau变量的OA约束
 */
public class XiSet {
    // 存储历史ψ^h解的集合
    private List<Psi> xiSet;

    // 迭代计数器
    private int iterationCount = 0;

    // 数据维度
    private int J;   // 候选点数量（上下层共用）
    private int K_f; // 折扣等级数量

    /**
     * 构造函数：初始化Ξ集合（默认添加ψ^0）
     * @param data 全局数据对象
     */
    public XiSet(Data data) {
        this.xiSet = new ArrayList<>();
        this.J = data.getJ();
        this.K_f = data.getK_f();
        initializeDefaultPsi();
    }

    /**
     * 初始化默认ψ^0解（全为0）- 简单初始化方式
     */
    private void initializeDefaultPsi() {
        double[][] defaultZ = new double[J][K_f];
        double[] defaultVF = new double[J];
    
        // === 设置 z ===
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                if ((j == 0 || j == 1 || j == 2) && k == 4) {
                    defaultZ[j][k] = 1.0;  // 候选点0~2，k4=1
                } else {
                    defaultZ[j][k] = 0.0;
                }
            }
        }
    
        // === vF 根据 z 自适配 ===
        for (int j = 0; j < J; j++) {
            double sumZ = 0.0;
            for (int k = 0; k < K_f; k++) {
                sumZ += defaultZ[j][k];
            }
            // 若你的模型里 vF 是连续变量，可直接赋 sumZ；
            // 若它应为0/1，可转化为逻辑值：
            defaultVF[j] = (sumZ > 1e-6) ? 1.0 : 0.0;
        }
    
        Psi defaultPsi = new Psi(defaultVF, defaultZ);
        xiSet.add(defaultPsi);
        iterationCount++;
    }


    /**
     * 添加新的ψ^h解到Ξ集合
     * @param psi 新的ψ^h解
     * @return boolean 是否成功添加（false表示重复解）
     */
    public boolean addXiSet(Psi psi) {
        // 检查是否已存在
        if (contains(psi)) {
            System.out.println("警告：ψ解已存在于Ξ集合中，跳过添加");
            return false;
        }

        // 验证维度
        if (psi.z != null && (psi.z.length != J || psi.z[0].length != K_f)) {
            throw new IllegalArgumentException("ψ^h的z维度不匹配: 期望[" + J + "][" + K_f + "], 实际[" + psi.z.length + "][" + psi.z[0].length + "]");
        }

        xiSet.add(psi);
        iterationCount++;
        return true;
    }

    /**
     * 获取所有ψ^h解的Ξ集合
     * @return List<Psi> 所有历史ψ^h解
     */
    public List<Psi> getXiSet() {
        return xiSet;
    }

    /**
     * 获取指定迭代h的ψ^h解
     * @param h 迭代索引
     * @return Psi ψ^h解
     */
    public Psi getPsi(int h) {
        if (h >= 0 && h < xiSet.size()) {
            return xiSet.get(h);
        }
        return null;
    }

    /**
     * 获取指定迭代h的z^h解（向后兼容）
     * @param h 迭代索引
     * @return double[][] z^h[j][k]
     */
    public double[][] getZh(int h) {
        Psi psi = getPsi(h);
        return (psi != null) ? psi.z : null;
    }

    /**
     * 获取指定迭代h的vF^h解
     * @param h 迭代索引
     * @return double[] vF^h[j]
     */
    public double[] getVFh(int h) {
        Psi psi = getPsi(h);
        return (psi != null) ? psi.vF : null;
    }

    /**
     * 获取Ξ集合大小
     * @return int Ξ集合中ψ^h解的数量
     */
    public int getSize() {
        return xiSet.size();
    }

    /**
     * 获取当前迭代次数
     * @return int 迭代次数
     */
    public int getIterationCount() {
        return iterationCount;
    }

    /**
     * 检查Ξ集合是否为空
     * @return boolean 是否为空
     */
    public boolean isEmpty() {
        return xiSet.isEmpty();
    }

    /**
     * 清空Ξ集合（用于重新开始算法）
     */
    public void clear() {
        xiSet.clear();
        iterationCount = 0;
        initializeDefaultPsi(); // 重新添加默认ψ^0
    }

    /**
     * 清空Ξ集合并设置指定的初始ψ解（替代默认ψ^0）
     * @param initialPsi 用于初始化的ψ解
     */
    public void clearAndSetInitialPsi(Psi initialPsi) {
        xiSet.clear();
        iterationCount = 0;
        if (initialPsi != null) {
            xiSet.add(initialPsi);
            iterationCount++;
        }
    }

    /**
     * 获取候选点数量
     */
    public int getJ() {
        return J;
    }

    /**
     * 获取折扣等级数量
     */
    public int getK_f() {
        return K_f;
    }

    /**
     * 打印Ξ集合信息（用于调试）
     */
    public void printXiSet() {
        System.out.println("=== Ξ集合信息 ===");
        System.out.println("集合大小: " + getSize());
        System.out.println("迭代次数: " + getIterationCount());

        for (int h = 0; h < xiSet.size(); h++) {
            Psi psi = xiSet.get(h);
            System.out.println("\n--- ψ^" + h + " ---");

            // 打印vF
            if (psi.vF != null) {
                System.out.print("vF: ");
                for (int j = 0; j < J; j++) {
                    if (psi.vF[j] > 0.5) {
                        System.out.print("j" + j + "=1 ");
                    }
                }
                System.out.println();
            }

            // 打印z
            if (psi.z != null) {
                for (int j = 0; j < J; j++) {
                    boolean hasDiscount = false;
                    for (int k = 0; k < K_f; k++) {
                        if (psi.z[j][k] > 0.5) {
                            hasDiscount = true;
                            break;
                        }
                    }
                    if (hasDiscount) {
                        System.out.print("店铺 " + j + ": ");
                        for (int k = 0; k < K_f; k++) {
                            if (psi.z[j][k] > 0.5) {
                                System.out.print("k" + k + "=1 ");
                            }
                        }
                        System.out.println();
                    }
                }
            }
        }
    }

    /**
     * 检查ψ^h解是否已存在于Ξ集合中
     * @param psi 待检查的ψ^h解
     * @return boolean 是否已存在
     */
    public boolean contains(Psi psi) {
        for (Psi existingPsi : xiSet) {
            if (existingPsi.equals(psi)) {
                return true;
            }
        }
        return false;
    }

}
