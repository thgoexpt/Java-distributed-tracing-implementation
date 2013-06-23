package com.github.kristofa.brave;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.Span;
import com.twitter.zipkin.gen.zipkinCoreConstants;

public class ClientTracerImplTest {

    private final static long CURRENT_TIME_MICROSECONDS = System.currentTimeMillis() * 1000;
    private final static String REQUEST_NAME = "requestName";
    private final static String ANNOTATION_NAME = "annotationName";
    private final static int DURATION = 11;

    private ServerAndClientSpanState mockState;
    private Random mockRandom;
    private SpanCollector mockCollector;
    private ClientTracerImpl clientTracer;
    private Span mockSpan;
    private Endpoint endPoint;
    private TraceFilter mockTraceFilter;

    @Before
    public void setup() {
        mockState = mock(ServerAndClientSpanState.class);
        endPoint = new Endpoint();
        mockTraceFilter = mock(TraceFilter.class);
        when(mockState.shouldTrace()).thenReturn(true);
        when(mockState.getEndPoint()).thenReturn(endPoint);
        when(mockTraceFilter.shouldTrace(REQUEST_NAME)).thenReturn(true);

        mockRandom = mock(Random.class);
        mockCollector = mock(SpanCollector.class);
        mockSpan = mock(Span.class);

        clientTracer = new ClientTracerImpl(mockState, mockRandom, mockCollector, mockTraceFilter) {

            @Override
            long currentTimeMicroseconds() {
                return CURRENT_TIME_MICROSECONDS;
            }
        };

    }

    @Test(expected = NullPointerException.class)
    public void testConstructorNullState() {
        new ClientTracerImpl(null, mockRandom, mockCollector, mockTraceFilter);
    }

    @Test(expected = NullPointerException.class)
    public void testConstructorNullRandom() {
        new ClientTracerImpl(mockState, null, mockCollector, mockTraceFilter);
    }

    @Test(expected = NullPointerException.class)
    public void testConstructorNullCollector() {
        new ClientTracerImpl(mockState, mockRandom, null, mockTraceFilter);
    }

    @Test(expected = NullPointerException.class)
    public void testConstructorNullTraceFilter() {
        new ClientTracerImpl(mockState, mockRandom, mockCollector, null);
    }

    @Test
    public void testSetClientSentShouldTraceFalse() {
        when(mockState.shouldTrace()).thenReturn(false);
        clientTracer.setClientSent();
        verify(mockState).shouldTrace();
        verifyNoMoreInteractions(mockState, mockRandom, mockCollector, mockTraceFilter);
    }

    @Test
    public void testSetClientSent() {

        when(mockState.getCurrentClientSpan()).thenReturn(mockSpan);
        clientTracer.setClientSent();

        final Annotation expectedAnnotation = new Annotation();
        expectedAnnotation.setHost(endPoint);
        expectedAnnotation.setValue(zipkinCoreConstants.CLIENT_SEND);
        expectedAnnotation.setTimestamp(CURRENT_TIME_MICROSECONDS);
        verify(mockState).shouldTrace();
        verify(mockState).getCurrentClientSpan();
        verify(mockState).getEndPoint();
        verify(mockSpan).addToAnnotations(expectedAnnotation);
        verifyNoMoreInteractions(mockState, mockRandom, mockCollector, mockTraceFilter);
    }

    @Test
    public void testSetClientReceivedShouldTraceFalse() {
        when(mockState.shouldTrace()).thenReturn(false);
        clientTracer.setClientReceived();
        verify(mockState).shouldTrace();
        verifyNoMoreInteractions(mockState, mockRandom, mockCollector, mockTraceFilter);
    }

    @Test
    public void testSetClientReceived() {

        when(mockState.getCurrentClientSpan()).thenReturn(mockSpan);
        clientTracer.setClientReceived();

        final Annotation expectedAnnotation = new Annotation();
        expectedAnnotation.setHost(endPoint);
        expectedAnnotation.setValue(zipkinCoreConstants.CLIENT_RECV);
        expectedAnnotation.setTimestamp(CURRENT_TIME_MICROSECONDS);
        verify(mockState).shouldTrace();
        verify(mockState).getCurrentClientSpan();
        verify(mockState).getEndPoint();
        verify(mockSpan).addToAnnotations(expectedAnnotation);
        verify(mockState).setCurrentClientSpan(null);
        verify(mockCollector).collect(mockSpan);
        verifyNoMoreInteractions(mockState, mockRandom, mockCollector, mockTraceFilter);
    }

    @Test
    public void testStartNewSpanShouldTraceFalse() {
        when(mockState.shouldTrace()).thenReturn(false);
        assertNull(clientTracer.startNewSpan(REQUEST_NAME));
        verify(mockState).shouldTrace();
        verifyNoMoreInteractions(mockState, mockRandom, mockCollector, mockTraceFilter);
    }

    @Test
    public void testStartNewSpanNotPartOfExistingSpan() {

        when(mockState.getCurrentServerSpan()).thenReturn(null);
        when(mockRandom.nextLong()).thenReturn(1l).thenReturn(2l);

        clientTracer.startNewSpan(REQUEST_NAME);

        final Span expectedSpan = new Span();
        expectedSpan.setTrace_id(2);
        expectedSpan.setId(1);
        expectedSpan.setName(REQUEST_NAME);

        verify(mockState).shouldTrace();
        verify(mockTraceFilter).shouldTrace(REQUEST_NAME);
        verify(mockRandom, times(2)).nextLong();
        verify(mockState).getCurrentServerSpan();
        verify(mockState).setCurrentClientSpan(expectedSpan);

        verifyNoMoreInteractions(mockState, mockRandom, mockCollector, mockTraceFilter);
    }

    @Test
    public void testSubmitAnnotationStringLongShouldTraceFalse() {
        when(mockState.shouldTrace()).thenReturn(false);
        clientTracer.submitAnnotation(ANNOTATION_NAME, DURATION);
        verify(mockState).shouldTrace();
        verifyNoMoreInteractions(mockState, mockRandom, mockCollector);
    }

    @Test
    public void testSubmitAnnotationStringLong() {

        when(mockState.getCurrentClientSpan()).thenReturn(mockSpan);
        clientTracer.submitAnnotation(ANNOTATION_NAME, DURATION);
        verify(mockState).shouldTrace();
        verify(mockState).getCurrentClientSpan();
        verify(mockState).getEndPoint();

        final Annotation expectedAnnotation = new Annotation();
        expectedAnnotation.setHost(endPoint);
        expectedAnnotation.setValue(ANNOTATION_NAME);
        expectedAnnotation.setDuration(DURATION * 1000); // conversion to microseconds.
        expectedAnnotation.setTimestamp(CURRENT_TIME_MICROSECONDS);
        verify(mockSpan).addToAnnotations(expectedAnnotation);

        verifyNoMoreInteractions(mockState, mockRandom, mockCollector);
    }

    @Test
    public void testSubmitAnnotationStringShouldTraceFalse() {
        when(mockState.shouldTrace()).thenReturn(false);
        clientTracer.submitAnnotation(ANNOTATION_NAME);
        verify(mockState).shouldTrace();
        verifyNoMoreInteractions(mockState, mockRandom, mockCollector);
    }

    @Test
    public void testSubmitAnnotationString() {
        when(mockState.getCurrentClientSpan()).thenReturn(mockSpan);
        clientTracer.submitAnnotation(ANNOTATION_NAME);
        verify(mockState).shouldTrace();
        verify(mockState).getCurrentClientSpan();
        verify(mockState).getEndPoint();

        final Annotation expectedAnnotation = new Annotation();
        expectedAnnotation.setHost(endPoint);
        expectedAnnotation.setValue(ANNOTATION_NAME);
        expectedAnnotation.setTimestamp(CURRENT_TIME_MICROSECONDS);
        verify(mockSpan).addToAnnotations(expectedAnnotation);

        verifyNoMoreInteractions(mockState, mockRandom, mockCollector);
    }

    @Test
    public void testTraceFilterFalse() {
        when(mockTraceFilter.shouldTrace(REQUEST_NAME)).thenReturn(false);

        assertNull(clientTracer.startNewSpan(REQUEST_NAME));

        verify(mockState).shouldTrace();
        verify(mockTraceFilter).shouldTrace(REQUEST_NAME);
        verify(mockState).setTracing(false);

        verifyNoMoreInteractions(mockState, mockTraceFilter, mockRandom, mockCollector);

    }

}
