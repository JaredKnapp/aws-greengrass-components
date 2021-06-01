/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.bufferedmqttbridge;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPC;
import software.amazon.awssdk.aws.greengrass.model.PublishToIoTCoreRequest;

public class IoTCorePublisher extends Thread {

	private final GreengrassCoreIPC ipcClient;
	private ArrayBlockingQueue<MessageObject> messageQueue;

	public IoTCorePublisher(GreengrassCoreIPC ipcClient, ArrayBlockingQueue<MessageObject> messageQueue) {
		this.ipcClient = ipcClient;
		this.messageQueue = messageQueue;
	}

	/**
	 * Subscribe to all cloud and local topics, then block until requested to
	 * shutdown.
	 *
	 * @throws ?? if ??
	 */
	public void run() {

		MessageObject messageObject = null;
		boolean isConnected = true;

		try {

			while (true) {

				System.out.println("Publisher Queue Depth = " + String.valueOf(messageQueue.size()));

				// Only get next message if we're connected, othewise continue to try to publish
				// last message
				// Our BlockingQueue waits here until a message becomes available
				messageObject = isConnected ? messageQueue.take() : messageObject;
				String topic = messageObject.getTopic();
				int qos = messageObject.getQos();
				byte[] message = messageObject.getMessage();

				try {
					// Forward message to AWS IoT Core
					PublishToIoTCoreRequest request = new PublishToIoTCoreRequest();
					request.setQos(IPCUtil.getQOSFromValue(qos));
					request.setPayload(message);
					request.setTopicName(topic);
					ipcClient.publishToIoTCore(request, Optional.empty()).getResponse().get();

					isConnected = true;
				} catch (Exception e) {
					// problem delivering message... wait 3 seconds, and retry
					System.out.println("Publisher Exception: " + e.getMessage());

					isConnected = false;
					TimeUnit.SECONDS.sleep(3);
				}

			}

		} catch (InterruptedException e) {
			// Application is exiting, time to get out.
			e.printStackTrace();
			System.out.println("IoTCorePublisher InterruptedException: " + e.getMessage());
		}

	}

}
