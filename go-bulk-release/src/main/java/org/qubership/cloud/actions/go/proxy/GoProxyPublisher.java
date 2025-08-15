package org.qubership.cloud.actions.go.proxy;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.model.RepositoryInfo;
import org.qubership.cloud.actions.go.util.CommandRunner;

@Slf4j
public class GoProxyPublisher {

    public static void publishToLocalGoProxy(RepositoryInfo srcRepo,
                                             String version,
                                             String proxyRoot) {
        String[] command = {"./gopack", "-out", proxyRoot, "-src", srcRepo.getRepositoryDirFile().getAbsolutePath(), "-version", version};
        CommandRunner.runCommand(null, command);
    }
}

