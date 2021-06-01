package com.aws.iotcore.httpdatasource;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPC;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationRequest;
import software.amazon.awssdk.aws.greengrass.model.GetConfigurationResponse;

@SuppressWarnings("restriction")
public class Worker implements Closeable {

	protected static final String MAPPINGS = "mappings";
	protected static final String LOCAL_TOPIC = "localTopic";

	protected static final String CONNECTION_INFO = "connectionInfo";
	protected static final String HTTP_PORT = "httpPort";
	protected static final String HTTP_ROOT_CONTEXT = "httpRootContext";
	protected static final String BROKER_URI = "brokerUri";
	protected static final String CLIENT_ID = "clientId";
	protected static final String USERNAME = "username";
	protected static final String PASSWORD = "password";

	private GreengrassCoreIPC ipcClient;
	private MqttClient mqttClient;
	private HttpServer server;

	private String localTopic;
	private Double httpPort;
	private String httpRootContext;
	private String brokerUri;
	private String clientId;
	private String username;
	private String password;

	public Worker(GreengrassCoreIPC ipcClient) {
		this.ipcClient = ipcClient;
	}

	/**
	 * Get the initial configuration from Greengrass and connect to the broker. Does
	 * NOT dynamically reconfigure when configuration changes.
	 *
	 * @throws ExecutionException   if reading configuration fails
	 * @throws InterruptedException if anything is interrupted
	 * @throws MqttException        if connecting to the broker fails
	 */
	public void startup() throws ExecutionException, InterruptedException, MqttException, IOException {
		// Get Configuration Information
		GetConfigurationRequest getConfigurationRequest = new GetConfigurationRequest();

		// Get MQTT Mappings
		getConfigurationRequest.setKeyPath(Arrays.asList(MAPPINGS));
		GetConfigurationResponse response = ipcClient.getConfiguration(getConfigurationRequest, Optional.empty())
				.getResponse().get();
		localTopic = (String) response.getValue().get(LOCAL_TOPIC);

		// Get Connection Information
		getConfigurationRequest.setKeyPath(Arrays.asList(CONNECTION_INFO));
		response = ipcClient.getConfiguration(getConfigurationRequest, Optional.empty()).getResponse().get();
		httpPort = (Double) response.getValue().get(HTTP_PORT);
		httpRootContext = (String) response.getValue().get(HTTP_ROOT_CONTEXT);
		brokerUri = (String) response.getValue().get(BROKER_URI);
		username = (String) response.getValue().get(USERNAME);
		password = (String) response.getValue().get(PASSWORD);
		clientId = (String) response.getValue().get(CLIENT_ID);

		// Create our MQTT Client and connect
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
				} catch (InterruptedException | MqttException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}

			@Override
			public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
				// Instead of publishing to IoT Core, you could instead write into a Stream
				// Manager stream
				// and have that data sent to Kinesis, IoT Analytics, or more
				//
				// Assuming you had created a stream manager client, then:
				// streamManagerClient.appendMessage("streamName", mqttMessage.getPayload());
				//
				System.out.println("Local Message Arrived... Ignoring");
			}

			@Override
			public void deliveryComplete(IMqttDeliveryToken t) {
				System.out.println("Message delivery complete - " + t.toString());
			}
		});
		

		// Create HTTP Server
		server = HttpServer.create(new InetSocketAddress(httpPort.intValue()), 0);
		server.createContext(httpRootContext, new httpHandler(mqttClient, localTopic));
		server.setExecutor(null); // creates a default executor
		server.start();

	}

	static class httpHandler implements HttpHandler {
		
		private MqttClient mqttClient;
		private String localTopic;
		
		public httpHandler(MqttClient client, String topic) {
			this.mqttClient = client;
			this.localTopic = topic;
		}

		public void handle(HttpExchange he) throws IOException {
			System.out.println("HTTP Message Received");

			// Serve for POST requests only
			if (he.getRequestMethod().equalsIgnoreCase("POST")) {

				try {

					Headers requestHeaders = he.getRequestHeaders();
					int contentLength = Integer.parseInt(requestHeaders.getFirst("Content-length"));
					byte[] message = new byte[contentLength];
					
					//InputStream is = he.getRequestBody();
					//int length = is.read(message);
					
					he.getRequestBody().read(message);

					// Forward message to local broker
					System.out.println("Forwarding message to local broker");
					try {
						mqttClient.publish(localTopic, new MqttMessage(message));
					} catch (MqttException e) {
						// Handle publish errors
						System.out.println("Publish Error: " + e.getMessage());
						e.printStackTrace();
					}

					// Send RESPONSE
					byte[] response = "{\"result\":\"SENT\"}".getBytes();
					he.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);

					OutputStream os = he.getResponseBody();
					os.write(response);

					he.close();

				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	@Override
	public void close() throws IOException {
		server.stop(0);
	}
}
