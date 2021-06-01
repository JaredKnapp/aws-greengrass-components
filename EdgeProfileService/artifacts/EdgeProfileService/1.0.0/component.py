import sys
import os
import time
import json
import boto3
import zipfile

import shlex
import subprocess

import socket

import awsiot.greengrasscoreipc
import awsiot.greengrasscoreipc.client as client
from awsiot.greengrasscoreipc.model import (
    IoTCoreMessage,
    QOS,
    PublishToIoTCoreRequest,
    SubscribeToIoTCoreRequest
)

TIMEOUT = 10

thingName = sys.argv[1]
commandTopic = sys.argv[2];
responseTopic = sys.argv[3];
s3Bucket = sys.argv[4];
outDirectory = sys.argv[5]

print("Starting EdgeProfileService Component\n=====================================")
print("Arguments:")
print(f"thingName      ='{thingName}'")
print(f"commandTopic   ='{commandTopic}'")
print(f"responseTopic  ='{responseTopic}'")
print(f"s3Bucket       ='{s3Bucket}'")
print(f"outDirectory   ='{outDirectory}'")

def publish_reply(status, message):
    message = {
        "status": status, 
        "device": thingName,
        "location": socket.gethostname(), 
        "message": message
        }
    request = PublishToIoTCoreRequest()
    request.topic_name = responseTopic
    messages = json.dumps(message)
    request.payload = bytes(messages, "utf-8")
    request.qos = QOS.AT_LEAST_ONCE

    try: 
        operation = ipc_client.new_publish_to_iot_core()
        operation.activate(request)
        future = operation.get_response()
        future.result(0)
    
    except:
        print("Error:", sys.exc_info()[0])

class StreamHandler(client.SubscribeToIoTCoreStreamHandler):
    def __init__(self):
        super().__init__()

    def on_stream_event(self, event: IoTCoreMessage) -> None:
        try:
            
            message = str(event.message.payload, "utf-8")
            messageObj = json.loads(message);
            print("received message:", messageObj["command"])
            
            if messageObj["command"]=="ping" :
                publish_reply(200, "pong");
                
            elif messageObj["command"]=="deploy" :
                
                with zipfile.ZipFile("package.zip", 'r') as zip_ref:
                    zip_ref.extractall(outDirectory)
                sargs = shlex.split(f"/usr/bin/docker-compose -f {outDirectory}/docker-compose.yaml up -d");
                result = subprocess.run(sargs, capture_output=True, text=True);
                print("stdout:", result.stdout)
                print("stderr:", result.stderr)

                publish_reply(200, "Deployed...")

            elif messageObj["command"]=="stop" :
                commandline = f"/usr/bin/docker-compose -f {outDirectory}/docker-compose.yaml down"
                sargs = shlex.split(commandline);
                result = subprocess.run(sargs, capture_output=True, text=True);
                print("stdout:", result.stdout)
                print("stderr:", result.stderr)

                publish_reply(200, "Stopped...")

            elif messageObj["command"]=="start" :
                commandline = f"/usr/bin/docker-compose -f {outDirectory}/docker-compose.yaml up -d"
                sargs = shlex.split(commandline);
                result = subprocess.run(sargs, capture_output=True, text=True);
                print("stdout:", result.stdout)
                print("stderr:", result.stderr)

                publish_reply(200, "Started...")

                
            elif messageObj["command"]=="remove" :
                commandline = f"rm -rfv {outDirectory}"
                sargs = shlex.split(commandline);
                result = subprocess.run(sargs, capture_output=True, text=True);
                print("stdout:", result.stdout)
                print("stderr:", result.stderr)

                publish_reply(200, "Removed...");
                
            else :
                publish_reply(404, "Unknown request: " + messageObj["command"]);

        except:
            publish_reply(500, "Error: " + sys.exc_info()[0])
            print("Error:", sys.exc_info()[0])

    def on_stream_error(self, error: Exception) -> bool:
        print("on_stream_error: Exception: {0}".format(error))
        return True  # Return True to close stream, False to keep stream open.

    def on_stream_closed(self) -> None:
        # Handle close.
        pass


try:
    ipc_client = awsiot.greengrasscoreipc.connect()
    print("Created IoT Greengrass IPC client")

    s3 = boto3.client('s3')
    print("Created Boto3 S3 client")
    
except Exception as e:
    print("Failed to create clients. Error: " + str(e))
    publish_reply(500, "Failed to create Greengrass clients. Error: " + str(e))
    exit(1)

request = SubscribeToIoTCoreRequest()
request.topic_name = commandTopic
request.qos = QOS.AT_MOST_ONCE
operation = ipc_client.new_subscribe_to_iot_core(StreamHandler())
future = operation.activate(request)
future.result(TIMEOUT)

## Say that we're ready
publish_reply(200, "ready")

# Keep the main thread alive, or the process will exit.
while True:
    time.sleep(10)
                  
# To stop subscribing, close the operation stream.
operation.close()
