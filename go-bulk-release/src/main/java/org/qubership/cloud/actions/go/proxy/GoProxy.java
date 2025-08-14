package org.qubership.cloud.actions.go.proxy;

import org.qubership.cloud.actions.go.util.CommandRunner;

public class GoProxy {
    public static void enableGoProxy() {
        String[][] commands = {
                //todo vlla change to real one
                {"go", "env", "-w", "GOPROXY=file:///Z:/home/user/bulk_release/GOPROXY,https://proxy.golang.org,direct"},
                {"go", "env", "-w", "GONOPROXY="},
                {"go", "env", "-w", "GONOSUMDB=github.com/netcracker/*,github.com/taurmorchant/*"},
                {"go", "env", "-w", "GOPRIVATE="}
        };
        for (String[] cmd : commands) {
            CommandRunner.runCommand(cmd);
        }
    }
}
