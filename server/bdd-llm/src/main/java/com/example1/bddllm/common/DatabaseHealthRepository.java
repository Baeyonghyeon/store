package com.example1.bddllm.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

@Repository
public class DatabaseHealthRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public void check() {
        entityManager.createNativeQuery("SELECT 1", Integer.class).getSingleResult();
    }
}
