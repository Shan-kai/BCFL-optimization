import os

BASE_FILE = 'data.txt'
PARENT_DIR = 'alldata_R3_Mcoef'

# R3 (无人机直送服务范�?: 5 ~ 35, 步长 5
R3_VALUES = list(range(5, 40, 5))  # 5, 10, 15, 20, 25, 30, 35

# MI1占比系数: 0.70 ~ 0.96, 步长 0.02
COEF_VALUES = [round(0.70 + 0.02 * i, 2) for i in range(14)]


def read_base(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        return f.readlines()


def find_service_line_indices(lines):
    idx_s3 = idx_s4 = -1
    in_service = False
    for i, line in enumerate(lines):
        stripped = line.rstrip('\n').rstrip('\r')
        if stripped.startswith('s\t'):
            in_service = True
            continue
        if not in_service:
            continue
        parts = stripped.split('\t')
        if len(parts) >= 5:
            try:
                s = int(parts[0])
                if s == 3:
                    idx_s3 = i
                elif s == 4:
                    idx_s4 = i
            except ValueError:
                continue
    return idx_s3, idx_s4


def find_i_node_range(lines):
    i_start = -1
    i_end = -1
    for i, line in enumerate(lines):
        stripped = line.rstrip('\n').rstrip('\r')
        if stripped.startswith('I-ID'):
            i_start = i + 1
        elif i_start >= 0 and (stripped == '' or stripped.startswith('s\t') or stripped.startswith('B_')):
            i_end = i
            break
    if i_end < 0:
        i_end = len(lines)
    return i_start, i_end


def get_original_line_ending(line):
    if line.endswith('\r\n'):
        return '\r\n'
    elif line.endswith('\n'):
        return '\n'
    elif line.endswith('\r'):
        return '\r'
    return '\n'


def fmt_val(v):
    s = f"{v:.12f}"
    s = s.rstrip('0').rstrip('.')
    return s


def generate_file(lines, idx_s3, idx_s4, i_start, i_end, r3, coef):
    result = list(lines)

    # s=3: r_s(km) = R3
    le_s3 = get_original_line_ending(result[idx_s3])
    parts_s3 = result[idx_s3].rstrip('\n').rstrip('\r').split('\t')
    parts_s3[4] = str(r3)
    result[idx_s3] = '\t'.join(parts_s3) + le_s3

    # s=4: r_s(km) = 2*R3
    le_s4 = get_original_line_ending(result[idx_s4])
    parts_s4 = result[idx_s4].rstrip('\n').rstrip('\r').split('\t')
    parts_s4[4] = str(2 * r3)
    result[idx_s4] = '\t'.join(parts_s4) + le_s4

    # I-node: MI1 = coef * total, MI0 = total - MI1
    for i in range(i_start, i_end):
        stripped = result[i].rstrip('\n').rstrip('\r')
        if stripped == '':
            continue
        parts = stripped.split('\t')
        if len(parts) >= 6:
            try:
                mi0 = float(parts[4])
                mi1 = float(parts[5])
                total = mi0 + mi1

                if abs(coef - 0.86) < 1e-9:
                    # 保持原字符串，确保完全一�?                    new_mi0_str = parts[4]
                    new_mi1_str = parts[5]
                else:
                    new_mi1 = coef * total
                    new_mi0 = total - new_mi1
                    new_mi0_str = fmt_val(new_mi0)
                    new_mi1_str = fmt_val(new_mi1)

                parts[4] = new_mi0_str
                parts[5] = new_mi1_str
                result[i] = '\t'.join(parts) + get_original_line_ending(result[i])
            except (ValueError, IndexError):
                pass

    return result


def main():
    lines = read_base(BASE_FILE)
    idx_s3, idx_s4 = find_service_line_indices(lines)
    i_start, i_end = find_i_node_range(lines)

    if idx_s3 < 0 or idx_s4 < 0:
        print("error: s=3 or s=4 not found")
        return
    if i_start < 0:
        print("error: I-node section not found")
        return

    print(f"Base file: {BASE_FILE}")
    print(f"s=3 line: {idx_s3+1}, s=4 line: {idx_s4+1}")
    print(f"I-node: lines {i_start+1} to {i_end} ({i_end - i_start} nodes)")
    print(f"R3 values: {R3_VALUES} ({len(R3_VALUES)})")
    print(f"coef values: {COEF_VALUES} ({len(COEF_VALUES)})")
    total_combos = len(R3_VALUES) * len(COEF_VALUES)
    print(f"Total: {len(R3_VALUES)} x {len(COEF_VALUES)} = {total_combos}")
    print()

    os.makedirs(PARENT_DIR, exist_ok=True)
    total = 0
    for r3 in R3_VALUES:
        for coef in COEF_VALUES:
            filename = f'R3_{r3}_coef_{coef:.2f}.txt'
            filepath = os.path.join(PARENT_DIR, filename)

            content = generate_file(lines, idx_s3, idx_s4, i_start, i_end, r3, coef)
            with open(filepath, 'w', encoding='utf-8', newline='') as f:
                f.writelines(content)
            total += 1

    print(f"Done: {total} files in {PARENT_DIR}/")
    print(f"  Naming: R3_{{val}}_coef_{{val:.2f}}.txt")
    print(f"  R3: {R3_VALUES[0]} to {R3_VALUES[-1]} (step 5)")
    print(f"  coef: {COEF_VALUES[0]} to {COEF_VALUES[-1]} (step 0.02)")


if __name__ == '__main__':
    main()
