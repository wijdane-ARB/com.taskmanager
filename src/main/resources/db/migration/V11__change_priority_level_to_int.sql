ALTER TABLE tasks ALTER COLUMN priority_level DROP DEFAULT;
ALTER TABLE tasks ALTER COLUMN priority_level TYPE INTEGER USING
    CASE priority_level
        WHEN 'LOW' THEN 1
        WHEN 'NORMAL' THEN 2
        WHEN 'HIGH' THEN 3
        WHEN 'URGENT' THEN 4
        ELSE 2
    END;
ALTER TABLE tasks ALTER COLUMN priority_level SET DEFAULT 2;