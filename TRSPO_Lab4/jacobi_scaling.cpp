#include <omp.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

double run_jacobi_seq(int n, const std::vector<double>& A, const std::vector<double>& b,
                      int iters, double& out_max_diff) {
    std::vector<double> x(n, 0.0), x_new(n, 0.0);
    double max_diff = 0.0;
    double t0 = omp_get_wtime();
    for (int k = 0; k < iters; ++k) {
        max_diff = 0.0;
        for (int i = 0; i < n; ++i) {
            const double* row = &A[static_cast<size_t>(i) * n];
            double sigma = 0.0;
            for (int j = 0; j < n; ++j) {
                if (j != i) sigma += row[j] * x[j];
            }
            x_new[i] = (b[i] - sigma) / row[i];
            double d = std::fabs(x_new[i] - x[i]);
            if (d > max_diff) max_diff = d;
        }
        std::swap(x, x_new);
    }
    out_max_diff = max_diff;
    return omp_get_wtime() - t0;
}

double run_jacobi_par(int n, const std::vector<double>& A, const std::vector<double>& b,
                      int iters, int p, double& out_max_diff) {
    std::vector<double> x(n, 0.0), x_new(n, 0.0);
    double max_diff = 0.0;
    double t0 = omp_get_wtime();
    #pragma omp parallel num_threads(p)
    {
        for (int k = 0; k < iters; ++k) {
            #pragma omp single
            max_diff = 0.0;

            #pragma omp for reduction(max: max_diff) schedule(static)
            for (int i = 0; i < n; ++i) {
                const double* row = &A[static_cast<size_t>(i) * n];
                double sigma = 0.0;
                for (int j = 0; j < n; ++j) {
                    if (j != i) sigma += row[j] * x[j];
                }
                x_new[i] = (b[i] - sigma) / row[i];
                double d = std::fabs(x_new[i] - x[i]);
                if (d > max_diff) max_diff = d;
            }

            #pragma omp single
            std::swap(x, x_new);
        }
    }
    out_max_diff = max_diff;
    return omp_get_wtime() - t0;
}

int main() {
    const int ITERS = 40;
    const int RUNS = 5;
    std::vector<int> sizes = {2000, 3500, 5000};
    std::vector<int> threads = {1, 2, 4, 8, 16};

    for (int n : sizes) {
        std::vector<double> A(static_cast<size_t>(n) * n);
        std::vector<double> b(n);
        for (int i = 0; i < n; ++i) {
            double row_sum = 0.0;
            for (int j = 0; j < n; ++j) {
                if (i != j) {
                    double v = std::sin(0.01 * (i + 1) + 0.02 * (j + 1));
                    A[static_cast<size_t>(i) * n + j] = v;
                    row_sum += std::fabs(v);
                }
            }
            A[static_cast<size_t>(i) * n + i] = row_sum + 10.0;
            b[i] = std::cos(0.05 * (i + 1)) * 100.0;
        }

        double ref_diff = 0.0;
        run_jacobi_seq(n, A, b, 2, ref_diff);
        run_jacobi_par(n, A, b, 2, 4, ref_diff);

        std::vector<double> t_seq_runs(RUNS);
        for (int r = 0; r < RUNS; ++r) {
            t_seq_runs[r] = run_jacobi_seq(n, A, b, ITERS, ref_diff);
        }
        std::sort(t_seq_runs.begin(), t_seq_runs.end());
        double t_seq = t_seq_runs[RUNS / 2];

        std::printf("\n=== Розмір матриці n = %d (iters = %d) | T_seq (медіана) = %.4f s ===\n", n, ITERS, t_seq);
        std::printf("%-4s | %-10s | %-10s | %-10s | %-12s\n", "p", "T(p), s", "S(p)", "E(p)", "diff");
        std::printf("--------------------------------------------------------\n");

        for (int p : threads) {
            std::vector<double> t_par_runs(RUNS);
            double par_diff = 0.0;
            for (int r = 0; r < RUNS; ++r) {
                t_par_runs[r] = run_jacobi_par(n, A, b, ITERS, p, par_diff);
            }
            std::sort(t_par_runs.begin(), t_par_runs.end());
            double t_par = t_par_runs[RUNS / 2];
            double S = t_seq / t_par;
            double E = S / p;
            std::printf("%-4d | %-10.4f | %-10.2f | %-10.2f | %.2e\n",
                        p, t_par, S, E, std::fabs(ref_diff - par_diff));
        }
    }
    return 0;
}
