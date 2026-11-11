package com.rishav.product.service.impl;

import com.rishav.product.exception.ProductNotFoundException;
import com.rishav.product.model.Product;
import com.rishav.product.repository.ProductRepository;
import com.rishav.product.service.ProductService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@AllArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    @Transactional
    @Override
    public Product createProduct(Product product) {
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    @Override
    public Product getProductById(Long id) {
        return findProduct(id);
    }

    @Transactional(readOnly = true)
    @Override
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @Transactional
    @Override
    public Product updateProduct(Long id, Product product) {
        Product existing = findProduct(id);
        existing.setName(product.getName());
        existing.setDescription(product.getDescription());
        existing.setPrice(product.getPrice());
        existing.setStock(product.getStock());
        existing.setCategory(product.getCategory());
        return productRepository.save(existing);
    }

    @Transactional
    @Override
    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
    }

    // Not getProductById: a call through 'this' bypasses the transactional proxy (Sonar java:S6809).
    private Product findProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }
}
