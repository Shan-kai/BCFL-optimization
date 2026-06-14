import glob
import os
import re
import subprocess
import sys
import time
import csv
import threading
import datetime

# 自适应切换到项目根目录（兼容脚本在 run_scripts/ 或项目根两种情况�?script_dir = os.path.dirname(os.path.abspath(__file__))
# 如果当前�?run_scripts/ 下，向上一级到项目�?if os.path.basename(script_dir) == 'run_scripts':
    os.chdir(os.path.dirname(script_dir))
else:
    os.chdir(script_dir)

# ==================== 配置�?====================
DATA_DIR = "alldata_R3_Mcoef"
PATTERN = os.path.join(DATA_DIR, "R3_*_coef_*.txt")
OUTPUT_DIR = "output_R3_Mcoef"
CSV_DIR = OUTPUT_DIR
CONCURRENCY = 1

JAVA_COMPILE_CMD = [
    'javac', '-encoding', 'UTF-8', '-cp', 'lib/cplex.jar', '-d', 'bin'
]
JAVA_CMD_BASE = [
    'java', '-Djava.library.path=D:\\cplex\\bin\\x64_win64',
    '-Dfile.encoding=UTF-8',
    '-cp', 'bin;lib/cplex.jar'
]

TIMEOUT_SEC = 0  # 不设超时，让算法自己跑完
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
        log("error: no Java files")
        sys.exit(1)

    cmd = JAVA_COMPILE_CMD + src_files
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='ignore')
        if result.returncode != 0:
            log("compile failed:")
            print(result.stdout)
            print(result.stderr)
            sys.exit(1)
        log(f"compile done: {len(src_files)} files")
    except Exception as e:
        log(f"compile error: {e}")
        sys.exit(1)


def parse_params_from_path(data_path):
    """从文件名解析 R3 �?coef: R3_5_coef_0.70.txt"""
    filename = os.path.basename(data_path)
    name_noext = os.path.splitext(filename)[0]  # 先去扩展�?    m = re.match(r'R3_(\d+)_coef_([0-9.]+)', name_noext)
    if not m:
        return None, None
    return int(m.group(1)), float(m.group(2))


def get_output_dir():
    """输出目录: output_R3_Mcoef/ (平铺)"""
    out_dir = os.path.abspath(OUTPUT_DIR)
    os.makedirs(out_dir, exist_ok=True)
    return out_dir


def find_output_file(r3, coef):
    """查找已有输出文件"""
    if not os.path.isdir(OUTPUT_DIR):
        return None
    # Main.java 输出命名: {timestamp}-R3_{r3}_coef_{coef}-output基础实验.txt
    pattern = os.path.join(OUTPUT_DIR, f"*R3_{r3}_coef_{coef:.2f}*output*.txt")
    candidates = glob.glob(pattern)
    if not candidates:
        return None
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return candidates[0]


def should_skip(r3, coef):
    if not SKIP_EXISTING:
        return False
    return find_output_file(r3, coef) is not None


def parse_output_file(path):
    """解析算法输出文件"""
    info = {
        'ub': None, 'lb': None, 'iterations': None,
        'total_time_sec': None, 'termination': '未知', 'gap': None,
        'sumDLin': None, 'sumDFin': None,
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

    # UB
    m = re.search(r'最终上�?UB = ([-+eE0-9.]+)', content)
    if m: info['ub'] = float(m.group(1))

    # LB
    m = re.search(r'最终下�?LB = ([-+eE0-9.]+)', content)
    if m: info['lb'] = float(m.group(1))

    # iterations
    m = re.search(r'总迭代次�?= (\d+)', content)
    if m: info['iterations'] = int(m.group(1))

    # total time
    m = re.search(r'算法总运行时�? ([0-9.]+) �?, content)
    if m: info['total_time_sec'] = float(m.group(1))

    # sumDLin
    m = re.search(r'sumDLin = .* = ([0-9.eE+-]+)', content)
    if m: info['sumDLin'] = float(m.group(1))

    # sumDFin
    m = re.search(r'sumDFin = .* = ([0-9.eE+-]+)', content)
    if m: info['sumDFin'] = float(m.group(1))

    # termination
    if '达到总时间上�?小时，算法终�? in content:
        info['termination'] = '时间上限(3h)'
    elif '达到最大迭代次数，算法终止' in content:
        info['termination'] = '最大迭代次�?
    elif '算法停滞' in content and '提前终止' in content:
        info['termination'] = '停滞提前终止'
    elif '主问题无法最优求解，算法终止' in content:
        info['termination'] = 'MP不可�?无界'
    elif '最终上�?UB' in content and '最终下�?LB' in content:
        info['termination'] = '收敛'

    # gap
    if info['ub'] is not None and info['lb'] is not None:
        ub = info['ub']
        lb = info['lb']
        if abs(ub) > 1e-9:
            info['gap'] = (ub - lb) / abs(ub)
        else:
            info['gap'] = 0.0 if abs(lb) < 1e-9 else float('inf')

    return info


# 并发控制
sem = threading.Semaphore(CONCURRENCY)
results = {}
lock = threading.Lock()
completed_count = 0
total_count = 0


def run_single_instance(data_path):
    global completed_count
    start_time = time.time()

    r3, coef = parse_params_from_path(data_path)
    if r3 is None:
        log(f"[skip] {data_path} -- parse failed")
        with lock:
            completed_count += 1
        return

    output_dir = get_output_dir()

    if should_skip(r3, coef):
        out_file = find_output_file(r3, coef)
        info = parse_output_file(out_file)
        info['R3'] = r3
        info['R4'] = 2 * r3
        info['coef'] = coef
        info['elapsed_wall'] = 0.0
        info['output_path'] = out_file
        info['returncode'] = 0
        with lock:
            results[(r3, coef)] = info
            completed_count += 1
        gap_str = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
        log(f"[skip {completed_count}/{total_count}] R3={r3}, coef={coef:.2f} -> "
            f"UB={info['ub']}, LB={info['lb']}, gap={gap_str}")
        return

    log(f"[start {completed_count+1}/{total_count}] R3={r3}, coef={coef:.2f}")
    with sem:
        cmd = JAVA_CMD_BASE + ['-Doutput.dir=' + output_dir, 'run.Main', data_path]
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
                    log(f"[timeout] R3={r3}, coef={coef:.2f}")
                    proc.kill()
                    stdout, _ = proc.communicate()
                    info = {
                        'returncode': -2, 'elapsed_wall': TIMEOUT_SEC,
                        'termination': f'timeout({TIMEOUT_SEC}s)',
                        'ub': None, 'lb': None, 'iterations': None, 'gap': None,
                        'sumDLin': None, 'sumDFin': None,
                        'R3': r3, 'R4': 2 * r3, 'coef': coef,
                        'output_path': None, 'total_time_sec': None,
                    }
                    with lock:
                        results[(r3, coef)] = info
                        completed_count += 1
                    return
            else:
                stdout, _ = proc.communicate()

            elapsed = time.time() - start_time

            time.sleep(0.5)
            out_file = find_output_file(r3, coef)
            info = parse_output_file(out_file)
            info['R3'] = r3
            info['R4'] = 2 * r3
            info['coef'] = coef
            info['elapsed_wall'] = elapsed
            info['output_path'] = out_file
            info['returncode'] = proc.returncode

            with lock:
                results[(r3, coef)] = info
                completed_count += 1

            gap_str = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
            iter_str = str(info['iterations']) if info['iterations'] is not None else "N/A"
            log(f"[done {completed_count}/{total_count}] R3={r3}, coef={coef:.2f} -> "
                f"wall={elapsed:.1f}s, iter={iter_str}, UB={info['ub']}, LB={info['lb']}, "
                f"gap={gap_str}, term={info['termination']}")
        except Exception as e:
            elapsed = time.time() - start_time
            if proc is not None and proc.poll() is None:
                proc.kill()
            with lock:
                results[(r3, coef)] = {
                    'returncode': -1, 'elapsed_wall': elapsed,
                    'termination': f'exception: {e}',
                    'ub': None, 'lb': None, 'iterations': None, 'gap': None,
                    'sumDLin': None, 'sumDFin': None,
                    'R3': r3, 'R4': 2 * r3, 'coef': coef,
                    'output_path': None, 'total_time_sec': None,
                }
                completed_count += 1
            log(f"[error {completed_count}/{total_count}] R3={r3}, coef={coef:.2f} -> {e}")


def save_csv(results_dict):
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = os.path.join(CSV_DIR, f"R3_Mcoef_sensitivity_{timestamp}.csv")
    with open(csv_path, 'w', newline='', encoding='utf-8-sig') as f:
        writer = csv.writer(f)
        writer.writerow([
            'R3(km)', 'R4(km)', 'coef',
            'final UB', 'final LB', 'relative gap(%)', 'iterations',
            'sumDLin', 'sumDFin', 'total demand',
            'runtime(s)', 'termination', 'output file'
        ])
        for key in sorted(results_dict.keys()):
            r = results_dict[key]
            total_demand = (r['sumDLin'] + r['sumDFin']) if (r['sumDLin'] is not None and r['sumDFin'] is not None) else None
            writer.writerow([
                r['R3'],
                r['R4'],
                r['coef'],
                f"{r['ub']:.6f}" if r['ub'] is not None else '',
                f"{r['lb']:.6f}" if r['lb'] is not None else '',
                f"{r['gap']*100:.4f}" if r['gap'] is not None else '',
                r['iterations'] if r['iterations'] is not None else '',
                f"{r['sumDLin']:.6f}" if r['sumDLin'] is not None else '',
                f"{r['sumDFin']:.6f}" if r['sumDFin'] is not None else '',
                f"{total_demand:.6f}" if total_demand is not None else '',
                f"{r['total_time_sec']:.2f}" if r['total_time_sec'] is not None else '',
                r['termination'],
                os.path.basename(r['output_path']) if r['output_path'] else ''
            ])
    log(f"CSV saved: {csv_path}")
    return csv_path


def main():
    global total_count

    log(f"Data dir: {os.path.abspath(DATA_DIR)}")
    log(f"Output dir: {os.path.abspath(OUTPUT_DIR)}")

    data_files = sorted(glob.glob(os.path.join(DATA_DIR, "R3_*_coef_*.txt")))
    if not data_files:
        log(f"No data files found: {PATTERN}")
        sys.exit(1)

    total_count = len(data_files)
    log(f"Total: {total_count} files, concurrency={CONCURRENCY}")

    for f in data_files:
        r3, coef = parse_params_from_path(f)
        skip_mark = " [SKIP]" if should_skip(r3, coef) else ""
        log(f"  - R3={r3}, coef={coef:.2f}{skip_mark}")

    if DO_COMPILE:
        compile_java()

    overall_start = time.time()

    threads = []
    for fp in data_files:
        t = threading.Thread(target=run_single_instance, args=(fp,))
        t.start()
        threads.append(t)

    for t in threads:
        t.join()

    overall_elapsed = time.time() - overall_start

    # 打印汇总表
    print("\n" + "=" * 120)
    print("R3 x coef sensitivity analysis complete:")
    print("=" * 120)
    header = (
        f"{'R3':>5} {'R4':>5} {'coef':>6} "
        f"{'iter':>6} {'UB':>14} {'LB':>14} {'gap':>12} "
        f"{'sumDLin':>14} {'sumDFin':>14} {'time(s)':>10} {'term':>16}"
    )
    print(header)
    print("-" * 120)

    for key in sorted(results.keys()):
        r = results[key]
        gap_str = f"{r['gap']*100:.2f}%" if r['gap'] is not None else "N/A"
        ub_str = f"{r['ub']:.4f}" if r['ub'] is not None else "N/A"
        lb_str = f"{r['lb']:.4f}" if r['lb'] is not None else "N/A"
        iter_str = str(r['iterations']) if r['iterations'] is not None else "N/A"
        time_str = f"{r['total_time_sec']:.1f}" if r['total_time_sec'] is not None else "N/A"
        dlin_str = f"{r['sumDLin']:.4f}" if r['sumDLin'] is not None else "N/A"
        dfin_str = f"{r['sumDFin']:.4f}" if r['sumDFin'] is not None else "N/A"

        print(
            f"{r['R3']:>5} {r['R4']:>5} {r['coef']:>6.2f} "
            f"{iter_str:>6} {ub_str:>14} {lb_str:>14} {gap_str:>12} "
            f"{dlin_str:>14} {dfin_str:>14} {time_str:>10} {r['termination']:>16}"
        )

    print("-" * 120)
    log(f"Total experiments: {len(results)}/{total_count}, total wall-clock: {overall_elapsed:.1f}s ({overall_elapsed/60:.1f}min)")
    print("=" * 120)

    if results:
        save_csv(results)


if __name__ == "__main__":
    main()
