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
DATA_DIR = "alldata_lambda_l_f-Bl_bf-lambda"
TARGET_LAMBDAS = ["lambda0.1", "lambda0.8", "lambda1.5"]
OUTPUT_DIR = "output_lambda_l_f_Blbf"
CSV_DIR = OUTPUT_DIR
CONCURRENCY = 2

JAVA_COMPILE_CMD = [
    'javac', '-encoding', 'UTF-8', '-cp', 'lib/cplex.jar', '-d', 'bin'
]
JAVA_CMD_BASE = [
    'java', '-Djava.library.path=D:\\cplex\\bin\\x64_win64',
    '-Dfile.encoding=UTF-8',
    '-cp', 'bin;lib/cplex.jar'
]

TIMEOUT_SEC = 0
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


def parse_blbf_params(blbf_name):
    """解析 BLBF 目录�?�?(B_l, B_f)，如 Bl1kw_Bf1kw �?(10000000, 10000000)"""
    m = re.match(r'Bl([0-9.]+)kw_Bf([0-9.]+)kw', blbf_name)
    if m:
        bl_val = float(m.group(1)) * 10000000
        bf_val = float(m.group(2)) * 10000000
        return int(bl_val), int(bf_val)
    return None, None


def parse_params_from_path(data_path):
    """从数据文件路径解析参数：blbf, lam, lam_l, lam_f, lam_folder"""
    norm = data_path.replace('\\', '/')
    parts = norm.split('/')
    filename = os.path.splitext(os.path.basename(data_path))[0]
    lam_folder = parts[-2]
    blbf = parts[-3]

    m = re.match(r'lambda_l([0-9.]+)_f([0-9.]+)', filename)
    if not m:
        return None, None, None, None, None
    lam_l, lam_f = float(m.group(1)), float(m.group(2))
    lam = float(lam_folder.replace('lambda', ''))

    return blbf, lam, lam_l, lam_f, lam_folder


def get_output_dir(blbf, lam_folder):
    """构造输出目录路径并确保存在"""
    out_dir = os.path.join(os.path.abspath(OUTPUT_DIR), blbf, lam_folder)
    os.makedirs(out_dir, exist_ok=True)
    return out_dir


def find_output_file(output_dir, lam_l, lam_f):
    if not os.path.isdir(output_dir):
        return None
    pattern = os.path.join(output_dir, f"*lambda_l{lam_l}_f{lam_f}*output*.txt")
    candidates = glob.glob(pattern)
    if not candidates:
        return None
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return candidates[0]


def should_skip(output_dir, lam_l, lam_f):
    if not SKIP_EXISTING:
        return False
    return find_output_file(output_dir, lam_l, lam_f) is not None


def parse_output_file(path):
    info = {
        'ub': None, 'lb': None, 'iterations': None,
        'total_time_sec': None, 'termination': '未知', 'gap': None,
        'sumDLin': None, 'sumDFin': None,
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

    m = re.search(r'lambda = ([0-9.eE+-]+)', content)
    if m: info['lambda'] = float(m.group(1))

    m = re.search(r'lambda_L = ([0-9.eE+-]+)', content)
    if m: info['lambda_l'] = float(m.group(1))

    m = re.search(r'lambda_F = ([0-9.eE+-]+)', content)
    if m: info['lambda_f'] = float(m.group(1))

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


# 并发控制
sem = threading.Semaphore(CONCURRENCY)
results = {}
lock = threading.Lock()
completed_count = 0
total_count = 0


def run_single_instance(data_path):
    global completed_count
    start_time = time.time()

    blbf, lam, lam_l, lam_f, lam_folder = parse_params_from_path(data_path)
    if blbf is None:
        log(f"[跳过] {data_path} �?无法解析参数")
        with lock:
            completed_count += 1
        return

    output_dir = get_output_dir(blbf, lam_folder)

    # 检查是否跳�?    if should_skip(output_dir, lam_l, lam_f):
        out_file = find_output_file(output_dir, lam_l, lam_f)
        info = parse_output_file(out_file)
        info['blbf'] = blbf
        info['lambda'] = lam
        info['lambda_l'] = lam_l
        info['lambda_f'] = lam_f
        info['elapsed_wall'] = 0.0
        info['output_path'] = out_file
        info['returncode'] = 0
        with lock:
            results[(blbf, lam, lam_l, lam_f)] = info
            completed_count += 1
        gap_str = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
        ub_str = f"{info['ub']:.4f}" if info['ub'] is not None else "N/A"
        lb_str = f"{info['lb']:.4f}" if info['lb'] is not None else "N/A"
        log(f"[跳过 {completed_count}/{total_count}] {blbf}/{lam} (l={lam_l}, f={lam_f}) -> UB={ub_str}, LB={lb_str}, 间隙={gap_str}")
        return

    log(f"[开�?{completed_count+1}/{total_count}] {blbf} lam={lam} l={lam_l} f={lam_f}")
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
                    log(f"[超时] {blbf}/{lam} (l={lam_l}, f={lam_f}) 超过 {TIMEOUT_SEC}s，强制终�?)
                    proc.kill()
                    stdout, _ = proc.communicate()
                    info = {
                        'returncode': -2, 'elapsed_wall': TIMEOUT_SEC,
                        'termination': f'超时({TIMEOUT_SEC}s)',
                        'ub': None, 'lb': None, 'iterations': None, 'gap': None,
                        'sumDLin': None, 'sumDFin': None,
                        'blbf': blbf, 'lambda': lam, 'lambda_l': lam_l, 'lambda_f': lam_f,
                        'output_path': None, 'total_time_sec': None,
                    }
                    with lock:
                        results[(blbf, lam, lam_l, lam_f)] = info
                        completed_count += 1
                    return
            else:
                stdout, _ = proc.communicate()

            elapsed = time.time() - start_time

            time.sleep(0.5)
            out_file = find_output_file(output_dir, lam_l, lam_f)
            info = parse_output_file(out_file)
            info['blbf'] = blbf
            info['lambda'] = lam
            info['lambda_l'] = lam_l
            info['lambda_f'] = lam_f
            info['elapsed_wall'] = elapsed
            info['output_path'] = out_file
            info['returncode'] = proc.returncode

            with lock:
                results[(blbf, lam, lam_l, lam_f)] = info
                completed_count += 1

            gap_str = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
            ub_str = f"{info['ub']:.4f}" if info['ub'] is not None else "N/A"
            lb_str = f"{info['lb']:.4f}" if info['lb'] is not None else "N/A"
            iter_str = str(info['iterations']) if info['iterations'] is not None else "N/A"
            time_str = f"{info['total_time_sec']:.1f}" if info['total_time_sec'] is not None else "N/A"

            log(f"[结束 {completed_count}/{total_count}] {blbf}/{lam} (l={lam_l}, f={lam_f}) -> wall={elapsed:.1f}s, "
                f"迭代={iter_str}, UB={ub_str}, LB={lb_str}, 间隙={gap_str}, 终止={info['termination']}")
        except Exception as e:
            elapsed = time.time() - start_time
            if proc is not None and proc.poll() is None:
                proc.kill()
            with lock:
                results[(blbf, lam, lam_l, lam_f)] = {
                    'returncode': -1, 'elapsed_wall': elapsed,
                    'termination': f'异常: {e}',
                    'ub': None, 'lb': None, 'iterations': None, 'gap': None,
                    'sumDLin': None, 'sumDFin': None,
                    'blbf': blbf, 'lambda': lam, 'lambda_l': lam_l, 'lambda_f': lam_f,
                    'output_path': None, 'total_time_sec': None,
                }
                completed_count += 1
            log(f"[异常 {completed_count}/{total_count}] {blbf}/{lam} (l={lam_l}, f={lam_f}) -> {e}, 耗时={elapsed:.1f}s")


def save_csv(results_dict):
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = os.path.join(CSV_DIR, f"lambda_l_f_Blbf_3lambda_sensitivity_{timestamp}.csv")
    with open(csv_path, 'w', newline='', encoding='utf-8-sig') as f:
        writer = csv.writer(f)
        writer.writerow([
            'BLBF', 'B_l', 'B_f', 'lambda', 'lambda_l', 'lambda_f',
            '最终UB', '最终LB', '相对间隙(%)', '迭代次数',
            '领导者需求sumDLin', '追随者需求sumDFin', '总需�?,
            '运行时间(s)', '终止原因', '输出文件'
        ])
        for key in sorted(results_dict.keys()):
            r = results_dict[key]
            b_l, b_f = parse_blbf_params(r['blbf'])
            total_demand = (r['sumDLin'] + r['sumDFin']) if (r['sumDLin'] is not None and r['sumDFin'] is not None) else None
            writer.writerow([
                r['blbf'],
                b_l,
                b_f,
                r['lambda'],
                r['lambda_l'],
                r['lambda_f'],
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

    log(f"数据目录: {os.path.abspath(DATA_DIR)}")
    log(f"CSV 输出目录: {os.path.abspath(CSV_DIR)}")
    log(f"目标 lambda �?文件�?: {TARGET_LAMBDAS}")

    data_files = sorted(glob.glob(os.path.join(DATA_DIR, "**", "*.txt"), recursive=True))

    # 过滤掉可能的输出文件
    data_files = [f for f in data_files if not os.path.basename(f).startswith(tuple('0123456789'))]

    # 只保留目�?lambda 文件夹下的文�?    data_files = [
        f for f in data_files
        if any(f"/{lam}/" in f.replace('\\', '/') for lam in TARGET_LAMBDAS)
    ]

    # 验证文件路径深度（确保是 3 层结构）
    valid = []
    for f in data_files:
        norm = f.replace('\\', '/')
        rel = os.path.relpath(norm, DATA_DIR).replace('\\', '/')
        if rel.count('/') == 2:
            valid.append(f)
        else:
            log(f"跳过非标准路径文�? {f}")
    data_files = valid

    if not data_files:
        log("未找到匹配的数据文件")
        sys.exit(1)

    total_count = len(data_files)
    log(f"�?{total_count} 个数据文件需要实验，并发�?{CONCURRENCY}")
    for f in data_files:
        blbf, lam, lam_l, lam_f, lam_folder = parse_params_from_path(f)
        out_dir = get_output_dir(blbf, lam_folder)
        skip_mark = " [将跳过]" if should_skip(out_dir, lam_l, lam_f) else ""
        log(f"  - {blbf}/{lam_folder}/lambda_l{lam_l}_f{lam_f}.txt{skip_mark}")

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

    print("\n" + "=" * 140)
    print("lambda_l/lambda_f × B_l/B_f × lambda(3�? 敏感性分析实验完成，汇总报告如下：")
    print("=" * 140)
    header = (
        f"{'BLBF':<14} "
        f"{'lambda':>6} "
        f"{'lam_l':>6} "
        f"{'lam_f':>6} "
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

        print(
            f"{r['blbf']:<14} "
            f"{r['lambda']:>6.1f} "
            f"{r['lambda_l']:>6.1f} "
            f"{r['lambda_f']:>6.1f} "
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
