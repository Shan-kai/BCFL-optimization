"""
批量�?(1.0,1.0) gamma 跨服务系数求解子问题

数据�?
  gamma来源: output_lambda_l_f_Blbf_gamma/{BLBF}/lambda{lambda}/gamma_lambda_l1.0_f1.0.txt
  data来源:  alldata_lambda_l_f-Bl_bf-lambda/{BLBF}/lambda{lambda}/lambda_l{ll}_f{lf}.txt
  结果保存:  output_lambda_l_f_Blbf_gamma/{BLBF}/lambda{lambda}/psi_from_gamma1.0_1.0_with_l{ll}_f{lf}.txt

只用lambda = 0.1, 0.8, 1.5三组
"""
import subprocess
import os
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GAMMA_DIR = os.path.join(BASE_DIR, "output_lambda_l_f_Blbf_gamma")
DATA_DIR = os.path.join(BASE_DIR, "alldata_lambda_l_f-Bl_bf-lambda")
JAVA_CP = "bin;lib/cplex.jar"

BLBF_LIST = [
    "Bl1kw_Bf1kw",
    "Bl1kw_Bf1.5kw",
    "Bl1kw_Bf2kw",
    "Bl1.5kw_Bf1kw",
    "Bl1.5kw_Bf1.5kw",
    "Bl2kw_Bf1kw",
    "Bl2kw_Bf2kw",
]

LAMBDA_LIST = ["0.1", "0.8", "1.5"]

# gamma来源固定�?(1.0, 1.0)
GAMMA_LF = ("1.0", "1.0")
# data使用其他5种组合（排除1.0,1.0�?DATA_LF_LIST = [("0.8", "0.2"), ("0.8", "1.0"), ("1.0", "0.2"), ("0.6", "0.2"), ("0.4", "0.2")]


def main():
    total = 0
    success = 0
    failed = []

    for blbf in BLBF_LIST:
        for lam in LAMBDA_LIST:
            # gamma文件
            gamma_file = os.path.join(
                GAMMA_DIR, blbf, f"lambda{lam}",
                f"gamma_lambda_l{GAMMA_LF[0]}_f{GAMMA_LF[1]}.txt"
            )
            if not os.path.exists(gamma_file):
                print(f"SKIP (gamma not found): {gamma_file}")
                continue

            for data_lam_l, data_lam_f in DATA_LF_LIST:
                total += 1

                # data文件
                data_file = os.path.join(
                    DATA_DIR, blbf, f"lambda{lam}",
                    f"lambda_l{data_lam_l}_f{data_lam_f}.txt"
                )
                if not os.path.exists(data_file):
                    print(f"SKIP (data not found): {data_file}")
                    failed.append((blbf, lam, data_lam_l, data_lam_f, "data not found"))
                    continue

                # 输出psi文件
                output_file = os.path.join(
                    GAMMA_DIR, blbf, f"lambda{lam}",
                    f"psi_from_gamma1.0_1.0_with_l{data_lam_l}_f{data_lam_f}.txt"
                )

                # 跳过已存在的（含Pi的版本）
                if os.path.exists(output_file):
                    with open(output_file, 'r', encoding='utf-8') as f:
                        first_line = f.readline()
                    if first_line.startswith("Pi("):
                        print(f"EXISTS: {output_file}")
                        success += 1
                        continue
                    else:
                        os.remove(output_file)  # 删除旧版，重新生�?
                # 调用Java求解
                cmd = [
                    "java", "-cp", JAVA_CP,
                    "run.SolveSPWithGamma",
                    gamma_file, data_file, output_file
                ]

                print(f"RUN: {blbf}/lambda{lam} gamma(1.0,1.0) + data({data_lam_l},{data_lam_f})")

                try:
                    result = subprocess.run(
                        cmd,
                        cwd=BASE_DIR,
                        capture_output=True,
                        text=True,
                        timeout=600
                    )
                    if result.returncode == 0:
                        success += 1
                    else:
                        print(f"  FAIL (exit {result.returncode}): {result.stderr[:200]}")
                        failed.append((blbf, lam, data_lam_l, data_lam_f, result.stderr[:200]))
                except subprocess.TimeoutExpired:
                    print(f"  TIMEOUT")
                    failed.append((blbf, lam, data_lam_l, data_lam_f, "timeout"))
                except Exception as e:
                    print(f"  ERROR: {e}")
                    failed.append((blbf, lam, data_lam_l, data_lam_f, str(e)))

    print(f"\n=== 完成 ===")
    print(f"总计: {total}, 成功: {success}, 失败: {len(failed)}")
    if failed:
        print("\n失败列表:")
        for blbf, lam, ll, lf, reason in failed:
            print(f"  {blbf}/lambda{lam} l{ll}_f{lf}: {reason}")


if __name__ == "__main__":
    main()
