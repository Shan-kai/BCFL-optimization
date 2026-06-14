package test;

import input.Data;
import input.Input;
import utils.BCFLCalculator;

/**
 * 偏导数公式数值验证测试
 * 使用有限差分法验证解析梯度的正确性
 */
public class GradientValidationTest {

    private Data data;
    private BCFLCalculator calculator;
    private double epsilon = 1e-6;  // 有限差分步长
    private double tolerance = 1e-4; // 容差

    public static void main(String[] args) {
        GradientValidationTest test = new GradientValidationTest();
        test.runAllTests();
    }

    public void runAllTests() {
        System.out.println("========================================");
        System.out.println("    偏导数公式数值验证测试");
        System.out.println("========================================\n");

        // 加载数据
        try {
            Input input = new Input("data.txt");
            data = input.getData();
            calculator = new BCFLCalculator(data);
            calculator.initializeCache();
            System.out.println("数据加载成功:");
            System.out.printf("  - 客户区 I = %d\n", data.getI());
            System.out.printf("  - 候选点 J = %d\n", data.getJ());
            System.out.printf("  - 服务 S = %d, K_f = %d\n",
                    data.getS(), data.getK_f());
            System.out.printf("  - 包裹类型 N = %d\n\n", data.getN());
        } catch (Exception e) {
            System.err.println("数据加载失败: " + e.getMessage());
            return;
        }

        // 运行四个偏导数测试
        testPartialD_in_wrt_y();
        testPartialD_in_wrt_z();
        testPartialD_inF_wrt_y();
        testPartialD_inF_wrt_z();

        System.out.println("\n========================================");
        System.out.println("    所有测试完成!");
        System.out.println("========================================");
    }

    /**
     * 测试 ∂D_in/∂y_isk
     */
    private void testPartialD_in_wrt_y() {
        System.out.println("----------------------------------------");
        System.out.println("测试 1: ∂D_in/∂y_isk");
        System.out.println("----------------------------------------");

        int testCases = 10;
        int passed = 0;

        for (int t = 0; t < testCases; t++) {
            // 随机选择测试点
            int i = (int) (Math.random() * data.getI());
            int n = (int) (Math.random() * data.getN());
            int s = (int) (Math.random() * data.getS());

            // 生成随机解
            double[][] y = generateRandomY();
            double[][] z = generateRandomZ();

            // 解析梯度
            double analytical = calculator.calculatePartialD_in_wrt_y(i, n, s, y, z);

            // 数值梯度 (有限差分)
            double numerical = numericalPartialD_in_wrt_y(i, n, s, y, z);

            // 比较
            double error = Math.abs(analytical - numerical);
            double relError = Math.abs(error / (numerical + 1e-10));
            boolean pass = error < tolerance || relError < tolerance;

            if (pass) passed++;

            System.out.printf("  测试 %d (i=%d,n=%d,s=%d):\n", t+1, i, n, s);
            System.out.printf("    解析梯度 = %.10f\n", analytical);
            System.out.printf("    数值梯度 = %.10f\n", numerical);
            System.out.printf("    绝对误差 = %.10f, 相对误差 = %.10f %s\n\n",
                    error, relError, pass ? "✓" : "✗");
        }

        System.out.printf("结果: %d/%d 通过\n\n", passed, testCases);
    }

    /**
     * 测试 ∂D_in/∂z_jk
     */
    private void testPartialD_in_wrt_z() {
        System.out.println("----------------------------------------");
        System.out.println("测试 2: ∂D_in/∂z_jk");
        System.out.println("----------------------------------------");

        int testCases = 5;
        int passed = 0;

        for (int t = 0; t < testCases; t++) {
            int i = (int) (Math.random() * data.getI());
            int n = (int) (Math.random() * data.getN());
            int j = (int) (Math.random() * data.getJ());
            int k = (int) (Math.random() * data.getK_f());

            double[][] y = generateRandomY();
            double[][] z = generateRandomZ();

            double analytical = calculator.calculatePartialD_in_wrt_z(i, n, j, k, y, z);
            double numerical = numericalPartialD_in_wrt_z(i, n, j, k, y, z);

            double error = Math.abs(analytical - numerical);
            double relError = Math.abs(error / (numerical + 1e-10));
            boolean pass = error < tolerance || relError < tolerance;

            if (pass) passed++;

            System.out.printf("  测试 %d (i=%d,n=%d,j=%d,k=%d):\n", t+1, i, n, j, k);
            System.out.printf("    解析梯度 = %.10f\n", analytical);
            System.out.printf("    数值梯度 = %.10f\n", numerical);
            System.out.printf("    绝对误差 = %.10f, 相对误差 = %.10f %s\n\n",
                    error, relError, pass ? "✓" : "✗");
        }

        System.out.printf("结果: %d/%d 通过\n\n", passed, testCases);
    }

    /**
     * 测试 ∂D_in^F/∂y_isk
     */
    private void testPartialD_inF_wrt_y() {
        System.out.println("----------------------------------------");
        System.out.println("测试 3: ∂D_in^F/∂y_isk");
        System.out.println("----------------------------------------");

        int testCases = 5;
        int passed = 0;

        for (int t = 0; t < testCases; t++) {
            int i = (int) (Math.random() * data.getI());
            int n = (int) (Math.random() * data.getN());
            int s = (int) (Math.random() * data.getS());

            double[][] y = generateRandomY();
            double[][] z = generateRandomZ();

            double analytical = calculator.calculatePartialD_inF_wrt_y(i, n, s, y, z);
            double numerical = numericalPartialD_inF_wrt_y(i, n, s, y, z);

            double error = Math.abs(analytical - numerical);
            double relError = Math.abs(error / (numerical + 1e-10));
            boolean pass = error < tolerance || relError < tolerance;

            if (pass) passed++;

            System.out.printf("  测试 %d (i=%d,n=%d,s=%d):\n", t+1, i, n, s);
            System.out.printf("    解析梯度 = %.10f\n", analytical);
            System.out.printf("    数值梯度 = %.10f\n", numerical);
            System.out.printf("    绝对误差 = %.10f, 相对误差 = %.10f %s\n\n",
                    error, relError, pass ? "✓" : "✗");
        }

        System.out.printf("结果: %d/%d 通过\n\n", passed, testCases);
    }

    /**
     * 测试 ∂D_in^F/∂z_jk
     */
    private void testPartialD_inF_wrt_z() {
        System.out.println("----------------------------------------");
        System.out.println("测试 4: ∂D_in^F/∂z_jk");
        System.out.println("----------------------------------------");

        int testCases = 5;
        int passed = 0;

        for (int t = 0; t < testCases; t++) {
            int i = (int) (Math.random() * data.getI());
            int n = (int) (Math.random() * data.getN());
            int j = (int) (Math.random() * data.getJ());
            int k = (int) (Math.random() * data.getK_f());

            double[][] y = generateRandomY();
            double[][] z = generateRandomZ();

            double analytical = calculator.calculatePartialD_inF_wrt_z(i, n, j, k, y, z);
            double numerical = numericalPartialD_inF_wrt_z(i, n, j, k, y, z);

            double error = Math.abs(analytical - numerical);
            double relError = Math.abs(error / (numerical + 1e-10));
            boolean pass = error < tolerance || relError < tolerance;

            if (pass) passed++;

            System.out.printf("  测试 %d (i=%d,n=%d,j=%d,k=%d):\n", t+1, i, n, j, k);
            System.out.printf("    解析梯度 = %.10f\n", analytical);
            System.out.printf("    数值梯度 = %.10f\n", numerical);
            System.out.printf("    绝对误差 = %.10f, 相对误差 = %.10f %s\n\n",
                    error, relError, pass ? "✓" : "✗");
        }

        System.out.printf("结果: %d/%d 通过\n\n", passed, testCases);
    }

    // ========== 数值梯度计算方法 (有限差分) ==========

    private double numericalPartialD_in_wrt_y(int i, int n, int s, double[][] y, double[][] z) {
        double[][] yPlus = copyY(y);
        double[][] yMinus = copyY(y);

        yPlus[i][s] += epsilon;
        yMinus[i][s] -= epsilon;

        double U_in_plus = calculator.calculateU_in(i, n, yPlus, z);
        double U_in_minus = calculator.calculateU_in(i, n, yMinus, z);

        double D_in_plus = calculator.calculateD_in(i, n, U_in_plus);
        double D_in_minus = calculator.calculateD_in(i, n, U_in_minus);

        return (D_in_plus - D_in_minus) / (2 * epsilon);
    }

    private double numericalPartialD_in_wrt_z(int i, int n, int j, int k, double[][] y, double[][] z) {
        double[][] zPlus = copyZ(z);
        double[][] zMinus = copyZ(z);

        zPlus[j][k] += epsilon;
        zMinus[j][k] -= epsilon;

        double U_in_plus = calculator.calculateU_in(i, n, y, zPlus);
        double U_in_minus = calculator.calculateU_in(i, n, y, zMinus);

        double D_in_plus = calculator.calculateD_in(i, n, U_in_plus);
        double D_in_minus = calculator.calculateD_in(i, n, U_in_minus);

        return (D_in_plus - D_in_minus) / (2 * epsilon);
    }

    private double numericalPartialD_inF_wrt_y(int i, int n, int s, double[][] y, double[][] z) {
        double[][] yPlus = copyY(y);
        double[][] yMinus = copyY(y);

        yPlus[i][s] += epsilon;
        yMinus[i][s] -= epsilon;

        double D_inF_plus = calculator.calculateD_inF(i, n, yPlus, z);
        double D_inF_minus = calculator.calculateD_inF(i, n, yMinus, z);

        return (D_inF_plus - D_inF_minus) / (2 * epsilon);
    }

    private double numericalPartialD_inF_wrt_z(int i, int n, int j, int k, double[][] y, double[][] z) {
        double[][] zPlus = copyZ(z);
        double[][] zMinus = copyZ(z);

        zPlus[j][k] += epsilon;
        zMinus[j][k] -= epsilon;

        double D_inF_plus = calculator.calculateD_inF(i, n, y, zPlus);
        double D_inF_minus = calculator.calculateD_inF(i, n, y, zMinus);

        return (D_inF_plus - D_inF_minus) / (2 * epsilon);
    }

    // ========== 辅助方法 ==========

    private double[][] generateRandomY() {
        int I = data.getI();
        int S = data.getS();

        double[][] y = new double[I][S];
        for (int i = 0; i < I; i++) {
            for (int s = 0; s < S; s++) {
                // 生成0-1之间的随机数，模拟松弛后的y值
                y[i][s] = Math.random();
            }
        }
        return y;
    }

    private double[][] generateRandomZ() {
        int J = data.getJ();
        int K_f = data.getK_f();

        double[][] z = new double[J][K_f];
        for (int j = 0; j < J; j++) {
            for (int k = 0; k < K_f; k++) {
                z[j][k] = Math.random();
            }
        }
        return z;
    }

    private double[][] copyY(double[][] y) {
        int I = y.length;
        int S = y[0].length;

        double[][] copy = new double[I][S];
        for (int i = 0; i < I; i++) {
            System.arraycopy(y[i], 0, copy[i], 0, S);
        }
        return copy;
    }

    private double[][] copyZ(double[][] z) {
        int J = z.length;
        int K_f = z[0].length;

        double[][] copy = new double[J][K_f];
        for (int j = 0; j < J; j++) {
            System.arraycopy(z[j], 0, copy[j], 0, K_f);
        }
        return copy;
    }
}
