import os

BASE_FILE = 'data.txt'
OUTPUT_DIR = 'alldata_lambda_Q3敏感性分析'

# lambda: 0.1 ~ 1.5, step 0.1
LAMBDA_VALUES = [round(0.1 * i, 1) for i in range(1, 16)]
# Q3 系数: 1.0 ~ 2.0, step 0.1
Q3_COEF_VALUES = [round(1.0 + 0.1 * i, 1) for i in range(11)]
# Q2 (s=3) 固定价格
Q2_PRICE = 2.26


def read_base(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        return f.readlines()


def modify_lines(lines, lambda_val, q3_coef):
    """Modify in place: set lambda, Q3 = 2.26 * coef (s=4 line)"""
    q3_val = round(Q2_PRICE * q3_coef, 2)
    result = list(lines)

    # 1) Find the service header line (starts with 's\t' and contains 'beta')
    service_header_idx = None
    for i, line in enumerate(result):
        stripped = line.rstrip('\n').rstrip('\r')
        parts = stripped.split('\t')
        if len(parts) >= 2 and parts[0].strip() == 's' and 'beta' in parts[1]:
            service_header_idx = i
            break

    if service_header_idx is None:
        raise ValueError("Cannot find service section header")

    # s=1 is at header+1, s=2 at header+2, s=3 at header+3, s=4 at header+4
    idx_s4 = service_header_idx + 4
    line_s4 = result[idx_s4]
    parts = line_s4.rstrip('\n').rstrip('\r').split('\t')
    if len(parts) >= 4:
        parts[3] = str(q3_val)
    result[idx_s4] = '\t'.join(parts) + '\n'

    # 2) Find and modify lambda line (exact match 'lambda' with 6 chars)
    for i, line in enumerate(result):
        stripped = line.rstrip('\n').rstrip('\r')
        parts = stripped.split('\t')
        if len(parts) >= 2 and parts[0].strip() == 'lambda' and len(parts[0].strip()) == 6:
            parts[1] = str(lambda_val)
            result[i] = '\t'.join(parts) + '\n'
            break

    return result


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    lines = read_base(BASE_FILE)

    total = len(LAMBDA_VALUES) * len(Q3_COEF_VALUES)
    count = 0

    for lam in LAMBDA_VALUES:
        for coef in Q3_COEF_VALUES:
            q3_price = round(Q2_PRICE * coef, 2)
            filename = f'lambda{lam}_Q3coef{coef}.txt'
            filepath = os.path.join(OUTPUT_DIR, filename)

            content = modify_lines(lines, lam, coef)
            with open(filepath, 'w', encoding='utf-8') as f:
                f.writelines(content)

            count += 1
            if count % 30 == 0:
                print(f'[{count}/{total}] Generated: {filename} (Q3={q3_price})')

    print(f'\n完成！共生成 {count} 个数据文件到 {OUTPUT_DIR}/')
    print(f'  lambda: {LAMBDA_VALUES[0]} ~ {LAMBDA_VALUES[-1]} (step 0.1, {len(LAMBDA_VALUES)}个)')
    print(f'  Q3系数: {Q3_COEF_VALUES[0]} ~ {Q3_COEF_VALUES[-1]} (step 0.1, {len(Q3_COEF_VALUES)}个)')
    print(f'  Q2 (s=3) 固定 = {Q2_PRICE}')
    print(f'  Q3 (s=4) = {Q2_PRICE} × 系数 = {round(Q2_PRICE * Q3_COEF_VALUES[0], 2)} ~ {round(Q2_PRICE * Q3_COEF_VALUES[-1], 2)}')


if __name__ == '__main__':
    main()
