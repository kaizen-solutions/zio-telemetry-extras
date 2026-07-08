import sbt.*

object Dependencies {
  private object org {
    val http4s        = "org.http4s"
    val openTelemetry = "io.opentelemetry"
    val zio           = "dev.zio"
  }

  private object version {
    val http4s        = "0.23.36"
    val openTelemetry = "1.63.0"
    val zio           = "2.1.26"
    val zioInterop    = "23.1.0.13"
    val zioTelemetry  = "3.1.18"
  }

  val http4s = Seq(
    org.http4s %% "http4s-server" % version.http4s,
    org.http4s %% "http4s-client" % version.http4s,
    org.http4s %% "http4s-dsl"    % version.http4s
  )

  val zio = Seq(
    org.zio %% "zio"               % version.zio,
    org.zio %% "zio-interop-cats"  % version.zioInterop,
    org.zio %% "zio-opentelemetry" % version.zioTelemetry,
    org.zio %% "zio-test"          % version.zio % Test,
    org.zio %% "zio-test-sbt"      % version.zio % Test
  )

  val openTelemetry = Seq(
    org.openTelemetry % "opentelemetry-sdk-testing" % version.openTelemetry % Test
  )
}
