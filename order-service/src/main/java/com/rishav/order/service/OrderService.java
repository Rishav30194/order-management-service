package com.rishav.order.service;


import com.rishav.order.model.Order;

import java.util.List;

public interface OrderService {

    Order placeOrder(Order order);
    Order getOrderById(Long id);
    List<Order> getAllOrders();
}
