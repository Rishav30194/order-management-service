package com.rishav.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.rishav.payment.dto.PaymentRequest;
import com.rishav.payment.dto.PaymentResponseDto;
import com.rishav.payment.entity.PaymentResponse;

public interface PaymentService {

    PaymentResponseDto handlePayment(PaymentRequest request) throws JsonProcessingException;

}
