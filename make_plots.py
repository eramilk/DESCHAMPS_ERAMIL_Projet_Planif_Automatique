import pandas as pd
import matplotlib.pyplot as plt
from pathlib import Path

INFILE = Path("results.csv")
OUTDIR = Path("plots")
OUTDIR.mkdir(exist_ok=True)

DOMAINS = ["blocksworld", "depots", "gripper", "logistics"]
PLANNERS = ["MCTS", "A*"]

def load_and_clean():
    df = pd.read_csv(INFILE)

    # Normaliser planner names (au cas où)
    df["planner"] = df["planner"].astype(str).str.strip()

    # success en bool
    df["success"] = df["success"].astype(str).str.lower().map({"true": True, "false": False})
    df["time_s"] = pd.to_numeric(df["time_s"], errors="coerce")
    df["plan_len"] = pd.to_numeric(df["plan_len"], errors="coerce")

    # Pour éviter des plan_len = 0 si échec
    df.loc[~df["success"], "plan_len"] = pd.NA

    return df

def plot_metric_per_domain(df, metric, ylabel, filename_prefix):
    # 1 figure par domaine
    for d in DOMAINS:
        sub = df[df["domain"] == d].copy()
        if sub.empty:
            continue

        # pivot: rows=problem, cols=planner
        pivot = sub.pivot_table(index="problem", columns="planner", values=metric, aggfunc="first")

        # Garder l’ordre des problèmes
        pivot = pivot.sort_index()

        plt.figure()
        if "MCTS" in pivot.columns:
            plt.plot(range(1, len(pivot.index) + 1), pivot["MCTS"].values, marker="o", label="MCTS")
        if "A*" in pivot.columns:
            plt.plot(range(1, len(pivot.index) + 1), pivot["A*"].values, marker="o", label="A*")

        plt.xticks(range(1, len(pivot.index) + 1), pivot.index, rotation=45, ha="right")
        plt.xlabel("Problem")
        plt.ylabel(ylabel)
        plt.title(f"{d} — {ylabel}")
        plt.legend()
        plt.tight_layout()

        out = OUTDIR / f"{filename_prefix}_{d}.png"
        plt.savefig(out, dpi=200)
        plt.close()

def make_summary(df):
    # stats par domaine/planner
    g = df.groupby(["domain", "planner"], as_index=False)

    summary = g.agg(
        n_runs=("problem", "count"),
        success_rate=("success", "mean"),
        mean_time=("time_s", "mean"),
        median_time=("time_s", "median"),
        mean_plan_len=("plan_len", "mean"),
        median_plan_len=("plan_len", "median"),
    )

    summary.to_csv(OUTDIR / "summary.csv", index=False)

def main():
    df = load_and_clean()

    # Runtime figures (inclure échecs aussi, c’est le temps réel)
    plot_metric_per_domain(df, "time_s", "Runtime (s)", "runtime")

    # Makespan/plan length figures (uniquement pour success, sinon NA)
    plot_metric_per_domain(df, "plan_len", "Plan length (steps)", "planlen")

    make_summary(df)

    print("Saved plots in:", OUTDIR)
    print("Summary:", OUTDIR / "summary.csv")

if __name__ == "__main__":
    main()
