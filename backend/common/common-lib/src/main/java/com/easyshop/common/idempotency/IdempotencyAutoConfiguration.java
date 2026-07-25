package com.easyshop.common.idempotency; // align with common-lib layout

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Fleet-wide wiring — the SAME auto-configuration mechanism as
 * GlobalExceptionHandler (§5.2) and the RBAC converter. Append this class to the
 * existing imports file:
 *   META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 *
 * The engine is INERT until an endpoint carries @Idempotent AND a request sends
 * the header — so dropping common-lib on the classpath changes no existing
 * behaviour. Servlet-only (the reactive gateway is excluded); requires Redis on
 * the classpath.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(StringRedisTemplate.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration implements WebMvcConfigurer {

    // ObjectProvider, not a direct IdempotencyStore dependency: this class ALSO
    // DEFINES the IdempotencyStore bean below (idempotencyStore()), a @Bean
    // method that Spring can only invoke on an already-constructed instance of
    // THIS class. Injecting IdempotencyStore straight into the constructor asks
    // Spring to build IdempotencyStore before IdempotencyAutoConfiguration
    // exists, to satisfy a constructor that only runs to make
    // IdempotencyAutoConfiguration exist - an unresolvable cycle Spring rejects
    // at startup ("dependencies... form a cycle"). ObjectProvider defers the
    // actual lookup to addInterceptors(), which Spring calls well after every
    // bean (including this one) is fully constructed.
    private final ObjectProvider<IdempotencyStore> storeProvider;
    private final IdempotencyProperties props;
    private final ObjectMapper mapper;

    public IdempotencyAutoConfiguration(ObjectProvider<IdempotencyStore> storeProvider,
                                        IdempotencyProperties props,
                                        ObjectMapper mapper,
                                        Environment env) {
        this.storeProvider = storeProvider;
        this.props = props;
        this.mapper = mapper;
        resolveNamespace(props, env); // set namespace = spring.application.name if unset
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(StringRedisTemplate redis, ObjectMapper mapper) {
        return new IdempotencyStore(redis, mapper);
    }

    // @ControllerAdvice beans are discovered from the bean registry, not classpath
    // scanning - registering it here (regardless of common-lib's package) is
    // sufficient for Spring MVC to pick it up in every consuming service.
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyResponseBodyAdvice idempotencyResponseBodyAdvice(ObjectMapper mapper) {
        return new IdempotencyResponseBodyAdvice(mapper);
    }

    /**
     * Default the namespace to the service name so cross-service keys never
     * collide (§4.9). Runs once at construction via the Environment; if you set
     * easyshop.idempotency.namespace explicitly, that wins.
     */
    private static String resolveNamespace(IdempotencyProperties props, Environment env) {
        String app = env.getProperty("spring.application.name");
        if (app != null && !app.isBlank() && "app".equals(props.getNamespace())) {
            props.setNamespace(app);
        }
        return props.getNamespace();
    }

    /**
     * Register the filter early — it must wrap request/response BEFORE the
     * DispatcherServlet so the interceptor sees the cached body and the caching
     * response. REQUEST dispatch only (never on ASYNC/ERROR re-dispatch).
     */
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyProperties props) {
        FilterRegistrationBean<IdempotencyFilter> reg = new FilterRegistrationBean<>(new IdempotencyFilter(props));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20); // after security, before MVC
        reg.setDispatcherTypes(DispatcherType.REQUEST);
        return reg;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new IdempotencyInterceptor(storeProvider.getObject(), props, mapper));
    }
}