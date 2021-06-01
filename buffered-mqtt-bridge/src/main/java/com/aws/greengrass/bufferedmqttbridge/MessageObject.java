package com.aws.greengrass.bufferedmqttbridge;

import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MessageObject {
	private String topic;
	private int qos;
	private byte[] message;

	public MessageObject(String topic, int qos, byte[] message) {
		this.topic = topic;
		this.message = message;
	}
	
	public MessageObject(String topic, MqttMessage mqttMessage) {
		this.topic = topic;
		this.qos = mqttMessage.getQos();
		this.message = mqttMessage.getPayload();
	}

	public String getTopic() {
		return topic;
	}

	public int getQos() {
		return qos;
	}
	
	public byte[] getMessage() {
		return message;
	}
}