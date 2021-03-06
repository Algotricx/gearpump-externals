package io.gearpump.streaming.kafka.lib.source.consumer

import java.util.concurrent.LinkedBlockingQueue

import kafka.common.TopicAndPartition
import org.mockito.Mockito._
import org.scalacheck.Gen
import org.scalatest.mock.MockitoSugar
import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}

class FetchThreadSpec extends PropSpec with PropertyChecks with Matchers with MockitoSugar {

  val nonNegativeGen = Gen.choose[Int](0, 1000)
  val positiveGen = Gen.choose[Int](1, 1000)
  val startOffsetGen = Gen.choose[Long](0L, 1000L)
  property("FetchThread should set startOffset to iterators") {
    forAll(nonNegativeGen, nonNegativeGen, startOffsetGen) {
      (fetchThreshold: Int, fetchSleepMS: Int, startOffset: Long) =>
        val topicAndPartition = mock[TopicAndPartition]
        val consumer = mock[KafkaConsumer]
        val createConsumer = (tp: TopicAndPartition) => consumer
        val sleeper = mock[ExponentialBackoffSleeper]
        val incomingQueue = new LinkedBlockingQueue[KafkaMessage]()
        val fetchThread = new FetchThread(createConsumer,
          incomingQueue, sleeper, fetchThreshold, fetchSleepMS)
        fetchThread.setTopicAndPartitions(Array(topicAndPartition))
        fetchThread.setStartOffset(topicAndPartition, startOffset)
        verify(consumer).setStartOffset(startOffset)
    }
  }

  val topicAndPartitionGen = for {
    topic <- Gen.alphaStr
    partition <- Gen.choose[Int](0, Int.MaxValue)
  } yield TopicAndPartition(topic, partition)
  property("FetchThread should only fetchMessage when the number " +
    "of messages in queue is below the threshold") {
    forAll(positiveGen, nonNegativeGen, nonNegativeGen, startOffsetGen, topicAndPartitionGen) {
      (messageNum: Int, fetchThreshold: Int, fetchSleepMS: Int,
        startOffset: Long, topicAndPartition: TopicAndPartition) =>
        val message = mock[KafkaMessage]
        val consumer = mock[KafkaConsumer]
        val createConsumer = (tp: TopicAndPartition) => consumer

        0.until(messageNum) foreach { _ =>
          when(consumer.hasNext).thenReturn(true)
          when(consumer.next()).thenReturn(message)
        }

        val sleeper = mock[ExponentialBackoffSleeper]
        val incomingQueue = new LinkedBlockingQueue[KafkaMessage]()
        val fetchThread = new FetchThread(createConsumer,
          incomingQueue, sleeper, fetchThreshold, fetchSleepMS)

        fetchThread.setTopicAndPartitions(Array(topicAndPartition))

        0.until(messageNum) foreach { _ =>
          fetchThread.runLoop()
        }

        verify(sleeper, times(messageNum)).reset()
        incomingQueue.size() shouldBe Math.min(messageNum, fetchThreshold)
    }
  }

  property("FetchThread poll should try to retrieve and remove the head of incoming queue") {
    val topicAndPartition = mock[TopicAndPartition]
    val consumer = mock[KafkaConsumer]
    val createConsumer = (tp: TopicAndPartition) => consumer
    val kafkaMsg = mock[KafkaMessage]
    val sleeper = mock[ExponentialBackoffSleeper]
    val incomingQueue = new LinkedBlockingQueue[KafkaMessage]()
    incomingQueue.put(kafkaMsg)
    val fetchThread = new FetchThread(createConsumer, incomingQueue, sleeper, 0, 0)
    fetchThread.setTopicAndPartitions(Array(topicAndPartition))
    fetchThread.poll shouldBe Some(kafkaMsg)
    fetchThread.poll shouldBe None
  }

  val tpAndHasNextGen = for {
    tp <- topicAndPartitionGen
    hasNext <- Gen.oneOf(true, false)
  } yield (tp, hasNext)

  val tpHasNextMapGen = Gen.listOf[(TopicAndPartition, Boolean)](tpAndHasNextGen)
    .map(_.toMap) suchThat (_.nonEmpty)

  property("FetchThread fetchMessage should return false when there are no more messages " +
    "from any TopicAndPartition") {
    forAll(tpHasNextMapGen, nonNegativeGen) {
      (tpHasNextMap: Map[TopicAndPartition, Boolean], fetchSleepMS: Int) =>
        val createConsumer = (tp: TopicAndPartition) => {
          val consumer = mock[KafkaConsumer]
          val kafkaMsg = mock[KafkaMessage]
          val hasNext = tpHasNextMap(tp)
          when(consumer.hasNext).thenReturn(hasNext)
          when(consumer.next()).thenReturn(kafkaMsg)
          consumer
        }
        val sleeper = mock[ExponentialBackoffSleeper]
        val incomingQueue = new LinkedBlockingQueue[KafkaMessage]()
        val fetchThread = new FetchThread(createConsumer, incomingQueue, sleeper,
          tpHasNextMap.size + 1, fetchSleepMS)
        fetchThread.setTopicAndPartitions(tpHasNextMap.keys.toArray)
        fetchThread.runLoop()
        val hasMoreMessages = tpHasNextMap.values.reduce(_ || _)
        if (!hasMoreMessages) {
          verify(sleeper).sleep(fetchSleepMS)
        }
    }
  }

  property("FetchThread should reset consumers on exception") {
    forAll(startOffsetGen) { (offset: Long) =>
      val topicAndPartition = mock[TopicAndPartition]
      val consumer = mock[KafkaConsumer]
      val createConsumer = (tp: TopicAndPartition) => consumer
      val sleeper = mock[ExponentialBackoffSleeper]
      val incomingQueue = new LinkedBlockingQueue[KafkaMessage]()
      val fetchThread = new FetchThread(createConsumer, incomingQueue, sleeper, 1, 0)
      fetchThread.setTopicAndPartitions(Array(topicAndPartition))

      when(consumer.hasNext).thenReturn(true)
      when(consumer.next()).thenThrow(new RuntimeException)
      fetchThread.runLoop()
      // sleep on exception
      verify(sleeper).sleep()

      // reset on previous exception
      when(consumer.getNextOffset).thenReturn(offset)
      when(consumer.hasNext).thenReturn(false)
      fetchThread.runLoop()
      verify(consumer).close()
      // consumer is reset
      verify(consumer).setStartOffset(offset)
      // reset sleeper on successful fetch
      verify(sleeper).reset()
    }
  }
}
