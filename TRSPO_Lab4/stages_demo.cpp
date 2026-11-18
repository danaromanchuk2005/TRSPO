#include <omp.h>
#include <cmath>
#include <cstdio>
#include <vector>
#include <algorithm>

int main() {
    const int N = 3500;
    std::vector<double> A(static_cast<size_t>(N) * N);
    std::vector<double> b(N);

    double min_diag_margin = 0.0;
    double rhs_norm2 = 0.0;
    double max_abs_elem = 0.0;

    double t0 = omp_get_wtime();

    #pragma omp parallel num_threads(4)
    {
        #pragma omp single
        {
            std::printf("[single] Ініціалізацію системи (n=%d) виконує потік %d із %d\n",
                        N, omp_get_thread_num(), omp_get_num_threads());
            for (int i = 0; i < N; ++i) {
                double row_sum = 0.0;
                for (int j = 0; j < N; ++j) {
                    if (i != j) {
                        double v = std::sin(0.01 * (i + 1) + 0.02 * (j + 1));
                        A[static_cast<size_t>(i) * N + j] = v;
                        row_sum += std::fabs(v);
                    }
                }
                A[static_cast<size_t>(i) * N + i] = row_sum + 10.0;
                b[i] = std::cos(0.05 * (i + 1)) * 100.0;
            }
        }

        #pragma omp sections
        {
            #pragma omp section
            {
                double min_m = 1e30;
                for (int i = 0; i < N; ++i) {
                    double off_diag = 0.0;
                    for (int j = 0; j < N; ++j) {
                        if (i != j) off_diag += std::fabs(A[static_cast<size_t>(i) * N + j]);
                    }
                    min_m = std::min(min_m, A[static_cast<size_t>(i) * N + i] - off_diag);
                }
                min_diag_margin = min_m;
                #pragma omp critical
                std::printf("  [section 1] Запас діагонального переважання обчислив потік %d\n",
                            omp_get_thread_num());
            }

            #pragma omp section
            {
                double s = 0.0;
                for (int i = 0; i < N; ++i) s += b[i] * b[i];
                rhs_norm2 = std::sqrt(s);
                #pragma omp critical
                std::printf("  [section 2] Евклідову норму вектора b обчислив потік %d\n",
                            omp_get_thread_num());
            }

            #pragma omp section
            {
                double mx = 0.0;
                for (double v : A) mx = std::max(mx, std::fabs(v));
                max_abs_elem = mx;
                #pragma omp critical
                std::printf("  [section 3] Максимальний елемент матриці A обчислив потік %d\n",
                            omp_get_thread_num());
            }
        }

        #pragma omp barrier

        #pragma omp master
        {
            std::printf("[master] Потік %d виводить звіт (час %.4f s): min_margin=%.4f, ||b||_2=%.4f, max|A|=%.4f\n",
                        omp_get_thread_num(), omp_get_wtime() - t0,
                        min_diag_margin, rhs_norm2, max_abs_elem);
        }
    }
    return 0;
}
