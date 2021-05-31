var os = require('os');
const { OPCUAServer, Variant, DataType } = require("node-opcua");

const config = {
    port: 8080,
    resourcePath: "/UA/Server",
    deviceName: "Device"
};

(async() => {

    // Let's create an instance of OPCUAServer
    const server = new OPCUAServer({
        port: config.port, // the port of the listening socket of the server
        resourcePath: config.resourcePath, // this path will be added to the endpoint resource name
        buildInfo: {
            productName: "Server",
            buildNumber: "1",
            buildDate: new Date(2021, 5, 20)
        }
    });
    await server.initialize();

    const addressSpace = server.engine.addressSpace;
    const namespace = addressSpace.getOwnNamespace();

    // declare a new object
    const device = namespace.addObject({
        organizedBy: addressSpace.rootFolder.objects,
        browseName: config.deviceName
    });

    // add some variables
    // add a variable named MyVariable1 to the newly created folder "MyDevice"
    let var_counter = 1;
    setInterval(() => { var_counter += 1; }, 1000);
    namespace.addVariable({
        componentOf: device,
        browseName: "Counter",
        dataType: "Double",
        value: {
            get: () => new Variant({ dataType: DataType.Double, value: var_counter })
        }
    });

    namespace.addVariable({
        componentOf: device,
        browseName: "FreeMem",
        dataType: "Double",
        value: {
            get: () => new Variant({ dataType: DataType.Double, value: os.freemem() })
        }
    });


    namespace.addVariable({
        componentOf: device,
        browseName: "LoadAvg1",
        dataType: "Double",
        value: {
            get: () => new Variant({ dataType: DataType.Double, value: os.loadavg()[0] })
        }
    });
    
    namespace.addVariable({
        componentOf: device,
        browseName: "LoadAvg5",
        dataType: "Double",
        value: {
            get: () => new Variant({ dataType: DataType.Double, value: os.loadavg()[1] })
        }
    });
    
    namespace.addVariable({
        componentOf: device,
        browseName: "LoadAvg15",
        dataType: "Double",
        value: {
            get: () => new Variant({ dataType: DataType.Double, value: os.loadavg()[2] })
        }
    });

    server.start(function() {
        console.log("Server is now listening ... ( press CTRL+C to stop)");
        console.log("port ", server.endpoints[0].port);
        const endpointUrl = server.endpoints[0].endpointDescriptions()[0].endpointUrl;
        console.log(" the primary server endpoint url is ", endpointUrl);
    });

})();
