package com.rishav.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// A payment event saved in the payment's own transaction, so it exists only if the payment committed.
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The Kafka record key, so every event for one order goes to the same partition, in order.
    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false, length = 1000)
    private String payload;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
