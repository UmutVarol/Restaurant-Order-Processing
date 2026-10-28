package com.umutvarol.restaurant.bootstrap;

import com.umutvarol.restaurant.channel.Channel;
import com.umutvarol.restaurant.channel.ChannelService;
import com.umutvarol.restaurant.channel.dto.MappingRequest;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Demo mode: the simulated platforms list a few of our dishes under their own ids and names. */
@Component
class DemoChannelSeeder implements ApplicationRunner {

    /** Platform item id prefix, then our dish and the platform's name for it. */
    private static final List<String[]> ITEMS = List.of(
            new String[] {"Adana Kebap", "Adana Kebap (Porsiyon)"},
            new String[] {"Lahmacun", "Lahmacun"},
            new String[] {"İskender", "İskender Kebap"},
            new String[] {"Mercimek Çorbası", "Mercimek Çorbası"},
            new String[] {"Künefe", "Künefe"},
            new String[] {"Ayran", "Ayran 300 ml"});

    private static final Map<Channel, String> PREFIX = Map.of(Channel.YEMEKSEPETI, "YS-", Channel.GETIR, "GTR-",
            Channel.TRENDYOL, "TY-");

    private final DemoProperties properties;
    private final ChannelService channels;
    private final MenuService menu;

    DemoChannelSeeder(DemoProperties properties, ChannelService channels, MenuService menu) {
        this.properties = properties;
        this.channels = channels;
        this.menu = menu;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        Map<String, Long> dishes = menu.list(null, null, true).stream()
                .collect(Collectors.toMap(MenuItemResponse::name, MenuItemResponse::id));
        for (Channel channel : Channel.values()) {
            if (!channels.mappings(channel).isEmpty()) {
                continue;
            }
            for (int i = 0; i < ITEMS.size(); i++) {
                Long dish = dishes.get(ITEMS.get(i)[0]);
                if (dish != null) {
                    channels.map(channel, PREFIX.get(channel) + (1001 + i), new MappingRequest(ITEMS.get(i)[1], dish));
                }
            }
        }
    }
}
