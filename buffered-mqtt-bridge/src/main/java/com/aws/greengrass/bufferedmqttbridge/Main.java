/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.bufferedmqttbridge;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;

import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPC;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationResponse;
import software.amazon.awssdk.aws.greengrass.model.ReportedLifecycleState;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;

public class Main {

    private static EventStreamRPCConnection connection;
    private static GreengrassCoreIPC client;
    private static IoTCorePublisher publisher;
    private static Subscriber subscriber;

    protected static final String CONNECTION_INFO = "connectionInfo";
    protected static final String QUEUE_DEPTH = "queueDepth";

    /**
     * Run.
     *
     * @param args arguments
     */
    public static void main(String[] args) {
        try {

            connection = IPCUtil.getEventStreamRpcConnection();
            client = new GreengrassCoreIPCClient(connection);

            // Get Queue Size
            GetConfigurationRequest getConfigurationRequest = new GetConfigurationRequest();
            getConfigurationRequest.setKeyPath(Arrays.asList(CONNECTION_INFO));
            GetConfigurationResponse response = client.getConfiguration(getConfigurationRequest, Optional.empty()).getResponse().get();
            Double queueDepth = (Double) response.getValue().get(QUEUE_DEPTH);

            System.out.println("Starting Buffered MQTT Broker. Queue buffer size = " + Integer.valueOf(queueDepth.intValue()));
            ArrayBlockingQueue<MessageObject> messageQueue = new ArrayBlockingQueue<>(queueDepth.intValue());

            // Publisher runs in its own thread
            publisher = new IoTCorePublisher(client, messageQueue);
            publisher.start();

            subscriber = new Subscriber(client, messageQueue);
            subscriber.startup();

            IPCUtil.reportState(client, ReportedLifecycleState.RUNNING);

            subscriber.run();
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (connection != null) {
                connection.close();
            }
            if (subscriber != null) {
                try {
                    subscriber.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
