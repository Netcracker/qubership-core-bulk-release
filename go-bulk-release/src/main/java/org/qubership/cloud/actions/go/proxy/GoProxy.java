package org.qubership.cloud.actions.go.proxy;

import lombok.extern.slf4j.Slf4j;
import org.qubership.cloud.actions.go.util.CommandRunner;

@Slf4j
public class GoProxy {

    public static void enableGoProxy() {
        //todo vlla just fo local dev
        String osName = System.getProperty("os.name").toLowerCase();
        log.debug("os.name = {}", osName);
        if (osName.contains("win")) {
            String[][] commands = {
                    //todo vlla change to real one
                    {"go", "env", "-w", "GOPROXY=file:///Z:/home/user/bulk_release/GOPROXY,https://proxy.golang.org,direct"},
                    {"go", "env", "-w", "GONOPROXY="},
                    {"go", "env", "-w", "GONOSUMDB=github.com/netcracker/*,github.com/taurmorchant/*,github.com/vlla-test-organization/*"},
                    {"go", "env", "-w", "GOPRIVATE="}
            };
            for (String[] cmd : commands) {
                CommandRunner.runCommand(cmd);
            }
        }
        else {
            String[][] commands = {
                    {"go", "env", "-w", "GOPROXY=file:///tmp/GOPROXY,https://proxy.golang.org,direct"},
                    {"go", "env", "-w", "GONOPROXY="},
                    {"go", "env", "-w", "GONOSUMDB=github.com/netcracker/*,github.com/taurmorchant/*"},
                    {"go", "env", "-w", "GOPRIVATE="}
            };
            for (String[] cmd : commands) {
                CommandRunner.runCommand(cmd);
            }
        }
    }
}
