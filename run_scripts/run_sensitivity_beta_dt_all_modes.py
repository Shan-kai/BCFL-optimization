import glob
import os
import re
import subprocess
import sys
import time
import csv
import threading
import datetime

os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ==================== 配置�?====================
BASE_DATA_FILE = "data.txt"
DATA_DIR = "alldata_beta_dt敏感性分�?
PATTERN = os.path.join(DATA_DIR, "*.txt")
CONCURRENCY = 1

# beta_dt: 0 ~ 1.9, step 0.1
BETA_DT_VALUES = [round(0.1 * i, 1) for i in range(20)]

# 三种实验模式
MODES = {
    "基础实验": {
        "output_dir": "output_beta_dt_基础实验",
        "jvm_args": ["-Ddisable.drone=false", "-Ddisable.charging=false"],
        "label": "基础实验(无人�?换电�?",
    },
    "无换电站": {
        "output_dir": "output_beta_dt_无换电站",
        "jvm_args": ["-Ddisable.drone=false", "-Ddisable.charging=true"],
        "label": "无换电站(仅无人机)",
    },
    "无无人机": {
        "output_dir": "output_beta_dt_无无人机",
        "jvm_args": ["-Ddisable.drone=true", "-Ddisable.charging=false"],
        "label": "无无人机",
    },
}

JAVA_COMPILE_CMD = ["javac", "-encoding", "UTF-8", "-cp", "lib/cplex.jar", "-d", "bin"]
TIMEOUT_SEC = 0
DO_COMPILE = True
SKIP_EXISTING = True
# ================================================


# ========== 数据生成 ==========

def read_base(filepath):
    with open(filepath, "r", encoding="utf-8") as f:
        return f.readlines()


def modify_beta_dt(lines, beta_dt_val):
    result = list(lines)
    for i, line in enumerate(result):
        stripped = line.rstrip("\n").rstrip("\r")
        parts = stripped.split("\t")
        if len(parts) >= 2 and parts[0].strip() == "beta_{dt}":
            parts[1] = str(beta_dt_val)
            result[i] = "\t".join(parts) + "\n"
            break
    return result


def generate_data_files():
    os.makedirs(DATA_DIR, exist_ok=True)
    lines = read_base(BASE_DATA_FILE)
    total = len(BETA_DT_VALUES)
    count = 0
    for val in BETA_DT_VALUES:
        filename = f"betadt{val}.txt"
        filepath = os.path.join(DATA_DIR, filename)
        content = modify_beta_dt(lines, val)
        with open(filepath, "w", encoding="utf-8") as f:
            f.writelines(content)
        count += 1
    return count


# ========== 实验运行 ==========

def log(msg):
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def compile_java():
    src_patterns = [
        "src/input/*.java", "src/utils/*.java", "src/pool/*.java",
        "src/mp/*.java", "src/sp/*.java", "src/result/*.java", "src/run/*.java"
    ]
    src_files = []
    for p in src_patterns:
        src_files.extend(glob.glob(p))
    if not src_files:
        log("错误：未找到任何 Java 源文�?)
        sys.exit(1)
    cmd = JAVA_COMPILE_CMD + src_files
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="ignore")
        if result.returncode != 0:
            log("编译失败�?)
            print(result.stdout)
            print(result.stderr)
            sys.exit(1)
        log(f"编译成功，共编译 {len(src_files)} 个文�?)
    except Exception as e:
        log(f"编译异常: {e}")
        sys.exit(1)


def parse_beta_dt_from_filename(name):
    base = os.path.splitext(os.path.basename(name))[0]
    m = re.match(r"betadt([0-9.]+)", base)
    if m:
        return float(m.group(1))
    return None


def find_output_file(output_dir, beta_dt_val, mode_name):
    if not os.path.isdir(output_dir):
        return None
    pattern = os.path.join(output_dir, f"*betadt{beta_dt_val}*{mode_name}*.txt")
    candidates = glob.glob(pattern)
    if not candidates:
        return None
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return candidates[0]


def should_skip(output_dir, beta_dt_val, mode_name):
    if not SKIP_EXISTING:
        return False
    out_file = find_output_file(output_dir, beta_dt_val, mode_name)
    if not out_file:
        return False
    return True


def parse_output_file(path):
    info = {
        "ub": None, "lb": None, "iterations": None,
        "total_time_sec": None, "termination": "未知", "gap": None,
        "sumDLin": None, "sumDFin": None,
    }
    if not path or not os.path.exists(path):
        return info
    content = None
    for enc in ("utf-8", "gbk", "gb2312", "utf-16"):
        try:
            with open(path, "r", encoding=enc, errors="ignore") as f:
                content = f.read()
            break
        except Exception:
            continue
    if content is None:
        return info
    m = re.search(r"最终上�?UB = ([-+eE0-9.]+)", content)
    if m: info["ub"] = float(m.group(1))
    m = re.search(r"最终下�?LB = ([-+eE0-9.]+)", content)
    if m: info["lb"] = float(m.group(1))
    m = re.search(r"总迭代次�?= (\d+)", content)
    if m: info["iterations"] = int(m.group(1))
    m = re.search(r"算法总运行时�? ([0-9.]+) �?, content)
    if m: info["total_time_sec"] = float(m.group(1))
    m = re.search(r"sumDLin = \u03a3_i \u03a3_n D_in\^L\(y\*, z\*\) = ([0-9.eE+-]+)", content)
    if m: info["sumDLin"] = float(m.group(1))
    m = re.search(r"sumDFin = \u03a3_i \u03a3_n D_in\^F\(y\*, z\*\) = ([0-9.eE+-]+)", content)
    if m: info["sumDFin"] = float(m.group(1))
    if "达到总时间上�?小时，算法终�? in content:
        info["termination"] = "时间上限(3h)"
    elif "达到最大迭代次数，算法终止" in content:
        info["termination"] = "最大迭代次�?
    elif "算法停滞，连�? in content and "提前终止" in content:
        info["termination"] = "停滞提前终止"
    elif "主问题无法最优求解，算法终止" in content:
        info["termination"] = "MP不可�?无界"
    elif "最终上�?UB" in content and "最终下�?LB" in content:
        info["termination"] = "收敛"
    if info["ub"] is not None and info["lb"] is not None:
        ub = info["ub"]
        lb = info["lb"]
        if abs(ub) > 1e-9:
            info["gap"] = (ub - lb) / abs(ub)
        else:
            info["gap"] = 0.0 if abs(lb) < 1e-9 else float("inf")
    return info


sem = threading.Semaphore(CONCURRENCY)
results = {}
lock = threading.Lock()
completed_count = 0
total_count = 0


def run_single_instance(data_path, beta_dt_val, mode_name, mode_config, idx, total):
    global completed_count
    start_time = time.time()
    output_dir = mode_config["output_dir"]
    if should_skip(output_dir, beta_dt_val, mode_name):
        out_file = find_output_file(output_dir, beta_dt_val, mode_name)
        info = parse_output_file(out_file)
        info["beta_dt"] = beta_dt_val
        info["mode"] = mode_name
        info["elapsed_wall"] = 0.0
        info["output_path"] = out_file
        info["returncode"] = 0
        with lock:
            results[(beta_dt_val, mode_name)] = info
            completed_count += 1
        gap_str = f"{info['gap']*100:.4f}%" if info["gap"] is not None else "N/A"
        ub_str = f"{info['ub']:.4f}" if info["ub"] is not None else "N/A"
        lb_str = f"{info['lb']:.4f}" if info["lb"] is not None else "N/A"
        log(f"[跳过 {completed_count}/{total}] beta_dt={beta_dt_val}, {mode_name} -> UB={ub_str}, LB={lb_str}, 间隙={gap_str}")
        return
    log(f"[开�?{completed_count+1}/{total}] beta_dt={beta_dt_val}, {mode_name}")
    with sem:
        jvm_args = mode_config["jvm_args"]
        cmd = ["java", "-Djava.library.path=D:\\cplex\\bin\\x64_win64", "-Dfile.encoding=UTF-8",
               "-Doutput.dir=" + output_dir, "-cp", "bin;lib/cplex.jar"] + jvm_args + ["run.Main", data_path]
        proc = None
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding="utf-8", errors="ignore")
            if TIMEOUT_SEC > 0:
                try:
                    stdout, _ = proc.communicate(timeout=TIMEOUT_SEC)
                except subprocess.TimeoutExpired:
                    log(f"[超时] beta_dt={beta_dt_val}, {mode_name} 超过 {TIMEOUT_SEC}s")
                    proc.kill()
                    stdout, _ = proc.communicate()
                    info = {"returncode": -2, "elapsed_wall": TIMEOUT_SEC, "termination": f"超时({TIMEOUT_SEC}s)",
                            "ub": None, "lb": None, "iterations": None, "gap": None,
                            "sumDLin": None, "sumDFin": None, "beta_dt": beta_dt_val, "mode": mode_name,
                            "output_path": None, "total_time_sec": None}
                    with lock:
                        results[(beta_dt_val, mode_name)] = info
                        completed_count += 1
                    return
            else:
                stdout, _ = proc.communicate()
            elapsed = time.time() - start_time
            time.sleep(0.5)
            out_file = find_output_file(output_dir, beta_dt_val, mode_name)
            info = parse_output_file(out_file)
            info["beta_dt"] = beta_dt_val
            info["mode"] = mode_name
            info["elapsed_wall"] = elapsed
            info["output_path"] = out_file
            info["returncode"] = proc.returncode
            with lock:
                results[(beta_dt_val, mode_name)] = info
                completed_count += 1
            gap_str = f"{info['gap']*100:.4f}%" if info["gap"] is not None else "N/A"
            ub_str = f"{info['ub']:.4f}" if info["ub"] is not None else "N/A"
            lb_str = f"{info['lb']:.4f}" if info["lb"] is not None else "N/A"
            iter_str = str(info["iterations"]) if info["iterations"] is not None else "N/A"
            time_str = f"{info['total_time_sec']:.1f}" if info["total_time_sec"] is not None else "N/A"
            log(f"[结束 {completed_count}/{total}] beta_dt={beta_dt_val}, {mode_name} -> wall={elapsed:.1f}s, "
                f"迭代={iter_str}, UB={ub_str}, LB={lb_str}, 间隙={gap_str}")
        except Exception as e:
            elapsed = time.time() - start_time
            if proc is not None and proc.poll() is None:
                proc.kill()
            with lock:
                results[(beta_dt_val, mode_name)] = {
                    "returncode": -1, "elapsed_wall": elapsed, "termination": f"异常: {e}",
                    "ub": None, "lb": None, "iterations": None, "gap": None,
                    "sumDLin": None, "sumDFin": None, "beta_dt": beta_dt_val, "mode": mode_name,
                    "output_path": None, "total_time_sec": None}
                completed_count += 1
            log(f"[异常 {completed_count}/{total}] beta_dt={beta_dt_val}, {mode_name} -> {e}")


def save_csv(results_dict):
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    merged_path = f"results_beta_dt_all_modes_{timestamp}.csv"
    with open(merged_path, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        writer.writerow([
            "模式", "beta_dt", "最终UB", "最终LB", "相对间隙(%)", "迭代次数",
            "领导者需求sumDLin", "追随者需求sumDFin", "总需�?,
            "运行时间(s)", "终止原因", "输出文件"
        ])
        for key in sorted(results_dict.keys()):
            r = results_dict[key]
            total_demand = (r["sumDLin"] + r["sumDFin"]) if (r["sumDLin"] is not None and r["sumDFin"] is not None) else None
            writer.writerow([
                r["mode"], r["beta_dt"],
                f"{r['ub']:.6f}" if r["ub"] is not None else "",
                f"{r['lb']:.6f}" if r["lb"] is not None else "",
                f"{r['gap']*100:.4f}" if r["gap"] is not None else "",
                r["iterations"] if r["iterations"] is not None else "",
                f"{r['sumDLin']:.6f}" if r["sumDLin"] is not None else "",
                f"{r['sumDFin']:.6f}" if r["sumDFin"] is not None else "",
                f"{total_demand:.6f}" if total_demand is not None else "",
                f"{r['total_time_sec']:.2f}" if r["total_time_sec"] is not None else "",
                r["termination"],
                os.path.basename(r["output_path"]) if r["output_path"] else ""
            ])
    log(f"合并 CSV 已保�? {merged_path}")
    for mode_name, mode_config in MODES.items():
        mode_results = {k: v for k, v in results_dict.items() if k[1] == mode_name}
        if not mode_results:
            continue
        mode_path = os.path.join(mode_config["output_dir"], f"results_beta_dt_{mode_name}_{timestamp}.csv")
        with open(mode_path, "w", newline="", encoding="utf-8-sig") as f:
            writer = csv.writer(f)
            writer.writerow([
                "beta_dt", "最终UB", "最终LB", "相对间隙(%)", "迭代次数",
                "领导者需求sumDLin", "追随者需求sumDFin", "总需�?,
                "运行时间(s)", "终止原因", "输出文件"
            ])
            for key in sorted(mode_results.keys()):
                r = mode_results[key]
                total_demand = (r["sumDLin"] + r["sumDFin"]) if (r["sumDLin"] is not None and r["sumDFin"] is not None) else None
                writer.writerow([
                    r["beta_dt"],
                    f"{r['ub']:.6f}" if r["ub"] is not None else "",
                    f"{r['lb']:.6f}" if r["lb"] is not None else "",
                    f"{r['gap']*100:.4f}" if r["gap"] is not None else "",
                    r["iterations"] if r["iterations"] is not None else "",
                    f"{r['sumDLin']:.6f}" if r["sumDLin"] is not None else "",
                    f"{r['sumDFin']:.6f}" if r["sumDFin"] is not None else "",
                    f"{total_demand:.6f}" if total_demand is not None else "",
                    f"{r['total_time_sec']:.2f}" if r["total_time_sec"] is not None else "",
                    r["termination"],
                    os.path.basename(r["output_path"]) if r["output_path"] else ""
                ])
        log(f"{mode_name} CSV 已保�? {mode_path}")


def main():
    global total_count

    # 1. 生成数据
    log("=== 步骤1: 生成实验数据 ===")
    n_generated = generate_data_files()
    log(f"已生�?{n_generated} 个数据文件到 {DATA_DIR}/")

    # 2. 确保输出目录存在
    for mode_name, mode_config in MODES.items():
        os.makedirs(mode_config["output_dir"], exist_ok=True)
        log(f"输出目录: {mode_config['output_dir']} ({mode_config['label']})")

    # 3. 扫描数据文件
    data_files = sorted(glob.glob(PATTERN), key=lambda p: parse_beta_dt_from_filename(p))
    if not data_files:
        log(f"未找到数据文�? {PATTERN}")
        sys.exit(1)

    total_count = len(data_files) * len(MODES)
    log(f"\n=== 步骤2: 运行实验 ===")
    log(f"�?{len(data_files)} 个数据文�?x {len(MODES)} 种模�?= {total_count} 个实验，并发�?{CONCURRENCY}")
    for f in data_files:
        bt = parse_beta_dt_from_filename(f)
        log(f"  - {os.path.basename(f)} (beta_dt={bt})")

    if DO_COMPILE:
        compile_java()

    overall_start = time.time()
    threads = []
    for fp in data_files:
        bt = parse_beta_dt_from_filename(fp)
        for mode_name, mode_config in MODES.items():
            t = threading.Thread(target=run_single_instance, args=(fp, bt, mode_name, mode_config, 0, total_count))
            t.start()
            threads.append(t)
            if CONCURRENCY == 1:
                t.join()
    for t in threads:
        t.join()

    overall_elapsed = time.time() - overall_start

    print()
    print("=" * 130)
    print("beta_dt 敏感性分析（三种模式）实验完成，汇总报告如下：")
    print("=" * 130)
    header = (
        f"{'beta_dt':>8} "
        f"{'模式':>16} "
        f"{'迭代次数':>10} "
        f"{'最终UB':>14} "
        f"{'最终LB':>14} "
        f"{'相对间隙':>12} "
        f"{'sumDLin':>14} "
        f"{'sumDFin':>14} "
        f"{'运行时间(s)':>12} "
        f"{'终止原因':>14}"
    )
    print(header)
    print("-" * 130)
    for key in sorted(results.keys()):
        r = results[key]
        gap_str = f"{r['gap']*100:.2f}%" if r["gap"] is not None else "N/A"
        ub_str = f"{r['ub']:.4f}" if r["ub"] is not None else "N/A"
        lb_str = f"{r['lb']:.4f}" if r["lb"] is not None else "N/A"
        iter_str = str(r["iterations"]) if r["iterations"] is not None else "N/A"
        time_str = f"{r['total_time_sec']:.1f}" if r["total_time_sec"] is not None else "N/A"
        dlin_str = f"{r['sumDLin']:.4f}" if r["sumDLin"] is not None else "N/A"
        dfin_str = f"{r['sumDFin']:.4f}" if r["sumDFin"] is not None else "N/A"
        term_str = r["termination"] if r["termination"] else "N/A"
        print(
            f"{r['beta_dt']:>8.1f} "
            f"{r['mode']:>16} "
            f"{iter_str:>10} "
            f"{ub_str:>14} "
            f"{lb_str:>14} "
            f"{gap_str:>12} "
            f"{dlin_str:>14} "
            f"{dfin_str:>14} "
            f"{time_str:>12} "
            f"{term_str:>14}"
        )
    print("-" * 130)
    log(f"总实验数: {len(results)}/{total_count}, �?wall-clock 耗时: {overall_elapsed:.1f}s ({overall_elapsed/60:.1f}min)")
    print("=" * 130)
    if results:
        save_csv(results)


if __name__ == "__main__":
    main()
