package scheduler

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{DefaultKubernetesClient, KubernetesClient}
import scheduler.hooks.JFRHook

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.reflect.io.Path
import scala.sys.process.{Process, ProcessLogger}
import scala.util.{Failure, Success}

case class LoadTestingRunner(
  testingTargetConfig: TestingTargetConfig,
  k8sClient:           KubernetesClient,
  hooks:               Seq[LoadTestingRunner.Hook]
) {

  def start(body: => Future[Unit])(implicit ctx: ExecutionContext): Future[Unit] = {
    val activationF       = new DeploymentActivationListener(k8sClient, testingTargetConfig.deploymentName).start()
    val pods              = findPods()
    val willBeDeletedPods = pods.map(_.getMetadata.getName)
    println(s"will be deleted pods: s${willBeDeletedPods.mkString(",")}")
    deletePods()
    for {
      _ <- activationF
      activePods = findPods().filterNot(pod => willBeDeletedPods.contains(pod.getMetadata.getName))
      _          = println(s"raised pods: ${activePods.map(_.getMetadata.getName).mkString(",")}")
      _ <- Future.sequence(hooks.map(_.beforeRun(activePods.toSeq)))
      _ <- body
      _ <- Future.sequence(hooks.map(_.afterRun(activePods.toSeq)))
    } yield ()
  }

  private def deletePods(): Unit = {
    val result = k8sClient
      .pods()
      .inNamespace(testingTargetConfig.namespace)
      .withLabel(testingTargetConfig.podSelector._1, testingTargetConfig.podSelector._2)
      .delete()
    if (result == false || result == null) {
      throw new IllegalStateException(
        s"not found pods namespace($testingTargetConfig.namespace) podSelector($testingTargetConfig.podSelector)"
      )
    }
  }

  private def findPods(): mutable.Seq[Pod] = {
    k8sClient
      .pods()
      .inNamespace(testingTargetConfig.namespace)
      .withLabel(testingTargetConfig.podSelector._1, testingTargetConfig.podSelector._2)
      .list()
      .getItems
      .asScala
  }

  def addHook(hook: LoadTestingRunner.Hook): LoadTestingRunner = {
    copy(hooks = hooks :+ hook)
  }

  def addHooks(hooks: Seq[LoadTestingRunner.Hook]): LoadTestingRunner = {
    hooks.foldLeft(this)(_.addHook(_))
  }

}

object LoadTestingRunner extends App {

  def apply(
    testingTargetConfig: TestingTargetConfig,
    k8sClient:           KubernetesClient
  ): LoadTestingRunner = {
    LoadTestingRunner(testingTargetConfig = testingTargetConfig, k8sClient = k8sClient, hooks = Nil)
  }

  trait Hook {

    def beforeRun(
      pods:         Seq[Pod]
    )(implicit ctx: ExecutionContext): Future[Unit]

    def afterRun(
      pods:         Seq[Pod]
    )(implicit ctx: ExecutionContext): Future[Unit]
  }

  import concurrent.ExecutionContext.Implicits.global

  implicit val k8sClient: DefaultKubernetesClient = new DefaultKubernetesClient()

  runShellCmd(
    TestingTargetConfig(
      namespace      = "default",
      deploymentName = "api-server-deployment",
      podSelector    = ("app", "api-server"),
      hookConfigs = Seq(
        JFRHookConfig(
          javaProcessName    = "api.SessionServer",
          javaContainerName  = "api-server",
          jfrTemplateSetting = None,
          recordingDuration  = "10s",
          distPathInPod      = "/dump/record.jfr",
          distDir            = Path(".").toAbsolute.toString()
        )
      )
    )
  )(args.head) onComplete {
    case Success(_) =>
      k8sClient.close()
      System.exit(0)
    case Failure(exception) =>
      k8sClient.close()
      exception.printStackTrace()
      System.exit(1)
  }

  def runShellCmd(config: TestingTargetConfig)(
    cmd:                  String
  )(implicit k8sClient:   KubernetesClient): Future[Unit] = {
    val hooks = config.hookConfigs.map(HookResolver.resolve)
    val scheduler = LoadTestingRunner(
      config,
      k8sClient
    ).addHooks(hooks)
    scheduler.start {
      Process(cmd) !! ProcessLogger(Console.out.print, Console.err.print)
      Future.successful()
    }
  }

  object HookResolver {

    def resolve(hookConfig: HookConfig): Hook = {
      hookConfig match {
        case c: JFRHookConfig =>
          JFRHook(
            k8sClient        = k8sClient,
            jfrRunningConfig = c,
            recodingName     = Instant.now().toString
          )
      }
    }
  }
}
