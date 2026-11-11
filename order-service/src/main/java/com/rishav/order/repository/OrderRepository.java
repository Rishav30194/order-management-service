package com.rishav.order.repository;

import com.rishav.order.constant.OrderStatus;
import com.rishav.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // Conditional so a status the Kafka consumer already wrote is never overwritten.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = :to where o.id = :id and o.status = :from")
    int updateStatusIfCurrent(Long id, OrderStatus from, OrderStatus to);

    @Modifying
    @Query("update Order o set o.status = :to where o.status in :from and o.createdAt < :cutoff")
    int updateStatusIfCreatedBefore(Collection<OrderStatus> from, OrderStatus to, LocalDateTime cutoff);
}
