-- A table whose guests have paid and left waits to be cleared and reset; the waiter's service screen lists it until
-- a waiter marks it done, or new guests sit down.
ALTER TABLE dining_tables ADD COLUMN needs_clearing_since TIMESTAMP WITH TIME ZONE;
