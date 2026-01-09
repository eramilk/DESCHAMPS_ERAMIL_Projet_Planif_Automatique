import subprocess
import time
import re
from pathlib import Path
import csv
import os

JAVA_CP = "classes_build:lib/pddl4j-4.0.0.jar"

MCTS_CLASS = "fr.uga.pddl4j.examples.mcts.MCTSPlanner"
ASTAR_CLASS = "fr.uga.pddl4j.examples.asp.ASP"

DOMAINS_DIR = Path("tp_domains")
DOMAINS = ["blocksworld", "depots", "gripper", "logistics"]

# 10 problèmes par domaine
N_PROBLEMS = 10

# Timeout (seconds) pour chaque run
TIMEOUT_S = int(os.environ.get("TIMEOUT_S", "300"))

# Paramètres MCTS (tu peux ajuster après)
MCTS_ARGS = ["-t", str(TIMEOUT_S), "-n", "400", "-d", "80", "-p", "250", "-s", "1"]

# Paramètres A*
ASTAR_ARGS = ["-t", str(TIMEOUT_S), "-e", "FAST_FORWARD", "-w", "1.2"]

RE_PLAN_STEP = re.compile(r"^\s*\d+:\s+\(")
RE_MCTS_SUCC = re.compile(r"plan found \((\d+) steps\)", re.IGNORECASE)

LOG_DIR = Path("runs_logs")

def list_first_n_problems(domain_dir: Path, n: int):
    # prend p*.pddl (tri lexicographique => p01, p02, ..., p10)
    problems = sorted(domain_dir.glob("p*.pddl"))
    return problems[:n]

def run_planner(planner_class: str, domain_file: Path, problem_file: Path, extra_args):
    cmd = ["java", "-cp", JAVA_CP, planner_class, str(domain_file), str(problem_file)] + extra_args

    t0 = time.time()
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=TIMEOUT_S)
        timed_out = False
    except subprocess.TimeoutExpired as e:
        p = None
        timed_out = True
        stdout = e.stdout
        stderr = e.stderr
        if isinstance(stdout, bytes):
            stdout = stdout.decode("utf-8", errors="replace")
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", errors="replace")
        out = (stdout or "") + "\n" + (stderr or "")
    t1 = time.time()

    if not timed_out:
        out = (p.stdout or "") + "\n" + (p.stderr or "")

    if timed_out:
        return {
            "ok": False,
            "time_s": (t1 - t0),
            "plan_len": 0,
            "returncode": None,
            "output": out + f"\n\n[TIMEOUT] Killed after {TIMEOUT_S}s (Python-level timeout).\n",
            "cmd": " ".join(cmd)
        }

    # succès ?
    ok = ("succeeded" in out.lower()) and ("plan" in out.lower())

    # longueur du plan : compter les lignes "0: ( ... )"
    plan_len = sum(1 for line in out.splitlines() if RE_PLAN_STEP.match(line))

    # fallback MCTS (plan found (X steps))
    m = RE_MCTS_SUCC.search(out)
    if m:
        plan_len = int(m.group(1))

    return {
        "ok": ok and plan_len > 0,
        "time_s": (t1 - t0),
        "plan_len": plan_len,
        "returncode": p.returncode,
        "output": out,
        "cmd": " ".join(cmd)
    }

def save_log(domain, problem_name, planner, content):
    LOG_DIR.mkdir(exist_ok=True)
    fname = LOG_DIR / f"{domain}__{problem_name}__{planner}.log"
    fname.write_text(content, encoding="utf-8")

def main():
    if not DOMAINS_DIR.exists():
        raise SystemExit("tp_domains/ not found. Run from your ASP project root.")

    results = []

    for d in DOMAINS:
        ddir = DOMAINS_DIR / d
        domain_file = ddir / "domain.pddl"
        if not domain_file.exists():
            print(f"[SKIP] Missing domain: {domain_file}")
            continue

        problems = list_first_n_problems(ddir, N_PROBLEMS)
        if not problems:
            print(f"[SKIP] No p*.pddl in {ddir}")
            continue

        print(f"\n=== Domain: {d} ({len(problems)} problems) ===")

        for pb in problems:
            print(f"- {pb.name}")

            # MCTS
            mcts = run_planner(MCTS_CLASS, domain_file, pb, MCTS_ARGS)
            save_log(d, pb.stem, "MCTS", f"$ {mcts['cmd']}\n\n{mcts['output']}")
            results.append([d, pb.name, "MCTS", mcts["ok"], f"{mcts['time_s']:.4f}", mcts["plan_len"]])

            # A*
            astar = run_planner(ASTAR_CLASS, domain_file, pb, ASTAR_ARGS)
            save_log(d, pb.stem, "ASTAR", f"$ {astar['cmd']}\n\n{astar['output']}")
            results.append([d, pb.name, "A*", astar["ok"], f"{astar['time_s']:.4f}", astar["plan_len"]])

    out_csv = Path("results.csv")
    with out_csv.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["domain", "problem", "planner", "success", "time_s", "plan_len"])
        w.writerows(results)

    print(f"\nSaved: {out_csv}")
    print(f"Logs in: {LOG_DIR}/")

if __name__ == "__main__":
    main()
