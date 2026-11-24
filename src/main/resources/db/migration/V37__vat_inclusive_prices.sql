-- Prices guests see are the prices they pay (Fiyat Etiketi Yönetmeliği): menu prices now include VAT (KDV), and
-- each dish carries its own rate (food 10 %, alcoholic drinks 20 %).
--
-- Until now the menu held prices before VAT and 10 % was added on the bill. To keep what guests pay unchanged,
-- every stored price becomes its VAT-inclusive equivalent. Orders already placed keep their own figures and are
-- still read the old way (tax_included = FALSE).
ALTER TABLE menu_items ADD COLUMN vat_rate NUMERIC(5, 2) DEFAULT 10.00 NOT NULL;
ALTER TABLE menu_items ADD CONSTRAINT ck_menu_items_vat_rate CHECK (vat_rate >= 0 AND vat_rate <= 100);
-- when the price last changed: the price list at the door and on the tables shows the latest of these
ALTER TABLE menu_items ADD COLUMN price_changed_at TIMESTAMP WITH TIME ZONE;

UPDATE menu_items SET price = ROUND(price * 1.10, 2), price_changed_at = CURRENT_TIMESTAMP;
UPDATE option_choices SET price_delta = ROUND(price_delta * 1.10, 2);
UPDATE branch_menu_items SET price = ROUND(price * 1.10, 2) WHERE price IS NOT NULL;
ALTER TABLE branch_menu_items ADD COLUMN price_changed_at TIMESTAMP WITH TIME ZONE;
UPDATE branch_menu_items SET price_changed_at = CURRENT_TIMESTAMP WHERE price IS NOT NULL;

-- what each line was taxed at when ordered
ALTER TABLE order_lines ADD COLUMN vat_rate NUMERIC(5, 2) DEFAULT 10.00 NOT NULL;
-- how an order's figures read: TRUE, total = subtotal - discount + delivery fee and tax is the VAT within it;
-- FALSE (orders placed before this change), tax was added on top
ALTER TABLE orders ADD COLUMN tax_included BOOLEAN DEFAULT FALSE NOT NULL;
-- the VAT within the total by rate, "rate:gross:vat" joined by "|", e.g. "10.00:1650.00:150.00"
ALTER TABLE orders ADD COLUMN vat_lines VARCHAR(255);
