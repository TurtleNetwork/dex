package com.wavesplatform.dex.queue

import java.util.concurrent.Executors
import java.util.{Timer, TimerTask}

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.wavesplatform.dex.db.LocalQueueStore
import com.wavesplatform.dex.domain.utils.ScorexLogging
import com.wavesplatform.dex.queue.LocalMatcherQueue._
import com.wavesplatform.dex.queue.MatcherQueue.{IgnoreProducer, Producer}
import com.wavesplatform.dex.queue.ValidatedCommandWithMeta.Offset
import com.wavesplatform.dex.time.Time

import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

class LocalMatcherQueue(settings: Settings, store: LocalQueueStore, time: Time) extends MatcherQueue with ScorexLogging {

  @volatile private var lastUnreadOffset: ValidatedCommandWithMeta.Offset = -1L

  private val executor = Executors.newSingleThreadExecutor {
    new ThreadFactoryBuilder()
      .setDaemon(false)
      .setNameFormat("queue-local-consumer-%d")
      .build()
  }

  implicit private val executionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(executor)

  private val timer = new Timer("local-dex-queue", true)

  private val producer: Producer = {
    val r = if (settings.enableStoring) new LocalProducer(store, time) else IgnoreProducer
    log.info(s"Choosing ${r.getClass.getName} producer")
    r
  }

  override def startConsume(fromOffset: ValidatedCommandWithMeta.Offset, process: Seq[ValidatedCommandWithMeta] => Future[Unit]): Unit = {
    if (settings.cleanBeforeConsume) store.dropUntil(fromOffset)

    def runOnce(from: ValidatedCommandWithMeta.Offset): Future[ValidatedCommandWithMeta.Offset] = {
      val requests = store.getFrom(from, settings.maxElementsPerPoll)
      if (requests.isEmpty) Future.successful(from)
      else {
        val newOffset = requests.last.offset + 1
        log.trace(s"Read ${newOffset - from} commands")
        process(requests).map(_ => newOffset)
      }
    }

    val pollingInterval = settings.pollingInterval.toNanos
    def loop(from: ValidatedCommandWithMeta.Offset): Unit = {
      val start = System.nanoTime()
      runOnce(from)
        .recover {
          case NonFatal(e) =>
            // Actually this should not happen. The Future[_] type is not powerful to express error-less computations
            log.error("Can't process messages, trying again", e)
            from
        }
        .map { nextStartOffset =>
          lastUnreadOffset = nextStartOffset
          val diff = System.nanoTime() - start
          val delay = math.max(pollingInterval - diff, 0L) / 1000000 // to millis
          timer.schedule(
            new TimerTask {
              override def run(): Unit = loop(lastUnreadOffset)
            },
            delay
          )
        }
    }

    loop(fromOffset)
  }

  override def store(command: ValidatedCommand): Future[Option[ValidatedCommandWithMeta]] = producer.store(command)

  override def firstOffset: Future[Offset] = Future(store.oldestOffset.getOrElse(-1L))

  override def lastOffset: Future[ValidatedCommandWithMeta.Offset] = Future(store.newestOffset.getOrElse(-1L))

  override def close(timeout: FiniteDuration): Future[Unit] =
    Future {
      blocking {
        timer.cancel()
        producer.close(timeout)
        executor.shutdown()
      }
    }(scala.concurrent.ExecutionContext.global)

}

object LocalMatcherQueue {
  case class Settings(enableStoring: Boolean, pollingInterval: FiniteDuration, maxElementsPerPoll: Int, cleanBeforeConsume: Boolean)

  private class LocalProducer(store: LocalQueueStore, time: Time) extends Producer {

    private val executor = Executors.newSingleThreadExecutor {
      new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("queue-local-producer-%d")
        .build()
    }

    implicit private val executionContext: ExecutionContextExecutor = ExecutionContext.fromExecutor(executor)

    override def store(command: ValidatedCommand): Future[Option[ValidatedCommandWithMeta]] = {
      val p = Promise[ValidatedCommandWithMeta]()
      // Need to guarantee the order
      executor.submit(new Runnable {
        override def run(): Unit = {
          val ts = time.correctedTime()
          val offset = store.enqueue(command, time.correctedTime())
          p.success(ValidatedCommandWithMeta(offset, ts, command))
        }
      })
      p.future.map(Some(_))
    }

    override def close(timeout: FiniteDuration): Unit = executor.shutdown()
  }

}
