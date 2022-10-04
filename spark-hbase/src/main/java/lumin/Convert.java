package lumin;

import com.google.common.collect.Maps;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.mapreduce.HFileInputFormat;
import org.apache.hadoop.io.NullWritable;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.encoders.RowEncoder;

public class Convert implements Serializable {

  private SparkSession spark;
  private String metricDir;
  private String uidDir;
  private String outputTable;

  private Broadcast<List<UID>> uidBroadcast;

  public Convert(SparkSession spark, String metricDir, String uidDir, String outputTable) {
    this.spark = spark;
    this.metricDir = metricDir;
    this.uidDir = uidDir;
    this.outputTable = outputTable;
  }

  public void convert() {
    List<UID> uidList = mapHFiles(uidDir, UID::fromCellData).collect();
    uidBroadcast = new JavaSparkContext(spark.sparkContext()).broadcast(uidList);

    JavaRDD<Metric> metricRdd = flatMapHFiles(metricDir, new MetricMapFunction(uidBroadcast));
    writeOutput(metricRdd);
  }

  private void writeOutput(JavaRDD<Metric> metricRdd) {
    spark
        .createDataset(metricRdd.map(Metric::toRow).rdd(), RowEncoder.apply(Metric.SCHEMA))
        .writeTo(outputTable)
        .createOrReplace();
  }

  private <T> JavaRDD<T> mapHFiles(String sourceDir, MapFunction<CellData, T> fn) {
    return createRDD(sourceDir).map(fn::call).filter(Objects::nonNull);
  }

  private <T> JavaRDD<T> flatMapHFiles(String sourceDir, FlatMapFunction<CellData, T> fn) {
    return createRDD(sourceDir).flatMap(fn::call).filter(Objects::nonNull);
  }

  private JavaRDD<CellData> createRDD(String sourceDir) {
    SparkContext ctx = spark.sparkContext();
    return ctx.newAPIHadoopFile(
            sourceDir,
            HFileInputFormat.class,
            NullWritable.class,
            Cell.class,
            ctx.hadoopConfiguration())
        .toJavaRDD()
        .map(tuple -> new CellData(tuple._2))
        .repartition(spark.sessionState().conf().numShufflePartitions());
  }

  static class MetricMapFunction implements FlatMapFunction<CellData, Metric> {
    private final Broadcast<List<UID>> uidBroadcast;
    private transient Map<ByteBuffer, String> metricMap;
    private transient Map<ByteBuffer, String> tagKeyMap;
    private transient Map<ByteBuffer, String> tagValueMap;

    MetricMapFunction(Broadcast<List<UID>> uidBroadcast) {
      this.uidBroadcast = uidBroadcast;
    }

    @Override
    public Iterator<Metric> call(CellData cellData) {
      if (metricMap == null) {
        loadMaps(uidBroadcast.value());
      }
      return Metric.fromCellData(cellData, metricMap, tagKeyMap, tagValueMap).iterator();
    }

    private void loadMaps(List<UID> uidList) {
      metricMap = Maps.newHashMap();
      tagKeyMap = Maps.newHashMap();
      tagValueMap = Maps.newHashMap();
      for (UID uid : uidList) {
        switch (uid.qualifier) {
          case "metrics":
            metricMap.put(ByteBuffer.wrap(uid.uid), uid.name);
            break;
          case "tagk":
            tagKeyMap.put(ByteBuffer.wrap(uid.uid), uid.name);
            break;
          case "tagv":
            tagValueMap.put(ByteBuffer.wrap(uid.uid), uid.name);
            break;
        }
      }
    }
  }
}
