package com.xinqing.summer.mvc.route;

import com.xinqing.summer.mvc.exception.RouteException;
import com.xinqing.summer.mvc.util.AntMatcher;
import com.xinqing.summer.mvc.util.PathMatcher;
import com.xinqing.summer.mvc.util.RouteUtils;
import com.xinqing.summer.mvc.http.RequestContext;
import com.xinqing.summer.mvc.http.handler.Before;
import com.xinqing.summer.mvc.http.handler.DefaultFailureHandler;
import com.xinqing.summer.mvc.http.handler.FailureHandler;
import com.xinqing.summer.mvc.http.handler.Handler;
import com.xinqing.summer.mvc.http.handler.NotFoundHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.internal.ConcurrentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by xuan on 2018/4/25
 */
public class RouterImpl implements Router {

    private static final Logger LOG = LoggerFactory.getLogger(RouterImpl.class);

    /**
     * AntMatcher
     */
    private final AntMatcher antMatcher = new AntMatcher();

    /**
     * 前置拦截
     */
    private final Map<String, List<Before>> befores = new ConcurrentHashMap<>();

    /**
     * 所有的路由信息
     */
    private final Map<String, Route> routes = new ConcurrentHashMap<>();

    /**
     * 模糊匹配的路由
     */
    private Set<Route> patternRoutes = new ConcurrentSet<>();

    /**
     * 存放模糊匹配的正则与原始模式匹配路径信息，用于验证路由是否重复添加
     */
    private Map<String, String> regexToPathMap = new ConcurrentHashMap<>();

    /**
     * 匹配不到路由时的Handler
     */
    private Handler notFoundHandler = new NotFoundHandler();

    private FailureHandler failureHandler = new DefaultFailureHandler();

    @Override
    public void before(String ant, Before before) {
        RouteUtils.checkPath(ant);
        if (antMatcher.isPattern(ant)) {
            LOG.info("before '{}' onto {}", ant, before);
            befores.computeIfAbsent(ant, v -> new ArrayList<>()).add(before);
        } else {
            throw new RouteException("'" + ant + "' is not ant pattern");
        }
    }

    @Override
    public void get(String path, Handler handler) {
        route(path, HttpMethod.GET, handler);
    }

    @Override
    public void post(String path, Handler handler) {
        route(path, HttpMethod.POST, handler);
    }

    @Override
    public void put(String path, Handler handler) {
        route(path, HttpMethod.PUT, handler);
    }

    @Override
    public void delete(String path, Handler handler) {
        route(path, HttpMethod.DELETE, handler);
    }

    @Override
    public void route(String path, Set<HttpMethod> methods, Handler handler) {
        RouteUtils.checkPath(path);
        addRoute(routes, new Route(path, methods, handler));
    }

    @Override
    public void notFound(Handler notFoundHandler) {
        if (notFoundHandler == null) {
            throw new NullPointerException();
        }
        this.notFoundHandler = notFoundHandler;
    }

    @Override
    public Handler notFound() {
        return notFoundHandler;
    }

    @Override
    public void failureHandler(FailureHandler failureHandler) {
        if (failureHandler == null) {
            throw new NullPointerException();
        }
        this.failureHandler = failureHandler;
    }

    @Override
    public FailureHandler failureHandler() {
        return failureHandler;
    }

    @Override
    public List<Before> lookup(String path) {
        for (Map.Entry<String, List<Before>> entry : befores.entrySet()) {
            String ant = entry.getKey();
            if (antMatcher.match(ant, path)) {
                LOG.debug("request '{}' matched by before '{}'", path, ant);
                return entry.getValue();
            }
        }
        LOG.debug("request '{}' no befores found", path);
        return Collections.emptyList();
    }

    @Override
    public Route lookup(HttpMethod method, String path) {
        // 精确匹配
        Route route = lookupExact(method, path);
        // 精确匹配不到路径，则模糊匹配
        if (route == null) {
            route = lookupPattern(method, path);
        }
        // 模糊匹配不到路径
        if (route == null) {
            LOG.debug("request '{}' no matches found", path);
            return null;
        }
        return route;
    }

    private void route(String path, HttpMethod method, Handler handler) {
        Set<HttpMethod> methods = new HashSet<>();
        methods.add(method);
        route(path, methods, handler);
    }

    private void addRoute(Map<String, Route> routes, Route route) {
        String path = route.getPath();
        // 匹配路径不允许重复
        if (routes.containsKey(path)) {
            throw new RouteException("mapping '" + path + "' added already");
        }
        if (route.isExactPath()) {
            // 精确匹配
            LOG.info("mapping '{}' for {} onto {}", path, route.getMethods(), route.getHandler());
        } else {
            // 模糊匹配的路由的正则不允许重复
            if (regexToPathMap.containsKey(route.getRegex())) {
                throw new RouteException("mapping '" + path + "' added already by '" + regexToPathMap.get(route.getRegex()) + "'");
            }
            // 模糊匹配的路由
            regexToPathMap.put(route.getRegex(), route.getPath());
            patternRoutes.add(route);
            LOG.info("mapping '{}' -> [{}] for {} onto {}", path, route.getAnt(), route.getMethods(), route.getHandler());
        }
        routes.put(path, route);
    }

    private Route lookupExact(HttpMethod method, String path) {
        Route route = routes.get(path);
        // 精确匹配不到路径
        if (route == null) {
            return null;
        }
        // 精确匹配到路径，查看http method是否支持
        if (route.getMethods().contains(method)) {
            LOG.debug("request '{}' matched by exact path '{}'", path, path);
            return route;
        } else {
            // 请求方法不支持
            LOG.debug("request '{}' method unsupported", path);
            return null;
        }
    }

    private Route lookupPattern(HttpMethod method, String path) {
        // 遍历模糊匹配，直到找到一个匹配的Route
        for (Route route : patternRoutes) {
            String[] match = PathMatcher.match(route.getPattern(), path);
            // 模糊匹配到，并且http method支持
            if (isPatternMatch(method, match, route)) {
                LOG.debug("request '{}' matched py pattern path '{}'", path, route.getPath());
                // 设置路径变量
                setPathVariables(match, route.getVariables());
                return route;
            }
        }
        return null;
    }

    private boolean isPatternMatch(HttpMethod method, String[] match, Route route) {
        // 路径未匹配
        if (match.length == 0) {
            return false;
        }
        // 请求方法不支持
        if (!route.getMethods().contains(method)) {
            return false;
        }
        return true;
    }

    private void setPathVariables(String[] match, String[] variables) {
        int len = match.length;
        Map<String, String> pathVariables = new HashMap<>(len);
        for (int i = 0; i < len; i++) {
            pathVariables.put(variables[i], match[i]);
        }
        RequestContext.current().paths(pathVariables);
    }
}
