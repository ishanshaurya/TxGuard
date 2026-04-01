package com.txguard.internal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * Infrastructure bean definitions for RabbitMQ and Redis.
 *
 * <h2>RabbitMQ topology</h2>
 * <pre>
 *   Exchange: bouncer.charges  (direct)
 *      │
 *      └─[charge.received]──► Queue: bouncer.charges.received
 *                                    │ (on 3 failures)
 *                                    └──► DLQ: bouncer.charges.received.dlq
 * </pre>
 *
 * <h2>Retry policy</h2>
 * <p>3 total attempts (1 initial + 2 retries) with exponential backoff:
 * 1s → 2s → 4s. After exhaustion, {@link RejectAndDontRequeueRecoverer}
 * nacks the message → RabbitMQ routes it to the DLQ.
 */
@Configuration
public class InfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureConfig.class);

    // ── Queue / exchange name constants ───────────────────────────────────────
    public static final String EXCHANGE_CHARGES        = "bouncer.charges";
    public static final String QUEUE_CHARGE_RECEIVED   = "bouncer.charges.received";
    public static final String QUEUE_CHARGE_DLQ        = "bouncer.charges.received.dlq";
    public static final String ROUTING_CHARGE_RECEIVED = "charge.received";

    // ── Retry constants ───────────────────────────────────────────────────────
    private static final int    RETRY_MAX_ATTEMPTS      = 3;
    private static final long   RETRY_INITIAL_INTERVAL  = 1_000L; // 1 second
    private static final double RETRY_MULTIPLIER        = 2.0;
    private static final long   RETRY_MAX_INTERVAL      = 10_000L; // 10 seconds

    // ── RabbitMQ: exchange ────────────────────────────────────────────────────

    @Bean
    public DirectExchange chargesExchange() {
        return new DirectExchange(EXCHANGE_CHARGES, true, false);
    }

    // ── RabbitMQ: dead-letter queue ───────────────────────────────────────────

    @Bean
    public Queue chargeReceivedDlq() {
        return QueueBuilder.durable(QUEUE_CHARGE_DLQ).build();
    }

    // ── RabbitMQ: main queue ──────────────────────────────────────────────────

    @Bean
    public Queue chargeReceivedQueue() {
        return QueueBuilder.durable(QUEUE_CHARGE_RECEIVED)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE_CHARGE_DLQ)
                .withArgument("x-message-ttl", 86_400_000)
                .build();
    }

    // ── RabbitMQ: binding ─────────────────────────────────────────────────────

    @Bean
    public Binding chargeReceivedBinding(Queue chargeReceivedQueue, DirectExchange chargesExchange) {
        return BindingBuilder
                .bind(chargeReceivedQueue)
                .to(chargesExchange)
                .with(ROUTING_CHARGE_RECEIVED);
    }

    // ── RabbitMQ: JSON message converter ──────────────────────────────────────

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ── RabbitMQ: template (used by publisher) ────────────────────────────────

    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter
    ) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    // ── RabbitMQ: retry interceptor ───────────────────────────────────────────

    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(RETRY_MAX_ATTEMPTS)
                .backOffOptions(
                        RETRY_INITIAL_INTERVAL,
                        RETRY_MULTIPLIER,
                        RETRY_MAX_INTERVAL
                )
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    // ── RabbitMQ: listener container factory (wires in retry + converter) ─────

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter,
            RetryOperationsInterceptor retryInterceptor
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
        factory.setAdviceChain(retryInterceptor);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(5);
        return factory;
    }


    // ── RabbitMQ: admin (used by health indicators) ───────────────────────────

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    // ── Redis: string template ────────────────────────────────────────────────

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
