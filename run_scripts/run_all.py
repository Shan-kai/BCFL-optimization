import glob
import os
import re
import subprocess
import threading
import time
import csv
import datetime
import sys

# 切换到项目根目录（run_scripts/的上层）
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ==================== 配置�?====================
DATA_DIR = "alldata"
PATTERN = os.path.join(DATA_DIR, "*.txt")
CONCURRENCY = 1           # 同时运行的进程数（CPLEX license 通常限制�?�?TIMEOUT_SEC = 0           # 单个实例超时时间（秒），0表示不限制（由Java主程序控制）
SKIP_EXISTING = True      # 如果已有成功的输出文件，是否跳过
SKIP_GAP_THRESHOLD = 1e-3 # 判定"已有成功结果"的间隙阈�?
JAVA_COMPILE_CMD = [
    'javac', '-encoding', 'UTF-8', '-cp', 'lib/cplex.jar',
    'src/input/*.java', 'src/utils/*.java', 'src/pool/*.java',
    'src/mp/*.java', 'src/sp/*.java', 'src/result/*.java', 'src/run/*.java',
    '-d', 'bin'
]
JAVA_RUN_CMD = [
    'java', '-Djava.library.path=C:\\cplex\\bin\\x64_win64',
    '-Dfile.encoding=UTF-8', '-cp', 'bin;lib/cplex.jar', 'run.Main'
]
OUTPUT_DIR = "output_MP(BI)SP(BI)"
# ================================================


def log(msg):
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def compile_java():
    """编译 Java 项目，若失败则退�?""
    log("开始编�?Java 项目...")
    # Windows �?glob 不会自动展开，需要用 shell=True 或手动展开
    # 这里手动构造完整命�?    cmd = [
        'javac', '-encoding', 'UTF-8', '-cp', 'lib/cplex.jar', '-d', 'bin'
    ]
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
    cmd.extend(src_files)

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


# 从数据文件名提取 I �?J，例�?"100-10.txt" -> (J=10, I=100)
def parse_ji_from_datafile(name):
    base = os.path.splitext(os.path.basename(name))[0]
    parts = base.split('-')
    if len(parts) >= 2:
        return int(parts[1]), int(parts[0])
    return None, None


def find_output_file(j, i):
    """根据 J,I 从输出目录查找对应的最新输出文�?""
    if not os.path.isdir(OUTPUT_DIR):
        return None
    pattern = os.path.join(OUTPUT_DIR, f"*I{i}J{j}.txt")
    candidates = glob.glob(pattern)
    if not candidates:
        return None
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return candidates[0]


def parse_output_file(path):
    """解析输出文件，提取收敛信�?""
    info = {
        'ub': None,
        'lb': None,
        'iterations': None,
        'total_time_sec': None,
        'termination': '未知',
        'gap': None,
    }
    if not path or not os.path.exists(path):
        return info

    # 尝试多种编码（Windows �?Java 默认输出可能�?GBK，但也可以显式指�?UTF-8�?    content = None
    for enc in ('utf-8', 'gbk', 'gb2312', 'utf-16'):
        try:
            with open(path, 'r', encoding=enc, errors='ignore') as f:
                content = f.read()
            break
        except Exception:
            continue
    if content is None:
        return info

    # 提取最终上�?    m = re.search(r'最终上�?UB = ([-+eE0-9.]+)', content)
    if m:
        info['ub'] = float(m.group(1))

    # 提取最终下�?    m = re.search(r'最终下�?LB = ([-+eE0-9.]+)', content)
    if m:
        info['lb'] = float(m.group(1))

    # 提取总迭代次�?    m = re.search(r'总迭代次�?= (\d+)', content)
    if m:
        info['iterations'] = int(m.group(1))

    # 提取算法总运行时�?    m = re.search(r'算法总运行时�? ([0-9.]+) �?, content)
    if m:
        info['total_time_sec'] = float(m.group(1))

    # 终止原因
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

    # 计算相对间隙
    if info['ub'] is not None and info['lb'] is not None:
        ub = info['ub']
        lb = info['lb']
        if abs(ub) > 1e-9:
            info['gap'] = (ub - lb) / abs(ub)
        else:
            info['gap'] = 0.0 if abs(lb) < 1e-9 else float('inf')

    return info


def should_skip(data_path, j, i):
    """判断该实例是否应该跳�?""
    if not SKIP_EXISTING:
        return False
    out_file = find_output_file(j, i)
    if not out_file:
        return False
    return True


# 并发控制
sem = threading.Semaphore(CONCURRENCY)
results = {}
lock = threading.Lock()
completed_count = 0
total_count = 0


def run_instance(data_path):
    global completed_count
    name = os.path.basename(data_path)
    j, i = parse_ji_from_datafile(name)
    start_time = time.time()

    # 检查是否跳�?    if should_skip(data_path, j, i):
        out_file = find_output_file(j, i)
        info = parse_output_file(out_file)
        info['returncode'] = 0
        info['elapsed_wall'] = 0.0
        info['output_path'] = out_file
        info['skipped'] = True
        with lock:
            results[name] = info
            completed_count += 1
        gap_str = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
        log(f"[跳过 {completed_count}/{total_count}] {name} (J={j}, I={i})  已有结果: UB={info['ub']:.4f}, LB={info['lb']:.4f}, gap={gap_str}")
        return

    log(f"[开�?{completed_count+1}/{total_count}] {name} (J={j}, I={i})")
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
            # 带超时的等待
            if TIMEOUT_SEC > 0:
                try:
                    stdout, _ = proc.communicate(timeout=TIMEOUT_SEC)
                except subprocess.TimeoutExpired:
                    log(f"[超时] {name} 超过 {TIMEOUT_SEC}s，强制终�?)
                    proc.kill()
                    stdout, _ = proc.communicate()
                    info = {
                        'returncode': -2,
                        'elapsed_wall': TIMEOUT_SEC,
                        'termination': f'超时({TIMEOUT_SEC}s)',
                        'ub': None, 'lb': None, 'iterations': None, 'gap': None,
                        'output_path': None, 'total_time_sec': None, 'skipped': False
                    }
                    with lock:
                        results[name] = info
                        completed_count += 1
                    return
            else:
                stdout, _ = proc.communicate()

            elapsed = time.time() - start_time

            # 查找输出文件并解�?            out_file = find_output_file(j, i) if (j and i) else None
            info = parse_output_file(out_file)
            info['returncode'] = proc.returncode
            info['elapsed_wall'] = elapsed
            info['output_path'] = out_file
            info['skipped'] = False

            with lock:
                results[name] = info
                completed_count += 1

            status = "成功" if proc.returncode == 0 else f"失败(�?{proc.returncode})"
            gap_str = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
            ub_str = f"{info['ub']:.4f}" if info['ub'] is not None else "N/A"
            lb_str = f"{info['lb']:.4f}" if info['lb'] is not None else "N/A"
            log(f"[结束 {completed_count}/{total_count}] {name} -> {status}, wall={elapsed:.1f}s, 输出={info.get('total_time_sec')}s, 迭代={info.get('iterations')}, UB={ub_str}, LB={lb_str}, 间隙={gap_str}")
        except Exception as e:
            elapsed = time.time() - start_time
            if proc is not None and proc.poll() is None:
                proc.kill()
            with lock:
                results[name] = {
                    'returncode': -1,
                    'elapsed_wall': elapsed,
                    'termination': f'异常: {e}',
                    'ub': None, 'lb': None, 'iterations': None, 'gap': None,
                    'output_path': None, 'total_time_sec': None, 'skipped': False
                }
                completed_count += 1
            log(f"[异常 {completed_count}/{total_count}] {name} -> {e}, 耗时={elapsed:.1f}s")


def save_csv(results):
    """将结果保存为 CSV"""
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    csv_path = f"results_{timestamp}.csv"
    with open(csv_path, 'w', newline='', encoding='utf-8-sig') as f:
        writer = csv.writer(f)
        writer.writerow(['数据文件', 'J', 'I', '迭代次数', '最终UB', '最终LB', '相对间隙(%)',
                         '运行时间(s)', '终止原因', '输出文件', '是否跳过'])
        for name in sorted(results.keys()):
            r = results[name]
            j, i = parse_ji_from_datafile(name)
            writer.writerow([
                name,
                j if j is not None else '',
                i if i is not None else '',
                r.get('iterations') if r.get('iterations') is not None else '',
                f"{r['ub']:.6f}" if r['ub'] is not None else '',
                f"{r['lb']:.6f}" if r['lb'] is not None else '',
                f"{r['gap']*100:.4f}" if r['gap'] is not None else '',
                f"{r['total_time_sec']:.2f}" if r['total_time_sec'] is not None else '',
                r['termination'],
                os.path.basename(r['output_path']) if r['output_path'] else '',
                '�? if r.get('skipped') else '�?
            ])
    log(f"CSV 结果已保�? {csv_path}")


def main():
    global total_count

    # 1. 编译
    compile_java()

    # 2. 扫描数据文件
    data_files = sorted(glob.glob(PATTERN))
    if not data_files:
        log(f"未找到数据文�? {PATTERN}")
        sys.exit(1)

    # �?I 从小到大排序，I 相同则按 J 从小到大排序
    def _sort_key(p):
        j, i = parse_ji_from_datafile(os.path.basename(p))
        return (i if i is not None else float('inf'), j if j is not None else float('inf'))
    data_files = sorted(glob.glob(PATTERN), key=_sort_key)

    total_count = len(data_files)
    log(f"共找�?{total_count} 个数据文件，并发�?{CONCURRENCY}, 超时={TIMEOUT_SEC}s, 跳过已有结果={SKIP_EXISTING}")
    for f in data_files:
        j, i = parse_ji_from_datafile(os.path.basename(f))
        skip_mark = "[将跳过]" if should_skip(f, j, i) else ""
        log(f"  - {os.path.basename(f)} (J={j}, I={i}) {skip_mark}")

    # 3. 启动所有任�?    threads = []
    overall_start = time.time()
    for fp in data_files:
        t = threading.Thread(target=run_instance, args=(fp,))
        t.start()
        threads.append(t)

    # 4. 等待全部完成
    for t in threads:
        t.join()

    overall_elapsed = time.time() - overall_start

    # 5. 汇总报�?    print("\n" + "=" * 110)
    print("批量运行完成，详细汇总报告如下：")
    print("=" * 110)
    header = (
        f"{'数据文件':<14} "
        f"{'迭代次数':>10} "
        f"{'最终UB':>14} "
        f"{'最终LB':>14} "
        f"{'相对间隙':>12} "
        f"{'运行时间(s)':>12} "
        f"{'终止原因':>14} "
        f"{'跳过':>6} "
        f"{'输出文件':>20}"
    )
    print(header)
    print("-" * 110)

    success_count = 0
    fail_count = 0
    skip_count = 0
    for name in sorted(results.keys()):
        r = results[name]
        if r.get('skipped'):
            skip_count += 1
            success_count += 1
        elif r['returncode'] == 0:
            success_count += 1
        else:
            fail_count += 1

        iter_str = str(r['iterations']) if r['iterations'] is not None else "N/A"
        ub_str = f"{r['ub']:.4f}" if r['ub'] is not None else "N/A"
        lb_str = f"{r['lb']:.4f}" if r['lb'] is not None else "N/A"
        gap_str = f"{r['gap']*100:.2f}%" if r['gap'] is not None else "N/A"
        time_str = f"{r['total_time_sec']:.1f}" if r['total_time_sec'] is not None else "N/A"
        term_str = r['termination'] if r['termination'] else "N/A"
        out_name = os.path.basename(r['output_path']) if r['output_path'] else "N/A"
        skip_str = "�? if r.get('skipped') else "�?

        print(
            f"{name:<14} "
            f"{iter_str:>10} "
            f"{ub_str:>14} "
            f"{lb_str:>14} "
            f"{gap_str:>12} "
            f"{time_str:>12} "
            f"{term_str:>14} "
            f"{skip_str:>6} "
            f"{out_name:>20}"
        )

    print("-" * 110)
    log(f"总实例数: {len(results)}, 成功: {success_count}, 失败: {fail_count}, 跳过: {skip_count}")
    log(f"�?wall-clock 耗时: {overall_elapsed:.1f}s ({overall_elapsed/60:.1f}min)")
    print("=" * 110)

    # 6. 保存 CSV
    save_csv(results)


if __name__ == "__main__":
    main()
