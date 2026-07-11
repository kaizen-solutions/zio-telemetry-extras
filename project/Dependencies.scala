import sbt.*

object Dependencies {
  private object org {
    val http4s        = "org.http4s"
    val openTelemetry = "io.opentelemetry"
    val smithy4s      = "com.disneystreaming.smithy4s"
    val zio           = "dev.zio"
  }

  private object version {
    val http4s        = "0.23.36"
    val openTelemetry = "1.63.0"
    val smithy4s18    = "0.18.55"
    val smithy4s19    = "0.19.8"
    val zio           = "2.1.26"
    val zioInterop    = "23.1.0.13"
    val zioTelemetry  = "3.1.18"
  }

  object http4s {
    val core = Seq(org.http4s %% "http4s-core" % version.http4s)

    val all = Seq(
      org.http4s %% "http4s-server" % version.http4s,
      org.http4s %% "http4s-client" % version.http4s,
      org.http4s %% "http4s-dsl"    % version.http4s
    )
  }

  val zio = Seq(
    org.zio %% "zio"               % version.zio,
    org.zio %% "zio-interop-cats"  % version.zioInterop,
    org.zio %% "zio-opentelemetry" % version.zioTelemetry,
    org.zio %% "zio-test"          % version.zio % Test,
    org.zio %% "zio-test-sbt"      % version.zio % Test
  )

  object smithy4s {
    val `0.18.x` = Seq(org.smithy4s %% "smithy4s-http4s" % version.smithy4s18)
    val `0.19.x` = Seq(org.smithy4s %% "smithy4s-http4s" % version.smithy4s19)
  }

  val openTelemetry = Seq(
    org.openTelemetry % "opentelemetry-sdk-testing" % version.openTelemetry % Test
  )
}
