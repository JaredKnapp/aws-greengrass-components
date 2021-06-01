package com.aws.iotcore.httpdatasource;

import java.io.IOException;

import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPC;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ReportedLifecycleState;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;

public class Main {

	private static EventStreamRPCConnection connection;
	private static GreengrassCoreIPC client;
	private static Worker worker;

	public static void main(String[] args) {
		try {

			System.out.println("HttpDataSource: Starting application...");

			connection = IPCUtil.getEventStreamRpcConnection();
			client = new GreengrassCoreIPCClient(connection);

			System.out.println("HttpDataSource: Starting client");
			worker = new Worker(client);
			worker.startup();

			IPCUtil.reportState(client, ReportedLifecycleState.RUNNING);

			System.out.println("HttpDataSource: Waiting");
			// Just keep the main thread alive here.
			while (!Thread.currentThread().isInterrupted()) {
				Thread.sleep(100_000);
			}

			System.out.println("HttpDataSource: Main Thread exiting...");

		} catch (Throwable e) {
			e.printStackTrace();
			System.exit(1);
		} finally {
			if (connection != null) {
				connection.close();
			}
			if (worker != null) {
				try {
					worker.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		System.out.println("HttpDataSource: Application has exited");
	}
}
