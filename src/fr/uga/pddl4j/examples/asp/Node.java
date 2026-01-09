package fr.uga.pddl4j.examples.asp;

import fr.uga.pddl4j.problem.State;

/**
 * Node of the search tree for A*.
 */
public final class Node extends State {

    /**
     * Parent node in the search tree.
     */
    private Node parent;

    /**
     * Index of the action used to reach this node.
     */
    private int action;

    /**
     * Cost from the root to this node.
     */
    private double cost;

    /**
     * Heuristic estimate from this node to the goal.
     */
    private double heuristic;

    /**
     * Depth of this node in the search tree.
     */
    private int depth;

    /**
     * Creates a new node from a given state.
     *
     * @param state the state to wrap.
     */
    public Node(final State state) {
        super(state);
        this.parent = null;
        this.action = -1;
        this.cost = 0.0;
        this.heuristic = 0.0;
        this.depth = 0;
    }

    /**
     * Copy constructor.
     *
     * @param node the node to copy.
     */
    public Node(final Node node) {
        super(node);
        this.parent = node.parent;
        this.action = node.action;
        this.cost = node.cost;
        this.heuristic = node.heuristic;
        this.depth = node.depth;
    }

    public Node getParent() {
        return parent;
    }

    public void setParent(final Node parent) {
        this.parent = parent;
    }

    public int getAction() {
        return action;
    }

    public void setAction(final int action) {
        this.action = action;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(final double cost) {
        this.cost = cost;
    }

    public double getHeuristic() {
        return heuristic;
    }

    public void setHeuristic(final double heuristic) {
        this.heuristic = heuristic;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(final int depth) {
        this.depth = depth;
    }

    /**
     * Returns f(n) = g(n) + w*h(n).
     *
     * @param weight the heuristic weight.
     * @return the value of f for this node.
     */
    public double getValueF(final double weight) {
        return weight * this.heuristic + this.cost;
    }
}
