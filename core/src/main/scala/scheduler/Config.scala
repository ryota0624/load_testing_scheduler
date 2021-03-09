package scheduler

import io.circe.generic.auto._
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, HCursor}

sealed trait HookConfig {
  def `type`: String
}

object HookConfig {
  val typeJFRHook = "JFRHook"

  implicit val decodeHookConfig: Decoder[HookConfig] = (c: HCursor) => {
    for {
      configType <- c.downField("type").as[String]
      result <- configType match {
        case "JFRHook" =>
          c.value.as[JFRHookConfig]
      }
    } yield result
  }

  implicit val encodeHookConfig: Encoder[HookConfig] = (c: HookConfig) => {
    import io.circe.Json._
    val jsonMain = c match {
      case jfr: JFRHookConfig =>
        jfr.asJson
    }
    jsonMain.deepMerge(obj("type" -> fromString(c.`type`)))
  }

}

case class JFRHookConfig(
  javaProcessName:    String,
  javaContainerName:  String,
  jfrTemplateSetting: Option[String],
  recordingDuration:  String,
  distPathInPod:      String,
  distDir:            String
) extends HookConfig {
  override def `type`: String = HookConfig.typeJFRHook
}

case class TestingTarget(
  namespace:      String,
  deploymentName: String,
  podSelector:    (String, String)
)

case class TestingConfig(
  target:      TestingTarget,
  hookConfigs: Seq[HookConfig]
)
