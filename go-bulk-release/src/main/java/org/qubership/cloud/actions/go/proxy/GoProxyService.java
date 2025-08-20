package org.qubership.cloud.actions.go.proxy;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.Config;
import org.qubership.cloud.actions.go.model.RepositoryInfo;
import org.qubership.cloud.actions.go.util.CommandRunner;

@Slf4j
public class GoProxyService {
    private final Config config;

    public GoProxyService(Config config) {
        this.config = config;
    }

    public void enableGoProxy() {
        String goproxy = String.format("GOPROXY=file://%s,https://proxy.golang.org,direct", config.getGoProxyDir());
        String[][] commands = {
                {"go", "env", "-w", goproxy},
                {"go", "env", "-w", "GONOPROXY="},
                {"go", "env", "-w", "GONOSUMDB=github.com/netcracker/*,github.com/taurmorchant/*,github.com/vlla-test-organization/*"},
                {"go", "env", "-w", "GOPRIVATE="}
        };
        //todo VLLA remove extra GONOSUMDB
        for (String[] cmd : commands) {
            CommandRunner.runCommand(cmd);
        }
    }

    public void publishToLocalGoProxy(RepositoryInfo srcRepo, String version, String proxyRoot) {
        String[] command = {"./gopack", "-out", proxyRoot, "-src", srcRepo.getRepositoryDirFile().getAbsolutePath(), "-version", version};
        CommandRunner.runCommand(null, command);
    }
}

