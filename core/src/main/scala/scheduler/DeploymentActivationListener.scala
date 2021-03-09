package scheduler

import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.{KubernetesClient, Watcher, WatcherException}

import scala.concurrent.{ExecutionContext, Future, Promise}

class DeploymentActivationListener(k8sClient: KubernetesClient, deploymentName: String) extends Watcher[Deployment] {
  private var initialized = false

  private val promise = Promise[Deployment]()

  private def asInitialized(): Unit = {
    initialized = true
  }

  def start()(implicit ctx: ExecutionContext): Future[Deployment] = {
    val watch = k8sClient
      .apps()
      .deployments()
      .withName(deploymentName)
      .watch(this)

    val f = promise.future
    f onComplete { _ => watch.close() }
    f
  }

  override def eventReceived(action: Watcher.Action, resource: Deployment): Unit = {
    val countOfDesired = resource.getSpec.getReplicas.toInt
    resource.getStatus.getAvailableReplicas.toInt match {
      case 0 =>
        asInitialized()
      case available if initialized && available == countOfDesired =>
        promise.success(resource)
      case _ => ()
    }
  }

  override def onClose(cause: WatcherException): Unit = ()
}
