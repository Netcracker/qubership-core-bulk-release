package org.qubership.cloud.actions.go;

import org.qubership.cloud.actions.go.proxy.GoModule;
import org.qubership.cloud.actions.go.proxy.GoProxy;
import org.qubership.cloud.actions.go.proxy.GoProxyPublisher;

import java.nio.file.Path;
import java.nio.file.Paths;

public class TestMain {
    public static void main(String[] args) throws Exception {
//        GoProxy.enableGoProxy();
//        CommandRunner.runCommand("go", "env", "GOPROXY");

//        Path path = Paths.get("C:\\git\\netcracker\\go\\qubership-core-lib-go-actuator-common");
//        GoModule goModule = new GoModule(path);
//        goModule.get("github.com/netcracker/qubership-core-lib-go/v3@v3.3.3");

//        Path path = Paths.get("C:\\git\\netcracker\\go\\qubership-core-lib-go");
//        Path goProxy = Paths.get("\\\\wsl.localhost\\Ubuntu-24.04\\home\\user\\bulk_release\\GOPROXY");
//        GoProxyPublisher.publishToLocalGoProxy(path, "v3.0.2", goProxy, null);

        GoProxy.enableGoProxy();

        Path path = Paths.get("C:\\git\\netcracker\\go\\qubership-core-lib-go-dbaas-postgres-client");
        GoModule goModule = new GoModule(path);
        goModule.get("github.com/netcracker/qubership-core-lib-go-actuator-common/v2@v2.0.1");
    }
}
