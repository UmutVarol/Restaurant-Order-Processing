-- Reference data: the menu of "Sofra Bistro", its tables and promotional codes.

INSERT INTO menu_items (name, description, category, price, icon, prep_time_minutes, popular)
VALUES
    -- Starters
    ('Mercimek Çorbası', 'Velvety red lentil soup with lemon and paprika butter', 'STARTER', 165.00, '🥣', 5, TRUE),
    ('Sigara Böreği', 'Crispy filo rolls filled with white cheese and parsley', 'STARTER', 185.00, '🥟', 8, TRUE),
    ('Humus', 'Chickpea purée with tahini, warm butter and pine nuts', 'STARTER', 175.00, '🧆', 4, FALSE),
    ('Çoban Salata', 'Tomato, cucumber, green pepper, red onion and sumac', 'STARTER', 150.00, '🥗', 5, FALSE),
    ('Acılı Ezme', 'Fiery tomato and pepper relish with pomegranate molasses', 'STARTER', 140.00, '🌶️', 4, FALSE),
    ('Paçanga Böreği', 'Golden filo parcels with pastırma and melted kaşar', 'STARTER', 210.00, '🥐', 9, FALSE),
    -- Mains
    ('Adana Kebap', 'Hand-minced lamb kebab with chilli, lavash and grilled peppers', 'MAIN', 520.00, '🍢', 18, TRUE),
    ('İskender', 'Döner over pide with tomato sauce, browned butter and yogurt', 'MAIN', 560.00, '🥩', 15, TRUE),
    ('Lahmacun', 'Thin crispy flatbread with spiced minced lamb, herbs and lemon', 'MAIN', 220.00, '🍕', 10, TRUE),
    ('Mantı', 'Tiny beef dumplings with garlic yogurt and chilli butter', 'MAIN', 410.00, '🥟', 14, TRUE),
    ('Izgara Tavuk', 'Marinated chicken thigh, bulgur pilaf and smoked aubergine', 'MAIN', 450.00, '🍗', 16, FALSE),
    ('Köfte Tabağı', 'Grilled Turkish meatballs with piyaz and roasted tomatoes', 'MAIN', 470.00, '🍖', 14, FALSE),
    ('Karnıyarık', 'Aubergine stuffed with spiced beef, served with rice pilaf', 'MAIN', 430.00, '🍆', 12, FALSE),
    ('Levrek Izgara', 'Charcoal-grilled sea bass with rocket and lemon', 'MAIN', 640.00, '🐟', 20, FALSE),
    ('Sebzeli Güveç', 'Clay-pot vegetable stew with herbs and rice', 'MAIN', 380.00, '🥘', 15, FALSE),
    -- Desserts
    ('Künefe', 'Hot shredded pastry with melting cheese and pistachio syrup', 'DESSERT', 260.00, '🧀', 12, TRUE),
    ('Fıstıklı Baklava', 'Four pieces of pistachio baklava with clotted cream', 'DESSERT', 280.00, '🍯', 2, TRUE),
    ('Fırın Sütlaç', 'Oven-baked rice pudding dusted with cinnamon', 'DESSERT', 160.00, '🍮', 2, FALSE),
    ('San Sebastian Cheesecake', 'Burnt Basque cheesecake with a creamy centre', 'DESSERT', 230.00, '🍰', 2, FALSE),
    -- Drinks
    ('Türk Kahvesi', 'Turkish coffee served with lokum', 'DRINK', 110.00, '☕', 5, TRUE),
    ('Demlik Çay', 'Rize black tea, brewed in a double teapot', 'DRINK', 45.00, '🍵', 1, TRUE),
    ('Ayran', 'Frothy salted yogurt drink', 'DRINK', 70.00, '🥛', 1, FALSE),
    ('Taze Portakal Suyu', 'Freshly squeezed orange juice', 'DRINK', 130.00, '🍊', 3, FALSE),
    ('Ev Yapımı Limonata', 'Homemade lemonade with fresh mint', 'DRINK', 120.00, '🍋', 3, FALSE),
    ('Şalgam', 'Fermented purple carrot juice, hot or mild', 'DRINK', 75.00, '🥤', 1, FALSE),
    ('Maden Suyu', 'Sparkling mineral water', 'DRINK', 55.00, '💧', 1, FALSE);

INSERT INTO menu_item_tags (menu_item_id, tag)
SELECT id, 'VEGETARIAN'
FROM menu_items
WHERE name IN ('Mercimek Çorbası', 'Sigara Böreği', 'Humus', 'Çoban Salata', 'Acılı Ezme', 'Sebzeli Güveç',
               'Künefe', 'Fıstıklı Baklava', 'Fırın Sütlaç', 'San Sebastian Cheesecake');

INSERT INTO menu_item_tags (menu_item_id, tag)
SELECT id, 'VEGAN'
FROM menu_items
WHERE name IN ('Çoban Salata', 'Acılı Ezme', 'Sebzeli Güveç');

INSERT INTO menu_item_tags (menu_item_id, tag)
SELECT id, 'GLUTEN_FREE'
FROM menu_items
WHERE name IN ('Mercimek Çorbası', 'Humus', 'Çoban Salata', 'Acılı Ezme', 'Levrek Izgara', 'Sebzeli Güveç',
               'Fırın Sütlaç');

INSERT INTO menu_item_tags (menu_item_id, tag)
SELECT id, 'SPICY'
FROM menu_items
WHERE name IN ('Acılı Ezme', 'Adana Kebap', 'Mantı', 'Şalgam');

INSERT INTO dining_tables (code, seats, zone)
VALUES ('A1', 2, 'INDOOR'),
       ('A2', 4, 'INDOOR'),
       ('A3', 4, 'INDOOR'),
       ('A4', 6, 'INDOOR'),
       ('A5', 4, 'INDOOR'),
       ('A6', 8, 'INDOOR'),
       ('W1', 2, 'WINDOW'),
       ('W2', 2, 'WINDOW'),
       ('T1', 4, 'TERRACE'),
       ('T2', 4, 'TERRACE'),
       ('T3', 6, 'TERRACE'),
       ('T4', 2, 'TERRACE');

INSERT INTO coupons (code, description, discount_type, discount_value, minimum_subtotal, max_discount, active, valid_until)
VALUES ('HOSGELDIN', 'Welcome offer: 10% off', 'PERCENTAGE', 10.00, 0.00, 150.00, TRUE, NULL),
       ('SOFRA20', '20% off orders of 1,000 TL or more', 'PERCENTAGE', 20.00, 1000.00, 400.00, TRUE, NULL),
       ('TATLI50', '50 TL off when you spend 400 TL', 'FIXED_AMOUNT', 50.00, 400.00, NULL, TRUE, NULL),
       ('OGRENCI15', 'Student discount: 15% off', 'PERCENTAGE', 15.00, 200.00, 200.00, TRUE, NULL),
       ('YAZ2025', 'Summer 2025 campaign', 'PERCENTAGE', 25.00, 0.00, NULL, TRUE, TIMESTAMP WITH TIME ZONE '2025-09-01 00:00:00+03:00');
