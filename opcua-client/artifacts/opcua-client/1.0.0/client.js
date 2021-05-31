const async = require("async");
const { OPCUAClient, AttributeIds, TimestampsToReturn } = require("node-opcua-client");
const { GreengrassV2Client } = require("@aws-sdk/client-greengrassv2");
const { mqtt, auth, http, io, iot } = require('aws-iot-device-sdk-v2');

const config = {
    "servers": [{
        "name": "wjk",
        "endpoint": "opc.tcp://ip-172-31-19-2.ec2.internal:8080/UA/Server",
        "topic-prefix": "wjk",
        "variables": [
            { "nodeId": "ns=0;i=2258", "topic": "/server/currenttime" },
            { "nodeId": "ns=1;i=1001", "topic": "/device/counter" },
            { "nodeId": "ns=1;i=1002", "topic": "/device/freemem" },
            { "nodeId": "ns=1;i=1003", "topic": "/device/loadavg1" }
        ]
    }]
};

class ConnectedClient {

    constructor(server) {
        this.server = server;
        this.subscriptions = [];

        async.series(
            [

                //Step1: Connect
                (callback) => {

                    this.client = OPCUAClient.create({
                        endpointMustExist: false
                    });

                    this.client.on("backoff", (retry, delay) =>
                        this.log("still trying to connect to ", this.server.endpoint, ": retry =", retry, "next attempt in ", (delay / 1000), "seconds")
                    );

                    this.client.connect(server.endpoint).then(() => {
                        this.log("Connected to ", server.endpoint);
                        callback();
                    }).catch((err) => callback(this.server.name + ": " + err));

                },

                //Step2: Create Session
                (callback) => {
                    this.client.createSession().then((result) => {
                        this.session = result;
                        callback();
                    }).catch(err => callback(this.server.name + ": " + err));
                },

                //Step3: Create Subscription to Variables
                (callback) => {
                    const subscriptionOptions = {
                        publishingEnabled: true,
                    };
                    this.session.createSubscription2(subscriptionOptions)
                        .then(subscription => {

                            this.subscriptions.push(subscription);

                            subscription
                                .on("started", () => {
                                    this.log("subscription started - subscriptionId=", subscription.subscriptionId);
                                })
                                .on("keepalive", () => {
                                    this.log("subscription keepalive");
                                })
                                .on("terminated", () => {
                                    this.log("subscription terminated");
                                });

                            this.server.variables.forEach((variable) => {

                                const itemToMonitor = {
                                    nodeId: variable.nodeId,
                                    attributeId: AttributeIds.Value
                                };

                                const monitoringParamaters = {
                                    samplingInterval: 100,
                                    queueSize: 10,
                                    discardOldest: true,
                                };

                                subscription.monitor(itemToMonitor, monitoringParamaters, TimestampsToReturn.Both)
                                    .then(monitoredItem => {
                                        monitoredItem.on("changed", (dataValue) => {
                                            this.log(variable.topic, ": monitored changed: ", dataValue.value.value.toString());
                                        });
                                    })
                                    .catch(err => this.log("Error creating subscription: ", err));
                            });
                        }).catch(err => {
                            this.log("ERROR: ", err);
                        });

                    callback();
                }
            ],
            (err) => {
                if (err) {
                    this.log("Error creating client: ", err);
                }
            }
        );
    }

    disconnect() {

        async.series(
            [
                //Step 1: Close Subscriptions
                (callback) => {
                    const self = this;
                    while (this.subscriptions.length > 0) {
                        this.subscriptions.pop().terminate().then(() => self.log("Terminated"));
                    }
                    callback();
                },

                //Step2: Close Session
                (callback) => {
                    if (this.session) {
                        this.session.close().then(callback).catch((err) => {
                            callback(this.server.name + ": " + err);
                        });
                    }
                    else {
                        callback();
                    }
                },
            ],
            (err) => {
                if (err) {
                    this.log("Error disconnecting client: ", err);
                }
                this.client && this.client.disconnect(() => {
                    this.log("Disconnected.");
                });
            }
        );

    }

    log(...args) {
        console.log(this.server.name + ": ", ...args);
    }
}

var clients = [];

//const ggclient = new GreengrassV2Client();
//console.log(ggclient);

//const client_bootstrap = new io.ClientBootstrap();
//var config_builder = iot.AwsIotMqttConnectionConfigBuilder.new_mtls_builder_from_path(argv.cert, argv.key);
//console.log(config_builder);


/*
console.log("Creating Clients...");
config.servers.forEach((server) => {
    //Connect to Client
    clients.push(new ConnectedClient(server));
});

process.on('SIGINT', () => {
    console.log("\nDisconnecting Clients...");
    while (clients.length > 0) {
        clients.pop().disconnect();
    }
});
*/