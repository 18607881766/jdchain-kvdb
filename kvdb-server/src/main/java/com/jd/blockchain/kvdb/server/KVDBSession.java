package com.jd.blockchain.kvdb.server;

import com.jd.blockchain.kvdb.KVDBInstance;
import com.jd.blockchain.kvdb.KVWriteBatch;
import com.jd.blockchain.kvdb.protocol.exception.KVDBException;
import com.jd.blockchain.kvdb.protocol.proto.Message;
import com.jd.blockchain.kvdb.server.wal.RedoLog;
import com.jd.blockchain.kvdb.server.wal.WalEntity;
import com.jd.blockchain.kvdb.server.wal.WalKV;
import com.jd.blockchain.utils.Bytes;
import io.netty.channel.ChannelHandlerContext;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 连接会话
 */
public class KVDBSession implements Session {
    // 最大batch数量
    private static final int MAX_BATCH_SIZE = 10000000;
    // 会话ID
    private final String id;
    // Channel上下文
    private final ChannelHandlerContext ctx;
    // 当前数据库实例名称
    private String dbName;
    // 当前数据库实例
    private KVDBInstance instance;
    // 批处理模式
    private volatile boolean batchMode;
    // 待提交批处理数据集
    private HashMap<Bytes, byte[]> batch;
    // WAL
    private RedoLog wal;

    public KVDBSession(String id, ChannelHandlerContext ctx, RedoLog wal) {
        this.id = id;
        this.ctx = ctx;
        this.wal = wal;
        this.batch = new HashMap<>();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setDB(String dbName, KVDBInstance instance) {
        batchAbort();
        this.dbName = dbName;
        this.instance = instance;
    }

    @Override
    public KVDBInstance getDBInstance() {
        return instance;
    }

    @Override
    public String getDBName() {
        return dbName;
    }

    @Override
    public void publish(Message msg) {
        if (null != msg) {
            ctx.writeAndFlush(msg);
        }
    }

    @Override
    public void close() {
        if (null != batch) {
            batch.clear();
        }
        batch = null;
        if (null != ctx) {
            ctx.close();
        }
    }

    @Override
    public boolean batchMode() {
        return batchMode;
    }

    /**
     * 开启批处理操作，幂等
     */
    @Override
    public void batchBegin() {
        if (!batchMode) {
            batchMode = true;
        }
        if (null != batch) {
            batch.clear();
        } else {
            batch = new HashMap<>();
        }
    }

    /**
     * 取消批处理，幂等
     */
    @Override
    public void batchAbort() {
        batchMode = false;
        if (null != batch) {
            batch.clear();
        }
    }

    /**
     * 提交批处理，执行rocksdb批处理操作
     *
     * @throws RocksDBException
     */
    @Override
    public void batchCommit() throws RocksDBException, IOException {
        batchCommit(batch.size());
    }

    @Override
    public void batchCommit(long size) throws RocksDBException, IOException {
        if (!batchMode) {
            throw new KVDBException("not in batch mode");
        }
        batchMode = false;
        if (batch.size() != size) {
            throw new KVDBException("batch size not match, expect:" + size + ", actually:" + batch.size());
        }
        try {
            synchronized (instance) {
                KVWriteBatch writeBatch = instance.beginBatch();
                WalKV[] walkvs = new WalKV[batch.size()];
                int i = 0;
                for (Map.Entry<Bytes, byte[]> entry : batch.entrySet()) {
                    byte[] key = entry.getKey().toBytes();
                    writeBatch.set(key, entry.getValue());
                    walkvs[i] = new WalKV(key, entry.getValue());
                    i++;
                }
                long lsn = wal.append(WalEntity.newPutEntity(getDBName(), walkvs));
                writeBatch.commit();
                wal.updateMeta(lsn);
            }
        } finally {
            batch.clear();
        }
    }

    @Override
    public boolean[] exists(Bytes... keys) throws RocksDBException {
        boolean[] values = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) {
            final Bytes key = keys[i];
            byte[] value = null;
            if (batchMode) {
                value = batch.get(key);
            }
            if (null == value) {
                value = instance.get(key.toBytes());
            }
            values[i] = null != value ? true : false;
        }

        return values;
    }

    @Override
    public Bytes[] get(Bytes... keys) throws RocksDBException {
        Bytes[] values = new Bytes[keys.length];
        for (int i = 0; i < keys.length; i++) {
            final Bytes key = keys[i];
            byte[] value = null;
            if (batchMode) {
                value = batch.get(key);
            }
            if (null == value) {
                value = instance.get(key.toBytes());
            }
            values[i] = null != value ? new Bytes(value) : null;
        }

        return values;
    }

    @Override
    public void put(Map<Bytes, byte[]> kvs) throws RocksDBException, IOException {
        if (kvs.size() > MAX_BATCH_SIZE) {
            throw new KVDBException("too large executions");
        }
        if (batchMode) {
            if (batch.size() + kvs.size() > MAX_BATCH_SIZE) {
                throw new KVDBException("too large executions in batch");
            }
            for (int i = 0; i < kvs.size(); i++) {
                batch.putAll(kvs);
            }
        } else {
            synchronized (instance) {
                KVWriteBatch wb = instance.beginBatch();
                WalKV[] walkvs = new WalKV[kvs.size()];
                int i = 0;
                for (Map.Entry<Bytes, byte[]> entry : kvs.entrySet()) {
                    byte[] key = entry.getKey().toBytes();
                    wb.set(key, entry.getValue());
                    walkvs[i] = new WalKV(key, entry.getValue());
                    i++;
                }
                long lsn = wal.append(WalEntity.newPutEntity(getDBName(), walkvs));
                wb.commit();
                wal.updateMeta(lsn);
            }
        }
    }

}
