package scheduler

import java.io.ByteArrayOutputStream
import java.util.{Timer, TimerTask}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object StreamListeningTimer {

  class Task(
    timerName:     String,
    retryCount:    Int,
    maxRetryCount: Int,
    promise:       Promise[Unit],
    out:           ByteArrayOutputStream,
    error:         ByteArrayOutputStream,
    predicate:     String => Boolean,
    interval:      Duration
  ) extends TimerTask {

    private def ensureRetrievable(): Try[Unit] = Try {
      if (retryCount == maxRetryCount) {
        throw new IllegalStateException("retry count exceeded")
      }
    }

    override def run(): Unit = {
      if (predicate(out.toString)) {
        promise.success(())
      } else if (error.size() >= 0 && error.toString.replace(" ", "").replace("\n", "").nonEmpty) {
        promise.failure(new RuntimeException(error.toString))
      } else {
        ensureRetrievable() match {
          case Failure(exception) =>
            promise.failure(exception)
          case Success(_) =>
            new Timer(timerName).schedule(
              new Task(
                timerName,
                retryCount + 1,
                maxRetryCount,
                promise,
                out,
                error,
                predicate,
                interval
              ),
              interval.toMillis
            )
        }
      }
    }
  }
}

case class StreamListeningTimer(
  timerName:     String,
  predicate:     String => Boolean,
  maxRetryCount: Int = 10,
  interval:      Duration = 1.seconds
) {

  var retryCount = 0

  def start(
    out:   ByteArrayOutputStream,
    error: ByteArrayOutputStream
  ): Future[Unit] = {
    val promise = Promise[Unit]()
    val check = new StreamListeningTimer.Task(
      timerName,
      retryCount + 1,
      maxRetryCount,
      promise,
      out,
      error,
      predicate,
      interval
    )

    new Timer(timerName).schedule(check, interval.toMillis)
    promise.future
  }

}
