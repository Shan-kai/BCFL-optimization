package pool;

import input.Data;
import java.io.*;

/**
 * Gamma解结构：领导者（上层）完整决策变量
 * γ = (vL, vP, x, y)
 *
 * 根据PDF模型定义：
 * - vL[j]: 电商零售店选址 (binary)
 * - vP[j]: 充电站选址 (binary)
 * - x[j][s]: 设施j提供服务s (binary)
 * - y[i][s]: 客户区i选择服务s (binary)
 */
public class Gamma {
    public final double[] vL;           // vL[j]：电商零售店选址
    public final double[] vP;           // vP[j]：充电站选址
    public final double[][] x;          // x[j][s]：设施-服务分配
    public final double[][] y;          // y[i][s]：客户服务选择

    /**
     * 构造函数：创建Gamma解
     * @param vL 电商零售店选址 vL[j]
     * @param vP 充电站选址 vP[j]
     * @param x 设施-服务分配 x[j][s]
     * @param y 客户服务选择 y[i][s]
     */
    public Gamma(double[] vL, double[] vP, double[][] x, double[][] y) {
        // 深拷贝 vL
        this.vL = (vL != null) ? vL.clone() : null;

        // 深拷贝 vP
        this.vP = (vP != null) ? vP.clone() : null;

        // 深拷贝 x
        if (x != null) {
            this.x = new double[x.length][];
            for (int j = 0; j < x.length; j++) {
                this.x[j] = (x[j] != null) ? x[j].clone() : null;
            }
        } else {
            this.x = null;
        }

        // 深拷贝 y
        if (y != null) {
            this.y = new double[y.length][];
            for (int i = 0; i < y.length; i++) {
                this.y[i] = (y[i] != null) ? y[i].clone() : null;
            }
        } else {
            this.y = null;
        }
    }

    /**
     * 获取vL解：电商零售店选址
     * @return double[] vL[j]
     */
    public double[] getVL() {
        return vL;
    }

    /**
     * 获取vP解：充电站选址
     * @return double[] vP[j]
     */
    public double[] getVP() {
        return vP;
    }

    /**
     * 获取x解：设施-服务分配
     * @return double[][] x[j][s]
     */
    public double[][] getX() {
        return x;
    }

    /**
     * 获取y解：客户服务选择
     * @return double[][] y[i][s]
     */
    public double[][] getY() {
        return y;
    }

    /**
     * 判断两个Gamma解是否相等（重载，兼容旧代码）
     * @param other 另一个Gamma解
     * @return true如果所有变量都相等
     */
    public boolean equals(Gamma other) {
        if (other == null) return false;
        return arrayEquals(this.vL, other.vL)
            && arrayEquals(this.vP, other.vP)
            && array2DEquals(this.x, other.x)
            && array2DEquals(this.y, other.y);
    }

    /**
     * 重写Object.equals以支持HashSet去重
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        return equals((Gamma) obj);
    }

    /**
     * 重写hashCode以支持HashSet（基于数组内容）
     */
    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + java.util.Arrays.hashCode(vL);
        result = 31 * result + java.util.Arrays.hashCode(vP);
        result = 31 * result + java.util.Arrays.deepHashCode(x);
        result = 31 * result + java.util.Arrays.deepHashCode(y);
        return result;
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

    /**
     * 将Gamma解写入文件（稀疏格式，只保存非零值）
     * @param filename 文件名
     * @throws IOException 文件写入异常
     */
    public void writeToFile(String filename) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            // 写入文件头
            writer.println("# Gamma Solution File");
            writer.println("# Format: variable_name indices value");
            writer.println("# Only non-zero values are stored (sparse format)");
            writer.println();

            // 写入维度信息
            int J = vL.length;
            int I = (y != null) ? y.length : 0;
            int S = (y != null && y.length > 0) ? y[0].length : 0;
            writer.printf("DIMENSIONS J=%d I=%d S=%d%n", J, I, S);
            writer.println();

            // 写入vL（稀疏格式）
            writer.println("# vL[j] - Leader retail store locations");
            for (int j = 0; j < vL.length; j++) {
                if (Math.abs(vL[j]) > 1e-6) {
                    writer.printf("vL %d %.1f%n", j, vL[j]);
                }
            }
            writer.println();

            // 写入vP（稀疏格式）
            writer.println("# vP[j] - Charging station locations");
            for (int j = 0; j < vP.length; j++) {
                if (Math.abs(vP[j]) > 1e-6) {
                    writer.printf("vP %d %.1f%n", j, vP[j]);
                }
            }
            writer.println();

            // 写入x（稀疏格式）
            if (x != null) {
                writer.println("# x[j][s] - Facility-service assignments");
                for (int j = 0; j < x.length; j++) {
                    for (int s = 0; s < x[j].length; s++) {
                        if (Math.abs(x[j][s]) > 1e-6) {
                            writer.printf("x %d %d %.1f%n", j, s, x[j][s]);
                        }
                    }
                }
                writer.println();
            }

            // 写入y（稀疏格式）
            if (y != null) {
                writer.println("# y[i][s] - Customer service selections");
                for (int i = 0; i < y.length; i++) {
                    for (int s = 0; s < y[i].length; s++) {
                        if (Math.abs(y[i][s]) > 1e-6) {
                            writer.printf("y %d %d %.1f%n", i, s, y[i][s]);
                        }
                    }
                }
            }
        }
    }

    /**
     * 从文件读取Gamma解（自动识别格式）
     * 支持两种格式：
     * 1. 稀疏格式（机器格式）：DIMENSIONS, vL j value, ...
     * 2. 人类可读格式（打印格式）：vL解 (设施选址):, 设施 j: value, ...
     *
     * @param filename 文件名
     * @param data 数据对象，用于获取维度信息
     * @return 读取的Gamma对象
     * @throws IOException 文件读取异常
     */
    public static Gamma readFromFile(String filename, Data data) throws IOException {
        // 先读取第一行判断格式
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String firstLine = reader.readLine();
            if (firstLine == null) {
                throw new IOException("文件为空");
            }

            // 判断格式
            if (firstLine.trim().startsWith("vL解") || firstLine.trim().startsWith("设施")) {
                // 人类可读格式
                System.out.println("检测到人类可读格式，使用打印格式解析器");
                return readFromPrintFormat(filename, data);
            } else {
                // 稀疏格式
                System.out.println("检测到稀疏格式，使用稀疏格式解析器");
                return readFromSparseFormat(filename, data);
            }
        }
    }

    /**
     * 从稀疏格式文件读取Gamma解
     */
    private static Gamma readFromSparseFormat(String filename, Data data) throws IOException {
        int J = data.getJ();
        int I = data.getI();
        int S = data.getS();

        // 初始化所有数组为零
        double[] vL = new double[J];
        double[] vP = new double[J];
        double[][] x = new double[J][S-1];
        double[][] y = new double[I][S];

        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 跳过注释和空行
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;

                String varName = parts[0];

                try {
                    if (varName.equals("DIMENSIONS")) {
                        // 跳过维度行（仅用于验证）
                        continue;
                    } else if (varName.equals("vL")) {
                        int j = Integer.parseInt(parts[1]);
                        double value = Double.parseDouble(parts[2]);
                        if (j >= 0 && j < J) {
                            vL[j] = value;
                        }
                    } else if (varName.equals("vP")) {
                        int j = Integer.parseInt(parts[1]);
                        double value = Double.parseDouble(parts[2]);
                        if (j >= 0 && j < J) {
                            vP[j] = value;
                        }
                    } else if (varName.equals("x")) {
                        int j = Integer.parseInt(parts[1]);
                        int s = Integer.parseInt(parts[2]);
                        double value = Double.parseDouble(parts[3]);
                        if (j >= 0 && j < J && s >= 0 && s < (S-1)) {
                            x[j][s] = value;
                        }
                    } else if (varName.equals("y")) {
                        int i = Integer.parseInt(parts[1]);
                        int s = Integer.parseInt(parts[2]);
                        double value = Double.parseDouble(parts[3]);
                        if (i >= 0 && i < I && s >= 0 && s < S) {
                            y[i][s] = value;
                        }
                    }
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    System.err.println("警告：无法解析行: " + line);
                }
            }
        }

        return new Gamma(vL, vP, x, y);
    }

    /**
     * 从人类可读格式（打印格式）读取Gamma解
     * 格式示例：
     *   vL解 (设施选址):
     *     设施 0: 0.0   设施 1: 1.0
     *   x解 (设施服务分配):
     *     设施 0: x[0][0]=0.0 x[0][1]=1.0
     */
    private static Gamma readFromPrintFormat(String filename, Data data) throws IOException {
        int J = data.getJ();
        int I = data.getI();
        int S = data.getS();

        // 初始化所有数组为零
        double[] vL = new double[J];
        double[] vP = new double[J];
        double[][] x = new double[J][S-1];
        double[][] y = new double[I][S];

        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            String currentSection = null;  // 当前解析的部分：vL, vP, x, y

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 识别节标题
                if (line.startsWith("vL解")) {
                    currentSection = "vL";
                    continue;
                } else if (line.startsWith("vP解")) {
                    currentSection = "vP";
                    continue;
                } else if (line.startsWith("x解")) {
                    currentSection = "x";
                    continue;
                } else if (line.startsWith("y解")) {
                    currentSection = "y";
                    continue;
                }

                // 解析数据行
                try {
                    if (currentSection == null) continue;

                    if (currentSection.equals("vL")) {
                        parseVLLine(line, vL);
                    } else if (currentSection.equals("vP")) {
                        parseVPLine(line, vP);
                    } else if (currentSection.equals("x")) {
                        parseXLine(line, x);
                    } else if (currentSection.equals("y")) {
                        parseYLine(line, y);
                    }
                } catch (Exception e) {
                    System.err.println("警告：解析行失败: " + line + " - " + e.getMessage());
                }
            }
        }

        return new Gamma(vL, vP, x, y);
    }

    /**
     * 解析vL行：设施 0: 0.0   设施 1: 1.0
     */
    private static void parseVLLine(String line, double[] vL) {
        String[] tokens = line.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equals("设施")) {
                try {
                    int j = Integer.parseInt(tokens[i + 1].replace(":", ""));
                    double value = Double.parseDouble(tokens[i + 2]);
                    if (j >= 0 && j < vL.length) {
                        vL[j] = value;
                    }
                } catch (Exception e) {
                    // 跳过无法解析的部分
                }
            }
        }
    }

    /**
     * 解析vP行：换电站 0: 0.0   换电站 1: 1.0
     */
    private static void parseVPLine(String line, double[] vP) {
        String[] tokens = line.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equals("换电站")) {
                try {
                    int j = Integer.parseInt(tokens[i + 1].replace(":", ""));
                    double value = Double.parseDouble(tokens[i + 2]);
                    if (j >= 0 && j < vP.length) {
                        vP[j] = value;
                    }
                } catch (Exception e) {
                    // 跳过无法解析的部分
                }
            }
        }
    }

    /**
     * 解析x行：设施 0: x[0][0]=0.0 x[0][1]=1.0
     */
    private static void parseXLine(String line, double[][] x) {
        if (!line.startsWith("设施")) return;

        String[] tokens = line.split("\\s+");
        if (tokens.length < 2) return;

        try {
            int j = Integer.parseInt(tokens[1].replace(":", ""));
            if (j < 0 || j >= x.length) return;

            // 解析 x[j][s]=value 格式
            for (String token : tokens) {
                if (token.startsWith("x[")) {
                    int startIdx = token.indexOf('[');
                    int midIdx = token.indexOf("][");
                    int endIdx = token.indexOf("]=");

                    if (startIdx > 0 && midIdx > startIdx && endIdx > midIdx) {
                        int s = Integer.parseInt(token.substring(midIdx + 2, endIdx));
                        double value = Double.parseDouble(token.substring(endIdx + 2));

                        if (s >= 0 && s < x[j].length) {
                            x[j][s] = value;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("解析x行失败: " + line + " - " + e.getMessage());
        }
    }

    /**
     * 解析y行：客户区 0: y[0][0]=0.0 y[0][1]=1.0
     */
    private static void parseYLine(String line, double[][] y) {
        if (!line.startsWith("客户区")) return;

        String[] tokens = line.split("\\s+");
        if (tokens.length < 2) return;

        try {
            int i = Integer.parseInt(tokens[1].replace(":", ""));
            if (i < 0 || i >= y.length) return;

            // 解析 y[i][s]=value 格式
            for (String token : tokens) {
                if (token.startsWith("y[")) {
                    int idx1 = token.indexOf('[');
                    int idx2 = token.indexOf("][");
                    int idx3 = token.indexOf("]=");

                    if (idx1 > 0 && idx2 > idx1 && idx3 > idx2) {
                        int s = Integer.parseInt(token.substring(idx2 + 2, idx3));
                        double value = Double.parseDouble(token.substring(idx3 + 2));

                        if (s >= 0 && s < y[i].length) {
                            y[i][s] = value;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("解析y行失败: " + line + " - " + e.getMessage());
        }
    }
}
