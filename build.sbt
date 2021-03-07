name := "load_test_scheduler"

version := "0.1"

scalaVersion := "2.13.3"

libraryDependencies += "com.typesafe" % "config" % "1.4.1"
libraryDependencies += "io.kubernetes"              % "client-java"       % "10.0.0"
libraryDependencies += "ch.qos.logback"             % "logback-classic"   % "1.2.3"
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging"    % "3.9.2"
libraryDependencies += "io.fabric8"                 % "kubernetes-client" % "5.1.1"
libraryDependencies +=
  "org.scalatra.scalate" %% "scalate-core" % "1.9.6"
enablePlugins(NativeImagePlugin)


nativeImageOptions ++= List(
  "--initialize-at-build-time",
  "--no-fallback",
  "--no-server",
  "--enable-http",
  "--enable-https",
  "--enable-url-protocols=http,https",
  "--enable-all-security-services",
  "-H:+JNI",
  "-H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.msg.XMLMessages",
  "--allow-incomplete-classpath",
  "--report-unsupported-elements-at-runtime",
  "-H:ReflectionConfigurationResources=META-INF/native-image/reflect-config.json",
  "--initialize-at-run-time" + Seq(
    "com.typesafe.config.impl.ConfigImpl",
    "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
    "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",
    "com.typesafe.config.impl.ConfigImpl$LoaderCacheHolder",
    "io.fabric8.kubernetes.client.internal.CertUtils$1",
  ).mkString("=", ",", "")
)
