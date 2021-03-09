package scheduler

sealed trait HookConfig

case class JFRHookConfig(
  javaProcessName:    String,
  javaContainerName:  String,
  jfrTemplateSetting: Option[String],
  recordingDuration:  String,
  distPathInPod:      String,
  distDir:            String
) extends HookConfig

case class TestingTarget(
  namespace:      String,
  deploymentName: String,
  podSelector:    (String, String)
)

case class TestingConfig(
  target:      TestingTarget,
  hookConfigs: Seq[HookConfig]
)
