/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package com.yahoo.omid.notifications.client;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.TException;
import org.apache.thrift.server.TNonblockingServer;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TTransportException;

import akka.actor.ActorRef;

import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.yahoo.omid.notifications.Constants;
import com.yahoo.omid.notifications.thrift.generated.Notification;
import com.yahoo.omid.notifications.thrift.generated.NotificationReceiverService;

public class NotificationManager extends AbstractExecutionThreadService {

    private static final Log logger = LogFactory.getLog(NotificationManager.class);

    private Map<String, ActorRef> registeredObservers;

    private final ExecutorService notificatorAcceptorExecutor = Executors.newSingleThreadExecutor();

    // We could use this: Queue<Notification> queue =
    // Collections.synchronizedList(new LinkedList<Notification>()); but we use
    // the queue below
    // This could be overkill in a one producer/consumer scenario but it's used
    // for the sake of simplicity and we expect to have
    // more than one consumer at the same time in the near future
    private BlockingQueue<Notification> queue = new LinkedBlockingQueue<Notification>();
    
    /**
     * @param registeredObservers
     * 
     */
    public NotificationManager(Map<String, ActorRef> registeredObservers) {
        super();
        this.registeredObservers = registeredObservers;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.google.common.util.concurrent.AbstractExecutionThreadService#startUp
     * ()
     */
    @Override
    protected void startUp() throws Exception {
        logger.info("Starting Notification Listener");
        // Init Thrift server
        notificatorAcceptorExecutor.execute(new NotificationAcceptor());
        super.startUp();
        logger.info("Notification Listener started");
    }

    /*
     * Stop Thrift server and stop consuming from the queue (non-Javadoc)
     * 
     * @see
     * com.google.common.util.concurrent.AbstractExecutionThreadService#shutDown
     * ()
     */
    @Override
    protected void shutDown() throws Exception {
        logger.info("Stopping Notification Listener");
        notificatorAcceptorExecutor.shutdownNow();
        // Stop consuming from queue
        super.shutDown();
        logger.info("Notification Listener stopped");
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.google.common.util.concurrent.AbstractExecutionThreadService#run()
     */
    @Override
    protected void run() {
        logger.trace("On run!!!");
        while (isRunning()) {
            // Take into account that there's a single consumer that has the
            // responsibility of notifying all the observers serially. Change this to have concurrent consumers
            Notification notification;
            try {
                //logger.trace("Trying to consume from queue...");
                notification = queue.take();//.poll(3, TimeUnit.SECONDS);
                if(notification != null) {
                    String table = new String(notification.getTable());
                    String rowKey = new String(notification.getRowKey());
                    String columnFamily = new String(notification.getColumnFamily());
                    String column = new String(notification.getColumn());
                    //logger.trace("Notification for observer " + notification.getObserver() + " on RowKey " + rowKey
                    //        + " in Table " + table + " CF " + columnFamily + " C " + column);
                    ActorRef obsRef = registeredObservers.get(notification.getObserver());
                    if (obsRef != null) {
                        //obs.notify(notification.getTable(), notification.getRowKey(), notification.getColumnFamily(), notification.getColumn());
                        obsRef.tell(notification);
                    } else {
                        logger.error("The observer " + obsRef.toString() + " does not exists!!!");
                    }
                } else {
                    logger.trace("Extracted notif " + notification + " is null");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private class NotificationAcceptor implements Runnable, NotificationReceiverService.Iface {

        private TServer server;

        @Override
        public void run() {
            try {
                TNonblockingServerTransport serverTransport = new TNonblockingServerSocket(Constants.THRIFT_SERVER_PORT);
                NotificationReceiverService.Processor<NotificationAcceptor> processor = new NotificationReceiverService.Processor<NotificationAcceptor>(
                        this);
                server = new TNonblockingServer(new TNonblockingServer.Args(serverTransport).processor(processor));
                logger.trace("Starting Thrift server on " + Constants.THRIFT_SERVER_PORT);
                server.serve();
            } catch (TTransportException e) {
                e.printStackTrace();
            } finally {
                server.stop();
                logger.trace("Thrift server stopped");
            }
        }

        /*
         * (non-Javadoc)
         * 
         * @see
         * com.yahoo.omid.notifications.thrift.generated.ObserverContainer.Iface
         * #update(com.yahoo.omid.notifications.thrift.generated.Notification)
         */
        @Override
        public void notify(Notification notification) throws TException {            
            try {
                queue.put(notification);
                //logger.trace("Notification " + notification + " inserted in queue!!!!");
                //Thread.currentThread().sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}