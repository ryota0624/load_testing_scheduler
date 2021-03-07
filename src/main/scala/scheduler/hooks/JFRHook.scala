package scheduler.hooks

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.ExecListener
import okhttp3.Response
import scheduler.JFRHookConfig
import scheduler.LoadTestingRunner.Hook

import java.io.ByteArrayOutputStream
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.reflect.io.Path

case class JFRHook(
                    k8sClient:        KubernetesClient,
                    jfrRunningConfig: JFRHookConfig,
                    recodingName:     String
) extends Hook {
  import JFRHook._

  override def beforeRun(
    pods:         Seq[Pod]
  )(implicit ctx: ExecutionContext): Future[Unit] = startJFR(pods)

  override def afterRun(
    pods:         Seq[Pod]
  )(implicit ctx: ExecutionContext): Future[Unit] = collectJFRFiles(pods)

  private def startJFR(
    pods: Seq[Pod]
  )(
    implicit ctx: ExecutionContext
  ): Future[Unit] = {
    val execJFR = for (pod <- pods) yield {
      val cmd = Seq(
        "jcmd",
        jfrRunningConfig.javaProcessName,
        "JFR.start",
        jfrRunningConfig.jfrTemplateSetting.getOrElse(""),
        s"filename=${jfrRunningConfig.distPathInPod}",
        s"name=$recodingName"
      )
      val execResult = pod.container(jfrRunningConfig.javaContainerName).exec(cmd: _*)(k8sClient)
      execResult.future
    }

    Future.sequence(execJFR).map(_ => ())
  }

  private def collectJFRFiles(
    pods: Seq[Pod]
  )(
    implicit ctx: ExecutionContext
  ): Future[Unit] = {
    val collects = for (pod <- pods) yield {
      val cmd = Seq(
        "jcmd",
        jfrRunningConfig.javaProcessName,
        "JFR.stop",
        s"name=$recodingName"
      )
      val result = pod.container(jfrRunningConfig.javaContainerName).exec(cmd: _*)(k8sClient)

      result.output.writeTo(Console.out)

      for (_ <- result.future) yield {
        k8sClient
          .pods()
          .inNamespace(pod.getMetadata.getNamespace)
          .withName(pod.getMetadata.getName)
          .inContainer(jfrRunningConfig.javaContainerName)
          .file(jfrRunningConfig.distPathInPod)
          .copy(Path(s"${jfrRunningConfig.distDir}/${pod.getMetadata.getName}.jfr").jfile.toPath)
      }

    }

    Future.sequence(collects).map(_ => ())
  }
}

object JFRHook {

  class Container(
    private val pod: Pod,
    val name:        String
  ) {

    def exec(args:        String*)(
      implicit k8sClient: KubernetesClient
    ): ExecResult = {
      val out        = new ByteArrayOutputStream
      val error      = new ByteArrayOutputStream
      val execFuture = new ExecFuture
      k8sClient
        .pods()
        .inNamespace(pod.getMetadata.getNamespace)
        .withName(pod.getMetadata.getName)
        .inContainer(name)
        .writingOutput(out)
        .writingError(error)
        .usingListener(execFuture)
        .exec(args: _*)

      ExecResult(execFuture.future, out, error)
    }
  }

  implicit class PodOps(val pod: Pod) {

    def container(name: String): Container = {
      require(
        pod.getSpec.getContainers.asScala.exists(container => container.getName == name),
        s"container($name) does not exists"
      )
      new Container(pod, name)
    }
  }
}

class ExecFuture extends ExecListener {
  private val promise = Promise[Unit]()
  val future: Future[Unit] = promise.future
  override def onOpen(response: Response): Unit = ()

  override def onFailure(t: Throwable, response: Response): Unit = {
    promise.failure(new RuntimeException(s"failed exec in pod", t))
  }

  override def onClose(code: Int, reason: String): Unit = {
    promise.success()
  }
}

case class ExecResult(
  future: Future[Unit],
  output: ByteArrayOutputStream,
  error:  ByteArrayOutputStream
)
