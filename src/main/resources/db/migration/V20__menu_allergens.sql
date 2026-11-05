-- The fourteen allergens EU Regulation 1169/2011 (and the Turkish Food Codex after it) asks restaurants to declare.
-- Guests see them on the table's QR menu and can hide what they cannot eat.
CREATE TABLE menu_item_allergens
(
    menu_item_id BIGINT      NOT NULL REFERENCES menu_items (id) ON DELETE CASCADE,
    allergen     VARCHAR(20) NOT NULL,
    PRIMARY KEY (menu_item_id, allergen),
    CONSTRAINT ck_menu_item_allergens CHECK (allergen IN ('GLUTEN', 'CRUSTACEANS', 'EGGS', 'FISH', 'PEANUTS', 'SOY',
        'MILK', 'NUTS', 'CELERY', 'MUSTARD', 'SESAME', 'SULPHITES', 'LUPIN', 'MOLLUSCS'))
);

-- The seeded dishes, by what their recipes hold. A restaurant that renamed or replaced them sets its own.
INSERT INTO menu_item_allergens (menu_item_id, allergen)
SELECT id, 'GLUTEN' FROM menu_items
WHERE name IN ('Sigara Böreği', 'Paçanga Böreği', 'Adana Kebap', 'İskender', 'Lahmacun', 'Mantı', 'Izgara Tavuk',
               'Köfte Tabağı', 'Künefe', 'Fıstıklı Baklava', 'San Sebastian Cheesecake');

INSERT INTO menu_item_allergens (menu_item_id, allergen)
SELECT id, 'MILK' FROM menu_items
WHERE name IN ('Mercimek Çorbası', 'Sigara Böreği', 'Humus', 'Paçanga Böreği', 'İskender', 'Mantı', 'Künefe',
               'Fıstıklı Baklava', 'Fırın Sütlaç', 'San Sebastian Cheesecake', 'Ayran');

INSERT INTO menu_item_allergens (menu_item_id, allergen)
SELECT id, 'EGGS' FROM menu_items
WHERE name IN ('Sigara Böreği', 'Paçanga Böreği', 'Mantı', 'Köfte Tabağı', 'San Sebastian Cheesecake');

INSERT INTO menu_item_allergens (menu_item_id, allergen)
SELECT id, 'NUTS' FROM menu_items WHERE name IN ('Humus', 'Künefe', 'Fıstıklı Baklava');

INSERT INTO menu_item_allergens (menu_item_id, allergen)
SELECT id, 'SESAME' FROM menu_items WHERE name IN ('Humus');

INSERT INTO menu_item_allergens (menu_item_id, allergen)
SELECT id, 'FISH' FROM menu_items WHERE name IN ('Levrek Izgara');
