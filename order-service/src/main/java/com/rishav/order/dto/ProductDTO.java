package com.rishav.order.dto;

public record ProductDTO(Long id, String name, String description, Long price, Integer stock, String category) {
}
