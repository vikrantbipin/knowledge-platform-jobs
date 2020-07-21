package org.sunbird.async.spec

import java.util

import com.google.gson.Gson
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext
import org.apache.flink.test.util.MiniClusterWithClientResource
import org.cassandraunit.CQLDataLoader
import org.cassandraunit.dataset.cql.FileCQLDataSet
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.mockito.Mockito
import org.mockito.Mockito._
import org.sunbird.async.core.cache.RedisConnect
import org.sunbird.async.core.job.FlinkKafkaConnector
import org.sunbird.async.core.util.CassandraUtil
import org.sunbird.async.fixture.EventFixture
import org.sunbird.async.task.{RelationCacheUpdaterConfig, RelationCacheUpdaterStreamTask}
import org.sunbird.spec.{BaseMetricsReporter, BaseTestSpec}
import redis.clients.jedis.Jedis
import redis.embedded.RedisServer

import scala.collection.JavaConverters._

class RelationCacheUpdaterTaskTestSpec extends BaseTestSpec {

  implicit val mapTypeInfo: TypeInformation[java.util.Map[String, AnyRef]] = TypeExtractor.getForClass(classOf[java.util.Map[String, AnyRef]])

  val flinkCluster = new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
    .setConfiguration(testConfiguration())
    .setNumberSlotsPerTaskManager(1)
    .setNumberTaskManagers(1)
    .build)

  var redisServer: RedisServer = _
  redisServer = new RedisServer(6340)
  redisServer.start()
  var relCacheDb: Jedis = _
  var contentCacheDb: Jedis = _
  val mockKafkaUtil: FlinkKafkaConnector = mock[FlinkKafkaConnector](Mockito.withSettings().serializable())
  val gson = new Gson()
  val config: Config = ConfigFactory.load("test.conf")
  val jobConfig: RelationCacheUpdaterConfig = new RelationCacheUpdaterConfig(config)


  var cassandraUtil: CassandraUtil = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val redisConnect = new RedisConnect(jobConfig)
    relCacheDb = redisConnect.getConnection(jobConfig.relationCacheStore)
    contentCacheDb = redisConnect.getConnection(jobConfig.collectionCacheStore)
    EmbeddedCassandraServerHelper.startEmbeddedCassandra(80000L)
    cassandraUtil = new CassandraUtil(jobConfig.dbHost, jobConfig.dbPort)
    val session = cassandraUtil.session

    val dataLoader = new CQLDataLoader(session);
    dataLoader.load(new FileCQLDataSet(getClass.getResource("/test.cql").getPath, true, true));
    // Clear the metrics
    testCassandraUtil(cassandraUtil)
    BaseMetricsReporter.gaugeMetrics.clear()
    relCacheDb.flushDB()
    contentCacheDb.flushDB()
    flinkCluster.before()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    try {
      EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
      redisServer.stop()
    } catch {
      case ex: Exception => {
      }
    }
    flinkCluster.after()
  }


  "RelationCacheUpdater " should "generate cache" in {
    when(mockKafkaUtil.kafkaMapSource(jobConfig.kafkaInputTopic)).thenReturn(new RelationCacheUpdaterMapSource)
    new RelationCacheUpdaterStreamTask(jobConfig, mockKafkaUtil).process()
    BaseMetricsReporter.gaugeMetrics(s"${jobConfig.jobName}.${jobConfig.totalEventsCount}").getValue() should be(2)
    BaseMetricsReporter.gaugeMetrics(s"${jobConfig.jobName}.${jobConfig.successEventCount}").getValue() should be(2)
    BaseMetricsReporter.gaugeMetrics(s"${jobConfig.jobName}.${jobConfig.failedEventCount}").getValue() should be(0)
    BaseMetricsReporter.gaugeMetrics(s"${jobConfig.jobName}.${jobConfig.skippedEventCount}").getValue() should be(0)

    // Assertion on total keys for leafnodes and ancestors.
    getKeysLength("*:leafnodes", relCacheDb) should be (18)
    getKeysLength("*:ancestors", relCacheDb) should be (9)

    // Checking assertion of leafNodes for some of the collections.
    val leafNodes1  = getList("do_11305855864948326411234:do_11305855931314995211239:leafnodes", relCacheDb)
    leafNodes1.size should be (2)
    leafNodes1 should contain theSameElementsAs (List("do_1130314841730334721104", "do_1130314845426565121105"))

    val leafNodes2 = getList("do_11305855864948326411234:do_11305856061520281611348:leafnodes", relCacheDb)
    leafNodes2.size should be (4)
    leafNodes2 should contain theSameElementsAs (List("do_1130314847650037761106", "do_1130314857102131201110", "do_1130314841730334721104", "do_1130314851303178241108"))

    // Checking assertion of Ancestors for some of the resources.
    val ancestors1 = getList("do_11305855864948326411234:do_1130314847650037761106:ancestors", relCacheDb)
    ancestors1.size should be (11)
    ancestors1 should contain theSameElementsAs (List("do_11305856007008256011330", "do_11305856061516185611346", "do_11305856061513728011342",
      "do_11305856007004160011324", "do_11305856007007436811328", "do_11305856007009075211332", "do_11305856061514547211344",
      "do_11305856061510451211340", "do_11305855864948326411234", "do_11305856007005798411326", "do_11305856061520281611348"))

    val ancestors2 = getList("do_11305855864948326411234:do_1130314857102131201110:ancestors", relCacheDb)
    ancestors2.size should be (6)
    ancestors2 should contain theSameElementsAs (List("do_11305856061516185611346", "do_11305856061513728011342", "do_11305856061514547211344",
      "do_11305855864948326411234", "do_11305856061510451211340", "do_11305856061520281611348"))

    // Checking assertion of Collection metadata for some of the collection (with visibility parent)
    val keys = getKeys("*", contentCacheDb)
    keys should not contain ("do_11305855864948326411234")
    keys.size should be (16)
    contentCacheDb.get("do_11305927545289932812008") should not be empty
    contentCacheDb.get("do_11305856007004160011324") should not be empty
    contentCacheDb.get("do_11305855931314995211239") should not be empty
    contentCacheDb.get("do_11305855931315814411241") should not be empty
  }

  def testCassandraUtil(cassandraUtil: CassandraUtil): Unit = {
    cassandraUtil.reconnect()
  }

  def getKeys(pattern: String, redisDb: Jedis): List[String] = {
    redisDb.keys(pattern).asScala.toList
  }

  def getKeysLength(pattern: String, redisDb: Jedis): Int = {
    redisDb.keys(pattern).size()
  }

  def getList(key: String, redisDb: Jedis): List[String] = {
    redisDb.smembers(key).asScala.toList
  }

}

class RelationCacheUpdaterMapSource extends SourceFunction[java.util.Map[String, AnyRef]] {
  override def run(ctx: SourceContext[util.Map[String, AnyRef]]): Unit = {
    val gson = new Gson()
    val eventMap1 = gson.fromJson(EventFixture.EVENT_1, new util.LinkedHashMap[String, AnyRef]().getClass).asInstanceOf[util.Map[String, AnyRef]].asScala
    val eventMap2 = gson.fromJson(EventFixture.EVENT_2, new util.LinkedHashMap[String, AnyRef]().getClass).asInstanceOf[util.Map[String, AnyRef]].asScala
    ctx.collect(eventMap1.asJava)
    ctx.collect(eventMap2.asJava)
  }

  override def cancel() = {}
}