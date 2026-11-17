import matplotlib.pyplot as plt
import numpy as np

phys_cores = 10
p = np.array([1, 2, 4, 8, 10, 16, 20])

# Твої експериментальні дані (Tp у мс)
T_small = np.array([2.56, 1.31, 0.82, 0.65, 0.69, 0.77, 0.88])
T_med   = np.array([27.20, 13.51, 7.04, 4.85, 5.39, 5.31, 4.58])
T_large = np.array([670.99, 340.81, 180.34, 113.67, 99.98, 102.51, 99.49])

datasets = [
    ("Малий (L = 10^6)", T_small, "tab:blue"),
    ("Середній (L = 10^7)", T_med, "tab:green"),
    ("Великий (L = 2.5·10^8)", T_large, "tab:red"),
]

# --- 1. Порівняння всіх 3 розмірів на одній площині ---
fig, axes = plt.subplots(1, 3, figsize=(16, 4.8))
p_smooth = np.linspace(1, 20, 200)

for label, T, color in datasets:
    S = T[0] / T
    E = S / p
    axes[0].plot(p, T, 'o-', label=label, color=color)
    axes[1].plot(p, S, 'o-', label=label, color=color)
    axes[2].plot(p, E, 'o-', label=label, color=color)

axes[0].set_yscale('log')
axes[0].set_title('Час виконання T(p) (лог. шкала)')
axes[0].set_ylabel('Час Tp, мс')

axes[1].plot(p_smooth, p_smooth, '--', color='gray', label='Ідеал S = p')
axes[1].set_title('Прискорення S(p) для різних розмірів')
axes[1].set_ylabel('Прискорення Sp')

axes[2].axhline(1.0, linestyle='--', color='gray', label='Ідеал E = 1')
axes[2].set_title('Ефективність E(p) для різних розмірів')
axes[2].set_ylabel('Ефективність Ep')

for ax in axes:
    ax.axvline(phys_cores, color='black', linestyle=':', linewidth=1.3, label='10 фіз. ядер')
    ax.set_xlabel('Кількість потоків p')
    ax.set_xticks([1, 2, 4, 8, 10, 16, 20])
    ax.legend(fontsize=8.5)
    ax.grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig('graphs_all_sizes.png', dpi=200)

# --- 2. Детальний аналіз великого розміру з кривою Амдала ---
fig2, axes2 = plt.subplots(1, 3, figsize=(16, 4.8))
S_large = T_large[0] / T_large
E_large = S_large / p
f_avg = 0.037
S_amdahl = 1.0 / (f_avg + (1.0 - f_avg) / p_smooth)
E_amdahl = S_amdahl / p_smooth

axes2[0].plot(p, T_large, 'o-', color='tab:blue', label='Експеримент')
axes2[0].plot(p_smooth, T_large[0] / p_smooth, '--', color='gray', label='Ідеал T1/p')
axes2[0].set_title('Час виконання T(p) (L = 2.5·10^8)')
axes2[0].set_ylabel('Час Tp, мс')

axes2[1].plot(p, S_large, 'o-', color='tab:blue', label='Експеримент')
axes2[1].plot(p_smooth, p_smooth, '--', color='gray', label='Лінійне S = p')
axes2[1].plot(p_smooth, S_amdahl, '-', color='tab:orange', linewidth=2, label=f'Амдал (f = {f_avg:.3f})')
axes2[1].set_title('Прискорення S(p) та закон Амдала')
axes2[1].set_ylabel('Прискорення Sp')

axes2[2].plot(p, E_large, 'o-', color='tab:blue', label='Експеримент')
axes2[2].plot(p_smooth, E_amdahl, '-', color='tab:orange', linewidth=2, label=f'Амдал (f = {f_avg:.3f})')
axes2[2].axhline(1.0, linestyle='--', color='gray', label='Ідеал E = 1')
axes2[2].set_title('Ефективність E(p) та закон Амдала')
axes2[2].set_ylabel('Ефективність Ep')

for ax in axes2:
    ax.axvline(phys_cores, color='red', linestyle=':', linewidth=1.3, label='10 фіз. ядер')
    ax.set_xlabel('Кількість потоків p')
    ax.set_xticks([1, 2, 4, 8, 10, 16, 20])
    ax.legend(fontsize=8.5)
    ax.grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig('graphs_large_amdahl.png', dpi=200)
plt.show()