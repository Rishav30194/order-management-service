package com.rishav.payment.entity;


import com.rishav.payment.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "payment_responses")
public class PaymentResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String transactionId;
    private String orderId;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status; // SUCCESS, FAILED
    private String message;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
