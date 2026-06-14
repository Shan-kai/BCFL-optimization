import os

BASE_FILE = 'data.txt'
PARENT_DIR = 'alldata_lambda_l_f-Bl_bf-lambda'

# BLBF目录�?�?(B_l, B_f) 映射
BLBF_MAP = {
    'Bl1kw_Bf1kw': (10000000, 10000000),
    'Bl1kw_Bf2kw': (10000000, 20000000),
    'Bl2kw_Bf1kw': (20000000, 10000000),
    'Bl2kw_Bf2kw': (20000000, 20000000),
    'Bl1.5kw_Bf1kw': (15000000, 10000000),   # 1.5-1
    'Bl1kw_Bf1.5kw': (10000000, 15000000),   # 1-1.5
    'Bl1.5kw_Bf1.5kw': (15000000, 15000000), # 1.5-1.5
}

# lambda文件�? 0.1 ~ 1.5
LAMBDA_FOLDERS = [f'lambda{round(0.1 * i, 1)}' for i in range(1, 16)]

# 6�?(lambda_l, lambda_f) 组合
LAMBDA_LF_COMBOS = [
    (0.8, 0.2),
    (1.0, 0.2),
    (1.0, 1.0),
    (0.8, 1.0),
    (0.6, 0.2),
    (0.4, 0.2),
]


def read_base(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        return f.readlines()


def find_layout(lines):
    """定位 B_l, B_f, lambda_L, lambda_F, lambda 各行索引"""
    idx_bl = idx_bf = idx_ll = idx_lf = idx_l = -1
    for i, line in enumerate(lines):
        parts = line.rstrip('\n').rstrip('\r').split('\t')
        if len(parts) < 2:
            continue
        key = parts[0].strip()
        if key == 'B_l':
            idx_bl = i
        elif key == 'B_f':
            idx_bf = i
        elif key == 'lambda_L':
            idx_ll = i
        elif key == 'lambda_F':
            idx_lf = i
        elif key == 'lambda' and len(key) == 6:
            idx_l = i
    return idx_bl, idx_bf, idx_ll, idx_lf, idx_l


def generate_file(lines, idx_bl, idx_bf, idx_ll, idx_lf, idx_l,
                   b_l, b_f, lam, lam_l, lam_f):
    result = list(lines)
    result[idx_bl] = f'B_l\t{b_l}\n'
    result[idx_bf] = f'B_f\t{b_f}\n'
    result[idx_ll] = f'lambda_L\t{lam_l}\n'
    result[idx_lf] = f'lambda_F\t{lam_f}\n'
    result[idx_l] = f'lambda\t{lam}\n'
    return result


def main():
    lines = read_base(BASE_FILE)
    idx_bl, idx_bf, idx_ll, idx_lf, idx_l = find_layout(lines)

    if any(i < 0 for i in (idx_bl, idx_bf, idx_ll, idx_lf, idx_l)):
        print("错误：未找到 B_l, B_f, lambda_L, lambda_F �?lambda �?)
        return

    total = 0
    for blbf_name, (b_l, b_f) in BLBF_MAP.items():
        for lam_folder in LAMBDA_FOLDERS:
            # 解析lambda�?            lam = float(lam_folder.replace('lambda', ''))
            folder_path = os.path.join(PARENT_DIR, blbf_name, lam_folder)
            os.makedirs(folder_path, exist_ok=True)

            for lam_l, lam_f in LAMBDA_LF_COMBOS:
                filename = f'lambda_l{lam_l}_f{lam_f}.txt'
                filepath = os.path.join(folder_path, filename)

                content = generate_file(
                    lines, idx_bl, idx_bf, idx_ll, idx_lf, idx_l,
                    b_l, b_f, lam, lam_l, lam_f
                )
                with open(filepath, 'w', encoding='utf-8') as f:
                    f.writelines(content)
                total += 1

    print(f'完成！共生成 {total} 个数据文�?)
    for blbf_name in BLBF_MAP:
        print(f'  {blbf_name}/: {len(LAMBDA_FOLDERS)} 个lambda文件�?× 4个文�?= {len(LAMBDA_FOLDERS)*4} �?)
    print(f'  参数范围: B_l/B_f: {list(BLBF_MAP.values())}')
    print(f'  lambda: 0.1 ~ 1.5')
    print(f'  (lambda_l, lambda_f): {LAMBDA_LF_COMBOS}')


if __name__ == '__main__':
    main()
