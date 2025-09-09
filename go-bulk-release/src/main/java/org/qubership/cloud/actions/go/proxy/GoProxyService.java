package org.qubership.cloud.actions.go.proxy;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.Config;
import org.qubership.cloud.actions.go.model.ReleaseTerminationException;
import org.qubership.cloud.actions.go.model.repository.RepositoryInfo;
import org.qubership.cloud.actions.go.util.CommandExecutionException;
import org.qubership.cloud.actions.go.util.CommandRunner;

@Slf4j
public class GoProxyService {
    private final Config config;

    public GoProxyService(Config config) {
        this.config = config;
    }

    public void enableGoProxy() {
        try {
            String goProxyDir;
            if (config.getGoProxyDir().startsWith("/")) {
                goProxyDir = config.getGoProxyDir().substring(1);
            } else {
                goProxyDir = config.getGoProxyDir();
            }
            String goproxy = String.format("GOPROXY=file:///%s,https://proxy.golang.org,direct", goProxyDir);
            String[][] commands = {
                    {"go", "env", "-w", goproxy},
                    {"go", "env", "-w", "GONOPROXY="},
                    {"go", "env", "-w", "GONOSUMDB=github.com/netcracker/*,github.com/taurmorchant/*,github.com/vlla-test-organization/*"},
                    {"go", "env", "-w", "GOPRIVATE="}
            };
            //todo VLLA remove extra GONOSUMDB
            for (String[] cmd : commands) {
                CommandRunner.exec(cmd);
            }
        } catch (CommandExecutionException e) {
            throw new ReleaseTerminationException("Cannot enable GOPROXY", e);
        }
    }

    public void publishToLocalGoProxy(RepositoryInfo srcRepo, String version, String proxyRoot) {
        String modulePath = srcRepo.getRepositoryDirFile().getAbsolutePath();
        try {
            String[] command = {"go-pack", "-out", proxyRoot, "-src", modulePath, "-version", version};
            CommandRunner.exec(null, command);
        } catch (CommandExecutionException e) {
            String msg = "Cannot publish module '%s' to local go proxy".formatted(modulePath);
            throw new ReleaseTerminationException(msg, e);
        }
    }
}

