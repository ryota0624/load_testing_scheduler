package scheduler

import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}
import io.fabric8.kubernetes.client.DefaultKubernetesClient

import java.io.{ByteArrayOutputStream, StringReader}
import java.util
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class AggregateRunner(
  config:    AggregateRunner.Config,
  k8sClient: DefaultKubernetesClient
) {
  import com.github.mustachejava.{DefaultMustacheFactory, Mustache}

  import java.io.PrintWriter

  private def executeSequentially(ops: Seq[() => Future[Unit]])(
    implicit ctx:                      ExecutionContext
  ): Future[Unit] =
    ops.foldLeft(Future.successful(()))((cur, next) => cur.flatMap(_ => next()))

  def run()(implicit ctx: ExecutionContext): Future[Unit] = {
    val mf = new DefaultMustacheFactory
    val cmdTemplate: Mustache = mf.compile(new StringReader(config.cmdTemplate), "cmd-template")
    val cmdSeq = config.variables.map(fillTemplate(cmdTemplate, _))
    val runLoadTestings =
      cmdSeq.map(cmd => () => LoadTestingRunner.runShellCmd(config.testingTargetConfig)(cmd)(k8sClient, ctx))
    executeSequentially(runLoadTestings)
  }

  private def fillTemplate(template: Mustache, variable: Map[String, String]): String = {
    val out   = new ByteArrayOutputStream()
    val scope = new util.HashMap[String, String]()
    variable.foreach {
      case (key, value) =>
        scope.put(key, value)
    }
    template.execute(new PrintWriter(out), scope).flush()
    out.toString
  }
}

case class AggregateRunnerConfig(
  cmdTemplate:         String,
  variables:           Seq[Map[String, String]],
  testingDuration:     Duration,
  testingTargetConfig: TestingConfig
)

object AggregateRunnerConfig {
  implicit val durationDec: Decoder[Duration] = _.as[String].map(Duration.apply)
  implicit val durationEnd: Encoder[Duration] = (a: Duration) => Json.fromString(s"${a.toMillis} ms")
}

object AggregateRunner extends App {
  type Config = AggregateRunnerConfig

  def apply(config: Config): AggregateRunner = {
    AggregateRunner(config, new DefaultKubernetesClient)
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  val aggregateRunnerConfig = AggregateRunnerConfig(
    cmdTemplate = "echo {{ name }}",
    variables = Seq(
      Map("name" -> "jon"),
      Map("name" -> "mic")
    ),
    testingTargetConfig = LoadTestingRunner.testingTargetConfig,
    testingDuration     = 10 seconds
  )
  import AggregateRunnerConfig._
  import HookConfig._
  import io.circe.generic.auto._


  println(aggregateRunnerConfig.asJson.toString())

  val k8sClient = new DefaultKubernetesClient()

  val runner = AggregateRunner(
    aggregateRunnerConfig,
    k8sClient
  )

  val runF = runner.run()

  runF onComplete {
    case Success(_) =>
      k8sClient.close()
      System.exit(0)
    case Failure(exception) =>
      k8sClient.close()
      exception.printStackTrace()
      System.exit(1)
  }

  Await.result(runF, Duration.Inf)
}
