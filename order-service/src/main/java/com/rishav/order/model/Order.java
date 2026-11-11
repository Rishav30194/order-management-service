package com.rishav.order.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rishav.order.constant.OrderStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long id;

    @NotNull @Positive
    private Long userId;

    @NotNull @Positive
    private Long productId;

    @NotNull @Positive
    private Integer quantity;
    private Long totalPrice;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private LocalDateTime createdAt;

    // Comes from the Idempotency-Key header, never the request body. Unique, so a concurrent repeat fails to save.
    @JsonIgnore
    @Column(unique = true, length = IDEMPOTENCY_KEY_MAX_LENGTH)
    private String idempotencyKey;

    // A save() of a copy loaded before another write fails instead of overwriting that write. The repository's
    // update queries bypass this check, so each one increments the version itself.
    @Version
    @JsonIgnore
    private Long version;

    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 255;
}
