package com.qihoo.qsql.codegen.spark;

import com.qihoo.qsql.codegen.QueryGenerator;
import com.qihoo.qsql.codegen.ClassBodyComposer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.StructType;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Code generator, used when {@link com.qihoo.qsql.exec.spark.SparkPipeline} is chosen and source data of query is in
 * MySql at the same time.
 */
public class SparkJdbcGenerator extends QueryGenerator {

    private static String QSQL_CLUSTER_URL = System.getenv("QSQL_CLUSTER_URL");
    private static String QSQL_HDFS_TMP = System.getenv("QSQL_HDFS_TMP");
    private static String FILE_SYSTEM_URI = QSQL_CLUSTER_URL + QSQL_HDFS_TMP;

    @Override
    public void importDependency() {
        String[] imports = {
            "import org.apache.spark.sql.Dataset",
            "import org.apache.spark.api.java.JavaRDD",
            "import org.apache.spark.sql.Row",
            "import org.apache.spark.sql.RowFactory",
            "import org.apache.spark.sql.SparkSession",
            "import org.apache.spark.sql.types.DataTypes",
            "import org.apache.spark.sql.types.DataType",
            "import org.apache.spark.sql.types.StructField",
            "import org.apache.spark.sql.types.StructType",
            "import java.sql.*",
            "import java.util.Enumeration",
            "import java.sql.Driver",
            "import java.sql.DriverManager",
            "import java.util.ArrayList",
            "import java.util.List",
            "import java.math.BigInteger",
            "import org.apache.hadoop.conf.Configuration",
            "import org.apache.hadoop.fs.FileSystem",
            "import org.apache.hadoop.fs.Path",
            "import java.io.IOException",
            "import java.net.URI",
            "import java.util.ArrayDeque",
            "import java.util.Queue",
            "import java.util.concurrent.ArrayBlockingQueue",
            "import java.util.concurrent.BlockingQueue",
            "import java.util.concurrent.ExecutorService",
            "import java.util.concurrent.Executors",
            "import com.qihoo.qsql.codegen.spark.SparkJdbcGenerator.ResultSetWrapper",
            "import com.qihoo.qsql.codegen.spark.SparkJdbcGenerator.ResultSetInMemoryWrapper",
            "import com.qihoo.qsql.codegen.spark.SparkJdbcGenerator.ResultSetInFileSystemWrapper",
            "import com.qihoo.qsql.codegen.spark.SparkJdbcGenerator.SpecificFunction",
            "import com.qihoo.qsql.codegen.spark.SparkJdbcGenerator.ResultsTransporter"
        };

        composer.handleComposition(ClassBodyComposer.CodeCategory.IMPORT, imports);
    }

    @Override
    public void prepareQuery() {
        composer.handleComposition(ClassBodyComposer.CodeCategory.METHOD,
            declareGetDataTypesMethod());
        composer.handleComposition(ClassBodyComposer.CodeCategory.METHOD,
            declareDataTypeFormat());
        composer.handleComposition(ClassBodyComposer.CodeCategory.METHOD,
            declarePersistMethod());
        composer.handleComposition(ClassBodyComposer.CodeCategory.METHOD,
            declareTransferContentsMethod());
    }

    //remember to remove temporary files after computing in hdfs or local machine
    @Override
    public void executeQuery() {
        Invoker config = Invoker.registerMethod("persist");
        String invokeWrap = config.invoke(add(convertProperties("jdbcUrl", "jdbcUser", "jdbcPassword", "jdbcDriver"),
            query));
        String wrapper = with("wrapper", "tmp");
        String invokedStatement = "ResultSetWrapper" + " " + wrapper + " = " + invokeWrap + ";";
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE, invokedStatement);

        String invokeUnwrapResult = invokeWrapperUnwrap(wrapper);
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE, invokeUnwrapResult);
    }

    private String[] add(String[] oldArray, String element) {
        String[] array = new String[oldArray.length + 1];
        for (int i = 0; i < oldArray.length; i++) {
            array[i] = oldArray[i];
        }
        //将新元素添加到新数组
        array[oldArray.length] = element;
        return array;
    }

    @Override
    public void saveToTempTable() {
        String created = "tmp" + ".createOrReplaceTempView(\"" + tableName + "\");";
        composer.handleComposition(ClassBodyComposer.CodeCategory.SENTENCE, created);
    }

    private String invokeWrapperUnwrap(String wrapperName) {
        return "        if(" + wrapperName + " instanceof ResultSetInMemoryWrapper)\n"
            + "            " + "tmp" + " = spark.createDataFrame(((ResultSetInMemoryWrapper) "
            + wrapperName + ").getRows(), "
            + wrapperName + ".getType());\n"
            + "        else {\n"
            + "            JavaRDD<String> lines = spark.sparkContext()\n"
            + "                    .textFile(((ResultSetInFileSystemWrapper) "
            + wrapperName + ").getPath(), 100).toJavaRDD();\n"
            + "            JavaRDD<Row> rows = lines.map(new SpecificFunction());\n"
            + "            " + "tmp" + " = spark.createDataFrame(rows, "
            + wrapperName + ".getType());\n"
            + "        }";
    }

    private String declareGetDataTypesMethod() {
        return "    private List<StructField> getDataTypes(ResultSet resultSet) {\n"
            + "        List<StructField> columns;\n"
            + "\n"
            + "        try {\n"
            + "            int n = resultSet.getMetaData().getColumnCount();\n"
            + "            String labelName;\n"
            + "\n"
            + "            columns = new ArrayList<StructField>();\n"
            + "\n"
            + "            for (int index = 1; index <= n; index++) {\n"
            + "                labelName = resultSet.getMetaData().getColumnLabel(index);\n"
            + "                columns.add(DataTypes.createStructField(labelName, "
            + "getColumnType(resultSet.getMetaData().getColumnType(index)), true));\n"
            + "            }\n"
            + "        } catch (SQLException e) {\n"
            + "            throw new RuntimeException(e.getMessage());\n"
            + "        }\n"
            + "\n"
            + "        return columns;\n"
            + "    }";
    }

    private String declareDataTypeFormat() {
        return "    private DataType getColumnType(int dataType) {\n"
            + "        switch (dataType) {\n"
            + "            case Types.BINARY:\n"
            + "                return DataTypes.BinaryType;\n"
            + "            case Types.BIT:\n"
            + "            case Types.BOOLEAN:\n"
            + "                return DataTypes.BooleanType;\n"
            + "            case Types.DATE:\n"
            + "                return DataTypes.DateType;\n"
            + "            case Types.TIMESTAMP:\n"
            + "                return DataTypes.TimestampType;\n"
            + "            case Types.DECIMAL:\n"
            + "            case Types.NUMERIC:\n"
            + "                return DataTypes.createDecimalType();\n"
            + "            case Types.DOUBLE:\n"
            + "                return DataTypes.DoubleType;\n"
            + "            case Types.FLOAT:\n"
            + "            case Types.REAL:\n"
            + "                return DataTypes.FloatType;\n"
            + "            case Types.BIGINT:\n"
            + "                return DataTypes.LongType;\n"
            + "            case Types.INTEGER:\n"
            + "                return DataTypes.IntegerType;\n"
            + "            case Types.SMALLINT:\n"
            + "                return DataTypes.ShortType;\n"
            + "            case Types.TINYINT:\n"
            + "                return DataTypes.IntegerType;\n"
            + "            case Types.NULL:\n"
            + "                return DataTypes.NullType;\n"
            + "            case Types.VARCHAR:\n"
            + "            case Types.NVARCHAR:\n"
            + "            case Types.CHAR:\n"
            + "            case Types.NCHAR:\n"
            + "            default:\n"
            + "                return DataTypes.StringType;\n"
            + "        }\n"
            + "    }";
    }

    private String declarePersistMethod() {
        //TODO debug toLowerCase, will lead to mistake occured which sql identifier was changed
        return "private ResultSetWrapper persist(String url, String user, String password,String driver, String sql) "
            + "{\n"
            // + "        String sql = \"" + query.replaceAll("\"","\\\\\"")  + "\";\n"
            + "        String baseURI = \"" + FILE_SYSTEM_URI + "\";\n"
            + "        Connection connection = null;\n"
            + "        Statement statement = null;\n"
            + "        ResultSet resultSet = null;\n"
            + "\n"
            + "        StructType schema;\n"
            + "\n"
            + "        final int THRESHOLD = 0x400000;\n"
            + "        final int BUFFER_SIZE = 0x40000;\n"
            + "        final int CONCURRENT_PACKAGE_NUM = 8;\n"
            + "\n"
            + "        int rowCount = 0;\n"
            + "        int rowPartition = 0;\n"
            + "\n"
            + "        long partitionStamp = System.currentTimeMillis();\n"
            + "\n"
            + "        BlockingQueue<List<Object[]>> queue = new ArrayBlockingQueue<List<Object[]>>"
            + "(CONCURRENT_PACKAGE_NUM);\n"
            + "        Queue<String> partitionQueue = new ArrayDeque<String>();\n"
            + "\n"
            + "        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_PACKAGE_NUM);\n"
            + "\n"
            + getTryAndCatchCodeInMethod()
            + "\n"
            + "        if (!queue.isEmpty()) {\n"
            + "            List<Row> rows = new ArrayList<Row>(queue.size() * rowPartition);\n"
            + "\n"
            + "            for (List<Object[]> rowSet : queue) {\n"
            + "                for (Object[] rowFields : rowSet)\n"
            + "                    rows.add(RowFactory.create((Object[]) rowFields));\n"
            + "                rowSet.clear();\n"
            + "            }\n"
            + "\n"
            + "            return new ResultSetInMemoryWrapper(rows, schema);\n"
            + "        } else {\n"
            + "            executor.shutdown();\n"
            + "            try {\n"
            + "                while (!executor.isTerminated())\n"
            + "                    Thread.sleep(500);\n"
            + "            } catch (InterruptedException e) {\n"
            + "                throw new RuntimeException(e);\n"
            + "            }\n"
            + "\n"
            + "            return new ResultSetInFileSystemWrapper(baseURI, schema);\n"
            + "        }\n"
            + "    }";
    }

    private String getTryAndCatchCodeInMethod() {
        return "        try {\n"
            + "            Class.forName(driver);\n"
            + "            connection = DriverManager.getConnection(url, user, password);\n"
            + "            statement = connection.prepareStatement(sql);\n"
            + "            resultSet = statement.executeQuery(sql);\n"
            + "\n"
            + "            schema = DataTypes.createStructType(getDataTypes(resultSet));\n"
            + "\n"
            + "            List<Object[]> rows = new ArrayList<Object[]>();\n"
            + "            int n = resultSet.getMetaData().getColumnCount();\n"
            + "\n"
            + getWhileCodeInMethod()
            + "\n"
            + "            if (!rows.isEmpty()) {\n"
            + "                queue.offer(rows);\n"
            + "                partitionQueue.offer(baseURI + \"/\" + partitionStamp +\n"
            + "                        \"/partition#\" + rowPartition);\n"
            + "\n"
            + "                if (rowPartition * BUFFER_SIZE > THRESHOLD) {\n"
            + "                    while (!queue.isEmpty()) {\n"
            + "                        try {\n"
            + "                            transferContents((List<Object[]>) queue.take(), (String) partitionQueue.poll"
            + "(), executor);\n"
            + "                        } catch (InterruptedException e) {\n"
            + "                            e.printStackTrace();\n"
            + "                        }\n"
            + "                    }\n"
            + "                }\n"
            + "            }\n"
            + "\n"
            + "//            Row row = RowFactory.create(rowValue.toArray());\n"
            + "        } catch (Exception e) {\n"
            + "            throw new RuntimeException(e);\n"
            + "        } finally {\n"
            + "            try {\n"
            + "                if (resultSet != null)\n"
            + "                    resultSet.close();\n"
            + "                if (statement != null)\n"
            + "                    statement.close();\n"
            + "                if (connection != null)\n"
            + "                    connection.close();\n"
            + "            } catch (SQLException ignored) {\n"
            + "            }\n"
            + "        }\n";
    }

    private String getWhileCodeInMethod() {
        return "            while (resultSet.next()) {\n"
            + "                Object[] rowValue = new Object[n];\n"
            + "\n"
            + "                for (int index = 1; index <= n; index++){\n"
            // + "                    rowValue[index - 1] = resultSet.getObject(index);\n"
            + "                    Object value = resultSet.getObject(index);\n"
            + "                    if (value instanceof BigInteger) {\n"
            + "                         rowValue[index - 1] = ((BigInteger) value).longValue();\n"
            + "                    } else {\n"
            + "                         rowValue[index - 1] = value;\n"
            + "                    }\n"
            + "                 }\n"
            + "                rows.add(rowValue);\n"
            + "                rowCount++;\n"
            + "\n"
            + "                if (rowCount > BUFFER_SIZE) {\n"
            + "                    queue.offer(rows);\n"
            + "                    //magic value '#'\n"
            + "                    partitionQueue.offer(baseURI + \"/\" + partitionStamp +\n"
            + "                            \"/partition#\" + rowPartition);\n"
            + "\n"
            + "                    if (rowPartition * BUFFER_SIZE > THRESHOLD) {\n"
            + "                        while (!queue.isEmpty()) {\n"
            + "                            transferContents((List<Object[]>) queue.poll(), (String) partitionQueue.poll"
            + "(), executor);\n"
            + "                        }\n"
            + "                    }\n"
            + "                    rows = new ArrayList<Object[]>();\n"
            + "\n"
            + "                    rowCount = 0;\n"
            + "                    rowPartition++;\n"
            + "                }\n"
            + "            }\n";
    }


    private String declareTransferContentsMethod() {
        return "    private void transferContents(List<Object[]> rows,\n"
            + "                                  String url,\n"
            + "                                  ExecutorService executor) {\n"
            + "        Configuration conf = new Configuration();\n"
            + "\n"
            + "        String hadoopConfDir = System.getenv(\"SPARK_HOME\") + \"/hadoopconf/\";\n"
            + "        conf.addResource(new Path(hadoopConfDir + \"core-site.xml\"));\n"
            + "        conf.addResource(new Path(hadoopConfDir + \"hdfs-site.xml\"));\n"
            + "\n"
            + "        FileSystem fs = null;\n"
            + "        try {\n"
            + "            fs = FileSystem.get(URI.create(\"" + QSQL_CLUSTER_URL + "\"), conf);\n"
            + "            Path path = new Path(url.substring(0, url.lastIndexOf('/')));\n"
            + "\n"
            + "            if (!fs.exists(path))\n"
            + "                fs.mkdirs(path);\n"
            + "        } catch (IOException e) {\n"
            + "            e.printStackTrace();\n"
            + "        } finally {\n"
            + "            if (fs != null) {\n"
            + "                try {\n"
            + "                    fs.close();\n"
            + "                } catch (IOException ignored) {}\n"
            + "            }\n"
            + "        }\n"
            + "\n"
            + "        executor.execute(new ResultsTransporter(url, rows, conf));\n"
            + "    }";
    }

    public static class ResultSetWrapper {

        private StructType type;

        ResultSetWrapper(StructType type) {
            this.type = type;
        }

        public StructType getType() {
            return type;
        }
    }

    public static class ResultSetInMemoryWrapper extends ResultSetWrapper {

        private List<Row> rows;

        public ResultSetInMemoryWrapper(List<Row> rows, StructType type) {
            super(type);
            this.rows = rows;
        }

        public List<Row> getRows() {
            return rows;
        }
    }

    public static class ResultSetInFileSystemWrapper extends ResultSetWrapper {

        private String path;

        public ResultSetInFileSystemWrapper(String path, StructType type) {
            super(type);
            this.path = path;
        }

        public String getPath() {
            return path;
        }
    }

    public static class SpecificFunction implements Function {

        @Override
        public Row call(Object ob) throws Exception {
            return RowFactory.create((Object[]) ((String) ob).split("\u0006"));
        }
    }

    /**
     * Transport results to HDFS cache.
     */
    public static class ResultsTransporter implements Runnable {

        private String url;
        private Configuration conf;
        private List<Object[]> content;
        private char separator = '\u0006';

        /**
         * Transport results to HDFS cache.
         *
         * @param url path of HDFS
         * @param content result of query
         * @param conf config of the calculation
         */
        public ResultsTransporter(String url, List<Object[]> content, Configuration conf) {
            this.url = url;
            this.content = content;
            this.conf = conf;
        }

        @Override
        public void run() {
            try {
                try (FileSystem fs = FileSystem.get(
                    URI.create(QSQL_CLUSTER_URL), conf)) {
                    FSDataOutputStream out = fs.create(new Path(url));
                    try {
                        for (Object[] arrayContent : content) {
                            StringBuilder builder = new StringBuilder();

                            for (int j = 0; j < arrayContent.length; j++) {
                                builder.append(arrayContent[j]);
                                if (j != arrayContent.length - 1) {
                                    builder.append(separator);
                                }
                            }
                            builder.append("\n");
                            out.writeChars(builder.toString());
                        }
                        content = null;
                    } finally {
                        out.flush();
                        out.close();
                        System.gc();
                    }
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
