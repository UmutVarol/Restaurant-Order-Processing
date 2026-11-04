-- Tables are known by plain numbers from 1, which guests and new staff read at a glance, instead of a letter for
-- the zone and a number (A1, W2, T3). The zone stays its own column. Only the seeded codes are renamed, so a
-- restaurant that already chose its own codes keeps them.
UPDATE dining_tables
SET code = CASE code
               WHEN 'A1' THEN '1'
               WHEN 'A2' THEN '2'
               WHEN 'A3' THEN '3'
               WHEN 'A4' THEN '4'
               WHEN 'A5' THEN '5'
               WHEN 'A6' THEN '6'
               WHEN 'W1' THEN '7'
               WHEN 'W2' THEN '8'
               WHEN 'T1' THEN '9'
               WHEN 'T2' THEN '10'
               WHEN 'T3' THEN '11'
               WHEN 'T4' THEN '12'
    END
WHERE code IN ('A1', 'A2', 'A3', 'A4', 'A5', 'A6', 'W1', 'W2', 'T1', 'T2', 'T3', 'T4')
  AND NOT EXISTS (SELECT 1
                  FROM dining_tables taken
                  WHERE taken.code IN ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12'));
