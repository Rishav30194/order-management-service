package com.rishav.order.service;


import com.rishav.order.model.Order;

import java.util.List;

public interface OrderService {

    // A request with the key of an earlier order gets that order back instead of placing a new one.
    Order placeOrder(Order order, String idempotencyKey);

    default Order placeOrder(Order order) {
        return placeOrder(order, null);
    }

    Order getOrderById(Long id);
    List<Order> getAllOrders();
    int failStalePayments();
}
