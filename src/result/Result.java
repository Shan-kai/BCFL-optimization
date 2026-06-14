package result;

import input.Data;
import mp.BCFLMasterProblem;
import sp.BCFLSubProblem;

public class Result {
    private Data data;
    
    public Result(Data data) {
        this.data = data;
    }
    
    public void print(int iteration, BCFLMasterProblem MP, BCFLSubProblem CSP, double UB, double LB) {
        System.out.println("=== 迭代 " + iteration + " ===");
        System.out.println("上界 UB = " + UB);
        System.out.println("下界 LB = " + LB);
        System.out.println("间隙 = " + (UB - LB));
        System.out.println("相对间隙 = " + (Math.abs(UB - LB) / UB * 100) + "%");
        System.out.println();
    }
}
