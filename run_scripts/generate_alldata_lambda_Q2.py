import os

BASE_FILE = 'data.txt'
OUTPUT_DIR = 'alldata_lambda_Q2敏感性分�?

# lambda: 0.1 ~ 1.5, step 0.1
LAMBDA_VALUES = [round(0.1 * i, 1) for i in range(1, 16)]
# Q_{s=2}: -5 ~ 18, step 1
Q2_VALUES = list(range(-5, 19))


def read_base(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        return f.readlines()


def modify_lines(lines, lambda_val, q2_val):
    """Modify in place: set lambda, Q_{s=2}, Q_{s=3}=Q_{s=2}+0.1"""
    q3_val = q2_val + 0.1
    result = list(lines)

    # 1) Find the service header and modify Q on the next lines (s=2 �?line+3, s=3 �?line+4)
    service_header_idx = None
    for i, line in enumerate(result):
        parts = line.rstrip('\n').rstrip('\r').split('\t')
        if len(parts) >= 2 and parts[0].strip() == 's' and 'beta' in parts[1]:
            service_header_idx = i
            break

    if service_header_idx is None:
        raise ValueError("Cannot find service section header")

    # Modify Q_{s=2}: service header + 3 = s=2 line
    idx_s2 = service_header_idx + 3
    line_s2 = result[idx_s2]
    parts = line_s2.rstrip('\n').rstrip('\r').split('\t')
    if len(parts) >= 4:
        parts[3] = str(q2_val)
    result[idx_s2] = '\t'.join(parts) + '\n'

    # Modify Q_{s=3}: service header + 4 = s=3 line
    idx_s3 = service_header_idx + 4
    line_s3 = result[idx_s3]
    parts = line_s3.rstrip('\n').rstrip('\r').split('\t')
    if len(parts) >= 4:
        parts[3] = str(q3_val)
    result[idx_s3] = '\t'.join(parts) + '\n'

    # 2) Find and modify lambda line
    for i, line in enumerate(result):
        parts = line.rstrip('\n').rstrip('\r').split('\t')
        if len(parts) >= 2 and parts[0].strip() == 'lambda' and len(parts[0].strip()) == 6:
            parts[1] = str(lambda_val)
            result[i] = '\t'.join(parts) + '\n'
            break

    return result


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    lines = read_base(BASE_FILE)

    total = len(LAMBDA_VALUES) * len(Q2_VALUES)
    count = 0

    for lam in LAMBDA_VALUES:
        for q2 in Q2_VALUES:
            filename = f'lambda{lam}_Q2{q2}.txt'
            filepath = os.path.join(OUTPUT_DIR, filename)

            content = modify_lines(lines, lam, q2)
            with open(filepath, 'w', encoding='utf-8') as f:
                f.writelines(content)

            count += 1
            if count % 50 == 0:
                print(f'[{count}/{total}] Generated: {filename}')

    print(f'\n完成！共生成 {count} 个数据文件到 {OUTPUT_DIR}/')
    print(f'  lambda: {LAMBDA_VALUES[0]} ~ {LAMBDA_VALUES[-1]} (step 0.1, {len(LAMBDA_VALUES)}�?')
    print(f'  Q_{{s=2}}: {Q2_VALUES[0]} ~ {Q2_VALUES[-1]} (step 1, {len(Q2_VALUES)}�?')
    print(f'  Q_{{s=3}} = Q_{{s=2}} + 0.1')


if __name__ == '__main__':
    main()
