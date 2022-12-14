package com.jd.blockchain.kvdb.server;

import com.jd.blockchain.kvdb.protocol.KVDBConnectionHandler;
import com.jd.blockchain.kvdb.protocol.KVDBDecoder;
import com.jd.blockchain.kvdb.protocol.KVDBEncoder;
import com.jd.blockchain.kvdb.protocol.KVDBHandler;
import com.jd.blockchain.kvdb.protocol.KVDBInitializerHandler;
import com.jd.blockchain.kvdb.protocol.exception.KVDBException;
import com.jd.blockchain.kvdb.protocol.proto.Command;
import com.jd.blockchain.kvdb.protocol.proto.Message;
import com.jd.blockchain.kvdb.protocol.proto.impl.KVDBMessage;
import com.jd.blockchain.kvdb.server.executor.Executor;
import com.jd.blockchain.kvdb.server.executor.KVDBExecutor;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Log4J2LoggerFactory;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Set;

import static com.jd.blockchain.kvdb.protocol.proto.Command.CommandType.CLUSTER_INFO;

public class KVDBServer implements KVDBHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(KVDBServer.class);

    private final KVDBServerContext serverContext;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture future;
    private ChannelFuture managerFuture;
    private ClusterService clusterService;

    /**
     * Whether this server is ready to service.
     * After cluster confirmed ready will be set to true.
     */
    private boolean ready = false;

    public KVDBServer(KVDBServerContext serverContext) throws InstantiationException, IllegalAccessException {
        this.serverContext = serverContext;
        bindExecutors();
        this.clusterService = new ClusterService(serverContext);
    }

    private void bindExecutors() throws IllegalAccessException, InstantiationException {
        Set<Class<?>> clazzes = new Reflections("com.jd.blockchain.kvdb.server.executor").getTypesAnnotatedWith(KVDBExecutor.class);
        for (Class clazz : clazzes) {
            KVDBExecutor executor = (KVDBExecutor) clazz.getAnnotation(KVDBExecutor.class);
            serverContext.addExecutor(executor.command(), (Executor) clazz.newInstance());
        }
    }

    public void start() throws KVDBException {
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);

        ServerBootstrap bootstrap = new ServerBootstrap();
        InternalLoggerFactory.setDefaultFactory(Log4J2LoggerFactory.INSTANCE);
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new KVDBInitializerHandler(this))
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

        future = bootstrap.bind(serverContext.getConfig().getKvdbConfig().getHost(), serverContext.getConfig().getKvdbConfig().getPort());
        future.syncUninterruptibly();

        managerFuture = bootstrap.bind("127.0.0.1", serverContext.getConfig().getKvdbConfig().getManagerPort());
        managerFuture.syncUninterruptibly();

        // Confirm cluster settings
        clusterService.confirm();

        LOGGER.info("server started: {}:{}", serverContext.getConfig().getKvdbConfig().getHost(), serverContext.getConfig().getKvdbConfig().getPort());

        ready = true;
    }

    public void stop() {
        try {
            if (future != null) {
                closeFuture(future.channel().close());
            }
            future = null;
            if (managerFuture != null) {
                closeFuture(managerFuture.channel().close());
            }
            managerFuture = null;
        } finally {
            workerGroup = closeWorker(workerGroup);
            bossGroup = closeWorker(bossGroup);
        }

        serverContext.stop();

        LOGGER.info("server stopped");
    }

    private void closeFuture(Future<?> future) {
        LOGGER.debug("closing future");
        future.syncUninterruptibly();
        LOGGER.debug("future closed");
    }

    private EventLoopGroup closeWorker(EventLoopGroup worker) {
        if (worker != null) {
            closeFuture(worker.shutdownGracefully());
        }
        return null;
    }

    private String sourceKey(Channel channel) {
        InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
        return remoteAddress.getHostName() + ":" + remoteAddress.getPort();
    }

    public void channel(SocketChannel channel) {
        LOGGER.debug("new channel: {}", sourceKey(channel));

        channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4))
                .addLast("kvdbDecoder", new KVDBDecoder())
                .addLast(new LengthFieldPrepender(4, 0, false))
                .addLast("kvdbEncoder", new KVDBEncoder())
                .addLast(new KVDBConnectionHandler(this));
    }

    public void connected(ChannelHandlerContext ctx) {
        String sourceKey = sourceKey(ctx.channel());
        LOGGER.debug("client connected: {}", sourceKey);
        getSession(ctx, sourceKey);
    }

    private Session getSession(ChannelHandlerContext ctx, String sourceKey) {
        return serverContext.getSession(sourceKey, key -> new KVDBSession(key, ctx));
    }

    public void disconnected(ChannelHandlerContext ctx) {
        String sourceKey = sourceKey(ctx.channel());

        LOGGER.debug("client disconnected: {}", sourceKey);

        serverContext.removeSession(sourceKey);
    }

    public void receive(ChannelHandlerContext ctx, Message message) {
        String sourceKey = sourceKey(ctx.channel());

        LOGGER.debug("message received: {}", sourceKey);

        Command command = (Command) message.getContent();

        // ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        if (!ready && command.getName().equals(CLUSTER_INFO.getCommand())) {
            serverContext.processCommand(sourceKey, message);
        } else {
            // ???????????????IP??????????????????????????????????????????????????????????????????????????????
            int serverPort = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
            if (Command.CommandType.getCommand(command.getName()).isOpen()
                    || serverPort == serverContext.getConfig().getKvdbConfig().getManagerPort()) {
                serverContext.processCommand(sourceKey, message);
            } else {
                ctx.writeAndFlush(KVDBMessage.error(message.getId(), "un support command"));
            }
        }
    }

}
