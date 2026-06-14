package run;

import input.Data;
import input.Input;
import sp.BCFLSubProblem;
import pool.Gamma;
import pool.Psi;
import pool.FeasibleSolution;
import utils.BCFLCalculator;
import ilog.concert.IloException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 独立子问题求解器
 * 用法: java run.SolveSPWithGamma <gamma文件路径> <data文件路径> <输出psi路径>
 *
 * 功能:
 *   1. 从gamma文件解析领导者解 (vL, vP, x, y)
 *   2. 加载data文件获取问题参数
 *   3. 求解子问题获取追随者最优响应 psi = (vF, z)
 *   4. 以人类可读格式输出psi到文件
 */
public class SolveSPWithGamma {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("用法: java run.SolveSPWithGamma <gamma文件路径> <data文件路径> <输出psi路径>");
            System.exit(1);
        }

        String gammaFilePath = args[0];
        String dataFilePath = args[1];
        String outputFilePath = args[2];

        try {
            // 1. 加载数据
            Input input = new Input(dataFilePath);
            Data data = input.getData();

            // 2. 解析gamma文件（处理文件头，提取gamma段）
            Gamma gamma = parseGammaFile(gammaFilePath, data);

            // 3. 求解子问题
            BCFLSubProblem CSP = new BCFLSubProblem(data);
            CSP.solve_Model(gamma);
            Psi psi = CSP.getPsi();
            CSP.close();

            // 4. 计算 Pi(gamma, psi) 和 Phi(gamma, psi)
            BCFLCalculator calculator = new BCFLCalculator(data);
            FeasibleSolution sol = new FeasibleSolution(gamma, psi, calculator);

            // 5. 输出psi（含Pi和Phi）
            writePsiToFile(psi, outputFilePath, data, sol.PiH, sol.PhiH);

            System.out.println("OK: " + outputFilePath);

        } catch (IloException e) {
            System.err.println("CPLEX求解错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
            System.exit(3);
        }
    }

    /**
     * 解析gamma文件，自动处理含有"=== 最优解对"等头部的文件
     */
    private static Gamma parseGammaFile(String filePath, Data data) throws IOException {
        // 读取文件全部内容
        String content = Files.readString(Path.of(filePath));

        // 找到gamma段的起始位置: "vL解" 或 "=== gamma*"
        int startIdx = -1;
        int vlIdx = content.indexOf("vL解");
        int gammaIdx = content.indexOf("=== gamma*");

        if (vlIdx >= 0 && (gammaIdx < 0 || vlIdx < gammaIdx)) {
            startIdx = vlIdx;
        } else if (gammaIdx >= 0) {
            // 找到 "=== gamma*" 之后的 "vL解"
            int vlAfterGamma = content.indexOf("vL解", gammaIdx);
            startIdx = vlAfterGamma >= 0 ? vlAfterGamma : gammaIdx;
        }

        if (startIdx < 0) {
            // 没有找到标记，尝试直接读取（文件可能本身就是纯gamma格式）
            return Gamma.readFromFile(filePath, data);
        }

        // 找到gamma段的结束位置: "=== psi*" 或文件末尾
        int endIdx = content.indexOf("=== psi*", startIdx);
        if (endIdx < 0) {
            endIdx = content.indexOf("======================", startIdx + 50);
        }
        if (endIdx < 0) {
            endIdx = content.length();
        }

        String gammaSection = content.substring(startIdx, endIdx).trim();

        // 写入临时文件
        Path tempFile = Files.createTempFile("gamma_", ".txt");
        Files.writeString(tempFile, gammaSection);

        try {
            return Gamma.readFromFile(tempFile.toString(), data);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * 以人类可读格式输出psi到文件，含Pi和Phi
     */
    private static void writePsiToFile(Psi psi, String filePath, Data data, double piH, double phiH) throws IOException {
        int J = data.getJ();
        int K_f = data.getK_f();

        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            // Pi和Phi
            writer.printf("Pi(gamma, psi) = %.6f%n", piH);
            writer.printf("Phi(gamma, psi) = %.6f%n", phiH);
            writer.println();

            // vF解
            writer.println("vF解 (传统零售商选择):");
            for (int j = 0; j < J; j++) {
                writer.printf("  传统零售商 %d: %.1f ", j, psi.vF[j]);
                if ((j + 1) % 5 == 0) writer.println();
            }
            writer.println();

            // z解
            writer.println("z解 (传统零售商折扣选择):");
            for (int j = 0; j < J; j++) {
                writer.printf("  零售商 %d: ", j);
                for (int k = 0; k < K_f; k++) {
                    writer.printf("z[%d][%d]=%.1f ", j, k, psi.z[j][k]);
                }
                writer.println();
            }
        }
    }
}
