package scheduler.cli

import io.circe.generic.auto._
import io.circe.parser.decode
import picocli.CommandLine
import picocli.CommandLine._
import scheduler.{AggregateRunner, AggregateRunnerConfig}

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

@Command(
  name                     = "scheduler",
  mixinStandardHelpOptions = true,
  version                  = Array("1.0"),
  description              = Array("run command with deployment restart")
)
class Scheduler extends Callable[Int] {
  import AggregateRunnerConfig._

  @Parameters(index = "0", description = Array("config file"))
  private var file: File = null

  override def call(): Int = {
    val fileContents = Files.readAllBytes(file.toPath)
    decode[AggregateRunnerConfig](fileContents.toString) match {
      case Left(error) =>
        print(error)
        1
      case Right(config) =>
        val runF = AggregateRunner(config).run()
        Await.result(runF, Duration.Inf)
        0
    }
  }
}

object Scheduler extends App {
  val exitCode = new CommandLine(new Scheduler()).execute(args: _*)
  System.exit(exitCode)
}
