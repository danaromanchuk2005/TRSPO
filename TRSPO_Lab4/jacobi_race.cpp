#include <omp.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <algorithm>

struct LinearSystem {
    int n;
    std::vector<double> A;
    std::vector<double> b;

    LinearSystem(int n_) : n(n_), A(static_cast<size_t>(n_) * n_), b(n_) {
        for (int i = 0; i < n; ++i) {
            double row_sum = 0.0;
            for (int j = 0; j < n; ++j) {
                if (i != j) {
                    double val = std::sin(0.01 * (i + 1) + 0.02 * (j + 1));
                    A[static_cast<size_t>(i) * n + j] = val;
                    row_sum += std::fabs(val);
                }
            }
            A[static_cast<size_t>(i) * n + i] = row_sum + 1.5;
            b[i] = std::cos(0.05 * (i + 1)) * 100.0;
        }
    }
};

struct JacobiResult {
    double max_diff;
    double total_l1;
    double elapsed;
};

// Еталонна послідовна версія
JacobiResult jacobi_seq(const LinearSystem& sys, int iters) {
    int n = sys.n;
    std::vector<double> x(n, 0.0), x_new(n, 0.0);
    double max_diff = 0.0, total_l1 = 0.0;

    double t0 = omp_get_wtime();
    for (int k = 0; k < iters; ++k) {
        max_diff = 0.0;
        for (int i = 0; i < n; ++i) {
            const double* row = &sys.A[static_cast<size_t>(i) * n];
            double sigma = 0.0;
            for (int j = 0; j < n; ++j) {
                if (j != i) sigma += row[j] * x[j];
            }
            x_new[i] = (sys.b[i] - sigma) / row[i];
            double d = std::fabs(x_new[i] - x[i]);
            if (d > max_diff) max_diff = d;
            total_l1 += d;
        }
        std::swap(x, x_new);
    }
    return {max_diff, total_l1, omp_get_wtime() - t0};
}

// 0. НЕКОРЕКТНА паралельна версія (гонка на спільному акумуляторі total_l1)
JacobiResult jacobi_race(const LinearSystem& sys, int iters) {
    int n = sys.n;
    std::vector<double> x(n, 0.0), x_new(n, 0.0);
    double max_diff = 0.0, total_l1 = 0.0;

    double t0 = omp_get_wtime();
    for (int k = 0; k < iters; ++k) {
        max_diff = 0.0;
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < n; ++i) {
            const double* row = &sys.A[static_cast<size_t>(i) * n];
            double sigma = 0.0;
            for (int j = 0; j < n; ++j) {
                if (j != i) sigma += row[j] * x[j];
            }
            x_new[i] = (sys.b[i] - sigma) / row[i];
            double d = std::fabs(x_new[i] - x[i]);
            if (d > max_diff) max_diff = d; // гонка
            total_l1 += d;                  // гонка: втрата оновлень
        }
        std::swap(x, x_new);
    }
    return {max_diff, total_l1, omp_get_wtime() - t0};
}

// 1. Виправлення через #pragma omp critical
JacobiResult jacobi_critical(const LinearSystem& sys, int iters) {
    int n = sys.n;
    std::vector<double> x(n, 0.0), x_new(n, 0.0);
    double max_diff = 0.0, total_l1 = 0.0;

    double t0 = omp_get_wtime();
    for (int k = 0; k < iters; ++k) {
        max_diff = 0.0;
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < n; ++i) {
            const double* row = &sys.A[static_cast<size_t>(i) * n];
            double sigma = 0.0;
            for (int j = 0; j < n; ++j) {
                if (j != i) sigma += row[j] * x[j];
            }
            x_new[i] = (sys.b[i] - sigma) / row[i];
            double d = std::fabs(x_new[i] - x[i]);
            #pragma omp critical
            {
                if (d > max_diff) max_diff = d;
                total_l1 += d;
            }
        }
        std::swap(x, x_new);
    }
    return {max_diff, total_l1, omp_get_wtime() - t0};
}

// 2. Виправлення через #pragma omp atomic
JacobiResult jacobi_atomic(const LinearSystem& sys, int iters) {
    int n = sys.n;
    std::vector<double> x(n, 0.0), x_new(n, 0.0);
    double total_l1 = 0.0;

    double t0 = omp_get_wtime();
    for (int k = 0; k < iters; ++k) {
        #pragma omp parallel for schedule(static)
        for (int i = 0; i < n; ++i) {
            const double* row = &sys.A[static_cast<size_t>(i) * n];
            double sigma = 0.0;
            for (int j = 0; j < n; ++j) {
                if (j != i) sigma += row[j] * x[j];
            }
            x_new[i] = (sys.b[i] - sigma) / row[i];
            double d = std::fabs(x_new[i] - x[i]);
            #pragma omp atomic
            total_l1 += d;
        }
        std::swap(x, x_new);
    }
    return {0.0, total_l1, omp_get_wtime() - t0};
}

// 3. Виправлення через reduction(max: max_diff) reduction(+: total_l1)
JacobiResult jacobi_reduction(const LinearSystem& sys, int iters) {
    int n = sys.n;
    std::vector<double> x(n, 0.0), x_new(n, 0.0);
    double max_diff = 0.0, total_l1 = 0.0;

    double t0 = omp_get_wtime();
    #pragma omp parallel
    {
        for (int k = 0; k < iters; ++k) {
            #pragma omp single
            max_diff = 0.0;

            #pragma omp for reduction(max: max_diff) reduction(+: total_l1) schedule(static)
            for (int i = 0; i < n; ++i) {
                const double* row = &sys.A[static_cast<size_t>(i) * n];
                double sigma = 0.0;
                for (int j = 0; j < n; ++j) {
                    if (j != i) sigma += row[j] * x[j];
                }
                x_new[i] = (sys.b[i] - sigma) / row[i];
                double d = std::fabs(x_new[i] - x[i]);
                if (d > max_diff) max_diff = d;
                total_l1 += d;
            }
            #pragma omp single
            std::swap(x, x_new);
        }
    }
    return {max_diff, total_l1, omp_get_wtime() - t0};
}

int main() {
    const int N = 2500;
    const int ITERS = 120;
    const int P = 8;
    omp_set_num_threads(P);

    LinearSystem sys(N);
    auto r_seq = jacobi_seq(sys, ITERS);
    std::printf("Еталон (послідовна, n=%d, iters=%d): T=%.4f s | total_l1=%.8f\n\n",
                N, ITERS, r_seq.elapsed, r_seq.total_l1);

    std::printf("=== Демонстрація гонки (5 запусків без синхронізації, p=%d) ===\n", P);
    for (int run = 1; run <= 5; ++run) {
        auto r = jacobi_race(sys, ITERS);
        std::printf("  Запуск #%d: T=%.4f s | total_l1=%.8f | похибка diff=%.6f\n",
                    run, r.elapsed, r.total_l1, std::fabs(r.total_l1 - r_seq.total_l1));
    }

    std::printf("\n=== Порівняння способів усунення гонки (p=%d) ===\n", P);
    auto r_crit = jacobi_critical(sys, ITERS);
    auto r_atom = jacobi_atomic(sys, ITERS);
    auto r_red  = jacobi_reduction(sys, ITERS);

    std::printf("  1) critical : T=%.4f s | похибка=%.2e | S=%.2f\n",
                r_crit.elapsed, std::fabs(r_crit.total_l1 - r_seq.total_l1), r_seq.elapsed / r_crit.elapsed);
    std::printf("  2) atomic   : T=%.4f s | похибка=%.2e | S=%.2f\n",
                r_atom.elapsed, std::fabs(r_atom.total_l1 - r_seq.total_l1), r_seq.elapsed / r_atom.elapsed);
    std::printf("  3) reduction: T=%.4f s | похибка=%.2e | S=%.2f\n",
                r_red.elapsed, std::fabs(r_red.total_l1 - r_seq.total_l1), r_seq.elapsed / r_red.elapsed);
    return 0;
}
