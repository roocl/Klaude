package io.klaude.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NdjsonServer implements AutoCloseable {
    private final String host;
    private final int requestedPort;
    private final int maxLineBytes;
    private final NdjsonLineHandler handler;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Channel> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong connectionIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Channel serverChannel;
    private volatile int boundPort = -1;

    // 初始化 Netty NDJSON server 的地址、行长限制和异步 handler
    public NdjsonServer(
            String host, int port, int maxLineBytes, NdjsonLineHandler handler) {
        this.host = host;
        this.requestedPort = port;
        this.maxLineBytes = maxLineBytes;
        this.handler = handler;
    }

    // 异步绑定 TCP 地址并在成功后暴露实际端口
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    // 为每个客户端安装独立的 UTF-8 NDJSON pipeline
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        clients.add(channel);
                        channel.closeFuture().addListener(ignored -> clients.remove(channel));
                        NdjsonConnection connection = new NdjsonConnection(
                                "connection-" + connectionIds.incrementAndGet(), channel);
                        channel.pipeline()
                                .addLast(new LineBasedFrameDecoder(maxLineBytes, true, true))
                                .addLast(new StringDecoder(CharsetUtil.UTF_8))
                                .addLast(new StringEncoder(CharsetUtil.UTF_8))
                                .addLast(new SimpleChannelInboundHandler<String>() {
                                    // 将完整行提交到 virtual thread，避免阻塞 Netty event loop
                                    @Override
                                    protected void channelRead0(
                                            io.netty.channel.ChannelHandlerContext context,
                                            String line) {
                                        handlers.submit(() -> invokeHandler(connection, line));
                                    }

                                    // 发生 framing 或 channel 异常时关闭当前连接
                                    @Override
                                    public void exceptionCaught(
                                            io.netty.channel.ChannelHandlerContext context,
                                            Throwable cause) {
                                        if (cause instanceof TooLongFrameException) {
                                            handlers.submit(() -> invokeOversized(connection));
                                        } else {
                                            context.close();
                                        }
                                    }
                                });
                    }
                });
        bootstrap.bind(host, requestedPort).addListener(future -> {
            if (!future.isSuccess()) {
                completion.completeExceptionally(future.cause());
                return;
            }
            serverChannel = ((io.netty.channel.ChannelFuture) future).channel();
            boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            completion.complete(null);
        });
        return completion;
    }

    // 调用业务 handler 并在未处理异常时关闭故障连接
    private void invokeHandler(NdjsonConnection connection, String line) {
        try {
            handler.handle(connection, line).whenComplete((ignored, error) -> {
                if (error != null) {
                    connection.close();
                }
            });
        } catch (Exception error) {
            connection.close();
        }
    }

    // 调用 oversized 回调并在写回完成后关闭故障连接
    private void invokeOversized(NdjsonConnection connection) {
        try {
            handler.handleOversized(connection)
                    .whenComplete((ignored, error) -> connection.close());
        } catch (Exception error) {
            connection.close();
        }
    }

    // 返回绑定成功后的实际 TCP 端口
    public int port() {
        if (boundPort < 0) {
            throw new IllegalStateException("server is not started");
        }
        return boundPort;
    }

    // 关闭监听与客户端 channel，并等待 event loops 和 virtual threads 结束
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Channel client : Set.copyOf(clients)) {
            client.close().syncUninterruptibly();
        }
        Channel listening = serverChannel;
        if (listening != null) {
            listening.close().syncUninterruptibly();
        }
        handlers.close();
        workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
