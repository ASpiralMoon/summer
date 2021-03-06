package com.xinqing.summer.mvc;

import com.xinqing.summer.mvc.concurrent.StandardThreadExecutor;
import com.xinqing.summer.mvc.constant.SummerConstants;
import com.xinqing.summer.mvc.http.HttpExecution;
import com.xinqing.summer.mvc.bootstrap.HttpPipelineInitializer;
import com.xinqing.summer.mvc.bootstrap.HttpServer;
import com.xinqing.summer.mvc.bootstrap.HttpServerHandler;
import com.xinqing.summer.mvc.bootstrap.HttpStaticFileHandler;
import com.xinqing.summer.mvc.http.handler.Before;
import com.xinqing.summer.mvc.http.handler.Handler;
import com.xinqing.summer.mvc.route.Router;
import com.xinqing.summer.mvc.route.RouterImpl;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * bootstrap
 *
 * Created by xuan on 2018/4/16
 */
public class Summer {

    private static final Logger LOG = LoggerFactory.getLogger(Summer.class);

    private final Router router = new RouterImpl();
    private int port = SummerConstants.DEFAULT_PORT;

    /**
     * 处理http静态资源
     */
    private HttpStaticFileHandler httpStaticFileHandler;

    /**
     * 处理业务http请求
     */
    private HttpServerHandler httpServerHandler;

    private Summer() {
        this(new StandardThreadExecutor());
    }

    private Summer(ThreadPoolExecutor threadPoolExecutor) {
        this.httpServerHandler = new HttpServerHandler(new HttpExecution(router), threadPoolExecutor);
    }

    public static Summer me() {
        return me(new StandardThreadExecutor());
    }

    public static Summer me(ThreadPoolExecutor threadPoolExecutor) {
        LOG.info("summer *_*!!!");
        return new Summer(threadPoolExecutor);
    }

    public Router router() {
        return router;
    }

    public Summer before(String ant, Before before) {
        router.before(ant, before);
        return this;
    }

    public Summer get(String path, Handler handler) {
        router.get(path, handler);
        return this;
    }

    public Summer post(String path, Handler handler) {
        router.post(path, handler);
        return this;
    }

    public Summer put(String path, Handler handler) {
        router.put(path, handler);
        return this;
    }

    public Summer delete(String path, Handler handler) {
        router.delete(path, handler);
        return this;
    }

    public Summer route(String path, Set<HttpMethod> methods, Handler handler) {
        router.route(path, methods, handler);
        return this;
    }

    public Summer staticFile(String staticPrefix, String staticPath) {
        if (httpStaticFileHandler == null) {
            httpStaticFileHandler = new HttpStaticFileHandler(staticPrefix, staticPath);
            LOG.info("static resource: '{}'", staticPrefix);
        }
        return this;
    }

    public Summer listen(int port) {
        this.port = port;
        return this;
    }

    public void serve() {
        try {
            // 启动http server
            server().listenAndServe(port);
        } catch (Exception e) {
            LOG.error("listenAndServe error.", e);
        }
    }

    private HttpServer server() {
        return new HttpServer(new HttpPipelineInitializer(handlers()));
    }

    private List<ChannelHandler> handlers() {
        List<ChannelHandler> handlers = new ArrayList<>();
        // 静态资源
        if (httpStaticFileHandler != null) {
            handlers.add(httpStaticFileHandler);
        }
        // 业务http
        handlers.add(httpServerHandler);
        return handlers;
    }

}
