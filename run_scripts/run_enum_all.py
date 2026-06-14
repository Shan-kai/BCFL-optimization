import glob
import os
import re
import subprocess
import sys
import time
import csv
import threading
import datetime

# 切换到项目根目录（run_scripts/的上层）
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ==================== 配置�?====================
DATA_DIR = "alldata"
PATTERN = os.path.join(DATA_DIR, "*.txt")
OUTPUT_DIR = "output全枚举结�?
CSV_DIR = OUTPUT_DIR
CONCURRENCY = 1  # 枚举较耗资源，建议串行

JAVA_COMPILE_CMD = [
    'javac', '-encoding', 'UTF-8', '-cp', 'lib/cplex.jar', '-d', 'bin'
]
JAVA_CMD_BASE = [
    'java', '-Djava.library.path=D:\\cplex\\bin\\x64_win64',
    '-Dfile.encoding=UTF-8',
    '-cp', 'bin;lib/cplex.jar'
]

TIMEOUT_SEC = 10900  # 比枚举内�?0800s�?00s留给JVM启动
DO_COMPILE = True
SKIP_EXISTING = True
# ================================================


def log(msg):
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def compile_java():
    src_patterns = [
        'src/input/*.java', 'src/utils/*.java', 'src/pool/*.java',
        'src/mp/*.java', 'src/sp/*.java', 'src/result/*.java', 'src/run/*.java'
    ]
    src_files = []
    for p in src_patterns:
        src_files.extend(glob.glob(p))
    if not src_files:
        log("错误：未找到任何 Java 源文�?)
        sys.exit(1)

    cmd = JAVA_COMPILE_CMD + src_files
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='ignore')
        if result.returncode != 0:
            log("编译失败�?)
            print(result.stdout)
            print(result.stderr)
            sys.exit(1)
        log(f"编译成功，共编译 {len(src_files)} 个文�?)
    except Exception as e:
        log(f"编译异常: {e}")
        sys.exit(1)


def parse_params_from_path(data_path):
    """从数据文件路径解析参�? I-J.txt -> I=客户区数, J=候选点�?""
    filename = os.path.splitext(os.path.basename(data_path))[0]
    m = re.match(r'(\d+)-(\d+)', filename)
    if not m:
        return None
    I_val, J_val = int(m.group(1)), int(m.group(2))
    return {
        'I': I_val,
        'J': J_val,
        'filename': filename,
        'data_path': data_path,
    }


def find_output_file(output_dir, I_val, J_val):
    """查找已有枚举输出文件"""
    if not os.path.isdir(output_dir):
        return None
    pattern = os.path.join(output_dir, f"*-{I_val}-{J_val}-enumoutput*.txt")
    candidates = glob.glob(pattern)
    if not candidates:
        return None
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return candidates[0]


def should_skip(output_dir, I_val, J_val):
    if not SKIP_EXISTING:
        return False
    return find_output_file(output_dir, I_val, J_val) is not None


def parse_enum_output(path):
    """解析枚举输出文件"""
    info = {
        'best_pi': None, 'best_phi': None,
        'count_vlpvx': None, 'count_gamma': None, 'count_evaluated': None,
        'count_infeasible': None, 'count_nan': None,
        'elapsed_sec': None, 'termination': '未知',
        'lambda': None, 'lambda_l': None, 'lambda_f': None,
    }
    if not path or not os.path.exists(path):
        return info

    content = None
    for enc in ('utf-8', 'gbk', 'gb2312', 'utf-16'):
        try:
            with open(path, 'r', encoding=enc, errors='ignore') as f:
                content = f.read()
            break
        except Exception:
            continue
    if content is None:
        return info

    # Pi*
    m = re.search(r'Pi\* \(领导者市场份额\) = ([0-9.eE+-]+)', content)
    if m:
        info['best_pi'] = float(m.group(1))

    # Phi*
    m = re.search(r'Phi\* \(追随者市场份额\) = ([0-9.eE+-]+)', content)
    if m:
        info['best_phi'] = float(m.group(1))

    # 统计信息
    m = re.search(r'可行\(vL,vP,x\)组合�?\s+(\d+)', content)
    if m:
        info['count_vlpvx'] = int(m.group(1))

    m = re.search(r'有效Gamma�?\s+(\d+)', content)
    if m:
        info['count_gamma'] = int(m.group(1))

    m = re.search(r'去重后实际评估数:\s+(\d+)', content)
    if m:
        info['count_evaluated'] = int(m.group(1))

    m = re.search(r'子问题不可行/异常:\s+(\d+)', content)
    if m:
        info['count_infeasible'] = int(m.group(1))

    m = re.search(r'NaN跳过:\s+(\d+)', content)
    if m:
        info['count_nan'] = int(m.group(1))

    # 枚举耗时
    m = re.search(r'枚举耗时:\s+([0-9.]+)\s+�?, content)
    if m:
        info['elapsed_sec'] = float(m.group(1))

    # 求解时间
    if info['elapsed_sec'] is None:
        m = re.search(r'求解时间:\s+([0-9.]+)\s+�?, content)
        if m:
            info['elapsed_sec'] = float(m.group(1))

    # 终止状�?    if '超时终止' in content:
        info['termination'] = '时间上限(3600s)'
    elif '枚举完成' in content:
        info['termination'] = '完成'
    elif '未找到任何可行解' in content:
        info['termination'] = '无可行解'

    # lambda参数
    m = re.search(r'lambda_L = ([0-9.eE+-]+)', content)
    if m:
        info['lambda_l'] = float(m.group(1))
    m = re.search(r'lambda_F = ([0-9.eE+-]+)', content)
    if m:
        info['lambda_f'] = float(m.group(1))
    m = re.search(r'lambda = ([0-9.eE+-]+)', content)
    if m:
        info['lambda'] = float(m.group(1))

    return info


# 并发控制
sem = threading.Semaphore(CONCURRENCY)
results = {}
lock = threading.Lock()
completed_count = 0
total_count = 0


def run_single_enum(params):
    global completed_count

    I_val = params['I']
    J_val = params['J']
    data_path = params['data_path']
    tag = f"I{I_val}-J{J_val}"

    start_time = time.time()

    output_dir = os.path.abspath(OUTPUT_DIR)
    os.makedirs(output_dir, exist_ok=True)

    # 检查是否跳�?    if should_skip(output_dir, I_val, J_val):
        out_file = find_output_file(output_dir, I_val, J_val)
        info = parse_enum_output(out_file)
        info['I'] = I_val
        info['J'] = J_val
        info['elapsed_wall'] = 0.0
        info['output_path'] = out_file
        info['returncode'] = 0
        with lock:
            results[(I_val, J_val)] = info
            completed_count += 1
        pi_str = f"{info['best_pi']:.6f}" if info['best_pi'] is not None else "N/A"
        log(f"[跳过 {completed_count}/{total_count}] {tag} -> Pi={pi_str}, 终止={info['termination']}")
        return

    log(f"[开�?{completed_count+1}/{total_count}] {tag}")

    with sem:
        cmd = JAVA_CMD_BASE + ['run.EnumerationVerifier', data_path]
        proc = None
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding='utf-8',
                errors='ignore'
            )
            if TIMEOUT_SEC > 0:
                try:
                    stdout, _ = proc.communicate(timeout=TIMEOUT_SEC)
                except subprocess.TimeoutExpired:
                    log(f"[超时] {tag}，强制终�?)
                    proc.kill()
                    stdout, _ = proc.communicate()
                    info = {
                        'returncode': -2, 'elapsed_wall': TIMEOUT_SEC,
                        'termination': f'Python超时({TIMEOUT_SEC}s)',
                        'best_pi': None, 'best_phi': None,
                        'count_vlpvx': None, 'count_gamma': None, 'count_evaluated': None,
                        'count_infeasible': None, 'count_nan': None, 'elapsed_sec': None,
                        'I': I_val, 'J': J_val,
                        'output_path': None,
                    }
                    with lock:
                        results[(I_val, J_val)] = info
                        completed_count += 1
                    return
            else:
                stdout, _ = proc.communicate()

            elapsed_wall = time.time() - start_time

            # 找到输出文件
            out_file = None
            if stdout:
                m = re.search(r'输出已保存到:\s*output全枚举结�?([^\s]+)', stdout)
                if m:
                    src = os.path.join(OUTPUT_DIR, m.group(1))
                    if os.path.exists(src):
                        out_file = src

            info = parse_enum_output(out_file)
            info['I'] = I_val
            info['J'] = J_val
            info['elapsed_wall'] = elapsed_wall
            info['output_path'] = out_file
            info['returncode'] = proc.returncode

            with lock:
                results[(I_val, J_val)] = info
                completed_count += 1

            pi_str = f"{info['best_pi']:.6f}" if info['best_pi'] is not None else "N/A"
            phi_str = f"{info['best_phi']:.6f}" if info['best_phi'] is not None else "N/A"
            eval_str = str(info['count_evaluated']) if info['count_evaluated'] is not None else "N/A"
            time_str = f"{info['elapsed_sec']:.1f}s" if info['elapsed_sec'] is not None else "N/A"

            log(f"[结束 {completed_count}/{total_count}] {tag} -> "
                f"wall={elapsed_wall:.1f}s, CPU={time_str}, 评估={eval_str}, "
                f"Pi={pi_str}, Phi={phi_str}, 终止={info['termination']}")

        except Exception as e:
            elapsed_wall = time.time() - start_time
            if proc is not None and proc.poll() is None:
                proc.kill()
            with lock:
                results[(I_val, J_val)] = {
                    'returncode': -1, 'elapsed_wall': elapsed_wall,
                    'termination': f'异常: {e}',
                    'best_pi': None, 'best_phi': None,
                    'count_vlpvx': None, 'count_gamma': None, 'count_evaluated': None,
                    'count_infeasible': None, 'count_nan': None, 'elapsed_sec': None,
                    'I': I_val, 'J': J_val,
                    'output_path': None,
                }
                completed_count += 1
            log(f"[异常 {completed_count}/{total_count}] {tag} -> {e}, wall={elapsed_wall:.1f}s")


def save_csv(results_dict):
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = os.path.join(CSV_DIR, f"全枚举结果汇总_{timestamp}.csv")
    with open(csv_path, 'w', newline='', encoding='utf-8-sig') as f:
        writer = csv.writer(f)
        writer.writerow([
            'I(客户�?', 'J(候选点)', '文件�?,
            '枚举最优Pi', 'Phi', '可行(vL,vP,x)�?, '有效Gamma�?,
            '实际评估�?, '不可行数', 'NaN�?,
            'CPU耗时(s)', 'Wall耗时(s)', '终止原因', '输出文件'
        ])
        for key in sorted(results_dict.keys()):
            r = results_dict[key]
            writer.writerow([
                r['I'],
                r['J'],
                f"{r['I']}-{r['J']}.txt",
                f"{r['best_pi']:.6f}" if r['best_pi'] is not None else '',
                f"{r['best_phi']:.6f}" if r['best_phi'] is not None else '',
                r['count_vlpvx'] if r['count_vlpvx'] is not None else '',
                r['count_gamma'] if r['count_gamma'] is not None else '',
                r['count_evaluated'] if r['count_evaluated'] is not None else '',
                r['count_infeasible'] if r['count_infeasible'] is not None else '',
                r['count_nan'] if r['count_nan'] is not None else '',
                f"{r['elapsed_sec']:.2f}" if r['elapsed_sec'] is not None else '',
                f"{r['elapsed_wall']:.2f}" if r['elapsed_wall'] is not None else '',
                r['termination'],
                os.path.basename(r['output_path']) if r['output_path'] else ''
            ])
    log(f"CSV 汇总结果已保存: {csv_path}")
    return csv_path


def main():
    global total_count

    log(f"数据目录: {os.path.abspath(DATA_DIR)}")
    log(f"输出目录: {os.path.abspath(OUTPUT_DIR)}")

    # 收集所有数据文�?(alldata/*.txt)
    data_files = sorted(glob.glob(os.path.join(DATA_DIR, "*.txt")))

    total_count = len(data_files)
    log(f"�?{total_count} 个数据文件，并发�?{CONCURRENCY}")
    if total_count == 0:
        log("未找到数据文�?)
        sys.exit(1)

    output_dir = os.path.abspath(OUTPUT_DIR)
    os.makedirs(output_dir, exist_ok=True)

    # 列出所有文件及跳过状�?    for f in data_files:
        params = parse_params_from_path(f)
        if params is None:
            log(f"  [无法解析] {f}")
            continue
        tag = f"I{params['I']}-J{params['J']}"
        skip_mark = " [将跳过]" if should_skip(output_dir, params['I'], params['J']) else ""
        log(f"  - {tag}.txt{skip_mark}")

    if DO_COMPILE:
        compile_java()

    overall_start = time.time()

    threads = []
    for fp in data_files:
        params = parse_params_from_path(fp)
        if params is None:
            continue
        t = threading.Thread(target=run_single_enum, args=(params,))
        t.start()
        threads.append(t)

    for t in threads:
        t.join()

    overall_elapsed = time.time() - overall_start

    # 打印汇总表
    print("\n" + "=" * 100)
    print("全枚举验证汇总报告：")
    print("=" * 100)
    header = (
        f"{'I':>5} "
        f"{'J':>4} "
        f"{'评估�?:>10} "
        f"{'最优Pi':>16} "
        f"{'Phi':>16} "
        f"{'CPU(s)':>10} "
        f"{'终止原因':>16}"
    )
    print(header)
    print("-" * 100)

    for key in sorted(results.keys()):
        r = results[key]
        pi_str = f"{r['best_pi']:.6f}" if r['best_pi'] is not None else "N/A"
        phi_str = f"{r['best_phi']:.6f}" if r['best_phi'] is not None else "N/A"
        eval_str = str(r['count_evaluated']) if r['count_evaluated'] is not None else "N/A"
        time_str = f"{r['elapsed_sec']:.1f}" if r['elapsed_sec'] is not None else "N/A"
        term_str = r['termination'] if r['termination'] else "N/A"

        print(
            f"{r['I']:>5} "
            f"{r['J']:>4} "
            f"{eval_str:>10} "
            f"{pi_str:>16} "
            f"{phi_str:>16} "
            f"{time_str:>10} "
            f"{term_str:>16}"
        )

    print("-" * 100)
    log(f"总实验数: {len(results)}/{total_count}, 总耗时: {overall_elapsed:.1f}s ({overall_elapsed/60:.1f}min)")
    print("=" * 100)

    if results:
        save_csv(results)


if __name__ == "__main__":
    main()
