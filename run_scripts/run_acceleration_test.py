import glob
import os
import re
import subprocess
import time
import csv
import datetime
import sys

os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ==================== 配置�?====================
import argparse
parser = argparse.ArgumentParser(description='加速策略对比测�?)
parser.add_argument('--data', default='data.txt', help='数据文件路径')
parser.add_argument('--outdir', default='test_acceleration', help='输出根目�?)
parser.add_argument('--runs', type=int, default=5, help='每配置运行次�?)
args = parser.parse_args()

DATA_FILE = args.data
RUNS_PER_CONFIG = args.runs
OUTPUT_BASE_DIR = args.outdir

CONFIGS = [
    ("基线",              "A_基线",              []),
    ("固定变量",          "B_固定变量",          ['-Dfix.var=true']),
    ("单点覆盖",          "C_单点覆盖",          ['-Dfix.singlecov=true']),
    ("聚合覆盖",          "D_聚合覆盖",          ['-Dfix.aggcov=true']),
    ("最大开�?,          "E_最大开�?,          ['-Dfix.maxstore=true']),
    ("T求和",             "F_T求和",             ['-Dfix.tsum=true']),
]
# ================================================


def log(msg):
    now = datetime.datetime.now().strftime("%H:%M:%S")
    print(f"[{now}] {msg}", flush=True)


def compile_java():
    src_files = []
    for p in ['src/input/*.java', 'src/utils/*.java', 'src/pool/*.java',
              'src/mp/*.java', 'src/sp/*.java', 'src/result/*.java', 'src/run/*.java']:
        src_files.extend(glob.glob(p))
    cmd = ['javac', '-encoding', 'UTF-8', '-cp', 'lib/cplex.jar', '-d', 'bin'] + src_files
    result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='ignore')
    if result.returncode != 0:
        log("编译失败"); print(result.stderr); sys.exit(1)
    log(f"编译成功 ({len(src_files)} 文件)")


def build_cmd(extra_jvm_args, output_dir):
    cmd = ['java'] + extra_jvm_args + [
        f'-Doutput.dir={output_dir}',
        '-Djava.library.path=D:\\cplex\\bin\\x64_win64',
        '-Dfile.encoding=UTF-8',
        '-cp', 'bin;lib/cplex.jar', 'run.Main', DATA_FILE
    ]
    return cmd


def find_latest_output(output_dir):
    if not os.path.isdir(output_dir):
        return None
    candidates = glob.glob(os.path.join(output_dir, "*.txt"))
    if not candidates:
        return None
    candidates.sort(key=os.path.getmtime, reverse=True)
    return candidates[0]


def parse_output(path):
    info = {
        'ub': None, 'lb': None, 'gap': None,
        'iterations': None, 'total_time_sec': None,
        'termination': '未知',
        'fixed_vp': 0, 'fixed_z': 0, 'single_cov': 0,
    }
    if not path or not os.path.exists(path):
        return info
    content = None
    for enc in ('utf-8', 'gbk', 'gb2312'):
        try:
            with open(path, 'r', encoding=enc, errors='ignore') as f:
                content = f.read()
            break
        except Exception:
            continue
    if not content:
        return info

    m = re.search(r'最终上�?UB = ([-+eE0-9.]+)', content)
    if m: info['ub'] = float(m.group(1))
    m = re.search(r'最终下�?LB = ([-+eE0-9.]+)', content)
    if m: info['lb'] = float(m.group(1))
    m = re.search(r'总迭代次�?= (\d+)', content)
    if m: info['iterations'] = int(m.group(1))
    m = re.search(r'算法总运行时�? ([0-9.]+) �?, content)
    if m: info['total_time_sec'] = float(m.group(1))

    if '达到总时间上�?小时' in content:
        info['termination'] = '超时(3h)'
    elif '达到最大迭代次�? in content:
        info['termination'] = '最大迭�?
    elif '提前终止' in content:
        info['termination'] = '停滞终止'
    elif '主问题无法最优求�? in content:
        info['termination'] = 'MP不可�?
    elif '最终上�?UB' in content:
        info['termination'] = '收敛'

    if info['ub'] and info['lb'] is not None and abs(info['ub']) > 1e-9:
        info['gap'] = (info['ub'] - info['lb']) / abs(info['ub'])

    m = re.search(r'固定变量-充电�?\s*固定了\s*(\d+)', content)
    if m: info['fixed_vp'] = int(m.group(1))
    m = re.search(r'固定变量-追随�?\s*固定了\s*(\d+)', content)
    if m: info['fixed_z'] = int(m.group(1))
    m = re.search(r'单点覆盖强化:\s*添加了\s*(\d+)', content)
    if m: info['single_cov'] = int(m.group(1))

    return info


def main():
    log("=" * 60)
    log(f"加速策略对比测�?| 数据={DATA_FILE} | 每配置{RUNS_PER_CONFIG}�?)
    log("=" * 60)

    compile_java()

    all_results = []
    overall_start = time.time()

    for ci, (name, subdir, extra) in enumerate(CONFIGS, 1):
        out_dir = os.path.abspath(os.path.join(OUTPUT_BASE_DIR, subdir))
        os.makedirs(out_dir, exist_ok=True)

        # 检查已有输出文件数，够则跳�?        existing = sorted(glob.glob(os.path.join(out_dir, "*.txt")))
        if len(existing) >= RUNS_PER_CONFIG:
            log(f"\n[{ci}/{len(CONFIGS)}] {name} �?已有{len(existing)}个结果，跳过")
            for ri in range(1, RUNS_PER_CONFIG + 1):
                info = parse_output(existing[ri - 1])
                info['config'] = name
                info['run'] = ri
                info['elapsed_wall'] = 0.0  # 未实际运�?                all_results.append(info)
            continue

        log(f"\n{'='*50}")
        log(f"[{ci}/{len(CONFIGS)}] {name}")
        log(f"{'='*50}")

        for ri in range(1, RUNS_PER_CONFIG + 1):
            cmd = build_cmd(extra, out_dir)
            start = time.time()
            log(f"  第{ri}�?开�?)

            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding='utf-8', errors='ignore'
            )
            stdout, stderr = proc.communicate()
            wall = time.time() - start

            if proc.returncode != 0:
                log(f"  第{ri}�?失败 (code={proc.returncode})")
                if stderr:
                    log(f"  {stderr.strip().split(chr(10))[0]}")
                all_results.append({
                    'config': name, 'run': ri, 'elapsed_wall': wall,
                    'returncode': proc.returncode, 'termination': f'Java异常',
                    'ub': None, 'lb': None, 'gap': None, 'iterations': None,
                    'total_time_sec': None, 'fixed_vp': 0, 'fixed_z': 0, 'single_cov': 0,
                })
                continue

            out_file = find_latest_output(out_dir)
            info = parse_output(out_file)
            info['config'] = name
            info['run'] = ri
            info['elapsed_wall'] = wall
            all_results.append(info)

            ub = f"{info['ub']:.4f}" if info['ub'] is not None else "N/A"
            lb = f"{info['lb']:.4f}" if info['lb'] is not None else "N/A"
            gap = f"{info['gap']*100:.4f}%" if info['gap'] is not None else "N/A"
            t = f"{info['total_time_sec']:.1f}s" if info['total_time_sec'] is not None else "N/A"
            log(f"  第{ri}�?完成 wall={wall:.0f}s 算法={t} 迭代={info['iterations']} "
                f"UB={ub} LB={lb} gap={gap} [{info['termination']}]")

    total_time = time.time() - overall_start

    # 汇�?    print("\n" + "=" * 80)
    print("各配置平均�?)
    print("=" * 80)
    print(f"{'配置':<18} {'平均UB':>10} {'平均LB':>10} {'平均Gap':>10} {'迭代':>6} {'算法(s)':>8} {'Wall(s)':>8}")
    print("-" * 80)
    for name, _, _ in CONFIGS:
        rows = [r for r in all_results if r['config'] == name]
        if not rows: continue
        n = len(rows)
        avg = lambda key: sum(r[key] for r in rows if r[key] is not None) / n
        ub = avg('ub')
        lb = avg('lb')
        gap = avg('gap') * 100 if any(r['gap'] for r in rows) else 0
        it = avg('iterations') if any(r['iterations'] for r in rows) else 0
        cpu = avg('total_time_sec') if any(r['total_time_sec'] for r in rows) else 0
        wall = avg('elapsed_wall')
        print(f"{name:<18} {ub:>10.4f} {lb:>10.4f} {gap:>9.4f}% {it:>6.1f} {cpu:>8.1f} {wall:>8.1f}")
    print("-" * 80)
    log(f"总耗时: {total_time:.0f}s ({total_time/60:.1f}min)")
    print("=" * 80)

    # 保存CSV
    csv_path = os.path.join(OUTPUT_BASE_DIR, "汇总结�?csv")
    with open(csv_path, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.writer(f)
        w.writerow(['配置', '运行序号', 'UB', 'LB', 'Gap(%)', '迭代次数',
                     '算法时间(s)', 'Wall时间(s)', '固定vP�?, '固定z�?,
                     '覆盖强化约束�?, '终止原因'])
        for r in all_results:
            w.writerow([
                r['config'], r['run'],
                f"{r['ub']:.6f}" if r['ub'] is not None else '',
                f"{r['lb']:.6f}" if r['lb'] is not None else '',
                f"{r['gap']*100:.4f}" if r['gap'] is not None else '',
                r['iterations'] if r['iterations'] is not None else '',
                f"{r['total_time_sec']:.2f}" if r['total_time_sec'] is not None else '',
                f"{r['elapsed_wall']:.2f}" if r['elapsed_wall'] is not None else '',
                r['fixed_vp'], r['fixed_z'], r['single_cov'],
                r['termination'],
            ])
        w.writerow([])
        w.writerow(['配置', '平均UB', '平均LB', '平均Gap(%)', '平均迭代', '平均算法时间(s)', '平均Wall时间(s)'])
        for name, _, _ in CONFIGS:
            rows = [r for r in all_results if r['config'] == name]
            if not rows: continue
            n = len(rows)
            avg = lambda key: sum(r[key] for r in rows if r[key] is not None) / n
            w.writerow([
                name,
                f"{avg('ub'):.4f}", f"{avg('lb'):.4f}",
                f"{avg('gap')*100:.4f}" if any(r['gap'] for r in rows) else '',
                f"{avg('iterations'):.1f}" if any(r['iterations'] for r in rows) else '',
                f"{avg('total_time_sec'):.2f}" if any(r['total_time_sec'] for r in rows) else '',
                f"{avg('elapsed_wall'):.2f}",
            ])
    log(f"CSV 已保�? {csv_path}")


if __name__ == "__main__":
    main()
