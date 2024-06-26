/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.dispatch;


import su.sres.dispatch.io.RedisPubSubConnectionFactory;
import su.sres.dispatch.redis.PubSubConnection;
import su.sres.dispatch.redis.PubSubReply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class DispatchManager extends Thread {

  private final Logger                       logger        = LoggerFactory.getLogger(DispatchManager.class);
  private final Executor                     executor      = Executors.newCachedThreadPool();
  private final Map<String, DispatchChannel> subscriptions = new ConcurrentHashMap<>();

  private final Optional<DispatchChannel>    deadLetterChannel;
  private final RedisPubSubConnectionFactory redisPubSubConnectionFactory;

  private          PubSubConnection pubSubConnection;
  private volatile boolean          running;

  public DispatchManager(RedisPubSubConnectionFactory redisPubSubConnectionFactory,
                         Optional<DispatchChannel> deadLetterChannel)
  {
    this.redisPubSubConnectionFactory = redisPubSubConnectionFactory;
    this.deadLetterChannel            = deadLetterChannel;
  }

  @Override
  public void start() {
    this.pubSubConnection = redisPubSubConnectionFactory.connect();
    this.running          = true;
    super.start();
  }

  public void shutdown() {
    this.running = false;
    this.pubSubConnection.close();
  }

  public synchronized void subscribe(String name, DispatchChannel dispatchChannel) {
	Optional<DispatchChannel> previous = Optional.ofNullable(subscriptions.get(name));
    subscriptions.put(name, dispatchChannel);

    try {
      pubSubConnection.subscribe(name);
    } catch (IOException e) {
      logger.warn("Subscription error", e);
    }

    previous.ifPresent(channel -> dispatchUnsubscription(name, channel));
  }

  public synchronized void unsubscribe(String name, DispatchChannel channel) {
	  Optional<DispatchChannel> subscription = Optional.ofNullable(subscriptions.get(name));

    if (subscription.isPresent() && subscription.get() == channel) {
      subscriptions.remove(name);

      try {
        pubSubConnection.unsubscribe(name);
      } catch (IOException e) {
        logger.warn("Unsubscribe error", e);
      }

      dispatchUnsubscription(name, subscription.get());
    }
  }

  public boolean hasSubscription(String name) {
    return subscriptions.containsKey(name);
  }
  
  @Override
  public void run() {
    while (running) {
      try {
        PubSubReply reply = pubSubConnection.read();

        switch (reply.getType()) {
          case UNSUBSCRIBE:                             break;
          case SUBSCRIBE:   dispatchSubscribe(reply);   break;
          case MESSAGE:     dispatchMessage(reply);     break;
          default:          throw new AssertionError("Unknown pubsub reply type! " + reply.getType());
        }
      } catch (IOException e) {
        logger.warn("***** PubSub Connection Error *****", e);
        if (running) {
          this.pubSubConnection.close();
          this.pubSubConnection = redisPubSubConnectionFactory.connect();
          resubscribeAll();
        }
      }
    }

    logger.warn("DispatchManager Shutting Down...");
  }

  private void dispatchSubscribe(final PubSubReply reply) {
	  Optional<DispatchChannel> subscription = Optional.ofNullable(subscriptions.get(reply.getChannel()));

    if (subscription.isPresent()) {
      dispatchSubscription(reply.getChannel(), subscription.get());
    } else {
      logger.info("Received subscribe event for non-existing channel: " + reply.getChannel());
    }
  }

  private void dispatchMessage(PubSubReply reply) {
	  Optional<DispatchChannel> subscription = Optional.ofNullable(subscriptions.get(reply.getChannel()));

    if (subscription.isPresent()) {
      dispatchMessage(reply.getChannel(), subscription.get(), reply.getContent().get());
    } else if (deadLetterChannel.isPresent()) {
      dispatchMessage(reply.getChannel(), deadLetterChannel.get(), reply.getContent().get());
    } else {
      logger.warn("Received message for non-existing channel, with no dead letter handler: " + reply.getChannel());
    }
  }

  private void resubscribeAll() {
    new Thread(() -> {
      synchronized (DispatchManager.this) {
        try {
          for (String name : subscriptions.keySet()) {
            pubSubConnection.subscribe(name);
          }
        } catch (IOException e) {
          logger.warn("***** RESUBSCRIPTION ERROR *****", e);
        }
      }
    }).start();
  }

  private void dispatchMessage(final String name, final DispatchChannel channel, final byte[] message) {
    executor.execute(() -> channel.onDispatchMessage(name, message));
  }

  private void dispatchSubscription(final String name, final DispatchChannel channel) {
    executor.execute(() -> channel.onDispatchSubscribed(name));
  }

  private void dispatchUnsubscription(final String name, final DispatchChannel channel) {
    executor.execute(() -> channel.onDispatchUnsubscribed(name));
  }
}
