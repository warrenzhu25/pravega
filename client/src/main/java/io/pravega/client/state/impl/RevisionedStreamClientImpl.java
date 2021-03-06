/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.client.state.impl;

import io.pravega.client.batch.SegmentInfo;
import io.pravega.client.segment.impl.EndOfSegmentException;
import io.pravega.client.segment.impl.Segment;
import io.pravega.client.segment.impl.SegmentInputStream;
import io.pravega.client.segment.impl.SegmentMetadataClient;
import io.pravega.client.segment.impl.SegmentOutputStream;
import io.pravega.client.segment.impl.SegmentSealedException;
import io.pravega.client.segment.impl.SegmentTruncatedException;
import io.pravega.client.state.Revision;
import io.pravega.client.state.RevisionedStreamClient;
import io.pravega.client.stream.Serializer;
import io.pravega.client.stream.TruncatedDataException;
import io.pravega.client.stream.impl.Controller;
import io.pravega.client.stream.impl.PendingEvent;
import io.pravega.common.concurrent.Futures;
import io.pravega.shared.protocol.netty.WireCommands;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.GuardedBy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static io.pravega.client.segment.impl.SegmentAttribute.NULL_VALUE;
import static io.pravega.client.segment.impl.SegmentAttribute.RevisionStreamClientMark;

@RequiredArgsConstructor
@Slf4j
public class RevisionedStreamClientImpl<T> implements RevisionedStreamClient<T> {

    private final Segment segment;
    @GuardedBy("lock")
    private final SegmentInputStream in;
    @GuardedBy("lock")
    private final SegmentOutputStream out;
    @GuardedBy("lock")
    private final SegmentMetadataClient meta;
    private final Serializer<T> serializer;
    private final Controller controller;
    private final String delegationToken;

    private final Object lock = new Object();

    @Override
    public Revision writeConditionally(Revision latestRevision, T value) {
        CompletableFuture<Boolean> wasWritten = new CompletableFuture<>();
        long offset = latestRevision.asImpl().getOffsetInSegment();
        ByteBuffer serialized = serializer.serialize(value);
        int size = serialized.remaining();
        try {
            PendingEvent event = new PendingEvent(null, serialized, wasWritten, offset);
            synchronized (lock) {
                out.write(event);
                out.flush();
            }
        } catch (SegmentSealedException e) {
            throw new CorruptedStateException("Unexpected end of segment ", e);
        }
        if (Futures.getAndHandleExceptions(wasWritten, RuntimeException::new)) {
            long newOffset = getNewOffset(offset, size);
            log.trace("Wrote from {} to {}", offset, newOffset);
            return new RevisionImpl(segment, newOffset, 0);
        } else {
            log.trace("Write failed at offset {}", offset);
            return null;
        }
    }

    private static final long getNewOffset(long initial, int size) {
        return initial + size + WireCommands.TYPE_PLUS_LENGTH_SIZE;
    }

    @Override
    public void writeUnconditionally(T value) {
        CompletableFuture<Boolean> wasWritten = new CompletableFuture<>();
        ByteBuffer serialized = serializer.serialize(value);
        try {
            PendingEvent event = new PendingEvent(null, serialized, wasWritten);
            log.trace("Unconditionally writing: {}", value);
            synchronized (lock) {
                out.write(event);
                out.flush();
            }
        } catch (SegmentSealedException e) {
            throw new CorruptedStateException("Unexpected end of segment ", e);
        }
        Futures.getAndHandleExceptions(wasWritten, RuntimeException::new);
    }

    @Override
    public Iterator<Entry<Revision, T>> readFrom(Revision start) {
        synchronized (lock) {
            long startOffset = start.asImpl().getOffsetInSegment();
            SegmentInfo segmentInfo = meta.getSegmentInfo(delegationToken);
            long endOffset = segmentInfo.getWriteOffset();
            if (startOffset < segmentInfo.getStartingOffset()) {
                throw new TruncatedDataException("Data at the supplied revision has been truncated.");
            }
            log.trace("Creating iterator from {} until {}", startOffset, endOffset);
            return new StreamIterator(startOffset, endOffset);
        }
    }
    
    @Override
    public Revision fetchLatestRevision() {
        synchronized (lock) {
            long streamLength = meta.fetchCurrentSegmentLength(delegationToken);
            return new RevisionImpl(segment, streamLength, 0);
        }
    }

    private class StreamIterator implements Iterator<Entry<Revision, T>> {
        private final AtomicLong offset;
        private final long endOffset;

        StreamIterator(long startingOffset, long endOffset) {
            this.offset = new AtomicLong(startingOffset);
            this.endOffset = endOffset;
        }
        
        @Override
        public boolean hasNext() {
            return offset.get() < endOffset;
        }

        @Override
        public Entry<Revision, T> next() {
            Revision revision;
            ByteBuffer data;
            synchronized (lock) {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                log.trace("Iterater reading entry at", offset.get());
                in.setOffset(offset.get());
                try {
                    data = in.read();
                } catch (EndOfSegmentException e) {
                    throw new IllegalStateException(
                        "SegmentInputStream: " + in + " shrunk from its original length: " + endOffset);
            } catch (SegmentTruncatedException e) {
                throw new TruncatedDataException(e);
            }
                offset.set(in.getOffset());
                revision = new RevisionImpl(segment, offset.get(), 0);
            }
            return new AbstractMap.SimpleImmutableEntry<>(revision, serializer.deserialize(data));
        }
    }

    @Override
    public Revision getMark() {
        synchronized (lock) {
            long value = meta.fetchProperty(RevisionStreamClientMark);
            return value == NULL_VALUE ? null : new RevisionImpl(segment, value, 0);
        }
    }

    @Override
    public boolean compareAndSetMark(Revision expected, Revision newLocation) {
        long expectedValue = expected == null ? NULL_VALUE : expected.asImpl().getOffsetInSegment();
        long newValue = newLocation == null ? NULL_VALUE : newLocation.asImpl().getOffsetInSegment();
        synchronized (lock) {
            return meta.compareAndSetAttribute(RevisionStreamClientMark, expectedValue, newValue, delegationToken);
        }
    }

    @Override
    public Revision fetchOldestRevision() {
        long startingOffset = meta.getSegmentInfo(delegationToken).getStartingOffset();
        return new RevisionImpl(segment, startingOffset, 0);
    }

    @Override
    public void truncateToRevision(Revision newStart) {
        meta.truncateSegment(newStart.asImpl().getSegment(), newStart.asImpl().getOffsetInSegment(), delegationToken);
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                out.close();
            } catch (SegmentSealedException e) {
                log.warn("Error closing segment writer {}", out);
            }
            meta.close();
            in.close();
        }
    }
}
