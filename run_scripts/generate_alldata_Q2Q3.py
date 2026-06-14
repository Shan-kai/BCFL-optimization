import os

BASE_FILE = 'data.txt'
OUTPUT_DIR = 'alldata_Q2Q3敏感性分�?

# q2 (Q_{s=2}): 0 ~ 11, step 1 (�?q2=0 时只生成 1 个文�?
Q2_VALUES = list(range(0, 12))
# k: 1.0 ~ 3.0, step 0.2 �?1.0, 1.2, 1.4, ..., 3.0, 再加 4.0, 5.0
K_VALUES = [round(1.0 + 0.2 * i, 1) for i in range(11)] + [4.0, 5.0]
# q3 = q2, �?Q3 = k * q2 (�?q2=0 �?q3=0，所�?k 结果相同)


def read_base(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        return f.readlines()


def modify_lines(lines, q2_val, k_val):
    """Modify in place: set Q_{s=2} = q2_val, Q_{s=3} = k * q2_val"""
    q3_val = round(k_val * q2_val, 4) if q2_val > 0 else 0
    result = list(lines)

    # Find service header
    service_header_idx = None
    for i, line in enumerate(result):
        parts = line.rstrip('\n').rstrip('\r').split('\t')
        if len(parts) >= 2 and parts[0].strip() == 's' and 'beta' in parts[1]:
            service_header_idx = i
            break

    if service_header_idx is None:
        raise ValueError("Cannot find service section header")

    # Modify Q_{s=2}: header + 3 = s=2 line
    idx_s2 = service_header_idx + 3
    line_s2 = result[idx_s2]
    parts = line_s2.rstrip('\n').rstrip('\r').split('\t')
    if len(parts) >= 4:
        parts[3] = str(q2_val)
    result[idx_s2] = '\t'.join(parts) + '\n'

    # Modify Q_{s=3}: header + 4 = s=3 line
    idx_s3 = service_header_idx + 4
    line_s3 = result[idx_s3]
    parts = line_s3.rstrip('\n').rstrip('\r').split('\t')
    if len(parts) >= 4:
        parts[3] = str(q3_val)
    result[idx_s3] = '\t'.join(parts) + '\n'

    return result


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    lines = read_base(BASE_FILE)

    total = 0
    count = 0

    for q2 in Q2_VALUES:
        ks = K_VALUES if q2 > 0 else [1.0]  # q2=0 时只生成 1 �?        total += len(ks)
        for k in ks:
            filename = f'q2{q2}_k{k}.txt'
            filepath = os.path.join(OUTPUT_DIR, filename)

            content = modify_lines(lines, q2, k)
            with open(filepath, 'w', encoding='utf-8') as f:
                f.writelines(content)

            count += 1
            if count % 30 == 0:
                print(f'[{count}/{total}] Generated: {filename}')

    print(f'\n完成！共生成 {count} 个数据文件到 {OUTPUT_DIR}/')
    print(f'  q2 (Q_{{s=2}}): {Q2_VALUES[0]} ~ {Q2_VALUES[-1]} (step 1, {len(Q2_VALUES)}�?')
    print(f'  k: {K_VALUES[0]} ~ {K_VALUES[-1]} (step 0.2, {11}�? q2=0时只用k=1.0)')
    print(f'  q3 = q2, �?Q3 = k * q2')


if __name__ == '__main__':
    main()
