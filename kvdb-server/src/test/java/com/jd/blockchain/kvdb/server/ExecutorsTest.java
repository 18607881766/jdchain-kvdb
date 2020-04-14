package com.jd.blockchain.kvdb.server;

import com.jd.blockchain.binaryproto.BinaryProtocol;
import com.jd.blockchain.kvdb.protocol.*;
import com.jd.blockchain.kvdb.server.config.ServerConfig;
import com.jd.blockchain.kvdb.server.executor.*;
import com.jd.blockchain.utils.Bytes;
import com.jd.blockchain.utils.io.BytesUtils;
import com.jd.blockchain.utils.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.rocksdb.RocksDBException;

import java.io.File;
import java.util.UUID;

public class ExecutorsTest {

    private DefaultServerContext context;

    @Before
    public void setUp() throws Exception {
        context = new DefaultServerContext(new ServerConfig(this.getClass().getResource("/").getFile()));
    }

    @After
    public void tearDown() {
        context.stop();
        FileUtils.deletePath(new File(context.getConfig().getKvdbConfig().getDbsRootdir()), true);
    }

    private Session newSession() {
        return context.getSession(UUID.randomUUID().toString(), key -> new DefaultSession(key, null));
    }

    private Session newSessionWithTestDB() throws RocksDBException {
        Session session = context.getSession(UUID.randomUUID().toString(), key -> new DefaultSession(key, null));

        session.setDB("test1", context.getDatabase("test1"));

        return session;
    }

    private Response execute(Session session, Executor executor, Message message) {
        return (Response) executor.execute(new DefaultRequest(context, session, message)).getContent();
    }

    @Test
    public void testBatchAbort() throws RocksDBException {
        Session session = newSessionWithTestDB();
        Response response = execute(session, new BatchAbortExecutor(), KVDBMessage.batchAbort());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertFalse(session.batchMode());
    }

    @Test
    public void testBatchBegin() throws RocksDBException {
        Session session = newSessionWithTestDB();
        Response response = execute(session, new BatchBeginExecutor(), KVDBMessage.batchBegin());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertTrue(session.batchMode());
    }

    @Test
    public void testBatchCommit() throws RocksDBException {
        Session session = newSessionWithTestDB();
        Response response = execute(session, new BatchCommitExecutor(), KVDBMessage.batchCommit());
        Assert.assertEquals(Constants.ERROR, response.getCode());
        response = execute(session, new BatchBeginExecutor(), KVDBMessage.batchBegin());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertTrue(session.batchMode());
        response = execute(session, new BatchCommitExecutor(), KVDBMessage.batchCommit());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertFalse(session.batchMode());
    }

    @Test
    public void testExists() throws RocksDBException {
        Session session = newSessionWithTestDB();
        Response response = execute(session, new ExistsExecutor(), KVDBMessage.exists());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(0, response.getResult().length);

        response = execute(session, new ExistsExecutor(), KVDBMessage.exists(Bytes.fromString("k")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(1, response.getResult().length);
        Assert.assertEquals(0, BytesUtils.toInt(response.getResult()[0].toBytes()));

        response = execute(session, new ExistsExecutor(), KVDBMessage.exists(Bytes.fromString("k1"), Bytes.fromString("k2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(2, response.getResult().length);
        Assert.assertEquals(0, BytesUtils.toInt(response.getResult()[0].toBytes()));
        Assert.assertEquals(0, BytesUtils.toInt(response.getResult()[1].toBytes()));
    }

    @Test
    public void testGet() throws RocksDBException {
        Session session = newSessionWithTestDB();
        Response response = execute(session, new GetExecutor(), KVDBMessage.get());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(0, response.getResult().length);

        response = execute(session, new GetExecutor(), KVDBMessage.get(Bytes.fromString("k")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(1, response.getResult().length);
        Assert.assertNull(response.getResult()[0]);

        response = execute(session, new GetExecutor(), KVDBMessage.get(Bytes.fromString("k1"), Bytes.fromString("k2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(2, response.getResult().length);
        Assert.assertNull(response.getResult()[0]);
        Assert.assertNull(response.getResult()[1]);
    }

    @Test
    public void testPut() throws RocksDBException {
        Session session = newSessionWithTestDB();

        // kv must in pairs
        Response response = execute(session, new PutExecutor(), KVDBMessage.put(Bytes.fromString("k")));
        Assert.assertEquals(Constants.ERROR, response.getCode());

        // single kv
        response = execute(session, new PutExecutor(), KVDBMessage.put(Bytes.fromString("k"), Bytes.fromString("v")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        response = execute(session, new ExistsExecutor(), KVDBMessage.exists(Bytes.fromString("k")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(1, response.getResult().length);
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[0].toBytes()));
        response = execute(session, new GetExecutor(), KVDBMessage.get(Bytes.fromString("k")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(1, response.getResult().length);
        Assert.assertEquals(Bytes.fromString("v"), response.getResult()[0]);

        // multiple kvs
        response = execute(session, new PutExecutor(), KVDBMessage.put(Bytes.fromString("k1"), Bytes.fromString("v1"), Bytes.fromString("k2"), Bytes.fromString("v2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        response = execute(session, new ExistsExecutor(), KVDBMessage.exists(Bytes.fromString("k1"), Bytes.fromString("k2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(2, response.getResult().length);
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[0].toBytes()));
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[1].toBytes()));
        response = execute(session, new GetExecutor(), KVDBMessage.get(Bytes.fromString("k1"), Bytes.fromString("k2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(2, response.getResult().length);
        Assert.assertEquals(Bytes.fromString("v1"), response.getResult()[0]);
        Assert.assertEquals(Bytes.fromString("v2"), response.getResult()[1]);
    }

    @Test
    public void testPutInBatch() throws RocksDBException {
        Session session1 = newSessionWithTestDB();
        Session session2 = newSessionWithTestDB();

        // batch begin in session1
        Response response = execute(session1, new BatchBeginExecutor(), KVDBMessage.batchBegin());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());

        // single kv in session1
        response = execute(session1, new PutExecutor(), KVDBMessage.put(Bytes.fromString("k"), Bytes.fromString("v")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        response = execute(session1, new ExistsExecutor(), KVDBMessage.exists(Bytes.fromString("k")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(1, response.getResult().length);
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[0].toBytes()));
        response = execute(session1, new GetExecutor(), KVDBMessage.get(Bytes.fromString("k")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(1, response.getResult().length);
        Assert.assertEquals(Bytes.fromString("v"), response.getResult()[0]);

        // multiple kvs
        response = execute(session1, new PutExecutor(), KVDBMessage.put(Bytes.fromString("k1"), Bytes.fromString("v1"), Bytes.fromString("k2"), Bytes.fromString("v2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        response = execute(session1, new ExistsExecutor(), KVDBMessage.exists(Bytes.fromString("k1"), Bytes.fromString("k2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(2, response.getResult().length);
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[0].toBytes()));
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[1].toBytes()));
        response = execute(session1, new GetExecutor(), KVDBMessage.get(Bytes.fromString("k1"), Bytes.fromString("k2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(2, response.getResult().length);
        Assert.assertEquals(Bytes.fromString("v1"), response.getResult()[0]);
        Assert.assertEquals(Bytes.fromString("v2"), response.getResult()[1]);

        // k/k1/k2 invisible in session2 before session commit batch
        response = execute(session2, new ExistsExecutor(), KVDBMessage.exists(Bytes.fromString("k"), Bytes.fromString("k1"), Bytes.fromString("k2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(3, response.getResult().length);
        Assert.assertEquals(0, BytesUtils.toInt(response.getResult()[0].toBytes()));
        Assert.assertEquals(0, BytesUtils.toInt(response.getResult()[1].toBytes()));
        Assert.assertEquals(0, BytesUtils.toInt(response.getResult()[2].toBytes()));

        // batch commit in session1
        response = execute(session1, new BatchCommitExecutor(), KVDBMessage.batchCommit());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());

        // k/k1/k2 invisible in session2 after session commit batch
        response = execute(session2, new ExistsExecutor(), KVDBMessage.exists(Bytes.fromString("k"), Bytes.fromString("k1"), Bytes.fromString("k2")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        Assert.assertEquals(3, response.getResult().length);
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[0].toBytes()));
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[1].toBytes()));
        Assert.assertEquals(1, BytesUtils.toInt(response.getResult()[2].toBytes()));
    }

    @Test
    public void testUse() {
        Session session = newSession();

        Response response = execute(session, new UseExecutor(), KVDBMessage.use("db0"));
        Assert.assertEquals(Constants.ERROR, response.getCode());

        response = execute(session, new CreateDatabaseExecutor(), KVDBMessage.createDatabase(Bytes.fromString("db0")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());

        response = execute(session, new UseExecutor(), KVDBMessage.use("db0"));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());

    }

    @Test
    public void testCreateDB() {
        Session session = newSession();

        Response response = execute(session, new CreateDatabaseExecutor(), KVDBMessage.createDatabase(Bytes.fromString("db0")));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());

        response = execute(session, new UseExecutor(), KVDBMessage.use("db0"));
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
    }

    @Test
    public void testClusterInfo() throws RocksDBException {
        Session session = newSessionWithTestDB();

        Response response = execute(session, new ClusterInfoExecutor(), KVDBMessage.clusterInfo());
        Assert.assertEquals(Constants.SUCCESS, response.getCode());
        ClusterInfo info = BinaryProtocol.decodeAs(response.getResult()[0].toBytes(), ClusterInfo.class);
        Assert.assertNotNull(info);
        Assert.assertEquals(2, info.size());
    }

}
