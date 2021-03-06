package com.netflix.hystrix.contrib.rxnetty.metricsstream;

import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.reactivex.netty.protocol.http.server.HttpServerRequest;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import io.reactivex.netty.protocol.http.server.RequestHandler;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action1;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;
import rx.subscriptions.MultipleAssignmentSubscription;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import static com.netflix.hystrix.contrib.rxnetty.metricsstream.JsonMappers.*;

/**
 * Streams Hystrix metrics in Server Sent Event (SSE) format. RxNetty application handlers shall
 * be wrapped by this handler. It transparently intercepts HTTP requests at a configurable path
 * (default "/hystrix.stream"), and sends unbounded SSE streams back to the client. All other requests
 * are transparently forwarded to the application handlers.
 * <p/>
 * For RxNetty client tapping into SSE stream: remember to use unpooled HTTP connections. If not, the pooled HTTP
 * connection will not be closed on unsubscribe event and the event stream will continue to flow towards the client
 * (unless the client is shutdown).
 *
 * @author Tomasz Bak
 */
public class HystrixMetricsStreamHandler<I, O> implements RequestHandler<I, O> {

    public static final String DEFAULT_HYSTRIX_PREFIX = "/hystrix.stream";

    public static final int DEFAULT_INTERVAL = 2000;

    private static final byte[] HEADER = "data: ".getBytes(Charset.defaultCharset());
    private static final byte[] FOOTER = {10, 10};
    private static final int EXTRA_SPACE = HEADER.length + FOOTER.length;

    private final String hystrixPrefix;
    private final long interval;
    private final RequestHandler<I, O> appHandler;

    public HystrixMetricsStreamHandler(RequestHandler<I, O> appHandler) {
        this(DEFAULT_HYSTRIX_PREFIX, DEFAULT_INTERVAL, appHandler);
    }

    HystrixMetricsStreamHandler(String hystrixPrefix, long interval, RequestHandler<I, O> appHandler) {
        this.hystrixPrefix = hystrixPrefix;
        this.interval = interval;
        this.appHandler = appHandler;
    }

    @Override
    public Observable<Void> handle(HttpServerRequest<I> request, HttpServerResponse<O> response) {
        if (request.getPath().startsWith(hystrixPrefix)) {
            return handleHystrixRequest(response);
        }
        return appHandler.handle(request, response);
    }

    private Observable<Void> handleHystrixRequest(final HttpServerResponse<O> response) {
        writeHeaders(response);

        final Subject<Void, Void> subject = PublishSubject.create();
        final MultipleAssignmentSubscription subscription = new MultipleAssignmentSubscription();
        Subscription actionSubscription = Observable.timer(0, interval, TimeUnit.MILLISECONDS, Schedulers.computation())
                .subscribe(new Action1<Long>() {
                    @Override
                    public void call(Long tick) {
                        if (!response.getChannelHandlerContext().channel().isOpen()) {
                            subscription.unsubscribe();
                            return;
                        }
                        try {
                            for (HystrixCommandMetrics commandMetrics : HystrixCommandMetrics.getInstances()) {
                                writeMetric(toJson(commandMetrics), response);
                            }
                            for (HystrixThreadPoolMetrics threadPoolMetrics : HystrixThreadPoolMetrics.getInstances()) {
                                writeMetric(toJson(threadPoolMetrics), response);
                            }
                        } catch (Exception e) {
                            subject.onError(e);
                        }
                    }
                });
        subscription.set(actionSubscription);
        return subject;
    }

    private void writeHeaders(HttpServerResponse<O> response) {
        response.getHeaders().add("Content-Type", "text/event-stream;charset=UTF-8");
        response.getHeaders().add("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        response.getHeaders().add("Pragma", "no-cache");
    }

    @SuppressWarnings("unchecked")
    private void writeMetric(String json, HttpServerResponse<O> response) {
        byte[] bytes = json.getBytes(Charset.defaultCharset());
        ByteBuf byteBuf = UnpooledByteBufAllocator.DEFAULT.buffer(bytes.length + EXTRA_SPACE);
        byteBuf.writeBytes(HEADER);
        byteBuf.writeBytes(bytes);
        byteBuf.writeBytes(FOOTER);
        response.writeAndFlush((O) byteBuf);
    }
}
