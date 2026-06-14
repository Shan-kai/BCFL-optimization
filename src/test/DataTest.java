package test;

import input.Data;
import input.Input;
import utils.BCFLCalculator;

/**
 * 数据读取测试类
 * 用于验证数据文件读取和解析是否正确
 */
public class DataTest {
    
    public static void main(String[] args) {
        System.out.println("=== 数据读取测试开始 ===");
        
        try {
            // 读取数据
            System.out.println("正在读取数据文件...");
            Input input = new Input("data.txt");
            Data data = input.getData();
            
            System.out.println("数据读取成功！");
            System.out.println();

            // 测试J_f节点数据
            //testJ_fNodesData(data);

            testCalculateU_sk(data);
            //testCalculateU_ijk(data);
            testCalculateUtilityComponents(data);
            // 测试服务数据
            testServiceData(data);
            
            // 测试其他数据
            testOtherData(data);
            
            System.out.println("=== 数据读取测试完成 ===");
            
        } catch (Exception e) {
            System.err.println("数据读取测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 测试J_f节点数据
     */
    // private static void testJ_fNodesData(Data data) {
    //     System.out.println("=== J_f节点数据测试 ===");

    //     var j_fNodes = data.getJ_fNodes();
    //     System.out.println("J_f节点总数: " + data.getJ_f());
    //     System.out.println();

    //     for (int i = 0; i < Math.min(5, j_fNodes.size()); i++) {
    //         var jfNode = j_fNodes.get(i);
    //         System.out.println("J_f节点 " + i + ": " + jfNode.toString());
    //         System.out.println("  l_j = " + jfNode.getCF_j());

    //         System.out.print("  theta_jk = [");
    //         for (int k = 0; k < 5; k++) {
    //             System.out.print(jfNode.getTheta_jk(k));
    //             if (k < 4) System.out.print(", ");
    //         }
    //         System.out.println("]");

    //         System.out.print("  f_jk = [");
    //         for (int k = 0; k < 5; k++) {
    //             System.out.print(jfNode.get_f_jk(k));
    //             if (k < 4) System.out.print(", ");
    //         }
    //         System.out.println("]");
    //         System.out.println();
    //     }
    // }

    /**
     * 测试计算服务效用(已检验，无误)
     */
    private static void testCalculateU_sk(Data data) {
        System.out.println("=== 计算服务效用测试 ===");
        
        BCFLCalculator calculator = new BCFLCalculator(data);
        
        // 打印所有服务的效用值
        for (int s = 0; s < data.getS(); s++) {
            double u_sk = calculator.calculateU_sk(s);
            System.out.println("服务 " + s + ": u_" + s + " = " + String.format("%.4f", u_sk));
        }
        System.out.println();
    }

     /**
      * 测试计算传统零售商效用
      */
     private static void testCalculateU_ijk(Data data) {
         System.out.println("=== 计算传统零售商效用测试 ===");
         System.out.println("β_{0} = " + data.getParams().getBeta_0());
         BCFLCalculator calculator = new BCFLCalculator(data);
         for (int i = 0; i < data.getI(); i++) {
             for (int j = 0; j < data.getJ(); j++) {
                 for (int k = 0; k < data.getK_f(); k++) {
                         double u_ijk = calculator.calculateU_ijk(i, j, k);
                         System.out.println(" u_{" + i + "," + j + "," + k + "} = " + String.format("%.4f", u_ijk));
                 }
             }
         }
     }
     
     /**
      * 测试计算效用组件（领导者部分、追随者部分、总效用）
      */
     private static void testCalculateUtilityComponents(Data data) {
         System.out.println("=== 计算效用组件测试 ===");
         
         BCFLCalculator calculator = new BCFLCalculator(data);
         
         // 创建示例的y和z矩阵
         double[][] y = new double[data.getI()][data.getS()];
         double[][] z = new double[data.getJ()][data.getK_f()];
         
         // 初始化y矩阵
         for (int i = 0; i < data.getI(); i++) {
             for (int s = 0; s < data.getS(); s++) {
                 y[i][s] = 1.0;
             }
         }

         // 初始化z矩阵（假设所有传统零售商都提供所有折扣等级）
         for (int j= 0; j < data.getJ(); j++) {
             for (int k = 0; k < data.getK_f(); k++) {
                 if(k == data.getK_f() - 1) {z[j][k] = 1.0;  // 使用最后一个索引
                 }else{
                    z[j][k] = 0.0;
                 };
             }
         }
         
         // 计算并打印效用组件
         for (int i = 0; i < Math.min(10, data.getI()); i++) { // 只测试前3个客户区
             for (int n = 0; n < data.getN(); n++) {
                 System.out.println(String.format("客户区 %d, 包裹类型 %d:", i, n));
                 
                 // 计算领导者部分
                 double leaderPart = calculator.calculateLeaderPart(i, n, y);
                 System.out.println(String.format("  领导者部分: %.6f", leaderPart));
                 
                 // 计算追随者部分
                 double followerPart = calculator.calculateFollowerPart(i,  z);
                 System.out.println(String.format("  追随者部分: %.6f", followerPart));
                 
                 // 计算总效用
                 double totalUtility = calculator.calculateU_in(i, n, y, z);
                 System.out.println(String.format("  总效用 U_in: %.6f", totalUtility));
                 
                 System.out.println();
             }
         }
     }
     
    /**
     * 测试服务数据(已检验，无误)
     */
    private static void testServiceData(Data data) {
        System.out.println("=== 服务数据测试 ===");
        
        var services = data.getServices();
        System.out.println("服务总数: " + data.getS() );
        System.out.println();
        
        for (int i = 0; i < services.size(); i++) {
            var service = services.get(i);
            System.out.println("服务 " + (i + 1) + ":");
            // 按原始文件列顺序输出：s, beta0s, DTs, Q_s, r_s, a_sn0, a_sn1, F_s, alpha_sk[], G_sk[]
            System.out.println("  s = " + service.getS());
            System.out.println("  beta0s = " + service.getBeta_0s());
            System.out.println("  DTs = " + service.getDTs());
            System.out.println("  Q_s = " + service.getQs());
            System.out.println("  r_s = " + service.getR_s());
            System.out.println("  a_sn0 = " + service.getA_sn0());
            System.out.println("  a_sn1 = " + service.getA_sn1());
            System.out.println("  F_s = " + service.getFs());
            // alpha_sk and G_sk arrays removed in refactoring
            System.out.println();
        }
    }
    
    /**
     * 测试其他数据(已检验，无误)
     */
    private static void testOtherData(Data data) {
        System.out.println("=== 其他数据测试 ===");
        
        // 按原始文件节点区的顺序输出：J -> B -> I
        var eRetailers = data.getJNodes();
        System.out.println("电商候选点节点总数: " + data.getJ());
        for (int i = 0; i < Math.min(3, eRetailers.size()); i++) {
            var eRetailer = eRetailers.get(i);
            System.out.println("电商节点 " + (i+1) + ": " + eRetailer.toString());
        }
        System.out.println();
        // var tRetailers = data.getJ_fNodes();
        // System.out.println("传统零售店候选点节点总数: " + data.getJ());
        // for (int i = 0; i < Math.min(3, tRetailers.size()); i++) {
        //     var tRetailer = tRetailers.get(i);
        //     System.out.println("电商节点 " + (i+1) + ": " + tRetailer.toString());
        // }
        System.out.println();
        var existingRetailers = data.getJ_0Nodes();
        System.out.println("现存传统零售商节点总数: " + data.getJ_0());
        for (int i = 0; i < Math.min(3, existingRetailers.size()); i++) {
            var existingRetailer = existingRetailers.get(i);
            System.out.println("现存传统零售商节点 " + (i+1) + ": " + existingRetailer.toString());
        }
        System.out.println();
        var customers = data.getINodes();
        System.out.println("客户区总数: " + data.getI());
        for (int i = 0; i < Math.min(3, customers.size()); i++) {
            var customer = customers.get(i);
            System.out.println("客户区 " + (i+1) + ": " + customer.toString());
        }
        System.out.println();
        
        // 测试参数
        var params = data.getParams();
        System.out.println("参数信息:");
        System.out.println("  B = " + params.B_l);
        System.out.println("  b = " + params.B_f);
        System.out.println("  beta_05 = " + params.beta_0);
        System.out.println("  beta_tt = " + params.beta_tt);
        System.out.println("  beta_tc = " + params.beta_tc);
        System.out.println("  beta_dt = " + params.beta_dt);
        System.out.println("  beta_dc = " + params.beta_dc);
        System.out.println("  lambda_L = " + params.lambda_l);
        System.out.println("  lambda_F = " + params.lambda_f);
        System.out.println("  lambda = " + params.lambda);
        System.out.println();

    }
}