/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.store.stream;

import io.pravega.client.stream.ScalingPolicy;
import io.pravega.controller.store.task.TxnResource;
import io.pravega.test.common.TestingServerStarter;
import io.pravega.controller.store.stream.tables.State;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.test.common.AssertExtensions;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.Test;

import java.io.IOException;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Zookeeper based stream metadata store tests.
 */
public class ZKStreamMetadataStoreTest extends StreamMetadataStoreTest {

    private TestingServer zkServer;
    private CuratorFramework cli;

    @Override
    public void setupTaskStore() throws Exception {
        zkServer = new TestingServerStarter().start();
        zkServer.start();
        int sessionTimeout = 8000;
        int connectionTimeout = 5000;
        cli = CuratorFrameworkFactory.newClient(zkServer.getConnectString(), sessionTimeout, connectionTimeout, new RetryOneTime(2000));
        cli.start();
        store = new ZKStreamMetadataStore(cli, 1, executor);
    }

    @Override
    public void cleanupTaskStore() throws IOException {
        cli.close();
        zkServer.close();
    }

    @Test
    public void listStreamsWithInactiveStream() throws Exception {
        // list stream in scope
        store.createScope("Scope").get();
        store.createStream("Scope", stream1, configuration1, System.currentTimeMillis(), null, executor).get();
        store.setState("Scope", stream1, State.ACTIVE, null, executor).get();

        store.createStream("Scope", stream2, configuration2, System.currentTimeMillis(), null, executor).get();

        List<StreamConfiguration> streamInScope = store.listStreamsInScope("Scope").get();
        assertEquals("List streams in scope", 2, streamInScope.size());
        assertEquals("List streams in scope", stream1, streamInScope.get(0).getStreamName());
        assertEquals("List streams in scope", stream2, streamInScope.get(1).getStreamName());
    }

    @Test
    public void testInvalidOperation() throws Exception {
        // Test operation when stream is not in active state
        store.createScope(scope).get();
        store.createStream(scope, stream1, configuration1, System.currentTimeMillis(), null, executor).get();
        store.setState(scope, stream1, State.CREATING, null, executor).get();

        AssertExtensions.assertThrows("Should throw IllegalStateException",
                store.getActiveSegments(scope, stream1, null, executor),
                (Throwable t) -> t instanceof StoreException.IllegalStateException);
    }

    @Test(timeout = 5000)
    public void testError() throws Exception {
        String host = "host";
        TxnResource txn = new TxnResource("SCOPE", "STREAM1", UUID.randomUUID());
        Predicate<Throwable> checker = (Throwable ex) -> ex instanceof StoreException.UnknownException;

        cli.close();
        testFailure(host, txn, checker);
    }

    @Test
    public void testConnectionLoss() throws Exception {
        String host = "host";
        TxnResource txn = new TxnResource("SCOPE", "STREAM1", UUID.randomUUID());
        Predicate<Throwable> checker = (Throwable ex) -> ex instanceof StoreException.StoreConnectionException;

        zkServer.close();
        AssertExtensions.assertThrows("Add txn to index fails", store.addTxnToIndex(host, txn, 0), checker);
    }

    private void testFailure(String host, TxnResource txn, Predicate<Throwable> checker) {
        AssertExtensions.assertThrows("Add txn to index fails", store.addTxnToIndex(host, txn, 0), checker);
        AssertExtensions.assertThrows("Remove txn fails", store.removeTxnFromIndex(host, txn, true), checker);
        AssertExtensions.assertThrows("Remove host fails", store.removeHostFromIndex(host), checker);
        AssertExtensions.assertThrows("Get txn version fails", store.getTxnVersionFromIndex(host, txn), checker);
        AssertExtensions.assertThrows("Get random txn fails", store.getRandomTxnFromIndex(host), checker);
        AssertExtensions.assertThrows("List hosts fails", store.listHostsOwningTxn(), checker);
    }

    @Test
    public void testScaleMetadata() throws Exception {
        String scope = "testScopeScale";
        String stream = "testStreamScale";
        ScalingPolicy policy = ScalingPolicy.fixed(3);
        StreamConfiguration configuration = StreamConfiguration.builder().scope(scope).streamName(stream).scalingPolicy(policy).build();
        SimpleEntry<Double, Double> segment1 = new SimpleEntry<>(0.0, 0.5);
        SimpleEntry<Double, Double> segment2 = new SimpleEntry<>(0.5, 1.0);
        List<SimpleEntry<Double, Double>> newRanges = Arrays.asList(segment1, segment2);

        store.createScope(scope).get();
        store.createStream(scope, stream, configuration, System.currentTimeMillis(), null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        List<ScaleMetadata> scaleIncidents = store.getScaleMetadata(scope, stream, null, executor).get();
        assertTrue(scaleIncidents.size() == 1);
        assertTrue(scaleIncidents.get(0).getSegments().size() == 3);
        // scale
        scale(scope, stream, scaleIncidents.get(0).getSegments(), newRanges);
        scaleIncidents = store.getScaleMetadata(scope, stream, null, executor).get();
        assertTrue(scaleIncidents.size() == 2);
        assertTrue(scaleIncidents.get(0).getSegments().size() == 2);
        assertTrue(scaleIncidents.get(1).getSegments().size() == 3);

        // scale again
        scale(scope, stream, scaleIncidents.get(0).getSegments(), newRanges);
        scaleIncidents = store.getScaleMetadata(scope, stream, null, executor).get();
        assertTrue(scaleIncidents.size() == 3);
        assertTrue(scaleIncidents.get(0).getSegments().size() == 2);
        assertTrue(scaleIncidents.get(1).getSegments().size() == 2);

        // scale again
        scale(scope, stream, scaleIncidents.get(0).getSegments(), newRanges);
        scaleIncidents = store.getScaleMetadata(scope, stream, null, executor).get();
        assertTrue(scaleIncidents.size() == 4);
        assertTrue(scaleIncidents.get(0).getSegments().size() == 2);
        assertTrue(scaleIncidents.get(1).getSegments().size() == 2);
    }


    @Test
    public void testSplitsMerges() throws Exception {
        String scope = "testScopeScale";
        String stream = "testStreamScale";
        ScalingPolicy policy = ScalingPolicy.fixed(2);
        StreamConfiguration configuration = StreamConfiguration.builder().
                scope(scope).streamName(stream).scalingPolicy(policy).build();

        store.createScope(scope).get();
        store.createStream(scope, stream, configuration, System.currentTimeMillis(), null, executor).get();
        store.setState(scope, stream, State.ACTIVE, null, executor).get();

        // Case: Initial state, splits = 0, merges = 0
        // time t0, total segments 2, S0 {0.0 - 0.5} S1 {0.5 - 1.0}
        List<ScaleMetadata> scaleRecords = store.getScaleMetadata(scope, stream, null, executor).get();
        assertTrue(scaleRecords.size() == 1);
        SimpleEntry<Long, Long> simpleEntrySplitsMerges = store.findNumSplitsMerges(scope, stream, executor).get();
        assertEquals("Number of splits ", new Long(0), simpleEntrySplitsMerges.getKey());
        assertEquals("Number of merges", new Long(0), simpleEntrySplitsMerges.getValue());

        // Case: Only splits, S0 split into S2, S3, S4 and S1 split into S5, S6,
        //  total splits = 2, total merges = 3
        // time t1, total segments 5, S2 {0.0, 0.2}, S3 {0.2, 0.4}, S4 {0.4, 0.5}, S5 {0.5, 0.7}, S6 {0.7, 1.0}
        SimpleEntry<Double, Double> segment2 = new SimpleEntry<>(0.0, 0.2);
        SimpleEntry<Double, Double> segment3 = new SimpleEntry<>(0.2, 0.4);
        SimpleEntry<Double, Double> segment4 = new SimpleEntry<>(0.4, 0.5);
        SimpleEntry<Double, Double> segment5 = new SimpleEntry<>(0.5, 0.7);
        SimpleEntry<Double, Double> segment6 = new SimpleEntry<>(0.7, 1.0);
        List<SimpleEntry<Double, Double>> newRanges1 = Arrays.asList(segment2, segment3, segment4, segment5, segment6);
        scale(scope, stream, scaleRecords.get(0).getSegments(), newRanges1);
        scaleRecords = store.getScaleMetadata(scope, stream, null, executor).get();
        assertTrue(scaleRecords.size() == 2);
        SimpleEntry<Long, Long> simpleEntrySplitsMerges1 = store.findNumSplitsMerges(scope, stream, executor).get();
        assertEquals("Number of splits ", new Long(2), simpleEntrySplitsMerges1.getKey());
        assertEquals("Number of merges", new Long(0), simpleEntrySplitsMerges1.getValue());

        // Case: Splits and merges both, S2 and S3 merged to S7,  S4 and S5 merged to S8,  S6 split to S9 and S10
        // total splits = 3, total merges = 2
        // time t2, total segments 4, S7 {0.0, 0.4}, S8 {0.4, 0.7}, S9 {0.7, 0.8}, S10 {0.8, 1.0}
        SimpleEntry<Double, Double> segment7 = new SimpleEntry<>(0.0, 0.4);
        SimpleEntry<Double, Double> segment8 = new SimpleEntry<>(0.4, 0.7);
        SimpleEntry<Double, Double> segment9 = new SimpleEntry<>(0.7, 0.8);
        SimpleEntry<Double, Double> segment10 = new SimpleEntry<>(0.8, 1.0);
        List<SimpleEntry<Double, Double>> newRanges2 = Arrays.asList(segment7, segment8, segment9, segment10);
        scale(scope, stream, scaleRecords.get(0).getSegments(), newRanges2);
        scaleRecords = store.getScaleMetadata(scope, stream, null, executor).get();
        SimpleEntry<Long, Long> simpleEntrySplitsMerges2 = store.findNumSplitsMerges(scope, stream, executor).get();
        assertEquals("Number of splits ", new Long(3), simpleEntrySplitsMerges2.getKey());
        assertEquals("Number of merges", new Long(2), simpleEntrySplitsMerges2.getValue());

        // Case: Only merges , S7 and S8 merged to S11,  S9 and S10 merged to S12
        // total splits = 3, total merges = 4
        // time t3, total segments 2, S11 {0.0, 0.7}, S12 {0.7, 1.0}
        SimpleEntry<Double, Double> segment11 = new SimpleEntry<>(0.0, 0.7);
        SimpleEntry<Double, Double> segment12 = new SimpleEntry<>(0.7, 1.0);
        List<SimpleEntry<Double, Double>> newRanges3 = Arrays.asList(segment11, segment12);
        scale(scope, stream, scaleRecords.get(0).getSegments(), newRanges3);
        SimpleEntry<Long, Long> simpleEntrySplitsMerges3 = store.findNumSplitsMerges(scope, stream, executor).get();
        assertEquals("Number of splits ", new Long(3), simpleEntrySplitsMerges3.getKey());
        assertEquals("Number of merges", new Long(4), simpleEntrySplitsMerges3.getValue());
    }

    private void scale(String scope, String stream, List<Segment> segments,
                       List<SimpleEntry<Double, Double>> newRanges) {

        long scaleTimestamp = System.currentTimeMillis();
        List<Integer> existingSegments = segments.stream().map(Segment::getNumber).collect(Collectors.toList());
        StartScaleResponse response = store.startScale(scope, stream, existingSegments, newRanges,
                scaleTimestamp, false, null, executor).join();
        List<Segment> segmentsCreated = response.getSegmentsCreated();
        store.setState(scope, stream, State.SCALING, null, executor).join();
        store.scaleNewSegmentsCreated(scope, stream, existingSegments, segmentsCreated, response.getActiveEpoch(),
                scaleTimestamp, null, executor).join();
        store.scaleSegmentsSealed(scope, stream, existingSegments.stream().collect(Collectors.toMap(x -> x, x -> 0L)), segmentsCreated, response.getActiveEpoch(),
                scaleTimestamp, null, executor).join();
    }
}
