package input;

import java.io.*;
import java.util.*;

public class Data {
    // Base Node class
    public static class Node {
        protected int id;
        protected int type;
        protected double x, y;
        
        public Node(int id, int type, double x, double y) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
        }
        
        // Getters
        public int getId() { return id; }
        public int getType() { return type; }
        public double getX() { return x; }
        public double getY() { return y; }
        
        // Setters
        public void setId(int id) { this.id = id; }
        public void setType(int type) { this.type = type; }
        public void setX(double x) { this.x = x; }
        public void setY(double y) { this.y = y; }
    }
    
    // J Nodes (E-retailers) with type = 1
    public static class JNodes extends Node {
        private double CL_j;           // 
        private double CP_j;           //
        private double CF_j;           // l_j (small l)
        private double[] theta_jk;    // theta_{jk0..4}
        private double[] f_jk;        // f_{jk0..4}
        
        
        public JNodes(int id, double x, double y, double CL_j, double CP_j,double CF_j,
        double[] theta_jk, double[] f_jk) {
            super(id, 1, x, y); // type = 1 for E-retailers
            this.CL_j = CL_j;
            this.CP_j = CP_j;
            this.CF_j = CF_j;
            this.theta_jk = theta_jk;
            this.f_jk = f_jk;
        }
        // Getters
        public double getL_J() { return CL_j; }
        public double getC_J() { return CP_j; }
        public double getCF_j() { return CF_j; }
        public double[] getTheta_jk() { return theta_jk; }
        public double[] get_f_jk() { return f_jk; }
        public double getTheta_jk(int k) { return theta_jk[k]; }
        public double get_f_jk(int k) { return f_jk[k]; }
        
        // Setters
        public void setL_J(double L_j) { this.CL_j = L_j; }
        public void setC_J(double C_j) { this.CP_j = C_j; }
        public void setCF_j(double CF_j) { this.CF_j = CF_j; }
        public void setTheta_jk(double[] theta_jk) { this.theta_jk = theta_jk; }
        public void set_f_jk(double[] f_jk) { this.f_jk = f_jk; }
        
        @Override
        public String toString() {
            return String.format(
                "J%d: ID=%d, (x,y)=(%.2f, %.2f), L_j=%.2f, C_j=%.2f, CF_j=%.2f",
                id, id, x, y, CL_j, CP_j ,CF_j
            );
        }
    }

    // J_0 Nodes (Existing traditional retailers) with type = 0
    public static class J_0Nodes extends Node {

        public J_0Nodes(int id, double x, double y) {
            super(id, 0, x, y); // type = 0 for Existing traditional retailers
        }

        @Override
        public String toString() {
            return String.format("J_0_%d: ID=%d, Coordinates=(%.2f, %.2f)",
                               id, id, x, y);
        }
    }
    
    // I Nodes (Customer zones) with type = -1
    public static class INodes extends Node {
        private double[] M_in; // Market size for each service type
        
        public INodes(int id, double x, double y, double[] M_in) {
            super(id, -1, x, y); // type = -1 for Customer zones
            this.M_in = M_in;
        }
        
        // Getters
        public double[] getM_in() { return M_in; }
        
        // Setters
        public void setM_in(double[] M_in) { this.M_in = M_in.clone(); }
        
        @Override
        public String toString() {
            return String.format("I%d: ID=%d, Coordinates=(%.2f, %.2f), M_in=[%.2f, %.2f]", 
                               id, id, x, y, M_in[0], M_in[1]);
        }
    }

    // Service table data
    public static class Service {
        private int s;
        private double beta_0s;     // beta_{0s}
        private double DTs;        // DTs
        private double Q_s;           // Q_{s}
        private double r_s;        // r_s(km)
        private int a_sn0, a_sn1;  // a_{sn0}, a_{sn1}
        private double F_s;        // F_s 标量（新）

        // 新构造：包含F_s，移除alpha_sk和G_sk
        public Service(int s, double beta_0s, double DTs, double Q_s, double r_s, int a_sn0, int a_sn1, double F_s) {
            this.s = s;
            this.beta_0s = beta_0s;
            this.DTs = DTs;
            this.Q_s = Q_s;
            this.r_s = r_s;
            this.a_sn0 = a_sn0;
            this.a_sn1 = a_sn1;
            this.F_s = F_s;
        }

        public int getS() { return s; }
        public double getBeta_0s() { return beta_0s; }
        public double getDTs() { return DTs; }
        public double getQ_s() { return Q_s; }
        public double getR_s() { return r_s; }
        public int getA_sn0() { return a_sn0; }
        public int getA_sn1() { return a_sn1; }
        public double getDt() { return DTs; } // 配送时间（小时）
        public double getQs() { return Q_s; } // 返回Q_s值
        public double getFs() { return F_s; }
        // Get service weight a_{sn}
        public double getAsn(int n) {
            if (n == 0) {
                return a_sn0;
            } else if (n == 1) {
                return a_sn1;
            } else {
                return 0; // 默认权重
            }
        }

    }

    // Global parameters
    public static class Parameters {
        public double B_l;           
        public double B_f;          
        public double beta_0;     // beta_{0}
        public double beta_tt;     // beta_{tt}
        public double beta_tc;     // beta_{tc}
        public double beta_dt;     // beta_{dt}
        public double beta_dc;     // beta_{dc}
        public double lambda_l;    // lambda_l
        public double lambda_f;    // lambda_f
        public double lambda;      // lambda

        // Getters for compatibility
        public double getLambda() { return lambda; }
        public double getLambdal() { return lambda_l; }
        public double getLambdaf() { return lambda_f; }
        public double getBetaDt() { return beta_dt; }
        public double getBetaDc() { return beta_dc; }
        public double getBetaTt() { return beta_tt; }
        public double getBetaTc() { return beta_tc; }
        public double getBeta_0() { return beta_0; } 
    }

    // Store data
    private List<JNodes> jNodes = new ArrayList<>();  // E-retailer nodes (leader candidates)
    private List<J_0Nodes> j_0Nodes = new ArrayList<>();  // Existing traditional retailer nodes
    private List<INodes> iNodes = new ArrayList<>();  // Customer zone nodes
    private List<Service> services = new ArrayList<>();
    private Parameters params = new Parameters();

    // Distance matrix and travel time/cost matrices for I-J_0 (customers to existing traditional retailers)
    private double[][] distanceMatrixIJ_0;
    private double[][] travelTimeMatrixIJ_0;
    private double[][] travelCostMatrixIJ_0;

    // Distance matrix and travel time/cost matrices for I-J (customers to e-retailers)
    private double[][] distanceMatrixIJ;
    private double[][] travelTimeMatrixIJ;
    private double[][] travelCostMatrixIJ;
    
    // Service coverage matrix: r_ijs[i][j][s] = 1 if customer i can be served by facility j with service s
    private double[][][] r_ijs;

    public List<JNodes> getJNodes() { return jNodes; }    // Get J nodes (E-retailers)
    public List<J_0Nodes> getJ_0Nodes() { return j_0Nodes; }    // Get J_0 nodes (Existing traditional retailers)
    public List<INodes> getINodes() { return iNodes; }    // Get I nodes (Customer zones)
    public List<Service> getServices() { return services; }
    public Parameters getParams() { return params; }

    // Get all nodes as a unified list
    public List<Node> getNodes() {
        List<Node> allNodes = new ArrayList<>();
        allNodes.addAll(jNodes);
        allNodes.addAll(j_0Nodes);
        allNodes.addAll(iNodes);
        return allNodes;
    }
    
    // Get market size for package n in customer zone i
    public double getM_in() {
        double total = 0.0;
        for (INodes customer : iNodes) {
            double[] M_in = customer.getM_in();
            for (double m : M_in) {
                total += m;
            }
        }
        return total;
    }
    
    // Get market size for package n in customer zone i
    public double getMarketSize(int i, int n) {
        if (i >= iNodes.size()) {
            throw new IllegalArgumentException("Customer index out of bounds: " + i);
        }
        INodes customer = iNodes.get(i);
        double[] M_in = customer.getM_in();
        if (n >= M_in.length) {
            throw new IllegalArgumentException("Package index out of bounds: " + n);
        }
        return M_in[n];
    }

    // ========== Read TXT file ==========
    public static Data readFromTxt(String filePath) throws IOException {
        Data data = new Data();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(filePath), "UTF-8"))) {
            String line;

            // ========== 第一阶段：读取表头集合大小定义 ==========
            while ((line = br.readLine()) != null) {
                line = line.trim().replaceAll("\0", "");
                // 移除BOM字符
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                // 移除其他可能的BOM字符（UTF-16 LE/BE）
                line = line.replaceAll("^\uFFFE", "").replaceAll("^\uFEFF", "");
                if (line.isEmpty()) continue;

                // 遇到数据表头时停止读取集合大小
                if (line.contains("J-ID")) break;

                String[] parts = line.split("\\t+");
                if (parts.length >= 2) {
                    String key = parts[0].replaceAll("\\u0000", "").replaceAll("[^a-zA-Z0-9_]", "").trim();
                    String value = parts[1].replaceAll("\\u0000", "").trim();
                    switch (key) {
                        case "J": data.J = Integer.parseInt(value); break;
                        case "J0": case "J_0": data.J_0 = Integer.parseInt(value); break;
                        case "I": data.I = Integer.parseInt(value); break;
                        case "S": data.S = Integer.parseInt(value); break;
                        case "Kf": case "K_f": data.K_f = Integer.parseInt(value); break;
                        case "N": data.N = Integer.parseInt(value); break;
                    }
                }
            }

            // ========== 第二阶段：读取 J 个 J节点 ==========
            int jCount = 0;
            while (jCount < data.J && (line = br.readLine()) != null) {
                line = line.trim().replaceAll("\0", "");
                if (line.isEmpty() || line.contains("J-ID") || line.contains("J_0-ID")) continue;

                String[] parts = line.split("\\t+");
                if (parts.length < 4) continue;

                // Clean all parts
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].replaceAll("\\u0000", "").trim();
                }

                // 检查是否为有效数据行
                if (!parts[0].matches("\\d+") || !parts[1].matches("-?\\d+")) continue;

                int id = Integer.parseInt(parts[0]);
                int type = Integer.parseInt(parts[1]);

                if (type == 1) { // J nodes
                    double x = Double.parseDouble(parts[2]);
                    double y = Double.parseDouble(parts[3]);
                    double CL_j = Double.parseDouble(parts[4]);
                    double CP_j = Double.parseDouble(parts[5]);
                    double CF_j = Double.parseDouble(parts[6]);

                    // 使用 K_f 确定数组大小
                    double[] theta_jk = new double[data.K_f];
                    for (int k = 0; k < data.K_f && (7 + k) < parts.length; k++) {
                        theta_jk[k] = Double.parseDouble(parts[7 + k]);
                    }

                    double[] f_jk = new double[data.K_f];
                    int fjkStartIdx = 7 + data.K_f;
                    for (int k = 0; k < data.K_f && (fjkStartIdx + k) < parts.length; k++) {
                        f_jk[k] = Double.parseDouble(parts[fjkStartIdx + k]);
                    }

                    JNodes jNode = new JNodes(id, x, y, CL_j, CP_j, CF_j, theta_jk, f_jk);
                    data.jNodes.add(jNode);
                    jCount++;
                }
            }

            // 读取完成后，根据实际读取数量修正 J 的大小，确保与数据一致
            data.J = data.jNodes.size();

            // ========== 第三阶段：读取 J_0 个 J_0节点 ==========
            int j0Count = 0;
            while (j0Count < data.J_0 && (line = br.readLine()) != null) {
                line = line.trim().replaceAll("\0", "");
                if (line.isEmpty() || line.contains("J_0-ID") || line.contains("I-ID")) continue;

                String[] parts = line.split("\\t+");
                if (parts.length < 4) continue;

                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].replaceAll("\\u0000", "").trim();
                }

                if (!parts[0].matches("\\d+") || !parts[1].matches("-?\\d+")) continue;

                int id = Integer.parseInt(parts[0]);
                int type = Integer.parseInt(parts[1]);

                if (type == 0) { // J_0 nodes
                    double x = Double.parseDouble(parts[2]);
                    double y = Double.parseDouble(parts[3]);
                    J_0Nodes j_0Node = new J_0Nodes(id, x, y);
                    data.j_0Nodes.add(j_0Node);
                    j0Count++;
                }
            }

            // 读取完成后，根据实际读取数量修正 J_0 的大小，确保与数据一致
            data.J_0 = data.j_0Nodes.size();

            // ========== 第四阶段：读取 I 个 I节点 ==========
            int iCount = 0;
            while (iCount < data.I && (line = br.readLine()) != null) {
                line = line.trim().replaceAll("\0", "");
                if (line.isEmpty() || line.contains("I-ID") || line.startsWith("s\t")) continue;

                String[] parts = line.split("\\t+");
                if (parts.length < 4) continue;

                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].replaceAll("\\u0000", "").trim();
                }

                if (!parts[0].matches("\\d+") || !parts[1].matches("-?\\d+")) continue;

                int id = Integer.parseInt(parts[0]);
                int type = Integer.parseInt(parts[1]);

                if (type == -1) { // I nodes
                    double x = Double.parseDouble(parts[2]);
                    double y = Double.parseDouble(parts[3]);

                    // 使用 N 确定数组大小
                    double[] M_in = new double[data.N];
                    for (int n = 0; n < data.N && (4 + n) < parts.length; n++) {
                        if (!parts[4 + n].isEmpty()) {
                            M_in[n] = Double.parseDouble(parts[4 + n]);
                        }
                    }

                    INodes iNode = new INodes(id, x, y, M_in);
                    data.iNodes.add(iNode);
                    iCount++;
                }
            }

            // 读取完成后，根据实际读取数量修正 I 的大小，确保与数据一致
            data.I = data.iNodes.size();

            // ========== 第五阶段 & 第六阶段：读取 Service 与 参数 ==========
            // 这里不再依赖表头中的 S 值，而是根据实际行内容自动判断
            while ((line = br.readLine()) != null) {
                line = line.trim().replaceAll("\0", "");
                if (line.isEmpty()) continue;

                // 跳过服务表头行
                if (line.startsWith("s\t") || line.contains("beta_{0s}")) continue;

                String[] parts = line.split("\\t+");
                if (parts.length == 0) continue;

                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parts[i].replaceAll("\\u0000", "").trim();
                }

                // 若首列为数字，则认为是服务行
                if (parts[0].matches("\\d+")) {
                    if (parts.length < 8) continue;

                    int s = Integer.parseInt(parts[0]);
                    double beta_0 = Double.parseDouble(parts[1]);
                    double DTs = Double.parseDouble(parts[2]);
                    double Q_s = Double.parseDouble(parts[3]);
                    double r_s = Double.parseDouble(parts[4]);
                    int a_sn0 = Integer.parseInt(parts[5]);
                    int a_sn1 = Integer.parseInt(parts[6]);
                    double F_s = Double.parseDouble(parts[7]);

                    data.services.add(new Service(s, beta_0, DTs, Q_s, r_s, a_sn0, a_sn1, F_s));
                } else {
                    // 否则认为进入参数区，按参数格式解析
                    if (parts.length < 2) continue;
                    String key = parts[0];
                    String value = parts[1];

                    if (key.equals("B_l") && !line.contains("\\")) data.params.B_l = Double.parseDouble(value);
                    else if (key.equals("B_f") && !line.contains("\\")) data.params.B_f = Double.parseDouble(value);
                    else if (key.equals("beta_{0}") || key.equals("beta_{05}")) data.params.beta_0 = Double.parseDouble(value);
                    else if (key.equals("beta_{tt}")) data.params.beta_tt = Double.parseDouble(value);
                    else if (key.equals("beta_{tc}")) data.params.beta_tc = Double.parseDouble(value);
                    else if (key.equals("beta_{dt}")) data.params.beta_dt = Double.parseDouble(value);
                    else if (key.equals("beta_{dc}")) data.params.beta_dc = Double.parseDouble(value);
                    else if (key.equals("lambda_L")) data.params.lambda_l = Double.parseDouble(value);
                    else if (key.equals("lambda_F")) data.params.lambda_f = Double.parseDouble(value);
                    else if (key.equals("lambda")) data.params.lambda = Double.parseDouble(value);
                }
            }

            // 读取完成后，根据实际读取数量修正 S 的大小，确保与数据一致
            data.S = data.services.size();
        }

        // Calculate distance matrices and travel time/cost matrices
        data.calculateDistanceMatrices();

        // 输出读取的集合大小用于验证
        System.out.println("集合大小: J=" + data.J + ", J_0=" + data.J_0 + ", I=" + data.I +
                           ", S=" + data.S + ", K_f=" + data.K_f + ", N=" + data.N);

        return data;
    }

    // New statistical attributes
    private int J, I, J_0, S, N, K_f;

    // getter方法直接返回表头读取的值
    public int getJ() { return J; }
    public int getI() { return I; }
    public int getJ_0() { return J_0; }
    public int getS() { return S; }
    public int getN() { return N; }
    public int getK_f() { return K_f; }

    // Getter methods for distance matrices and travel time/cost matrices (I-J_0)
    public double[][] getDistanceMatrixIJ_0() { return distanceMatrixIJ_0; }
    public double[][] getTravelTimeMatrixIJ_0() { return travelTimeMatrixIJ_0; }
    public double[][] getTravelCostMatrixIJ_0() { return travelCostMatrixIJ_0; }

    // Getter methods for distance matrices and travel time/cost matrices (I-J)
    public double[][] getDistanceMatrixIJ() { return distanceMatrixIJ; }
    public double[][] getTravelTimeMatrixIJ() { return travelTimeMatrixIJ; }
    public double[][] getTravelCostMatrixIJ() { return travelCostMatrixIJ; }
    
    // Getter method for service coverage matrix
    public double[][][] getR_ijs() { return r_ijs; }
    
    /**
     * Get service coverage value r_ijs
     * @param i Customer index
     * @param j Facility index
     * @param s Service index
     * @return Coverage value (1.0 if covered, 0.0 if not)
     */
    public double getServiceCoverage(int i, int j, int s) {
        if (r_ijs == null) {
            calculater_ijs();
        }
        return r_ijs[i][j][s];
    }
    
    /**
     * Calculate and initialize distance matrices and travel time/cost matrices
     * Distance, travel time, and travel cost from customer i to traditional retailer b
     */
    public void calculateDistanceMatrices() {

    int I = iNodes.size();
    int J = jNodes.size();      // E-retailers (J)
    int J_0 = j_0Nodes.size();   // Existing traditional retailers (J_0)

    // Matrices for I-J (customers to e-retailers and follower candidates reuse this)
    distanceMatrixIJ = new double[I][J];
    travelTimeMatrixIJ = new double[I][J];
    travelCostMatrixIJ = new double[I][J];

        // Matrices for I-J_0 (customers to existing traditional retailers)
        distanceMatrixIJ_0 = new double[I][J_0];
        travelTimeMatrixIJ_0 = new double[I][J_0];
        travelCostMatrixIJ_0 = new double[I][J_0];
        
        // Note: Follower candidate distances reuse the I-J matrices (follower candidates == J nodes)
        
        // Calculate for e-retailers (I-J)
        for (int i = 0; i < I; i++) {
            INodes customer = iNodes.get(i);
            for (int j = 0; j < J; j++) {
                JNodes retailer = jNodes.get(j);

                // Calculate Euclidean distance
                double distance = calculateEuclideanDistance(customer.getX(), customer.getY(),
                                                        retailer.getX(), retailer.getY());
                distanceMatrixIJ[i][j] = distance;

                // Calculate travel time
                double travelTime = calculateTravelTime(distance);
                travelTimeMatrixIJ[i][j] = travelTime;

                // Calculate travel cost
                double travelCost = calculateTravelCost(distance);
                travelCostMatrixIJ[i][j] = travelCost;
            }
        }

        // Calculate for existing traditional retailers (I-J_0)
        for (int i = 0; i < I; i++) {
            INodes customer = iNodes.get(i);
            for (int j = 0; j < J_0; j++) {
                J_0Nodes retailer_0 = j_0Nodes.get(j);

                // Calculate Euclidean distance
                double distance = calculateEuclideanDistance(customer.getX(), customer.getY(),
                                                        retailer_0.getX(), retailer_0.getY());
                distanceMatrixIJ_0[i][j] = distance;

                // Calculate travel time
                double travelTime = calculateTravelTime(distance);
                travelTimeMatrixIJ_0[i][j] = travelTime;

                // Calculate travel cost
                double travelCost = calculateTravelCost(distance);
                travelCostMatrixIJ_0[i][j] = travelCost;
            }
        }
    }
    
    /**
     * Calculate and initialize service coverage matrix
     * r_ijs[i][j][s] = 1 if customer i can be served by facility j with service s
     */
    public void calculater_ijs() {
        int numCustomers = iNodes.size();
        int numFacilities = jNodes.size();
        int numServices = services.size();
        
        r_ijs = new double[numCustomers][numFacilities][numServices];
        
        for (int i = 0; i < numCustomers; i++) {
            INodes customer = iNodes.get(i);
            for (int j = 0; j < numFacilities; j++) {
                JNodes facility = jNodes.get(j);
                for (int s = 0; s < numServices; s++) {
                    Service service = services.get(s);
                    
                    // Calculate Euclidean distance between customer and facility
                    double distance = calculateEuclideanDistance(customer.getX(), customer.getY(),
                                                               facility.getX(), facility.getY());
                    double distanceInKm = distance / 1000.0;  // 米 → 公里
                    double serviceRange = service.getR_s();   // 服务范围单位是公里

                    // r_ijs = 1 if distance <= service range, 0 otherwise
                    r_ijs[i][j][s] = (distanceInKm <= serviceRange) ? 1.0 : 0.0;
                }
            }
        }
    }
    
    /**
     * Calculate Euclidean distance between two nodes
     * @param node1 First node
     * @param node2 Second node
     * @return Distance
     */

    
    /**
     * Calculate Euclidean distance between two points
     * @param x1 X coordinate of first point
     * @param y1 Y coordinate of first point
     * @param x2 X coordinate of second point
     * @param y2 Y coordinate of second point
     * @return Distance
     */
    public double calculateEuclideanDistance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Calculate travel time based on distance
     * Assuming average travel speed of 30km/h
     * @param distance Distance in meters (will be converted to km)
     * @return Travel time (hours)
     */
    public double calculateTravelTime(double distance) {
        double averageSpeed = 30.0; // km/h
        double distanceInKm = distance / 1000.0;  // 米 → 公里
        return distanceInKm / averageSpeed;
    }
    
    /**
     * Calculate travel cost based on distance
     * Including time cost and fuel cost
     * @param distance Distance in meters (will be converted to km)
     * @return Travel cost
     */
    public double calculateTravelCost(double distance) {
        double averageSpeed = 30.0; // km/h
        double CostPerH = 40;
        double distanceInKm = distance / 1000.0;  // 米 → 公里
        return distanceInKm/averageSpeed * CostPerH;
    }
    
    /**
     * Get distance from customer i to traditional retailer b
     * @param i Customer index
     * @param b Traditional retailer index
     * @return Distance
     */
    public double getDistance(int i, int b) {
        if (distanceMatrixIJ == null) {
            calculateDistanceMatrices();
        }
        return distanceMatrixIJ[i][b];
    }
    
    /**
     * Get travel time from customer i to traditional retailer b
     * @param i Customer index
     * @param b Traditional retailer index
     * @return Travel time
     */
    public double getTravelTime(int i, int b) {
        if (travelTimeMatrixIJ == null) {
            calculateDistanceMatrices();
        }
        return travelTimeMatrixIJ[i][b];
    }
    
    /**
     * Get travel cost from customer i to traditional retailer b
     * @param i Customer index
     * @param b Traditional retailer index
     * @return Travel cost
     */
    public double getTravelCost(int i, int b) {
        if (travelCostMatrixIJ == null) {
            calculateDistanceMatrices();
        }
        return travelCostMatrixIJ[i][b];
    }
}
    


