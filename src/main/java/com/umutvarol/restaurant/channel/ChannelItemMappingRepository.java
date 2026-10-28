package com.umutvarol.restaurant.channel;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelItemMappingRepository extends JpaRepository<ChannelItemMapping, Long> {

    List<ChannelItemMapping> findByChannelOrderByExternalNameAsc(Channel channel);

    Optional<ChannelItemMapping> findByChannelAndExternalId(Channel channel, String externalId);

    List<ChannelItemMapping> findByMenuItemId(Long menuItemId);
}
