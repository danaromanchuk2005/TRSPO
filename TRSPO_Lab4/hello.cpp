#include <omp.h>
#include <cstdio>

int main() {
    std::printf("Логічних ядер: %d, потоків за замовчуванням: %d\n",
                omp_get_num_procs(), omp_get_max_threads());

    std::printf("\n--- 1. Паралельна область (за замовчуванням або OMP_NUM_THREADS) ---\n");
    #pragma omp parallel
    {
        int id = omp_get_thread_num();
        int n  = omp_get_num_threads();
        #pragma omp critical
        std::printf("Привіт від потоку %d із %d\n", id, n);
    }

    omp_set_num_threads(4);
    std::printf("\n--- 2. Після виклику omp_set_num_threads(4) ---\n");
    #pragma omp parallel
    {
        #pragma omp critical
        std::printf("[omp_set_num_threads(4)] Потік %d із %d\n",
                    omp_get_thread_num(), omp_get_num_threads());
    }

    std::printf("\n--- 3. Клауза num_threads(2) поверх omp_set_num_threads(4) ---\n");
    #pragma omp parallel num_threads(2)
    {
        #pragma omp critical
        std::printf("[num_threads(2)] Потік %d із %d\n",
                    omp_get_thread_num(), omp_get_num_threads());
    }
    return 0;
}
