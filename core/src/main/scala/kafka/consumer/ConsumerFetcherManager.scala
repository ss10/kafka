/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.consumer

import org.I0Itec.zkclient.ZkClient
import kafka.server.{AbstractFetcherThread, AbstractFetcherManager}
import kafka.cluster.{Cluster, Broker}
import scala.collection.immutable
import collection.mutable.HashMap
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import kafka.utils.ZkUtils._
import kafka.utils.{ShutdownableThread, SystemTime}
import kafka.common.TopicAndPartition
import kafka.client.ClientUtils
import java.util.concurrent.atomic.AtomicInteger

/**
 *  Usage:
 *  Once ConsumerFetcherManager is created, startConnections() and stopAllConnections() can be called repeatedly
 *  until shutdown() is called.
 */
class ConsumerFetcherManager(private val consumerIdString: String,
                             private val config: ConsumerConfig,
                             private val zkClient : ZkClient)
        extends AbstractFetcherManager("ConsumerFetcherManager-%d".format(SystemTime.milliseconds), 1) {
  private var partitionMap: immutable.Map[TopicAndPartition, PartitionTopicInfo] = null
  private var cluster: Cluster = null
  private val noLeaderPartitionSet = new mutable.HashSet[TopicAndPartition]
  private val lock = new ReentrantLock
  private val cond = lock.newCondition()
  private var leaderFinderThread: ShutdownableThread = null
  private val correlationId = new AtomicInteger(0)

  private class LeaderFinderThread(name: String) extends ShutdownableThread(name) {
    // thread responsible for adding the fetcher to the right broker when leader is available
    override def doWork() {
      lock.lock()
      try {
        if (noLeaderPartitionSet.isEmpty) {
          trace("No partition for leader election.")
          cond.await()
        }

        try {
          trace("Partitions without leader %s".format(noLeaderPartitionSet))
          val brokers = getAllBrokersInCluster(zkClient)
          val topicsMetadata = ClientUtils.fetchTopicMetadata(noLeaderPartitionSet.map(m => m.topic).toSet,
                                                              brokers,
                                                              config.clientId,
                                                              config.socketTimeoutMs,
                                                              correlationId.getAndIncrement).topicsMetadata
          if(logger.isDebugEnabled) topicsMetadata.foreach(topicMetadata => debug(topicMetadata.toString()))
          val leaderForPartitionsMap = new HashMap[TopicAndPartition, Broker]
          topicsMetadata.foreach { tmd =>
            val topic = tmd.topic
            tmd.partitionsMetadata.foreach { pmd =>
              val topicAndPartition = TopicAndPartition(topic, pmd.partitionId)
              if(pmd.leader.isDefined && noLeaderPartitionSet.contains(topicAndPartition)) {
                val leaderBroker = pmd.leader.get
                leaderForPartitionsMap.put(topicAndPartition, leaderBroker)
              }
            }
          }

          leaderForPartitionsMap.foreach {
            case(topicAndPartition, leaderBroker) =>
              val pti = partitionMap(topicAndPartition)
              try {
                  addFetcher(topicAndPartition.topic, topicAndPartition.partition, pti.getFetchOffset(), leaderBroker)
                  noLeaderPartitionSet -= topicAndPartition
              } catch {
                case t => {
                  /*
                   * If we are shutting down (e.g., due to a rebalance) propagate this exception upward to avoid
                   * processing subsequent partitions without leader so the leader-finder-thread can exit.
                   * It is unfortunate that we depend on the following behavior and we should redesign this: as part of
                   * processing partitions, we catch the InterruptedException (thrown from addPartition's call to
                   * lockInterruptibly) when adding partitions, thereby clearing the interrupted flag. If we process
                   * more partitions, then the lockInterruptibly in addPartition will not throw an InterruptedException
                   * and we can run into the deadlock described in KAFKA-914.
                   */
                  if (!isRunning.get())
                    throw t
                  else
                    warn("Failed to add fetcher for %s to broker %s".format(topicAndPartition, leaderBroker), t)
                }
              }
          }

          shutdownIdleFetcherThreads()
        } catch {
          case t => {
            if (!isRunning.get())
              throw t /* See above for why we need to propagate this exception. */
            else
              warn("Failed to find leader for %s".format(noLeaderPartitionSet), t)
          }
        }
      } finally {
        lock.unlock()
      }
      Thread.sleep(config.refreshLeaderBackoffMs)
    }
  }

  override def createFetcherThread(fetcherId: Int, sourceBroker: Broker): AbstractFetcherThread = {
    new ConsumerFetcherThread(
      "ConsumerFetcherThread-%s-%d-%d".format(consumerIdString, fetcherId, sourceBroker.id),
      config, sourceBroker, partitionMap, this)
  }

  def startConnections(topicInfos: Iterable[PartitionTopicInfo], cluster: Cluster) {
    leaderFinderThread = new LeaderFinderThread(consumerIdString + "-leader-finder-thread")
    leaderFinderThread.start()

    lock.lock()
    try {
      partitionMap = topicInfos.map(tpi => (TopicAndPartition(tpi.topic, tpi.partitionId), tpi)).toMap
      this.cluster = cluster
      noLeaderPartitionSet ++= topicInfos.map(tpi => TopicAndPartition(tpi.topic, tpi.partitionId))
      cond.signalAll()
    } finally {
      lock.unlock()
    }
  }

  def stopConnections() {
    /*
     * Stop the leader finder thread first before stopping fetchers. Otherwise, if there are more partitions without
     * leader, then the leader finder thread will process these partitions (before shutting down) and add fetchers for
     * these partitions.
     */
    info("Stopping leader finder thread")
    if (leaderFinderThread != null) {
      leaderFinderThread.shutdown()
      leaderFinderThread = null
    }

    info("Stopping all fetchers")
    closeAllFetchers()

    // no need to hold the lock for the following since leaderFindThread and all fetchers have been stopped
    partitionMap = null
    noLeaderPartitionSet.clear()

    info("All connections stopped")
  }

  def addPartitionsWithError(partitionList: Iterable[TopicAndPartition]) {
    debug("adding partitions with error %s".format(partitionList))
    lock.lock()
    try {
      if (partitionMap != null) {
        noLeaderPartitionSet ++= partitionList
        cond.signalAll()
      }
    } finally {
      lock.unlock()
    }
  }
}