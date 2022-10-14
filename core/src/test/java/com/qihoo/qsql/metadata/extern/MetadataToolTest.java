package com.qihoo.qsql.metadata.extern;

import com.qihoo.qsql.exception.ParseException;
import com.qihoo.qsql.metadata.utils.MetadataUtil;
import com.qihoo.qsql.utils.PropertiesReader;
import java.util.Properties;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MetadataToolTest {

    MetadataTool metadataTool;
    Properties metadataProperties = PropertiesReader.readProperties(
        "metadata.properties", MetadataUtil.class);

    /**
     * init metadataTool.
     */
    @Before
    public void init() {
        metadataTool = new MetadataTool();
    }

    @Test
    public void testMetadataTool() {
        if (! MetadataUtil.isEmbeddedDatabase(metadataProperties)) {
            Properties properties = new Properties();
            properties.put("--action", "delete");
            properties.put("--dbType", "mysql");
            try {
                metadataTool.run(properties);
            } catch (ParseException ex) {
                Assert.assertTrue(false);
            }
        }
    }

    @Test
    public void testMetadataToolWithoutDbType() {
        if (! MetadataUtil.isEmbeddedDatabase(metadataProperties)) {
            Properties properties = new Properties();
            properties.put("--action", "init");
            try {
                metadataTool.run(properties);
            } catch (ParseException ex) {
                Assert.assertTrue(true);
            }
        }
    }

}
