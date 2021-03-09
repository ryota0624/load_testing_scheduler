package scheduler.cli

import picocli.CommandLine
import picocli.CommandLine._
import scheduler.{AggregateRunner, AggregateRunnerConfig, LoadTestingRunner}
import scheduler.LoadTestingRunner.testingTargetConfig

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}

@Command(
  name                     = "checksum",
  mixinStandardHelpOptions = true,
  version                  = Array("checksum 4.0"),
  description              = Array("Prints the checksum (MD5 by default) of a file to STDOUT.")
)
class Scheduler extends Callable[Int] {

  @Parameters(index = "0", description = Array("config file"))
  private var file: File = null
  import io.circe.syntax._

  override def call(): Int = {
    val fileContents = Files.readAllBytes(file.toPath)
    import io.circe.generic.auto._
    import io.circe.parser.decode
    import io.circe.syntax._
    import io.circe.generic.auto._

    val c = AggregateRunnerConfig(
      cmdTemplate = "echo {{ name }}",
      variables = Seq(
        Map("name" -> "jon"),
        Map("name" -> "mic")
      ),
      testingTargetConfig = LoadTestingRunner.testingTargetConfig,
      testingDuration     = 10 seconds
    ).asJson

    println(c)

    decode[AggregateRunner.Config](fileContents.toString) match {
      case Left(value) =>
        print(value)
        1
      case Right(value) =>
        val runF = AggregateRunner(
          value
          //      AggregateRunner.Config(
          //        cmdTemplate = "echo {{ name }}",
          //        variables = Seq(
          //          Map("name" -> "jon"),
          //          Map("name" -> "mic")
          //        ),
          //        testingTargetConfig = LoadTestingRunner.testingTargetConfig,
          //        testingDuration     = 10 seconds
          //      )
        ).run()
        Await.result(runF, Duration.Inf)
        0
    }
  }
}

object Scheduler extends App {
  val exitCode = new CommandLine(new Scheduler()).execute(args: _*)
  System.exit(exitCode)
}
