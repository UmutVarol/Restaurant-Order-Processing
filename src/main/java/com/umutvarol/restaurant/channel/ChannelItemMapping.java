package com.umutvarol.restaurant.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Which of our dishes a platform's menu item is. Nothing is guessed: an item without one stops its order. */
@Entity
@Table(name = "channel_item_mappings")
public class ChannelItemMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(name = "external_id", nullable = false, length = 80)
    private String externalId;

    @Column(name = "external_name", nullable = false, length = 120)
    private String externalName;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    protected ChannelItemMapping() {
        // for JPA
    }

    ChannelItemMapping(Channel channel, String externalId, String externalName, Long menuItemId) {
        this.channel = channel;
        this.externalId = externalId;
        update(externalName, menuItemId);
    }

    void update(String externalName, Long menuItemId) {
        this.externalName = externalName;
        this.menuItemId = menuItemId;
    }

    public Long getId() {
        return id;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getExternalName() {
        return externalName;
    }

    public Long getMenuItemId() {
        return menuItemId;
    }
}
