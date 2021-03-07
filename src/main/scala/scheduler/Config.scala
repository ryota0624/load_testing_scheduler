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

case class TestingTargetConfig(
  namespace:      String,
  deploymentName: String,
  podSelector:    (String, String),
  hookConfigs:    Seq[HookConfig]
)
