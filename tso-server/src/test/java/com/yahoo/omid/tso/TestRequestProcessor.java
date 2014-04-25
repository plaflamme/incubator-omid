package com.yahoo.omid.tso;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import com.google.common.collect.Lists;

import org.jboss.netty.channel.Channel;

import com.codahale.metrics.MetricRegistry;

import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

public class TestRequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(TestRequestProcessor.class);

    @Test(timeout=10000)
    public void testTimestamp() throws Exception {
        PersistenceProcessor persist = mock(PersistenceProcessor.class);
        RequestProcessor proc = new RequestProcessorImpl(new MetricRegistry(),
                                                         new TimestampOracle(), persist, 1000);

        proc.timestampRequest(null);
        ArgumentCaptor<Long> firstTScapture = ArgumentCaptor.forClass(Long.class);
        verify(persist, timeout(100).times(1)).persistTimestamp(
                firstTScapture.capture(), any(Channel.class));

        long firstTS = firstTScapture.getValue();
        // verify that timestamps increase monotonically
        for (int i = 0; i < 100; i++) {
            proc.timestampRequest(null);
            verify(persist, timeout(100).times(1)).persistTimestamp(eq(firstTS++), any(Channel.class));
        }
    }

    @Test(timeout=10000)
    public void testCommit() throws Exception {
        List<Long> rows = Lists.newArrayList(1L, 20L, 203L);

        PersistenceProcessor persist = mock(PersistenceProcessor.class);
        RequestProcessor proc = new RequestProcessorImpl(new MetricRegistry(),
                                                         new TimestampOracle(), persist, 1000);
        proc.timestampRequest(null);
        ArgumentCaptor<Long> TScapture = ArgumentCaptor.forClass(Long.class);
        verify(persist, timeout(100).times(1)).persistTimestamp(
                TScapture.capture(), any(Channel.class));
        long firstTS = TScapture.getValue();

        proc.commitRequest(firstTS - 1, rows, null);
        verify(persist, timeout(100).times(1)).persistAbort(eq(firstTS - 1), any(Channel.class));

        proc.commitRequest(firstTS, rows, null);
        ArgumentCaptor<Long> commitTScapture = ArgumentCaptor.forClass(Long.class);

        verify(persist, timeout(100).times(1)).persistCommit(eq(firstTS), commitTScapture.capture(),
                                                             any(Channel.class));
        assertTrue("Commit TS must be greater than start TS", commitTScapture.getValue() > firstTS);

        // test conflict
        proc.timestampRequest(null);
        TScapture = ArgumentCaptor.forClass(Long.class);
        verify(persist, timeout(100).times(2)).persistTimestamp(
                TScapture.capture(), any(Channel.class));
        long secondTS = TScapture.getValue();

        proc.timestampRequest(null);
        TScapture = ArgumentCaptor.forClass(Long.class);
        verify(persist, timeout(100).times(3)).persistTimestamp(
                TScapture.capture(), any(Channel.class));
        long thirdTS = TScapture.getValue();

        proc.commitRequest(thirdTS, rows, null);
        verify(persist, timeout(100).times(1)).persistCommit(eq(thirdTS), anyLong(),
                                                             any(Channel.class));
        proc.commitRequest(secondTS, rows, null);
        verify(persist, timeout(100).times(1)).persistAbort(eq(secondTS),
                                                            any(Channel.class));
    }
}
