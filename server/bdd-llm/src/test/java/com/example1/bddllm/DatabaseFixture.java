package com.example1.bddllm;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Transactions;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DatabaseFixture {
    @PersistenceContext private EntityManager entityManager;
    private final Transactions transactions;

    public DatabaseFixture(Transactions transactions) {
        this.transactions = transactions;
    }

    public int update(String sql, Object... parameters) {
        return transactions.required(
                () -> {
                    Query query = entityManager.createNativeQuery(sql);
                    bind(query, parameters);
                    int count = query.executeUpdate();
                    entityManager.clear();
                    return count;
                });
    }

    public List<ObjectNode> rows(String sql, Object... parameters) {
        return transactions.required(
                () -> {
                    NativeQuery<Tuple> query =
                            entityManager.unwrap(Session.class).createNativeQuery(sql, Tuple.class);
                    bind(query, parameters);
                    List<Tuple> tuples = query.getResultList();
                    List<ObjectNode> rows = new ArrayList<>();
                    for (Tuple tuple : tuples) {
                        ObjectNode row = Json.obj();
                        for (TupleElement<?> column : tuple.getElements()) {
                            Object value = tuple.get(column);
                            String name = column.getAlias();
                            if (value == null) row.putNull(name);
                            else if (value instanceof Instant
                                    || value instanceof OffsetDateTime
                                    || value instanceof UUID) row.put(name, value.toString());
                            else if (value instanceof String text
                                    && (text.startsWith("{") || text.startsWith("[")))
                                row.set(name, Json.parse(text));
                            else row.set(name, Json.value(value));
                        }
                        rows.add(row);
                    }
                    return rows;
                });
    }

    public ObjectNode one(String sql, Object... parameters) {
        List<ObjectNode> rows = rows(sql, parameters);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void bind(Query query, Object[] values) {
        for (int parameterIndex = 0; parameterIndex < values.length; parameterIndex++) query.setParameter(parameterIndex + 1, values[parameterIndex]);
    }
}
