package com.rishav.payment.repository;

import com.rishav.payment.entity.PaymentResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentResponseRepository extends JpaRepository<PaymentResponse, Long> {

    Optional<PaymentResponse> findByOrderId(String orderId);
}
