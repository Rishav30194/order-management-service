package com.rishav.order.dto;

import lombok.Data;

@Data
public class ProductDTO {
    private Long id;
    private String name;
    private String description;
    private Long price;
    private Integer stock;
    private String category;
}
