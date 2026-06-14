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

DATA_DIR = 'alldata_Q2Q3敏感性分�?
PATTERN = os.path.join(DATA_DIR, '*.txt')
OUTPUT_DIR = 'output_Q2Q3敏感性分�?
CONCURRENCY = 2

JAVA_COMPILE_CMD = [
    'javac', '-encoding', 'UTF-8', '-cp', 'lib/cplex.jar', '-d', 'bin'
]
JAVA_RUN_CMD = [
    'java', '-Djava.library.path=D:\\cplex\\bin\\x64_win64',
    '-Dfile.encoding=UTF-8', '-Doutput.dir=' + OUTPUT_DIR,
    '-cp', 'bin;lib/cplex.jar', 'run.Main'
]

TIMEOUT_SEC = 0
DO_COMPILE = True
SKIP_EXISTING = True

# Q3 = k * q2, 无需独立基�?

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


def parse_params_from_filename(name):
    base = os.path.splitext(os.path.basename(name))[0]
    m = re.match(r'q2(\d+)_k([0-9.]+)', base)
    if m:
        return int(m.group(1)), float(m.group(2))
    return None, None


def find_output_file(q2_val, k_val):
    if not os.path.isdir(OUTPUT_DIR):
        return None
    pattern = os.path.join(OUTPUT_DIR, f"*q2{q2_val}_k{k_val}*.txt")
    candidates = glob.glob(pattern)
    if not candidates:
        return None
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return candidates[0]


def should_skip(q2_val, k_val):
    if not SKIP_EXISTING:
        return False
    out_file = find_output_file(q2_val, k_val)
    if not out_file:
        return False
    return True


def parse_output_file(path):
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
    m = re.search(r'最终上�?UB = ([-+eE0-9.]+)', content)
    if m: info['ub'] = float(m.group(1))
    m = re.search(r'最终下�?LB = ([-+eE0-9.]+)', content)
    if m: info['lb'] = float(m.group(1))
    m = re.search(r'总迭代次�?= (\d+)', content)
    if m: info['iterations'] = int(m.group(1))
    m = re.search(r'算法总运行时�? ([0-9.]+) �?, content)
    if m: info['total_time_sec'] = float(m.group(1))
    m = re.search(r'sumDLin = Σ_i Σ_n D_in\^L\(y\*, z\*\) = ([0-9.eE+-]+)', content)
    if m: info['sumDLin'] = float(m.group(1))
    m = re.search(r'sumDFin = Σ_i Σ_n D_in\^F\(y\*, z\*\) = ([0-9.eE+-]+)', content)
    if m: info['sumDFin'] = float(m.group(1))
    if '达到总时间上�?小时，算法终�? in content:
        info['termination'] = '时间上限(3h)'
    elif '达到最大迭代次数，算法终止' in content:
        info['termination'] = '最大迭代次�?
    elif '算法停滞，连�? in content and '提前终止' in content:
        info['termination'] = '停滞提前终止'
    elif '主问题无法最优求解，算法终止' in content:
        info['termination'] = 'MP不可�?无界'
    elif '最终上�?UB' in content and '最终下�?LB' in content:
        info['termination'] = '收敛'
    if info['ub'] is not None and info['lb'] is not None:
        ub = info['ub']
        lb = info['lb']
        if abs(ub) > 1e-9:
            info['gap'] = (ub - lb) / abs(ub)
        else:
            info['gap'] = 0.0 if abs(lb) < 1e-9 else float('inf')
    return info


sem = threading.Semaphore(CONCURRENCY)
results = {}
lock = threading.Lock()
completed_count = 0
total_count = 0


def run_single_instance(data_path, q2_val, k_val, idx, total):
    global completed_count
    start_time = time.time()

    if should_skip(q2_val, k_val):
        out_file = find_output_file(q2_val, k_val)
        info = parse_output_file(out_file)
        info['q2'] = q2_val
        info['k'] = k_val
        info['elapsed_wall'] = 0.0
        info['output_path'] = out_file
        info['returncode'] = 0
        with lock:
            results[(q2_val, k_val)] = info
            completed_count += 1
        gap_str = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
        ub_str = f"{info['ub']:.4f}" if info['ub'] is not None else "N/A"
        lb_str = f"{info['lb']:.4f}" if info['lb'] is not None else "N/A"
        log(f"[跳过 {completed_count}/{total}] q2={q2_val}, k={k_val} -> UB={ub_str}, LB={lb_str}, 间隙={gap_str}")
        return

    log(f"[开�?{completed_count+1}/{total}] q2={q2_val}, k={k_val}")
    with sem:
        cmd = JAVA_RUN_CMD + [data_path]
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
                    log(f"[超时] q2={q2_val}, k={k_val} 超过 {TIMEOUT_SEC}s，强制终�?)
                    proc.kill()
                    stdout, _ = proc.communicate()
                    info = {
                        'returncode': -2, 'elapsed_wall': TIMEOUT_SEC,
                        'termination': f'超时({TIMEOUT_SEC}s)',
                        'ub': None, 'lb': None, 'iterations': None, 'gap': None,
                        'sumDLin': None, 'sumDFin': None,
                        'q2': q2_val, 'k': k_val,
                        'output_path': None, 'total_time_sec': None,
                    }
                    with lock:
                        results[(q2_val, k_val)] = info
                        completed_count += 1
                    return
            else:
                stdout, _ = proc.communicate()

            elapsed = time.time() - start_time
            time.sleep(0.5)
            out_file = find_output_file(q2_val, k_val)
            info = parse_output_file(out_file)
            info['q2'] = q2_val
            info['k'] = k_val
            info['elapsed_wall'] = elapsed
            info['output_path'] = out_file
            info['returncode'] = proc.returncode

            with lock:
                results[(q2_val, k_val)] = info
                completed_count += 1

            gap_str = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
            ub_str = f"{info['ub']:.4f}" if info['ub'] is not None else "N/A"
            lb_str = f"{info['lb']:.4f}" if info['lb'] is not None else "N/A"
            iter_str = str(info['iterations']) if info['iterations'] is not None else "N/A"
            time_str = f"{info['total_time_sec']:.1f}" if info['total_time_sec'] is not None else "N/A"

            log(f"[结束 {completed_count}/{total}] q2={q2_val}, k={k_val} -> wall={elapsed:.1f}s, "
                f"迭代={iter_str}, UB={ub_str}, LB={lb_str}, 间隙={gap_str}")
        except Exception as e:
            elapsed = time.time() - start_time
            if proc is not None and proc.poll() is None:
                proc.kill()
            with lock:
                results[(q2_val, k_val)] = {
                    'returncode': -1, 'elapsed_wall': elapsed,
                    'termination': f'异常: {e}',
                    'ub': None, 'lb': None, 'iterations': None, 'gap': None,
                    'sumDLin': None, 'sumDFin': None,
                    'q2': q2_val, 'k': k_val,
                    'output_path': None, 'total_time_sec': None,
                }
                completed_count += 1
            log(f"[异常 {completed_count}/{total}] q2={q2_val}, k={k_val} -> {e}, 耗时={elapsed:.1f}s")


def save_csv(results_dict):
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = os.path.join(OUTPUT_DIR, f"results_Q2Q3_sensitivity_{timestamp}.csv")
    with open(csv_path, 'w', newline='', encoding='utf-8-sig') as f:
        writer = csv.writer(f)
        writer.writerow([
            'q2 (Q_{s=2})', 'k', 'Q_{s=3} = k * 2.36',
            '最终UB', '最终LB', '相对间隙(%)', '迭代次数',
            '领导者需求sumDLin', '追随者需求sumDFin', '总需�?,
            '运行时间(s)', '终止原因', '输出文件'
        ])
        for key in sorted(results_dict.keys()):
            r = results_dict[key]
            total_demand = (r['sumDLin'] + r['sumDFin']) if (r['sumDLin'] is not None and r['sumDFin'] is not None) else None
            q3_val = round(r['k'] * r['q2'], 4) if (r['k'] is not None and r['q2'] is not None) else None
            writer.writerow([
                r['q2'],
                r['k'],
                q3_val,
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
    log(f"CSV 汇总结果已保存: {csv_path}")
    return csv_path


def main():
    global total_count

    if not os.path.exists(OUTPUT_DIR):
        os.makedirs(OUTPUT_DIR)
    log(f"输出目录: {OUTPUT_DIR}")

    data_files = sorted(glob.glob(PATTERN), key=lambda p: parse_params_from_filename(p))
    if not data_files:
        log(f"未找到数据文�? {PATTERN}")
        sys.exit(1)

    total_count = len(data_files)
    log(f"�?{total_count} 个数据文件需要实验，并发�?{CONCURRENCY}")
    for f in data_files:
        q2_val, k_val = parse_params_from_filename(f)
        q3_val = round(k_val * q2_val, 4) if q2_val > 0 else 0
        log(f"  - {os.path.basename(f)} (q2={q2_val}, k={k_val}, Q3={q3_val})")

    if DO_COMPILE:
        compile_java()

    overall_start = time.time()

    threads = []
    for idx, fp in enumerate(data_files, 1):
        q2_val, k_val = parse_params_from_filename(fp)
        t = threading.Thread(target=run_single_instance, args=(fp, q2_val, k_val, idx, total_count))
        t.start()
        threads.append(t)

    for t in threads:
        t.join()

    overall_elapsed = time.time() - overall_start

    print("\n" + "=" * 140)
    print("Q2 × Q3 敏感性分析实验完成，汇总报告如下：")
    print("=" * 140)
    header = (
        f"{'q2':>4} "
        f"{'k':>5} "
        f"{'Q3':>8} "
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
    print("-" * 140)

    for key in sorted(results.keys()):
        r = results[key]
        gap_str = f"{r['gap']*100:.2f}%" if r['gap'] is not None else "N/A"
        ub_str = f"{r['ub']:.4f}" if r['ub'] is not None else "N/A"
        lb_str = f"{r['lb']:.4f}" if r['lb'] is not None else "N/A"
        iter_str = str(r['iterations']) if r['iterations'] is not None else "N/A"
        time_str = f"{r['total_time_sec']:.1f}" if r['total_time_sec'] is not None else "N/A"
        dlin_str = f"{r['sumDLin']:.4f}" if r['sumDLin'] is not None else "N/A"
        dfin_str = f"{r['sumDFin']:.4f}" if r['sumDFin'] is not None else "N/A"
        term_str = r['termination'] if r['termination'] else "N/A"
        q3_val = round(r['k'] * r['q2'], 4)

        print(
            f"{r['q2']:>4d} "
            f"{r['k']:>5.1f} "
            f"{q3_val:>8.4f} "
            f"{iter_str:>10} "
            f"{ub_str:>14} "
            f"{lb_str:>14} "
            f"{gap_str:>12} "
            f"{dlin_str:>14} "
            f"{dfin_str:>14} "
            f"{time_str:>12} "
            f"{term_str:>14}"
        )

    print("-" * 140)
    log(f"总实验数: {len(results)}/{total_count}, �?wall-clock 耗时: {overall_elapsed:.1f}s ({overall_elapsed/60:.1f}min)")
    print("=" * 140)

    if results:
        save_csv(results)


if __name__ == "__main__":
    main()
