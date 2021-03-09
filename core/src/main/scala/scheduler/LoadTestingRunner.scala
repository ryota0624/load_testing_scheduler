package scheduler

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import scheduler.hooks.JFRHook

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.reflect.io.Path
import scala.sys.process.{Process, ProcessLogger}

case class LoadTestingRunner(
  testingTargetConfig: TestingConfig,
  k8sClient:           KubernetesClient,
  hooks:               Seq[LoadTestingRunner.Hook]
) {

  def start(body: => Future[Unit])(implicit ctx: ExecutionContext): Future[Unit] = {
    val activationF       = new DeploymentActivationListener(k8sClient, testingTargetConfig.target.deploymentName).start()
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
      .inNamespace(testingTargetConfig.target.namespace)
      .withLabel(testingTargetConfig.target.podSelector._1, testingTargetConfig.target.podSelector._2)
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
      .inNamespace(testingTargetConfig.target.namespace)
      .withLabel(testingTargetConfig.target.podSelector._1, testingTargetConfig.target.podSelector._2)
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

object LoadTestingRunner {

  def apply(
    testingTargetConfig: TestingConfig,
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

  val testingTargetConfig = TestingConfig(
    TestingTarget(
      namespace      = "default",
      deploymentName = "api-server-deployment",
      podSelector    = ("app", "api-server")
    ),
    hookConfigs = Seq(
      JFRHookConfig(
        javaProcessName    = "api.server",
        javaContainerName  = "api-server",
        jfrTemplateSetting = None,
        recordingDuration  = "10s",
        distPathInPod      = "/dump/record.jfr",
        distDir            = Path(".").toAbsolute.toString()
      )
    )
  )

  def runShellCmd(config: TestingConfig)(
    cmd:                  String
  )(implicit k8sClient:   KubernetesClient, ctx: ExecutionContext): Future[Unit] = {
    val hooks = config.hookConfigs.map(HookResolver.resolve(_, k8sClient))
    val scheduler = LoadTestingRunner(
      config,
      k8sClient
    ).addHooks(hooks)
    scheduler.start {
      println(s"run cmd: ${cmd}")
      val result = Process(cmd) lazyLines ProcessLogger(Console.out.print, Console.err.print)
      result.foreach(Console.out.print)
      Future.successful()
    }
  }

  object HookResolver {

    def resolve(hookConfig: HookConfig, k8sClient: KubernetesClient): Hook = {
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
