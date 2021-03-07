package scheduler

import io.fabric8.kubernetes.client.DefaultKubernetesClient
import org.fusesource.scalate.TemplateEngine

import scala.concurrent.{ExecutionContext, Future}

class AggregateRunner {
  val templateEngine = new TemplateEngine

  def run(config: AggregateRunner.Config)(implicit k8sClient: DefaultKubernetesClient, ctx: ExecutionContext) = {
//    implicit val k8sClient: DefaultKubernetesClient = new DefaultKubernetesClient()
    val cmdTemplate     = templateEngine.compileMoustache(config.cmdTemplate)
    val cmdSeq          = config.variables.map(templateEngine.layout(cmdTemplate.source, _))
    val runLoadTestings = cmdSeq.map(cmd => () => LoadTestingRunner.runShellCmd(???)(cmd))
    runLoadTestings.foldLeft(Future.successful())((pre, cmd) => pre.flatMap(_ => cmd()))
  }
}

object AggregateRunner {

  case class Config(
    cmdTemplate: String,
    variables:   Seq[Map[String, Any]]
  )
}
