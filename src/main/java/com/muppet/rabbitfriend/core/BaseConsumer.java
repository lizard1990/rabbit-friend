package com.muppet.rabbitfriend.core;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.muppet.util.AspectAddPropertyUtil;
import com.muppet.util.ExceptionDSL;
import com.muppet.util.GsonTransient;
import com.muppet.util.GsonTypeCoder;
import com.muppet.util.GsonUtil;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import okio.Timeout;
import org.apache.commons.lang.reflect.FieldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reflections.ReflectionUtils;
import org.reflections.Reflections;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Created by yuhaiqiang on 2018/7/4.
 *
 * @description
 */
public abstract class BaseConsumer implements Consumer, Lifecycle, Consume, Consume.AutoAckEnable, RabbitFriendComponent {

    protected RabbitContext context;

    private Logger logger = LogManager.getLogger(this.getClass());

    private HashMap<String, RpcProducer> rpcProducers = new HashMap<>();

    private Gson gson = GsonUtil.getGson();

    protected Channel channel;

    protected UuidGenerate uuidGenerate;

    protected RabbitmqDelegate delegate;

    private RpcProducer rpcProducer;

    private java.util.function.BiConsumer<Message, Throwable> exceptionHandler;

    private List<MessageConsumerExtractor> extractors = new ArrayList<>();


    public BaseConsumer(RabbitContext context) {
        this.context = context;
    }

    public BaseConsumer setChannel(Channel channel) {
        this.channel = channel;
        return this;
    }

    public RpcProducer getRpcProducer() {
        return rpcProducer;
    }

    public BaseConsumer setRpcProducer(RpcProducer rpcProducer) {
        this.rpcProducer = rpcProducer;
        return this;
    }

    private Map<String, String> headers = new HashMap<>();


    @Override
    public void start() {
        delegate = context.getDelegateFactory().acquireDelegate();
        uuidGenerate = context.getConfiguration().getUuidGenerator();
    }


    protected boolean checkMessage(Message message) {
        if (message instanceof TimeoutMessage) {
            TimeoutMessage timeoutMessage = (TimeoutMessage) message;
            timeoutMessage.getTimeout();
            Date createDate = message.getBasicProperties().getTimestamp();
            Long timeout = Long.valueOf(message.getBasicProperties().getHeaders().get(TimeoutMessage.TIMEOUT_KEY).toString());
            AspectAddPropertyUtil.addGetTimeoutAspect(timeoutMessage, timeout);
            if (timeoutMessage.getTimeout() <= System.currentTimeMillis() - createDate.getTime()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
        Message message = context.getDefaultMessageConvertor().loads(consumerTag, envelope, properties, body);
        message.setBasicProperties(properties);

        boolean isTimeout = checkMessage(message);
        //if (isTimeout) {
        //TODO reply the error MessageReply
        //z  return;
        //}
        AtomicBoolean acked = new AtomicBoolean(false);
        BiFunction<Boolean, Boolean, Void> ackFunc = ((ack, requeue) -> {
            if (!acked.compareAndSet(false, true)) {
                return null;
            }

            if (autoAck()) {
                return null;
            }
            try {
                if (ack) {
                    channel.basicAck(envelope.getDeliveryTag(), false);
                } else {
                    //TODO requeue
                    channel.basicNack(envelope.getDeliveryTag(), true, requeue);
                }
            } catch (IOException e) {
                throw new RabbitFriendException(e);
            }
            return null;
        });
        ExceptionDSL.throwable(() -> FieldUtils.writeField(message, "ackFunc", ackFunc, true));

        extractors.stream().forEach((extractor) -> extractor.extracte(message));

        //Handle message before Interceptor

        try {
            handle(message);
        } catch (Throwable throwable) {
            if (exceptionHandler != null) {
                exceptionHandler.accept(message, throwable);
            }
        } finally {
            message.nack(false);
        }
    }

    protected void processMessage(Message message) {
    }

    @Override
    public void destroy() {

    }

    @Override
    public void handleConsumeOk(String consumerTag) {

    }

    @Override
    public void handleCancelOk(String consumerTag) {

    }

    @Override
    public void handleCancel(String consumerTag) throws IOException {

    }

    @Override
    public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {

    }

    @Override
    public boolean autoAck() {
        return false;
    }

    @Override
    public void handleRecoverOk(String consumerTag) {

    }


    public BaseQueue getConsumedQueue() {
        return new BaseQueue(getQueueName());
    }

    protected abstract String getQueueName();


    @Override
    public Map<String, String> setHeaderEntry(String key, String value) {
        return null;
    }

    @Override
    public Set<String> getEnabledHeaderKeys() {
        return null;
    }

    @Override
    public Map<String, String> getHeaders() {
        return null;
    }

    public java.util.function.BiConsumer<Message, Throwable> getExceptionHandler() {
        return exceptionHandler;
    }

    public BaseConsumer setExceptionHandler(java.util.function.BiConsumer<Message, Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    public void addMessageConsumerExtractor(MessageConsumerExtractor extractor) {
        extractors.add(extractor);
    }

}
