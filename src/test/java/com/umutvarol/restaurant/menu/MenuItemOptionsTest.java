package com.umutvarol.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.common.error.DomainException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MenuItemOptionsTest {

    private final MenuItem kebab = new MenuItem("Adana Kebap", null, MenuCategory.MAIN, new BigDecimal("520"), "🍢", 18,
            true, Set.of());

    {
        kebab.replaceOptions(List.of(
                new OptionGroupSpec("Spice", 1, 1, List.of(choice("Mild", "0"), choice("Hot", "0"))),
                new OptionGroupSpec("Extras", 0, 2, List.of(choice("Cheese", "40"), choice("Bread", "20"), choice("Pepper", "0")))));
        AtomicLong ids = new AtomicLong(1);
        kebab.getOptionGroups().forEach(group -> group.getChoices()
                .forEach(c -> ReflectionTestUtils.setField(c, "id", ids.getAndIncrement())));
    }

    @Test
    void requiredGroupsFallBackToTheirFirstChoice() {
        assertThat(kebab.resolveOptions(null)).extracting(OptionChoice::getName).containsExactly("Mild");
        assertThat(kebab.resolveOptions(List.of(2L, 3L))).extracting(OptionChoice::getName).containsExactly("Hot", "Cheese");
    }

    @Test
    void refusesChoicesFromAnotherDishOrOverTheLimit() {
        assertThatThrownBy(() -> kebab.resolveOptions(List.of(99L)))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("OPTION_NOT_FOUND");
        assertThatThrownBy(() -> kebab.resolveOptions(List.of(1L, 2L)))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("OPTION_LIMIT");
        assertThatThrownBy(() -> kebab.resolveOptions(List.of(3L, 4L, 5L)))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("OPTION_LIMIT");
    }

    @Test
    void refusesGroupsThatCannotBeSatisfied() {
        assertThatThrownBy(() -> kebab.replaceOptions(List.of(new OptionGroupSpec("Size", 2, 2, List.of(choice("Small", "0"))))))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("MENU_OPTIONS_INVALID");
        assertThatThrownBy(() -> kebab.replaceOptions(List.of(new OptionGroupSpec("Size", 0, 1,
                List.of(choice("Small", "0"), choice("small", "5"))))))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("MENU_OPTIONS_INVALID");
        assertThatThrownBy(() -> kebab.replaceOptions(List.of(new OptionGroupSpec("Size", 0, 1, List.of(choice("Half", "-10"))))))
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("MENU_OPTIONS_INVALID");
    }

    private static OptionGroupSpec.Choice choice(String name, String delta) {
        return new OptionGroupSpec.Choice(name, new BigDecimal(delta));
    }
}
