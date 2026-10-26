package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StdioTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STDERR_LINES = 200;

    private final Process process;
    private final BufferedWriter stdin;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> stderrRing = new ArrayDeque<>();
    private final Object stderrLock = new Object();
    private volatile boolean closed;

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path workingDir) throws IOException {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            commandLine.addAll(args);
        }
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        if (env != null && !env.isEmpty()) { // 启动这个 MCP server 时，额外塞给它的环境变量，如给 server 传它需要的 API Key、token、路径等（例如 {"API_KEY": "xxx"}）
            builder.environment().putAll(env);
        }
        this.process = builder.start(); // Mcp Server 子进程
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)); // process.getOutputStream() = 是父进程和子进程之间的一条通信管道，方法名按"父进程视角"命名——即"父进程的输出管道"，它接到子进程的 stdin
        startStdoutReader(); //  起一个后台线程，持续读子进程的 stdout（getInputStream()），每读到一行就解析成 JSON-RPC 消息，分发给监听器。这是接收 MCP server 响应/通知的主通道
        startStderrReader(); // 起一个后台线程，持续读子进程的 stderr（getErrorStream()），把输出存进一个环形缓冲（最多 200 行）。这是收 server 的日志/报错，供 /mcp logs 排错用
    }

    @Override
    public synchronized void send(JsonNode message) throws IOException {
        if (closed) {
            throw new IOException("MCP stdio transport already closed");
        }
        stdin.write(MAPPER.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public List<String> stderrLines() {
        synchronized (stderrLock) {
            return List.copyOf(stderrRing);
        }
    }

    @Override
    public Long processId() {
        return process.pid();
    }

    @Override
    public String transportName() {
        return "stdio";
    }

    @Override
    public void close() {
        closed = true;
        // 关 stdin 让子进程读到 EOF，给一次优雅退出窗口（1 秒）。
        // shutdown 通知由 McpClient.close 在调本方法之前发出，子进程拿到 EOF 后通常会立即退出。
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        try {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return;
        }
        // 优雅窗口超时，发 SIGTERM
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
    // 子进程stdout->java,java读json响应
    private void startStdoutReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode message = MAPPER.readTree(line);
                    for (Consumer<JsonNode> listener : listeners) {
                        listener.accept(message);
                    }
                }
            } catch (Exception e) {
                appendStderr("[paicli] stdout reader stopped: " + e.getMessage());
            }
        }, "paicli-mcp-stdio-stdout");
        thread.setDaemon(true);
        thread.start();
    }

    private void startStderrReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (IOException e) {
                appendStderr("[paicli] stderr reader stopped: " + e.getMessage());
            }
        }, "paicli-mcp-stdio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void appendStderr(String line) {
        synchronized (stderrLock) {
            while (stderrRing.size() >= MAX_STDERR_LINES) {
                stderrRing.removeFirst();
            }
            stderrRing.addLast(line);
        }
    }
}
