package pool;

/**
 * Psi解结构：追随者（下层）完整决策变量
 * ψ = (vF, z)
 *
 * 根据PDF模型定义：
 * - vF[j]: 传统零售店选址 (binary)
 * - z[j][k]: 店铺j选择折扣等级k (binary)
 */
public class Psi {
    public final double[] vF;           // vF[j]：传统零售店选址
    public final double[][] z;          // z[j][k]：折扣选择

    /**
     * 构造函数：创建Psi解
     * @param vF 传统零售店选址 vF[j]
     * @param z 折扣选择 z[j][k]
     */
    public Psi(double[] vF, double[][] z) {
        // 深拷贝 vF
        this.vF = (vF != null) ? vF.clone() : null;

        // 深拷贝 z
        if (z != null) {
            this.z = new double[z.length][];
            for (int j = 0; j < z.length; j++) {
                this.z[j] = (z[j] != null) ? z[j].clone() : null;
            }
        } else {
            this.z = null;
        }
    }
    
    /**
     * 判断两个Psi解是否相等
     * @param other 另一个Psi解
     * @return true如果所有变量都相等
     */
    public boolean equals(Psi other) {
        if (other == null) return false;

        // 比较vF
        if (!arrayEquals(this.vF, other.vF)) return false;

        // 比较z
        if (!array2DEquals(this.z, other.z)) return false;

        return true;
    }

    /**
     * 比较两个1维数组是否相等
     */
    private boolean arrayEquals(double[] arr1, double[] arr2) {
        if (arr1 == null && arr2 == null) return true;
        if (arr1 == null || arr2 == null) return false;
        if (arr1.length != arr2.length) return false;

        for (int i = 0; i < arr1.length; i++) {
            if (Math.abs(arr1[i] - arr2[i]) > 1e-6) {
                return false;
            }
        }
        return true;
    }

    /**
     * 比较两个2维数组是否相等
     */
    private boolean array2DEquals(double[][] arr1, double[][] arr2) {
        if (arr1 == null && arr2 == null) return true;
        if (arr1 == null || arr2 == null) return false;
        if (arr1.length != arr2.length) return false;

        for (int i = 0; i < arr1.length; i++) {
            if (!arrayEquals(arr1[i], arr2[i])) {
                return false;
            }
        }
        return true;
    }
}
