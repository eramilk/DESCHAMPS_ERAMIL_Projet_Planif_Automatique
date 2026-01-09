package fr.uga.pddl4j.examples.mcts;

import fr.uga.pddl4j.parser.DefaultParsedProblem;
import fr.uga.pddl4j.parser.RequireKey;
import fr.uga.pddl4j.plan.Plan;
import fr.uga.pddl4j.plan.SequentialPlan;
import fr.uga.pddl4j.planners.AbstractPlanner;
import fr.uga.pddl4j.planners.ProblemNotSupportedException;
import fr.uga.pddl4j.problem.DefaultProblem;
import fr.uga.pddl4j.problem.Problem;
import fr.uga.pddl4j.problem.State;
import fr.uga.pddl4j.problem.operator.Action;
import fr.uga.pddl4j.problem.operator.ConditionalEffect;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * MCTS / Monte-Carlo planning with "pure random walks" (Exercise 1).
 *
 * This is an online planner:
 * - at each step, it evaluates applicable actions by running N random rollouts
 * - chooses the best action
 * - repeats until goal / timeout / dead-end
 */
@CommandLine.Command(
    name = "MCTS",
    version = "MCTS 1.0",
    description = "Solves a specified planning problem using pure Monte-Carlo random walks.",
    sortOptions = false,
    mixinStandardHelpOptions = true
)
public class MCTSPlanner extends AbstractPlanner {

    private static final Logger LOGGER = LogManager.getLogger(MCTSPlanner.class.getName());

    // ---------- CLI options (specific to MCTS) ----------
    private int rolloutsPerAction = 200;   // N
    private int maxRolloutDepth = 60;      // depth limit for random walks
    private int maxPlanSteps = 200;        // safety cap for plan length
    private long seed = 0L;               // 0 => random seed

    @CommandLine.Option(
        names = {"-n", "--rollouts"},
        defaultValue = "200",
        paramLabel = "<N>",
        description = "Number of random rollouts per applicable action (preset 200)."
    )
    public void setRolloutsPerAction(int n) {
        if (n <= 0) throw new IllegalArgumentException("rolloutsPerAction must be > 0");
        this.rolloutsPerAction = n;
    }

    @CommandLine.Option(
        names = {"-d", "--depth"},
        defaultValue = "60",
        paramLabel = "<D>",
        description = "Maximum depth of a random walk rollout (preset 60)."
    )
    public void setMaxRolloutDepth(int d) {
        if (d <= 0) throw new IllegalArgumentException("maxRolloutDepth must be > 0");
        this.maxRolloutDepth = d;
    }

    @CommandLine.Option(
        names = {"-p", "--max-plan-steps"},
        defaultValue = "200",
        paramLabel = "<P>",
        description = "Maximum number of actions in the returned plan (preset 200)."
    )
    public void setMaxPlanSteps(int p) {
        if (p <= 0) throw new IllegalArgumentException("maxPlanSteps must be > 0");
        this.maxPlanSteps = p;
    }

    @CommandLine.Option(
        names = {"-s", "--seed"},
        defaultValue = "0",
        paramLabel = "<seed>",
        description = "Random seed (0 means random seed) (preset 0)."
    )
    public void setSeed(long seed) {
        this.seed = seed;
    }

    // ---------- Mandatory overrides ----------
    @Override
    public Problem instantiate(final DefaultParsedProblem problem) {
        final Problem pb = new DefaultProblem(problem);
        pb.instantiate();
        return pb;
    }

    /**
     * Same support policy as your ASP: only "classical" problems (no advanced requirements).
     */
    @Override
    public boolean isSupported(final Problem problem) {
        Set<RequireKey> r = problem.getRequirements();

        return !(r.contains(RequireKey.ACTION_COSTS)
              || r.contains(RequireKey.CONSTRAINTS)
              || r.contains(RequireKey.CONTINOUS_EFFECTS)
              || r.contains(RequireKey.DERIVED_PREDICATES)
              || r.contains(RequireKey.DURATIVE_ACTIONS)
              || r.contains(RequireKey.DURATION_INEQUALITIES)
              || r.contains(RequireKey.FLUENTS)
              || r.contains(RequireKey.GOAL_UTILITIES)
              || r.contains(RequireKey.METHOD_CONSTRAINTS)
              || r.contains(RequireKey.NUMERIC_FLUENTS)
              || r.contains(RequireKey.OBJECT_FLUENTS)
              || r.contains(RequireKey.PREFERENCES)
              || r.contains(RequireKey.TIMED_INITIAL_LITERALS)
              || r.contains(RequireKey.HIERARCHY));
    }

    @Override
    public Plan solve(final Problem problem) {
        try {
            return this.pureRandomWalkPlanner(problem);
        } catch (ProblemNotSupportedException e) {
            LOGGER.error("Problem not supported: {}", e.getMessage());
            return null;
        }
    }

    // ---------- Core: Pure random walks planning ----------
    private Plan pureRandomWalkPlanner(final Problem problem) throws ProblemNotSupportedException {
        if (!this.isSupported(problem)) {
            throw new ProblemNotSupportedException("Problem not supported");
        }

        final int timeoutMs = this.getTimeout() * 1000;
        final long t0 = System.currentTimeMillis();

        final long realSeed = (this.seed == 0L) ? System.nanoTime() : this.seed;
        final Random rng = new Random(realSeed);

        LOGGER.info("* Starting MCTS (pure random walks)\n");
        LOGGER.info("  rollouts/action = {}, rolloutDepth = {}, maxPlanSteps = {}, seed = {}",
            this.rolloutsPerAction, this.maxRolloutDepth, this.maxPlanSteps, realSeed);

        State current = new State(problem.getInitialState());
        final SequentialPlan plan = new SequentialPlan();

        int step = 0;
        while (!current.satisfy(problem.getGoal())) {

            if (step >= this.maxPlanSteps) {
                LOGGER.info("* MCTS stopped: reached maxPlanSteps.");
                return null;
            }
            if ((System.currentTimeMillis() - t0) >= timeoutMs) {
                LOGGER.info("* MCTS stopped: timeout reached.");
                return null;
            }

            final List<Integer> applicable = getApplicableActionIndices(problem, current);
            if (applicable.isEmpty()) {
                LOGGER.info("* MCTS failed: dead-end (no applicable action).");
                return null;
            }

            // Evaluate each applicable action with N random rollouts
            int bestActionIdx = -1;
            double bestScore = -1.0;
            double bestSuccessRate = -1.0;
            double bestAvgLenOnSuccess = Double.POSITIVE_INFINITY;

            for (int actIdx : applicable) {
                final Action a = problem.getActions().get(actIdx);
                final State next = applyAction(current, a);

                RolloutStats stats = evaluateByRollouts(problem, next, rng, t0, timeoutMs);

                if (stats.trials == 0) {
                    // No time to rollout
                    continue;
                }

                // Primary: average score
                // Tie-breakers: success rate, then avg length of successful rollouts
                if (stats.avgScore > bestScore
                        || (almostEqual(stats.avgScore, bestScore) && stats.successRate > bestSuccessRate)
                        || (almostEqual(stats.avgScore, bestScore) && almostEqual(stats.successRate, bestSuccessRate)
                            && stats.avgLenSuccess < bestAvgLenOnSuccess)) {

                    bestScore = stats.avgScore;
                    bestSuccessRate = stats.successRate;
                    bestAvgLenOnSuccess = stats.avgLenSuccess;
                    bestActionIdx = actIdx;
                }
            }

            if (bestActionIdx == -1) {
                LOGGER.info("* MCTS failed: no action could be evaluated (timeout too tight?).");
                return null;
            }

            // Apply best action for real
            final Action chosen = problem.getActions().get(bestActionIdx);
            current = applyAction(current, chosen);
            plan.add(plan.size(), chosen); // append at end
            step++;
        }

        LOGGER.info("* MCTS succeeded, plan found ({} steps).", plan.size());
        return plan;
    }

    /**
     * Evaluate a state by running rolloutsPerAction random walks.
     * Score is in [0,1], success gets higher score when shorter.
     */
    private RolloutStats evaluateByRollouts(final Problem problem,
                                            final State start,
                                            final Random rng,
                                            final long t0,
                                            final int timeoutMs) {

        int successes = 0;
        int trials = 0;
        double scoreSum = 0.0;
        int lenSumSuccess = 0;

        for (int i = 0; i < this.rolloutsPerAction; i++) {
            if ((System.currentTimeMillis() - t0) >= timeoutMs) break;

            RolloutResult rr = rollout(problem, start, rng);
            trials++;

            if (rr.success) {
                successes++;
                lenSumSuccess += rr.length;
                // success score: 1 - normalized length (shorter is better)
                double normalized = Math.min(1.0, (double) rr.length / (double) this.maxRolloutDepth);
                double score = 1.0 - normalized;
                scoreSum += score;
            } else {
                scoreSum += 0.0;
            }
        }

        RolloutStats stats = new RolloutStats();
        stats.trials = trials;
        stats.successes = successes;
        stats.successRate = (trials == 0) ? 0.0 : ((double) successes / (double) trials);
        stats.avgScore = (trials == 0) ? 0.0 : (scoreSum / (double) trials);
        stats.avgLenSuccess = (successes == 0) ? Double.POSITIVE_INFINITY : ((double) lenSumSuccess / (double) successes);
        return stats;
    }

    /**
     * One random walk from 'start' up to maxRolloutDepth.
     */
    private RolloutResult rollout(final Problem problem, final State start, final Random rng) {
        State s = new State(start);

        for (int depth = 0; depth < this.maxRolloutDepth; depth++) {
            if (s.satisfy(problem.getGoal())) {
                return new RolloutResult(true, depth);
            }
            List<Integer> applicable = getApplicableActionIndices(problem, s);
            if (applicable.isEmpty()) {
                return new RolloutResult(false, depth);
            }
            int pick = applicable.get(rng.nextInt(applicable.size()));
            Action a = problem.getActions().get(pick);
            s = applyAction(s, a);
        }

        // depth limit reached
        return new RolloutResult(s.satisfy(problem.getGoal()), this.maxRolloutDepth);
    }

    /**
     * Returns indices of actions applicable in a given state.
     */
    private static List<Integer> getApplicableActionIndices(final Problem problem, final State state) {
        final List<Action> actions = problem.getActions();
        final List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).isApplicable(state)) {
                idx.add(i);
            }
        }
        return idx;
    }

    /**
     * Applies an action to a state by using conditional effects (same logic as your A*).
     */
    private static State applyAction(final State state, final Action action) {
        final State next = new State(state);
        final List<ConditionalEffect> effects = action.getConditionalEffects();
        for (ConditionalEffect ce : effects) {
            if (state.satisfy(ce.getCondition())) {
                next.apply(ce.getEffect());
            }
        }
        return next;
    }

    private static boolean almostEqual(double a, double b) {
        return Math.abs(a - b) < 1e-12;
    }

    // ---------- small internal structs ----------
    private static final class RolloutResult {
        final boolean success;
        final int length;
        RolloutResult(boolean success, int length) {
            this.success = success;
            this.length = length;
        }
    }

    private static final class RolloutStats {
        int trials;
        int successes;
        double successRate;
        double avgScore;
        double avgLenSuccess;
    }

    // ---------- main ----------
    public static void main(String[] args) {
        try {
            final MCTSPlanner planner = new MCTSPlanner();
            CommandLine cmd = new CommandLine(planner);
            cmd.execute(args);
        } catch (IllegalArgumentException e) {
            LOGGER.fatal(e.getMessage());
        }
    }
}
