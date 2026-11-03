package com.dsmnh.mobile.backend.net;

import com.dsmnh.mobile.backend.core.ServerContext;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/** 单平面 TCP 服务器：帧编解码 + 180 秒读空闲 + 协议状态机；并发连接上限按配置。 */
public final class ProtocolServer {
    private static final Logger LOG = LoggerFactory.getLogger(ProtocolServer.class);
    public static final int IDLE_TIMEOUT_SEC = 180;

    private final ServerContext ctx;
    private final Plane plane;
    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup();
    private final AtomicInteger activeCount = new AtomicInteger();
    private Channel listenChannel;

    public ProtocolServer(ServerContext ctx, Plane plane) {
        this.ctx = ctx;
        this.plane = plane;
    }

    /** 绑定并返回实际端口（port=0 时为系统分配端口）。 */
    public int start(int port) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new FrameDecoder())
                                .addLast(new FrameEncoder())
                                .addLast(new IdleStateHandler(IDLE_TIMEOUT_SEC, 0, 0))
                                .addLast(new ProtocolHandler(ctx, plane, activeCount));
                    }
                });
        listenChannel = bootstrap.bind(port).sync().channel();
        int bound = ((java.net.InetSocketAddress) listenChannel.localAddress()).getPort();
        LOG.info("{} 平面监听 0.0.0.0:{}（协议 v{}）", plane.wire, bound, ProtocolHandler.PROTOCOL_VERSION);
        return bound;
    }

    public void stop() {
        if (listenChannel != null) {
            listenChannel.close();
        }
        boss.shutdownGracefully();
        worker.shutdownGracefully();
    }
}
