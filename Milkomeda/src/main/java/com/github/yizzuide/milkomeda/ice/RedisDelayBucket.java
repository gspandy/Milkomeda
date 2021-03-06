package com.github.yizzuide.milkomeda.ice;

import com.github.yizzuide.milkomeda.universe.context.ApplicationContextHolder;
import com.github.yizzuide.milkomeda.util.JSONUtil;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * RedisDelayBucket
 * 延迟桶
 *
 * @author yizzuide
 * @since 1.15.0
 * @version 3.8.0
 * Create at 2019/11/16 16:17
 */
public class RedisDelayBucket implements DelayBucket, InitializingBean, ApplicationListener<IceInstanceChangeEvent> {

    private final IceProperties props;

    private StringRedisTemplate redisTemplate;

    private final List<String> bucketNames = new ArrayList<>();

    private static final AtomicInteger index = new AtomicInteger(0);

    // 默认最大桶大小
    public static final int DEFAULT_MAX_BUCKET_SIZE = 100;

    public RedisDelayBucket(IceProperties props) {
        this.props = props;
        for (int i = 0; i < props.getDelayBucketCount(); i++) {
            if (IceProperties.DEFAULT_INSTANCE_NAME.equals(props.getInstanceName())) {
                bucketNames.add("ice:bucket" + i);
            } else {
                bucketNames.add("ice:bucket" + i + ":" + props.getInstanceName());
            }
        }
    }

    @Override
    public void add(DelayJob delayJob) {
        String bucketName = getCurrentBucketName();
        BoundZSetOperations<String, String> bucket = getBucket(bucketName);
        bucket.add(delayJob.toSimple(), delayJob.getDelayTime());
    }

    @Override
    public void add(List<DelayJob> delayJobs) {
        String bucketName = getCurrentBucketName();
        BoundZSetOperations<String, String> bucket = getBucket(bucketName);
        Set<ZSetOperations.TypedTuple<String>> delayJobSet = delayJobs.stream()
                .map(delayJob -> new DefaultTypedTuple<>(delayJob.toSimple(), (double) delayJob.getDelayTime()))
                .collect(Collectors.toSet());
        bucket.add(delayJobSet);
    }

    @SuppressWarnings("unchecked")
    @Override
    public DelayJob poll(Integer index) {
        String name = bucketNames.get(index);
        BoundZSetOperations<String, String> bucket = getBucket(name);
        // 升序查第一个（最上面的是延迟/TTR过期的）
        Set<ZSetOperations.TypedTuple<String>> set = bucket.rangeWithScores(0, 1);
        if (CollectionUtils.isEmpty(set)) {
            return null;
        }
        ZSetOperations.TypedTuple<String> typedTuple = set.toArray(new ZSetOperations.TypedTuple[]{})[0];
        if (typedTuple.getValue() == null) {
            return null;
        }
        return DelayJob.compatibleDecode(typedTuple.getValue(), typedTuple.getScore());
    }

    @Override
    public void remove(Integer index, DelayJob delayJob) {
        String name = bucketNames.get(index);
        BoundZSetOperations<String, String> bucket = getBucket(name);
        // 优化后的方式删除
        if (delayJob.isUsedSimple()) {
            bucket.remove(delayJob.toSimple());
            return;
        }
        // 兼容旧方式序列化删除
        bucket.remove(JSONUtil.serialize(delayJob));
    }

    /**
     * 获得桶的ZSet
     *
     * @param bucketName 桶名
     * @return BoundZSetOperations
     */
    private BoundZSetOperations<String, String> getBucket(String bucketName) {
        return redisTemplate.boundZSetOps(bucketName);
    }

    /**
     * 获得桶的名称
     *
     * @return BucketName
     */
    private String getCurrentBucketName() {
        int thisIndex = index.getAndIncrement() % DEFAULT_MAX_BUCKET_SIZE;
        return bucketNames.get(thisIndex % props.getDelayBucketCount());
    }

    @Override
    public void afterPropertiesSet() {
        redisTemplate = ApplicationContextHolder.get().getBean(StringRedisTemplate.class);
    }

    @Override
    public void onApplicationEvent(IceInstanceChangeEvent event) {
        String instanceName = event.getSource().toString();
        bucketNames.clear();
        for (int i = 0; i < props.getDelayBucketCount(); i++) {
            bucketNames.add("ice:bucket" + i + ":" + instanceName);
        }
    }
}
