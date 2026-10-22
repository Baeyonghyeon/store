package com.example1.bddllm.common;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class Transactions {
    private final TransactionTemplate transaction;

    public Transactions(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
    }

    public <T> T required(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }
}
