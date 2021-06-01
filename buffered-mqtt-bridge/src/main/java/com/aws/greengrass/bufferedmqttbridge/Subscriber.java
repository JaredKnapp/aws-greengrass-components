/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.greengrass.bufferedmqttbridge;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;

import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPC;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationResponse;
import software.amazon.awssdk.aws.greengrass.model.IoTCoreMessage;
import software.amazon.awssdk.aws.greengrass.model.MQTTMessage;
import software.amazon.awssdk.aws.greengrass.model.QOS;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToIoTCoreRequest;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

public class Subscriber implements Closeable {

    protected static final String MAPPINGS = "mappings";
    protected static final String CONNECTION_INFO = "connectionInfo";
    protected static final String LOCAL_TOPICS = "localTopics";
    protected static final String CLOUD_TOPICS = "cloudTopics";

    private final GreengrassCoreIPC ipcClient;
    private String brokerUri;
    private String username;
    private String password;
    private String clientId;
    private MqttClient mqttClient;
    private ArrayBlockingQueue<MessageObject> messageQueue;
    private List<String> localTopics;
    private List<String> cloudTopics;

    public Subscriber(GreengrassCoreIPC ipcClient, ArrayBlockingQueue<MessageObject> messageQueue) {
        this.ipcClient = ipcClient;
        this.messageQueue = messageQueue;
    }

    /**
     * Get the initial configuration from Greengrass and connect to the broker.
     * Does NOT dynamically reconfigure when configuration changes.
     *
     * @throws ExecutionException if reading configuration fails
     * @throws InterruptedException if anything is interrupted
     * @throws MqttException if connecting to the broker fails
     */
    @SuppressWarnings("unchecked")
    public void startup() throws ExecutionException, InterruptedException, MqttException {
        GetConfigurationRequest getConfigurationRequest = new GetConfigurationRequest();

        // Get MQTT Topics
        getConfigurationRequest.setKeyPath(Arrays.asList(MAPPINGS));
        GetConfigurationResponse response = ipcClient.getConfiguration(getConfigurationRequest, Optional.empty()).getResponse().get();

        localTopics = (List<String>) response.getValue().get(LOCAL_TOPICS);
        cloudTopics = (List<String>) response.getValue().get(CLOUD_TOPICS);

        // Get MQTT Connection Information
        getConfigurationRequest.setKeyPath(Arrays.asList(CONNECTION_INFO));
        response = ipcClient.getConfiguration(getConfigurationRequest, Optional.empty()).getResponse().get();

        brokerUri = (String) response.getValue().get("brokerUri");
        username = (String) response.getValue().get("username");
        password = (String) response.getValue().get("password");
        clientId = (String) response.getValue().get("clientId");

        // Create our Local Mosquitto MQTT Client and connect
        mqttClient = new MqttClient(brokerUri, clientId);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        if (username != null) {
            options.setUserName(username);
        }
        if (password != null) {
            options.setPassword(password.toCharArray());
        }
        System.out.println("Connecting to broker...");
        mqttClient.connectWithResult(options).waitForCompletion();
        System.out.println("Connected to broker");

        mqttClient.setCallback(new MqttCallback() {

            @Override
            public void connectionLost(Throwable throwable) {
                // Handle reconnection here
                System.err.println("Lost connection to broker!!! Waiting 10 seconds");
                try {
                    TimeUnit.SECONDS.sleep(10);
                    System.out.println("Connecting to broker...");
                    mqttClient.connectWithResult(options).waitForCompletion();
                    System.out.println("Connected to broker");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (MqttSecurityException e) {
                    e.printStackTrace();
                } catch (MqttException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
                // Instead of publishing to IoT Core, you could instead write into a Stream
                // Manager stream
                // and have that data sent to Kinesis, IoT Analytics, or more
                //
                // Assuming you had created a stream manager client, then:
                // streamManagerClient.appendMessage("streamName", mqttMessage.getPayload());
                //
                MessageObject obj = new MessageObject(topic, mqttMessage);
                System.out.println("Local Message Arrived");
                if (messageQueue.remainingCapacity() > 0) {
                    messageQueue.put(obj);
                } else {
                    throw new Exception("Message Queue is FULL! Dropping message.");
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken t) {
                System.out.println("Message delivery complete - " + t.toString());
            }
        });

    }

    /**
     * Subscribe to all cloud and local topics, then block until requested to
     * shutdown.
     *
     * @throws MqttException if subscribing fails
     * @throws InterruptedException if subscribing is interrupted
     */
    public void run() throws MqttException, InterruptedException {
        System.out.println("Subscribing to local topics");
        // Subscribe to broker's messages
        mqttClient.subscribeWithResponse(localTopics.toArray(new String[0])).waitForCompletion();

        System.out.println("Subscribing to cloud topics");
        // Subscribe to IoT Core's messages
        for (String topic : cloudTopics) {
            SubscribeToIoTCoreRequest request = new SubscribeToIoTCoreRequest();
            request.setTopicName(topic);
            request.setQos(QOS.AT_MOST_ONCE);

            ipcClient.subscribeToIoTCore(request, Optional.of(new StreamResponseHandler<IoTCoreMessage>() {
                @Override
                public void onStreamEvent(IoTCoreMessage ioTCoreMessage) {
                    // Forward message to local broker
                    System.out.println("Forwarding message to local broker");
                    MQTTMessage message = ioTCoreMessage.getMessage();
                    try {
                        mqttClient.publish(message.getTopicName(), new MqttMessage(message.getPayload()));
                    } catch (MqttException e) {
                        // Handle publish errors
                        System.out.println("Publish Error: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                @Override
                public boolean onStreamError(Throwable throwable) {
                    throwable.printStackTrace();
                    return false;
                }

                @Override
                public void onStreamClosed() {
                }
            }));
        }

        // Just keep the main thread alive here.
        while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(100_000);
        }
    }

    @Override
    public void close() throws IOException {
        if (mqttClient != null) {
            try {
                mqttClient.close(true);
            } catch (MqttException e) {
                throw new IOException(e);
            }
        }
    }
}
