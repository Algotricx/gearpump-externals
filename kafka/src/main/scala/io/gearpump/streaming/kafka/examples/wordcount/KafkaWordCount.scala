package io.gearpump.streaming.kafka.examples.wordcount

import java.util.Properties

import akka.actor.ActorSystem
import io.gearpump.streaming.kafka.util.KafkaConfig
import io.gearpump.streaming.kafka.{KafkaSink, KafkaSource, KafkaStoreFactory}
import kafka.api.OffsetRequest
import org.slf4j.Logger
import org.apache.gearpump.cluster.UserConfig
import org.apache.gearpump.cluster.client.ClientContext
import org.apache.gearpump.cluster.main.{ArgumentsParser, CLIOption, ParseResult}
import org.apache.gearpump.streaming.partitioner.HashPartitioner
import org.apache.gearpump.streaming.sink.DataSinkProcessor
import org.apache.gearpump.streaming.source.DataSourceProcessor
import org.apache.gearpump.streaming.{Processor, StreamApplication}
import org.apache.gearpump.util.Graph._
import org.apache.gearpump.util.{AkkaApp, Graph, LogUtil}

object KafkaWordCount extends AkkaApp with ArgumentsParser {
  private val LOG: Logger = LogUtil.getLogger(getClass)

  override val options: Array[(String, CLIOption[Any])] = Array(
    "source" -> CLIOption[Int]("<how many kafka source tasks>", required = false,
      defaultValue = Some(1)),
    "split" -> CLIOption[Int]("<how many split tasks>", required = false, defaultValue = Some(1)),
    "sum" -> CLIOption[Int]("<how many sum tasks>", required = false, defaultValue = Some(1)),
    "sink" -> CLIOption[Int]("<how many kafka sink tasks>", required = false,
      defaultValue = Some(1))
  )

  def application(config: ParseResult, system: ActorSystem): StreamApplication = {
    implicit val actorSystem = system
    val appName = "KafkaWordCount"
    val sourceNum = config.getInt("source")
    val splitNum = config.getInt("split")
    val sumNum = config.getInt("sum")
    val sinkNum = config.getInt("sink")
    val appConfig = UserConfig.empty
    val props = new Properties
    props.put(KafkaConfig.ZOOKEEPER_CONNECT_CONFIG, "localhost:2181")
    props.put(KafkaConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
    props.put(KafkaConfig.CONSUMER_START_OFFSET_CONFIG,
      new java.lang.Long(OffsetRequest.LatestTime))
    props.put(KafkaConfig.CHECKPOINT_STORE_NAME_PREFIX_CONFIG, appName)
    val sourceTopic = "topic1"
    val source = new KafkaSource(sourceTopic, props)
    val checkpointStoreFactory = new KafkaStoreFactory(props)
    source.setCheckpointStore(checkpointStoreFactory)
    val sourceProcessor = DataSourceProcessor(source, sourceNum)
    val split = Processor[Split](splitNum)
    val sum = Processor[Sum](sumNum)
    val sink = new KafkaSink("topic2", props)
    val sinkProcessor = DataSinkProcessor(sink, sinkNum)
    val partitioner = new HashPartitioner
    val computation = sourceProcessor ~ partitioner ~> split ~ partitioner ~>
      sum ~ partitioner ~> sinkProcessor
    val app = StreamApplication(appName, Graph(computation), appConfig)
    app
  }

  override def main(akkaConf: Config, args: Array[String]): Unit = {
    val config = parse(args)
    val context = ClientContext(akkaConf)
    val appId = context.submit(application(config, context.system))
    context.close()
  }
}
