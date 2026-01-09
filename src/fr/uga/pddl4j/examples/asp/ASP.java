package fr.uga.pddl4j.examples.asp;

import fr.uga.pddl4j.heuristics.state.StateHeuristic;
import fr.uga.pddl4j.parser.DefaultParsedProblem;
import fr.uga.pddl4j.parser.RequireKey;
import fr.uga.pddl4j.plan.Plan;
import fr.uga.pddl4j.plan.SequentialPlan;
import fr.uga.pddl4j.planners.AbstractPlanner;
import fr.uga.pddl4j.planners.Planner;
import fr.uga.pddl4j.planners.PlannerConfiguration;
import fr.uga.pddl4j.planners.ProblemNotSupportedException;
import fr.uga.pddl4j.problem.DefaultProblem;
import fr.uga.pddl4j.problem.Problem;
import fr.uga.pddl4j.problem.State;
import fr.uga.pddl4j.problem.operator.Action;
import fr.uga.pddl4j.problem.operator.ConditionalEffect;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import picocli.CommandLine;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Simple A* planner example.
 */
@CommandLine.Command(
    name = "ASP",
    version = "ASP 1.0",
    description = "Solves a specified planning problem using A* search strategy.",
    sortOptions = false,
    mixinStandardHelpOptions = true,
    headerHeading = "Usage:%n",
    synopsisHeading = "%n",
    descriptionHeading = "%nDescription:%n%n",
    parameterListHeading = "%nParameters:%n",
    optionListHeading = "%nOptions:%n"
)
public class ASP extends AbstractPlanner {

    /**
     * Logger.
     */
    private static final Logger LOGGER = LogManager.getLogger(ASP.class.getName());

    /**
     * Heuristic weight.
     */
    private double heuristicWeight;

    /**
     * Heuristic name.
     */
    private StateHeuristic.Name heuristic;

    // ============================================================
    //  Command-line options for heuristic and weight
    // ============================================================

    @CommandLine.Option(
        names = {"-w", "--weight"},
        defaultValue = "1.0",
        paramLabel = "<weight>",
        description = "Set the weight of the heuristic (preset 1.0)."
    )
    public void setHeuristicWeight(final double weight) {
        if (weight <= 0) {
            throw new IllegalArgumentException("Weight <= 0");
        }
        this.heuristicWeight = weight;
    }

    @CommandLine.Option(
        names = {"-e", "--heuristic"},
        defaultValue = "FAST_FORWARD",
        description = "Set the heuristic : AJUSTED_SUM, AJUSTED_SUM2, AJUSTED_SUM2M, COMBO, "
            + "MAX, FAST_FORWARD, SET_LEVEL, SUM, SUM_MUTEX (preset: FAST_FORWARD)"
    )
    public void setHeuristic(final StateHeuristic.Name heuristic) {
        this.heuristic = heuristic;
    }

    public final StateHeuristic.Name getHeuristic() {
        return this.heuristic;
    }

    public final double getHeuristicWeight() {
        return this.heuristicWeight;
    }

    // ============================================================
    //  Mandatory overrides from AbstractPlanner
    // ============================================================

    @Override
    public Problem instantiate(final DefaultParsedProblem problem) {
        final Problem pb = new DefaultProblem(problem);
        pb.instantiate();
        return pb;
    }

    /**
     * Entry point: here we call our own A* implementation.
     */
    @Override
    public Plan solve(final Problem problem) {
        try {
            return this.astar(problem);
        } catch (ProblemNotSupportedException e) {
            LOGGER.error("Problem not supported: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Only classical ADL problems are supported.
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

    // ============================================================
    //  Custom A* search (section 5 du TP)
    // ============================================================

    /**
     * Custom A* search using our Node class.
     *
     * @param problem the planning problem.
     * @return a plan if one is found, null otherwise.
     * @throws ProblemNotSupportedException if the problem is not supported.
     */
    public Plan astar(final Problem problem) throws ProblemNotSupportedException {

        // Check if we can handle this problem
        if (!this.isSupported(problem)) {
            throw new ProblemNotSupportedException("Problem not supported");
        }

        // Heuristic instance
        final StateHeuristic hfun =
            StateHeuristic.getInstance(this.getHeuristic(), problem);

        // Initial state
        final State init = new State(problem.getInitialState());

        // Closed list (already explored nodes)
        final Set<Node> closed = new HashSet<>();

        // Open list (pending nodes) sorted by f = g + w*h
        final double w = this.getHeuristicWeight();
        final PriorityQueue<Node> open = new PriorityQueue<>(100, new Comparator<Node>() {
            @Override
            public int compare(final Node n1, final Node n2) {
                double f1 = n1.getValueF(w);
                double f2 = n2.getValueF(w);
                return Double.compare(f1, f2);
            }
        });

        // Root node
        final Node root = new Node(init);
        root.setParent(null);
        root.setAction(-1);
        root.setCost(0.0);
        root.setHeuristic(hfun.estimate(init, problem.getGoal()));
        root.setDepth(0);

        open.add(root);

        Plan plan = null;

        final int timeoutMs = this.getTimeout() * 1000;
        final long startTime = System.currentTimeMillis();

        LOGGER.info("* Starting custom A* search");

        while (!open.isEmpty()
                && plan == null
                && (System.currentTimeMillis() - startTime) < timeoutMs) {

            // Get best node according to f
            final Node current = open.poll();
            closed.add(current);

            // Goal test
            if (current.satisfy(problem.getGoal())) {
                plan = this.extractPlan(current, problem);
            } else {
                // Expand node
                final List<Action> actions = problem.getActions();
                for (int i = 0; i < actions.size(); i++) {
                    final Action a = actions.get(i);

                    if (a.isApplicable(current)) {
                        // Child node
                        final Node next = new Node(current);

                        // Apply conditional effects
                        final List<ConditionalEffect> effects = a.getConditionalEffects();
                        for (ConditionalEffect ce : effects) {
                            if (current.satisfy(ce.getCondition())) {
                                next.apply(ce.getEffect());
                            }
                        }

                        final double g = current.getCost() + 1.0;

                        if (!closed.contains(next)) {
                            next.setCost(g);
                            next.setParent(current);
                            next.setAction(i);
                            next.setHeuristic(hfun.estimate(next, problem.getGoal()));
                            next.setDepth(current.getDepth() + 1);
                            open.add(next);
                        }
                    }
                }
            }
        }

        if (plan != null) {
            LOGGER.info("* Custom A* succeeded, plan found.");
        } else {
            LOGGER.info("* Custom A* failed or timeout reached.");
        }

        return plan;
    }

    /**
     * Extracts a plan from a goal node by backtracking to the root.
     */
    private Plan extractPlan(final Node node, final Problem problem) {
        final SequentialPlan plan = new SequentialPlan();
        Node current = node;

        while (current.getParent() != null) {
            int actIndex = current.getAction();
            Action a = problem.getActions().get(actIndex);
            plan.add(0, a); // add at the beginning
            current = current.getParent();
        }

        return plan;
    }

    // ============================================================
    //  Main
    // ============================================================

    /**
     * Main method.
     */
    public static void main(String[] args) {
        try {
            final ASP planner = new ASP();
            CommandLine cmd = new CommandLine(planner);
            cmd.execute(args);
        } catch (IllegalArgumentException e) {
            LOGGER.fatal(e.getMessage());
        }
    }
}
