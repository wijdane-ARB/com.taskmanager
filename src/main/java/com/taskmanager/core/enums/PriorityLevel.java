package com.taskmanager.core.enums;

/**
 * Maps directly to the 4-tier priority system in the product spec.
 * The integer value is persisted to the DB and used as a feature
 * weight in the Python RL model's reward function.
 */
public enum PriorityLevel {
    LOW(1),
    NORMAL(2),
    HIGH(3),
    URGENT(4);

    private final int weight;

    PriorityLevel(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}