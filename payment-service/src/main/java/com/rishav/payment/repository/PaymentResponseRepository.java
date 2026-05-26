package com.rishav.payment.repository;

import com.rishav.payment.entity.PaymentResponse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentResponseRepository extends JpaRepository<PaymentResponse, Long> {
}
