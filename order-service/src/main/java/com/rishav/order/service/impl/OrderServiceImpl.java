package com.rishav.order.service.impl;

import com.rishav.order.dto.ProductDTO;
import com.rishav.order.model.Order;
import com.rishav.order.repository.OrderRepository;
import com.rishav.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final RestTemplate restTemplate;

    @Override
    public Order placeOrder(Order order) {
        // call user-service to validate user
        String userUrl = "http://USER-SERVICE/api/users/"+order.getUserId();
        var userResponse = restTemplate.getForObject(userUrl, Object.class);
        if(userResponse == null){
            throw new RuntimeException("User not found with ID: " + order.getUserId());
        }
        // Call product-service to validate product
        String productUrl = "http://PRODUCT-SERVICE/api/products/" + order.getProductId();
        ProductDTO product = restTemplate.getForObject(productUrl, ProductDTO.class);
        if(product == null){
            throw new RuntimeException("Product not found with ID: " + order.getProductId());
        }
        order.setTotalPrice(product.getPrice() * order.getQuantity());
        order.setStatus("PLACED");
        order.setCreatedAt(LocalDateTime.now());
        return orderRepository.save(order);
    }

    @Override
    public Order getOrderById(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new RuntimeException("Order not found with ID: "+ id));
    }

    @Override
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }
}
