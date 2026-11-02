package com.taskmanager.core.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PriorityLevelConverter implements AttributeConverter<PriorityLevel, Integer> {

    @Override
    public Integer convertToDatabaseColumn(PriorityLevel priority) {
        if (priority == null) {
            return null;
        }
        return priority.getWeight();
    }

    @Override
    public PriorityLevel convertToEntityAttribute(Integer weight) {
        if (weight == null) {
            return null;
        }
        for (PriorityLevel p : PriorityLevel.values()) {
            if (p.getWeight() == weight) {
                return p;
            }
        }
        return PriorityLevel.NORMAL;
    }
}