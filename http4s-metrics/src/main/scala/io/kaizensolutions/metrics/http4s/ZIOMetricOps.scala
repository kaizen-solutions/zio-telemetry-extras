package io.kaizensolutions.metrics.http4s

import org.http4s.{Method, Status}
import org.http4s.metrics.{MetricsOps, TerminationType}
import org.http4s.metrics.TerminationType.{Abnormal, Canceled, Timeout}
import zio.*
import zio.metrics.*
import zio.metrics.MetricKeyType.Histogram

final case class ZIOMetricOpsConfig(
    metricPrefix: Option[String],
    metricLabels: Set[MetricLabel],
    histogramBoundaries: Histogram.Boundaries
)
object ZIOMetricOpsConfig {
  val default = ZIOMetricOpsConfig(
    metricPrefix = None,
    metricLabels = Set.empty[MetricLabel],
    histogramBoundaries = Histogram.Boundaries.fromChunk(
      Chunk(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10)
    )
  )
}

object ZIOMetricOps {
  def make[R, E](
      config: ZIOMetricOpsConfig
  ): MetricsOps[ZIO[R, E, *]] = new MetricsOps[ZIO[R, E, *]] {
    import config.*

    val prefix = metricPrefix.map(p => s"${p}_").getOrElse("")

    val responseDuration =
      Metric
        .histogram(s"${prefix}response_latency", histogramBoundaries)
        .tagged(metricLabels)
        .contramap[Long](_.toDouble / 1e9)

    val activeRequests =
      Metric
        .gauge(s"${prefix}active_requests")
        .contramap[Long](_.toDouble)
        .tagged(metricLabels)

    val requests =
      Metric
        .counter(s"${prefix}request_count")
        .tagged(metricLabels)

    val abnormalTerminations = Metric
      .counter(s"${prefix}abnormal_count")
      .tagged(metricLabels)

    val abnormalTerminationsDuration =
      Metric
        .histogram(s"${prefix}abnormal_latency", histogramBoundaries)
        .tagged(metricLabels)
        .contramap[Long](_.toDouble / 1e9)

    val ClassifierLabel      = "classifier"
    val PhaseLabel           = "phase"
    val MethodLabel          = "method"
    val TerminationTypeLabel = "termination_type"
    val CauseLabel           = "cause"
    val StatusLabel          = "status"
    val StatusBucketLabel    = "status_bucket"

    override def increaseActiveRequests(classifier: Option[String]): ZIO[R, E, Unit] =
      activeRequests.increment @@ ZIOAspect.tagged(classifier.map(ClassifierLabel -> _).toSeq*)

    override def decreaseActiveRequests(classifier: Option[String]): ZIO[R, E, Unit] =
      activeRequests.decrement @@ ZIOAspect.tagged(classifier.map(ClassifierLabel -> _).toSeq*)

    override def recordHeadersTime(
        method: Method,
        elapsed: Long,
        classifier: Option[String]
    ): ZIO[R, E, Unit] = {
      val additionalTags =
        classifier.map(ClassifierLabel -> _).toSeq ++ Seq(
          MethodLabel -> method.name,
          PhaseLabel  -> "headers"
        )
      responseDuration.update(elapsed) @@ ZIOAspect.tagged(additionalTags*)
    }

    override def recordTotalTime(
        method: Method,
        status: Status,
        elapsed: Long,
        classifier: Option[String]
    ): ZIO[R, E, Unit] = {
      val baseTags =
        classifier.map(ClassifierLabel -> _).toSeq :+
          (MethodLabel -> method.name)

      val requestTags = baseTags ++ Seq(
        MethodLabel -> method.name,
        StatusLabel -> status.code.toString()
      )
      val durationTags = baseTags ++ Seq(
        MethodLabel       -> method.name,
        StatusBucketLabel -> statusBucket(status),
        PhaseLabel        -> "body"
      )

      (requests.increment @@ ZIOAspect.tagged(requestTags*)) *>
        (responseDuration.update(elapsed) @@ ZIOAspect.tagged(durationTags*))
    }

    override def recordAbnormalTermination(
        elapsed: Long,
        terminationType: TerminationType,
        classifier: Option[String]
    ): ZIO[R, E, Unit] = {
      val termType = terminationType match {
        case Abnormal(_)              => "abnormal"
        case Canceled                 => "canceled"
        case TerminationType.Error(_) => "error"
        case Timeout                  => "timeout"
      }

      val cause = terminationType match {
        case Abnormal(rootCause)              => Option(rootCause.getClass().getName())
        case Canceled                         => None
        case TerminationType.Error(rootCause) => Option(rootCause.getClass().getName())
        case Timeout                          => None
      }

      val additionalTags =
        classifier.map(ClassifierLabel -> _).toSeq ++
          cause.map(CauseLabel -> _).toSeq ++
          Seq(TerminationTypeLabel -> termType)

      (abnormalTerminations.increment *> abnormalTerminationsDuration.update(elapsed)) @@
        ZIOAspect.tagged(additionalTags*)
    }

    private def statusBucket(status: Status): String =
      status.code match {
        case hundreds if hundreds < 200           => "1xx"
        case twohundreds if twohundreds < 300     => "2xx"
        case threehundreds if threehundreds < 400 => "3xx"
        case fourhundreds if fourhundreds < 500   => "4xx"
        case _                                    => "5xx"
      }
  }
}
