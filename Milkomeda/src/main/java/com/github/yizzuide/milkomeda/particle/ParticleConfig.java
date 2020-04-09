package com.github.yizzuide.milkomeda.particle;

import com.github.yizzuide.milkomeda.universe.context.WebContext;
import com.github.yizzuide.milkomeda.util.ReflectUtil;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.web.filter.DelegatingFilterProxy;

import java.util.*;

/**
 * ParticleConfig
 *
 * @author yizzuide
 * @since 1.14.0
 * @since 3.0.0
 * Create at 2019/11/11 11:26
 */
@Configuration
@ConditionalOnClass(RedisTemplate.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(ParticleProperties.class)
public class ParticleConfig implements ApplicationContextAware {

    @Autowired
    private ParticleProperties particleProperties;

    /**
     * 缓存创建的LimitHandler
     */
    private Map<String, LimitHandler> cacheHandlerBeans = new HashMap<>();


    @Bean
    public ParticleAspect particleAspect() {
        return new ParticleAspect();
    }

    @Bean
    public IdempotentLimiter idempotentLimiter() {
        return new IdempotentLimiter();
    }

    @Bean
    public ParticleFilter particleFilter() {
        return new ParticleFilter();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Bean
    @ConditionalOnProperty(prefix = "milkomeda.particle", name = "enable-filter", havingValue = "true")
    public FilterRegistrationBean particleFilterRegistrationBean() {
        FilterRegistrationBean particleFilterRegistrationBean = new FilterRegistrationBean();
        // 设置代理注册的Bean
        particleFilterRegistrationBean.setFilter(new DelegatingFilterProxy("particleFilter"));
        particleFilterRegistrationBean.setName("particleFilter");
        particleFilterRegistrationBean.setUrlPatterns(Collections.singleton("/*"));
        particleFilterRegistrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return particleFilterRegistrationBean;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        List<ParticleProperties.Limiter> limiters = particleProperties.getLimiters();
        List<ParticleProperties.Limiter> barrierLimiters = new ArrayList<>();
        for (ParticleProperties.Limiter limiter : limiters) {
            String limiterName = limiter.getName();
            LimitHandler limitHandler = WebContext.registerBean((ConfigurableApplicationContext) applicationContext, limiterName, limiter.getHandlerClazz());
            cacheHandlerBeans.put(limiterName, limitHandler);
            if (!CollectionUtils.isEmpty(limiter.getProps())) {
                ReflectUtil.setField(limitHandler, limiter.getProps());
            }
            limiter.setLimitHandler(limitHandler);
            if (limiter.getHandlerClazz() == BarrierLimiter.class) {
                barrierLimiters.add(limiter);
            }
        }
        // 创建barrierLimiter类型限制器链
        for (ParticleProperties.Limiter limiter : barrierLimiters) {
            BarrierLimiter barrierLimiter = (BarrierLimiter) limiter.getLimitHandler();
            List<String> chain = barrierLimiter.getChain();
            List<LimitHandler> handlers = new ArrayList<>();
            for (String name : chain) {
                handlers.add(cacheHandlerBeans.get(name));
            }
            barrierLimiter.addLimitHandlerList(handlers);
        }
    }
}
