package com.umutvarol.restaurant.bootstrap;

import com.umutvarol.restaurant.inventory.MovementKind;
import com.umutvarol.restaurant.inventory.QuantityUnit;
import com.umutvarol.restaurant.inventory.RecipeService;
import com.umutvarol.restaurant.inventory.StockService;
import com.umutvarol.restaurant.inventory.StockUnit;
import com.umutvarol.restaurant.inventory.dto.IngredientRequest;
import com.umutvarol.restaurant.inventory.dto.IngredientResponse;
import com.umutvarol.restaurant.inventory.dto.MovementRequest;
import com.umutvarol.restaurant.inventory.dto.RecipeLineRequest;
import com.umutvarol.restaurant.inventory.dto.RecipeRequest;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode: ingredients with opening stock and recipes for most of the seeded menu, entered through the same
 * services a manager uses. Pistachios start close to their low level, so the warning shows after a few desserts.
 */
@Component
class DemoStockSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoStockSeeder.class);

    /** Name, unit, price per kg / l / piece, low level and opening stock (base units), tracked. */
    private record Spec(String name, StockUnit unit, int cost, int low, int opening, boolean tracked) {
    }

    private static final List<Spec> INGREDIENTS = List.of(
            new Spec("Kırmızı mercimek", StockUnit.GRAM, 90, 2000, 12000, true),
            new Spec("Soğan", StockUnit.GRAM, 30, 3000, 15000, true),
            new Spec("Tereyağı", StockUnit.GRAM, 520, 1500, 6000, true),
            new Spec("Yufka", StockUnit.PIECE, 12, 20, 120, true),
            new Spec("Beyaz peynir", StockUnit.GRAM, 380, 1000, 5000, true),
            new Spec("Nohut", StockUnit.GRAM, 85, 1500, 8000, true),
            new Spec("Tahin", StockUnit.MILLILITRE, 240, 500, 3000, true),
            new Spec("Domates", StockUnit.GRAM, 45, 4000, 20000, true),
            new Spec("Salatalık", StockUnit.GRAM, 40, 1500, 8000, true),
            new Spec("Kuzu kıyma", StockUnit.GRAM, 780, 4000, 15000, true),
            new Spec("Dana kıyma", StockUnit.GRAM, 650, 3000, 12000, true),
            new Spec("Döner eti", StockUnit.GRAM, 700, 3000, 10000, true),
            new Spec("Lavaş", StockUnit.PIECE, 6, 30, 150, true),
            new Spec("Pide", StockUnit.PIECE, 10, 15, 60, true),
            new Spec("Yoğurt", StockUnit.GRAM, 110, 2000, 12000, true),
            new Spec("Sarımsak", StockUnit.GRAM, 160, 300, 2000, true),
            new Spec("Mantı hamuru", StockUnit.GRAM, 240, 1500, 8000, true),
            new Spec("Tavuk but", StockUnit.GRAM, 260, 3000, 12000, true),
            new Spec("Bulgur", StockUnit.GRAM, 70, 1500, 8000, true),
            new Spec("Patlıcan", StockUnit.GRAM, 50, 2000, 10000, true),
            new Spec("Pirinç", StockUnit.GRAM, 75, 2000, 10000, true),
            new Spec("Levrek", StockUnit.PIECE, 280, 6, 18, true),
            new Spec("Kadayıf", StockUnit.GRAM, 180, 800, 4000, true),
            new Spec("Künefe peyniri", StockUnit.GRAM, 420, 800, 4000, true),
            new Spec("Antep fıstığı", StockUnit.GRAM, 1400, 500, 560, true),
            new Spec("Baklava", StockUnit.PIECE, 25, 40, 160, true),
            new Spec("Kaymak", StockUnit.GRAM, 600, 300, 1500, true),
            new Spec("Dondurma", StockUnit.MILLILITRE, 300, 800, 4000, true),
            new Spec("Süt", StockUnit.MILLILITRE, 40, 4000, 20000, true),
            new Spec("Türk kahvesi", StockUnit.GRAM, 900, 300, 2000, true),
            new Spec("Siyah çay", StockUnit.GRAM, 320, 400, 3000, true),
            new Spec("Portakal", StockUnit.PIECE, 4, 40, 200, true),
            new Spec("Limon", StockUnit.PIECE, 3, 30, 150, true),
            new Spec("Ayran (şişe)", StockUnit.PIECE, 9, 24, 120, true),
            new Spec("Maden suyu", StockUnit.PIECE, 8, 24, 120, true),
            new Spec("Zeytinyağı", StockUnit.MILLILITRE, 450, 0, 0, false),
            new Spec("Şeker", StockUnit.GRAM, 40, 0, 0, false));

    /** One recipe line; group and choice are set for an option, replaces when the option swaps an ingredient. */
    private record Line(String group, String choice, String ingredient, String quantity, String replaces) {
    }

    private static final Map<String, List<Line>> RECIPES = Map.ofEntries(
            Map.entry("Mercimek Çorbası", List.of(line("Kırmızı mercimek", "60"), line("Soğan", "20"), line("Tereyağı", "10"))),
            Map.entry("Sigara Böreği", List.of(line("Yufka", "1"), line("Beyaz peynir", "60"))),
            Map.entry("Humus", List.of(line("Nohut", "80"), line("Tahin", "20"), line("Tereyağı", "10"))),
            Map.entry("Çoban Salata", List.of(line("Domates", "120"), line("Salatalık", "80"), line("Soğan", "20"),
                    line("Zeytinyağı", "10"), line("Limon", "0.25"))),
            Map.entry("Acılı Ezme", List.of(line("Domates", "100"), line("Soğan", "20"), line("Zeytinyağı", "10"))),
            Map.entry("Adana Kebap", List.of(line("Kuzu kıyma", "200"), line("Lavaş", "1"), line("Domates", "60"),
                    option("Ekstra", "Közlenmiş domates", "Domates", "80", null),
                    option("Ekstra", "Ekstra lavaş", "Lavaş", "1", null))),
            Map.entry("İskender", List.of(line("Döner eti", "180"), line("Pide", "1"), line("Yoğurt", "80"), line("Tereyağı", "25"),
                    option("Tereyağı", "Az", "Tereyağı", "12", "Tereyağı"),
                    option("Tereyağı", "Bol", "Tereyağı", "15", null),
                    option("Ekstra", "Ekstra yoğurt", "Yoğurt", "60", null))),
            Map.entry("Lahmacun", List.of(line("Kuzu kıyma", "60"), line("Domates", "30"), line("Soğan", "15"), line("Limon", "0.25"))),
            Map.entry("Mantı", List.of(line("Mantı hamuru", "200"), line("Yoğurt", "100"), line("Sarımsak", "5"), line("Tereyağı", "15"),
                    option("Sos", "Sarımsaksız yoğurt", "Sarımsak", "0", "Sarımsak"),
                    option("Ekstra", "Ekstra tereyağı", "Tereyağı", "15", null))),
            Map.entry("Izgara Tavuk", List.of(line("Tavuk but", "220"), line("Bulgur", "80"), line("Patlıcan", "120"))),
            Map.entry("Köfte Tabağı", List.of(line("Dana kıyma", "180"), line("Domates", "60"), line("Soğan", "20"))),
            Map.entry("Karnıyarık", List.of(line("Patlıcan", "250"), line("Dana kıyma", "100"), line("Pirinç", "80"))),
            Map.entry("Levrek Izgara", List.of(line("Levrek", "1"), line("Limon", "0.5"))),
            Map.entry("Sebzeli Güveç", List.of(line("Patlıcan", "120"), line("Domates", "120"), line("Pirinç", "80"), line("Zeytinyağı", "15"))),
            Map.entry("Künefe", List.of(line("Kadayıf", "80"), line("Künefe peyniri", "70"), line("Antep fıstığı", "15"),
                    line("Tereyağı", "30"), line("Şeker", "40"),
                    option("Yanında", "Dondurma", "Dondurma", "80", null),
                    option("Yanında", "Kaymak", "Kaymak", "40", null))),
            Map.entry("Fıstıklı Baklava", List.of(line("Baklava", "4"), line("Antep fıstığı", "10"), line("Kaymak", "30"))),
            Map.entry("Fırın Sütlaç", List.of(line("Süt", "200"), line("Pirinç", "30"), line("Şeker", "30"))),
            Map.entry("Türk Kahvesi", List.of(line("Türk kahvesi", "7"))),
            Map.entry("Demlik Çay", List.of(line("Siyah çay", "6"))),
            Map.entry("Ayran", List.of(line("Ayran (şişe)", "1"))),
            Map.entry("Taze Portakal Suyu", List.of(line("Portakal", "3"))),
            Map.entry("Ev Yapımı Limonata", List.of(line("Limon", "2"), line("Şeker", "30"))),
            Map.entry("Maden Suyu", List.of(line("Maden suyu", "1"))));

    private final DemoProperties properties;
    private final StockService stock;
    private final RecipeService recipes;
    private final MenuService menu;

    DemoStockSeeder(DemoProperties properties, StockService stock, RecipeService recipes, MenuService menu) {
        this.properties = properties;
        this.stock = stock;
        this.recipes = recipes;
        this.menu = menu;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled() || !stock.ingredients().isEmpty()) {
            return;
        }
        Map<String, Long> ids = new HashMap<>();
        for (Spec spec : INGREDIENTS) {
            IngredientResponse ingredient = stock.create(new IngredientRequest(spec.name(), spec.unit(),
                    BigDecimal.valueOf(spec.cost()), spec.tracked() ? BigDecimal.valueOf(spec.low()) : null, spec.tracked()));
            ids.put(spec.name(), ingredient.id());
            if (spec.opening() > 0) {
                QuantityUnit unit = switch (spec.unit()) {
                    case GRAM -> QuantityUnit.G;
                    case MILLILITRE -> QuantityUnit.ML;
                    case PIECE -> QuantityUnit.PIECE;
                };
                stock.record(ingredient.id(), new MovementRequest(MovementKind.COUNT, BigDecimal.valueOf(spec.opening()), unit,
                        "Opening stock", null, null), "system");
            }
        }
        Map<String, Long> dishes = menu.list(null, null, true).stream()
                .collect(Collectors.toMap(MenuItemResponse::name, MenuItemResponse::id));
        RECIPES.forEach((dish, lines) -> {
            Long id = dishes.get(dish);
            if (id == null) {
                return;
            }
            List<RecipeLineRequest> requests = new ArrayList<>();
            for (Line line : lines) {
                requests.add(new RecipeLineRequest(ids.get(line.ingredient()), new BigDecimal(line.quantity()),
                        line.group(), line.choice(), line.replaces() == null ? null : ids.get(line.replaces())));
            }
            recipes.replace(id, new RecipeRequest(requests));
        });
        log.info("Demo mode: seeded {} ingredients and {} recipes", INGREDIENTS.size(), RECIPES.size());
    }

    private static Line line(String ingredient, String quantity) {
        return new Line(null, null, ingredient, quantity, null);
    }

    private static Line option(String group, String choice, String ingredient, String quantity, String replaces) {
        return new Line(group, choice, ingredient, quantity, replaces);
    }
}
