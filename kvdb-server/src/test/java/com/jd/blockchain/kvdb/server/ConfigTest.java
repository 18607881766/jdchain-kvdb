package com.jd.blockchain.kvdb.server;

import com.jd.blockchain.kvdb.protocol.exception.KVDBException;
import com.jd.blockchain.kvdb.server.config.ClusterConfig;
import com.jd.blockchain.kvdb.server.config.DBList;
import com.jd.blockchain.kvdb.server.config.KVDBConfig;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public class ConfigTest {

    @Before
    public void setUp() {
        System.setProperty("logging.path", "logs");
    }

    @Test
    public void testKVDBConfig() throws IOException, KVDBException {
        KVDBConfig config = new KVDBConfig(this.getClass().getResource("/config/config/kvdb.conf").getFile());
        Assert.assertEquals("0.0.0.0", config.getHost());
        Assert.assertEquals(7078, config.getPort());
        Assert.assertEquals(7060, config.getManagerPort());
        Assert.assertEquals("../dbs", config.getDbsRootdir());
        Assert.assertEquals(4, config.getDbsPartitions());
    }

    @Test
    public void testDBListConfig() throws IOException, KVDBException {
        KVDBConfig kvdbConfig = new KVDBConfig(this.getClass().getResource("/config/config/kvdb.conf").getFile());
        DBList config = new DBList(this.getClass().getResource("/config/system/dblist").getFile(), kvdbConfig);
        Assert.assertEquals(2, config.getDatabases().size());
        Assert.assertEquals(2, config.getEnabledDatabases().size());
        Assert.assertEquals("../dbs", config.getEnabledDatabases().get(0).getDbRootdir());
        Assert.assertEquals(4, config.getEnabledDatabases().get(0).getPartitions());
    }

    @Test
    public void testClusterConfig() throws IOException, KVDBException {
        KVDBConfig kvdbConfig = new KVDBConfig(this.getClass().getResource("/config/config/kvdb.conf").getFile());
        DBList dbList = new DBList(this.getClass().getResource("/config/system/dblist").getFile(), kvdbConfig);
        ClusterConfig config = new ClusterConfig(this.getClass().getResource("/config/config/cluster.conf").getFile(), dbList);
        Assert.assertEquals(2, config.getCluster().size());
        Assert.assertEquals(2, config.getCluster().get("test1").length);

        boolean ok = true;
        // ????????????????????????????????????dblist?????????????????????
        try {
            dbList = new DBList(this.getClass().getResource("/config/system/dblist_e1").getFile(), kvdbConfig);
            new ClusterConfig(this.getClass().getResource("/config/config/cluster.conf").getFile(), dbList);
        } catch (Exception e) {
            ok = false;
        }
        Assert.assertFalse(ok);
        ok = true;
        try {
            dbList = new DBList(this.getClass().getResource("/config/system/dblist_e2").getFile(), kvdbConfig);
            new ClusterConfig(this.getClass().getResource("/config/config/cluster.conf").getFile(), dbList);
        } catch (Exception e) {
            ok = false;
        }
        Assert.assertFalse(ok);
        // ???????????????
        dbList = new DBList(this.getClass().getResource("/config/system/dblist").getFile(), kvdbConfig);
        config = new ClusterConfig(this.getClass().getResource("/config/config/cluster_e1.conf").getFile(), dbList);
        Assert.assertEquals(1, config.getCluster().size());
        // ???????????????????????????
        ok = true;
        try {
            dbList = new DBList(this.getClass().getResource("/config/system/dblist").getFile(), kvdbConfig);
            new ClusterConfig(this.getClass().getResource("/config/config/cluster_e2.conf").getFile(), dbList);
        } catch (Exception e) {
            ok = false;
        }
        Assert.assertFalse(ok);
        ok = true;
        try {
            dbList = new DBList(this.getClass().getResource("/config/system/dblist").getFile(), kvdbConfig);
            new ClusterConfig(this.getClass().getResource("/config/config/cluster_e3.conf").getFile(), dbList);
        } catch (Exception e) {
            ok = false;
        }
        Assert.assertFalse(ok);
    }

}
