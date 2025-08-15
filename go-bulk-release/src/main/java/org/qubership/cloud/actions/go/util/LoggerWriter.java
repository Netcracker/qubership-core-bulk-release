package org.qubership.cloud.actions.go.util;

import lombok.extern.slf4j.Slf4j;

import java.io.Writer;

@Slf4j
public class LoggerWriter extends Writer {
    private final StringBuilder buf = new StringBuilder();

    @Override
    public void write(char[] cbuf, int off, int len) {
        for (int i = off; i < off + len; i++) {
            char c = cbuf[i];
            if (c == '\r') continue;
            if (c == '\n') {
                log.info(buf.toString());
                buf.setLength(0);
            } else buf.append(c);
        }
    }

    @Override
    public void flush() {
        if (!buf.isEmpty()) {
            log.info(buf.toString());
            buf.setLength(0);
        }
    }

    @Override
    public void close() {
        flush();
    }
}