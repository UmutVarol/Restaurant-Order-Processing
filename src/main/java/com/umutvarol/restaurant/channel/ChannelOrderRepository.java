package com.umutvarol.restaurant.channel;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelOrderRepository extends JpaRepository<ChannelOrder, Long> {

    Optional<ChannelOrder> findByChannelAndExternalId(Channel channel, String externalId);

    Optional<ChannelOrder> findByOrderId(Long orderId);

    List<ChannelOrder> findAllByOrderByReceivedAtDescIdDesc(Pageable page);

    long countByStatus(ChannelOrder.Status status);

    long countByChannelAndStatus(Channel channel, ChannelOrder.Status status);
}
